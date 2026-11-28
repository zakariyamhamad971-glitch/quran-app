package com.example.data.prayer

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Immutable
import com.example.data.quran.QuranApiClient
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

@Immutable
data class OnlineAdhanBackgroundOption(
  val id: String,
  val titleAr: String,
  val thumbUrl: String,
  val fullUrl: String,
  val attribution: String,
)

class AdhanBackgroundRepository(private val context: Context) {

  private fun bgDir(): File {
    val dir = File(context.filesDir, "adhan_bg")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  fun savePickedImage(uri: Uri): Result<Pair<String, String>> {
    return runCatching {
      val dest = File(bgDir(), "custom_gallery_bg.jpg")
      context.contentResolver.openInputStream(uri)?.use { input ->
        dest.outputStream().use { output ->
          input.copyTo(output)
        }
      } ?: error("تعذر قراءة الصورة المختارة")
      if (!dest.exists() || dest.length() < 512L) error("ملف الصورة غير صالح")
      dest.absolutePath to "صورة من المعرض"
    }
  }

  suspend fun fetchOnlineMosqueBackgrounds(): Result<List<OnlineAdhanBackgroundOption>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val titles = listOf(
          "File:Prophet's Mosque.jpg" to "المسجد النبوي الشريف",
          "File:Sheikh Zayed Mosque Silhouette.jpg" to "مسجد الشيخ زايد",
          "File:Masjid-al-haram-1-1024x602.jpg" to "المسجد الحرام بمكة",
          "File:Blue Mosque Istanbul.jpg" to "الجامع الأزرق بإسطنبول",
          "File:Sultan Qaboos Grand Mosque.jpg" to "جامع السلطان قابوس",
          "File:Al-Aqsa Mosque, Jerusalem (1).jpg" to "المسجد الأقصى المبارك",
        )
        val joinedTitles = URLEncoder.encode(titles.joinToString("|") { it.first }, "UTF-8")
        val url = "https://commons.wikimedia.org/w/api.php?action=query&titles=$joinedTitles&prop=imageinfo&iiprop=url|mime&iiurlwidth=1080&format=json"
        val req = Request.Builder()
          .url(url)
          .header("User-Agent", "QabasatAndroid/1.0 (Islamic Prayer & Quran App)")
          .build()
        val body = QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) error("تعذر جلب الخلفيات (${resp.code})")
          resp.body?.string().orEmpty()
        }
        val root = JSONObject(body)
        val pages = root.optJSONObject("query")?.optJSONObject("pages")
          ?: error("لا توجد نتائج خلفيات حالياً")

        val arabicTitleMap = titles.associate { (fileKey, ar) ->
          fileKey.removePrefix("File:").replace('_', ' ').lowercase() to ar
        }

        val out = mutableListOf<OnlineAdhanBackgroundOption>()
        val keys = pages.keys()
        while (keys.hasNext()) {
          val pageKey = keys.next()
          val page = pages.optJSONObject(pageKey) ?: continue
          if (page.has("missing")) continue
          val infoArr = page.optJSONArray("imageinfo") ?: continue
          val info = infoArr.optJSONObject(0) ?: continue
          val mime = info.optString("mime", "")
          if (!mime.startsWith("image/")) continue
          val thumbUrl = info.optString("thumburl", "").ifBlank { info.optString("url", "") }
          val fullUrl = info.optString("url", "").ifBlank { thumbUrl }
          if (fullUrl.isBlank()) continue
          val rawTitle = page.optString("title", "").removePrefix("File:")
          val arTitle = arabicTitleMap[rawTitle.lowercase()] ?: rawTitle.substringBeforeLast('.')
          val id = rawTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(50)
          out.add(
            OnlineAdhanBackgroundOption(
              id = id,
              titleAr = arTitle,
              thumbUrl = thumbUrl,
              fullUrl = fullUrl,
              attribution = "Wikimedia Commons",
            ),
          )
        }
        out
      }
    }

  suspend fun downloadOnlineBackground(option: OnlineAdhanBackgroundOption): Result<Pair<String, String>> =
    withContext(Dispatchers.IO) {
      runCatching {
        val dest = File(bgDir(), "online_${option.id}.jpg")
        if (!dest.exists() || dest.length() < 1024L) {
          val temp = File(bgDir(), "online_${option.id}.tmp")
          val targetUrl = option.thumbUrl.ifBlank { option.fullUrl }
          val req = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", "QabasatAndroid/1.0 (Islamic Prayer & Quran App)")
            .build()
          QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("فشل تنزيل الصورة (${resp.code})")
            val body = resp.body ?: error("بيانات الصورة فارغة")
            body.byteStream().use { input ->
              temp.outputStream().use { output ->
                input.copyTo(output)
              }
            }
          }
          if (temp.length() < 1024L) {
            temp.delete()
            error("ملف الصورة غير مكتمل")
          }
          if (dest.exists()) dest.delete()
          check(temp.renameTo(dest)) { "تعذر حفظ الصورة" }
        }
        dest.absolutePath to option.titleAr
      }
    }

  fun clearCustomBackgroundFiles() {
    runCatching {
      bgDir().listFiles()?.forEach { it.delete() }
    }
  }
}
