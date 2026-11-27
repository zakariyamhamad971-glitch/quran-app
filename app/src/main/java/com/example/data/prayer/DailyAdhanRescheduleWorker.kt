package com.example.data.prayer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.prefs.ReaderPreferences
import java.util.Calendar
import java.util.TimeZone

class DailyAdhanRescheduleWorker(
  appContext: Context,
  params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

  override suspend fun doWork(): Result {
    val appCtx = applicationContext
    val prefs = ReaderPreferences(appCtx)
    val repository = PrayerTimesRepository()
    val persisted = prefs.readPrayerPreferencesOnce()

    val loc = persisted.settings.location
    val schedule = if (loc != null) {
      val tz = TimeZone.getTimeZone(loc.timeZoneId)
      val horizonCal = Calendar.getInstance(tz).apply { add(Calendar.DAY_OF_YEAR, 20) }
      val horizonKey = PrayerFormatters.calendarDateKey(horizonCal)
      val existing = persisted.cachedSchedule?.takeIf {
        it.cacheKey == persisted.settings.cacheKey() && it.daysByDateKey.containsKey(horizonKey)
      }
      existing ?: repository.compute30DaySchedule(persisted.settings)?.also {
        prefs.saveCachedPrayerSchedule(it)
      }
    } else {
      persisted.cachedSchedule
    }

    AdhanAlarmScheduler.syncFromPreferences(
      context = appCtx,
      persisted = persisted.copy(cachedSchedule = schedule),
      repository = repository,
    )
    return Result.success()
  }
}
