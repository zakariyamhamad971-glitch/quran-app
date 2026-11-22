package com.example.data.audio

import android.content.Context
import com.example.data.quran.QuranApiClient
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class Mp3QuranRiwayaInfo(
  val id: Int,
  val name: String,
)

data class Mp3QuranMoshafInfo(
  val id: Int,
  val name: String,
  val server: String,
  val availableSurahs: Set<Int>,
)

data class Mp3QuranRiwayaReciterEntry(
  val reciterId: Int,
  val reciterName: String,
  val riwayaId: Int,
  val riwayaName: String,
  val moshafList: List<Mp3QuranMoshafInfo>,
)

data class Mp3QuranTimingReadEntry(
  val id: Int,
  val name: String,
  val rewaya: String,
  val folderUrl: String,
)

object Mp3QuranRiwayatParser {
  private val ARABIC_DIACRITICS_REGEX = Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
  private val WHITESPACE_REGEX = Regex("\\s+")

  fun normalizeArabicNoDiacritics(input: String): String {
    return input
      .replace(ARABIC_DIACRITICS_REGEX, "")
      .replace("أ", "ا")
      .replace("إ", "ا")
      .replace("آ", "ا")
      .replace("ى", "ي")
      .replace("ة", "ه")
      .replace(WHITESPACE_REGEX, " ")
      .trim()
  }

  fun parseSurahList(raw: String): Set<Int> {
    if (raw.isBlank()) return emptySet()
    return raw.split(",")
      .mapNotNull { it.trim().toIntOrNull() }
      .filter { it in 1..114 }
      .toSet()
  }

  fun normalizeHttpsServer(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    val secure = if (trimmed.startsWith("http://", ignoreCase = true)) {
      "https://" + trimmed.substring(7)
    } else {
      trimmed
    }
    return if (secure.endsWith("/")) secure else "$secure/"
  }

  fun buildSurahAudioUrl(server: String, surahId: Int): String? {
    if (surahId !in 1..114) return null
    val normalized = normalizeHttpsServer(server)
    if (normalized.isBlank()) return null
    val padded = surahId.toString().padStart(3, '0')
    return "$normalized$padded.mp3"
  }

  private fun JsonReader.nextScalarStringOrNull(): String? {
    return when (peek()) {
      JsonReader.Token.NULL -> {
        nextNull<Unit>()
        null
      }
      JsonReader.Token.STRING, JsonReader.Token.NUMBER -> {
        val v = nextString().trim()
        if (v.isEmpty() || v.equals("null", ignoreCase = true)) null else v
      }
      JsonReader.Token.BOOLEAN -> nextBoolean().toString()
      else -> {
        skipValue()
        null
      }
    }
  }

  private fun JsonReader.nextIntOrNull(): Int? {
    val str = nextScalarStringOrNull() ?: return null
    return str.toIntOrNull() ?: str.toDoubleOrNull()?.toInt()
  }

  private fun JsonReader.nextLongOrNull(): Long? {
    val str = nextScalarStringOrNull() ?: return null
    return str.toLongOrNull() ?: str.toDoubleOrNull()?.toLong()
  }

  fun parseRiwayatJson(rawJson: String): List<Mp3QuranRiwayaInfo> {
    val out = ArrayList<Mp3QuranRiwayaInfo>()
    JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
      reader.isLenient = true
      var foundArray = false
      when (reader.peek()) {
        JsonReader.Token.BEGIN_ARRAY -> {
          foundArray = true
          readRiwayatArray(reader, out)
        }
        JsonReader.Token.BEGIN_OBJECT -> {
          reader.beginObject()
          while (reader.hasNext()) {
            if (reader.nextName() == "riwayat" && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              foundArray = true
              readRiwayatArray(reader, out)
            } else {
              reader.skipValue()
            }
          }
          reader.endObject()
        }
        else -> Unit
      }
      if (!foundArray) {
        throw IllegalStateException("بيانات الروايات غير صالحة")
      }
    }
    return out
  }

  private fun readRiwayatArray(reader: JsonReader, out: MutableList<Mp3QuranRiwayaInfo>) {
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var id: Int? = null
      var name: String? = null
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "id" -> id = reader.nextIntOrNull()
          "name" -> name = reader.nextScalarStringOrNull()
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      if (id != null && !name.isNullOrBlank()) {
        out += Mp3QuranRiwayaInfo(id = id, name = name)
      }
    }
    reader.endArray()
  }

  fun parseRecitersForRiwayaJson(
    rawJson: String,
    riwayaId: Int,
    riwayaName: String,
  ): List<Mp3QuranRiwayaReciterEntry> {
    val out = ArrayList<Mp3QuranRiwayaReciterEntry>()
    JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
      reader.isLenient = true
      var foundArray = false
      when (reader.peek()) {
        JsonReader.Token.BEGIN_ARRAY -> {
          foundArray = true
          readRecitersArray(reader, riwayaId, riwayaName, out)
        }
        JsonReader.Token.BEGIN_OBJECT -> {
          reader.beginObject()
          while (reader.hasNext()) {
            if (reader.nextName() == "reciters" && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              foundArray = true
              readRecitersArray(reader, riwayaId, riwayaName, out)
            } else {
              reader.skipValue()
            }
          }
          reader.endObject()
        }
        else -> Unit
      }
      if (!foundArray) {
        throw IllegalStateException("تعذر قراءة قائمة القراء للرواية $riwayaId")
      }
    }
    return out
  }

  private fun readRecitersArray(
    reader: JsonReader,
    riwayaId: Int,
    riwayaName: String,
    out: MutableList<Mp3QuranRiwayaReciterEntry>,
  ) {
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var reciterId: Int? = null
      var reciterName: String? = null
      val moshafList = mutableListOf<Mp3QuranMoshafInfo>()
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "id" -> reciterId = reader.nextIntOrNull()
          "name" -> reciterName = reader.nextScalarStringOrNull()
          "moshaf" -> {
            if (reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              readMoshafArray(reader, moshafList)
            } else {
              reader.skipValue()
            }
          }
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      if (reciterId != null && !reciterName.isNullOrBlank() && moshafList.isNotEmpty()) {
        out += Mp3QuranRiwayaReciterEntry(
          reciterId = reciterId,
          reciterName = reciterName,
          riwayaId = riwayaId,
          riwayaName = riwayaName,
          moshafList = moshafList,
        )
      }
    }
    reader.endArray()
  }

  private fun readMoshafArray(reader: JsonReader, out: MutableList<Mp3QuranMoshafInfo>) {
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var mId: Int? = null
      var mName: String = ""
      var server: String = ""
      var rawSurahList = ""
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "id" -> mId = reader.nextIntOrNull()
          "name" -> mName = reader.nextScalarStringOrNull().orEmpty()
          "server" -> server = normalizeHttpsServer(reader.nextScalarStringOrNull().orEmpty())
          "surah_list" -> rawSurahList = reader.nextScalarStringOrNull().orEmpty()
          else -> reader.skipValue()
        }
      }
      reader.endObject()

      val surahs = parseSurahList(rawSurahList)
      if (mId != null && server.isNotBlank() && surahs.isNotEmpty()) {
        out += Mp3QuranMoshafInfo(
          id = mId,
          name = mName,
          server = server,
          availableSurahs = surahs,
        )
      }
    }
    reader.endArray()
  }

  fun parseTimingReadsJson(rawJson: String): List<Mp3QuranTimingReadEntry> {
    val out = ArrayList<Mp3QuranTimingReadEntry>()
    JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
      reader.isLenient = true
      var foundArray = false
      when (reader.peek()) {
        JsonReader.Token.BEGIN_ARRAY -> {
          foundArray = true
          readTimingReadsArray(reader, out)
        }
        JsonReader.Token.BEGIN_OBJECT -> {
          reader.beginObject()
          while (reader.hasNext()) {
            val name = reader.nextName()
            if (!foundArray && reader.peek() == JsonReader.Token.BEGIN_ARRAY && (name == "reads" || name.isNotEmpty())) {
              foundArray = true
              readTimingReadsArray(reader, out)
            } else {
              reader.skipValue()
            }
          }
          reader.endObject()
        }
        else -> Unit
      }
      if (!foundArray) {
        throw IllegalStateException("صيغة استجابة ayat_timing/reads غير متوقعة")
      }
    }
    return out
  }

  private fun readTimingReadsArray(reader: JsonReader, out: MutableList<Mp3QuranTimingReadEntry>) {
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var id: Int? = null
      var name = ""
      var rewaya = ""
      var folderUrl = ""
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "id" -> id = reader.nextIntOrNull()
          "name" -> name = reader.nextScalarStringOrNull().orEmpty()
          "rewaya" -> rewaya = reader.nextScalarStringOrNull().orEmpty()
          "folder_url" -> folderUrl = normalizeHttpsServer(reader.nextScalarStringOrNull().orEmpty())
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      if (id != null && id > 0) {
        out += Mp3QuranTimingReadEntry(
          id = id,
          name = name,
          rewaya = rewaya,
          folderUrl = folderUrl,
        )
      }
    }
    reader.endArray()
  }

  /**
   * Parses ayat_timing?surah=<n>&read=<id> response safely.
   * Ignores ayah == 0 (Basmalah/header marker) and any entry with null/missing ayah, start_time, or end_time.
   */
  fun parseAyahTimingsJson(rawJson: String): List<AyahTimingDto> {
    val out = ArrayList<AyahTimingDto>()
    JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
      reader.isLenient = true
      var foundArray = false
      when (reader.peek()) {
        JsonReader.Token.BEGIN_ARRAY -> {
          foundArray = true
          readAyahTimingsArray(reader, out)
        }
        JsonReader.Token.BEGIN_OBJECT -> {
          reader.beginObject()
          while (reader.hasNext()) {
            reader.nextName()
            if (!foundArray && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              foundArray = true
              readAyahTimingsArray(reader, out)
            } else {
              reader.skipValue()
            }
          }
          reader.endObject()
        }
        else -> Unit
      }
      if (!foundArray) {
        return emptyList()
      }
    }
    return out.sortedWith(compareBy<AyahTimingDto> { it.ayah }.thenBy { it.start_time })
  }

  private fun readAyahTimingsArray(reader: JsonReader, out: MutableList<AyahTimingDto>) {
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var ayah: Int? = null
      var startTime: Long? = null
      var endTime: Long? = null
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "ayah" -> ayah = reader.nextIntOrNull()
          "start_time" -> startTime = reader.nextLongOrNull()
          "end_time" -> endTime = reader.nextLongOrNull()
          else -> reader.skipValue()
        }
      }
      reader.endObject()

      if (ayah != null && ayah > 0 && startTime != null && endTime != null) {
        out += AyahTimingDto(
          ayah = ayah,
          start_time = startTime.coerceAtLeast(0L),
          end_time = endTime.coerceAtLeast(0L),
        )
      }
    }
    reader.endArray()
  }

  /**
   * Builds VerifiedReciter list ordered strictly by [orderedRiwayaIds] and API order within each group.
   */
  fun buildGroupedVerifiedReciters(
    orderedRiwayaIds: List<Int>,
    entriesByRiwayaId: Map<Int, List<Mp3QuranRiwayaReciterEntry>>,
    timingReadIds: Set<Int>,
  ): List<VerifiedReciter> {
    val result = ArrayList<VerifiedReciter>()
    var orderCursor = 0
    val seenMoshafIds = HashSet<Int>()

    for (riwayaId in orderedRiwayaIds) {
      val groupEntries = entriesByRiwayaId[riwayaId].orEmpty()
      for (entry in groupEntries) {
        for (moshaf in entry.moshafList) {
          if (!seenMoshafIds.add(moshaf.id)) continue
          val hasTiming = timingReadIds.contains(moshaf.id)
          val timingText = if (hasTiming) "له توقيت" else "تلاوة سورة كاملة (بدون تحديد آيات)"
          val statusParts = buildList {
            if (entry.riwayaName.isNotBlank()) add(entry.riwayaName)
            add("${moshaf.availableSurahs.size} سورة")
            add(timingText)
          }
          result += VerifiedReciter(
            orderIndex = orderCursor++,
            requestedName = entry.reciterName,
            reciterId = entry.reciterId,
            displayName = entry.reciterName,
            moshafId = moshaf.id,
            moshafName = moshaf.name,
            serverUrl = moshaf.server,
            availableSurahs = moshaf.availableSurahs,
            status = ReciterMatchStatus.VERIFIED,
            statusNote = statusParts.joinToString(" • "),
            isPrimaryOrdered = true,
            riwayaId = entry.riwayaId,
            riwayaName = entry.riwayaName,
            hasAyahTiming = hasTiming,
            isPdfModeReciter = true,
          )
        }
      }
    }
    return result
  }
}

class Mp3QuranRiwayatClient(
  private val context: Context,
  private val httpClient: OkHttpClient = QuranApiClient.okHttpClient,
) {
  companion object {
    private const val RIWAYAT_URL = "https://mp3quran.net/api/v3/riwayat?language=ar"
    private const val RECITERS_URL_PREFIX = "https://mp3quran.net/api/v3/reciters?language=ar&rewaya="
    private const val TIMING_READS_URL = "https://mp3quran.net/api/v3/ayat_timing/reads"
    private const val TIMING_AYAT_URL_FORMAT = "https://mp3quran.net/api/v3/ayat_timing?surah=%d&read=%d"
    private const val RECITERS_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    private const val TIMING_READS_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
  }

  private val cacheRoot = File(context.cacheDir, "mp3quran_riwayat").apply { mkdirs() }

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

  private fun executeGetString(url: String, contextLabel: String): String {
    val req = Request.Builder()
      .url(url)
      .header("Accept", "application/json")
      .build()
    try {
      httpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) {
          throw IllegalStateException("خطأ من الخادم أثناء $contextLabel (HTTP ${resp.code})")
        }
        return resp.body?.string()
          ?: throw IllegalStateException("استجابة فارغة أثناء $contextLabel")
      }
    } catch (e: IllegalStateException) {
      throw e
    } catch (e: Exception) {
      throw IllegalStateException(mapErrorToArabic(e, contextLabel), e)
    }
  }

  private fun isCacheFresh(file: File, ttlMs: Long): Boolean {
    if (!file.exists() || file.length() == 0L) return false
    val age = System.currentTimeMillis() - file.lastModified()
    return age in 0L until ttlMs
  }

  private fun readFileOrNull(file: File): String? {
    if (!file.exists() || file.length() == 0L) return null
    return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
  }

  private fun writeAtomic(file: File, content: String) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, "${file.name}.tmp")
    tmp.writeText(content)
    if (!tmp.renameTo(file)) {
      file.writeText(content)
      tmp.delete()
    }
  }

  suspend fun getRiwayatMap(forceRefresh: Boolean = false): Map<Int, String> = withContext(Dispatchers.IO) {
    val file = File(cacheRoot, "riwayat_ar.json")
    val cachedJson = if (!forceRefresh && isCacheFresh(file, RECITERS_CACHE_TTL_MS)) {
      readFileOrNull(file)
    } else {
      null
    }
    val raw = cachedJson ?: runCatching {
      val fetched = executeGetString(RIWAYAT_URL, "جلب قائمة الروايات")
      writeAtomic(file, fetched)
      fetched
    }.getOrElse {
      readFileOrNull(file) ?: return@withContext emptyMap()
    }
    runCatching {
      Mp3QuranRiwayatParser.parseRiwayatJson(raw).associate { it.id to it.name }
    }.getOrDefault(emptyMap())
  }

  suspend fun getTimingReadIds(forceRefresh: Boolean = false): Set<Int> = withContext(Dispatchers.IO) {
    val file = File(cacheRoot, "timing_reads.json")
    val cachedJson = if (!forceRefresh && isCacheFresh(file, TIMING_READS_CACHE_TTL_MS)) {
      readFileOrNull(file)
    } else {
      null
    }
    val raw = cachedJson ?: runCatching {
      val fetched = executeGetString(TIMING_READS_URL, "جلب قائمة القراء أصحاب التوقيت")
      writeAtomic(file, fetched)
      fetched
    }.getOrElse {
      readFileOrNull(file) ?: return@withContext emptySet()
    }
    runCatching {
      Mp3QuranRiwayatParser.parseTimingReadsJson(raw).mapTo(HashSet()) { it.id }
    }.getOrDefault(emptySet())
  }

  suspend fun fetchRecitersForRiwayas(
    apiRiwayaIds: List<Int>,
    forceRefresh: Boolean = false,
  ): Result<List<VerifiedReciter>> = withContext(Dispatchers.IO) {
    runCatching {
      if (apiRiwayaIds.isEmpty()) return@runCatching emptyList()

      coroutineScope {
        val riwayatNamesDeferred = async { getRiwayatMap(forceRefresh) }
        val timingIdsDeferred = async { getTimingReadIds(forceRefresh) }

        val perRiwayaDeferred = apiRiwayaIds.distinct().map { rId ->
          async {
            val file = File(cacheRoot, "reciters_rewaya_$rId.json")
            val freshCached = if (!forceRefresh && isCacheFresh(file, RECITERS_CACHE_TTL_MS)) {
              readFileOrNull(file)
            } else {
              null
            }
            val raw = freshCached ?: try {
              val fetched = executeGetString("$RECITERS_URL_PREFIX$rId", "جلب قراء الرواية")
              writeAtomic(file, fetched)
              fetched
            } catch (e: Exception) {
              if (!forceRefresh) {
                readFileOrNull(file) ?: throw e
              } else {
                throw e
              }
            }
            rId to raw
          }
        }

        val riwayatNames = riwayatNamesDeferred.await()
        val timingReadIds = timingIdsDeferred.await()
        val rawByRiwaya = perRiwayaDeferred.awaitAll().toMap()

        val parsedByRiwaya = HashMap<Int, List<Mp3QuranRiwayaReciterEntry>>(rawByRiwaya.size)
        for ((rId, rawJson) in rawByRiwaya) {
          val rName = riwayatNames[rId].orEmpty()
          parsedByRiwaya[rId] = Mp3QuranRiwayatParser.parseRecitersForRiwayaJson(
            rawJson = rawJson,
            riwayaId = rId,
            riwayaName = rName,
          )
        }

        Mp3QuranRiwayatParser.buildGroupedVerifiedReciters(
          orderedRiwayaIds = apiRiwayaIds.distinct(),
          entriesByRiwayaId = parsedByRiwaya,
          timingReadIds = timingReadIds,
        )
      }
    }
  }

  suspend fun fetchRecitersForAllRiwayasResilient(
    apiRiwayaIds: List<Int>,
    forceRefresh: Boolean = false,
  ): List<VerifiedReciter> = withContext(Dispatchers.IO) {
    if (apiRiwayaIds.isEmpty()) return@withContext emptyList()
    coroutineScope {
      val riwayatNamesDeferred = async { runCatching { getRiwayatMap(forceRefresh) }.getOrDefault(emptyMap()) }
      val timingIdsDeferred = async { runCatching { getTimingReadIds(forceRefresh) }.getOrDefault(emptySet()) }

      val distinctIds = apiRiwayaIds.distinct()
      val perRiwayaDeferred = distinctIds.map { rId ->
        async {
          val file = File(cacheRoot, "reciters_rewaya_$rId.json")
          val freshCached = if (!forceRefresh && isCacheFresh(file, RECITERS_CACHE_TTL_MS)) {
            readFileOrNull(file)
          } else {
            null
          }
          val raw = freshCached ?: runCatching {
            val fetched = executeGetString("$RECITERS_URL_PREFIX$rId", "جلب قراء الرواية")
            writeAtomic(file, fetched)
            fetched
          }.getOrElse { readFileOrNull(file) }
          rId to raw
        }
      }

      val riwayatNames = riwayatNamesDeferred.await()
      val timingReadIds = timingIdsDeferred.await()
      val rawByRiwaya = perRiwayaDeferred.awaitAll().toMap()

      val parsedByRiwaya = HashMap<Int, List<Mp3QuranRiwayaReciterEntry>>(rawByRiwaya.size)
      for ((rId, rawJson) in rawByRiwaya) {
        if (rawJson.isNullOrBlank()) continue
        val rName = riwayatNames[rId].orEmpty()
        runCatching {
          Mp3QuranRiwayatParser.parseRecitersForRiwayaJson(
            rawJson = rawJson,
            riwayaId = rId,
            riwayaName = rName,
          )
        }.onSuccess { parsed ->
          if (parsed.isNotEmpty()) {
            parsedByRiwaya[rId] = parsed
          }
        }
      }

      Mp3QuranRiwayatParser.buildGroupedVerifiedReciters(
        orderedRiwayaIds = distinctIds,
        entriesByRiwayaId = parsedByRiwaya,
        timingReadIds = timingReadIds,
      )
    }
  }

  suspend fun fetchAyahTimings(
    readId: Int,
    surahId: Int,
  ): List<AyahTimingDto> = withContext(Dispatchers.IO) {
    if (readId <= 0 || surahId !in 1..114) return@withContext emptyList()
    val file = File(cacheRoot, "timing_${readId}_$surahId.json")
    readFileOrNull(file)?.let { cached ->
      val parsed = runCatching { Mp3QuranRiwayatParser.parseAyahTimingsJson(cached) }.getOrNull()
      if (!parsed.isNullOrEmpty()) return@withContext parsed
    }

    val url = TIMING_AYAT_URL_FORMAT.format(surahId, readId)
    val fetched = runCatching {
      executeGetString(url, "جلب توقيت الآيات للسورة $surahId")
    }.getOrNull() ?: return@withContext emptyList()

    val parsed = runCatching { Mp3QuranRiwayatParser.parseAyahTimingsJson(fetched) }.getOrDefault(emptyList())
    if (parsed.isNotEmpty()) {
      writeAtomic(file, fetched)
    }
    parsed
  }
}
