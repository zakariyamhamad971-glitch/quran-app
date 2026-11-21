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
)

internal data class DebugReciterEntry(
  val uniqueKey: String,
  val reciterId: String,
  val reciterName: String,
  val riwayaId: String,
  val riwayaName: String,
  val moshafList: List<DebugMoshaf>,
)

internal data class DebugFetchSnapshot(
  val riwayat: List<DebugRiwaya>,
  val reciters: List<DebugReciterEntry>,
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
      reader.beginObject()
      while (reader.hasNext()) {
        when (reader.nextName()) {
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
      )
    }
    reader.endArray()
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

  internal fun buildMoshafAudioUrl(server: String, surahNumber: Int): String {
    if (server.isBlank() || server == RIWAYAT_MISSING_VALUE) return ""
    val padded = surahNumber.toString().padStart(3, '0')
    return "$server$padded.mp3"
  }

  internal fun formatFilteredResultsForClipboard(
    filtered: List<DebugReciterEntry>,
    selectedRiwayaLabel: String,
  ): String {
    return buildString {
      appendLine("نتائج API الروايات - mp3quran.net ($selectedRiwayaLabel)")
      appendLine("عدد القرّاء: ${filtered.size}")
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
            appendLine("  - المصحف: ${m.name} | server: ${m.server} | surah_list[0..9]: $surahStart")
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

  suspend fun fetchAllRiwayatAndReciters(
    client: OkHttpClient = QuranApiClient.okHttpClient,
    onProgress: suspend (String) -> Unit = {},
  ): Result<DebugFetchSnapshot> = withContext(Dispatchers.IO) {
    runCatching {
      onProgress("جارٍ جلب قائمة الروايات...")
      val riwayatJson = executeGetString(client, RIWAYAT_API_URL, "جلب الروايات")
      val riwayat = parseRiwayatJson(riwayatJson)
      if (riwayat.isEmpty()) {
        throw IllegalStateException("لم يُرجع الخادم أي رواية")
      }

      val semaphore = Semaphore(4)
      var completedCount = 0
      val progressLock = Any()

      val perRiwayaResults: List<Pair<List<DebugReciterEntry>, String?>> = coroutineScope {
        riwayat.mapIndexed { index, riwaya ->
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
      }

      val allReciters = perRiwayaResults.flatMap { it.first }
      val partialErrors = perRiwayaResults.mapNotNull { it.second }

      DebugFetchSnapshot(
        riwayat = riwayat,
        reciters = allReciters,
        partialErrors = partialErrors,
      )
    }
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
