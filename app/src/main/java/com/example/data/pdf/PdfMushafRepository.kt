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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import javax.net.ssl.SSLException

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
      var cropLeftPt = 0f
      var cropRightPt = 0f

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
          "cropLeftPt" -> cropLeftPt = reader.nextDoubleOrNull()?.toFloat() ?: 0f
          "cropRightPt" -> cropRightPt = reader.nextDoubleOrNull()?.toFloat() ?: 0f
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
          cropLeftPt = cropLeftPt,
          cropRightPt = cropRightPt,
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

  /**
   * A local file is stale only if it is empty/missing or if the server's HTTP HEAD Content-Length
   * (checked when online) is known (> 0) and differs from the local file byte length.
   * Never compares against rounded manifest sizeMB, and never rejects a valid file offline.
   */
  fun isStaleByRemoteContentLength(
    localBytes: Long,
    remoteContentLength: Long?,
  ): Boolean {
    if (localBytes <= 0L) return true
    if (remoteContentLength == null || remoteContentLength <= 0L) return false
    return remoteContentLength != localBytes
  }
}

object ColorsGuideParser {
  private fun JsonReader.nextRawScalarString(): String? {
    return when (peek()) {
      JsonReader.Token.NULL -> {
        nextNull<Unit>()
        null
      }
      JsonReader.Token.STRING -> nextString().trim()
      JsonReader.Token.NUMBER -> normalizeNumericId(nextString().trim())
      JsonReader.Token.BOOLEAN -> nextBoolean().toString()
      else -> {
        skipValue()
        null
      }
    }
  }

  private fun normalizeNumericId(raw: String): String {
    val asDouble = raw.toDoubleOrNull()
    if (asDouble != null && asDouble == asDouble.toLong().toDouble()) {
      return asDouble.toLong().toString()
    }
    return raw
  }

  fun normalizeId(raw: String?): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty()) return ""
    return normalizeNumericId(trimmed)
  }

  fun parseColorsGuideJson(rawJson: String): ColorsGuideData {
    var version = 1
    val sections = ArrayList<RiwayaColorGuideSection>()
    JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
      reader.isLenient = true
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        throw IllegalStateException("تنسيق دليل الألوان غير صالح")
      }
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "version" -> {
            version = reader.nextRawScalarString()?.toIntOrNull() ?: 1
          }
          "riwayat" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              readRiwayatArray(reader, sections)
            } else {
              reader.skipValue()
            }
          }
          else -> reader.skipValue()
        }
      }
      reader.endObject()
    }
    return ColorsGuideData(version = version, riwayat = sections)
  }

  private fun readRiwayatArray(
    reader: JsonReader,
    out: MutableList<RiwayaColorGuideSection>,
  ) {
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var id = ""
      var riwayaAr = ""
      val items = ArrayList<RiwayaColorGuideItem>()

      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "id" -> id = reader.nextRawScalarString().orEmpty()
          "riwayaAr" -> riwayaAr = reader.nextRawScalarString().orEmpty()
          "items" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              readItemsArray(reader, items)
            } else {
              reader.skipValue()
            }
          }
          else -> reader.skipValue()
        }
      }
      reader.endObject()

      if (riwayaAr.isNotEmpty() || items.isNotEmpty()) {
        out += RiwayaColorGuideSection(
          id = id,
          riwayaAr = riwayaAr,
          items = items,
        )
      }
    }
    reader.endArray()
  }

  private fun readItemsArray(
    reader: JsonReader,
    out: MutableList<RiwayaColorGuideItem>,
  ) {
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var label = ""
      var color = ""
      var styleRaw = ""

      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "label" -> label = reader.nextRawScalarString().orEmpty()
          "color" -> color = reader.nextRawScalarString().orEmpty()
          "style" -> styleRaw = reader.nextRawScalarString().orEmpty()
          else -> reader.skipValue()
        }
      }
      reader.endObject()

      val style = when (styleRaw.trim().lowercase()) {
        "ring" -> ColorSwatchStyle.RING
        else -> ColorSwatchStyle.FILLED
      }
      if (label.isNotEmpty() || color.isNotEmpty()) {
        out += RiwayaColorGuideItem(
          label = label,
          colorHex = color,
          style = style,
        )
      }
    }
    reader.endArray()
  }

  fun orderSectionsByManifest(
    sections: List<RiwayaColorGuideSection>,
    manifest: List<PdfMushafEntry>,
  ): List<RiwayaColorGuideSection> {
    if (sections.isEmpty() || manifest.isEmpty()) return sections
    val orderedManifest = manifest.sortedBy { it.order }
    val manifestOrderById = HashMap<String, Int>(orderedManifest.size)
    orderedManifest.forEachIndexed { index, entry ->
      val normId = normalizeId(entry.id)
      if (normId.isNotEmpty() && !manifestOrderById.containsKey(normId)) {
        manifestOrderById[normId] = index
      }
    }
    return sections
      .withIndex()
      .sortedWith(
        compareBy<IndexedValue<RiwayaColorGuideSection>>(
          { manifestOrderById[normalizeId(it.value.id)] ?: Int.MAX_VALUE },
          { it.index },
        ),
      )
      .map { it.value }
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
  private val colorsGuideCacheFile = File(persistentDir, "colors_guide.json")

  private val transferJobs = ConcurrentHashMap<String, Job>()
  private val mushafMutexes = ConcurrentHashMap<String, Mutex>()
  private val remoteContentLengthCache = ConcurrentHashMap<String, Long>()
  private val thumbMemoryCache = ConcurrentHashMap<String, Bitmap>()

  private fun mutexFor(mushafId: String): Mutex =
    mushafMutexes.getOrPut(mushafId) { Mutex() }

  private val _transferStates = MutableStateFlow<Map<String, PdfMushafTransferState>>(emptyMap())
  val transferStates: StateFlow<Map<String, PdfMushafTransferState>> = _transferStates.asStateFlow()

  private val _offlineDownloadedIds = MutableStateFlow<Set<String>>(scanOfflineDownloadedIds())
  val offlineDownloadedIds: StateFlow<Set<String>> = _offlineDownloadedIds.asStateFlow()

  private val _localOrCachedIds = MutableStateFlow<Set<String>>(scanLocalOrCachedIds())
  val localOrCachedIds: StateFlow<Set<String>> = _localOrCachedIds.asStateFlow()

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

  private fun scanLocalOrCachedIds(): Set<String> {
    val out = scanOfflineDownloadedIds().toHashSet()
    cachePdfDir.listFiles()?.forEach { f ->
      if (f.isFile && f.name.endsWith(".pdf") && PdfManifestParser.hasPdfMagicHeader(f)) {
        out += f.name.removeSuffix(".pdf")
      }
    }
    return out
  }

  private fun refreshFileStateFlows() {
    _offlineDownloadedIds.value = scanOfflineDownloadedIds()
    _localOrCachedIds.value = scanLocalOrCachedIds()
  }

  fun refreshOfflineDownloadedIds() {
    refreshFileStateFlows()
  }

  fun isOfflineDownloaded(mushafId: String): Boolean =
    !isLocalFileInvalid(offlinePdfFile(mushafId))

  fun isLocalFileInvalid(file: File): Boolean =
    !file.exists() || file.length() <= 0L || !PdfManifestParser.hasPdfMagicHeader(file)

  private fun fetchRemoteContentLengthOrNull(
    entry: PdfMushafEntry,
    useCached: Boolean = true,
  ): Long? {
    if (useCached) {
      remoteContentLengthCache[entry.id]?.let { return it }
    }
    val url = "$MUSHAF_BASE_URL${entry.file}"
    val req = Request.Builder().url(url).head().build()
    val len = runCatching {
      httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@use null
        resp.header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { it > 0L }
      }
    }.getOrNull()
    if (len != null && len > 0L) {
      remoteContentLengthCache[entry.id] = len
    }
    return len
  }

  suspend fun isFileStaleOnline(
    file: File,
    entry: PdfMushafEntry,
    forceHeadCheck: Boolean = false,
  ): Boolean = withContext(Dispatchers.IO) {
    if (isLocalFileInvalid(file)) return@withContext true
    val remoteLen = fetchRemoteContentLengthOrNull(entry, useCached = !forceHeadCheck)
    PdfManifestParser.isStaleByRemoteContentLength(file.length(), remoteLen)
  }

  suspend fun pruneStaleFilesAndCollectRedownloadEntries(
    entries: List<PdfMushafEntry>,
    forceHeadCheck: Boolean = false,
    onBeforeDelete: suspend (String) -> Unit = {},
  ): List<PdfMushafEntry> = withContext(Dispatchers.IO) {
    if (entries.isEmpty()) return@withContext emptyList()
    val staleOfflineEntries = ArrayList<PdfMushafEntry>()
    var changed = false

    for (entry in entries) {
      if (transferJobs[entry.id]?.isActive == true) continue
      mutexFor(entry.id).withLock {
        if (transferJobs[entry.id]?.isActive == true) return@withLock
        val offline = offlinePdfFile(entry.id)
        val cached = cachePdfFile(entry.id)
        if (offline.exists() && isFileStaleOnline(offline, entry, forceHeadCheck = forceHeadCheck)) {
          onBeforeDelete(entry.id)
          offline.delete()
          cached.delete()
          staleOfflineEntries += entry
          changed = true
        } else if (cached.exists() && isFileStaleOnline(cached, entry, forceHeadCheck = forceHeadCheck)) {
          onBeforeDelete(entry.id)
          cached.delete()
          changed = true
        }
      }
    }

    if (changed) {
      refreshFileStateFlows()
    }
    staleOfflineEntries
  }

  fun getLocalOrCachedPdfFile(mushafId: String): File? {
    val offline = offlinePdfFile(mushafId)
    if (!isLocalFileInvalid(offline)) return offline
    val cached = cachePdfFile(mushafId)
    if (!isLocalFileInvalid(cached)) return cached
    return null
  }

  private fun mapErrorToArabic(error: Throwable, contextLabel: String): String {
    return when (error) {
      is UnknownHostException, is ConnectException ->
        "لا يوجد اتصال بالإنترنت أثناء $contextLabel"
      is SocketTimeoutException ->
        "انتهت مهلة الاتصال بالخادم أثناء $contextLabel"
      is SSLException ->
        "تعذر تأمين الاتصال بالخادم أثناء $contextLabel"
      else -> "تعذر $contextLabel"
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

  fun readCachedColorsGuideOrNull(): ColorsGuideData? {
    if (!colorsGuideCacheFile.exists() || colorsGuideCacheFile.length() == 0L) return null
    return runCatching {
      ColorsGuideParser.parseColorsGuideJson(colorsGuideCacheFile.readText())
    }.getOrNull()
  }

  suspend fun fetchColorsGuide(): Result<ColorsGuideData> = withContext(Dispatchers.IO) {
    runCatching {
      val cachedData = readCachedColorsGuideOrNull()
      val url = "${MUSHAF_BASE_URL}colors_guide.json"
      val req = Request.Builder().url(url).header("Accept", "application/json").build()
      val fetchedRaw = try {
        httpClient.newCall(req).execute().use { resp ->
          if (!resp.isSuccessful) {
            throw IllegalStateException("HTTP ${resp.code}")
          }
          resp.body?.string()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Empty colors_guide.json")
        }
      } catch (e: Exception) {
        if (cachedData != null) {
          return@runCatching cachedData
        }
        throw IllegalStateException("تعذر تحميل دليل الألوان، تحقق من الاتصال", e)
      }

      val parsed = try {
        ColorsGuideParser.parseColorsGuideJson(fetchedRaw)
      } catch (e: Exception) {
        if (cachedData != null) {
          return@runCatching cachedData
        }
        throw IllegalStateException("تعذر تحميل دليل الألوان، تحقق من الاتصال", e)
      }

      val tmp = File(persistentDir, "colors_guide.json.tmp")
      tmp.writeText(fetchedRaw)
      if (!tmp.renameTo(colorsGuideCacheFile)) {
        colorsGuideCacheFile.writeText(fetchedRaw)
        tmp.delete()
      }
      parsed
    }
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
        var copiedFromValidCache = false
        mutexFor(entry.id).withLock {
          updateTransferState(
            PdfMushafTransferState(
              mushafId = entry.id,
              isDownloadingOffline = true,
              progressPercent = 0,
            ),
          )
          val cached = cachePdfFile(entry.id)
          if (cached.exists() && isLocalFileInvalid(cached)) {
            cached.delete()
          }
          if (cached.exists() && !isLocalFileInvalid(cached)) {
            val part = File(persistentDir, "${entry.id}.pdf.part")
            cached.copyTo(part, overwrite = true)
            if (part.length() == cached.length() && PdfManifestParser.hasPdfMagicHeader(part)) {
              if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
              }
              if (!isLocalFileInvalid(target)) {
                refreshFileStateFlows()
                updateTransferState(PdfMushafTransferState(mushafId = entry.id))
                copiedFromValidCache = true
              } else {
                target.delete()
              }
            } else {
              part.delete()
            }
          }
          if (!copiedFromValidCache) {
            downloadPdfToTarget(
              entry = entry,
              targetFile = target,
              isOfflineDownload = true,
            )
            refreshFileStateFlows()
            updateTransferState(PdfMushafTransferState(mushafId = entry.id))
          }
        }
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

  suspend fun forceRedownloadOffline(
    entry: PdfMushafEntry,
    onBeforeDelete: suspend () -> Unit = {},
    onCompleted: suspend () -> Unit = {},
  ) {
    withContext(Dispatchers.IO) {
      val prev = transferJobs[entry.id]
      cancelTransfer(entry.id)
      runCatching { prev?.join() }
      mutexFor(entry.id).withLock {
        onBeforeDelete()
        offlinePdfFile(entry.id).delete()
        cachePdfFile(entry.id).delete()
        remoteContentLengthCache.remove(entry.id)
        refreshFileStateFlows()
      }
    }
    startOfflineDownload(entry, onCompleted = onCompleted)
  }

  suspend fun ensurePdfReadyForReader(
    entry: PdfMushafEntry,
    onBeforeDeleteStale: suspend (String) -> Unit = {},
  ): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
      // Wait for any in-flight download for this mushaf to finish atomically before checking/opening
      transferJobs[entry.id]?.let { inFlight ->
        if (inFlight.isActive) {
          inFlight.join()
        }
      }

      mutexFor(entry.id).withLock {
        transferJobs[entry.id]?.let { inFlight ->
          if (inFlight.isActive) {
            inFlight.join()
          }
        }

        val offline = offlinePdfFile(entry.id)
        var hadInvalidOffline = false
        if (offline.exists()) {
          if (!isLocalFileInvalid(offline)) {
            return@withLock offline
          }
          onBeforeDeleteStale(entry.id)
          offline.delete()
          hadInvalidOffline = true
          refreshFileStateFlows()
        }

        val cached = cachePdfFile(entry.id)
        if (cached.exists()) {
          if (!hadInvalidOffline && !isLocalFileInvalid(cached)) {
            return@withLock cached
          }
          onBeforeDeleteStale(entry.id)
          cached.delete()
          refreshFileStateFlows()
        }

        val target = if (hadInvalidOffline) offline else cached
        updateTransferState(
          PdfMushafTransferState(
            mushafId = entry.id,
            isDownloadingOffline = hadInvalidOffline,
            isStreamingOpen = !hadInvalidOffline,
            progressPercent = 0,
          ),
        )
        try {
          downloadPdfToTarget(
            entry = entry,
            targetFile = target,
            isOfflineDownload = hadInvalidOffline,
          )
          refreshFileStateFlows()
          updateTransferState(PdfMushafTransferState(mushafId = entry.id))
          target
        } catch (e: CancellationException) {
          updateTransferState(PdfMushafTransferState(mushafId = entry.id))
          throw e
        } catch (e: Exception) {
          val msg = mapErrorToArabic(e, "فتح المصحف")
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
  }

  suspend fun invalidateAndRedownloadForReader(
    entry: PdfMushafEntry,
    onBeforeDelete: suspend () -> Unit = {},
  ): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
      val prev = transferJobs[entry.id]
      cancelTransfer(entry.id)
      runCatching { prev?.join() }

      mutexFor(entry.id).withLock {
        val offline = offlinePdfFile(entry.id)
        val cached = cachePdfFile(entry.id)
        val preferOfflineTarget = offline.exists()
        onBeforeDelete()
        offline.delete()
        cached.delete()
        File(persistentDir, "${entry.id}.pdf.part").delete()
        File(cachePdfDir, "${entry.id}.pdf.part").delete()
        remoteContentLengthCache.remove(entry.id)
        refreshFileStateFlows()

        val target = if (preferOfflineTarget) offline else cached
        updateTransferState(
          PdfMushafTransferState(
            mushafId = entry.id,
            isDownloadingOffline = preferOfflineTarget,
            isStreamingOpen = !preferOfflineTarget,
            progressPercent = 0,
          ),
        )
        try {
          downloadPdfToTarget(
            entry = entry,
            targetFile = target,
            isOfflineDownload = preferOfflineTarget,
          )
          refreshFileStateFlows()
          updateTransferState(PdfMushafTransferState(mushafId = entry.id))
          target
        } catch (e: CancellationException) {
          updateTransferState(PdfMushafTransferState(mushafId = entry.id))
          throw e
        } catch (e: Exception) {
          val msg = mapErrorToArabic(e, "فتح المصحف")
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
        throw IllegalStateException("تعذر تنزيل المصحف")
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
          runCatching { output.fd.sync() }
        }
      }

      if (headerTotal > 0L && downloaded != headerTotal) {
        partFile.delete()
        throw IllegalStateException("اكتمل تنزيل الملف بشكل غير مكتمل")
      }
      if (!PdfManifestParser.hasPdfMagicHeader(partFile)) {
        partFile.delete()
        throw IllegalStateException("تعذر التحقق من سلامة الملف المُنزّل")
      }
      if (!partFile.renameTo(targetFile)) {
        partFile.copyTo(targetFile, overwrite = true)
        partFile.delete()
      }
      if (isLocalFileInvalid(targetFile) || (headerTotal > 0L && targetFile.length() != headerTotal)) {
        targetFile.delete()
        throw IllegalStateException("تعذر التحقق من سلامة الملف المُنزّل")
      }
      remoteContentLengthCache[entry.id] = targetFile.length()
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
    refreshFileStateFlows()
  }

  private fun updateTransferState(state: PdfMushafTransferState) {
    _transferStates.update { it + (state.mushafId to state) }
  }
}
