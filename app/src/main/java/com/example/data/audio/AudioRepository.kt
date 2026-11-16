package com.example.data.audio

import com.example.data.integrity.VerifiedDiskCache
import com.example.data.quran.QuranApiClient
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap

interface Mp3QuranApi {
  @GET("reciters")
  suspend fun getRecitersRaw(
    @Query("language") language: String = "ar",
    @Query("rewaya") rewaya: Int = 1,
  ): Response<ResponseBody>

  @GET("suwar")
  suspend fun getSuwarRaw(
    @Query("language") language: String = "ar",
  ): Response<ResponseBody>

  @GET("ayat_timing/reads")
  suspend fun getTimingReadsRaw(): Response<ResponseBody>

  @GET("ayat_timing")
  suspend fun getAyahTimingRaw(
    @Query("surah") surah: Int,
    @Query("read") readId: Int,
  ): Response<ResponseBody>
}

class AudioRepository(
  private val diskCache: VerifiedDiskCache,
) {
  private val api: Mp3QuranApi = Retrofit.Builder()
    .baseUrl("https://www.mp3quran.net/api/v3/")
    .client(QuranApiClient.okHttpClient)
    .addConverterFactory(MoshiConverterFactory.create(QuranApiClient.moshi))
    .build()
    .create(Mp3QuranApi::class.java)

  private val recitersAdapter = QuranApiClient.moshi.adapter(Mp3QuranRecitersResponse::class.java)
  private val suwarAdapter = QuranApiClient.moshi.adapter(Mp3QuranSuwarResponse::class.java)
  private val timingReadsListAdapter = QuranApiClient.moshi.adapter<List<TimingReadDto>>(
    Types.newParameterizedType(List::class.java, TimingReadDto::class.java),
  )
  private val timingReadsWrapperAdapter = QuranApiClient.moshi.adapter(TimingReadsResponse::class.java)
  private val ayahTimingListAdapter = QuranApiClient.moshi.adapter<List<AyahTimingDto>>(
    Types.newParameterizedType(List::class.java, AyahTimingDto::class.java),
  )
  private val alQuranEditionsAdapter = QuranApiClient.moshi.adapter(AlQuranCloudEditionsResponse::class.java)
  private val alQuranSurahAudioAdapter = QuranApiClient.moshi.adapter(AlQuranCloudSurahAudioResponse::class.java)

  private val urlAuditCache = ConcurrentHashMap<String, SurahUrlAudit>()
  private var timingReadsCache: List<TimingReadDto>? = null
  private var alQuranAudioEditionsCache: List<AlQuranCloudAudioEditionDto>? = null

  companion object {
    const val TARGET_MOSHAF_NAME = "حفص عن عاصم - مرتل"

    val ORDERED_REQUESTED_RECITERS = listOf(
      "مصطفى إسماعيل",
      "محمود علي البنا",
      "محمد جبريل",
      "عبد الباسط عبد الصمد",
      "المنشاوي",
      "محمد أيوب",
      "مشاري العفاسي",
      "ياسر الدوسري",
      "ماهر المعيقلي",
      "حسن صالح",
      "أحمد نعينع",
      "مصطفى اللاهوني",
      "أحمد دايبان",
      "أحمد النفيس",
      "أبو بكر الشاطري",
    )

    private val ARABIC_TO_ENGLISH_RECITER_TOKENS = listOf(
      "العفاسي" to listOf("afasy"),
      "عبدالباسط" to listOf("abdul basit", "abdulbaset"),
      "المنشاوي" to listOf("minshawi"),
      "الحصري" to listOf("husary"),
      "الشاطري" to listOf("shatri", "shaatree"),
      "الدوسري" to listOf("dussary", "dosari"),
      "السديس" to listOf("sudais"),
      "الشريم" to listOf("shuraym", "shuraim"),
      "هاني الرفاعي" to listOf("hani ar-rifai", "rifai"),
      "الطبلاوي" to listOf("tablawi"),
      "الطنيجي" to listOf("tunaiji", "taniji"),
    )

    internal fun normalizeArabicForSearch(input: String): String {
      return input
        .replace("أ", "ا")
        .replace("إ", "ا")
        .replace("آ", "ا")
        .replace("ى", "ي")
        .replace("ة", "ه")
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace(Regex("\\s+"), " ")
        .replace("عبد ", "عبد")
        .replace("ابو ", "ابو")
        .trim()
    }

    internal fun compactArabic(input: String): String =
      normalizeArabicForSearch(input).replace(" ", "")

    internal fun parseTimingReadsJson(rawJson: String): List<TimingReadDto> {
      val trimmed = rawJson.trimStart()
      val moshi = QuranApiClient.moshi
      if (trimmed.startsWith("[")) {
        val listAdapter = moshi.adapter<List<TimingReadDto>>(
          Types.newParameterizedType(List::class.java, TimingReadDto::class.java),
        )
        return runCatching { listAdapter.fromJson(rawJson).orEmpty() }.getOrDefault(emptyList())
      }
      val wrapperAdapter = moshi.adapter(TimingReadsResponse::class.java)
      return runCatching { wrapperAdapter.fromJson(rawJson)?.reads.orEmpty() }.getOrDefault(emptyList())
    }

    internal fun findMatchingQuranComReciterIdFromList(
      reciterDisplayName: String,
      list: List<QuranComReciterDto>,
    ): Int? {
      val normTarget = normalizeArabicForSearch(reciterDisplayName)
      val compactTarget = compactArabic(reciterDisplayName)
      val tokens = normTarget.split(" ").filter { it.length >= 3 && it != "الشيخ" && it != "شيخ" }

      val englishHints = ARABIC_TO_ENGLISH_RECITER_TOKENS.firstOrNull { (arKey, _) ->
        val normKey = normalizeArabicForSearch(arKey)
        normTarget.contains(normKey) || compactTarget.contains(compactArabic(arKey))
      }?.second.orEmpty()

      val matching = list.filter { dto ->
        val candidatesAr = listOfNotNull(dto.arabic_name, dto.translated_name?.name, dto.name)
        val arMatched = candidatesAr.any { ar ->
          val normAr = normalizeArabicForSearch(ar)
          val compactAr = compactArabic(ar)
          normAr == normTarget ||
            compactAr == compactTarget ||
            (compactTarget.length >= 5 && compactAr.contains(compactTarget)) ||
            (tokens.isNotEmpty() && tokens.all { normAr.contains(it) })
        }
        val lowerEn = "${dto.name} ${dto.translated_name?.name.orEmpty()}".lowercase()
        val enMatched = englishHints.any { hint -> lowerEn.contains(hint) }
        val nonStreamingOk = !lowerEn.contains("streaming") && !lowerEn.contains("kids")
        (arMatched || enMatched) && nonStreamingOk
      }

      val hafsMurattal = matching.firstOrNull { dto ->
        val st = (dto.style?.name ?: "").lowercase()
        val qr = (dto.qirat?.name ?: "").lowercase()
        (st.isBlank() || st.contains("murattal") || st.contains("مرتل")) &&
          (qr.isBlank() || qr.contains("hafs"))
      }
      return (hafsMurattal ?: matching.firstOrNull())?.id
    }
  }

  private fun parseSurahList(raw: String): Set<Int> {
    if (raw.isBlank()) return emptySet()
    return raw.split(",")
      .mapNotNull { it.trim().toIntOrNull() }
      .filter { it in 1..114 }
      .toSet()
  }

  private fun normalizeHttpsServer(raw: String): String {
    val trimmed = raw.trim()
    val secure = if (trimmed.startsWith("http://")) {
      "https://" + trimmed.removePrefix("http://")
    } else {
      trimmed
    }
    return if (secure.endsWith("/")) secure else "$secure/"
  }

  suspend fun getVerifiedRecitersCatalog(): Result<Pair<List<VerifiedReciter>, List<VerifiedReciter>>> = runCatching {
    val bytes = diskCache.readVerifiedBytes("api/mp3quran_reciters_ar_r1.json") ?: run {
      val resp = api.getRecitersRaw("ar", 1)
      if (!resp.isSuccessful) error("فشل جلب قائمة القراء (${resp.code()})")
      val body = resp.body()?.bytes() ?: error("استجابة القراء فارغة")
      diskCache.writeVerifiedBytes("api/mp3quran_reciters_ar_r1.json", body)
      body
    }
    val parsed = recitersAdapter.fromJson(bytes.decodeToString())
      ?: error("تعذر تحليل بيانات القراء")

    // Only keep reciters that have a moshaf strictly matching "حفص عن عاصم - مرتل"
    val allHafsMurattal = parsed.reciters.mapNotNull { reciter ->
      val exactMoshafs = reciter.moshaf.filter { m ->
        normalizeArabicForSearch(m.name) == normalizeArabicForSearch(TARGET_MOSHAF_NAME)
      }
      if (exactMoshafs.isEmpty()) return@mapNotNull null
      val pickedMoshaf = exactMoshafs.maxByOrNull { parseSurahList(it.surah_list).size } ?: return@mapNotNull null
      Pair(reciter, pickedMoshaf)
    }

    val usedReciterIds = mutableSetOf<Int>()
    val orderedPrimary = ORDERED_REQUESTED_RECITERS.mapIndexed { idx, requested ->
      val normReq = normalizeArabicForSearch(requested)
      val aliases = when (requested) {
        "المنشاوي" -> listOf(normReq, normalizeArabicForSearch("محمد صديق المنشاوي"))
        "أبو بكر الشاطري" -> listOf(normReq, normalizeArabicForSearch("شيخ ابو بكر الشاطري"))
        "أحمد دايبان" -> listOf(normReq, normalizeArabicForSearch("احمد ديبان"), normalizeArabicForSearch("احمد بن علي دايبان"))
        "مشاري العفاسي" -> listOf(normReq, normalizeArabicForSearch("مشاري راشد العفاسي"))
        else -> listOf(normReq)
      }

      val matches = allHafsMurattal.filter { (rec, _) ->
        val normName = normalizeArabicForSearch(rec.name)
        aliases.any { alias ->
          normName == alias || normName.contains(alias)
        }
      }

      when {
        matches.isEmpty() -> VerifiedReciter(
          orderIndex = idx,
          requestedName = requested,
          reciterId = null,
          displayName = requested,
          moshafId = null,
          moshafName = null,
          serverUrl = null,
          availableSurahs = emptySet(),
          status = ReciterMatchStatus.MISSING,
          statusNote = "غير متوفر بمصحف \"$TARGET_MOSHAF_NAME\" في واجهة mp3quran",
          isPrimaryOrdered = true,
        )
        matches.size > 1 -> {
          val exact = matches.filter { (r, _) ->
            val n = normalizeArabicForSearch(r.name)
            aliases.any { it == n }
          }
          if (exact.size == 1) {
            val (rec, moshaf) = exact.first()
            usedReciterIds.add(rec.id)
            VerifiedReciter(
              orderIndex = idx,
              requestedName = requested,
              reciterId = rec.id,
              displayName = rec.name,
              moshafId = moshaf.id,
              moshafName = moshaf.name,
              serverUrl = normalizeHttpsServer(moshaf.server),
              availableSurahs = parseSurahList(moshaf.surah_list),
              status = ReciterMatchStatus.VERIFIED,
              statusNote = "متوفر (${parseSurahList(moshaf.surah_list).size} سورة)",
              isPrimaryOrdered = true,
            )
          } else {
            VerifiedReciter(
              orderIndex = idx,
              requestedName = requested,
              reciterId = null,
              displayName = requested,
              moshafId = null,
              moshafName = null,
              serverUrl = null,
              availableSurahs = emptySet(),
              status = ReciterMatchStatus.AMBIGUOUS,
              statusNote = "تطابق متعدد (${matches.joinToString(" / ") { it.first.name }})",
              isPrimaryOrdered = true,
            )
          }
        }
        else -> {
          val (rec, moshaf) = matches.first()
          usedReciterIds.add(rec.id)
          val surahs = parseSurahList(moshaf.surah_list)
          VerifiedReciter(
            orderIndex = idx,
            requestedName = requested,
            reciterId = rec.id,
            displayName = rec.name,
            moshafId = moshaf.id,
            moshafName = moshaf.name,
            serverUrl = normalizeHttpsServer(moshaf.server),
            availableSurahs = surahs,
            status = ReciterMatchStatus.VERIFIED,
            statusNote = "متوفر (${surahs.size} سورة)",
            isPrimaryOrdered = true,
          )
        }
      }
    }

    val additionalVerified = allHafsMurattal
      .filter { (rec, _) -> !usedReciterIds.contains(rec.id) }
      .mapIndexed { idx, (rec, moshaf) ->
        val surahs = parseSurahList(moshaf.surah_list)
        VerifiedReciter(
          orderIndex = orderedPrimary.size + idx,
          requestedName = rec.name,
          reciterId = rec.id,
          displayName = rec.name,
          moshafId = moshaf.id,
          moshafName = moshaf.name,
          serverUrl = normalizeHttpsServer(moshaf.server),
          availableSurahs = surahs,
          status = ReciterMatchStatus.VERIFIED,
          statusNote = "متوفر (${surahs.size} سورة)",
          isPrimaryOrdered = false,
        )
      }

    Pair(orderedPrimary, additionalVerified)
  }

  suspend fun getSuwarPageRanges(): Result<Map<Int, Mp3QuranSurahDto>> = runCatching {
    val bytes = diskCache.readVerifiedBytes("api/mp3quran_suwar_ar.json") ?: run {
      val resp = api.getSuwarRaw("ar")
      if (!resp.isSuccessful) error("فشل جلب نطاق صفحات السور")
      val body = resp.body()?.bytes() ?: error("بيانات السور فارغة")
      diskCache.writeVerifiedBytes("api/mp3quran_suwar_ar.json", body)
      body
    }
    val parsed = suwarAdapter.fromJson(bytes.decodeToString()) ?: error("تعذر قراءة السور")
    parsed.suwar.associateBy { it.id }
  }

  fun buildSurahMp3Url(reciter: VerifiedReciter, surahId: Int): String? {
    if (!reciter.isPlayable || !reciter.availableSurahs.contains(surahId)) return null
    val base = reciter.serverUrl ?: return null
    return "$base${"%03d".format(surahId)}.mp3"
  }

  suspend fun verifySurahMp3Url(url: String, surahId: Int): SurahUrlAudit = withContext(Dispatchers.IO) {
    urlAuditCache[url]?.let { return@withContext it }

    val audit = runCatching {
      val headReq = Request.Builder().url(url).head().build()
      QuranApiClient.okHttpClient.newCall(headReq).execute().use { resp ->
        val code = resp.code
        val contentType = resp.header("Content-Type")
        val contentLen = resp.header("Content-Length")?.toLongOrNull() ?: 0L
        val isAudio = contentType == null ||
          contentType.contains("audio", ignoreCase = true) ||
          contentType.contains("octet-stream", ignoreCase = true)
        if (resp.isSuccessful && isAudio && (contentLen == 0L || contentLen > 1024L)) {
          return@runCatching SurahUrlAudit(surahId, url, true, contentType, contentLen)
        }
        if (code == 405 || code == 403) {
          val rangeReq = Request.Builder().url(url).addHeader("Range", "bytes=0-1").get().build()
          QuranApiClient.okHttpClient.newCall(rangeReq).execute().use { r2 ->
            val ct = r2.header("Content-Type")
            val okAudio = ct == null || ct.contains("audio", ignoreCase = true) || ct.contains("octet-stream", ignoreCase = true)
            val ok = (r2.code == 200 || r2.code == 206) && okAudio
            return@runCatching SurahUrlAudit(surahId, url, ok, ct, r2.header("Content-Range")?.substringAfter("/")?.toLongOrNull() ?: 0L)
          }
        }
        SurahUrlAudit(surahId, url, false, contentType, contentLen)
      }
    }.getOrElse {
      SurahUrlAudit(surahId, url, false, null, 0L)
    }

    urlAuditCache[url] = audit
    audit
  }

  suspend fun resolveLocalTimingsOnlyIfMatched(
    reciter: VerifiedReciter,
    surahId: Int,
  ): List<AyahTimingDto> = withContext(Dispatchers.IO) {
    if (surahId !in 1..114) return@withContext emptyList()
    val reads = getAvailableTimingReads()
    val exactRead = findExactTimingRead(reciter.moshafId ?: 0, reciter.serverUrl, reads)
      ?: return@withContext emptyList()
    fetchTimingsForReadId(exactRead.id, surahId)
  }

  suspend fun resolveRecitationAudio(
    reciter: VerifiedReciter,
    surahId: Int,
  ): ResolvedRecitationAudio? = withContext(Dispatchers.IO) {
    if (surahId !in 1..114) return@withContext null
    val reads = getAvailableTimingReads()
    val moshafId = reciter.moshafId ?: 0

    // 1. Exact mp3quran timing read matching this exact moshafId / folder_url
    val exactRead = findExactTimingRead(moshafId, reciter.serverUrl, reads)
    if (exactRead != null) {
      val timings = fetchTimingsForReadId(exactRead.id, surahId)
      if (timings.isNotEmpty()) {
        val candidateUrl = exactRead.folder_url?.takeIf { it.isNotBlank() }?.let { base ->
          "${normalizeHttpsServer(base)}${"%03d".format(surahId)}.mp3"
        } ?: buildSurahMp3Url(reciter, surahId)
        if (candidateUrl != null && verifySurahMp3Url(candidateUrl, surahId).isReachable) {
          return@withContext ResolvedRecitationAudio.SingleTimedTrack(
            streamUrl = candidateUrl,
            ayahTimings = timings,
            timingSourceLabel = "متزامن مع الآيات (mp3quran)",
            matchesSelectedMoshafUrl = true,
          )
        }
      }
    }

    // 2. Quran.com synced chapter recitation (exact audio_url + timestamps)
    val qcomSynced = getQuranComSyncedRecitation(reciter.displayName, surahId)
    if (qcomSynced != null && verifySurahMp3Url(qcomSynced.streamUrl, surahId).isReachable) {
      return@withContext ResolvedRecitationAudio.SingleTimedTrack(
        streamUrl = qcomSynced.streamUrl,
        ayahTimings = qcomSynced.ayahTimings,
        timingSourceLabel = qcomSynced.timingSourceLabel,
        matchesSelectedMoshafUrl = false,
      )
    }

    // 3. AlQuran.cloud verse-by-verse playlist (accurate Hafs Murattal ayah-by-ayah e.g. ماهر المعيقلي، محمد جبريل)
    val verseSegments = getAlQuranVerseByVersePlaylist(reciter.displayName, surahId)
    if (verseSegments.isNotEmpty()) {
      return@withContext ResolvedRecitationAudio.VerseByVersePlaylist(
        segments = verseSegments,
        timingSourceLabel = "متزامن آية بآية (AlQuran.cloud)",
      )
    }

    // 4. Secondary mp3quran TimingRead for the same reciter, STRICTLY paired with read.folder_url
    // (e.g. مصطفى إسماعيل read=288, محمود علي البنا read=122)
    val pairedRead = findSecondaryTimingReadByName(reciter.displayName, reads)
    if (pairedRead != null && !pairedRead.folder_url.isNullOrBlank()) {
      val timings = fetchTimingsForReadId(pairedRead.id, surahId)
      if (timings.isNotEmpty()) {
        val pairedUrl = "${normalizeHttpsServer(pairedRead.folder_url)}${"%03d".format(surahId)}.mp3"
        if (verifySurahMp3Url(pairedUrl, surahId).isReachable) {
          return@withContext ResolvedRecitationAudio.SingleTimedTrack(
            streamUrl = pairedUrl,
            ayahTimings = timings,
            timingSourceLabel = "متزامن مع الآيات (${pairedRead.rewaya ?: "mp3quran"})",
            matchesSelectedMoshafUrl = false,
          )
        }
      }
    }

    // 5. Fallback plain stream (no ayah timing available for this reciter)
    val fallbackUrl = buildSurahMp3Url(reciter, surahId)
    if (fallbackUrl != null && verifySurahMp3Url(fallbackUrl, surahId).isReachable) {
      return@withContext ResolvedRecitationAudio.UntimedSingleTrack(fallbackUrl)
    }
    null
  }

  private fun findExactTimingRead(
    moshafId: Int,
    serverUrl: String?,
    reads: List<TimingReadDto>,
  ): TimingReadDto? {
    val normalizedServer = serverUrl?.takeIf { it.isNotBlank() }?.let { normalizeHttpsServer(it) }
    return reads.firstOrNull { r ->
      (moshafId > 0 && r.id == moshafId) ||
        (normalizedServer != null && !r.folder_url.isNullOrBlank() && normalizeHttpsServer(r.folder_url) == normalizedServer)
    }
  }

  private fun findSecondaryTimingReadByName(
    reciterName: String,
    reads: List<TimingReadDto>,
  ): TimingReadDto? {
    val normTarget = normalizeArabicForSearch(reciterName)
    val compactTarget = compactArabic(reciterName)
    val tokens = normTarget.split(" ").filter { it.length >= 3 && it != "الشيخ" && it != "شيخ" }
    if (tokens.isEmpty() && compactTarget.isEmpty()) return null
    val candidates = reads.filter { r ->
      val name = r.name.orEmpty()
      val normRead = normalizeArabicForSearch(name)
      val compactRead = compactArabic(name)
      normRead == normTarget ||
        compactRead == compactTarget ||
        (compactTarget.length >= 5 && compactRead.contains(compactTarget)) ||
        (tokens.isNotEmpty() && tokens.all { normRead.contains(it) })
    }
    return candidates.firstOrNull {
      val rw = normalizeArabicForSearch(it.rewaya.orEmpty())
      rw.contains("حفص")
    } ?: candidates.firstOrNull {
      val rw = normalizeArabicForSearch(it.rewaya.orEmpty())
      rw.contains("المجود") || rw.isBlank()
    } ?: candidates.firstOrNull()
  }

  private suspend fun fetchTimingsForReadId(readId: Int, surahId: Int): List<AyahTimingDto> {
    val cacheKey = "api/timing_${readId}_$surahId.json"
    val cachedBytes = diskCache.readVerifiedBytes(cacheKey)
    if (cachedBytes != null) {
      val cachedList = runCatching {
        ayahTimingListAdapter.fromJson(cachedBytes.decodeToString()).orEmpty()
      }.getOrDefault(emptyList())
      if (cachedList.isNotEmpty()) return cachedList
    }

    val resp = runCatching { api.getAyahTimingRaw(surahId, readId) }.getOrNull()
    if (resp == null || !resp.isSuccessful) return emptyList()
    val bodyBytes = resp.body()?.bytes() ?: return emptyList()
    val list = runCatching {
      ayahTimingListAdapter.fromJson(bodyBytes.decodeToString()).orEmpty()
    }.getOrDefault(emptyList())
    if (list.isNotEmpty()) {
      diskCache.writeVerifiedBytes(cacheKey, bodyBytes)
    }
    return list
  }

  private suspend fun getAvailableTimingReads(): List<TimingReadDto> {
    timingReadsCache?.let { cached ->
      if (cached.isNotEmpty()) return cached
    }
    val bytes = diskCache.readVerifiedBytes("api/mp3quran_timing_reads.json") ?: run {
      val resp = runCatching { api.getTimingReadsRaw() }.getOrNull()
      val body = if (resp?.isSuccessful == true) resp.body()?.bytes() else null
      if (body != null) diskCache.writeVerifiedBytes("api/mp3quran_timing_reads.json", body)
      body
    } ?: return emptyList()

    val parsed = parseTimingReadsJson(bytes.decodeToString())
    if (parsed.isNotEmpty()) {
      timingReadsCache = parsed
    }
    return parsed
  }

  private suspend fun getAlQuranVerseByVersePlaylist(
    reciterDisplayName: String,
    surahId: Int,
  ): List<VerseAudioSegment> {
    val editionId = findMatchingAlQuranCloudEditionId(reciterDisplayName) ?: return emptyList()
    val cacheKey = "api/alquran_surah_audio_${editionId}_$surahId.json"
    val bytes = diskCache.readVerifiedBytes(cacheKey) ?: run {
      val resp = runCatching {
        QuranApiClient.alQuranCloudApi.getSurahAudioRaw(surahId, editionId)
      }.getOrNull()
      val body = if (resp?.isSuccessful == true) resp.body()?.bytes() else null
      if (body != null) diskCache.writeVerifiedBytes(cacheKey, body)
      body
    } ?: return emptyList()

    val parsed = runCatching { alQuranSurahAudioAdapter.fromJson(bytes.decodeToString()) }.getOrNull()
    return parsed?.data?.ayahs.orEmpty()
      .filter { it.numberInSurah > 0 && it.audio.isNotBlank() }
      .map { VerseAudioSegment(ayahNumber = it.numberInSurah, audioUrl = it.audio) }
      .sortedBy { it.ayahNumber }
  }

  private suspend fun findMatchingAlQuranCloudEditionId(reciterDisplayName: String): String? {
    val editions = getAlQuranVerseByVerseEditions()
    if (editions.isEmpty()) return null

    val normTarget = normalizeArabicForSearch(reciterDisplayName)
    val compactTarget = compactArabic(reciterDisplayName)
    val tokens = normTarget.split(" ").filter { it.length >= 3 && it != "الشيخ" && it != "شيخ" }
    if (tokens.isEmpty() && compactTarget.isEmpty()) return null

    val matching = editions.filter { ed ->
      val normEd = normalizeArabicForSearch(ed.name)
      val compactEd = compactArabic(ed.name)
      normEd == normTarget ||
        compactEd == compactTarget ||
        (compactTarget.length >= 5 && compactEd.contains(compactTarget)) ||
        (tokens.isNotEmpty() && tokens.all { normEd.contains(it) })
    }
    val nonMujawwad = matching.firstOrNull { !it.identifier.contains("mujawwad", ignoreCase = true) }
    return (nonMujawwad ?: matching.firstOrNull())?.identifier
  }

  private suspend fun getAlQuranVerseByVerseEditions(): List<AlQuranCloudAudioEditionDto> {
    alQuranAudioEditionsCache?.let { cached ->
      if (cached.isNotEmpty()) return cached
    }
    val cacheKey = "api/alquran_audio_editions.json"
    val bytes = diskCache.readVerifiedBytes(cacheKey) ?: run {
      val resp = runCatching { QuranApiClient.alQuranCloudApi.getAudioEditionsRaw() }.getOrNull()
      val body = if (resp?.isSuccessful == true) resp.body()?.bytes() else null
      if (body != null) diskCache.writeVerifiedBytes(cacheKey, body)
      body
    } ?: return emptyList()

    val parsed = runCatching { alQuranEditionsAdapter.fromJson(bytes.decodeToString()) }.getOrNull()
    val filtered = parsed?.data.orEmpty().filter { ed ->
      ed.language == "ar" &&
        ed.type == "versebyverse" &&
        !ed.identifier.endsWith("-2")
    }
    if (filtered.isNotEmpty()) {
      alQuranAudioEditionsCache = filtered
    }
    return filtered
  }

  suspend fun getQuranComSyncedRecitation(
    reciterDisplayName: String,
    surahId: Int,
  ): ResolvedPlaybackSource? = withContext(Dispatchers.IO) {
    val quranComReciterId = findMatchingQuranComReciterId(reciterDisplayName) ?: return@withContext null
    val cacheKey = "api/qcom_recitation_${quranComReciterId}_$surahId.json"
    val audioAdapter = QuranApiClient.moshi.adapter(QuranComChapterAudioResponse::class.java)

    val bytes = diskCache.readVerifiedBytes(cacheKey) ?: run {
      val resp = runCatching {
        QuranApiClient.quranComApi.getChapterRecitationWithSegmentsRaw(quranComReciterId, surahId, true)
      }.getOrNull()
      val body = if (resp?.isSuccessful == true) resp.body()?.bytes() else null
      if (body != null) diskCache.writeVerifiedBytes(cacheKey, body)
      body
    } ?: return@withContext null

    val parsed = runCatching { audioAdapter.fromJson(bytes.decodeToString()) }.getOrNull()
    val audioFile = parsed?.audio_file ?: return@withContext null
    val url = audioFile.audio_url ?: return@withContext null
    val timings = audioFile.timestamps.mapNotNull { ts ->
      val ayahNum = ts.verse_key.substringAfter(":").toIntOrNull() ?: return@mapNotNull null
      AyahTimingDto(
        ayah = ayahNum,
        start_time = ts.timestamp_from,
        end_time = ts.timestamp_to,
      )
    }
    if (timings.isEmpty()) return@withContext null
    ResolvedPlaybackSource(
      streamUrl = url,
      ayahTimings = timings,
      timingSourceLabel = "متزامن مع الآيات (Quran.com)",
    )
  }

  private suspend fun findMatchingQuranComReciterId(reciterDisplayName: String): Int? {
    val recitersCache = "api/qcom_chapter_reciters_ar.json"
    val adapter = QuranApiClient.moshi.adapter(QuranComChapterRecitersResponse::class.java)
    val bytes = diskCache.readVerifiedBytes(recitersCache) ?: run {
      val resp = runCatching { QuranApiClient.quranComApi.getChapterRecitersRaw("ar") }.getOrNull()
      val body = if (resp?.isSuccessful == true) resp.body()?.bytes() else null
      if (body != null) diskCache.writeVerifiedBytes(recitersCache, body)
      body
    } ?: return null

    val list = runCatching { adapter.fromJson(bytes.decodeToString())?.reciters.orEmpty() }
      .getOrDefault(emptyList())
    return findMatchingQuranComReciterIdFromList(reciterDisplayName, list)
  }
}
