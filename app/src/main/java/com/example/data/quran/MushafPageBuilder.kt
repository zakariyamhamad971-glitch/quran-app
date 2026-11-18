package com.example.data.quran

import android.util.Log

object MushafPageBuilder {
  private const val TAG = "MushafPageBuilder"

  // Quran.com API v4 merges "بَعْدَ مَا" into a single WordDto in these 3 positions whereas QUL counts them as 2 word tokens.
  private val SPLIT_MULTI_WORD_TOKENS = setOf("2:181:3", "8:6:4", "13:37:8")

  private fun logPageError(message: String) {
    runCatching { Log.e(TAG, message) }
      .onFailure { System.err.println("$TAG: $message") }
  }

  fun countExpandedWordsForPage(
    pageNumber: Int,
    versesResponses: List<VersesByPageResponse>,
  ): Int {
    val seenVerseIds = HashSet<Int>()
    var count = 0
    for (resp in versesResponses) {
      for (verse in resp.verses) {
        if (!seenVerseIds.add(verse.id)) continue
        for (word in verse.words) {
          if (word.page_number != pageNumber) continue
          val keyPos = "${verse.verse_key}:${word.position}"
          count += if (keyPos in SPLIT_MULTI_WORD_TOKENS) 2 else 1
        }
      }
    }
    return count
  }

  /**
   * Builds a Mushaf page strictly from official QUL layout lines (`layout_madinah_v2.json` or `layout_tajweed_v4.json`).
   * - Never alters, normalizes, or invents Quran text, word order, or line breaks.
   * - Resolves `[first..last]` QUL word tokens via explicit WordDto.id match (if contiguous match) or sequential Quran.com API order.
   * - Emits `EmptyLine` and logs `pageNumber` if any line fails to resolve all of its words.
   */
  fun buildPageFromQulLayout(
    pageNumber: Int,
    qulLines: List<QulLayoutLine>,
    versesResponses: List<VersesByPageResponse>,
    chaptersById: Map<Int, SurahMeta>,
    basmalahTokens: List<WordDto>,
    tajweedAyahTexts: Map<String, String> = emptyMap(),
  ): MushafPageData {
    val uniqueVersesById = LinkedHashMap<Int, VerseDto>()
    for (resp in versesResponses) {
      for (verse in resp.verses) {
        uniqueVersesById.putIfAbsent(verse.id, verse)
      }
    }
    val sortedVerses = uniqueVersesById.values.sortedBy { it.id }
    val verseByKey = sortedVerses.associateBy { it.verse_key }

    val expandedPageWords = ArrayList<MushafWord>(160)
    val explicitIdMap = HashMap<Int, MushafWord>(160)
    var hasSplitOnThisPage = false

    for (verse in sortedVerses) {
      val parts = verse.verse_key.split(":")
      val surahId = parts.getOrNull(0)?.toIntOrNull() ?: continue
      val ayahNumber = parts.getOrNull(1)?.toIntOrNull() ?: continue

      val sortedWords = verse.words.sortedBy { it.position }
      for (word in sortedWords) {
        if (word.page_number != pageNumber) continue
        val keyPos = "${verse.verse_key}:${word.position}"
        if (keyPos in SPLIT_MULTI_WORD_TOKENS) {
          val textParts = word.text_uthmani.orEmpty().trim().split(Regex("\\s+"))
          val glyphs = word.code_v2.orEmpty().filter { !it.isWhitespace() }
          if (textParts.size >= 2 && glyphs.length >= 2) {
            hasSplitOnThisPage = true
            val w1 = MushafWord(
              id = word.id,
              verseKey = verse.verse_key,
              surahId = surahId,
              ayahNumber = ayahNumber,
              position = word.position,
              isEndMarker = false,
              codeV2 = glyphs[0].toString(),
              textUthmani = textParts[0],
            )
            val w2 = MushafWord(
              id = word.id,
              verseKey = verse.verse_key,
              surahId = surahId,
              ayahNumber = ayahNumber,
              position = word.position,
              isEndMarker = false,
              codeV2 = glyphs[1].toString(),
              textUthmani = textParts[1],
            )
            expandedPageWords.add(w1)
            expandedPageWords.add(w2)
            continue
          }
        }

        val mushafWord = MushafWord(
          id = word.id,
          verseKey = verse.verse_key,
          surahId = surahId,
          ayahNumber = ayahNumber,
          position = word.position,
          isEndMarker = word.char_type_name == "end",
          codeV2 = word.code_v2.orEmpty(),
          textUthmani = word.text_uthmani.orEmpty(),
        )
        expandedPageWords.add(mushafWord)
        explicitIdMap.putIfAbsent(word.id, mushafWord)
      }
    }

    var qulPageFirst: Int? = null
    var qulPageLast: Int? = null
    for (qLine in qulLines) {
      if (qLine.type == "ayah" && qLine.first != null && qLine.last != null) {
        if (qulPageFirst == null || qLine.first < qulPageFirst) qulPageFirst = qLine.first
        if (qulPageLast == null || qLine.last > qulPageLast) qulPageLast = qLine.last
      }
    }

    val wordByQulIndex = HashMap<Int, MushafWord>(expandedPageWords.size.coerceAtLeast(16))
    if (qulPageFirst != null && qulPageLast != null && qulPageLast >= qulPageFirst) {
      val expectedCount = qulPageLast - qulPageFirst + 1
      val allIdsMatchExplicitly = !hasSplitOnThisPage &&
        explicitIdMap.size >= expectedCount &&
        (qulPageFirst..qulPageLast).all { explicitIdMap.containsKey(it) }

      if (allIdsMatchExplicitly) {
        for (qulId in qulPageFirst..qulPageLast) {
          explicitIdMap[qulId]?.let { wordByQulIndex[qulId] = it }
        }
      } else if (expandedPageWords.size == expectedCount) {
        for (i in expandedPageWords.indices) {
          wordByQulIndex[qulPageFirst + i] = expandedPageWords[i]
        }
      } else {
        logPageError(
          "Word count mismatch on page $pageNumber: expected=$expectedCount actual=${expandedPageWords.size} (range=$qulPageFirst..$qulPageLast)",
        )
      }
    }

    val basmalahOnlyWords = basmalahTokens
      .filter { it.char_type_name != "end" }
      .sortedBy { it.position }
    val basmalahCodeV2 = basmalahOnlyWords.joinToString(" ") { it.code_v2.orEmpty() }.trim()
    val basmalahUthmani = basmalahOnlyWords.joinToString(" ") { it.text_uthmani.orEmpty() }.trim()

    val sortedQulLines = qulLines.sortedBy { it.line }
    val builtLines = ArrayList<MushafLine>(sortedQulLines.size)

    for (qLine in sortedQulLines) {
      when (qLine.type) {
        "surah_name" -> {
          val surah = qLine.surah?.let { chaptersById[it] }
          if (surah != null) {
            builtLines += MushafLine.SurahHeaderLine(qLine.line, surah)
          } else {
            logPageError("Missing Surah metadata (${qLine.surah}) on page $pageNumber line ${qLine.line}")
            builtLines += MushafLine.EmptyLine(qLine.line)
          }
        }

        "basmallah" -> {
          if (basmalahCodeV2.isNotEmpty() || basmalahUthmani.isNotEmpty()) {
            builtLines += MushafLine.BasmalahLine(qLine.line, basmalahCodeV2, basmalahUthmani)
          } else {
            logPageError("Missing Basmalah tokens on page $pageNumber line ${qLine.line}")
            builtLines += MushafLine.EmptyLine(qLine.line)
          }
        }

        "ayah" -> {
          val first = qLine.first
          val last = qLine.last
          if (first == null || last == null || last < first) {
            logPageError("Invalid ayah word range [$first..$last] on page $pageNumber line ${qLine.line}")
            builtLines += MushafLine.EmptyLine(qLine.line)
          } else {
            val expectedLen = last - first + 1
            val resolved = ArrayList<MushafWord>(expectedLen)
            var allResolved = true
            for (qulId in first..last) {
              val w = wordByQulIndex[qulId]
              if (w == null) {
                allResolved = false
                break
              }
              resolved += w
            }
            if (allResolved && resolved.size == expectedLen) {
              builtLines += MushafLine.AyahWordsLine(
                lineNumber = qLine.line,
                words = resolved,
                isCentered = qLine.centered,
              )
            } else {
              logPageError("Failed to resolve ayah line words [$first..$last] on page $pageNumber line ${qLine.line}")
              builtLines += MushafLine.EmptyLine(qLine.line)
            }
          }
        }

        else -> {
          logPageError("Unknown QUL line type '${qLine.type}' on page $pageNumber line ${qLine.line}")
          builtLines += MushafLine.EmptyLine(qLine.line)
        }
      }
    }

    val resolvedWords = builtLines
      .filterIsInstance<MushafLine.AyahWordsLine>()
      .flatMap { it.words }

    val surahIdsOnPage = resolvedWords
      .map { it.surahId }
      .distinct()
      .ifEmpty {
        sortedVerses
          .mapNotNull { it.verse_key.substringBefore(":").toIntOrNull() }
          .distinct()
      }

    val firstVerse = resolvedWords.firstOrNull()?.verseKey?.let { verseByKey[it] }
      ?: sortedVerses.firstOrNull()
    val juzNumber = firstVerse?.juz_number ?: 1
    val hizbNumber = firstVerse?.hizb_number ?: 1

    return MushafPageData(
      pageNumber = pageNumber,
      juzNumber = juzNumber,
      hizbNumber = hizbNumber,
      surahIds = surahIdsOnPage,
      lines = builtLines,
      tajweedAyahTexts = tajweedAyahTexts,
    )
  }

  /**
   * Legacy fallback page builder when no QUL layout lines are supplied.
   */
  fun buildPage(
    pageNumber: Int,
    versesResponse: VersesByPageResponse,
    chaptersById: Map<Int, SurahMeta>,
    basmalahTokens: List<WordDto>,
    tajweedAyahTexts: Map<String, String> = emptyMap(),
  ): MushafPageData {
    val lineWordsMap = (1..15).associateWith { mutableListOf<MushafWord>() }
    val surahFirstAyahLineOnThisPage = mutableMapOf<Int, Int>()
    val surahIdsOnPage = linkedSetOf<Int>()

    var juzNumber = 1
    var hizbNumber = 1

    val sortedVerses = versesResponse.verses.sortedBy { it.id }
    if (sortedVerses.isNotEmpty()) {
      juzNumber = sortedVerses.first().juz_number
      hizbNumber = sortedVerses.first().hizb_number
    }

    for (verse in sortedVerses) {
      val parts = verse.verse_key.split(":")
      val surahId = parts.getOrNull(0)?.toIntOrNull() ?: continue
      val ayahNumber = parts.getOrNull(1)?.toIntOrNull() ?: continue
      surahIdsOnPage.add(surahId)

      val sortedWords = verse.words.sortedBy { it.position }
      for (word in sortedWords) {
        if (word.page_number != pageNumber) continue
        val lineNo = word.line_number
        if (lineNo !in 1..15) continue

        if (ayahNumber == 1 && word.position == 1) {
          val existing = surahFirstAyahLineOnThisPage[surahId]
          if (existing == null || lineNo < existing) {
            surahFirstAyahLineOnThisPage[surahId] = lineNo
          }
        }

        val isEnd = word.char_type_name == "end"
        lineWordsMap[lineNo]?.add(
          MushafWord(
            id = word.id,
            verseKey = verse.verse_key,
            surahId = surahId,
            ayahNumber = ayahNumber,
            position = word.position,
            isEndMarker = isEnd,
            codeV2 = word.code_v2.orEmpty(),
            textUthmani = word.text_uthmani.orEmpty(),
          ),
        )
      }
    }

    val headerLineMap = mutableMapOf<Int, SurahMeta>()
    val basmalahLineSet = mutableSetOf<Int>()

    val basmalahOnlyWords = basmalahTokens
      .filter { it.char_type_name != "end" }
      .sortedBy { it.position }
    val basmalahCodeV2 = basmalahOnlyWords.joinToString(" ") { it.code_v2.orEmpty() }.trim()
    val basmalahUthmani = basmalahOnlyWords.joinToString(" ") { it.text_uthmani.orEmpty() }.trim()

    for ((surahId, firstWordLine) in surahFirstAyahLineOnThisPage) {
      val surah = chaptersById[surahId] ?: continue
      if (surahId == 1 || !surah.bismillahPre) {
        if (firstWordLine >= 2 && lineWordsMap[firstWordLine - 1].orEmpty().isEmpty()) {
          headerLineMap[firstWordLine - 1] = surah
        }
      } else {
        if (firstWordLine >= 2 && lineWordsMap[firstWordLine - 1].orEmpty().isEmpty()) {
          basmalahLineSet.add(firstWordLine - 1)
        }
        if (firstWordLine >= 3 && lineWordsMap[firstWordLine - 2].orEmpty().isEmpty()) {
          headerLineMap[firstWordLine - 2] = surah
        }
      }
    }

    val chaptersStartingOnThisPage = chaptersById.values.filter { it.startPage == pageNumber }
    for (surah in chaptersStartingOnThisPage) {
      if (surahFirstAyahLineOnThisPage.containsKey(surah.id)) continue
      val trailingEmpty = (15 downTo 1).takeWhile { ln ->
        lineWordsMap[ln].orEmpty().isEmpty() && !headerLineMap.containsKey(ln) && !basmalahLineSet.contains(ln)
      }.sorted()
      if (surah.bismillahPre && trailingEmpty.size >= 2) {
        headerLineMap[trailingEmpty[0]] = surah
        basmalahLineSet.add(trailingEmpty[1])
      } else if (trailingEmpty.isNotEmpty()) {
        headerLineMap[trailingEmpty.last()] = surah
      }
    }

    val lines = (1..15).map { ln ->
      val header = headerLineMap[ln]
      when {
        header != null -> MushafLine.SurahHeaderLine(ln, header)
        basmalahLineSet.contains(ln) -> MushafLine.BasmalahLine(ln, basmalahCodeV2, basmalahUthmani)
        else -> {
          val words = lineWordsMap[ln].orEmpty()
          if (words.isEmpty()) {
            MushafLine.EmptyLine(ln)
          } else {
            MushafLine.AyahWordsLine(ln, words)
          }
        }
      }
    }

    return MushafPageData(
      pageNumber = pageNumber,
      juzNumber = juzNumber,
      hizbNumber = hizbNumber,
      surahIds = surahIdsOnPage.toList(),
      lines = lines,
      tajweedAyahTexts = tajweedAyahTexts,
    )
  }
}
