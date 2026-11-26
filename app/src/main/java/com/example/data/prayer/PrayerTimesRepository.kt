package com.example.data.prayer

import com.batoulapps.adhan2.CalculationMethod
import com.batoulapps.adhan2.CalculationParameters
import com.batoulapps.adhan2.Coordinates
import com.batoulapps.adhan2.HighLatitudeRule
import com.batoulapps.adhan2.Madhab
import com.batoulapps.adhan2.PrayerAdjustments
import com.batoulapps.adhan2.PrayerTimes
import com.batoulapps.adhan2.data.DateComponents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

class PrayerTimesRepository {

  fun defaultMethodForCountry(countryCode: String?): PrayerCalculationMethod {
    return if (countryCode.equals("EG", ignoreCase = true)) {
      PrayerCalculationMethod.EGYPTIAN
    } else {
      PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE
    }
  }

  fun resolveEffectiveMethod(
    settings: PrayerSettingsState,
    countryCode: String? = settings.location?.countryCode,
  ): PrayerCalculationMethod {
    return if (settings.isMethodUserSelected) {
      settings.calculationMethod
    } else {
      defaultMethodForCountry(countryCode)
    }
  }

  @OptIn(kotlin.time.ExperimentalTime::class)
  fun computeDay(
    location: SavedPrayerLocation,
    method: PrayerCalculationMethod,
    madhab: AsrMadhab,
    highLatitudeRule: PrayerHighLatitudeRule,
    adjustments: PrayerMinuteAdjustments,
    year: Int,
    month: Int,
    day: Int,
  ): DailyPrayerTimesRecord {
    val coordinates = Coordinates(location.latitude, location.longitude)
    val params = buildParameters(method, madhab, highLatitudeRule, adjustments)
    val dateComponents = DateComponents(year, month, day)
    val prayerTimes = PrayerTimes(coordinates, dateComponents, params)

    return DailyPrayerTimesRecord(
      dateKey = PrayerFormatters.formatDateKey(year, month, day),
      year = year,
      month = month,
      day = day,
      fajrEpochMs = prayerTimes.fajr.toEpochMilliseconds(),
      sunriseEpochMs = prayerTimes.sunrise.toEpochMilliseconds(),
      dhuhrEpochMs = prayerTimes.dhuhr.toEpochMilliseconds(),
      asrEpochMs = prayerTimes.asr.toEpochMilliseconds(),
      maghribEpochMs = prayerTimes.maghrib.toEpochMilliseconds(),
      ishaEpochMs = prayerTimes.isha.toEpochMilliseconds(),
    )
  }

  suspend fun compute30DaySchedule(
    settings: PrayerSettingsState,
    nowEpochMs: Long = System.currentTimeMillis(),
  ): CachedPrayerSchedule? = withContext(Dispatchers.Default) {
    val location = settings.location ?: return@withContext null
    val tz = TimeZone.getTimeZone(location.timeZoneId)
    val cal = Calendar.getInstance(tz).apply {
      timeInMillis = nowEpochMs
      set(Calendar.HOUR_OF_DAY, 12)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val todayKey = PrayerFormatters.calendarDateKey(cal)

    // Precompute yesterday (-1) through +30 days ahead so Yesterday/Today/Tomorrow and next 30 days open instantly.
    val cursor = (cal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    val records = LinkedHashMap<String, DailyPrayerTimesRecord>(32)
    for (offset in -1..30) {
      val year = cursor.get(Calendar.YEAR)
      val month = cursor.get(Calendar.MONTH) + 1
      val day = cursor.get(Calendar.DAY_OF_MONTH)
      val rec = computeDay(
        location = location,
        method = settings.calculationMethod,
        madhab = settings.asrMadhab,
        highLatitudeRule = settings.highLatitudeRule,
        adjustments = settings.adjustments,
        year = year,
        month = month,
        day = day,
      )
      records[rec.dateKey] = rec
      cursor.add(Calendar.DAY_OF_YEAR, 1)
    }

    CachedPrayerSchedule(
      cacheKey = settings.cacheKey(),
      generatedDateKey = todayKey,
      daysByDateKey = records,
    )
  }

  fun resolveNextPrayer(
    todayRecord: DailyPrayerTimesRecord?,
    tomorrowRecord: DailyPrayerTimesRecord?,
    nowEpochMs: Long = System.currentTimeMillis(),
  ): NextPrayerTarget? {
    if (todayRecord == null) return null
    val ordered = listOf(
      PrayerType.FAJR,
      PrayerType.SUNRISE,
      PrayerType.DHUHR,
      PrayerType.ASR,
      PrayerType.MAGHRIB,
      PrayerType.ISHA,
    )
    for (type in ordered) {
      val t = todayRecord.epochFor(type)
      if (t > nowEpochMs) {
        return NextPrayerTarget(prayerType = type, targetEpochMs = t, isTomorrow = false)
      }
    }
    val tomorrowFajr = tomorrowRecord?.fajrEpochMs ?: return null
    return NextPrayerTarget(
      prayerType = PrayerType.FAJR,
      targetEpochMs = tomorrowFajr,
      isTomorrow = true,
    )
  }

  fun isDaytime(
    todayRecord: DailyPrayerTimesRecord?,
    nowEpochMs: Long = System.currentTimeMillis(),
  ): Boolean {
    if (todayRecord != null) {
      return nowEpochMs in todayRecord.sunriseEpochMs until todayRecord.maghribEpochMs
    }
    val cal = Calendar.getInstance().apply { timeInMillis = nowEpochMs }
    val hour = cal.get(Calendar.HOUR_OF_DAY)
    return hour in 6..17
  }

  private fun buildParameters(
    method: PrayerCalculationMethod,
    madhab: AsrMadhab,
    highLatitudeRule: PrayerHighLatitudeRule,
    adjustments: PrayerMinuteAdjustments,
  ): CalculationParameters {
    val adhanMethod = when (method) {
      PrayerCalculationMethod.EGYPTIAN -> CalculationMethod.EGYPTIAN
      PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE -> CalculationMethod.MUSLIM_WORLD_LEAGUE
      PrayerCalculationMethod.UMM_AL_QURA -> CalculationMethod.UMM_AL_QURA
      PrayerCalculationMethod.NORTH_AMERICA -> CalculationMethod.NORTH_AMERICA
      PrayerCalculationMethod.KARACHI -> CalculationMethod.KARACHI
      PrayerCalculationMethod.DUBAI -> CalculationMethod.DUBAI
      PrayerCalculationMethod.QATAR -> CalculationMethod.QATAR
      PrayerCalculationMethod.KUWAIT -> CalculationMethod.KUWAIT
      PrayerCalculationMethod.SINGAPORE -> CalculationMethod.SINGAPORE
      PrayerCalculationMethod.TURKEY -> CalculationMethod.TURKEY
      PrayerCalculationMethod.MOON_SIGHTING_COMMITTEE -> CalculationMethod.MOON_SIGHTING_COMMITTEE
    }
    val adhanMadhab = when (madhab) {
      AsrMadhab.SHAFI -> Madhab.SHAFI
      AsrMadhab.HANAFI -> Madhab.HANAFI
    }
    val adhanHighLat = when (highLatitudeRule) {
      PrayerHighLatitudeRule.MIDDLE_OF_THE_NIGHT -> HighLatitudeRule.MIDDLE_OF_THE_NIGHT
      PrayerHighLatitudeRule.SEVENTH_OF_THE_NIGHT -> HighLatitudeRule.SEVENTH_OF_THE_NIGHT
      PrayerHighLatitudeRule.TWILIGHT_ANGLE -> HighLatitudeRule.TWILIGHT_ANGLE
    }
    return adhanMethod.parameters.copy(
      madhab = adhanMadhab,
      highLatitudeRule = adhanHighLat,
      prayerAdjustments = PrayerAdjustments(
        fajr = adjustments.fajr,
        sunrise = adjustments.sunrise,
        dhuhr = adjustments.dhuhr,
        asr = adjustments.asr,
        maghrib = adjustments.maghrib,
        isha = adjustments.isha,
      ),
    )
  }
}
