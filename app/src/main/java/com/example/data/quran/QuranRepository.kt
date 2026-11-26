package com.example.data.quran

import android.content.Context
import android.util.LruCache
import com.example.data.integrity.AssetHashStatus
import com.example.data.integrity.VerifiedDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject

class QuranRepository(
  private val context: Context,
  val diskCache: VerifiedDiskCache,
  val fontManager: PageFontManager,
) {
  private val chaptersAdapter = QuranApiClient.moshi.adapter(ChaptersResponse::class.java)
  private val juzsAdapter = QuranApiClient.moshi.adapter(JuzsResponse::class.java)
  private val pageAdapter = QuranApiClient.moshi.adapter(VersesByPageResponse::class.java)
  private val verseKeyAdapter = QuranApiClient.moshi.adapter(VerseByKeyResponse::class.java)
  private val alQuranPageAdapter = QuranApiClient.moshi.adapter(AlQuranCloudPageResponse::class.java)

  @Volatile
  private var cachedSurahs: List<SurahMeta>? = null
  @Volatile
  private var cachedJuzs: List<JuzMeta>? = null
  @Volatile
  private var cachedBasmalahWords: List<WordDto>? = null
  @Volatile
  private var cachedTajweedLegend: List<TajweedRuleInfo>? = null
  @Volatile
  private var cachedCredits: List<CreditSourceInfo>? = null
  @Volatile
  private var cachedAssetHashes: List<AssetHashStatus>? = null

  companion object {
    const val LAYOUT_CACHE_VERSION = 4
  }

  // Compute each page layout once and keep up to 10 pages (covering +-2 prefetch + 8 bitmap pages)
  private val pageMemoryCache = LruCache<String, MushafPageData>(10)

  fun clearInMemoryPageCache() {
    pageMemoryCache.evictAll()
  }

  suspend fun getSurahs(): Result<List<SurahMeta>> = runCatching {
    cachedSurahs?.let { return@runCatching it }
    withContext(Dispatchers.IO) {
      cachedSurahs?.let { return@withContext it }
      val bytes = diskCache.readVerifiedBytes("api/chapters_ar.json") ?: run {
        val resp = QuranApiClient.quranComApi.getChaptersRaw("ar")
        if (!resp.isSuccessful) error("فشل جلب قائمة السور (${resp.code()})")
        val body = resp.body()?.bytes() ?: error("استجابة فارغة لقائمة السور")
        diskCache.writeVerifiedBytes("api/chapters_ar.json", body)
        body
      }
      val parsed = chaptersAdapter.fromJson(bytes.decodeToString())
        ?: error("تعذر قراءة بيانات السور")
      val list = parsed.chapters.map { dto ->
        SurahMeta(
          id = dto.id,
          nameArabic = dto.name_arabic,
          revelationOrder = dto.revelation_order,
          versesCount = dto.verses_count,
          bismillahPre = dto.bismillah_pre,
          startPage = dto.pages.firstOrNull() ?: 1,
          endPage = dto.pages.lastOrNull() ?: 1,
          makki = dto.revelation_place.equals("makkah", ignoreCase = true),
        )
      }
      cachedSurahs = list
      list
    }
  }

  suspend fun getJuzs(): Result<List<JuzMeta>> = runCatching {
    cachedJuzs?.let { return@runCatching it }

    withContext(Dispatchers.IO) {
      cachedJuzs?.let { return@withContext it }

      val cachedBytes = diskCache.readVerifiedBytes("api/juzs_resolved.json")
      if (cachedBytes != null) {
        val root = JSONObject(cachedBytes.decodeToString())
        val arr = root.getJSONArray("juzs")
        val list = (0 until arr.length()).map { idx ->
          val obj = arr.getJSONObject(idx)
          JuzMeta(
            juzNumber = obj.getInt("juzNumber"),
            startPage = obj.getInt("startPage"),
            firstSurahId = obj.getInt("firstSurahId"),
            firstAyah = obj.getInt("firstAyah"),
          )
        }
        cachedJuzs = list
        return@withContext list
      }

      val juzResp = QuranApiClient.quranComApi.getJuzsRaw()
      if (!juzResp.isSuccessful) error("فشل جلب قائمة الأجزاء")
      val juzJson = juzResp.body()?.string() ?: error("بيانات الأجزاء فارغة")
      val juzParsed = juzsAdapter.fromJson(juzJson) ?: error("تعذر قراءة بيانات الأجزاء")

      val uniqueJuz = juzParsed.juzs.distinctBy { it.juz_number }.sortedBy { it.juz_number }
      val resolved = coroutineScope {
        uniqueJuz.map { juzDto ->
          async(Dispatchers.IO) {
            val firstEntry = juzDto.verse_mapping.entries
              .minByOrNull { it.key.toIntOrNull() ?: Int.MAX_VALUE }
            val surahId = firstEntry?.key?.toIntOrNull() ?: 1
            val firstAyah = firstEntry?.value?.substringBefore("-")?.toIntOrNull() ?: 1
            val firstVerseResp = QuranApiClient.quranComApi.getFirstVerseByJuzRaw(juzDto.juz_number)
            val pageNumber = if (firstVerseResp.isSuccessful) {
              val vBytes = firstVerseResp.body()?.string()
              val vParsed = vBytes?.let { pageAdapter.fromJson(it) }
              vParsed?.verses?.firstOrNull()?.page_number ?: 1
            } else {
              1
            }
            JuzMeta(
              juzNumber = juzDto.juz_number,
              startPage = pageNumber,
              firstSurahId = surahId,
              firstAyah = firstAyah,
            )
          }
        }.awaitAll()
      }

      val outRoot = JSONObject().apply {
        val arr = org.json.JSONArray()
        for (item in resolved) {
          arr.put(
            JSONObject().apply {
              put("juzNumber", item.juzNumber)
              put("startPage", item.startPage)
              put("firstSurahId", item.firstSurahId)
              put("firstAyah", item.firstAyah)
            },
          )
        }
        put("juzs", arr)
      }
      diskCache.writeVerifiedBytes("api/juzs_resolved.json", outRoot.toString().encodeToByteArray())
      cachedJuzs = resolved
      resolved
    }
  }

  private suspend fun getBasmalahWords(): List<WordDto> {
    cachedBasmalahWords?.let { return it }
    return withContext(Dispatchers.IO) {
      cachedBasmalahWords?.let { return@withContext it }
      val bytes = diskCache.readVerifiedBytes("api/verse_1_1.json") ?: run {
        val resp = QuranApiClient.quranComApi.getVerseByKeyRaw("1:1")
        if (!resp.isSuccessful) error("فشل جلب البسملة الرسمية (${resp.code()})")
        val body = resp.body()?.bytes() ?: error("بيانات البسملة فارغة")
        diskCache.writeVerifiedBytes("api/verse_1_1.json", body)
        body
      }
      val parsed = verseKeyAdapter.fromJson(bytes.decodeToString())
        ?: error("تعذر قراءة بيانات البسملة")
      cachedBasmalahWords = parsed.verse.words
      parsed.verse.words
    }
  }

  private suspend fun loadRawPageVerses(pageNumber: Int): VersesByPageResponse {
    val pageBytes = diskCache.readVerifiedBytes("api/page_$pageNumber.json") ?: run {
      val resp = QuranApiClient.quranComApi.getVersesByPageRaw(page = pageNumber)
      if (!resp.isSuccessful) error("فشل تحميل الصفحة $pageNumber (${resp.code()})")
      val body = resp.body()?.bytes() ?: error("بيانات الصفحة $pageNumber فارغة")
      diskCache.writeVerifiedBytes("api/page_$pageNumber.json", body)
      body
    }
    return pageAdapter.fromJson(pageBytes.decodeToString())
      ?: error("تعذر قراءة الصفحة $pageNumber")
  }

  suspend fun getExistingPageHeaderMeta(pageNumber: Int): PdfPageHeaderMeta? = withContext(Dispatchers.IO) {
    if (pageNumber !in 1..604) return@withContext null

    val versesResp = runCatching {
      val cachedBytes = diskCache.readVerifiedBytes("api/page_$pageNumber.json")
      if (cachedBytes != null) {
        pageAdapter.fromJson(cachedBytes.decodeToString())
      } else {
        loadRawPageVerses(pageNumber)
      }
    }.getOrNull()

    if (versesResp != null && versesResp.verses.isNotEmpty()) {
      val surahIds = versesResp.verses
        .mapNotNull { it.verse_key.substringBefore(":").toIntOrNull() }
        .distinct()
      val firstVerse = versesResp.verses.firstOrNull()
      return@withContext PdfPageHeaderMeta(
        pageNumber = pageNumber,
        surahIds = surahIds,
        juzNumber = firstVerse?.juz_number?.takeIf { it in 1..30 },
        hizbNumber = firstVerse?.hizb_number?.takeIf { it in 1..60 },
      )
    }

    val cachedSurahIds = cachedSurahs
      ?.filter { pageNumber in it.startPage..it.endPage }
      ?.map { it.id }
      .orEmpty()
    if (cachedSurahIds.isNotEmpty()) {
      return@withContext PdfPageHeaderMeta(
        pageNumber = pageNumber,
        surahIds = cachedSurahIds,
        juzNumber = null,
        hizbNumber = null,
      )
    }
    null
  }

  suspend fun getPage(
    pageNumber: Int,
    edition: MushafEdition = MushafEdition.MADINAH_1421,
    includeTajweedFallback: Boolean = false,
  ): Result<MushafPageData> = runCatching {
    val cacheKey = "v${LAYOUT_CACHE_VERSION}_${edition.name}_${pageNumber}_$includeTajweedFallback"
    pageMemoryCache.get(cacheKey)?.let { return@runCatching it }

    val built = buildPageInternal(
      pageNumber = pageNumber,
      edition = edition,
      includeTajweedFallback = includeTajweedFallback,
    )
    pageMemoryCache.put(cacheKey, built)
    built
  }

  /**
   * Loads and builds a page for background global font sizing without evicting the active
   * reader page LRU cache.
   */
  suspend fun getPageForMeasurement(
    pageNumber: Int,
    edition: MushafEdition = MushafEdition.MADINAH_1421,
    includeTajweedFallback: Boolean = false,
  ): Result<MushafPageData> = runCatching {
    val cacheKey = "v${LAYOUT_CACHE_VERSION}_${edition.name}_${pageNumber}_$includeTajweedFallback"
    pageMemoryCache.get(cacheKey)?.let { return@runCatching it }
    buildPageInternal(
      pageNumber = pageNumber,
      edition = edition,
      includeTajweedFallback = includeTajweedFallback,
    )
  }

  private suspend fun buildPageInternal(
    pageNumber: Int,
    edition: MushafEdition,
    includeTajweedFallback: Boolean,
  ): MushafPageData = withContext(Dispatchers.IO) {
    val surahs = getSurahs().getOrThrow().associateBy { it.id }
    val basmalahWords = getBasmalahWords()
    val qulLines = QulPageLayoutLoader.getPageLines(context, edition, pageNumber)

    val currentVersesResp = loadRawPageVerses(pageNumber)
    val responses = mutableListOf(currentVersesResp)

    val expectedCount = qulLines
      .filter { it.type == "ayah" && it.first != null && it.last != null }
      .let { ayahLines ->
        if (ayahLines.isEmpty()) 0
        else (ayahLines.maxOf { it.last!! } - ayahLines.minOf { it.first!! } + 1)
      }

    if (expectedCount > 0 &&
      MushafPageBuilder.countExpandedWordsForPage(pageNumber, responses) < expectedCount &&
      pageNumber > 1
    ) {
      runCatching { loadRawPageVerses(pageNumber - 1) }.getOrNull()?.let { prevResp ->
        responses.add(0, prevResp)
      }
    }

    if (expectedCount > 0 &&
      MushafPageBuilder.countExpandedWordsForPage(pageNumber, responses) < expectedCount &&
      pageNumber < 604
    ) {
      runCatching { loadRawPageVerses(pageNumber + 1) }.getOrNull()?.let { nextResp ->
        responses.add(nextResp)
      }
    }

    val tajweedTexts = if (includeTajweedFallback) {
      fetchTajweedPageTexts(pageNumber)
    } else {
      emptyMap()
    }

    if (qulLines.isNotEmpty()) {
      MushafPageBuilder.buildPageFromQulLayout(
        pageNumber = pageNumber,
        qulLines = qulLines,
        versesResponses = responses,
        chaptersById = surahs,
        basmalahTokens = basmalahWords,
        tajweedAyahTexts = tajweedTexts,
      )
    } else {
      MushafPageBuilder.buildPage(
        pageNumber = pageNumber,
        versesResponse = currentVersesResp,
        chaptersById = surahs,
        basmalahTokens = basmalahWords,
        tajweedAyahTexts = tajweedTexts,
      )
    }
  }

  internal suspend fun ensurePageJsonCached(pageNumber: Int) = withContext(Dispatchers.IO) {
    if (diskCache.getVerifiedFile("api/page_$pageNumber.json") != null) return@withContext
    val resp = QuranApiClient.quranComApi.getVersesByPageRaw(page = pageNumber)
    if (!resp.isSuccessful) error("فشل تحميل بيانات الصفحة $pageNumber (${resp.code()})")
    val body = resp.body()?.bytes() ?: error("بيانات الصفحة $pageNumber فارغة")
    val parsed = pageAdapter.fromJson(body.decodeToString())
      ?: error("بيانات الصفحة $pageNumber غير صالحة")
    if (parsed.verses.isEmpty()) error("بيانات الصفحة $pageNumber غير مكتملة")
    diskCache.writeVerifiedBytes("api/page_$pageNumber.json", body)
  }

  internal suspend fun ensureBasmalahCached() = withContext(Dispatchers.IO) {
    if (diskCache.getVerifiedFile("api/verse_1_1.json") != null) return@withContext
    val resp = QuranApiClient.quranComApi.getVerseByKeyRaw("1:1")
    if (!resp.isSuccessful) error("فشل جلب البسملة الرسمية (${resp.code()})")
    val body = resp.body()?.bytes() ?: error("بيانات البسملة فارغة")
    val parsed = verseKeyAdapter.fromJson(body.decodeToString())
      ?: error("بيانات البسملة غير صالحة")
    cachedBasmalahWords = parsed.verse.words
    diskCache.writeVerifiedBytes("api/verse_1_1.json", body)
  }

  internal suspend fun ensureTajweedPageFallbackCached(pageNumber: Int) = withContext(Dispatchers.IO) {
    if (diskCache.getVerifiedFile("api/tajweed_page_$pageNumber.json") != null) return@withContext
    val resp = QuranApiClient.alQuranCloudApi.getTajweedPageRaw(pageNumber)
    if (!resp.isSuccessful) error("فشل تحميل بيانات التجويد للصفحة $pageNumber (${resp.code()})")
    val body = resp.body()?.bytes() ?: error("بيانات التجويد للصفحة $pageNumber فارغة")
    val parsed = alQuranPageAdapter.fromJson(body.decodeToString())
      ?: error("بيانات التجويد للصفحة $pageNumber غير صالحة")
    if (parsed.data?.ayahs.isNullOrEmpty()) error("بيانات التجويد للصفحة $pageNumber غير مكتملة")
    diskCache.writeVerifiedBytes("api/tajweed_page_$pageNumber.json", body)
  }

  private suspend fun fetchTajweedPageTexts(pageNumber: Int): Map<String, String> {
    return runCatching {
      val bytes = diskCache.readVerifiedBytes("api/tajweed_page_$pageNumber.json") ?: run {
        val resp = QuranApiClient.alQuranCloudApi.getTajweedPageRaw(pageNumber)
        if (!resp.isSuccessful) return@runCatching emptyMap()
        val body = resp.body()?.bytes() ?: return@runCatching emptyMap()
        diskCache.writeVerifiedBytes("api/tajweed_page_$pageNumber.json", body)
        body
      }
      val parsed = alQuranPageAdapter.fromJson(bytes.decodeToString()) ?: return@runCatching emptyMap()
      val ayahs = parsed.data?.ayahs ?: return@runCatching emptyMap()
      buildMap {
        for (a in ayahs) {
          val sNum = a.surah?.number ?: continue
          put("$sNum:${a.numberInSurah}", a.text)
        }
      }
    }.getOrDefault(emptyMap())
  }

  suspend fun loadTajweedLegend(): List<TajweedRuleInfo> {
    cachedTajweedLegend?.let { return it }
    return withContext(Dispatchers.IO) {
      cachedTajweedLegend?.let { return@withContext it }
      val text = context.assets.open("quran/tajweed_legend.json").bufferedReader().use { it.readText() }
      val root = JSONObject(text)
      val arr = root.getJSONArray("rules")
      val list = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        TajweedRuleInfo(
          id = o.getString("id"),
          nameAr = o.getString("nameAr"),
          descriptionAr = o.getString("descriptionAr"),
          colorHex = o.getString("colorHex"),
        )
      }
      cachedTajweedLegend = list
      list
    }
  }

  suspend fun loadCredits(): List<CreditSourceInfo> {
    cachedCredits?.let { return it }
    return withContext(Dispatchers.IO) {
      cachedCredits?.let { return@withContext it }
      val text = context.assets.open("quran/credits.json").bufferedReader().use { it.readText() }
      val root = JSONObject(text)
      val arr = root.getJSONArray("sources")
      val list = (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        CreditSourceInfo(
          id = o.getString("id"),
          title = o.getString("title"),
          organization = o.getString("organization"),
          url = o.getString("url"),
          license = o.getString("license"),
          usage = o.getString("usage"),
        )
      }
      cachedCredits = list
      list
    }
  }

  suspend fun verifyBundledAssets(): List<AssetHashStatus> {
    cachedAssetHashes?.let { return it }
    return diskCache.verifyBundledAssetsManifest().also { cachedAssetHashes = it }
  }

  suspend fun getVersePageNumber(verseKey: String): Int? = withContext(Dispatchers.IO) {
    val safeKey = verseKey.replace(":", "_")
    val bytes = diskCache.readVerifiedBytes("api/verse_meta_$safeKey.json") ?: run {
      val resp = runCatching { QuranApiClient.quranComApi.getVerseByKeyRaw(verseKey) }.getOrNull()
      val body = if (resp?.isSuccessful == true) resp.body()?.bytes() else null
      if (body != null) diskCache.writeVerifiedBytes("api/verse_meta_$safeKey.json", body)
      body
    } ?: return@withContext null
    val parsed = runCatching { verseKeyAdapter.fromJson(bytes.decodeToString()) }.getOrNull()
    parsed?.verse?.page_number?.takeIf { it in 1..604 }
  }

  private val wordSearchEngine = QuranWordSearchEngine(
    diskCache = diskCache,
    ensurePageCached = ::ensurePageJsonCached,
  )

  suspend fun warmUpWordSearchIndex(): Result<Unit> = runCatching {
    wordSearchEngine.ensureIndexReady()
  }

  suspend fun searchQuranWords(
    query: String,
    isSubstring: Boolean,
  ): Result<QuranWordSearchResult> = runCatching {
    val surahs = getSurahs().getOrDefault(emptyList()).associateBy { it.id }
    wordSearchEngine.search(
      query = query,
      isSubstring = isSubstring,
      surahsById = surahs,
    )
  }
}
