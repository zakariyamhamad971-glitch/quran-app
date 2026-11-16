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

  // Compute each page layout once and keep up to 6 pages in memory
  private val pageMemoryCache = LruCache<String, MushafPageData>(6)

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

  suspend fun getPage(
    pageNumber: Int,
    includeTajweedFallback: Boolean,
  ): Result<MushafPageData> = runCatching {
    val cacheKey = "${pageNumber}_$includeTajweedFallback"
    pageMemoryCache.get(cacheKey)?.let { return@runCatching it }

    withContext(Dispatchers.IO) {
      pageMemoryCache.get(cacheKey)?.let { return@withContext it }

      val surahs = getSurahs().getOrThrow().associateBy { it.id }
      val basmalahWords = getBasmalahWords()

      val pageBytes = diskCache.readVerifiedBytes("api/page_$pageNumber.json") ?: run {
        val resp = QuranApiClient.quranComApi.getVersesByPageRaw(page = pageNumber)
        if (!resp.isSuccessful) error("فشل تحميل الصفحة $pageNumber (${resp.code()})")
        val body = resp.body()?.bytes() ?: error("بيانات الصفحة $pageNumber فارغة")
        diskCache.writeVerifiedBytes("api/page_$pageNumber.json", body)
        body
      }
      val versesResp = pageAdapter.fromJson(pageBytes.decodeToString())
        ?: error("تعذر قراءة الصفحة $pageNumber")

      val tajweedTexts = if (includeTajweedFallback) {
        fetchTajweedPageTexts(pageNumber)
      } else {
        emptyMap()
      }

      val built = MushafPageBuilder.buildPage(
        pageNumber = pageNumber,
        versesResponse = versesResp,
        chaptersById = surahs,
        basmalahTokens = basmalahWords,
        tajweedAyahTexts = tajweedTexts,
      )
      pageMemoryCache.put(cacheKey, built)
      built
    }
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

  suspend fun loadTajweedLegend(): List<TajweedRuleInfo> = withContext(Dispatchers.IO) {
    val text = context.assets.open("quran/tajweed_legend.json").bufferedReader().use { it.readText() }
    val root = JSONObject(text)
    val arr = root.getJSONArray("rules")
    (0 until arr.length()).map { i ->
      val o = arr.getJSONObject(i)
      TajweedRuleInfo(
        id = o.getString("id"),
        nameAr = o.getString("nameAr"),
        descriptionAr = o.getString("descriptionAr"),
        colorHex = o.getString("colorHex"),
      )
    }
  }

  suspend fun loadCredits(): List<CreditSourceInfo> = withContext(Dispatchers.IO) {
    val text = context.assets.open("quran/credits.json").bufferedReader().use { it.readText() }
    val root = JSONObject(text)
    val arr = root.getJSONArray("sources")
    (0 until arr.length()).map { i ->
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
  }

  suspend fun verifyBundledAssets(): List<AssetHashStatus> =
    diskCache.verifyBundledAssetsManifest()

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

  suspend fun searchVerses(query: String): Result<List<VerseSearchHit>> = runCatching {
    val trimmed = query.trim()
    if (trimmed.isBlank()) return@runCatching emptyList()

    withContext(Dispatchers.IO) {
      // Direct surah:ayah key lookup (e.g., 2:255)
      val keyMatch = Regex("^(\\d{1,3})\\s*[:\\-]\\s*(\\d{1,3})$").matchEntire(trimmed)
      if (keyMatch != null) {
        val sId = keyMatch.groupValues[1].toIntOrNull() ?: 1
        val aNum = keyMatch.groupValues[2].toIntOrNull() ?: 1
        val key = "$sId:$aNum"
        val resp = QuranApiClient.quranComApi.getVerseByKeyRaw(key)
        if (!resp.isSuccessful) error("لم يتم العثور على الآية $key")
        val parsed = verseKeyAdapter.fromJson(resp.body()?.string().orEmpty())
          ?: error("تعذر قراءة الآية $key")
        return@withContext listOf(
          VerseSearchHit(
            verseKey = parsed.verse.verse_key,
            surahId = sId,
            ayahNumber = aNum,
            pageNumber = parsed.verse.page_number,
            snippetText = parsed.verse.text_uthmani.orEmpty(),
          ),
        )
      }

      val searchAdapter = QuranApiClient.moshi.adapter(QuranSearchResponse::class.java)
      val resp = QuranApiClient.quranComApi.searchQuranRaw(query = trimmed, size = 20, language = "ar")
      if (!resp.isSuccessful) error("تعذر البحث عن الآيات (${resp.code()})")
      val parsed = searchAdapter.fromJson(resp.body()?.string().orEmpty())
      val rawResults = parsed?.search?.results.orEmpty()
      coroutineScope {
        rawResults.take(15).map { item ->
          async(Dispatchers.IO) {
            val parts = item.verse_key.split(":")
            val sId = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val aNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
            val pageNum = getVersePageNumber(item.verse_key) ?: 1
            val cleanText = androidx.core.text.HtmlCompat.fromHtml(
              item.text,
              androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT,
            ).toString().trim()
            VerseSearchHit(
              verseKey = item.verse_key,
              surahId = sId,
              ayahNumber = aNum,
              pageNumber = pageNum,
              snippetText = cleanText,
            )
          }
        }.awaitAll()
      }
    }
  }
}
