package com.example.data.prayer

import android.icu.util.IslamicCalendar
import androidx.compose.runtime.Immutable
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

enum class PrayerType(
  val arabicName: String,
  val key: String,
) {
  FAJR("الفجر", "fajr"),
  SUNRISE("الشروق", "sunrise"),
  DHUHR("الظهر", "dhuhr"),
  ASR("العصر", "asr"),
  MAGHRIB("المغرب", "maghrib"),
  ISHA("العشاء", "isha"),
}

enum class PrayerCalculationMethod(
  val arabicLabel: String,
  val subtitleAr: String,
) {
  EGYPTIAN(
    arabicLabel = "الهيئة المصرية العامة للمساحة",
    subtitleAr = "مصر وشمال أفريقيا (١٩.٥° / ١٧.٥°)",
  ),
  MUSLIM_WORLD_LEAGUE(
    arabicLabel = "رابطة العالم الإسلامي",
    subtitleAr = "الافتراضي العالمي (١٨° / ١٧°)",
  ),
  UMM_AL_QURA(
    arabicLabel = "جامعة أم القرى",
    subtitleAr = "مكة المكرمة والمملكة العربية السعودية",
  ),
  NORTH_AMERICA(
    arabicLabel = "الجمعية الإسلامية بأمريكا الشمالية (ISNA)",
    subtitleAr = "أمريكا الشمالية (١٥° / ١٥°)",
  ),
  KARACHI(
    arabicLabel = "جامعة العلوم الإسلامية بكراتشي",
    subtitleAr = "باكستان وجنوب آسيا (١٨° / ١٨°)",
  ),
  DUBAI(
    arabicLabel = "دائرة الشؤون الإسلامية بدبي",
    subtitleAr = "الإمارات العربية المتحدة",
  ),
  QATAR(
    arabicLabel = "تقويم قطر",
    subtitleAr = "دولة قطر",
  ),
  KUWAIT(
    arabicLabel = "وزارة الأوقاف الكويتية",
    subtitleAr = "دولة الكويت",
  ),
  SINGAPORE(
    arabicLabel = "مجلس أوغاما إسلام سنغافورة",
    subtitleAr = "سنغافورة وجنوب شرق آسيا",
  ),
  TURKEY(
    arabicLabel = "رئاسة الشؤون الدينية التركية (ديانت)",
    subtitleAr = "تركيا",
  ),
  MOON_SIGHTING_COMMITTEE(
    arabicLabel = "لجنة تحري الهلال",
    subtitleAr = "مراعاة خطوط العرض والمواسم",
  ),
}

enum class AsrMadhab(val arabicLabel: String) {
  SHAFI("الجمهور (الشافعي / المالكي / الحنبلي)"),
  HANAFI("الحنفي"),
}

enum class PrayerHighLatitudeRule(val arabicLabel: String) {
  MIDDLE_OF_THE_NIGHT("منتصف الليل"),
  SEVENTH_OF_THE_NIGHT("سُبع الليل"),
  TWILIGHT_ANGLE("زاوية الشفق"),
}

@Immutable
data class PrayerMinuteAdjustments(
  val fajr: Int = 0,
  val sunrise: Int = 0,
  val dhuhr: Int = 0,
  val asr: Int = 0,
  val maghrib: Int = 0,
  val isha: Int = 0,
) {
  fun forPrayer(type: PrayerType): Int = when (type) {
    PrayerType.FAJR -> fajr
    PrayerType.SUNRISE -> sunrise
    PrayerType.DHUHR -> dhuhr
    PrayerType.ASR -> asr
    PrayerType.MAGHRIB -> maghrib
    PrayerType.ISHA -> isha
  }

  fun withDelta(type: PrayerType, deltaMinutes: Int): PrayerMinuteAdjustments {
    fun clamp(v: Int) = v.coerceIn(-60, 60)
    return when (type) {
      PrayerType.FAJR -> copy(fajr = clamp(fajr + deltaMinutes))
      PrayerType.SUNRISE -> copy(sunrise = clamp(sunrise + deltaMinutes))
      PrayerType.DHUHR -> copy(dhuhr = clamp(dhuhr + deltaMinutes))
      PrayerType.ASR -> copy(asr = clamp(asr + deltaMinutes))
      PrayerType.MAGHRIB -> copy(maghrib = clamp(maghrib + deltaMinutes))
      PrayerType.ISHA -> copy(isha = clamp(isha + deltaMinutes))
    }
  }

  fun cacheSignature(): String = "$fajr,$sunrise,$dhuhr,$asr,$maghrib,$isha"
}

@Immutable
data class SavedPrayerLocation(
  val latitude: Double,
  val longitude: Double,
  val displayName: String,
  val countryCode: String?,
  val timeZoneId: String,
  val isManual: Boolean,
) {
  fun resolvedLabel(): String {
    if (displayName.isNotBlank()) return displayName
    return PrayerFormatters.formatCoordinatesArabic(latitude, longitude)
  }

  /**
   * Returns a human-readable city/region label ONLY when known, and strictly null
   * if empty, placeholder, or formatted GPS coordinates.
   */
  fun cityOnlyOrNull(): String? {
    val trimmed = displayName.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed == "موقعك الحالي" || trimmed == "لم يتم تحديد الموقع") return null
    if (trimmed.contains('°')) return null
    return trimmed
  }
}

@Immutable
data class PrayerSettingsState(
  val location: SavedPrayerLocation? = null,
  val calculationMethod: PrayerCalculationMethod = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE,
  val isMethodUserSelected: Boolean = false,
  val asrMadhab: AsrMadhab = AsrMadhab.SHAFI,
  val highLatitudeRule: PrayerHighLatitudeRule = PrayerHighLatitudeRule.MIDDLE_OF_THE_NIGHT,
  val adjustments: PrayerMinuteAdjustments = PrayerMinuteAdjustments(),
) {
  fun cacheKey(): String {
    val loc = location ?: return "no_location"
    val latStr = String.format(Locale.US, "%.4f", loc.latitude)
    val lonStr = String.format(Locale.US, "%.4f", loc.longitude)
    return listOf(
      latStr,
      lonStr,
      loc.timeZoneId,
      calculationMethod.name,
      asrMadhab.name,
      highLatitudeRule.name,
      adjustments.cacheSignature(),
    ).joinToString("|")
  }
}

@Immutable
data class DailyPrayerTimesRecord(
  val dateKey: String,
  val year: Int,
  val month: Int,
  val day: Int,
  val fajrEpochMs: Long,
  val sunriseEpochMs: Long,
  val dhuhrEpochMs: Long,
  val asrEpochMs: Long,
  val maghribEpochMs: Long,
  val ishaEpochMs: Long,
) {
  fun epochFor(type: PrayerType): Long = when (type) {
    PrayerType.FAJR -> fajrEpochMs
    PrayerType.SUNRISE -> sunriseEpochMs
    PrayerType.DHUHR -> dhuhrEpochMs
    PrayerType.ASR -> asrEpochMs
    PrayerType.MAGHRIB -> maghribEpochMs
    PrayerType.ISHA -> ishaEpochMs
  }
}

@Immutable
data class CachedPrayerSchedule(
  val cacheKey: String,
  val generatedDateKey: String,
  val daysByDateKey: Map<String, DailyPrayerTimesRecord>,
)

@Immutable
data class NextPrayerTarget(
  val prayerType: PrayerType,
  val targetEpochMs: Long,
  val isTomorrow: Boolean,
)

@Immutable
data class OfflineCityOption(
  val id: String,
  val arabicName: String,
  val countryArabic: String,
  val countryCode: String,
  val latitude: Double,
  val longitude: Double,
  val timeZoneId: String,
) {
  fun toSavedLocation(): SavedPrayerLocation = SavedPrayerLocation(
    latitude = latitude,
    longitude = longitude,
    displayName = "$arabicName، $countryArabic",
    countryCode = countryCode,
    timeZoneId = timeZoneId,
    isManual = true,
  )
}

object OfflineCityCatalog {
  val cities: List<OfflineCityOption> = listOf(
    // Egypt
    OfflineCityOption("cairo", "القاهرة", "مصر", "EG", 30.0444, 31.2357, "Africa/Cairo"),
    OfflineCityOption("alexandria", "الإسكندرية", "مصر", "EG", 31.2001, 29.9187, "Africa/Cairo"),
    OfflineCityOption("giza", "الجيزة", "مصر", "EG", 30.0131, 31.2089, "Africa/Cairo"),
    OfflineCityOption("mansoura", "المنصورة", "مصر", "EG", 31.0409, 31.3785, "Africa/Cairo"),
    OfflineCityOption("tanta", "طنطا", "مصر", "EG", 30.7865, 31.0004, "Africa/Cairo"),
    OfflineCityOption("asyut", "أسيوط", "مصر", "EG", 27.1809, 31.1837, "Africa/Cairo"),
    OfflineCityOption("aswan", "أسوان", "مصر", "EG", 24.0889, 32.8998, "Africa/Cairo"),
    // Saudi Arabia
    OfflineCityOption("makkah", "مكة المكرمة", "السعودية", "SA", 21.4225, 39.8262, "Asia/Riyadh"),
    OfflineCityOption("madinah", "المدينة المنورة", "السعودية", "SA", 24.4672, 39.6112, "Asia/Riyadh"),
    OfflineCityOption("riyadh", "الرياض", "السعودية", "SA", 24.7136, 46.6753, "Asia/Riyadh"),
    OfflineCityOption("jeddah", "جدة", "السعودية", "SA", 21.4858, 39.1925, "Asia/Riyadh"),
    OfflineCityOption("dammam", "الدمام", "السعودية", "SA", 26.4207, 50.0888, "Asia/Riyadh"),
    // Gulf & Levant & Arab World
    OfflineCityOption("dubai", "دبي", "الإمارات", "AE", 25.2048, 55.2708, "Asia/Dubai"),
    OfflineCityOption("abudhabi", "أبوظبي", "الإمارات", "AE", 24.4539, 54.3773, "Asia/Dubai"),
    OfflineCityOption("doha", "الدوحة", "قطر", "QA", 25.2854, 51.5310, "Asia/Qatar"),
    OfflineCityOption("kuwait", "مدينة الكويت", "الكويت", "KW", 29.3759, 47.9774, "Asia/Kuwait"),
    OfflineCityOption("manama", "المنامة", "البحرين", "BH", 26.2285, 50.5860, "Asia/Bahrain"),
    OfflineCityOption("muscat", "مسقط", "عُمان", "OM", 23.5880, 58.3829, "Asia/Muscat"),
    OfflineCityOption("amman", "عمّان", "الأردن", "JO", 31.9454, 35.9284, "Asia/Amman"),
    OfflineCityOption("jerusalem", "القدس", "فلسطين", "PS", 31.7683, 35.2137, "Asia/Jerusalem"),
    OfflineCityOption("damascus", "دمشق", "سوريا", "SY", 33.5138, 36.2765, "Asia/Damascus"),
    OfflineCityOption("beirut", "بيروت", "لبنان", "LB", 33.8938, 35.5018, "Asia/Beirut"),
    OfflineCityOption("baghdad", "بغداد", "العراق", "IQ", 33.3152, 44.3661, "Asia/Baghdad"),
    OfflineCityOption("sanaa", "صنعاء", "اليمن", "YE", 15.3694, 44.1910, "Asia/Aden"),
    OfflineCityOption("khartoum", "الخرطوم", "السودان", "SD", 15.5007, 32.5599, "Africa/Khartoum"),
    OfflineCityOption("tunis", "تونس", "تونس", "TN", 36.8065, 10.1815, "Africa/Tunis"),
    OfflineCityOption("algiers", "الجزائر", "الجزائر", "DZ", 36.7538, 3.0588, "Africa/Algiers"),
    OfflineCityOption("casablanca", "الدار البيضاء", "المغرب", "MA", 33.5731, -7.5898, "Africa/Casablanca"),
    OfflineCityOption("rabat", "الرباط", "المغرب", "MA", 34.0209, -6.8416, "Africa/Casablanca"),
    // International
    OfflineCityOption("istanbul", "إسطنبول", "تركيا", "TR", 41.0082, 28.9784, "Europe/Istanbul"),
    OfflineCityOption("kualalumpur", "كوالالمبور", "ماليزيا", "MY", 3.1390, 101.6869, "Asia/Kuala_Lumpur"),
    OfflineCityOption("jakarta", "جاكرتا", "إندونيسيا", "ID", -6.2088, 106.8456, "Asia/Jakarta"),
    OfflineCityOption("karachi", "كراتشي", "باكستان", "PK", 24.8607, 67.0011, "Asia/Karachi"),
    OfflineCityOption("london", "لندن", "المملكة المتحدة", "GB", 51.5072, -0.1276, "Europe/London"),
    OfflineCityOption("paris", "باريس", "فرنسا", "FR", 48.8566, 2.3522, "Europe/Paris"),
    OfflineCityOption("berlin", "برلين", "ألمانيا", "DE", 52.5200, 13.4050, "Europe/Berlin"),
    OfflineCityOption("newyork", "نيويورك", "الولايات المتحدة", "US", 40.7128, -74.0060, "America/New_York"),
    OfflineCityOption("toronto", "تورونتو", "كندا", "CA", 43.6532, -79.3832, "America/Toronto"),
  )
}

object PrayerFormatters {
  private val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

  private val arabicWeekdays = arrayOf(
    "الأحد",
    "الإثنين",
    "الثلاثاء",
    "الأربعاء",
    "الخميس",
    "الجمعة",
    "السبت",
  )

  private val gregorianMonths = arrayOf(
    "يناير",
    "فبراير",
    "مارس",
    "أبريل",
    "مايو",
    "يونيو",
    "يوليو",
    "أغسطس",
    "سبتمبر",
    "أكتوبر",
    "نوفمبر",
    "ديسمبر",
  )

  private val hijriMonths = arrayOf(
    "محرم",
    "صفر",
    "ربيع الأول",
    "ربيع الآخر",
    "جمادى الأولى",
    "جمادى الآخرة",
    "رجب",
    "شعبان",
    "رمضان",
    "شوال",
    "ذو القعدة",
    "ذو الحجة",
  )

  fun toArabicIndicDigits(number: Int): String = toArabicIndicDigits(number.toString())

  fun toArabicIndicDigits(input: String): String = buildString(input.length) {
    for (ch in input) {
      append(if (ch in '0'..'9') arabicDigits[ch - '0'] else ch)
    }
  }

  fun formatSignedMinutesArabic(minutes: Int): String {
    if (minutes == 0) return toArabicIndicDigits(0)
    val sign = if (minutes > 0) "+" else "-"
    return "$sign${toArabicIndicDigits(abs(minutes))}"
  }

  fun formatCoordinatesArabic(lat: Double, lon: Double): String {
    val latDir = if (lat >= 0.0) "ش" else "ج"
    val lonDir = if (lon >= 0.0) "ق" else "غ"
    val latVal = toArabicIndicDigits(String.format(Locale.US, "%.2f", abs(lat)))
    val lonVal = toArabicIndicDigits(String.format(Locale.US, "%.2f", abs(lon)))
    return "$latVal° $latDir، $lonVal° $lonDir"
  }

  fun formatDateKey(year: Int, month: Int, day: Int): String =
    String.format(Locale.US, "%04d-%02d-%02d", year, month, day)

  fun calendarDateKey(cal: Calendar): String = formatDateKey(
    cal.get(Calendar.YEAR),
    cal.get(Calendar.MONTH) + 1,
    cal.get(Calendar.DAY_OF_MONTH),
  )

  fun formatTime12hArabic(epochMs: Long, timeZoneId: String): String {
    val tz = TimeZone.getTimeZone(timeZoneId)
    val cal = Calendar.getInstance(tz).apply { timeInMillis = epochMs }
    val hour24 = cal.get(Calendar.HOUR_OF_DAY)
    val minute = cal.get(Calendar.MINUTE)
    val hour12 = cal.get(Calendar.HOUR).let { if (it == 0) 12 else it }
    val period = if (hour24 < 12) "ص" else "م"
    val raw = "$hour12:${minute.toString().padStart(2, '0')} $period"
    return toArabicIndicDigits(raw)
  }

  fun formatCountdownHmsArabic(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L)) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val raw = "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    return toArabicIndicDigits(raw)
  }

  fun formatGregorianDateArabic(year: Int, month: Int, day: Int, timeZoneId: String): String {
    val tz = TimeZone.getTimeZone(timeZoneId)
    val cal = Calendar.getInstance(tz).apply {
      set(Calendar.YEAR, year)
      set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
      set(Calendar.DAY_OF_MONTH, day)
      set(Calendar.HOUR_OF_DAY, 12)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val dowIdx = (cal.get(Calendar.DAY_OF_WEEK) - Calendar.SUNDAY).coerceIn(0, 6)
    val monthIdx = (month - 1).coerceIn(0, 11)
    return "${arabicWeekdays[dowIdx]}، ${toArabicIndicDigits(day)} ${gregorianMonths[monthIdx]} ${toArabicIndicDigits(year)}"
  }

  fun formatHijriDateArabic(year: Int, month: Int, day: Int, timeZoneId: String): String {
    val tz = TimeZone.getTimeZone(timeZoneId)
    val gregCal = Calendar.getInstance(tz).apply {
      set(Calendar.YEAR, year)
      set(Calendar.MONTH, (month - 1).coerceIn(0, 11))
      set(Calendar.DAY_OF_MONTH, day)
      set(Calendar.HOUR_OF_DAY, 12)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val islamic = IslamicCalendar(
      android.icu.util.TimeZone.getTimeZone(tz.id),
      Locale.forLanguageTag("ar-SA"),
    ).apply {
      calculationType = IslamicCalendar.CalculationType.ISLAMIC_UMALQURA
      timeInMillis = gregCal.timeInMillis
    }
    val hDay = islamic.get(android.icu.util.Calendar.DAY_OF_MONTH)
    val hMonthIdx = islamic.get(android.icu.util.Calendar.MONTH).coerceIn(0, 11)
    val hYear = islamic.get(android.icu.util.Calendar.YEAR)
    return "${toArabicIndicDigits(hDay)} ${hijriMonths[hMonthIdx]} ${toArabicIndicDigits(hYear)} هـ"
  }
}
