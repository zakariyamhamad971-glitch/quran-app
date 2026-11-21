package com.example.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.data.quran.QuranApiClient
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

object PdfManifestParser {
  private fun JsonReader.nextStringOrNull(): String? {
    return when (peek()) {
      JsonReader.Token.NULL -> {
        nextNull<Unit>()
        null
      }
      JsonReader.Token.STRING, JsonReader.Token.NUMBER -> nextString().trim()
      JsonReader.Token.BOOLEAN -> nextBoolean().toString()
      else -> {
        skipValue()
        null
      }
    }
  }

  private fun JsonReader.nextIntOrNull(): Int? {
    val s = nextStringOrNull() ?: return null
    return s.toIntOrNull() ?: s.toDoubleOrNull()?.toInt()
  }

  private fun JsonReader.nextDoubleOrNull(): Double? {
    val s = nextStringOrNull() ?: return null
    return s.toDoubleOrNull()
  }

  fun parseManifestJson(rawJson: String): List<PdfMushafEntry> {
    val out = ArrayList<PdfMushafEntry>()
    JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
      reader.isLenient = true
      when (reader.peek()) {
        JsonReader.Token.BEGIN_ARRAY -> readEntriesArray(reader, out)
        JsonReader.Token.BEGIN_OBJECT -> {
          var parsedArray = false
          reader.beginObject()
          while (reader.hasNext()) {
            reader.nextName()
            if (!parsedArray && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              parsedArray = true
              readEntriesArray(reader, out)
            } else {
              reader.skipValue()
            }
          }
          reader.endObject()
        }
        else -> throw IllegalStateException("تنسيق ملف المصاحف غير صالح")
      }
    }
    return out.sortedBy { it.order }
  }

  private fun readEntriesArray(reader: JsonReader, out: MutableList<PdfMushafEntry>) {
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var id = ""
      var order = Int.MAX_VALUE
      var file = ""
      var thumb = ""
      var titleAr = ""
      var riwayaAr = ""
      var pages = 604
      var sizeMB = 0.0
      val apiRiwayaIds = mutableListOf<Int>()

      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "id" -> id = reader.nextStringOrNull().orEmpty()
          "order" -> order = reader.nextIntOrNull() ?: Int.MAX_VALUE
          "file" -> file = reader.nextStringOrNull().orEmpty()
          "thumb" -> thumb = reader.nextStringOrNull().orEmpty()
          "titleAr" -> titleAr = reader.nextStringOrNull().orEmpty()
          "riwayaAr" -> riwayaAr = reader.nextStringOrNull().orEmpty()
          "pages" -> pages = (reader.nextIntOrNull() ?: 604).coerceAtLeast(1)
          "sizeMB" -> sizeMB = reader.nextDoubleOrNull() ?: 0.0
          "apiRiwayaIds" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              reader.beginArray()
              while (reader.hasNext()) {
                val rId = reader.nextIntOrNull()
                if (rId != null) apiRiwayaIds += rId
              }
              reader.endArray()
            } else {
              reader.skipValue()
            }
          }
          else -> reader.skipValue()
        }
      }
      reader.endObject()

      if (id.isNotBlank() && file.isNotBlank()) {
        out += PdfMushafEntry(
          id = id,
          order = order,
          file = file.trimStart('/'),
          thumb = thumb.trimStart('/'),
          titleAr = titleAr.ifBlank { riwayaAr },
          riwayaAr = riwayaAr.ifBlank { titleAr },
          pages = pages,
          sizeMB = sizeMB,
          apiRiwayaIds = apiRiwayaIds,
        )
      }
    }
    reader.endArray()
  }

  fun hasPdfMagicHeader(file: File): Boolean {
    if (!file.exists() || file.length() < 5L) return false
    return runCatching {
      FileInputStream(file).use { input ->
        val buf = ByteArray(5)
        val read = input.read(buf)
        read == 5 &&
          buf[0] == '%'.code.toByte() &&
          buf[1] == 'P'.code.toByte() &&
          buf[2] == 'D'.code.toByte() &&
          buf[3] == 'F'.code.toByte() &&
          buf[4] == '-'.code.toByte()
      }
    }.getOrDefault(false)
  }
}

class PdfMushafRepository(
  private val context: Context,
  private val scope: CoroutineScope,
  private val httpClient: OkHttpClient = QuranApiClient.okHttpClient,
) {
  private val persistentDir = File(context.filesDir, "pdf_mushafs").apply { mkdirs() }
  private val cachePdfDir = File(context.cacheDir, "pdf_mushafs").apply { mkdirs() }
  private val thumbCacheDir = File(context.cacheDir, "pdf_thumbs").apply { mkdirs() }
  private val manifestCacheFile = File(persistentDir, "manifest.json")

  private val transferJobs = ConcurrentHashMap<String, Job>()
  private val thumbMemoryCache = ConcurrentHashMap<String, Bitmap>()

  private val _transferStates = MutableStateFlow<Map<String, PdfMushafTransferState>>(emptyMap())
  val transferStates: StateFlow<Map<String, PdfMushafTransferState>> = _transferStates.asStateFlow()

  private val _offlineDownloadedIds = MutableStateFlow<Set<String>>(scanOfflineDownloadedIds())
  val offlineDownloadedIds: StateFlow<Set<String>> = _offlineDownloadedIds.asStateFlow()

  private fun offlinePdfFile(id: String): File = File(persistentDir, "$id.pdf")
  private fun cachePdfFile(id: String): File = File(cachePdfDir, "$id.pdf")
  private fun thumbFile(id: String): File = File(thumbCacheDir, "$id.img")

  private fun scanOfflineDownloadedIds(): Set<String> {
    val files = persistentDir.listFiles() ?: return emptySet()
    return files.mapNotNull { f ->
      if (f.isFile && f.name.endsWith(".pdf") && PdfManifestParser.hasPdfMagicHeader(f)) {
        f.name.removeSuffix(".pdf")
      } else {
        null
      }
    }.toHashSet()
  }

  fun refreshOfflineDownloadedIds() {
    _offlineDownloadedIds.value = scanOfflineDownloadedIds()
  }

  fun isOfflineDownloaded(mushafId: String): Boolean =
    PdfManifestParser.hasPdfMagicHeader(offlinePdfFile(mushafId))

  fun getLocalOrCachedPdfFile(mushafId: String): File? {
    val offline = offlinePdfFile(mushafId)
    if (PdfManifestParser.hasPdfMagicHeader(offline)) return offline
    val cached = cachePdfFile(mushafId)
    if (PdfManifestParser.hasPdfMagicHeader(cached)) return cached
    return null
  }

  private fun mapErrorToArabic(error: Throwable, contextLabel: String): String {
    return when (error) {
      is UnknownHostException, is ConnectException ->
        "لا يوجد اتصال بالإنترنت أثناء $contextLabel"
      is SocketTimeoutException ->
        "انتهت مهلة الاتصال بالخادم أثناء $contextLabel"
      else ->
        error.localizedMessage ?: "تعذر $contextLabel"
    }
  }

  suspend fun fetchManifest(forceRefresh: Boolean = false): Result<List<PdfMushafEntry>> = withContext(Dispatchers.IO) {
    runCatching {
      val cachedRaw = if (manifestCacheFile.exists() && manifestCacheFile.length() > 0L) {
        runCatching { manifestCacheFile.readText() }.getOrNull()
      } else {
        null
      }

      if (!forceRefresh && !cachedRaw.isNullOrBlank()) {
        // Still try refreshing silently only if needed; or prefer network first and fall back to cache.
      }

      val url = "${MUSHAF_BASE_URL}manifest.json"
      val req = Request.Builder().url(url).header("Accept", "application/json").build()
      val fetchedRaw = try {
        httpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) {
            throw IllegalStateException("تعذر جلب قائمة المصاحف (HTTP ${resp.code})")
          }
          resp.body?.string()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ملف قائمة المصاحف فارغ")
        }
      } catch (e: Exception) {
        if (!cachedRaw.isNullOrBlank()) {
          return@runCatching PdfManifestParser.parseManifestJson(cachedRaw)
        }
        throw IllegalStateException(mapErrorToArabic(e, "جلب قائمة المصاحف"), e)
      }

      val parsed = PdfManifestParser.parseManifestJson(fetchedRaw)
      if (parsed.isEmpty()) {
        if (!cachedRaw.isNullOrBlank()) {
          return@runCatching PdfManifestParser.parseManifestJson(cachedRaw)
        }
        throw IllegalStateException("قائمة المصاحف فارغة")
      }

      val tmp = File(persistentDir, "manifest.json.tmp")
      tmp.writeText(fetchedRaw)
      if (!tmp.renameTo(manifestCacheFile)) {
        manifestCacheFile.writeText(fetchedRaw)
        tmp.delete()
      }
      parsed
    }
  }

  fun readCachedManifestOrEmpty(): List<PdfMushafEntry> {
    if (!manifestCacheFile.exists() || manifestCacheFile.length() == 0L) return emptyList()
    return runCatching {
      PdfManifestParser.parseManifestJson(manifestCacheFile.readText())
    }.getOrDefault(emptyList())
  }

  suspend fun loadThumbnail(entry: PdfMushafEntry): Bitmap? = withContext(Dispatchers.IO) {
    thumbMemoryCache[entry.id]?.let { return@withContext it }
    if (entry.thumb.isBlank()) return@withContext null

    val localThumb = thumbFile(entry.id)
    if (localThumb.exists() && localThumb.length() > 64L) {
      decodeSampledThumb(localThumb)?.let { bmp ->
        thumbMemoryCache[entry.id] = bmp
        return@withContext bmp
      }
    }

    val url = "$MUSHAF_BASE_URL${entry.thumb}"
    val req = Request.Builder().url(url).build()
    val bytes = runCatching {
      httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        resp.body?.bytes()
      }
    }.getOrNull() ?: return@withContext null

    if (bytes.size < 64) return@withContext null
    runCatching {
      val tmp = File(thumbCacheDir, "${entry.id}.img.tmp")
      tmp.writeBytes(bytes)
      if (!tmp.renameTo(localThumb)) {
        localThumb.writeBytes(bytes)
        tmp.delete()
      }
      decodeSampledThumb(localThumb)?.also { bmp ->
        thumbMemoryCache[entry.id] = bmp
      }
    }.getOrNull()
  }

  private fun decodeSampledThumb(file: File): Bitmap? {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
    var sample = 1
    while (opts.outWidth / sample > 260 || opts.outHeight / sample > 380) {
      sample *= 2
    }
    val decodeOpts = BitmapFactory.Options().apply {
      inSampleSize = sample
      inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
  }

  fun startOfflineDownload(
    entry: PdfMushafEntry,
    onCompleted: suspend () -> Unit = {},
  ) {
    if (transferJobs[entry.id]?.isActive == true) return
    val job = scope.launch(Dispatchers.IO) {
      val target = offlinePdfFile(entry.id)
      try {
        updateTransferState(
          PdfMushafTransferState(
            mushafId = entry.id,
            isDownloadingOffline = true,
            progressPercent = 0,
          ),
        )
        // If already cached from online streaming, copy cleanly to persistent dir first or stream if not
        val cached = cachePdfFile(entry.id)
        if (PdfManifestParser.hasPdfMagicHeader(cached)) {
          val part = File(persistentDir, "${entry.id}.pdf.part")
          cached.copyTo(part, overwrite = true)
          if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
          }
          _offlineDownloadedIds.value = scanOfflineDownloadedIds()
          updateTransferState(PdfMushafTransferState(mushafId = entry.id))
          onCompleted()
          return@launch
        }

        downloadPdfToTarget(
          entry = entry,
          targetFile = target,
          isOfflineDownload = true,
        )
        _offlineDownloadedIds.value = scanOfflineDownloadedIds()
        updateTransferState(PdfMushafTransferState(mushafId = entry.id))
        onCompleted()
      } catch (e: CancellationException) {
        updateTransferState(PdfMushafTransferState(mushafId = entry.id))
        throw e
      } catch (e: Exception) {
        updateTransferState(
          PdfMushafTransferState(
            mushafId = entry.id,
            errorMessage = mapErrorToArabic(e, "تنزيل المصحف"),
          ),
        )
      } finally {
        transferJobs.remove(entry.id)
      }
    }
    transferJobs[entry.id] = job
  }

  suspend fun ensurePdfReadyForReader(entry: PdfMushafEntry): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
      getLocalOrCachedPdfFile(entry.id)?.let { return@runCatching it }

      // Cancel any conflicting job or wait? Start streaming download into cache
      val target = cachePdfFile(entry.id)
      updateTransferState(
        PdfMushafTransferState(
          mushafId = entry.id,
          isStreamingOpen = true,
          progressPercent = 0,
        ),
      )
      try {
        downloadPdfToTarget(
          entry = entry,
          targetFile = target,
          isOfflineDownload = false,
        )
        updateTransferState(PdfMushafTransferState(mushafId = entry.id))
        target
      } catch (e: CancellationException) {
        updateTransferState(PdfMushafTransferState(mushafId = entry.id))
        throw e
      } catch (e: Exception) {
        val msg = mapErrorToArabic(e, "فتح المصحف أونلاين")
        updateTransferState(
          PdfMushafTransferState(
            mushafId = entry.id,
            errorMessage = msg,
          ),
        )
        throw IllegalStateException(msg, e)
      }
    }
  }

  private suspend fun downloadPdfToTarget(
    entry: PdfMushafEntry,
    targetFile: File,
    isOfflineDownload: Boolean,
  ) = withContext(Dispatchers.IO) {
    targetFile.parentFile?.mkdirs()
    val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
    if (partFile.exists()) partFile.delete()

    val url = "$MUSHAF_BASE_URL${entry.file}"
    val req = Request.Builder().url(url).build()
    httpClient.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        throw IllegalStateException("تعذر تنزيل المصحف (HTTP ${resp.code})")
      }
      val body = resp.body ?: throw IllegalStateException("ملف المصحف فارغ")
      val headerTotal = body.contentLength()
      val estimatedTotal = if (headerTotal > 0L) headerTotal else (entry.sizeMB * 1024.0 * 1024.0).toLong()
      var downloaded = 0L
      var lastReportedPct = -1

      body.byteStream().use { input ->
        FileOutputStream(partFile).use { output ->
          val buffer = ByteArray(16 * 1024)
          while (true) {
            coroutineContext.ensureActive()
            val read = input.read(buffer)
            if (read == -1) break
            output.write(buffer, 0, read)
            downloaded += read
            val pct = if (estimatedTotal > 0L) {
              ((downloaded * 100L) / estimatedTotal).toInt().coerceIn(0, 99)
            } else {
              0
            }
            if (pct != lastReportedPct) {
              lastReportedPct = pct
              updateTransferState(
                PdfMushafTransferState(
                  mushafId = entry.id,
                  isDownloadingOffline = isOfflineDownload,
                  isStreamingOpen = !isOfflineDownload,
                  progressPercent = pct,
                  downloadedBytes = downloaded,
                  totalBytes = estimatedTotal,
                ),
              )
            }
          }
          output.flush()
        }
      }

      if (!PdfManifestParser.hasPdfMagicHeader(partFile)) {
        partFile.delete()
        throw IllegalStateException("الملف الذي تم تنزيله ليس PDF صالحًا")
      }
      if (!partFile.renameTo(targetFile)) {
        partFile.copyTo(targetFile, overwrite = true)
        partFile.delete()
      }
    }
  }

  fun cancelTransfer(mushafId: String) {
    transferJobs.remove(mushafId)?.cancel()
    File(persistentDir, "$mushafId.pdf.part").delete()
    File(cachePdfDir, "$mushafId.pdf.part").delete()
    updateTransferState(PdfMushafTransferState(mushafId = mushafId))
  }

  suspend fun deleteOfflinePdf(mushafId: String) = withContext(Dispatchers.IO) {
    cancelTransfer(mushafId)
    offlinePdfFile(mushafId).delete()
    cachePdfFile(mushafId).delete()
    _offlineDownloadedIds.value = scanOfflineDownloadedIds()
  }

  private fun updateTransferState(state: PdfMushafTransferState) {
    _transferStates.update { it + (state.mushafId to state) }
  }
}
