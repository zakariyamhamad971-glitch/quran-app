package com.example.data.audio

import android.content.Context
import com.example.data.integrity.Sha256Integrity
import com.example.data.quran.QuranApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

class AudioCacheManager(context: Context) {
  private val audioRoot = File(context.filesDir, "audio_cache").apply { mkdirs() }

  private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
  val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

  private val _downloadedKeys = MutableStateFlow<Set<String>>(emptySet())
  val downloadedKeys: StateFlow<Set<String>> = _downloadedKeys.asStateFlow()

  @Volatile
  private var keysWarmed: Boolean = false

  suspend fun refreshDownloadedKeys() {
    if (keysWarmed) return
    val scanned = withContext(Dispatchers.IO) { scanExistingKeys() }
    _downloadedKeys.value = scanned
    keysWarmed = true
  }

  fun cacheKey(reciterKey: String, surahId: Int): String =
    "${reciterKey}_${padSurahId(surahId)}"

  private fun padSurahId(surahId: Int): String = when {
    surahId < 10 -> "00$surahId"
    surahId < 100 -> "0$surahId"
    else -> surahId.toString()
  }

  private fun audioFile(reciterKey: String, surahId: Int): File =
    File(File(audioRoot, reciterKey).apply { mkdirs() }, "${padSurahId(surahId)}.mp3")

  private fun shaFile(reciterKey: String, surahId: Int): File =
    File(File(audioRoot, reciterKey).apply { mkdirs() }, "${padSurahId(surahId)}.mp3.sha256")

  private fun scanExistingKeys(): Set<String> {
    val keys = mutableSetOf<String>()
    audioRoot.listFiles()?.forEach { reciterDir ->
      if (reciterDir.isDirectory) {
        val rKey = reciterDir.name
        reciterDir.listFiles()?.forEach { file ->
          if (file.extension == "mp3" && file.length() > 1024L) {
            val surah = file.nameWithoutExtension.toIntOrNull()
            if (surah != null) keys.add(cacheKey(rKey, surah))
          }
        }
      }
    }
    return keys
  }

  suspend fun getLocalAudioFileIfValid(reciterKey: String, surahId: Int): File? = withContext(Dispatchers.IO) {
    val file = audioFile(reciterKey, surahId)
    val hashF = shaFile(reciterKey, surahId)
    if (!file.exists() || file.length() <= 1024L) return@withContext null
    if (hashF.exists()) {
      val expected = runCatching { hashF.readText().trim().lowercase() }.getOrNull()
      val actual = runCatching { Sha256Integrity.sha256Hex(file) }.getOrNull()
      if (expected != null && actual != null && expected != actual) {
        file.delete()
        hashF.delete()
        _downloadedKeys.value = _downloadedKeys.value - cacheKey(reciterKey, surahId)
        return@withContext null
      }
    }
    file
  }

  suspend fun downloadSurah(
    reciterKey: String,
    surahId: Int,
    url: String,
  ): Result<File> = withContext(Dispatchers.IO) {
    val key = cacheKey(reciterKey, surahId)
    updateProgress(key, 0.01f)
    runCatching {
      val req = Request.Builder().url(url).build()
      QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) error("تعذر تحميل السورة (${resp.code})")
        val body = resp.body ?: error("الملف الصوتي فارغ")
        val contentType = resp.header("Content-Type").orEmpty()
        if (contentType.isNotBlank() && !contentType.contains("audio", ignoreCase = true) && !contentType.contains("octet-stream", ignoreCase = true)) {
          error("نوع الملف غير صالح للصوتيات")
        }
        val total = body.contentLength()
        val dest = audioFile(reciterKey, surahId)
        val tmp = File(dest.parentFile, "${dest.name}.tmp")
        var copied = 0L
        var lastReportedProgress = 0.01f
        body.byteStream().use { input ->
          FileOutputStream(tmp).use { output ->
            val buf = ByteArray(16 * 1024)
            while (true) {
              val read = input.read(buf)
              if (read <= 0) break
              output.write(buf, 0, read)
              copied += read
              if (total > 0L) {
                val pct = (copied.toFloat() / total.toFloat()).coerceIn(0.01f, 0.99f)
                if (pct - lastReportedProgress >= 0.04f) {
                  lastReportedProgress = pct
                  updateProgress(key, pct)
                }
              }
            }
          }
        }
        if (copied <= 1024L) {
          tmp.delete()
          error("حجم الملف الصوتي غير مكتمل")
        }
        val hash = Sha256Integrity.sha256Hex(tmp)
        shaFile(reciterKey, surahId).writeText(hash)
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
        _downloadedKeys.value = _downloadedKeys.value + key
        clearProgress(key)
        dest
      }
    }.onFailure {
      clearProgress(key)
    }
  }

  suspend fun deleteCachedSurah(reciterKey: String, surahId: Int) = withContext(Dispatchers.IO) {
    audioFile(reciterKey, surahId).delete()
    shaFile(reciterKey, surahId).delete()
    _downloadedKeys.value = _downloadedKeys.value - cacheKey(reciterKey, surahId)
  }

  private fun updateProgress(key: String, value: Float) {
    _downloadProgress.value = _downloadProgress.value + (key to value)
  }

  private fun clearProgress(key: String) {
    _downloadProgress.value = _downloadProgress.value - key
  }
}
