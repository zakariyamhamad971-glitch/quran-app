package com.example.data.prayer

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.quran.CreditSourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class AdhanSoundRegistrySnapshot(
  val version: String,
  val license: String,
  val attribution: String,
  val sounds: List<AdhanSoundRegistryEntry>,
)

class AdhanSoundRepository(private val context: Context) {

  suspend fun loadRegistry(): AdhanSoundRegistrySnapshot = withContext(Dispatchers.IO) {
    runCatching {
      val raw = context.assets.open("adhan_sounds_registry.json").bufferedReader().use { it.readText() }
      val root = JSONObject(raw)
      val arr = root.optJSONArray("sounds")
      val sounds = buildList {
        if (arr != null) {
          for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "").trim()
            if (id.isEmpty()) continue
            add(
              AdhanSoundRegistryEntry(
                id = id,
                nameAr = obj.optString("name_ar", id),
                muezzinAr = obj.optString("muezzin_ar", ""),
                sourceType = obj.optString("source_type", "user"),
                license = obj.optString("license", ""),
                attribution = obj.optString("attribution", ""),
                audioUrl = obj.optString("audio_url", "").trim().takeIf { it.isNotBlank() },
              ),
            )
          }
        }
      }
      AdhanSoundRegistrySnapshot(
        version = root.optString("version", "1.0.0"),
        license = root.optString("license", "System Default & User Local Audio (SAF)"),
        attribution = root.optString("attribution", ""),
        sounds = sounds,
      )
    }.getOrElse {
      AdhanSoundRegistrySnapshot(
        version = "1.0.0",
        license = "System Default & User Local Audio (SAF)",
        attribution = "نغمة النظام الافتراضية أو ملفات المستخدم المحلية",
        sounds = emptyList(),
      )
    }
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

  fun getLocalMuezzinAudioFile(muezzinId: String): java.io.File {
    val dir = java.io.File(context.filesDir, "muezzins")
    if (!dir.exists()) dir.mkdirs()
    return java.io.File(dir, "$muezzinId.mp3")
  }

  fun isMuezzinDownloaded(muezzinId: String): Boolean {
    val file = getLocalMuezzinAudioFile(muezzinId)
    return file.exists() && file.length() > 1024L
  }

  suspend fun downloadMuezzinAudio(entry: AdhanSoundRegistryEntry): Result<java.io.File> = withContext(Dispatchers.IO) {
    runCatching {
      val url = entry.audioUrl ?: error("لا يوجد رابط صوتي للمؤذن")
      val dest = getLocalMuezzinAudioFile(entry.id)
      val temp = java.io.File(dest.parentFile, "${dest.name}.tmp")
      val req = okhttp3.Request.Builder().url(url).build()
      val client = com.example.data.quran.QuranApiClient.okHttpClient
      val resp = client.newCall(req).execute()
      if (!resp.isSuccessful) error("فشل تنزيل ملف الأذان (${resp.code})")
      val body = resp.body ?: error("بيانات الصوت فارغة")
      body.byteStream().use { input ->
        temp.outputStream().use { output ->
          input.copyTo(output)
        }
      }
      temp.renameTo(dest)
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
      if (localFile.exists() && localFile.length() > 1024L) {
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
    val registry = loadRegistry()
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
          title = "ملفات صوت الأذان والتنبيهات",
          organization = "تنبيهات النظام الافتراضية وملفات المستخدم (SAF)",
          url = "",
          license = registry.license,
          usage = registry.attribution.ifBlank {
            "لا يتضمن التطبيق ملفات أذان مدمجة؛ يستخدم نغمة المنبه الافتراضية في النظام أو ملفاً صوتياً يختاره المستخدم."
          },
        ),
      )
    }
  }
}
