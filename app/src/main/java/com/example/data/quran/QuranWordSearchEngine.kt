package com.example.data.quran

import androidx.compose.runtime.Immutable
import com.example.data.integrity.VerifiedDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

@Immutable
data class AyahWordHit(
  val verseKey: String,
  val surahId: Int,
  val ayahNumber: Int,
  val pageNumber: Int,
  val originalText: String,
  val highlightRanges: List<IntRange>,
) {
  val matchCount: Int
    get() = highlightRanges.size
}

@Immutable
data class SurahSearchGroup(
  val surahId: Int,
  val surahName: String,
  val occurrenceCount: Int,
  val hits: List<AyahWordHit>,
)

@Immutable
data class QuranWordSearchResult(
  val query: String,
  val normalizedQuery: String,
  val isSubstringMode: Boolean,
  val totalOccurrences: Int,
  val totalMatchingAyahs: Int,
  val surahCount: Int,
  val groups: List<SurahSearchGroup>,
  val previewHits: List<AyahWordHit>,
)

internal class IndexedWordToken(
  val startInOriginal: Int,
  val endInOriginal: Int,
  val normKey: String,
  val origCharIndices: IntArray,
)

internal class IndexedAyah(
  val verseKey: String,
  val surahId: Int,
  val ayahNumber: Int,
  val pageNumber: Int,
  val originalText: String,
  val tokens: List<IndexedWordToken>,
)

internal class QuranWordSearchIndex(
  val ayahs: List<IndexedAyah>,
  val wordToAyahIndices: Map<String, IntArray>,
  val loadedPageCount: Int,
) {
  val isComplete: Boolean get() = loadedPageCount >= 604

  fun search(
    rawQuery: String,
    isSubstring: Boolean,
    surahsById: Map<Int, SurahMeta>,
  ): QuranWordSearchResult {
    val trimmed = rawQuery.trim()
    if (trimmed.isEmpty()) {
      return emptyResult(rawQuery = rawQuery, normalizedQuery = "", isSubstring = isSubstring)
    }

    val queryWords = trimmed
      .split(WHITESPACE_REGEX)
      .map { QuranWordSearchNormalizer.normalizeKey(it) }
      .filter { it.isNotEmpty() }

    if (queryWords.isEmpty()) {
      return emptyResult(rawQuery = rawQuery, normalizedQuery = "", isSubstring = isSubstring)
    }

    val normalizedJoined = queryWords.joinToString(" ")
    val matchedAyahHits = ArrayList<AyahWordHit>()
    var totalOccurrences = 0

    if (!isSubstring && queryWords.size == 1) {
      val targetKey = queryWords[0]
      val candidateIndices = wordToAyahIndices[targetKey]
      if (candidateIndices != null) {
        for (ayahIdx in candidateIndices) {
          val ayah = ayahs[ayahIdx]
          val ranges = ArrayList<IntRange>(2)
          for (tok in ayah.tokens) {
            if (tok.normKey == targetKey) {
              ranges += tok.startInOriginal until tok.endInOriginal
            }
          }
          if (ranges.isNotEmpty()) {
            totalOccurrences += ranges.size
            matchedAyahHits += AyahWordHit(
              verseKey = ayah.verseKey,
              surahId = ayah.surahId,
              ayahNumber = ayah.ayahNumber,
              pageNumber = ayah.pageNumber,
              originalText = ayah.originalText,
              highlightRanges = ranges,
            )
          }
        }
      }
    } else if (!isSubstring) {
      val firstWord = queryWords[0]
      val candidateIndices = wordToAyahIndices[firstWord]
      val k = queryWords.size
      if (candidateIndices != null) {
        for (ayahIdx in candidateIndices) {
          val ayah = ayahs[ayahIdx]
          val tokens = ayah.tokens
          if (tokens.size < k) continue
          val ranges = ArrayList<IntRange>(1)
          var i = 0
          while (i <= tokens.size - k) {
            var allEqual = true
            for (j in 0 until k) {
              if (tokens[i + j].normKey != queryWords[j]) {
                allEqual = false
                break
              }
            }
            if (allEqual) {
              ranges += tokens[i].startInOriginal until tokens[i + k - 1].endInOriginal
              i += k
            } else {
              i++
            }
          }
          if (ranges.isNotEmpty()) {
            totalOccurrences += ranges.size
            matchedAyahHits += AyahWordHit(
              verseKey = ayah.verseKey,
              surahId = ayah.surahId,
              ayahNumber = ayah.ayahNumber,
              pageNumber = ayah.pageNumber,
              originalText = ayah.originalText,
              highlightRanges = ranges,
            )
          }
        }
      }
    } else if (queryWords.size == 1) {
      val targetSub = queryWords[0]
      for (ayah in ayahs) {
        var ranges: ArrayList<IntRange>? = null
        for (tok in ayah.tokens) {
          var searchFrom = 0
          while (searchFrom <= tok.normKey.length - targetSub.length) {
            val foundIdx = tok.normKey.indexOf(targetSub, startIndex = searchFrom)
            if (foundIdx < 0) break
            val origStart = tok.origCharIndices[foundIdx]
            val lastOrigChar = tok.origCharIndices[foundIdx + targetSub.length - 1]
            var origEndExcl = lastOrigChar + 1
            while (
              origEndExcl < tok.endInOriginal &&
              QuranWordSearchNormalizer.isStrippedQuranChar(ayah.originalText[origEndExcl])
            ) {
              origEndExcl++
            }
            if (ranges == null) ranges = ArrayList(2)
            ranges += origStart until origEndExcl
            searchFrom = foundIdx + targetSub.length
          }
        }
        if (ranges != null && ranges.isNotEmpty()) {
          totalOccurrences += ranges.size
          matchedAyahHits += AyahWordHit(
            verseKey = ayah.verseKey,
            surahId = ayah.surahId,
            ayahNumber = ayah.ayahNumber,
            pageNumber = ayah.pageNumber,
            originalText = ayah.originalText,
            highlightRanges = ranges,
          )
        }
      }
    } else {
      val k = queryWords.size
      for (ayah in ayahs) {
        val tokens = ayah.tokens
        if (tokens.size < k) continue
        var ranges: ArrayList<IntRange>? = null
        var i = 0
        while (i <= tokens.size - k) {
          val firstTok = tokens[i]
          val lastTok = tokens[i + k - 1]
          val firstSub = queryWords[0]
          val lastSub = queryWords[k - 1]
          var middleOk = firstTok.normKey.endsWith(firstSub) && lastTok.normKey.startsWith(lastSub)
          if (middleOk) {
            for (j in 1 until k - 1) {
              if (tokens[i + j].normKey != queryWords[j]) {
                middleOk = false
                break
              }
            }
          }
          if (middleOk) {
            val startPosInFirst = firstTok.normKey.length - firstSub.length
            val origStart = firstTok.origCharIndices[startPosInFirst]
            val lastOrigChar = lastTok.origCharIndices[lastSub.length - 1]
            var origEndExcl = lastOrigChar + 1
            while (
              origEndExcl < lastTok.endInOriginal &&
              QuranWordSearchNormalizer.isStrippedQuranChar(ayah.originalText[origEndExcl])
            ) {
              origEndExcl++
            }
            if (ranges == null) ranges = ArrayList(1)
            ranges += origStart until origEndExcl
            i += k
          } else {
            i++
          }
        }
        if (ranges != null && ranges.isNotEmpty()) {
          totalOccurrences += ranges.size
          matchedAyahHits += AyahWordHit(
            verseKey = ayah.verseKey,
            surahId = ayah.surahId,
            ayahNumber = ayah.ayahNumber,
            pageNumber = ayah.pageNumber,
            originalText = ayah.originalText,
            highlightRanges = ranges,
          )
        }
      }
    }

    if (matchedAyahHits.isEmpty()) {
      return emptyResult(rawQuery = rawQuery, normalizedQuery = normalizedJoined, isSubstring = isSubstring)
    }

    val groups = ArrayList<SurahSearchGroup>()
    var currentSurahId = -1
    var currentHits = ArrayList<AyahWordHit>()
    var currentOccurrences = 0

    for (hit in matchedAyahHits) {
      if (hit.surahId != currentSurahId) {
        if (currentSurahId > 0 && currentHits.isNotEmpty()) {
          val name = surahsById[currentSurahId]?.nameArabic ?: currentSurahId.toString()
          groups += SurahSearchGroup(
            surahId = currentSurahId,
            surahName = name,
            occurrenceCount = currentOccurrences,
            hits = currentHits,
          )
        }
        currentSurahId = hit.surahId
        currentHits = ArrayList()
        currentOccurrences = 0
      }
      currentHits += hit
      currentOccurrences += hit.matchCount
    }
    if (currentSurahId > 0 && currentHits.isNotEmpty()) {
      val name = surahsById[currentSurahId]?.nameArabic ?: currentSurahId.toString()
      groups += SurahSearchGroup(
        surahId = currentSurahId,
        surahName = name,
        occurrenceCount = currentOccurrences,
        hits = currentHits,
      )
    }

    return QuranWordSearchResult(
      query = rawQuery,
      normalizedQuery = normalizedJoined,
      isSubstringMode = isSubstring,
      totalOccurrences = totalOccurrences,
      totalMatchingAyahs = matchedAyahHits.size,
      surahCount = groups.size,
      groups = groups,
      previewHits = matchedAyahHits.take(20),
    )
  }

  private fun emptyResult(
    rawQuery: String,
    normalizedQuery: String,
    isSubstring: Boolean,
  ): QuranWordSearchResult = QuranWordSearchResult(
    query = rawQuery,
    normalizedQuery = normalizedQuery,
    isSubstringMode = isSubstring,
    totalOccurrences = 0,
    totalMatchingAyahs = 0,
    surahCount = 0,
    groups = emptyList(),
    previewHits = emptyList(),
  )

  companion object {
    private val WHITESPACE_REGEX = Regex("\\s+")

    internal fun buildFromVerses(
      verses: List<VerseDto>,
      loadedPageCount: Int = 604,
    ): QuranWordSearchIndex {
      val seenKeys = HashSet<String>(verses.size)
      val sortable = ArrayList<IndexedAyah>(verses.size)

      for (v in verses) {
        if (!seenKeys.add(v.verse_key)) continue
        val colonIdx = v.verse_key.indexOf(':')
        if (colonIdx <= 0) continue
        val surahId = v.verse_key.substring(0, colonIdx).toIntOrNull() ?: continue
        val ayahNum = v.verse_key.substring(colonIdx + 1).toIntOrNull() ?: continue
        val originalText = v.text_uthmani?.takeIf { it.isNotEmpty() }
          ?: v.words
            .filter { it.char_type_name != "end" && !it.text_uthmani.isNullOrEmpty() }
            .joinToString(" ") { it.text_uthmani.orEmpty() }
        if (originalText.isEmpty()) continue
        val tokens = QuranWordSearchNormalizer.tokenizeOriginalText(originalText)
        sortable += IndexedAyah(
          verseKey = v.verse_key,
          surahId = surahId,
          ayahNumber = ayahNum,
          pageNumber = v.page_number,
          originalText = originalText,
          tokens = tokens,
        )
      }

      sortable.sortWith(compareBy<IndexedAyah> { it.surahId }.thenBy { it.ayahNumber })

      val invertedBuckets = HashMap<String, ArrayList<Int>>(16_384)
      for (ayahIdx in sortable.indices) {
        val tokens = sortable[ayahIdx].tokens
        for (tok in tokens) {
          val list = invertedBuckets.getOrPut(tok.normKey) { ArrayList(2) }
          if (list.isEmpty() || list[list.size - 1] != ayahIdx) {
            list += ayahIdx
          }
        }
      }

      val compactMap = HashMap<String, IntArray>(invertedBuckets.size)
      for ((key, list) in invertedBuckets) {
        compactMap[key] = list.toIntArray()
      }

      return QuranWordSearchIndex(
        ayahs = sortable,
        wordToAyahIndices = compactMap,
        loadedPageCount = loadedPageCount,
      )
    }
  }
}

object QuranWordSearchNormalizer {
  fun isStrippedQuranChar(ch: Char): Boolean {
    val code = ch.code
    return code in 0x0610..0x061A ||
      code == 0x061C ||
      code == 0x0640 || // tatweel ـ
      code in 0x064B..0x065F || // tashkeel / harakat / maddah above / hamza above-below
      code == 0x0670 || // superscript alef ٰ
      code in 0x06D6..0x06ED || // Quranic annotation marks, waqf marks, rub el hizb, sajdah, small waw/yeh
      code in 0x200C..0x200F ||
      code == 0xFEFF
  }

  fun normalizeKey(text: String): String {
    if (text.isEmpty()) return ""
    val sb = StringBuilder(text.length)
    for (i in text.indices) {
      val ch = text[i]
      if (isStrippedQuranChar(ch)) continue
      sb.append(if (ch == '\u0671') '\u0627' else ch)
    }
    return sb.toString()
  }

  internal fun tokenizeOriginalText(originalText: String): List<IndexedWordToken> {
    val n = originalText.length
    val tokens = ArrayList<IndexedWordToken>(16)
    var i = 0
    while (i < n) {
      while (i < n && originalText[i].isWhitespace()) {
        i++
      }
      if (i >= n) break
      val tokStart = i
      while (i < n && !originalText[i].isWhitespace()) {
        i++
      }
      val tokEnd = i

      val sb = StringBuilder(tokEnd - tokStart)
      val origIndices = IntArray(tokEnd - tokStart)
      var keptCount = 0
      for (pos in tokStart until tokEnd) {
        val ch = originalText[pos]
        if (isStrippedQuranChar(ch)) continue
        sb.append(if (ch == '\u0671') '\u0627' else ch)
        origIndices[keptCount++] = pos
      }
      if (keptCount > 0) {
        tokens += IndexedWordToken(
          startInOriginal = tokStart,
          endInOriginal = tokEnd,
          normKey = sb.toString(),
          origCharIndices = origIndices.copyOf(keptCount),
        )
      }
    }
    return tokens
  }
}

class QuranWordSearchEngine(
  private val diskCache: VerifiedDiskCache,
  private val ensurePageCached: suspend (Int) -> Unit,
) {
  private val pageAdapter = QuranApiClient.moshi.adapter(VersesByPageResponse::class.java)
  private val buildMutex = Mutex()

  @Volatile
  private var cachedIndex: QuranWordSearchIndex? = null

  fun hasCachedCompleteIndex(): Boolean = cachedIndex?.isComplete == true

  internal suspend fun ensureIndexReady(): QuranWordSearchIndex {
    cachedIndex?.takeIf { it.isComplete }?.let { return it }

    return buildMutex.withLock {
      cachedIndex?.takeIf { it.isComplete }?.let { return@withLock it }

      withContext(Dispatchers.IO) {
        val pageVerses = arrayOfNulls<List<VerseDto>>(604)
        val missingPages = ArrayList<Int>()
        val ioSemaphore = Semaphore(8)

        coroutineScope {
          (1..604).map { pageNum ->
            async(Dispatchers.IO) {
              ioSemaphore.withPermit {
                val bytes = diskCache.readVerifiedBytes("api/page_$pageNum.json")
                if (bytes != null) {
                  val parsed = runCatching {
                    pageAdapter.fromJson(bytes.decodeToString())
                  }.getOrNull()
                  if (parsed != null && parsed.verses.isNotEmpty()) {
                    pageVerses[pageNum - 1] = parsed.verses
                  }
                }
              }
            }
          }.awaitAll()
        }

        for (p in 1..604) {
          if (pageVerses[p - 1] == null) {
            missingPages += p
          }
        }

        if (missingPages.isNotEmpty()) {
          val netSemaphore = Semaphore(8)
          coroutineScope {
            missingPages.map { pageNum ->
              async(Dispatchers.IO) {
                netSemaphore.withPermit {
                  runCatching {
                    ensurePageCached(pageNum)
                    val bytes = diskCache.readVerifiedBytes("api/page_$pageNum.json")
                    if (bytes != null) {
                      val parsed = pageAdapter.fromJson(bytes.decodeToString())
                      if (parsed != null && parsed.verses.isNotEmpty()) {
                        pageVerses[pageNum - 1] = parsed.verses
                      }
                    }
                  }
                }
              }
            }.awaitAll()
          }
        }

        var loadedPages = 0
        val allVerses = ArrayList<VerseDto>(6236)
        for (list in pageVerses) {
          if (list != null) {
            loadedPages++
            allVerses.addAll(list)
          }
        }

        if (allVerses.isEmpty()) {
          error("يرجى الاتصال بالإنترنت أو استكمال تنزيل المصحف للبحث في الآيات")
        }

        val index = withContext(Dispatchers.Default) {
          QuranWordSearchIndex.buildFromVerses(
            verses = allVerses,
            loadedPageCount = loadedPages,
          )
        }
        if (index.isComplete) {
          cachedIndex = index
        }
        index
      }
    }
  }

  suspend fun search(
    query: String,
    isSubstring: Boolean,
    surahsById: Map<Int, SurahMeta>,
  ): QuranWordSearchResult {
    val index = ensureIndexReady()
    return withContext(Dispatchers.Default) {
      index.search(
        rawQuery = query,
        isSubstring = isSubstring,
        surahsById = surahsById,
      )
    }
  }
}
