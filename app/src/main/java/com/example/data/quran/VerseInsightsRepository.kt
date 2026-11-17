package com.example.data.quran

import androidx.core.text.HtmlCompat
import com.example.data.integrity.VerifiedDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class VerseInsightsRepository(
  private val diskCache: VerifiedDiskCache,
) {
  companion object {
    private val SUP_REGEX = Regex("<sup[^>]*>.*?</sup>", RegexOption.IGNORE_CASE)
  }
  private val translationResourcesAdapter =
    QuranApiClient.moshi.adapter(TranslationResourcesResponse::class.java)
  private val tafsirResourcesAdapter =
    QuranApiClient.moshi.adapter(TafsirResourcesResponse::class.java)
  private val verseTranslationsAdapter =
    QuranApiClient.moshi.adapter(VerseWithTranslationsResponse::class.java)
  private val tafsirByAyahAdapter =
    QuranApiClient.moshi.adapter(TafsirByAyahResponse::class.java)

  private val initMutex = Mutex()
  private var resolvedIds: ResolvedResourceIds? = null

  private data class ResolvedResourceIds(
    val en: TranslationResourceDto,
    val fr: TranslationResourceDto,
    val es: TranslationResourceDto,
    val tafsirAr: TafsirResourceDto?,
    val tafsirEn: TafsirResourceDto?,
  )

  private suspend fun ensureResolvedResources(): ResolvedResourceIds {
    resolvedIds?.let { return it }
    return initMutex.withLock {
      resolvedIds?.let { return@withLock it }
      coroutineScope {
        val enDeferred = async(Dispatchers.IO) { fetchTranslationForLang("en", listOf("saheeh", "sahih", "clear-quran", "haleem")) }
        val frDeferred = async(Dispatchers.IO) { fetchTranslationForLang("fr", listOf("hamidullah", "french", "montada")) }
        val esDeferred = async(Dispatchers.IO) { fetchTranslationForLang("es", listOf("garcia", "cortes", "spanish")) }
        val tafsirsDeferred = async(Dispatchers.IO) { fetchTafsirResources() }

        val enList = enDeferred.await()
        val frList = frDeferred.await()
        val esList = esDeferred.await()
        val tafsirs = tafsirsDeferred.await()

        val enPick = enList.firstOrNull { dto ->
          val s = "${dto.slug.orEmpty()} ${dto.name} ${dto.author_name.orEmpty()}".lowercase()
          s.contains("saheeh") || s.contains("sahih") || s.contains("clear")
        } ?: enList.firstOrNull()
          ?: TranslationResourceDto(id = 20, name = "Saheeh International", author_name = "Saheeh International")

        val frPick = frList.firstOrNull { dto ->
          val s = "${dto.slug.orEmpty()} ${dto.name} ${dto.author_name.orEmpty()}".lowercase()
          s.contains("hamidullah") || s.contains("montada")
        } ?: frList.firstOrNull()
          ?: TranslationResourceDto(id = 31, name = "Muhammad Hamidullah", author_name = "Muhammad Hamidullah")

        val esPick = esList.firstOrNull { dto ->
          val s = "${dto.slug.orEmpty()} ${dto.name} ${dto.author_name.orEmpty()}".lowercase()
          s.contains("garcia") || s.contains("cortes") || s.contains("isa")
        } ?: esList.firstOrNull()
          ?: TranslationResourceDto(id = 83, name = "Isa Garcia", author_name = "Sheikh Isa Garcia")

        val tafsirArPick = tafsirs.firstOrNull { dto ->
          val lang = dto.language_name.orEmpty().lowercase()
          val name = "${dto.name} ${dto.slug.orEmpty()}".lowercase()
          lang == "arabic" && (name.contains("ابن كثير") || name.contains("kathir"))
        } ?: tafsirs.firstOrNull { dto ->
          dto.language_name.orEmpty().equals("arabic", ignoreCase = true)
        }

        val tafsirEnPick = tafsirs.firstOrNull { dto ->
          val lang = dto.language_name.orEmpty().lowercase()
          val name = "${dto.name} ${dto.slug.orEmpty()}".lowercase()
          lang == "english" && name.contains("kathir")
        } ?: tafsirs.firstOrNull { dto ->
          dto.language_name.orEmpty().equals("english", ignoreCase = true)
        }

        val resolved = ResolvedResourceIds(
          en = enPick,
          fr = frPick,
          es = esPick,
          tafsirAr = tafsirArPick,
          tafsirEn = tafsirEnPick,
        )
        resolvedIds = resolved
        resolved
      }
    }
  }

  private suspend fun fetchTranslationForLang(lang: String, preferred: List<String>): List<TranslationResourceDto> {
    val cacheKey = "api/resources_translations_$lang.json"
    val bytes = diskCache.readVerifiedBytes(cacheKey) ?: run {
      val resp = runCatching { QuranApiClient.quranComApi.getTranslationsResourceRaw(lang) }.getOrNull()
      val body = if (resp?.isSuccessful == true) resp.body()?.bytes() else null
      if (body != null) {
        diskCache.writeVerifiedBytes(cacheKey, body)
      }
      body
    }
    val parsed = bytes?.let {
      runCatching { translationResourcesAdapter.fromJson(it.decodeToString()) }.getOrNull()
    }
    return parsed?.translations.orEmpty()
  }

  private suspend fun fetchTafsirResources(): List<TafsirResourceDto> {
    val cacheKey = "api/resources_tafsirs.json"
    val bytes = diskCache.readVerifiedBytes(cacheKey) ?: run {
      val resp = runCatching { QuranApiClient.quranComApi.getTafsirsResourceRaw() }.getOrNull()
      val body = if (resp?.isSuccessful == true) resp.body()?.bytes() else null
      if (body != null) {
        diskCache.writeVerifiedBytes(cacheKey, body)
      }
      body
    }
    val parsed = bytes?.let {
      runCatching { tafsirResourcesAdapter.fromJson(it.decodeToString()) }.getOrNull()
    }
    return parsed?.tafsirs.orEmpty()
  }

  suspend fun getVerseInsights(
    verseKey: String,
    pageNumber: Int,
    codeV2Line: String,
  ): Result<VerseInsightsData> = runCatching {
    val ids = ensureResolvedResources()
    val parts = verseKey.split(":")
    val surahId = parts.getOrNull(0)?.toIntOrNull() ?: 1
    val ayahNumber = parts.getOrNull(1)?.toIntOrNull() ?: 1
    val safeKey = verseKey.replace(":", "_")

    coroutineScope {
      val transDeferred = async(Dispatchers.IO) {
        val translationIdsParam = "${ids.en.id},${ids.fr.id},${ids.es.id}"
        val cachePath = "api/verse_trans_${safeKey}_$translationIdsParam.json"
        val bytes = diskCache.readVerifiedBytes(cachePath) ?: run {
          val resp = QuranApiClient.quranComApi.getVerseTranslationsRaw(verseKey, translationIdsParam)
          if (!resp.isSuccessful) error("فشل جلب الترجمات (${resp.code()})")
          val body = resp.body()?.bytes() ?: error("بيانات الترجمة فارغة")
          diskCache.writeVerifiedBytes(cachePath, body)
          body
        }
        val parsed = verseTranslationsAdapter.fromJson(bytes.decodeToString())
        val mapByResId = parsed?.verse?.translations.orEmpty().associateBy { it.resource_id }
        listOf(
          VerseTranslationEntry(
            languageCode = "EN",
            languageLabelAr = "الإنجليزية",
            translatorName = ids.en.author_name ?: ids.en.name,
            text = cleanHtml(mapByResId[ids.en.id]?.text.orEmpty()),
          ),
          VerseTranslationEntry(
            languageCode = "FR",
            languageLabelAr = "الفرنسية",
            translatorName = ids.fr.author_name ?: ids.fr.name,
            text = cleanHtml(mapByResId[ids.fr.id]?.text.orEmpty()),
          ),
          VerseTranslationEntry(
            languageCode = "ES",
            languageLabelAr = "الإسبانية",
            translatorName = ids.es.author_name ?: ids.es.name,
            text = cleanHtml(mapByResId[ids.es.id]?.text.orEmpty()),
          ),
        )
      }

      val tafsirArDeferred = async(Dispatchers.IO) {
        val res = ids.tafsirAr ?: return@async Pair("تفسير ابن كثير", "")
        fetchTafsirText(res.id, res.name, verseKey, safeKey)
      }

      val tafsirEnDeferred = async(Dispatchers.IO) {
        val res = ids.tafsirEn ?: return@async Pair("Tafsir Ibn Kathir", "")
        fetchTafsirText(res.id, res.name, verseKey, safeKey)
      }

      val translations = transDeferred.await()
      val (arTitle, arText) = tafsirArDeferred.await()
      val (enTitle, enText) = tafsirEnDeferred.await()

      VerseInsightsData(
        verseKey = verseKey,
        surahId = surahId,
        ayahNumber = ayahNumber,
        pageNumber = pageNumber,
        codeV2Line = codeV2Line,
        translations = translations,
        tafsirArabicTitle = arTitle,
        tafsirArabicText = arText,
        tafsirEnglishTitle = enTitle,
        tafsirEnglishText = enText,
      )
    }
  }

  private suspend fun fetchTafsirText(
    tafsirId: Int,
    fallbackTitle: String,
    verseKey: String,
    safeKey: String,
  ): Pair<String, String> {
    val cachePath = "api/tafsir_${tafsirId}_$safeKey.json"
    val bytes = diskCache.readVerifiedBytes(cachePath) ?: run {
      val resp = runCatching { QuranApiClient.quranComApi.getTafsirByAyahRaw(tafsirId, verseKey) }.getOrNull()
      val body = if (resp?.isSuccessful == true) resp.body()?.bytes() else null
      if (body != null) diskCache.writeVerifiedBytes(cachePath, body)
      body
    } ?: return Pair(fallbackTitle, "")

    val parsed = runCatching { tafsirByAyahAdapter.fromJson(bytes.decodeToString()) }.getOrNull()
    val title = parsed?.tafsir?.resource_name ?: fallbackTitle
    val text = cleanHtml(parsed?.tafsir?.text.orEmpty())
    return Pair(title, text)
  }

  private fun cleanHtml(raw: String): String {
    if (raw.isBlank()) return ""
    // Remove footnote <sup> markers that reference numeric footnotes cleanly
    val withoutSups = raw.replace(SUP_REGEX, "")
    return HtmlCompat.fromHtml(withoutSups, HtmlCompat.FROM_HTML_MODE_COMPACT)
      .toString()
      .trim()
  }
}
