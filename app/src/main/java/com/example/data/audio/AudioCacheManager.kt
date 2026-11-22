package com.example.data.audio

import android.content.Context
import android.os.StatFs
import androidx.compose.runtime.Immutable
import com.example.data.integrity.Sha256Integrity
import com.example.data.quran.QuranApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@Immutable
data class ReciterStorageSummary(
  val downloadedSurahs: Set<Int> = emptySet(),
  val totalBytes: Long = 0L,
) {
  val downloadedCount: Int get() = downloadedSurahs.size
}

class LowStorageException(message: String) : IOException(message)

class PermanentHttpException(val statusCode: Int, message: String) : IOException(message)

class AudioCacheManager(context: Context) {
  private val appContext = context.applicationContext
  private val audioRoot = File(appContext.filesDir, "audio_cache").apply { mkdirs() }

  private val downloadClient: OkHttpClient = QuranApiClient.okHttpClient.newBuilder()
    .connectTimeout(25, TimeUnit.SECONDS)
    .readTimeout(45, TimeUnit.SECONDS)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

  private val _downloadProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
  val downloadProgress: StateFlow<Map<String, Float>> = _downloadProgress.asStateFlow()

  private val _downloadedKeys = MutableStateFlow<Set<String>>(emptySet())
  val downloadedKeys: StateFlow<Set<String>> = _downloadedKeys.asStateFlow()

  private val _reciterSummaries = MutableStateFlow<Map<String, ReciterStorageSummary>>(emptyMap())
  val reciterSummaries: StateFlow<Map<String, ReciterStorageSummary>> = _reciterSummaries.asStateFlow()

  @Volatile
  private var keysWarmed: Boolean = false

  companion object {
    const val MIN_FREE_STORAGE_BYTES = 8L * 1024L * 1024L
    const val SAFETY_HEADROOM_BYTES = 2L * 1024L * 1024L

    @Volatile
    private var sharedInstance: AudioCacheManager? = null

    fun getInstance(context: Context): AudioCacheManager {
      return sharedInstance ?: synchronized(this) {
        sharedInstance ?: AudioCacheManager(context).also { sharedInstance = it }
      }
    }

    fun formatBytesArabic(bytes: Long): String {
      if (bytes <= 0L) return "٠ م.ب"
      val kb = bytes / 1024.0
      val mb = kb / 1024.0
      val gb = mb / 1024.0
      return when {
        gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f ج.ب", gb)
        mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f م.ب", mb)
        else -> String.format(java.util.Locale.US, "%.0f ك.ب", kb.coerceAtLeast(1.0))
      }
    }
  }

  suspend fun refreshDownloadedKeys(force: Boolean = false) {
    if (keysWarmed && !force) return
    val scan = withContext(Dispatchers.IO) { scanAudioRoot() }
    _downloadedKeys.value = scan.first
    _reciterSummaries.value = scan.second
    keysWarmed = true
  }

  fun cacheKey(reciterKey: String, surahId: Int): String =
    "${reciterKey}_${padSurahId(surahId)}"

  fun padSurahId(surahId: Int): String = when {
    surahId < 10 -> "00$surahId"
    surahId < 100 -> "0$surahId"
    else -> surahId.toString()
  }

  fun getAudioRootDir(): File = audioRoot

  private fun reciterDir(reciterKey: String): File =
    File(audioRoot, reciterKey).apply { mkdirs() }

  fun audioFile(reciterKey: String, surahId: Int): File =
    File(reciterDir(reciterKey), "${padSurahId(surahId)}.mp3")

  fun tempAudioFile(reciterKey: String, surahId: Int): File =
    File(reciterDir(reciterKey), "${padSurahId(surahId)}.mp3.tmp")

  private fun shaFile(reciterKey: String, surahId: Int): File =
    File(reciterDir(reciterKey), "${padSurahId(surahId)}.mp3.sha256")

  fun isSurahCachedQuick(reciterKey: String, surahId: Int): Boolean {
    val key = cacheKey(reciterKey, surahId)
    if (_downloadedKeys.value.contains(key)) {
      val file = File(File(audioRoot, reciterKey), "${padSurahId(surahId)}.mp3")
      if (file.exists() && file.length() > 1024L) return true
    }
    val file = File(File(audioRoot, reciterKey), "${padSurahId(surahId)}.mp3")
    return file.exists() && file.length() > 1024L
  }

  fun availableStorageBytes(): Long {
    return runCatching {
      val stat = StatFs(audioRoot.absolutePath)
      if (stat.totalBytes <= 0L) Long.MAX_VALUE else stat.availableBytes
    }.getOrElse { Long.MAX_VALUE }
  }

  private fun scanAudioRoot(): Pair<Set<String>, Map<String, ReciterStorageSummary>> {
    val keys = mutableSetOf<String>()
    val summaries = mutableMapOf<String, ReciterStorageSummary>()
    audioRoot.listFiles()?.forEach { reciterDir ->
      if (reciterDir.isDirectory) {
        val rKey = reciterDir.name
        val surahs = mutableSetOf<Int>()
        var totalSize = 0L
        reciterDir.listFiles()?.forEach { file ->
          if (file.isFile && file.extension == "mp3" && file.length() > 1024L) {
            val surah = file.nameWithoutExtension.toIntOrNull()
            if (surah != null && surah in 1..114) {
              keys.add(cacheKey(rKey, surah))
              surahs.add(surah)
              totalSize += file.length()
            }
          }
        }
        if (surahs.isNotEmpty()) {
          summaries[rKey] = ReciterStorageSummary(
            downloadedSurahs = surahs,
            totalBytes = totalSize,
          )
        }
      }
    }
    return keys to summaries
  }

  private fun recomputeReciterSummary(reciterKey: String) {
    val dir = File(audioRoot, reciterKey)
    if (!dir.exists() || !dir.isDirectory) {
      _reciterSummaries.update { it - reciterKey }
      return
    }
    val surahs = mutableSetOf<Int>()
    var totalSize = 0L
    dir.listFiles()?.forEach { file ->
      if (file.isFile && file.extension == "mp3" && file.length() > 1024L) {
        val surah = file.nameWithoutExtension.toIntOrNull()
        if (surah != null && surah in 1..114) {
          surahs.add(surah)
          totalSize += file.length()
        }
      }
    }
    if (surahs.isEmpty()) {
      _reciterSummaries.update { it - reciterKey }
    } else {
      _reciterSummaries.update {
        it + (reciterKey to ReciterStorageSummary(downloadedSurahs = surahs, totalBytes = totalSize))
      }
    }
  }

  suspend fun getLocalAudioFileIfValid(reciterKey: String, surahId: Int): File? = withContext(Dispatchers.IO) {
    val file = File(File(audioRoot, reciterKey), "${padSurahId(surahId)}.mp3")
    val hashF = File(File(audioRoot, reciterKey), "${padSurahId(surahId)}.mp3.sha256")
    if (!file.exists() || file.length() <= 1024L) return@withContext null
    if (hashF.exists()) {
      val expected = runCatching { hashF.readText().trim().lowercase() }.getOrNull()
      val actual = runCatching { Sha256Integrity.sha256Hex(file) }.getOrNull()
      if (expected != null && actual != null && expected != actual) {
        file.delete()
        hashF.delete()
        _downloadedKeys.update { it - cacheKey(reciterKey, surahId) }
        recomputeReciterSummary(reciterKey)
        return@withContext null
      }
    }
    file
  }

  suspend fun downloadSurah(
    reciterKey: String,
    surahId: Int,
    url: String,
    onProgress: ((Float) -> Unit)? = null,
  ): Result<File> = withContext(Dispatchers.IO) {
    val key = cacheKey(reciterKey, surahId)
    val availBefore = availableStorageBytes()
    if (availBefore < MIN_FREE_STORAGE_BYTES) {
      return@withContext Result.failure(
        LowStorageException("مساحة التخزين منخفضة، تعذر إكمال التحميل"),
      )
    }

    updateProgress(key, 0.01f)
    onProgress?.invoke(0.01f)

    try {
      val dest = audioFile(reciterKey, surahId)
      val tmp = tempAudioFile(reciterKey, surahId)
      val existingTmpLen = if (tmp.exists() && tmp.isFile) tmp.length() else 0L

      val reqBuilder = Request.Builder().url(url)
      if (existingTmpLen > 0L) {
        reqBuilder.header("Range", "bytes=$existingTmpLen-")
      }
      val call = downloadClient.newCall(reqBuilder.build())

      val finalFile = call.execute().use { resp ->
        coroutineContext.ensureActive()

        if (resp.code == 416 && existingTmpLen > 0L) {
          tmp.delete()
          throw IOException("إعادة ضبط نطاق التحميل المؤقت")
        }
        if (resp.code in listOf(403, 404, 410)) {
          tmp.delete()
          throw PermanentHttpException(resp.code, "ملف السورة غير متوفر على الخادم (${resp.code})")
        }
        if (!resp.isSuccessful) {
          throw IOException("تعذر تحميل السورة (${resp.code})")
        }

        val body = resp.body ?: throw IOException("الملف الصوتي فارغ")
        val contentType = resp.header("Content-Type").orEmpty()
        if (contentType.isNotBlank() &&
          !contentType.contains("audio", ignoreCase = true) &&
          !contentType.contains("octet-stream", ignoreCase = true)
        ) {
          tmp.delete()
          throw PermanentHttpException(resp.code, "نوع الملف غير صالح للصوتيات")
        }

        val isResumingPartial = resp.code == 206 && existingTmpLen > 0L &&
          resp.header("Content-Range")?.trim()?.startsWith("bytes $existingTmpLen-") == true

        val bodyLen = body.contentLength()
        val expectedTotalBytes = if (isResumingPartial) {
          resp.header("Content-Range")
            ?.substringAfter("/")
            ?.trim()
            ?.toLongOrNull()
            ?: if (bodyLen > 0L) existingTmpLen + bodyLen else -1L
        } else {
          bodyLen
        }

        val remainingNeeded = when {
          expectedTotalBytes > 0L && isResumingPartial -> (expectedTotalBytes - existingTmpLen).coerceAtLeast(0L)
          expectedTotalBytes > 0L -> expectedTotalBytes
          else -> 0L
        }
        val availNow = availableStorageBytes()
        if (availNow < MIN_FREE_STORAGE_BYTES ||
          (remainingNeeded > 0L && availNow < remainingNeeded + SAFETY_HEADROOM_BYTES)
        ) {
          throw LowStorageException("مساحة التخزين منخفضة، تعذر إكمال التحميل")
        }

        if (!isResumingPartial && tmp.exists()) {
          tmp.delete()
        }

        var copied = if (isResumingPartial) existingTmpLen else 0L
        var lastReportedProgress = if (expectedTotalBytes > 0L && copied > 0L) {
          (copied.toFloat() / expectedTotalBytes.toFloat()).coerceIn(0.01f, 0.99f)
        } else {
          0.01f
        }
        updateProgress(key, lastReportedProgress)
        onProgress?.invoke(lastReportedProgress)

        try {
          body.byteStream().use { input ->
            FileOutputStream(tmp, isResumingPartial).use { output ->
              val buf = ByteArray(16 * 1024)
              while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buf)
                if (read <= 0) break
                output.write(buf, 0, read)
                copied += read
                if (expectedTotalBytes > 0L) {
                  val pct = (copied.toFloat() / expectedTotalBytes.toFloat()).coerceIn(0.01f, 0.99f)
                  if (pct - lastReportedProgress >= 0.03f) {
                    lastReportedProgress = pct
                    updateProgress(key, pct)
                    onProgress?.invoke(pct)
                  }
                }
              }
              output.flush()
            }
          }
        } catch (cancel: CancellationException) {
          throw cancel
        } catch (io: IOException) {
          val msg = io.message.orEmpty()
          if (msg.contains("ENOSPC", ignoreCase = true) ||
            msg.contains("No space left", ignoreCase = true) ||
            availableStorageBytes() < MIN_FREE_STORAGE_BYTES
          ) {
            throw LowStorageException("مساحة التخزين منخفضة، تعذر إكمال التحميل")
          }
          throw io
        }

        coroutineContext.ensureActive()

        if (copied <= 1024L || (expectedTotalBytes > 0L && copied < expectedTotalBytes)) {
          tmp.delete()
          throw IOException("حجم الملف الصوتي غير مكتمل")
        }

        val hash = Sha256Integrity.sha256Hex(tmp)
        val shaOut = shaFile(reciterKey, surahId)
        val shaTmp = File(shaOut.parentFile, "${shaOut.name}.tmp")
        shaTmp.writeText(hash)
        if (shaOut.exists()) shaOut.delete()
        if (!shaTmp.renameTo(shaOut)) {
          shaOut.writeText(hash)
          shaTmp.delete()
        }

        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
          tmp.copyTo(dest, overwrite = true)
          tmp.delete()
        }

        _downloadedKeys.update { it + key }
        recomputeReciterSummary(reciterKey)
        clearProgress(key)
        dest
      }

      Result.success(finalFile)
    } catch (cancel: CancellationException) {
      clearProgress(key)
      throw cancel
    } catch (t: Throwable) {
      clearProgress(key)
      Result.failure(t)
    }
  }

  suspend fun deleteTempFile(reciterKey: String, surahId: Int) = withContext(Dispatchers.IO) {
    File(File(audioRoot, reciterKey), "${padSurahId(surahId)}.mp3.tmp").delete()
  }

  suspend fun deleteCachedSurah(reciterKey: String, surahId: Int) = withContext(Dispatchers.IO) {
    File(File(audioRoot, reciterKey), "${padSurahId(surahId)}.mp3").delete()
    File(File(audioRoot, reciterKey), "${padSurahId(surahId)}.mp3.sha256").delete()
    File(File(audioRoot, reciterKey), "${padSurahId(surahId)}.mp3.tmp").delete()
    _downloadedKeys.update { it - cacheKey(reciterKey, surahId) }
    recomputeReciterSummary(reciterKey)
  }

  suspend fun deleteAllForReciter(reciterKey: String) = withContext(Dispatchers.IO) {
    val dir = File(audioRoot, reciterKey)
    if (dir.exists()) {
      dir.deleteRecursively()
    }
    val prefix = "${reciterKey}_"
    _downloadedKeys.update { current -> current.filterNot { it.startsWith(prefix) }.toSet() }
    _reciterSummaries.update { it - reciterKey }
  }

  private fun updateProgress(key: String, value: Float) {
    _downloadProgress.update { it + (key to value) }
  }

  private fun clearProgress(key: String) {
    _downloadProgress.update { it - key }
  }
}
