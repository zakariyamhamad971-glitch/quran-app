package com.example

import com.example.data.prayer.AsrMadhab
import com.example.data.prayer.OfflineCityCatalog
import com.example.data.prayer.PrayerCalculationMethod
import com.example.data.prayer.PrayerFormatters
import com.example.data.prayer.PrayerMinuteAdjustments
import com.example.data.prayer.PrayerSettingsState
import com.example.data.prayer.PrayerTimesRepository
import com.example.data.prayer.PrayerType
import com.example.data.prayer.SavedPrayerLocation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PrayerTimesUnitTest {
  private val repository = PrayerTimesRepository()

  private val cairo = OfflineCityCatalog.cities.first { it.id == "cairo" }.toSavedLocation()
  private val london = OfflineCityCatalog.cities.first { it.id == "london" }.toSavedLocation()

  @Test
  fun defaultMethod_isEgyptianForEgypt_andMwlOtherwise() {
    assertEquals(PrayerCalculationMethod.EGYPTIAN, repository.defaultMethodForCountry("EG"))
    assertEquals(PrayerCalculationMethod.EGYPTIAN, repository.defaultMethodForCountry("eg"))
    assertEquals(PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, repository.defaultMethodForCountry("SA"))
    assertEquals(PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, repository.defaultMethodForCountry("US"))
    assertEquals(PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE, repository.defaultMethodForCountry(null))
  }

  @Test
  fun computeDay_producesOrderedPrayerTimesOnDevice() {
    val day = repository.computeDay(
      location = cairo,
      method = PrayerCalculationMethod.EGYPTIAN,
      madhab = AsrMadhab.SHAFI,
      highLatitudeRule = com.example.data.prayer.PrayerHighLatitudeRule.MIDDLE_OF_THE_NIGHT,
      adjustments = PrayerMinuteAdjustments(),
      year = 2026,
      month = 11,
      day = 26,
    )

    assertTrue(day.fajrEpochMs < day.sunriseEpochMs)
    assertTrue(day.sunriseEpochMs < day.dhuhrEpochMs)
    assertTrue(day.dhuhrEpochMs < day.asrEpochMs)
    assertTrue(day.asrEpochMs < day.maghribEpochMs)
    assertTrue(day.maghribEpochMs < day.ishaEpochMs)
  }

  @Test
  fun hanafiAsr_isLaterThanShafiAsr() {
    val shafi = repository.computeDay(
      location = cairo,
      method = PrayerCalculationMethod.EGYPTIAN,
      madhab = AsrMadhab.SHAFI,
      highLatitudeRule = com.example.data.prayer.PrayerHighLatitudeRule.MIDDLE_OF_THE_NIGHT,
      adjustments = PrayerMinuteAdjustments(),
      year = 2026,
      month = 6,
      day = 15,
    )
    val hanafi = repository.computeDay(
      location = cairo,
      method = PrayerCalculationMethod.EGYPTIAN,
      madhab = AsrMadhab.HANAFI,
      highLatitudeRule = com.example.data.prayer.PrayerHighLatitudeRule.MIDDLE_OF_THE_NIGHT,
      adjustments = PrayerMinuteAdjustments(),
      year = 2026,
      month = 6,
      day = 15,
    )

    assertTrue(
      "Hanafi Asr (${hanafi.asrEpochMs}) should be later than Shafi Asr (${shafi.asrEpochMs})",
      hanafi.asrEpochMs > shafi.asrEpochMs,
    )
    assertEquals(shafi.fajrEpochMs, hanafi.fajrEpochMs)
    assertEquals(shafi.dhuhrEpochMs, hanafi.dhuhrEpochMs)
  }

  @Test
  fun manualMinuteAdjustments_shiftPrayerEpochsExactly() {
    val base = repository.computeDay(
      location = london,
      method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE,
      madhab = AsrMadhab.SHAFI,
      highLatitudeRule = com.example.data.prayer.PrayerHighLatitudeRule.MIDDLE_OF_THE_NIGHT,
      adjustments = PrayerMinuteAdjustments(),
      year = 2026,
      month = 11,
      day = 26,
    )
    val adjusted = repository.computeDay(
      location = london,
      method = PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE,
      madhab = AsrMadhab.SHAFI,
      highLatitudeRule = com.example.data.prayer.PrayerHighLatitudeRule.MIDDLE_OF_THE_NIGHT,
      adjustments = PrayerMinuteAdjustments(fajr = 5, maghrib = -3),
      year = 2026,
      month = 11,
      day = 26,
    )

    assertEquals(base.fajrEpochMs + 5 * 60_000L, adjusted.fajrEpochMs)
    assertEquals(base.maghribEpochMs - 3 * 60_000L, adjusted.maghribEpochMs)
    assertEquals(base.dhuhrEpochMs, adjusted.dhuhrEpochMs)
  }

  @Test
  fun compute30DaySchedule_cachesYesterdayThroughNext30Days() = runTest {
    val settings = PrayerSettingsState(
      location = cairo,
      calculationMethod = PrayerCalculationMethod.EGYPTIAN,
    )
    val cal = Calendar.getInstance(TimeZone.getTimeZone("Africa/Cairo")).apply {
      set(2026, Calendar.NOVEMBER, 26, 10, 0, 0)
      set(Calendar.MILLISECOND, 0)
    }
    val schedule = repository.compute30DaySchedule(settings, nowEpochMs = cal.timeInMillis)
    assertNotNull(schedule)
    requireNotNull(schedule)
    assertEquals("2026-11-26", schedule.generatedDateKey)
    assertEquals(32, schedule.daysByDateKey.size)
    assertTrue(schedule.daysByDateKey.containsKey("2026-11-25"))
    assertTrue(schedule.daysByDateKey.containsKey("2026-11-26"))
    assertTrue(schedule.daysByDateKey.containsKey("2026-11-27"))
    assertTrue(schedule.daysByDateKey.containsKey("2026-12-26"))
  }

  @Test
  fun resolveNextPrayer_rollsToTomorrowFajrAfterIsha() {
    val today = repository.computeDay(
      location = cairo,
      method = PrayerCalculationMethod.EGYPTIAN,
      madhab = AsrMadhab.SHAFI,
      highLatitudeRule = com.example.data.prayer.PrayerHighLatitudeRule.MIDDLE_OF_THE_NIGHT,
      adjustments = PrayerMinuteAdjustments(),
      year = 2026,
      month = 11,
      day = 26,
    )
    val tomorrow = repository.computeDay(
      location = cairo,
      method = PrayerCalculationMethod.EGYPTIAN,
      madhab = AsrMadhab.SHAFI,
      highLatitudeRule = com.example.data.prayer.PrayerHighLatitudeRule.MIDDLE_OF_THE_NIGHT,
      adjustments = PrayerMinuteAdjustments(),
      year = 2026,
      month = 11,
      day = 27,
    )

    val midDayTarget = repository.resolveNextPrayer(today, tomorrow, today.dhuhrEpochMs + 10_000L)
    assertNotNull(midDayTarget)
    assertEquals(PrayerType.ASR, midDayTarget!!.prayerType)
    assertEquals(false, midDayTarget.isTomorrow)

    val afterIshaTarget = repository.resolveNextPrayer(today, tomorrow, today.ishaEpochMs + 10_000L)
    assertNotNull(afterIshaTarget)
    assertEquals(PrayerType.FAJR, afterIshaTarget!!.prayerType)
    assertEquals(true, afterIshaTarget.isTomorrow)
    assertEquals(tomorrow.fajrEpochMs, afterIshaTarget.targetEpochMs)
  }

  @Test
  fun formatters_renderArabicIndic12HourAndCountdown() {
    val tz = TimeZone.getTimeZone("Africa/Cairo")
    val amCal = Calendar.getInstance(tz).apply {
      set(2026, Calendar.NOVEMBER, 26, 4, 52, 0)
      set(Calendar.MILLISECOND, 0)
    }
    assertEquals("٤:٥٢ ص", PrayerFormatters.formatTime12hArabic(amCal.timeInMillis, "Africa/Cairo"))

    val pmCal = Calendar.getInstance(tz).apply {
      set(2026, Calendar.NOVEMBER, 26, 15, 7, 0)
      set(Calendar.MILLISECOND, 0)
    }
    assertEquals("٣:٠٧ م", PrayerFormatters.formatTime12hArabic(pmCal.timeInMillis, "Africa/Cairo"))

    val noonCal = Calendar.getInstance(tz).apply {
      set(2026, Calendar.NOVEMBER, 26, 12, 0, 0)
      set(Calendar.MILLISECOND, 0)
    }
    assertEquals("١٢:٠٠ م", PrayerFormatters.formatTime12hArabic(noonCal.timeInMillis, "Africa/Cairo"))

    val countdownMs = ((2 * 3600) + (5 * 60) + 9) * 1000L
    assertEquals("٢:٠٥:٠٩", PrayerFormatters.formatCountdownHmsArabic(countdownMs))
  }
}
