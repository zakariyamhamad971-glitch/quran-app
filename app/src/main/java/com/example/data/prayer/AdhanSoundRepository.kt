package com.example.data.prayer

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.quran.CreditSourceInfo
import com.example.data.quran.QuranApiClient
import java.io.File
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class AdhanSoundRegistrySnapshot(
  val version: String,
  val license: String,
  val attribution: String,
  val sounds: List<AdhanSoundRegistryEntry>,
  val isFromCache: Boolean = false,
  val loadError: String? = null,
)

class AdhanSoundRepository(private val context: Context) {

  private fun catalogCacheFile(): File {
    val dir = File(context.filesDir, "muezzins")
    if (!dir.exists()) dir.mkdirs()
    return File(dir, "catalog_cache_v2.json")
  }

  suspend fun loadRegistry(forceRefresh: Boolean = false): AdhanSoundRegistrySnapshot =
    withContext(Dispatchers.IO) {
      val cached = if (!forceRefresh) readCachedCatalog() else null
      if (cached != null && cached.sounds.isNotEmpty() && !isCacheStale(catalogCacheFile())) {
        return@withContext cached.copy(isFromCache = true)
      }

      val fetched = runCatching { fetchRemoteCatalog() }.getOrElse { emptyList() }
      if (fetched.isNotEmpty()) {
        writeCachedCatalog(fetched)
        return@withContext AdhanSoundRegistrySnapshot(
          version = "2.0.0",
          license = "Public Domain / Open Audio (Archive.org & GitHub)",
          attribution = "مجموعات الأذان المفتوحة: Internet Archive و Kiwifu/adhan-mp3",
          sounds = fetched,
          isFromCache = false,
          loadError = null,
        )
      }

      if (cached != null && cached.sounds.isNotEmpty()) {
        return@withContext cached.copy(
          isFromCache = true,
          loadError = "تعذر تحديث القائمة عبر الإنترنت، تم عرض القائمة المحفوظة.",
        )
      }

      val localOnly = buildOfflineDownloadedFallback()
      AdhanSoundRegistrySnapshot(
        version = "2.0.0",
        license = "Open Audio / Offline Downloaded",
        attribution = "مكتبة المؤذنين الصوتية عبر الإنترنت",
        sounds = localOnly,
        isFromCache = true,
        loadError = if (localOnly.isEmpty()) {
          "تعذر الاتصال بمكتبة المؤذنين عبر الإنترنت. تحقق من الشبكة واضغط تحديث."
        } else {
          "تعذر الاتصال بالإنترنت، تم عرض الملفات المحمّلة محلياً فقط."
        },
      )
    }

  private fun isCacheStale(file: File): Boolean {
    if (!file.exists()) return true
    val ageMs = System.currentTimeMillis() - file.lastModified()
    return ageMs > 7L * 24L * 60L * 60L * 1000L
  }

  private fun readCachedCatalog(): AdhanSoundRegistrySnapshot? {
    val file = catalogCacheFile()
    if (!file.exists()) return null
    return runCatching {
      val root = JSONObject(file.readText())
      val arr = root.optJSONArray("sounds") ?: return null
      val sounds = buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val id = obj.optString("id", "").trim()
          val url = obj.optString("audio_url", "").trim().takeIf { it.isNotBlank() } ?: continue
          if (id.isEmpty()) continue
          add(
            AdhanSoundRegistryEntry(
              id = id,
              nameAr = obj.optString("name_ar", id),
              muezzinAr = obj.optString("muezzin_ar", id),
              sourceType = obj.optString("source_type", "remote"),
              license = obj.optString("license", "Open Audio"),
              attribution = obj.optString("attribution", ""),
              audioUrl = url,
            ),
          )
        }
      }
      AdhanSoundRegistrySnapshot(
        version = root.optString("version", "2.0.0"),
        license = root.optString("license", "Public Domain / Open Audio"),
        attribution = root.optString("attribution", ""),
        sounds = sounds,
      )
    }.getOrNull()
  }

  private fun writeCachedCatalog(sounds: List<AdhanSoundRegistryEntry>) {
    runCatching {
      val arr = JSONArray()
      sounds.forEach { entry ->
        arr.put(
          JSONObject().apply {
            put("id", entry.id)
            put("name_ar", entry.nameAr)
            put("muezzin_ar", entry.muezzinAr)
            put("source_type", entry.sourceType)
            put("license", entry.license)
            put("attribution", entry.attribution)
            put("audio_url", entry.audioUrl ?: "")
          },
        )
      }
      val root = JSONObject().apply {
        put("version", "2.0.0")
        put("license", "Public Domain / Open Audio")
        put("attribution", "Internet Archive (MakkahAzan, adhan.notifications, Mp3CollectionAdzan) & GitHub Kiwifu/adhan-mp3")
        put("sounds", arr)
      }
      catalogCacheFile().writeText(root.toString())
    }
  }

  private fun buildOfflineDownloadedFallback(): List<AdhanSoundRegistryEntry> {
    val dir = File(context.filesDir, "muezzins")
    if (!dir.exists()) return emptyList()
    return dir.listFiles()
      ?.filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) && it.length() > 1024L }
      ?.map { file ->
        val id = file.nameWithoutExtension
        AdhanSoundRegistryEntry(
          id = id,
          nameAr = "أذان محفوظ محلياً",
          muezzinAr = localizeMuezzinNameAr(id.replace('_', ' ')).ifBlank { id },
          sourceType = "local_cache",
          license = "Cached Local Audio",
          attribution = "ملف محفوظ بدون إنترنت",
          audioUrl = Uri.fromFile(file).toString(),
        )
      }
      .orEmpty()
  }

  private suspend fun fetchRemoteCatalog(): List<AdhanSoundRegistryEntry> = coroutineScope {
    val archiveNotifDeferred = async { fetchArchiveOrgCollection("adhan.notifications", "أذان وإشعارات المؤذنين") }
    val archiveMakkahDeferred = async { fetchArchiveOrgCollection("MakkahAzan", "أذان الحرم المكي والمدني") }
    val archiveWorldDeferred = async { fetchArchiveOrgCollection("Mp3CollectionAdzan", "مجموعة أذان العالم الإسلامي") }
    val githubDeferred = async { fetchKiwifuGithubCollection() }

    val merged = mutableListOf<AdhanSoundRegistryEntry>()
    merged += archiveNotifDeferred.await()
    merged += archiveMakkahDeferred.await()
    merged += githubDeferred.await()
    merged += archiveWorldDeferred.await()

    val deduped = LinkedHashMap<String, AdhanSoundRegistryEntry>()
    for (entry in merged) {
      val key = entry.id.lowercase(Locale.ROOT)
      if (!deduped.containsKey(key)) {
        deduped[key] = entry
      }
    }
    deduped.values.sortedWith(
      compareBy<AdhanSoundRegistryEntry>(
        { priorityScore(it) },
        { it.muezzinAr },
      ),
    )
  }

  private fun fetchArchiveOrgCollection(identifier: String, collectionTitleAr: String): List<AdhanSoundRegistryEntry> {
    return runCatching {
      val req = Request.Builder()
        .url("https://archive.org/metadata/$identifier")
        .build()
      val bodyText = QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return emptyList()
        resp.body?.string().orEmpty()
      }
      val root = JSONObject(bodyText)
      val files = root.optJSONArray("files") ?: return emptyList()
      buildList {
        for (i in 0 until files.length()) {
          val obj = files.optJSONObject(i) ?: continue
          val name = obj.optString("name", "").trim()
          if (!name.endsWith(".mp3", ignoreCase = true)) continue
          val source = obj.optString("source", "")
          if (source.equals("derivative", ignoreCase = true) && name.contains("_64kb", ignoreCase = true)) continue
          val rawTitle = obj.optString("title", "").takeIf { it.isNotBlank() }
            ?: name.substringAfterLast('/').substringBeforeLast('.')
          val cleanSlug = "${identifier}_${name.substringBeforeLast('.')}"
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(80)
          val encodedPath = name.split('/').joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
          }
          val localizedMuezzin = localizeMuezzinNameAr(rawTitle)
          val localizedTrack = localizeAdhanTitleAr(rawTitle, collectionTitleAr)
          add(
            AdhanSoundRegistryEntry(
              id = cleanSlug,
              nameAr = localizedTrack,
              muezzinAr = localizedMuezzin,
              sourceType = "archive_org",
              license = "Public Domain (Archive.org)",
              attribution = "Archive.org/$identifier",
              audioUrl = "https://archive.org/download/$identifier/$encodedPath",
            ),
          )
        }
      }
    }.getOrElse { emptyList() }
  }

  private fun fetchKiwifuGithubCollection(): List<AdhanSoundRegistryEntry> {
    return runCatching {
      val req = Request.Builder()
        .url("https://api.github.com/repos/Kiwifu/adhan-mp3/git/trees/HEAD?recursive=1")
        .header("Accept", "application/vnd.github+json")
        .build()
      val bodyText = QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return emptyList()
        resp.body?.string().orEmpty()
      }
      val root = JSONObject(bodyText)
      val sha = root.optString("sha", "main").ifBlank { "main" }
      val tree = root.optJSONArray("tree") ?: return emptyList()
      buildList {
        for (i in 0 until tree.length()) {
          val item = tree.optJSONObject(i) ?: continue
          if (item.optString("type") != "blob") continue
          val path = item.optString("path", "").trim()
          if (!path.endsWith(".mp3", ignoreCase = true)) continue
          val fileName = path.substringAfterLast('/').substringBeforeLast('.')
          val cleanSlug = "gh_${path.substringBeforeLast('.')}"
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .take(80)
          val encodedPath = path.split('/').joinToString("/") { seg ->
            URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
          }
          val localizedMuezzin = localizeMuezzinNameAr(fileName)
          val localizedTitle = localizeAdhanTitleAr(fileName, "مكتبة الأذان الصوتية")
          add(
            AdhanSoundRegistryEntry(
              id = cleanSlug,
              nameAr = localizedTitle,
              muezzinAr = localizedMuezzin,
              sourceType = "github",
              license = "Open Repository (Kiwifu/adhan-mp3)",
              attribution = "GitHub: Kiwifu/adhan-mp3",
              audioUrl = "https://raw.githubusercontent.com/Kiwifu/adhan-mp3/$sha/$encodedPath",
            ),
          )
        }
      }
    }.getOrElse { emptyList() }
  }

  private fun priorityScore(entry: AdhanSoundRegistryEntry): Int {
    val ar = entry.muezzinAr + " " + entry.nameAr
    return when {
      ar.contains("الحرم") || ar.contains("مكة") || ar.contains("الملا") -> 0
      ar.contains("المدينة") || ar.contains("النبوي") || ar.contains("الحذيفي") -> 1
      ar.contains("العفاسي") || ar.contains("القطامي") || ar.contains("عبد الباسط") || ar.contains("المنشاوي") -> 2
      ar.contains("الأقصى") || ar.contains("رفعت") || ar.contains("مصطفى إسماعيل") || ar.contains("السديس") -> 3
      else -> 4
    }
  }

  private fun localizeMuezzinNameAr(raw: String): String {
    val clean = raw
      .replace(Regex("[_\\-.]+"), " ")
      .replace(Regex("\\s+"), " ")
      .trim()
    val lower = clean.lowercase(Locale.ROOT)
    val knownMappings = listOf(
      listOf("afasy", "alafasy", "al-afasy", "mishary") to "الشيخ مشاري راشد العفاسي",
      listOf("qatami", "al-qatami", "qatamy") to "الشيخ ناصر القطامي",
      listOf("ali mullah", "ali ahmed mullah", "mulla") to "الشيخ علي أحمد ملا (الحرم المكي)",
      listOf("abdulbasit", "abdul basit", "abdulsamad", "abdelbasset") to "الشيخ عبد الباسط عبد الصمد",
      listOf("minshawi", "menshawy", "manshawi") to "الشيخ محمد صديق المنشاوي",
      listOf("mustafa ismail", "mostafa ismail") to "الشيخ مصطفى إسماعيل",
      listOf("sudais", "soudais") to "الشيخ عبد الرحمن السديس",
      listOf("shuraim", "shreim") to "الشيخ سعود الشريم",
      listOf("muaiqly", "maher", "muaieqly") to "الشيخ ماهر المعيقلي",
      listOf("ghamdi", "ghamidi") to "الشيخ سعد الغامدي",
      listOf("hudhaify", "hudaify", "hudhaifi") to "الشيخ علي الحذيفي",
      listOf("dossari", "dosari", "yasser") to "الشيخ ياسر الدوسري",
      listOf("abbad", "fares") to "الشيخ فارس عباد",
      listOf("shatri", "shatree") to "الشيخ أبو بكر الشاطري",
      listOf("husary", "hosary") to "الشيخ محمود خليل الحصري",
      listOf("refaat", "rifaat", "refat") to "الشيخ محمد رفعت",
      listOf("imadi", "emadi") to "الشيخ أحمد العمادي",
      listOf("hamathani", "hamadhani", "majed") to "الشيخ ماجد الهمذاني",
      listOf("slimane", "mokhtar", "hadj") to "الشيخ مختار الحاج سليمان",
      listOf("jibreel", "jebril", "jibril") to "الشيخ محمد جبريل",
      listOf("ajamy", "ajmi") to "الشيخ أحمد العجمي",
      listOf("bukhatir") to "الشيخ صلاح بو خاطر",
      listOf("rifai", "hani") to "الشيخ هاني الرفاعي",
      listOf("luhaidan", "luhaydan") to "الشيخ محمد اللحيدان",
      listOf("kalbani") to "الشيخ عادل الكلباني",
      listOf("juhany", "juhani") to "الشيخ عبد الله الجهني",
      listOf("bukhari", "essam bukhari") to "الشيخ عصام بخاري",
      listOf("hadrawi", "hadrawy") to "الشيخ فاروق حضراوي",
      listOf("fashny", "fashni") to "الشيخ طه الفشني",
      listOf("tarouti") to "الشيخ عبد الفتاح الطاروطي",
      listOf("naina", "nuaina") to "الشيخ أحمد نعينع",
      listOf("makkah", "mecca", "haram") to "مؤذن الحرم المكي",
      listOf("madinah", "medina", "madina", "nabawi") to "مؤذن الحرم النبوي",
      listOf("aqsa", "quds", "jerusalem") to "مؤذن المسجد الأقصى",
      listOf("cairo", "egypt", "azhar") to "أذان القاهرة / الأزهر",
      listOf("turk", "istanbul", "turkey") to "أذان إسطنبول",
      listOf("bosnia", "sarajevo") to "أذان البوسنة",
      listOf("malaysia", "indonesia") to "أذان ماليزيا وإندونيسيا",
    )
    for ((tokens, arabicName) in knownMappings) {
      if (tokens.any { lower.contains(it) }) return arabicName
    }
    if (clean.any { it in '\u0600'..'\u06FF' }) return clean
    return "مؤذن ($clean)"
  }

  private fun localizeAdhanTitleAr(raw: String, fallbackCollection: String): String {
    val lower = raw.lowercase(Locale.ROOT)
    val parts = buildList {
      if (lower.contains("fajr") || lower.contains("subh")) add("أذان الفجر") else add("الأذان")
      if (lower.contains("makkah") || lower.contains("mecca") || lower.contains("haram")) add("من مكة المكرمة")
      else if (lower.contains("madina") || lower.contains("medina")) add("من المدينة المنورة")
      else if (lower.contains("aqsa")) add("من المسجد الأقصى")
      if (lower.contains("dua") || lower.contains("duaa")) add("مع الدعاء بعد الأذان")
    }
    return if (parts.size > 1) parts.joinToString(" • ") else fallbackCollection
  }

  fun persistReadPermissionAndResolveName(uri: Uri): String? {
    runCatching {
      context.contentResolver.takePersistableUriPermission(
        uri,
        Intent.FLAG_GRANT_READ_URI_PERMISSION,
      )
    }
    return queryDisplayName(uri)
  }

  fun queryDisplayName(uri: Uri): String? {
    return runCatching {
      context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
          if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) cursor.getString(idx)?.takeIf { it.isNotBlank() } else null
          } else {
            null
          }
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
  }

  fun getLocalMuezzinAudioFile(muezzinId: String): File {
    val dir = File(context.filesDir, "muezzins")
    if (!dir.exists()) dir.mkdirs()
    val safeId = muezzinId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80)
    return File(dir, "$safeId.mp3")
  }

  fun isMuezzinDownloaded(muezzinId: String): Boolean {
    val file = getLocalMuezzinAudioFile(muezzinId)
    return file.exists() && file.length() > 4096L
  }

  suspend fun downloadMuezzinAudio(entry: AdhanSoundRegistryEntry): Result<File> =
    withContext(Dispatchers.IO) {
      runCatching {
        val url = entry.audioUrl ?: error("لا يوجد رابط صوتي للمؤذن")
        val dest = getLocalMuezzinAudioFile(entry.id)
        if (dest.exists() && dest.length() > 4096L) return@runCatching dest
        val temp = File(dest.parentFile, "${dest.name}.tmp")
        val req = Request.Builder().url(url).build()
        QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) error("فشل تنزيل ملف الأذان (${resp.code})")
          val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
          if (contentType.contains("text/html")) error("الرابط لم يعد ملفاً صوتياً صالحاً")
          val body = resp.body ?: error("بيانات الصوت فارغة")
          body.byteStream().use { input ->
            temp.outputStream().use { output ->
              input.copyTo(output)
            }
          }
        }
        if (temp.length() < 4096L) {
          temp.delete()
          error("حجم ملف الصوت غير مكتمل")
        }
        if (dest.exists()) dest.delete()
        check(temp.renameTo(dest)) { "تعذر حفظ ملف الصوت" }
        dest
      }
    }

  fun deleteDownloadedMuezzin(muezzinId: String): Boolean {
    val file = getLocalMuezzinAudioFile(muezzinId)
    return if (file.exists()) file.delete() else true
  }

  fun resolvePlayableUri(config: PrayerAlarmConfig): Uri? {
    if (config.mode == PrayerAlarmMode.SILENT) return null
    if (config.mode == PrayerAlarmMode.MUEZZIN && !config.muezzinId.isNullOrBlank()) {
      val localFile = getLocalMuezzinAudioFile(config.muezzinId)
      if (localFile.exists() && localFile.length() > 4096L) {
        return Uri.fromFile(localFile)
      }
      if (!config.customUri.isNullOrBlank()) {
        return runCatching { Uri.parse(config.customUri) }.getOrNull()
      }
    }
    if (config.mode == PrayerAlarmMode.CUSTOM_URI && !config.customUri.isNullOrBlank()) {
      val parsed = runCatching { Uri.parse(config.customUri) }.getOrNull()
      if (parsed != null && isUriReadable(parsed)) {
        return parsed
      }
    }
    return resolveSystemDefaultAlarmUri()
  }

  fun resolveSystemDefaultAlarmUri(): Uri? {
    return RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
      ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
  }

  private fun isUriReadable(uri: Uri): Boolean {
    return runCatching {
      context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)
  }

  suspend fun loadAdhanCredits(): List<CreditSourceInfo> {
    return buildList {
      add(
        CreditSourceInfo(
          id = "batoulapps_adhan2",
          title = "مكتبة حساب مواقيت الصلاة (Adhan for Kotlin)",
          organization = "Batoul Apps (batoulapps/adhan-java / adhan2)",
          url = "https://github.com/batoulapps/adhan-java",
          license = "MIT License",
          usage = "حساب مواقيت الصلاة الفلكية محلياً على الجهاز دون إنترنت (نسخة adhan2 0.0.6).",
        ),
      )
      add(
        CreditSourceInfo(
          id = "adhan_sounds_registry",
          title = "مكتبة أصوات الأذان والمؤذنين",
          organization = "Internet Archive (MakkahAzan / adhan.notifications / Mp3CollectionAdzan) و GitHub (Kiwifu/adhan-mp3)",
          url = "https://archive.org/details/MakkahAzan",
          license = "Public Domain / Open Audio",
          usage = "جلب أصوات المؤذنين للاستماع والتنزيل للعمل بدون إنترنت أو اختيار ملف محلي.",
        ),
      )
    }
  }
}
