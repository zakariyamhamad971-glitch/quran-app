package com.example.data.prayer

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import com.example.data.quran.CreditSourceInfo
import com.example.data.quran.QuranApiClient
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class KiwifuRemoteFile(
  val fileName: String,
  val sizeBytes: Long,
)

data class MuezzinCatalogSnapshot(
  val groups: List<MuezzinGroup>,
  val countries: List<String>,
  val isFromCache: Boolean = false,
  val loadError: String? = null,
)

data class AdhanSoundRegistrySnapshot(
  val version: String,
  val license: String,
  val attribution: String,
  val sounds: List<AdhanSoundRegistryEntry>,
  val isFromCache: Boolean = false,
  val loadError: String? = null,
)

object KiwifuAdhanParser {
  const val JSDELIVR_LISTING_URL =
    "https://data.jsdelivr.com/v1/package/gh/Kiwifu/adhan-mp3@main"
  const val GITHUB_CONTENTS_URL =
    "https://api.github.com/repos/Kiwifu/adhan-mp3/contents/"
  const val CDN_BASE_URL =
    "https://cdn.jsdelivr.net/gh/Kiwifu/adhan-mp3@main/"
  const val RAW_BASE_URL =
    "https://raw.githubusercontent.com/Kiwifu/adhan-mp3/main/"
  const val ATTRIBUTION_TEXT =
    "Adhan recordings: github.com/Kiwifu/adhan-mp3, free for Islamic apps and personal use"

  private val DIACRITICS_REGEX = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640]")
  private val ARABIC_CHAR_REGEX = Regex("[\\u0600-\\u06FF]")
  private val PAREN_CONTENT_REGEX = Regex("\\(([^()]+)\\)\\s*$")
  private val RECORDING_NUMBER_REGEX = Regex("(?:^|\\s|_)(\\d{1,3})(?=\\s|_|$)")

  private val EGYPT_PRIORITY = listOf(
    "مصطفى اسماعيل",
    "محمد صديق المنشاوي",
    "محمود خليل الحصري",
    "محمد محمود الطبلاوي",
    "محمود علي البنا",
    "عبد الباسط عبد الصمد",
    "ابو العينين شعيشع",
    "محمد الشعشاعي",
  )

  private val SAUDI_HARAM_PRIORITY = listOf(
    "عبد الرحمن السديس",
    "ياسر الدوسري",
    "ماهر المعيقلي",
    "علي بن احمد ملا",
    "محمد خليل رمل",
    "فاروق حضراوي",
    "عاصم بخراري",
    "نايف فداح",
    "عبد العزيز السحيم",
  )

  private val FAMOUS_PRIORITY = listOf(
    "مشاري العفاسي",
    "ناصر القطامي",
  )

  fun encodeFileNameForUrl(fileName: String): String {
    return fileName.split('/').joinToString("/") { segment ->
      URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
  }

  fun deterministicRecordingId(fileName: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(fileName.toByteArray(Charsets.UTF_8))
    val shortHex = digest.take(5).joinToString("") { "%02x".format(it) }
    val base = fileName.substringBeforeLast('.')
      .replace(Regex("[^a-zA-Z0-9_-]"), "_")
      .replace(Regex("_+"), "_")
      .trim('_')
      .take(40)
      .ifBlank { "adhan" }
    return "kw_${shortHex}_$base"
  }

  fun normalizeArabicForSearch(raw: String): String {
    if (raw.isBlank()) return ""
    val noMarks = DIACRITICS_REGEX.replace(raw, "")
    val sb = StringBuilder(noMarks.length)
    for (ch in noMarks) {
      val mapped = when (ch) {
        'أ', 'إ', 'آ', 'ٱ' -> 'ا'
        'ى' -> 'ي'
        'ة' -> 'ه'
        'ؤ' -> 'و'
        'ئ' -> 'ي'
        '_', '-' -> ' '
        else -> ch
      }
      sb.append(mapped)
    }
    return sb.toString()
      .lowercase(Locale.ROOT)
      .replace(Regex("\\s+"), " ")
      .trim()
  }

  fun parseJsDelivrListingJson(jsonText: String): List<KiwifuRemoteFile> {
    val root = JSONObject(jsonText)
    val filesArray = root.optJSONArray("files") ?: return emptyList()
    val out = mutableListOf<KiwifuRemoteFile>()
    collectJsDelivrFiles(filesArray, prefix = "", out = out)
    return out
  }

  private fun collectJsDelivrFiles(
    array: JSONArray,
    prefix: String,
    out: MutableList<KiwifuRemoteFile>,
  ) {
    for (i in 0 until array.length()) {
      val obj = array.optJSONObject(i) ?: continue
      val type = obj.optString("type", "")
      val name = obj.optString("name", "").trim().trimStart('/')
      if (name.isEmpty()) continue
      val fullPath = if (prefix.isEmpty()) name else "$prefix/$name"
      if (type == "directory") {
        obj.optJSONArray("files")?.let { nested ->
          collectJsDelivrFiles(nested, fullPath, out)
        }
      } else if (name.endsWith(".mp3", ignoreCase = true)) {
        out += KiwifuRemoteFile(
          fileName = fullPath,
          sizeBytes = obj.optLong("size", 0L).coerceAtLeast(0L),
        )
      }
    }
  }

  fun parseGitHubContentsJson(jsonText: String): List<KiwifuRemoteFile> {
    val arr = JSONArray(jsonText)
    val out = ArrayList<KiwifuRemoteFile>(arr.length())
    for (i in 0 until arr.length()) {
      val obj = arr.optJSONObject(i) ?: continue
      val type = obj.optString("type", "file")
      if (type != "file") continue
      val name = obj.optString("name", "").trim()
      if (!name.endsWith(".mp3", ignoreCase = true)) continue
      out += KiwifuRemoteFile(
        fileName = obj.optString("path", name).ifBlank { name },
        sizeBytes = obj.optLong("size", 0L).coerceAtLeast(0L),
      )
    }
    return out
  }

  fun parseListingToGroups(files: List<KiwifuRemoteFile>): List<MuezzinGroup> {
    if (files.isEmpty()) return emptyList()

    val mp3Files = files.filter { it.fileName.endsWith(".mp3", ignoreCase = true) }
    if (mp3Files.isEmpty()) return emptyList()

    // First pass: parse raw Arabic/English components for each file
    val parsedRecords = mp3Files.map { parseSingleFile(it) }
    val existingNormalizedNames = parsedRecords.mapNotNull { it.normalizedArabicKey }.toSet()

    // Merge files belonging to same normalized muezzin key
    val bucketed = LinkedHashMap<String, MutableList<ParsedFileEntry>>()
    for (parsed in parsedRecords) {
      val groupKey = resolveCanonicalGroupKey(parsed, existingNormalizedNames)
      bucketed.getOrPut(groupKey) { mutableListOf() }.add(parsed)
    }

    val groups = bucketed.entries.map { (groupKey, entries) ->
      val displayArabicName = chooseGroupDisplayName(entries)
      val displayEnglishName = entries
        .map { it.englishMuezzinLabel }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
      val countryCounts = entries
        .mapNotNull { it.countryAr?.takeIf { c -> c.isNotBlank() } }
        .groupingBy { it }
        .eachCount()
      val groupCountry = countryCounts.maxByOrNull { it.value }?.key ?: "أخرى"
      val groupPlace = entries.firstNotNullOfOrNull { it.placeDetailAr?.takeIf { p -> p != groupCountry } }

      val sortedEntries = entries.sortedWith(
        compareBy<ParsedFileEntry>(
          { it.recordingNumber ?: Int.MAX_VALUE },
          { if (it.isFajr) 1 else 0 },
          { if (it.isHq) 0 else 1 },
          { it.file.fileName },
        ),
      )

      val recordingItems = sortedEntries.mapIndexed { idx, item ->
        val numLabel = item.recordingNumber ?: (idx + 1)
        val titleParts = buildList {
          add("تسجيل ${PrayerFormatters.toArabicIndicDigits(numLabel)}")
          if (item.isFajr) add("فجر")
          if (item.isHq) add("جودة عالية HQ")
          val place = item.placeDetailAr ?: item.countryAr
          if (!place.isNullOrBlank()) add(place)
        }
        AdhanRecordingItem(
          id = deterministicRecordingId(item.file.fileName),
          fileName = item.file.fileName,
          arabicTitle = titleParts.joinToString(" • "),
          recordingNumber = item.recordingNumber ?: (idx + 1),
          isFajr = item.isFajr,
          isHq = item.isHq,
          countryAr = item.countryAr,
          placeDetailAr = item.placeDetailAr,
          sizeBytes = item.file.sizeBytes,
          primaryUrl = CDN_BASE_URL + encodeFileNameForUrl(item.file.fileName),
          fallbackUrl = RAW_BASE_URL + encodeFileNameForUrl(item.file.fileName),
        )
      }

      MuezzinGroup(
        id = "grp_" + groupKey.hashCode().toUInt().toString(16),
        arabicName = displayArabicName,
        englishName = displayEnglishName,
        countryAr = groupCountry,
        placeDetailAr = groupPlace,
        hasFajr = recordingItems.any { it.isFajr },
        recordings = recordingItems,
      )
    }

    val arabicCollator = Collator.getInstance(Locale("ar")).apply {
      strength = Collator.PRIMARY
    }

    return groups.sortedWith { a, b ->
      val rankA = priorityRank(a)
      val rankB = priorityRank(b)
      if (rankA != rankB) return@sortedWith rankA.compareTo(rankB)

      // Within remaining countries bucket (tier 5), sort by country then Arabic name
      if (rankA >= 5_000) {
        val cCmp = arabicCollator.compare(a.countryAr, b.countryAr)
        if (cCmp != 0) return@sortedWith cCmp
      }
      arabicCollator.compare(a.arabicName, b.arabicName)
    }
  }

  private fun priorityRank(group: MuezzinGroup): Int {
    val normName = normalizeArabicForSearch(group.arabicName)

    val egyptIdx = findPriorityIndex(normName, EGYPT_PRIORITY)
    if (egyptIdx >= 0) return egyptIdx // 0..7
    if (group.countryAr == "مصر") return 1_000

    val saudiIdx = findPriorityIndex(normName, SAUDI_HARAM_PRIORITY)
    if (saudiIdx >= 0) return 2_000 + saudiIdx // 2000..2008
    if (group.countryAr == "السعودية") return 3_000

    val famousIdx = findPriorityIndex(normName, FAMOUS_PRIORITY)
    if (famousIdx >= 0) return 4_000 + famousIdx // 4000..4001

    return 5_000
  }

  private fun findPriorityIndex(normName: String, list: List<String>): Int {
    val nameTokens = normName.split(' ').filter { it.isNotBlank() }.toSet()
    for (i in list.indices) {
      val target = list[i]
      if (normName == target) return i
      val targetTokens = target.split(' ').filter { it.isNotBlank() }
      if (targetTokens.all { token -> token in nameTokens }) return i
    }
    return -1
  }

  private fun chooseGroupDisplayName(entries: List<ParsedFileEntry>): String {
    val arabicNames = entries.mapNotNull { it.arabicMuezzinName?.takeIf { n -> n.isNotBlank() } }
    if (arabicNames.isNotEmpty()) {
      // Prefer longest clean name (e.g. "مشاري راشد العفاسي" over "مشاري العفاسي")
      return arabicNames.maxWith(
        compareBy<String>({ it.length }, { entries.count { e -> e.arabicMuezzinName == it } }),
      )
    }
    return entries.first().englishMuezzinLabel
  }

  private fun resolveCanonicalGroupKey(
    parsed: ParsedFileEntry,
    existingNormalizedNames: Set<String>,
  ): String {
    val normAr = parsed.normalizedArabicKey
    if (!normAr.isNullOrBlank()) {
      // Collapse known repository variants of Mishary Alafasy that both appear in Arabic
      if (normAr == "مشاري العفاسي" || normAr == "مشاري راشد العفاسي") {
        return "ar:مشاري راشد العفاسي"
      }
      return "ar:$normAr"
    }
    val engNorm = parsed.englishMuezzinLabel.lowercase(Locale.ROOT)
    // Link paren-less files like Ali_Ibn_Ahmad_Mala_HQ.mp3 ONLY to an existing real Arabic entry
    if (engNorm.contains("ali") && engNorm.contains("mala") &&
      existingNormalizedNames.contains("علي بن احمد ملا")
    ) {
      return "ar:علي بن احمد ملا"
    }
    if ((engNorm.contains("nakshabandi") || engNorm.contains("naqshabandi")) &&
      existingNormalizedNames.any { it.contains("نقشبند") }
    ) {
      val existing = existingNormalizedNames.first { it.contains("نقشبند") }
      return "ar:$existing"
    }
    return "en:$engNorm"
  }

  private data class ParsedFileEntry(
    val file: KiwifuRemoteFile,
    val arabicMuezzinName: String?,
    val normalizedArabicKey: String?,
    val englishMuezzinLabel: String,
    val recordingNumber: Int?,
    val isFajr: Boolean,
    val isHq: Boolean,
    val countryAr: String?,
    val placeDetailAr: String?,
  )

  private fun parseSingleFile(file: KiwifuRemoteFile): ParsedFileEntry {
    val baseName = file.fileName.substringAfterLast('/').substringBeforeLast('.')
    val parenMatch = PAREN_CONTENT_REGEX.find(baseName)
    val insideParensRaw = parenMatch?.groupValues?.getOrNull(1).orEmpty()
    val englishRaw = if (parenMatch != null) {
      baseName.removeRange(parenMatch.range)
    } else {
      baseName
    }

    val englishClean = englishRaw
      .replace('_', ' ')
      .replace(Regex("\\s+"), " ")
      .trim()
      .trimEnd('-', ' ')

    val englishBeforeDash = englishClean.substringBefore(" - ").trim()
    val englishAfterDash = englishClean.substringAfter(" - ", "").trim()

    val isHq = Regex("(?i)\\bHQ\\b").containsMatchIn(englishClean)
    val isFajrEnglish = Regex("(?i)\\bFajr\\b").containsMatchIn(englishClean) ||
      englishRaw.contains("_Fajr", ignoreCase = true)

    val recNumber = extractRecordingNumber(englishBeforeDash)
      ?: extractRecordingNumber(englishAfterDash)

    val englishMuezzinLabel = englishBeforeDash
      .replace(Regex("(?i)\\bHQ\\b"), "")
      .replace(Regex("\\b\\d{1,3}\\b$"), "")
      .replace(Regex("\\s+"), " ")
      .trim()
      .ifBlank { englishClean }

    var arabicName: String? = null
    var placeDetailAr: String? = null
    var countryFromArabic: String? = null
    var isFajrArabic = false

    if (insideParensRaw.isNotBlank()) {
      val arabicWithSpaces = insideParensRaw
        .replace("_-_", " - ")
        .replace('_', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

      if (arabicWithSpaces.contains("الفجر") || arabicWithSpaces.contains("فجر")) {
        isFajrArabic = true
      }

      val dashIdx = arabicWithSpaces.indexOf(" - ")
      if (dashIdx >= 0) {
        val rawLeft = arabicWithSpaces.substring(0, dashIdx).trim()
        val rawRight = arabicWithSpaces.substring(dashIdx + 3).trim()
        arabicName = stripArabicAdhanPrefix(rawLeft)

        val cleanedRight = rawRight
          .removePrefix("فجر ")
          .removePrefix("الفجر ")
          .trim()
        if (cleanedRight.isNotBlank() && cleanedRight != "الفجر" && cleanedRight != "فجر") {
          placeDetailAr = cleanedRight
          countryFromArabic = inferCountryAr(cleanedRight, englishClean)
        } else {
          countryFromArabic = inferCountryAr("", englishClean)
        }
      } else {
        val stripped = stripArabicAdhanPrefix(arabicWithSpaces)
        arabicName = stripped.ifBlank { arabicWithSpaces }
        countryFromArabic = inferCountryAr(arabicWithSpaces, englishClean)
        if (arabicWithSpaces.contains("الحرم المكي")) placeDetailAr = "الحرم المكي"
        else if (arabicWithSpaces.contains("الحرم المدني")) placeDetailAr = "الحرم المدني"
        else if (arabicWithSpaces.contains("الأقصى") || arabicWithSpaces.contains("الاقصى")) {
          placeDetailAr = "المسجد الأقصى"
        }
      }
    }

    val finalCountry = countryFromArabic ?: inferCountryAr("", englishClean)

    return ParsedFileEntry(
      file = file,
      arabicMuezzinName = arabicName?.takeIf { ARABIC_CHAR_REGEX.containsMatchIn(it) },
      normalizedArabicKey = arabicName?.let { normalizeArabicForSearch(it) }?.takeIf { it.isNotBlank() },
      englishMuezzinLabel = englishMuezzinLabel,
      recordingNumber = recNumber,
      isFajr = isFajrEnglish || isFajrArabic,
      isHq = isHq,
      countryAr = finalCountry,
      placeDetailAr = placeDetailAr,
    )
  }

  private fun stripArabicAdhanPrefix(raw: String): String {
    var out = raw.trim()
    val prefixes = listOf(
      "اذان الفجر بصوت ",
      "أذان الفجر بصوت ",
      "اذان بصوت ",
      "أذان بصوت ",
    )
    for (prefix in prefixes) {
      if (out.startsWith(prefix)) {
        out = out.removePrefix(prefix).trim()
        break
      }
    }
    return out
  }

  private fun extractRecordingNumber(segment: String): Int? {
    if (segment.isBlank()) return null
    val matches = RECORDING_NUMBER_REGEX.findAll(segment).toList()
    return matches.lastOrNull()?.groupValues?.getOrNull(1)?.toIntOrNull()
  }

  private fun inferCountryAr(arabicText: String, englishText: String): String? {
    val normAr = normalizeArabicForSearch(arabicText)
    val lowEn = englishText.lowercase(Locale.ROOT)
    return when {
      normAr.contains("مصر") || normAr.contains("القاهره") || normAr.contains("الرفاعي") ||
        normAr.contains("الازهر") || normAr.contains("طنطا") ||
        lowEn.contains("egypt") || lowEn.contains("cairo") -> "مصر"

      normAr.contains("السعوديه") || normAr.contains("الحرم المكي") || normAr.contains("الحرم المدني") ||
        normAr.contains("الراجحي") || normAr.contains("الرياض") || normAr.contains("مكه") ||
        normAr.contains("المدينه") || normAr.contains("نجران") ||
        lowEn.contains("saudi") || lowEn.contains("makk") || lowEn.contains("mecca") ||
        lowEn.contains("madin") || lowEn.contains("medin") || lowEn.contains("haram") ||
        lowEn.contains("rajehe") -> "السعودية"

      normAr.contains("الكويت") || lowEn.contains("kuwait") -> "الكويت"
      normAr.contains("الامارات") || normAr.contains("دبي") || normAr.contains("عجمان") ||
        lowEn.contains("uae") || lowEn.contains("dubai") || lowEn.contains("ajman") -> "الإمارات"
      normAr.contains("العراق") || normAr.contains("بغداد") || lowEn.contains("iraq") -> "العراق"
      normAr.contains("فلسطين") || normAr.contains("الاقصي") || normAr.contains("القدس") ||
        lowEn.contains("aqsa") || lowEn.contains("palestin") || lowEn.contains("quds") -> "فلسطين"
      normAr.contains("سوريا") || normAr.contains("دمشق") || lowEn.contains("syria") -> "سوريا"
      normAr.contains("الاردن") || normAr.contains("عمان") || lowEn.contains("jordan") -> "الأردن"
      normAr.contains("تركيا") || normAr.contains("اسطنبول") || lowEn.contains("turkey") ||
        lowEn.contains("istanbul") -> "تركيا"
      normAr.contains("المغرب") || lowEn.contains("morocco") -> "المغرب"
      normAr.contains("الجزائر") || lowEn.contains("algeria") -> "الجزائر"
      normAr.contains("تونس") || lowEn.contains("tunisia") -> "تونس"
      normAr.contains("لبنان") || lowEn.contains("lebanon") -> "لبنان"
      normAr.contains("قطر") || lowEn.contains("qatar") -> "قطر"
      normAr.contains("البحرين") || lowEn.contains("bahrain") -> "البحرين"
      normAr.contains("اليمن") || lowEn.contains("yemen") -> "اليمن"
      normAr.contains("السودان") || lowEn.contains("sudan") -> "السودان"
      normAr.contains("باكستان") || lowEn.contains("pakistan") -> "باكستان"
      normAr.contains("اندونيسيا") || lowEn.contains("indonesia") -> "إندونيسيا"
      normAr.contains("ماليزيا") || lowEn.contains("malaysia") -> "ماليزيا"
      normAr.contains("بروناي") || lowEn.contains("brunei") -> "بروناي"
      normAr.contains("البوسنه") || lowEn.contains("bosnia") -> "البوسنة"
      normAr.contains("قطريه") -> "قطر"
      else -> null
    }
  }

  fun matchesSearchQuery(group: MuezzinGroup, rawQuery: String): Boolean {
    val normQuery = normalizeArabicForSearch(rawQuery)
    if (normQuery.isEmpty()) return true
    val searchable = buildString {
      append(normalizeArabicForSearch(group.arabicName)).append(' ')
      append(normalizeArabicForSearch(group.countryAr)).append(' ')
      group.placeDetailAr?.let { append(normalizeArabicForSearch(it)).append(' ') }
      append(group.englishName.lowercase(Locale.ROOT)).append(' ')
      group.recordings.forEach { rec ->
        append(normalizeArabicForSearch(rec.arabicTitle)).append(' ')
        append(rec.fileName.lowercase(Locale.ROOT)).append(' ')
      }
    }
    return searchable.contains(normQuery)
  }

  fun selectRecordingForPrayer(
    group: MuezzinGroup,
    preferredRecording: AdhanRecordingItem,
    prayerType: PrayerType,
  ): AdhanRecordingItem {
    if (prayerType == PrayerType.FAJR) {
      return group.recordings.firstOrNull { it.isFajr } ?: preferredRecording
    }
    return preferredRecording
  }
}

class AdhanSoundRepository(private val context: Context) {

  private fun muezzinsDir(): File {
    val dir = File(context.filesDir, "muezzins")
    if (!dir.exists()) dir.mkdirs()
    return dir
  }

  private fun listingCacheFile(): File = File(muezzinsDir(), "kiwifu_listing_v1.json")

  private fun downloadedMetaFile(): File = File(muezzinsDir(), "downloaded_meta_v1.json")

  suspend fun loadMuezzinGroups(forceRefresh: Boolean = false): MuezzinCatalogSnapshot =
    withContext(Dispatchers.IO) {
      val cacheFile = listingCacheFile()
      val cachedFiles = if (!forceRefresh) readCachedListing() else emptyList()
      if (cachedFiles.isNotEmpty() && !isCacheStale(cacheFile)) {
        val groups = KiwifuAdhanParser.parseListingToGroups(cachedFiles)
        return@withContext MuezzinCatalogSnapshot(
          groups = groups,
          countries = extractOrderedCountries(groups),
          isFromCache = true,
          loadError = null,
        )
      }

      val fetched = runCatching { fetchKiwifuFileListing() }.getOrElse { emptyList() }
      if (fetched.isNotEmpty()) {
        writeCachedListing(fetched)
        val groups = KiwifuAdhanParser.parseListingToGroups(fetched)
        return@withContext MuezzinCatalogSnapshot(
          groups = groups,
          countries = extractOrderedCountries(groups),
          isFromCache = false,
          loadError = null,
        )
      }

      val staleCached = if (cachedFiles.isNotEmpty()) cachedFiles else readCachedListing()
      if (staleCached.isNotEmpty()) {
        val groups = KiwifuAdhanParser.parseListingToGroups(staleCached)
        return@withContext MuezzinCatalogSnapshot(
          groups = groups,
          countries = extractOrderedCountries(groups),
          isFromCache = true,
          loadError = "تعذر تحديث القائمة عبر الإنترنت، تم عرض القائمة المحفوظة.",
        )
      }

      val offlineFiles = readDownloadedOfflineFiles()
      val offlineGroups = KiwifuAdhanParser.parseListingToGroups(offlineFiles)
      MuezzinCatalogSnapshot(
        groups = offlineGroups,
        countries = extractOrderedCountries(offlineGroups),
        isFromCache = true,
        loadError = if (offlineGroups.isEmpty()) {
          "تعذر الاتصال بمستودع Kiwifu/adhan-mp3. تحقق من الشبكة واضغط تحديث."
        } else {
          "تعذر الاتصال بالإنترنت، تم عرض الملفات المحمّلة محلياً فقط."
        },
      )
    }

  suspend fun loadRegistry(forceRefresh: Boolean = false): AdhanSoundRegistrySnapshot {
    val catalog = loadMuezzinGroups(forceRefresh = forceRefresh)
    val flatEntries = catalog.groups.flatMap { grp ->
      grp.recordings.map { rec ->
        AdhanSoundRegistryEntry(
          id = rec.id,
          nameAr = rec.arabicTitle,
          muezzinAr = grp.arabicName,
          sourceType = "github_kiwifu",
          license = "Free for Islamic apps & personal use",
          attribution = KiwifuAdhanParser.ATTRIBUTION_TEXT,
          audioUrl = rec.primaryUrl,
        )
      }
    }
    return AdhanSoundRegistrySnapshot(
      version = "3.0.0",
      license = "Free for Islamic apps and personal use",
      attribution = KiwifuAdhanParser.ATTRIBUTION_TEXT,
      sounds = flatEntries,
      isFromCache = catalog.isFromCache,
      loadError = catalog.loadError,
    )
  }

  private fun extractOrderedCountries(groups: List<MuezzinGroup>): List<String> {
    val all = groups.map { it.countryAr }.filter { it.isNotBlank() }.distinct()
    val preferred = listOf("مصر", "السعودية")
    val first = preferred.filter { it in all }
    val rest = all.filterNot { it in preferred || it == "أخرى" }.sorted()
    val other = if ("أخرى" in all) listOf("أخرى") else emptyList()
    return first + rest + other
  }

  private fun isCacheStale(file: File): Boolean {
    if (!file.exists()) return true
    val ageMs = System.currentTimeMillis() - file.lastModified()
    return ageMs > 7L * 24L * 60L * 60L * 1000L
  }

  private fun fetchKiwifuFileListing(): List<KiwifuRemoteFile> {
    // 1) Try jsDelivr package listing first
    val jsDelivrFiles = runCatching {
      val req = Request.Builder()
        .url(KiwifuAdhanParser.JSDELIVR_LISTING_URL)
        .header("Accept", "application/json")
        .build()
      QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@runCatching emptyList()
        val body = resp.body?.string().orEmpty()
        if (body.isBlank()) emptyList() else KiwifuAdhanParser.parseJsDelivrListingJson(body)
      }
    }.getOrElse { emptyList() }
    if (jsDelivrFiles.isNotEmpty()) return jsDelivrFiles

    // 2) Fall back to GitHub contents API
    val githubFiles = runCatching {
      val req = Request.Builder()
        .url(KiwifuAdhanParser.GITHUB_CONTENTS_URL)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "Qabasat-Android")
        .build()
      QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
        if (!resp.isSuccessful) return@runCatching emptyList()
        val body = resp.body?.string().orEmpty()
        if (body.isBlank()) emptyList() else KiwifuAdhanParser.parseGitHubContentsJson(body)
      }
    }.getOrElse { emptyList() }

    return githubFiles
  }

  private fun readCachedListing(): List<KiwifuRemoteFile> {
    val file = listingCacheFile()
    if (!file.exists()) return emptyList()
    return runCatching {
      val root = JSONObject(file.readText())
      val arr = root.optJSONArray("files") ?: return emptyList()
      buildList {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val name = obj.optString("name", "").trim()
          if (name.endsWith(".mp3", ignoreCase = true)) {
            add(
              KiwifuRemoteFile(
                fileName = name,
                sizeBytes = obj.optLong("size", 0L).coerceAtLeast(0L),
              ),
            )
          }
        }
      }
    }.getOrElse { emptyList() }
  }

  private fun writeCachedListing(files: List<KiwifuRemoteFile>) {
    runCatching {
      val arr = JSONArray()
      for (item in files) {
        arr.put(
          JSONObject().apply {
            put("name", item.fileName)
            put("size", item.sizeBytes)
          },
        )
      }
      val root = JSONObject().apply {
        put("source", "Kiwifu/adhan-mp3@main")
        put("cached_at", System.currentTimeMillis())
        put("files", arr)
      }
      listingCacheFile().writeText(root.toString())
    }
  }

  private fun recordDownloadedMeta(recording: AdhanRecordingItem) {
    runCatching {
      val metaFile = downloadedMetaFile()
      val root = if (metaFile.exists()) {
        runCatching { JSONObject(metaFile.readText()) }.getOrElse { JSONObject() }
      } else {
        JSONObject()
      }
      root.put(
        recording.id,
        JSONObject().apply {
          put("name", recording.fileName)
          put("size", recording.sizeBytes)
        },
      )
      metaFile.writeText(root.toString())
    }
  }

  private fun readDownloadedOfflineFiles(): List<KiwifuRemoteFile> {
    val dir = muezzinsDir()
    val metaObj = downloadedMetaFile().takeIf { it.exists() }?.let {
      runCatching { JSONObject(it.readText()) }.getOrNull()
    }
    return dir.listFiles()
      ?.filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) && it.length() > 4096L }
      ?.mapNotNull { file ->
        val id = file.nameWithoutExtension
        val meta = metaObj?.optJSONObject(id)
        val realName = meta?.optString("name", "")?.takeIf { it.isNotBlank() }
        if (realName != null) {
          KiwifuRemoteFile(
            fileName = realName,
            sizeBytes = meta.optLong("size", file.length()).coerceAtLeast(file.length()),
          )
        } else {
          null
        }
      }
      .orEmpty()
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

  fun getLocalMuezzinAudioFile(muezzinId: String): File {
    val safeId = muezzinId.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(80)
    return File(muezzinsDir(), "$safeId.mp3")
  }

  fun isMuezzinDownloaded(muezzinId: String): Boolean {
    val file = getLocalMuezzinAudioFile(muezzinId)
    return file.exists() && file.length() > 4096L
  }

  suspend fun downloadRecording(
    recording: AdhanRecordingItem,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
  ): Result<File> = withContext(Dispatchers.IO) {
    runCatching {
      val dest = getLocalMuezzinAudioFile(recording.id)
      if (dest.exists() && dest.length() > 4096L &&
        (recording.sizeBytes <= 0L || dest.length() >= recording.sizeBytes)
      ) {
        recordDownloadedMeta(recording)
        onProgress(dest.length(), dest.length())
        return@runCatching dest
      }

      val partFile = File(dest.parentFile, "${dest.name}.part")
      var lastError: Throwable? = null
      val urls = listOf(recording.primaryUrl, recording.fallbackUrl).distinct()

      for (url in urls) {
        val attempt = runCatching {
          executeResumableDownload(
            url = url,
            partFile = partFile,
            expectedTotal = recording.sizeBytes,
            onProgress = onProgress,
          )
        }
        if (attempt.isSuccess) {
          if (partFile.length() < 4096L) {
            partFile.delete()
            error("حجم ملف الصوت غير مكتمل")
          }
          if (dest.exists()) dest.delete()
          check(partFile.renameTo(dest)) { "تعذر حفظ ملف الأذان" }
          recordDownloadedMeta(recording)
          return@runCatching dest
        } else {
          lastError = attempt.exceptionOrNull()
        }
      }
      throw lastError ?: IllegalStateException("تعذر تنزيل ملف الأذان")
    }
  }

  private fun executeResumableDownload(
    url: String,
    partFile: File,
    expectedTotal: Long,
    onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
  ) {
    val existingBytes = if (partFile.exists()) partFile.length() else 0L
    val shouldResume = existingBytes > 0L && (expectedTotal <= 0L || existingBytes < expectedTotal)

    val reqBuilder = Request.Builder()
      .url(url)
      .header("User-Agent", "Qabasat-Android")
    if (shouldResume) {
      reqBuilder.header("Range", "bytes=$existingBytes-")
    }

    QuranApiClient.okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
      if (!resp.isSuccessful && resp.code != 206) {
        error("فشل التنزيل (${resp.code})")
      }
      val contentType = resp.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
      if (contentType.contains("text/html") || contentType.contains("application/json")) {
        error("الرابط لا يحتوي على ملف صوتي صالح")
      }
      val body = resp.body ?: error("بيانات الصوت فارغة")
      val isAppend = resp.code == 206 && shouldResume
      val baseOffset = if (isAppend) existingBytes else 0L
      val totalBytes = when {
        expectedTotal > 0L -> expectedTotal
        body.contentLength() > 0L -> baseOffset + body.contentLength()
        else -> 0L
      }

      onProgress(baseOffset, totalBytes)
      body.byteStream().use { input ->
        FileOutputStream(partFile, isAppend).use { output ->
          val buffer = ByteArray(16 * 1024)
          var written = baseOffset
          var read: Int
          while (input.read(buffer).also { read = it } != -1) {
            output.write(buffer, 0, read)
            written += read
            onProgress(written, totalBytes)
          }
          output.flush()
        }
      }
    }
  }

  suspend fun downloadMuezzinAudio(entry: AdhanSoundRegistryEntry): Result<File> {
    val url = entry.audioUrl ?: return Result.failure(IllegalStateException("لا يوجد رابط صوتي"))
    return downloadRecording(
      AdhanRecordingItem(
        id = entry.id,
        fileName = "${entry.id}.mp3",
        arabicTitle = entry.nameAr,
        recordingNumber = null,
        isFajr = entry.nameAr.contains("فجر"),
        isHq = false,
        countryAr = null,
        placeDetailAr = null,
        sizeBytes = 0L,
        primaryUrl = url,
        fallbackUrl = url,
      ),
    )
  }

  fun deleteDownloadedMuezzin(muezzinId: String): Boolean {
    val file = getLocalMuezzinAudioFile(muezzinId)
    val part = File(file.parentFile, "${file.name}.part")
    if (part.exists()) part.delete()
    return if (file.exists()) file.delete() else true
  }

  fun resolvePlayableUri(
    config: PrayerAlarmConfig,
    prayerType: PrayerType? = null,
  ): Uri? {
    if (config.mode == PrayerAlarmMode.SILENT) return null
    if (config.mode == PrayerAlarmMode.MUEZZIN) {
      // 1) If Fajr and we have cached catalog + muezzinGroupId, check if a downloaded Fajr recording exists
      if (prayerType == PrayerType.FAJR && !config.muezzinGroupId.isNullOrBlank()) {
        val cachedFiles = readCachedListing()
        if (cachedFiles.isNotEmpty()) {
          val grp = KiwifuAdhanParser.parseListingToGroups(cachedFiles)
            .firstOrNull { it.id == config.muezzinGroupId }
          val fajrRec = grp?.recordings?.firstOrNull { it.isFajr }
          if (fajrRec != null) {
            val fajrFile = getLocalMuezzinAudioFile(fajrRec.id)
            if (fajrFile.exists() && fajrFile.length() > 4096L) {
              return Uri.fromFile(fajrFile)
            }
          }
        }
      }
      // 2) Configured recording local file
      if (!config.muezzinId.isNullOrBlank()) {
        val localFile = getLocalMuezzinAudioFile(config.muezzinId)
        if (localFile.exists() && localFile.length() > 4096L) {
          return Uri.fromFile(localFile)
        }
      }
      // 3) Remote fallback URL only if local file doesn't exist
      if (!config.customUri.isNullOrBlank() &&
        (config.customUri.startsWith("http://") || config.customUri.startsWith("https://"))
      ) {
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

  suspend fun loadKiwifuMuezzinNamesForCredits(): List<String> = withContext(Dispatchers.IO) {
    val cached = readCachedListing()
    val files = if (cached.isNotEmpty()) cached else readDownloadedOfflineFiles()
    if (files.isEmpty()) return@withContext emptyList()
    KiwifuAdhanParser.parseListingToGroups(files)
      .map { grp ->
        if (grp.countryAr.isNotBlank() && grp.countryAr != "أخرى") {
          "${grp.arabicName} (${grp.countryAr})"
        } else {
          grp.arabicName
        }
      }
  }

  suspend fun loadAdhanCredits(): List<CreditSourceInfo> {
    val muezzinNames = loadKiwifuMuezzinNamesForCredits()
    val muezzinSummary = if (muezzinNames.isNotEmpty()) {
      "أصوات المؤذنين المتاحة (${PrayerFormatters.toArabicIndicDigits(muezzinNames.size)}): " +
        muezzinNames.joinToString(" • ")
    } else {
      "تُجلب أسماء المؤذنين والتسجيلات مباشرة من المستودع وتُحفظ محلياً."
    }
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
          title = "مكتبة أصوات المؤذنين",
          organization = KiwifuAdhanParser.ATTRIBUTION_TEXT,
          url = "https://github.com/Kiwifu/adhan-mp3",
          license = "Free for Islamic apps and personal use",
          usage = muezzinSummary,
        ),
      )
    }
  }
}
