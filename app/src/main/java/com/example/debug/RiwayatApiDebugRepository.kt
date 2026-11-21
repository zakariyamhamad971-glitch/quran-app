package com.example.debug

import com.example.data.quran.QuranApiClient
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal const val RIWAYAT_MISSING_VALUE = "غير موجود"
private const val RIWAYAT_API_URL = "https://mp3quran.net/api/v3/riwayat?language=ar"
private const val RECITERS_API_BASE_URL = "https://mp3quran.net/api/v3/reciters?language=ar&rewaya="
private const val TIMING_READS_API_URL = "https://mp3quran.net/api/v3/ayat_timing/reads"
private const val TIMING_SOAR_BASE_URL = "https://mp3quran.net/api/v3/ayat_timing/soar?read="
private const val TIMING_SURAH1_BASE_URL = "https://mp3quran.net/api/v3/ayat_timing?surah=1&read="

internal data class DebugRiwaya(
  val id: String,
  val name: String,
) {
  val displayLabel: String get() = "$id - $name"
}

internal data class DebugMoshaf(
  val id: String,
  val name: String,
  val server: String,
  val rawSurahList: String,
  val first10Surahs: List<String>,
  val firstSurahNumber: Int?,
  val rawFieldNames: List<String> = emptyList(),
)

internal data class DebugReciterEntry(
  val uniqueKey: String,
  val reciterId: String,
  val reciterName: String,
  val riwayaId: String,
  val riwayaName: String,
  val moshafList: List<DebugMoshaf>,
)

internal data class DebugTimingReadEntry(
  val id: String,
  val rawFields: Map<String, String>,
) {
  fun formatRawLine(): String {
    if (rawFields.isEmpty()) return "id: $id"
    return rawFields.entries.joinToString(" | ") { (k, v) -> "$k: $v" }
  }
}

internal data class DebugTimingSoarResult(
  val url: String,
  val httpCode: Int?,
  val surahCount: Int?,
  val errorMessage: String?,
)

internal data class DebugAyahTimingSample(
  val ayah: String,
  val startTime: String,
  val endTime: String,
) {
  fun formatLine(): String = "ayah=$ayah, start_time=$startTime, end_time=$endTime"
}

internal data class DebugTimingSurah1Result(
  val url: String,
  val httpCode: Int?,
  val objectCount: Int?,
  val first3Objects: List<DebugAyahTimingSample>,
  val errorMessage: String?,
)

internal data class DebugMoshafTimingProbeResult(
  val soarResult: DebugTimingSoarResult,
  val surah1Result: DebugTimingSurah1Result,
)

internal data class DebugFetchSnapshot(
  val riwayat: List<DebugRiwaya>,
  val reciters: List<DebugReciterEntry>,
  val timingReads: List<DebugTimingReadEntry>,
  val timingReadIds: Set<String>,
  val timingReadsLoaded: Boolean,
  val timingReadsError: String?,
  val partialErrors: List<String>,
)

internal data class AudioHttpProbeResult(
  val httpCode: Int?,
  val isSuccess: Boolean,
  val errorMessage: String?,
)

internal object RiwayatApiDebugRepository {
  private val ARABIC_DIACRITICS_REGEX = Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
  private val WHITESPACE_REGEX = Regex("\\s+")

  internal fun normalizeArabicNoDiacritics(input: String): String {
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

  private fun JsonReader.nextPresentScalarOrNull(): String? {
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

  internal fun parseRiwayatJson(rawJson: String): List<DebugRiwaya> {
    val out = ArrayList<DebugRiwaya>()
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
        throw IllegalStateException("حقل riwayat غير موجود في استجابة الخادم")
      }
    }
    return out
  }

  private fun readRiwayatArray(reader: JsonReader, out: MutableList<DebugRiwaya>) {
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var id = RIWAYAT_MISSING_VALUE
      var name = RIWAYAT_MISSING_VALUE
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "id" -> id = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
          "name" -> name = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
          else -> reader.skipValue()
        }
      }
      reader.endObject()
      out += DebugRiwaya(id = id, name = name)
    }
    reader.endArray()
  }

  internal fun parseRecitersJson(
    rawJson: String,
    riwayaId: String,
    riwayaName: String,
  ): List<DebugReciterEntry> {
    val out = ArrayList<DebugReciterEntry>()
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
        throw IllegalStateException("حقل reciters غير موجود لرواية $riwayaId")
      }
    }
    return out
  }

  private fun readRecitersArray(
    reader: JsonReader,
    riwayaId: String,
    riwayaName: String,
    out: MutableList<DebugReciterEntry>,
  ) {
    reader.beginArray()
    var idx = 0
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var reciterId = RIWAYAT_MISSING_VALUE
      var reciterName = RIWAYAT_MISSING_VALUE
      val moshafList = mutableListOf<DebugMoshaf>()
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
          "id" -> reciterId = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
          "name" -> reciterName = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
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
      out += DebugReciterEntry(
        uniqueKey = "${riwayaId}_${reciterId}_$idx",
        reciterId = reciterId,
        reciterName = reciterName,
        riwayaId = riwayaId,
        riwayaName = riwayaName,
        moshafList = moshafList,
      )
      idx++
    }
    reader.endArray()
  }

  private fun readMoshafArray(reader: JsonReader, out: MutableList<DebugMoshaf>) {
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var mId = RIWAYAT_MISSING_VALUE
      var mName = RIWAYAT_MISSING_VALUE
      var server = RIWAYAT_MISSING_VALUE
      var rawSurahList = RIWAYAT_MISSING_VALUE
      val rawFieldNames = mutableListOf<String>()
      reader.beginObject()
      while (reader.hasNext()) {
        val fieldName = reader.nextName()
        rawFieldNames += fieldName
        when (fieldName) {
          "id" -> mId = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
          "name" -> mName = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
          "server" -> server = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
          "surah_list" -> rawSurahList = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
          else -> reader.skipValue()
        }
      }
      reader.endObject()

      val parsedTokens = if (rawSurahList == RIWAYAT_MISSING_VALUE) {
        emptyList()
      } else {
        rawSurahList.split(",")
          .map { it.trim() }
          .filter { it.isNotEmpty() }
      }
      out += DebugMoshaf(
        id = mId,
        name = mName,
        server = server,
        rawSurahList = rawSurahList,
        first10Surahs = parsedTokens.take(10),
        firstSurahNumber = parsedTokens.firstOrNull()?.toIntOrNull(),
        rawFieldNames = rawFieldNames,
      )
    }
    reader.endArray()
  }

  internal fun parseTimingReadsJson(rawJson: String): List<DebugTimingReadEntry> {
    val out = ArrayList<DebugTimingReadEntry>()
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
            val key = reader.nextName()
            if (!foundArray && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              foundArray = true
              readTimingReadsArray(reader, out)
            } else if (key == "reads" && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
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

  private fun readTimingReadsArray(reader: JsonReader, out: MutableList<DebugTimingReadEntry>) {
    reader.beginArray()
    while (reader.hasNext()) {
      if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
        reader.skipValue()
        continue
      }
      var id = RIWAYAT_MISSING_VALUE
      val rawFields = LinkedHashMap<String, String>()
      reader.beginObject()
      while (reader.hasNext()) {
        val fieldName = reader.nextName()
        val value = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
        rawFields[fieldName] = value
        if (fieldName == "id") {
          id = value
        }
      }
      reader.endObject()
      out += DebugTimingReadEntry(id = id, rawFields = rawFields)
    }
    reader.endArray()
  }

  internal fun parseTimingSoarCount(rawJson: String): Int {
    JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
      reader.isLenient = true
      return when (reader.peek()) {
        JsonReader.Token.BEGIN_ARRAY -> {
          var count = 0
          reader.beginArray()
          while (reader.hasNext()) {
            reader.skipValue()
            count++
          }
          reader.endArray()
          count
        }
        JsonReader.Token.BEGIN_OBJECT -> {
          var foundCount: Int? = null
          reader.beginObject()
          while (reader.hasNext()) {
            reader.nextName()
            if (foundCount == null && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              var count = 0
              reader.beginArray()
              while (reader.hasNext()) {
                reader.skipValue()
                count++
              }
              reader.endArray()
              foundCount = count
            } else {
              reader.skipValue()
            }
          }
          reader.endObject()
          foundCount ?: throw IllegalStateException("لم يُعثر على مصفوفة السور في الاستجابة")
        }
        else -> throw IllegalStateException("صيغة استجابة ayat_timing/soar غير متوقعة")
      }
    }
  }

  internal fun parseAyahTimingsJson(rawJson: String): Pair<Int, List<DebugAyahTimingSample>> {
    val samples = mutableListOf<DebugAyahTimingSample>()
    var totalCount = 0
    JsonReader.of(Buffer().writeUtf8(rawJson)).use { reader ->
      reader.isLenient = true
      var foundArray = false
      when (reader.peek()) {
        JsonReader.Token.BEGIN_ARRAY -> {
          foundArray = true
          totalCount = readAyahTimingSamplesArray(reader, samples)
        }
        JsonReader.Token.BEGIN_OBJECT -> {
          reader.beginObject()
          while (reader.hasNext()) {
            reader.nextName()
            if (!foundArray && reader.peek() == JsonReader.Token.BEGIN_ARRAY) {
              foundArray = true
              totalCount = readAyahTimingSamplesArray(reader, samples)
            } else {
              reader.skipValue()
            }
          }
          reader.endObject()
        }
        else -> Unit
      }
      if (!foundArray) {
        throw IllegalStateException("لم يُعثر على مصفوفة التوقيت في الاستجابة")
      }
    }
    return totalCount to samples
  }

  private fun readAyahTimingSamplesArray(
    reader: JsonReader,
    first3Out: MutableList<DebugAyahTimingSample>,
  ): Int {
    var count = 0
    reader.beginArray()
    while (reader.hasNext()) {
      if (first3Out.size < 3 && reader.peek() == JsonReader.Token.BEGIN_OBJECT) {
        var ayah = RIWAYAT_MISSING_VALUE
        var startTime = RIWAYAT_MISSING_VALUE
        var endTime = RIWAYAT_MISSING_VALUE
        reader.beginObject()
        while (reader.hasNext()) {
          when (reader.nextName()) {
            "ayah" -> ayah = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
            "start_time" -> startTime = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
            "end_time" -> endTime = reader.nextPresentScalarOrNull() ?: RIWAYAT_MISSING_VALUE
            else -> reader.skipValue()
          }
        }
        reader.endObject()
        first3Out += DebugAyahTimingSample(
          ayah = ayah,
          startTime = startTime,
          endTime = endTime,
        )
      } else {
        reader.skipValue()
      }
      count++
    }
    reader.endArray()
    return count
  }

  internal fun filterReciters(
    all: List<DebugReciterEntry>,
    selectedRiwayaId: String?,
    searchQuery: String,
  ): List<DebugReciterEntry> {
    val normQuery = normalizeArabicNoDiacritics(searchQuery)
    return all.filter { entry ->
      val riwayaOk = selectedRiwayaId == null || entry.riwayaId == selectedRiwayaId
      if (!riwayaOk) return@filter false
      if (normQuery.isEmpty()) return@filter true
      normalizeArabicNoDiacritics(entry.reciterName).contains(normQuery, ignoreCase = true)
    }
  }

  internal fun hasTimingForMoshaf(
    moshaf: DebugMoshaf,
    timingReadIds: Set<String>,
  ): Boolean {
    return moshaf.id != RIWAYAT_MISSING_VALUE &&
      moshaf.id.isNotBlank() &&
      timingReadIds.contains(moshaf.id)
  }

  internal fun timingLabelForMoshaf(
    moshaf: DebugMoshaf,
    timingReadIds: Set<String>,
    timingReadsLoaded: Boolean,
  ): String {
    if (!timingReadsLoaded) return "غير معروف (فشل جلب reads)"
    return if (hasTimingForMoshaf(moshaf, timingReadIds)) "له توقيت" else "ليس له توقيت"
  }

  internal fun countRecitersWithTimingForRiwaya(
    all: List<DebugReciterEntry>,
    selectedRiwayaId: String?,
    timingReadIds: Set<String>,
  ): Pair<Int, Int> {
    val inRiwaya = if (selectedRiwayaId == null) {
      all
    } else {
      all.filter { it.riwayaId == selectedRiwayaId }
    }
    val withTiming = inRiwaya.count { entry ->
      entry.moshafList.any { m -> hasTimingForMoshaf(m, timingReadIds) }
    }
    return withTiming to inRiwaya.size
  }

  internal fun buildMoshafAudioUrl(server: String, surahNumber: Int): String {
    if (server.isBlank() || server == RIWAYAT_MISSING_VALUE) return ""
    val padded = surahNumber.toString().padStart(3, '0')
    return "$server$padded.mp3"
  }

  internal fun formatFilteredResultsForClipboard(
    filtered: List<DebugReciterEntry>,
    selectedRiwayaLabel: String,
    timingReadIds: Set<String> = emptySet(),
    timingReadsLoaded: Boolean = true,
  ): String {
    val withTimingFiltered = filtered.count { reciter ->
      reciter.moshafList.any { hasTimingForMoshaf(it, timingReadIds) }
    }
    return buildString {
      appendLine("نتائج API الروايات - mp3quran.net ($selectedRiwayaLabel)")
      appendLine("عدد القرّاء: ${filtered.size} | قرّاء لهم توقيت: $withTimingFiltered من ${filtered.size}")
      appendLine()
      for (reciter in filtered) {
        appendLine("القارئ: ${reciter.reciterName} | الرواية: ${reciter.riwayaId} - ${reciter.riwayaName} | المصاحف: ${reciter.moshafList.size}")
        if (reciter.moshafList.isEmpty()) {
          appendLine("  - المصاحف: $RIWAYAT_MISSING_VALUE")
        } else {
          for (m in reciter.moshafList) {
            val surahStart = if (m.first10Surahs.isEmpty()) {
              RIWAYAT_MISSING_VALUE
            } else {
              m.first10Surahs.joinToString(",")
            }
            val timingLabel = timingLabelForMoshaf(m, timingReadIds, timingReadsLoaded)
            appendLine("  - المصحف: ${m.name} | id: ${m.id} | $timingLabel | server: ${m.server} | surah_list[0..9]: $surahStart")
          }
        }
      }
    }.trimEnd()
  }

  private fun mapNetworkErrorToArabic(error: Throwable, contextLabel: String): String {
    return when (error) {
      is UnknownHostException, is ConnectException ->
        "تعذر الاتصال بالخادم ($contextLabel): لا يوجد اتصال بالإنترنت أو تعذر الوصول للمضيف (${error.message ?: error.javaClass.simpleName})"
      is SocketTimeoutException ->
        "انتهت مهلة الاتصال بالخادم ($contextLabel)"
      else ->
        "خطأ أثناء $contextLabel: ${error.localizedMessage ?: error.javaClass.simpleName}"
    }
  }

  private fun executeGetString(
    client: OkHttpClient,
    url: String,
    contextLabel: String,
  ): String {
    val request = Request.Builder()
      .url(url)
      .header("Accept", "application/json")
      .header("User-Agent", "Qabasat-Android-Debug")
      .build()
    try {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          throw IllegalStateException("فشل الطلب ($contextLabel): HTTP ${response.code} ${response.message}")
        }
        return response.body?.string()
          ?: throw IllegalStateException("استجابة فارغة من الخادم ($contextLabel)")
      }
    } catch (e: IllegalStateException) {
      throw e
    } catch (e: Exception) {
      throw IllegalStateException(mapNetworkErrorToArabic(e, contextLabel), e)
    }
  }

  private fun executeRawGetWithStatus(
    client: OkHttpClient,
    url: String,
    contextLabel: String,
  ): Triple<Int?, String?, String?> {
    val request = Request.Builder()
      .url(url)
      .header("Accept", "application/json")
      .header("User-Agent", "Qabasat-Android-Debug")
      .build()
    return try {
      client.newCall(request).execute().use { response ->
        val bodyStr = response.body?.string()
        if (!response.isSuccessful) {
          Triple(response.code, bodyStr, "HTTP ${response.code} ${response.message}")
        } else {
          Triple(response.code, bodyStr, null)
        }
      }
    } catch (e: Exception) {
      Triple(null, null, mapNetworkErrorToArabic(e, contextLabel))
    }
  }

  suspend fun fetchAllRiwayatAndReciters(
    client: OkHttpClient = QuranApiClient.okHttpClient,
    onProgress: suspend (String) -> Unit = {},
  ): Result<DebugFetchSnapshot> = withContext(Dispatchers.IO) {
    runCatching {
      onProgress("جارٍ جلب قائمة الروايات وقائمة التوقيت...")
      coroutineScope {
        val timingReadsDeferred = async {
          try {
            val raw = executeGetString(client, TIMING_READS_API_URL, "جلب التوقيت (reads)")
            val parsed = parseTimingReadsJson(raw)
            Triple(parsed, true, null as String?)
          } catch (e: Exception) {
            Triple(emptyList<DebugTimingReadEntry>(), false, e.localizedMessage ?: "تعذر جلب ayat_timing/reads")
          }
        }

        val riwayatJson = executeGetString(client, RIWAYAT_API_URL, "جلب الروايات")
        val riwayat = parseRiwayatJson(riwayatJson)
        if (riwayat.isEmpty()) {
          throw IllegalStateException("لم يُرجع الخادم أي رواية")
        }

        val semaphore = Semaphore(4)
        var completedCount = 0
        val progressLock = Any()

        val perRiwayaResults: List<Pair<List<DebugReciterEntry>, String?>> = riwayat.mapIndexed { index, riwaya ->
          async {
            semaphore.withPermit {
              if (riwaya.id == RIWAYAT_MISSING_VALUE || riwaya.id.isBlank()) {
                synchronized(progressLock) { completedCount++ }
                onProgress("جارٍ جلب القرّاء ($completedCount / ${riwayat.size})...")
                return@withPermit emptyList<DebugReciterEntry>() to "الرواية #${index + 1} (${riwaya.name}): معرف الرواية $RIWAYAT_MISSING_VALUE"
              }
              val url = "$RECITERS_API_BASE_URL${riwaya.id}"
              val pair = try {
                val json = executeGetString(client, url, "رواية ${riwaya.id}")
                val list = parseRecitersJson(json, riwayaId = riwaya.id, riwayaName = riwaya.name)
                list to null
              } catch (e: Exception) {
                emptyList<DebugReciterEntry>() to "${riwaya.displayLabel}: ${e.localizedMessage ?: "خطأ غير معروف"}"
              }
              val done = synchronized(progressLock) {
                completedCount++
                completedCount
              }
              onProgress("جارٍ جلب القرّاء ($done / ${riwayat.size}): ${riwaya.name}")
              pair
            }
          }
        }.awaitAll()

        val (timingReads, timingReadsLoaded, timingReadsError) = timingReadsDeferred.await()
        val timingReadIds = timingReads
          .mapNotNull { it.id.takeIf { id -> id != RIWAYAT_MISSING_VALUE && id.isNotBlank() } }
          .toHashSet()

        val allReciters = perRiwayaResults.flatMap { it.first }
        val partialErrors = perRiwayaResults.mapNotNull { it.second }

        DebugFetchSnapshot(
          riwayat = riwayat,
          reciters = allReciters,
          timingReads = timingReads,
          timingReadIds = timingReadIds,
          timingReadsLoaded = timingReadsLoaded,
          timingReadsError = timingReadsError,
          partialErrors = partialErrors,
        )
      }
    }
  }

  suspend fun testMoshafTimingEndpoints(
    moshafId: String,
    client: OkHttpClient = QuranApiClient.okHttpClient,
  ): DebugMoshafTimingProbeResult = withContext(Dispatchers.IO) {
    val soarUrl = "$TIMING_SOAR_BASE_URL$moshafId"
    val (soarCode, soarBody, soarHttpErr) = executeRawGetWithStatus(client, soarUrl, "اختبار التوقيت soar")
    val soarResult = if (soarHttpErr != null) {
      DebugTimingSoarResult(
        url = soarUrl,
        httpCode = soarCode,
        surahCount = null,
        errorMessage = soarHttpErr,
      )
    } else if (soarBody.isNullOrBlank()) {
      DebugTimingSoarResult(
        url = soarUrl,
        httpCode = soarCode,
        surahCount = null,
        errorMessage = "استجابة فارغة من الخادم",
      )
    } else {
      try {
        val count = parseTimingSoarCount(soarBody)
        DebugTimingSoarResult(
          url = soarUrl,
          httpCode = soarCode,
          surahCount = count,
          errorMessage = null,
        )
      } catch (e: Exception) {
        DebugTimingSoarResult(
          url = soarUrl,
          httpCode = soarCode,
          surahCount = null,
          errorMessage = e.localizedMessage ?: "تعذر قراءة JSON السور",
        )
      }
    }

    val surah1Url = "$TIMING_SURAH1_BASE_URL$moshafId"
    val (s1Code, s1Body, s1HttpErr) = executeRawGetWithStatus(client, surah1Url, "اختبار التوقيت surah=1")
    val surah1Result = if (s1HttpErr != null) {
      DebugTimingSurah1Result(
        url = surah1Url,
        httpCode = s1Code,
        objectCount = null,
        first3Objects = emptyList(),
        errorMessage = s1HttpErr,
      )
    } else if (s1Body.isNullOrBlank()) {
      DebugTimingSurah1Result(
        url = surah1Url,
        httpCode = s1Code,
        objectCount = null,
        first3Objects = emptyList(),
        errorMessage = "استجابة فارغة من الخادم",
      )
    } else {
      try {
        val (objCount, first3) = parseAyahTimingsJson(s1Body)
        DebugTimingSurah1Result(
          url = surah1Url,
          httpCode = s1Code,
          objectCount = objCount,
          first3Objects = first3,
          errorMessage = null,
        )
      } catch (e: Exception) {
        DebugTimingSurah1Result(
          url = surah1Url,
          httpCode = s1Code,
          objectCount = null,
          first3Objects = emptyList(),
          errorMessage = e.localizedMessage ?: "تعذر قراءة JSON الآيات",
        )
      }
    }

    DebugMoshafTimingProbeResult(
      soarResult = soarResult,
      surah1Result = surah1Result,
    )
  }

  suspend fun probeAudioUrlHttpStatus(
    url: String,
    client: OkHttpClient = QuranApiClient.okHttpClient,
  ): AudioHttpProbeResult = withContext(Dispatchers.IO) {
    fun runProbe(method: String): AudioHttpProbeResult {
      val builder = Request.Builder()
        .url(url)
        .header("User-Agent", "Qabasat-Android-Debug")
      if (method == "HEAD") {
        builder.head()
      } else {
        builder.get().header("Range", "bytes=0-0")
      }
      client.newCall(builder.build()).execute().use { resp ->
        return AudioHttpProbeResult(
          httpCode = resp.code,
          isSuccess = resp.isSuccessful || resp.code == 206,
          errorMessage = if (resp.isSuccessful || resp.code == 206) null else "HTTP ${resp.code} ${resp.message}",
        )
      }
    }

    try {
      val headResult = runProbe("HEAD")
      if (headResult.httpCode == 405 || headResult.httpCode == 501) {
        runProbe("GET")
      } else {
        headResult
      }
    } catch (e: Exception) {
      AudioHttpProbeResult(
        httpCode = null,
        isSuccess = false,
        errorMessage = "${e.javaClass.simpleName}: ${e.localizedMessage ?: "خطأ في الاتصال"}",
      )
    }
  }
}
