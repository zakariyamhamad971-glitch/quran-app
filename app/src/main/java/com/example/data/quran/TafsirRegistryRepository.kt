package com.example.data.quran

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

enum class InsightSourceType(val key: String, val labelAr: String) {
  TAFSIR("tafsir", "تفسير"),
  TRANSLATION("translation", "ترجمة"),
  IRAB("irab", "إعراب"),
  WORD_MEANINGS("word-meanings", "معاني الكلمات");

  companion object {
    fun fromKey(raw: String?): InsightSourceType {
      val norm = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
      return entries.firstOrNull { it.key == norm } ?: TAFSIR
    }
  }
}

@Immutable
data class InsightRegistryProvider(
  val id: String,
  val nameAr: String,
  val language: String,
  val type: InsightSourceType,
  val editionsUrl: String,
  val urlPattern: String,
  val version: String,
  val license: String,
  val attribution: String,
)

@Immutable
data class TafsirEditionInfo(
  val id: String,
  val providerId: String,
  val slug: String,
  val nameAr: String,
  val authorName: String,
  val languageKey: String,
  val languageLabelAr: String,
  val type: InsightSourceType,
  val urlPattern: String,
  val version: String,
  val license: String,
  val attribution: String,
  val sourceName: String,
)

@Immutable
data class DownloadedTafsirEdition(
  val editionId: String,
  val providerId: String,
  val slug: String,
  val nameAr: String,
  val authorName: String,
  val languageKey: String,
  val languageLabelAr: String,
  val type: InsightSourceType,
  val version: String,
  val license: String,
  val attribution: String,
  val sourceName: String,
  val completedSurahs: Int,
  val totalSurahs: Int,
  val downloadedBytes: Long,
  val isComplete: Boolean,
)

@Immutable
data class TafsirDownloadState(
  val editionId: String,
  val isDownloading: Boolean = false,
  val isUpdating: Boolean = false,
  val completedSurahs: Int = 0,
  val totalSurahs: Int = 114,
  val downloadedBytes: Long = 0L,
  val errorMessage: String? = null,
) {
  val progressFraction: Float
    get() = if (totalSurahs <= 0) 0f else (completedSurahs.toFloat() / totalSurahs.toFloat()).coerceIn(0f, 1f)
}

@Immutable
data class TafsirCatalogUiState(
  val availableEditions: List<TafsirEditionInfo> = emptyList(),
  val downloadedEditions: List<DownloadedTafsirEdition> = emptyList(),
  val isLoading: Boolean = false,
  val errorMessage: String? = null,
)

class TafsirRegistryRepository(
  private val context: Context,
  private val scope: CoroutineScope,
) {
  companion object {
    private const val REGISTRY_ASSET = "tafsir_registry.json"
    private const val ROOT_DIR = "tafsir_editions"
    private const val META_FILE = "meta.json"
    private const val CATALOG_CACHE_FILE = "editions_catalog_cache.json"
    private const val TOTAL_SURAHS = 114
    private const val CONCURRENT_SURAH_DOWNLOADS = 4

    private val SUP_REGEX = Regex("<sup[^>]*>.*?</sup>", RegexOption.IGNORE_CASE)
    private val NON_FILE_CHARS = Regex("[^a-zA-Z0-9._-]")
    private val ARABIC_DIACRITICS_REGEX = Regex("[\u064B-\u065F\u0670\u06D6-\u06ED]")

    internal fun normalizeSearchText(raw: String): String {
      if (raw.isBlank()) return ""
      val stripped = raw.replace(ARABIC_DIACRITICS_REGEX, "")
      return buildString(stripped.length) {
        for (ch in stripped) {
          val mapped = when (ch) {
            'أ', 'إ', 'آ', 'ٱ' -> 'ا'
            'ى' -> 'ي'
            'ة' -> 'ه'
            'ؤ' -> 'و'
            'ئ' -> 'ي'
            else -> ch.lowercaseChar()
          }
          append(mapped)
        }
      }.trim()
    }

    private fun isIrabEdition(slug: String, name: String): Boolean {
      val s = slug.lowercase(Locale.ROOT)
      val n = normalizeSearchText(name)
      return s.contains("i-rab") ||
        s.contains("irab") ||
        s.contains("eerab") ||
        n.contains("اعراب") ||
        n.contains("الجدول في اعراب")
    }

    private fun languageLabelAr(rawLang: String): String {
      val key = rawLang.trim().lowercase(Locale.ROOT)
      return when (key) {
        "arabic", "ar" -> "العربية"
        "english", "en" -> "الإنجليزية"
        "urdu", "ur" -> "الأردية"
        "bengali", "bn" -> "البنغالية"
        "russian", "ru" -> "الروسية"
        "kurdish", "ku" -> "الكردية"
        "indonesian", "id" -> "الإندونيسية"
        "malay", "ms" -> "الماليزية"
        "turkish", "tr" -> "التركية"
        "french", "fr" -> "الفرنسية"
        "spanish", "es" -> "الإسبانية"
        "german", "de" -> "الألمانية"
        "persian", "farsi", "fa" -> "الفارسية"
        "azeri", "azerbaijani", "az" -> "الأذرية"
        "uzbek", "uz" -> "الأوزبكية"
        "bosnian", "bs" -> "البوسنية"
        "albanian", "sq" -> "الألبانية"
        "chinese", "zh" -> "الصينية"
        "hindi", "hi" -> "الهندية"
        "malayalam", "ml" -> "المالايالامية"
        "tamil", "ta" -> "التاميلية"
        else -> rawLang.trim().replaceFirstChar {
          if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
        }.ifBlank { "أخرى" }
      }
    }

    private fun inferSourceType(slug: String, languageKey: String, name: String): InsightSourceType {
      val s = slug.lowercase(Locale.ROOT)
      val n = name.lowercase(Locale.ROOT)
      val isTafsirHint = s.contains("tafsir") ||
        s.contains("tafisr") ||
        s.contains("mukhtasar") ||
        s.contains("muyassar") ||
        s.contains("jalalayn") ||
        s.contains("kathir") ||
        s.contains("qurtubi") ||
        s.contains("tabari") ||
        s.contains("baghawy") ||
        s.contains("saadi") ||
        s.contains("waseet") ||
        n.contains("تفسير") ||
        n.contains("مختصر") ||
        n.contains("tafsir") ||
        n.contains("tafisr")
      return if (isTafsirHint || languageKey == "arabic") {
        InsightSourceType.TAFSIR
      } else {
        InsightSourceType.TRANSLATION
      }
    }

    fun formatBytesAr(bytes: Long): String {
      if (bytes <= 0L) return "٠ ك.ب"
      val kb = bytes / 1024.0
      val mb = kb / 1024.0
      val formatted = if (mb >= 1.0) {
        String.format(Locale.US, "%.1f م.ب", mb)
      } else {
        String.format(Locale.US, "%.0f ك.ب", kb.coerceAtLeast(1.0))
      }
      return toArabicIndicString(formatted)
    }

    private fun toArabicIndicString(raw: String): String {
      val digits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
      return buildString(raw.length) {
        for (ch in raw) {
          append(if (ch in '0'..'9') digits[ch - '0'] else ch)
        }
      }
    }
  }

  private val baseDir = File(context.filesDir, ROOT_DIR)
  private val catalogMutex = Mutex()
  private val activeJobs = mutableMapOf<String, Job>()

  @Volatile
  private var cachedProviders: List<InsightRegistryProvider>? = null

  private val _catalogState = MutableStateFlow(TafsirCatalogUiState())
  val catalogState: StateFlow<TafsirCatalogUiState> = _catalogState.asStateFlow()

  private val _downloadStates = MutableStateFlow<Map<String, TafsirDownloadState>>(emptyMap())
  val downloadStates: StateFlow<Map<String, TafsirDownloadState>> = _downloadStates.asStateFlow()

  init {
    scope.launch(Dispatchers.IO) {
      refreshLocalDownloadsSnapshot()
    }
  }

  suspend fun loadRegistryProviders(): List<InsightRegistryProvider> {
    cachedProviders?.let { return it }
    return withContext(Dispatchers.IO) {
      cachedProviders?.let { return@withContext it }
      val raw = context.assets.open(REGISTRY_ASSET).bufferedReader().use { it.readText() }
      val root = JSONObject(raw)
      val arr = root.optJSONArray("sources") ?: JSONArray()
      val list = ArrayList<InsightRegistryProvider>(arr.length())
      for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val type = InsightSourceType.fromKey(obj.optString("type"))
        list.add(
          InsightRegistryProvider(
            id = obj.optString("id"),
            nameAr = obj.optString("name_ar"),
            language = obj.optString("language"),
            type = type,
            editionsUrl = obj.optString("editions_url"),
            urlPattern = obj.optString("url_pattern"),
            version = obj.optString("version", "1.0.0"),
            license = obj.optString("license"),
            attribution = obj.optString("attribution"),
          ),
        )
      }
      cachedProviders = list
      list
    }
  }

  suspend fun loadCreditsForAbout(): List<CreditSourceInfo> {
    val providers = loadRegistryProviders()
    val downloaded = scanDownloadedEditionsOnDisk()
    val list = ArrayList<CreditSourceInfo>(providers.size + downloaded.size)
    for (p in providers) {
      list.add(
        CreditSourceInfo(
          id = "tafsir_provider_${p.id}",
          title = p.nameAr,
          organization = p.attribution,
          url = p.editionsUrl,
          license = p.license,
          usage = "مصدر بيانات التفاسير والترجمات القابل للتنزيل والاستخدام دون اتصال (${p.version})",
        ),
      )
    }
    for (dl in downloaded) {
      list.add(
        CreditSourceInfo(
          id = "tafsir_edition_${dl.slug}",
          title = "${dl.type.labelAr}: ${dl.nameAr}",
          organization = dl.authorName.ifBlank { dl.attribution },
          url = "https://cdn.jsdelivr.net/gh/spa5k/tafsir_api@main",
          license = dl.license,
          usage = "${dl.attribution} (${dl.languageLabelAr})",
        ),
      )
    }
    return list
  }

  suspend fun ensureCatalogLoaded(forceRefresh: Boolean = false) {
    catalogMutex.withLock {
      val current = _catalogState.value
      val downloaded = withContext(Dispatchers.IO) { scanDownloadedEditionsOnDisk() }
      if (!forceRefresh && current.availableEditions.isNotEmpty()) {
        _catalogState.update { it.copy(downloadedEditions = downloaded, errorMessage = null) }
        return
      }
      if (!forceRefresh) {
        val cached = withContext(Dispatchers.IO) { readCachedCatalogOrNull() }
        if (!cached.isNullOrEmpty()) {
          _catalogState.value = TafsirCatalogUiState(
            availableEditions = cached,
            downloadedEditions = downloaded,
            isLoading = false,
            errorMessage = null,
          )
          return
        }
      }
      _catalogState.update {
        it.copy(
          downloadedEditions = downloaded,
          isLoading = true,
          errorMessage = null,
        )
      }

      val res = withContext(Dispatchers.IO) { fetchCatalogFromNetwork() }
      res.onSuccess { editions ->
        _catalogState.value = TafsirCatalogUiState(
          availableEditions = editions,
          downloadedEditions = scanDownloadedEditionsOnDisk(),
          isLoading = false,
          errorMessage = null,
        )
      }.onFailure { err ->
        val cached = withContext(Dispatchers.IO) { readCachedCatalogOrNull() }.orEmpty()
        _catalogState.update {
          it.copy(
            availableEditions = it.availableEditions.ifEmpty { cached },
            downloadedEditions = downloaded,
            isLoading = false,
            errorMessage = if (cached.isEmpty() && it.availableEditions.isEmpty()) {
              err.message ?: "تعذر تحميل قائمة التفاسير والترجمات"
            } else {
              null
            },
          )
        }
      }
    }
  }

  private suspend fun fetchCatalogFromNetwork(): Result<List<TafsirEditionInfo>> = runCatching {
    val providers = loadRegistryProviders()
    val allEditions = ArrayList<TafsirEditionInfo>()
    for (provider in providers) {
      if (provider.editionsUrl.isBlank()) continue
      val raw = executeGetString(provider.editionsUrl)
      val parsed = parseEditionsJson(raw, provider)
      if (parsed.isNotEmpty()) {
        writeCachedCatalogRaw(raw)
        allEditions.addAll(parsed)
      }
    }
    if (allEditions.isEmpty()) error("قائمة التفاسير فارغة")
    allEditions
  }

  private fun readCachedCatalogOrNull(): List<TafsirEditionInfo>? {
    val cacheFile = File(baseDir, CATALOG_CACHE_FILE)
    if (!cacheFile.exists() || cacheFile.length() == 0L) return null
    return runCatching {
      val providers = kotlinx.coroutines.runBlocking { loadRegistryProviders() }
      val provider = providers.firstOrNull() ?: return null
      val raw = cacheFile.readText(Charsets.UTF_8)
      parseEditionsJson(raw, provider).takeIf { it.isNotEmpty() }
    }.getOrNull()
  }

  private fun writeCachedCatalogRaw(raw: String) {
    runCatching {
      if (!baseDir.exists()) baseDir.mkdirs()
      val tmp = File(baseDir, "$CATALOG_CACHE_FILE.tmp")
      tmp.writeText(raw, Charsets.UTF_8)
      tmp.renameTo(File(baseDir, CATALOG_CACHE_FILE))
    }
  }

  private fun getArabicEditionName(slug: String, rawName: String): String {
    val s = slug.lowercase(Locale.ROOT)
    return when {
      s.contains("ibn-kathir") || s.contains("kathir") -> "تفسير ابن كثير"
      s.contains("tabari") -> "تفسير الطبري"
      s.contains("saadi") -> "تفسير السعدي"
      s.contains("baghawy") -> "تفسير البغوي"
      s.contains("qurtubi") -> "تفسير القرطبي"
      s.contains("jalalayn") -> "تفسير الجلالين"
      s.contains("muyassar") -> "التفسير الميسر"
      s.contains("waseet") -> "التفسير الوسيط"
      s.contains("mukhtasar") -> "المختصر في التفسير"
      s.contains("ibn-ashour") || s.contains("tahrir") -> "تفسير التحرير والتنوير لابن عاشور"
      s.contains("i-rab") || s.contains("irab") || s.contains("eerab") -> "إعراب القرآن"
      else -> rawName
    }
  }

  internal fun parseEditionsJson(raw: String, provider: InsightRegistryProvider): List<TafsirEditionInfo> {
    val trimmed = raw.trim()
    val arr = when {
      trimmed.startsWith("[") -> JSONArray(trimmed)
      trimmed.startsWith("{") -> {
        val obj = JSONObject(trimmed)
        obj.optJSONArray("editions") ?: obj.optJSONArray("tafsirs") ?: JSONArray()
      }
      else -> JSONArray()
    }
    val out = ArrayList<TafsirEditionInfo>(arr.length())
    for (i in 0 until arr.length()) {
      val obj = arr.optJSONObject(i) ?: continue
      val slug = obj.optString("slug").trim()
      val rawName = obj.optString("name").trim()
      if (slug.isBlank() || rawName.isBlank()) continue
      val isIrab = isIrabEdition(slug, rawName)
      val name = getArabicEditionName(slug, rawName)
      val langRaw = obj.optString("language_name").trim().ifBlank { "other" }
      val langKey = langRaw.lowercase(Locale.ROOT)
      val author = obj.optString("author_name").trim()
      val sourceName = obj.optString("source").trim()
      val type = if (isIrab) InsightSourceType.IRAB else inferSourceType(slug, langKey, rawName)
      out.add(
        TafsirEditionInfo(
          id = slug,
          providerId = provider.id,
          slug = slug,
          nameAr = name,
          authorName = author,
          languageKey = langKey,
          languageLabelAr = languageLabelAr(langKey),
          type = type,
          urlPattern = provider.urlPattern,
          version = provider.version,
          license = provider.license,
          attribution = if (sourceName.isNotBlank()) "${provider.attribution} • $sourceName" else provider.attribution,
          sourceName = sourceName,
        ),
      )
    }
    return out
  }

  suspend fun refreshLocalDownloadsSnapshot() {
    val downloaded = withContext(Dispatchers.IO) { scanDownloadedEditionsOnDisk() }
    _catalogState.update { it.copy(downloadedEditions = downloaded) }
  }

  private fun editionDir(slug: String): File {
    val safeSlug = slug.replace(NON_FILE_CHARS, "_")
    return File(baseDir, safeSlug)
  }

  private fun scanDownloadedEditionsOnDisk(): List<DownloadedTafsirEdition> {
    if (!baseDir.exists()) return emptyList()
    val dirs = baseDir.listFiles { f -> f.isDirectory } ?: return emptyList()
    val results = ArrayList<DownloadedTafsirEdition>(dirs.size)
    for (dir in dirs) {
      val metaFile = File(dir, META_FILE)
      if (!metaFile.exists()) continue
      val meta = runCatching { JSONObject(metaFile.readText(Charsets.UTF_8)) }.getOrNull() ?: continue
      val slug = meta.optString("slug").takeIf { it.isNotBlank() } ?: continue
      val editionId = meta.optString("editionId").ifBlank { slug }
      var surahCount = 0
      var totalBytes = 0L
      val files = dir.listFiles() ?: emptyArray()
      for (f in files) {
        if (f.isFile && f.name.startsWith("surah_") && f.name.endsWith(".json") && f.length() > 0L) {
          surahCount++
          totalBytes += f.length()
        }
      }
      if (surahCount == 0) continue
      results.add(
        DownloadedTafsirEdition(
          editionId = editionId,
          providerId = meta.optString("providerId", "spa5k_tafsir_api"),
          slug = slug,
          nameAr = meta.optString("nameAr", slug),
          authorName = meta.optString("authorName", ""),
          languageKey = meta.optString("languageKey", "arabic"),
          languageLabelAr = meta.optString("languageLabelAr", "العربية"),
          type = InsightSourceType.fromKey(meta.optString("type", "tafsir")),
          version = meta.optString("version", "1.0.0"),
          license = meta.optString("license", ""),
          attribution = meta.optString("attribution", ""),
          sourceName = meta.optString("sourceName", ""),
          completedSurahs = surahCount.coerceIn(0, TOTAL_SURAHS),
          totalSurahs = TOTAL_SURAHS,
          downloadedBytes = totalBytes,
          isComplete = surahCount >= TOTAL_SURAHS,
        ),
      )
    }
    return results.sortedWith(
      compareBy<DownloadedTafsirEdition>(
        { !it.isComplete },
        { if (it.languageKey == "arabic") 0 else 1 },
        { it.nameAr },
      ),
    )
  }

  fun startOrResumeDownload(
    edition: TafsirEditionInfo,
    forceUpdateAllSurahs: Boolean = false,
    onCompleted: (suspend () -> Unit)? = null,
  ) {
    val existing = activeJobs[edition.id]
    if (existing?.isActive == true) return

    val job = scope.launch(Dispatchers.IO) {
      val dir = editionDir(edition.slug)
      if (!dir.exists()) dir.mkdirs()
      writeEditionMeta(dir, edition)

      val alreadyDone = HashSet<Int>()
      var currentBytes = 0L
      if (!forceUpdateAllSurahs) {
        for (surah in 1..TOTAL_SURAHS) {
          val f = File(dir, "surah_$surah.json")
          if (f.exists() && f.length() > 2L) {
            alreadyDone.add(surah)
            currentBytes += f.length()
          }
        }
      }

      _downloadStates.update {
        it + (
          edition.id to TafsirDownloadState(
            editionId = edition.id,
            isDownloading = true,
            isUpdating = forceUpdateAllSurahs,
            completedSurahs = alreadyDone.size,
            totalSurahs = TOTAL_SURAHS,
            downloadedBytes = currentBytes,
            errorMessage = null,
          )
        )
      }

      try {
        val pendingSurahs = (1..TOTAL_SURAHS).filter { forceUpdateAllSurahs || !alreadyDone.contains(it) }
        val semaphore = Semaphore(CONCURRENT_SURAH_DOWNLOADS)
        val stateMutex = Mutex()
        var completedCount = alreadyDone.size
        var runningBytes = currentBytes
        var failureError: Throwable? = null

        coroutineScope {
          pendingSurahs.map { surahNum ->
            async(Dispatchers.IO) {
              semaphore.withPermit {
                if (failureError != null) return@withPermit
                val url = edition.urlPattern
                  .replace("{edition}", edition.slug)
                  .replace("{surah}", surahNum.toString())
                try {
                  val payloadBytes = executeGetBytes(url)
                  // Validate format before committing
                  parseSurahTafsirPayload(payloadBytes.decodeToString())
                  val targetFile = File(dir, "surah_$surahNum.json")
                  val tmpFile = File(dir, "surah_$surahNum.json.tmp")
                  tmpFile.writeBytes(payloadBytes)
                  if (!tmpFile.renameTo(targetFile)) {
                    payloadBytes.inputStream().use { inp ->
                      targetFile.outputStream().use { out -> inp.copyTo(out) }
                    }
                    tmpFile.delete()
                  }
                  stateMutex.withLock {
                    completedCount++
                    runningBytes += targetFile.length()
                    _downloadStates.update { map ->
                      map + (
                        edition.id to TafsirDownloadState(
                          editionId = edition.id,
                          isDownloading = true,
                          isUpdating = forceUpdateAllSurahs,
                          completedSurahs = completedCount,
                          totalSurahs = TOTAL_SURAHS,
                          downloadedBytes = runningBytes,
                          errorMessage = null,
                        )
                      )
                    }
                  }
                } catch (e: CancellationException) {
                  throw e
                } catch (e: Throwable) {
                  failureError = e
                  throw e
                }
              }
            }
          }.awaitAll()
        }

        _downloadStates.update { map ->
          map + (
            edition.id to TafsirDownloadState(
              editionId = edition.id,
              isDownloading = false,
              isUpdating = false,
              completedSurahs = TOTAL_SURAHS,
              totalSurahs = TOTAL_SURAHS,
              downloadedBytes = runningBytes,
              errorMessage = null,
            )
          )
        }
        refreshLocalDownloadsSnapshot()
        onCompleted?.invoke()
      } catch (e: CancellationException) {
        _downloadStates.update { map -> map - edition.id }
        refreshLocalDownloadsSnapshot()
        throw e
      } catch (e: Throwable) {
        _downloadStates.update { map ->
          val prev = map[edition.id]
          map + (
            edition.id to TafsirDownloadState(
              editionId = edition.id,
              isDownloading = false,
              isUpdating = false,
              completedSurahs = prev?.completedSurahs ?: 0,
              totalSurahs = TOTAL_SURAHS,
              downloadedBytes = prev?.downloadedBytes ?: 0L,
              errorMessage = e.message ?: "تعذر إكمال التحميل، يمكنك الاستكمال لاحقاً",
            )
          )
        }
        refreshLocalDownloadsSnapshot()
      } finally {
        activeJobs.remove(edition.id)
      }
    }
    activeJobs[edition.id] = job
  }

  fun updateDownloadedEdition(
    downloaded: DownloadedTafsirEdition,
    onCompleted: (suspend () -> Unit)? = null,
  ) {
    scope.launch(Dispatchers.IO) {
      val matchingAvailable = _catalogState.value.availableEditions.firstOrNull { it.slug == downloaded.slug }
      val providers = runCatching { loadRegistryProviders() }.getOrDefault(emptyList())
      val provider = providers.firstOrNull { it.id == downloaded.providerId } ?: providers.firstOrNull()
      val edition = matchingAvailable ?: TafsirEditionInfo(
        id = downloaded.editionId,
        providerId = provider?.id ?: downloaded.providerId,
        slug = downloaded.slug,
        nameAr = downloaded.nameAr,
        authorName = downloaded.authorName,
        languageKey = downloaded.languageKey,
        languageLabelAr = downloaded.languageLabelAr,
        type = downloaded.type,
        urlPattern = provider?.urlPattern
          ?: "https://cdn.jsdelivr.net/gh/spa5k/tafsir_api@main/tafsir/{edition}/{surah}.json",
        version = provider?.version ?: downloaded.version,
        license = provider?.license ?: downloaded.license,
        attribution = downloaded.attribution,
        sourceName = downloaded.sourceName,
      )
      startOrResumeDownload(edition, forceUpdateAllSurahs = true, onCompleted = onCompleted)
    }
  }

  fun cancelDownload(editionId: String) {
    activeJobs.remove(editionId)?.cancel()
    _downloadStates.update { it - editionId }
  }

  suspend fun deleteDownloadedEdition(editionId: String) {
    cancelDownload(editionId)
    withContext(Dispatchers.IO) {
      val dir = editionDir(editionId)
      if (dir.exists()) {
        dir.deleteRecursively()
      }
      refreshLocalDownloadsSnapshot()
    }
  }

  private fun writeEditionMeta(dir: File, edition: TafsirEditionInfo) {
    val obj = JSONObject().apply {
      put("editionId", edition.id)
      put("providerId", edition.providerId)
      put("slug", edition.slug)
      put("nameAr", edition.nameAr)
      put("authorName", edition.authorName)
      put("languageKey", edition.languageKey)
      put("languageLabelAr", edition.languageLabelAr)
      put("type", edition.type.key)
      put("version", edition.version)
      put("license", edition.license)
      put("attribution", edition.attribution)
      put("sourceName", edition.sourceName)
    }
    val tmp = File(dir, "$META_FILE.tmp")
    tmp.writeText(obj.toString(), Charsets.UTF_8)
    tmp.renameTo(File(dir, META_FILE))
  }

  suspend fun readAyahTextOffline(
    slug: String,
    surahId: Int,
    ayahNumber: Int,
  ): String? = withContext(Dispatchers.IO) {
    val file = File(editionDir(slug), "surah_$surahId.json")
    if (!file.exists() || file.length() == 0L) return@withContext null
    runCatching {
      val map = parseSurahTafsirPayload(file.readText(Charsets.UTF_8))
      cleanHtml(map[ayahNumber].orEmpty()).ifBlank { null }
    }.getOrNull()
  }

  /**
   * Parses BOTH real spa5k/tafsir_api per-surah JSON formats observed in the wild:
   * 1) `{"ayahs": [{"ayah": 1, "surah": 1, "text": "..."}]}`
   * 2) Top-level array `[{"ayah": 1, "surah": 1, "text": "..."}]`
   */
  internal fun parseSurahTafsirPayload(raw: String): Map<Int, String> {
    val trimmed = raw.trim()
    val arr = when {
      trimmed.startsWith("{") -> {
        val obj = JSONObject(trimmed)
        obj.optJSONArray("ayahs") ?: throw IOException("صيغة ملف السورة غير صالحة")
      }
      trimmed.startsWith("[") -> JSONArray(trimmed)
      else -> throw IOException("صيغة ملف التفسير غير معروفة")
    }
    val out = HashMap<Int, String>(arr.length())
    for (i in 0 until arr.length()) {
      val item = arr.optJSONObject(i) ?: continue
      val ayahNum = when {
        item.has("ayah") -> item.optInt("ayah", -1)
        item.has("verse_number") -> item.optInt("verse_number", -1)
        item.has("numberInSurah") -> item.optInt("numberInSurah", -1)
        item.has("verse_key") -> item.optString("verse_key").substringAfter(":").toIntOrNull() ?: -1
        else -> -1
      }
      if (ayahNum <= 0) continue
      val text = item.optString("text", "")
      if (text.isNotBlank()) {
        out[ayahNum] = text
      }
    }
    return out
  }

  private fun executeGetString(url: String): String {
    val req = Request.Builder().url(url).get().build()
    QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
      return resp.body?.string() ?: throw IOException("استجابة فارغة")
    }
  }

  private fun executeGetBytes(url: String): ByteArray {
    val req = Request.Builder().url(url).get().build()
    QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
      return resp.body?.bytes() ?: throw IOException("بيانات فارغة")
    }
  }

  private fun cleanHtml(raw: String): String {
    if (raw.isBlank()) return ""
    val withoutSups = raw.replace(SUP_REGEX, "")
    return HtmlCompat.fromHtml(withoutSups, HtmlCompat.FROM_HTML_MODE_COMPACT)
      .toString()
      .trim()
  }
}
