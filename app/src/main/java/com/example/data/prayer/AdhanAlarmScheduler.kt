package com.example.data.prayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.MainActivity
import com.example.data.prefs.ReaderPreferences
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object AdhanAlarmScheduler {

  private const val WORK_NAME_DAILY_RESCHEDULE = "qabasat_daily_adhan_reschedule"
  private const val COUNTDOWN_REFRESH_REQUEST_CODE = 7801
  private const val ALARM_REQUEST_BASE = 6000
  private const val MAX_DAY_SLOTS = 3
  private const val FORTY_EIGHT_HOURS_MS = 48L * 60L * 60L * 1000L

  fun canScheduleExactAlarmsCompat(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    val am = context.getSystemService(AlarmManager::class.java) ?: return false
    return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
  }

  /**
   * Pure planning function computing all enabled prayer alarms in `(nowMs..nowMs+windowMs]`.
   */
  fun collectAlarmsForWindow(
    nowMs: Long,
    windowMs: Long = FORTY_EIGHT_HOURS_MS,
    dailyRecords: List<DailyPrayerTimesRecord>,
    adhanSettings: AdhanSettingsState,
  ): List<ScheduledPrayerAlarm> {
    if (!adhanSettings.alarmsEnabled || dailyRecords.isEmpty()) return emptyList()
    val horizonEnd = nowMs + windowMs
    val out = ArrayList<ScheduledPrayerAlarm>(dailyRecords.size * PrayerType.entries.size)
    dailyRecords.take(MAX_DAY_SLOTS).forEachIndexed { dayIndex, record ->
      for (prayerType in PrayerType.entries) {
        val cfg = adhanSettings.configFor(prayerType)
        if (cfg.mode == PrayerAlarmMode.SILENT) continue
        val triggerMs = record.epochFor(prayerType)
        if (triggerMs > nowMs && triggerMs <= horizonEnd) {
          out.add(
            ScheduledPrayerAlarm(
              requestCode = requestCodeFor(dayIndex, prayerType),
              dateKey = record.dateKey,
              prayerType = prayerType,
              triggerEpochMs = triggerMs,
              config = cfg,
            ),
          )
        }
      }
    }
    return out.sortedBy { it.triggerEpochMs }
  }

  fun requestCodeFor(daySlot: Int, prayerType: PrayerType): Int =
    ALARM_REQUEST_BASE + (daySlot * 16) + prayerType.ordinal

  fun syncFromPreferences(
    context: Context,
    persisted: ReaderPreferences.PersistedPrayerPreferences,
    repository: PrayerTimesRepository = PrayerTimesRepository(),
  ) {
    runCatching {
      val appCtx = context.applicationContext
      AdhanNotificationHelper.ensureChannels(appCtx)

      val settings = persisted.settings
      val adhanSettings = persisted.adhanSettings
      val location = settings.location

      // Cancel all previously slotted alarm PendingIntents first
      cancelAllScheduledPrayerAlarms(appCtx)

      if (location != null) {
        val nowMs = System.currentTimeMillis()
        val tz = TimeZone.getTimeZone(location.timeZoneId)
        val validCache = persisted.cachedSchedule?.takeIf { it.cacheKey == settings.cacheKey() }
        val records = (0 until MAX_DAY_SLOTS).map { dayOffset ->
          val cal = Calendar.getInstance(tz).apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, dayOffset)
          }
          val key = PrayerFormatters.calendarDateKey(cal)
          validCache?.daysByDateKey?.get(key) ?: repository.computeDay(
            location = location,
            method = settings.calculationMethod,
            madhab = settings.asrMadhab,
            highLatitudeRule = settings.highLatitudeRule,
            adjustments = settings.adjustments,
            year = cal.get(Calendar.YEAR),
            month = cal.get(Calendar.MONTH) + 1,
            day = cal.get(Calendar.DAY_OF_MONTH),
          )
        }

        val alarms = collectAlarmsForWindow(
          nowMs = nowMs,
          windowMs = FORTY_EIGHT_HOURS_MS,
          dailyRecords = records,
          adhanSettings = adhanSettings,
        )
        for (alarm in alarms) {
          scheduleSinglePrayerAlarm(appCtx, alarm, location)
        }

        // Schedule next boundary refresh for the persistent countdown notification
        val nextTarget = repository.resolveNextPrayer(
          todayRecord = records.getOrNull(0),
          tomorrowRecord = records.getOrNull(1),
          nowEpochMs = nowMs,
        )
        scheduleCountdownRefreshAlarm(
          context = appCtx,
          triggerMs = nextTarget?.targetEpochMs?.plus(1_000L),
          enabled = adhanSettings.persistentCountdownEnabled,
        )
      } else {
        scheduleCountdownRefreshAlarm(appCtx, triggerMs = null, enabled = false)
      }

      AdhanNotificationHelper.refreshPersistentCountdownNotification(
        context = appCtx,
        settings = settings,
        adhanSettings = adhanSettings,
        cachedSchedule = persisted.cachedSchedule,
        repository = repository,
      )

      ensureDailyWorkerScheduled(appCtx)
    }
  }

  fun cancelAllScheduledPrayerAlarms(context: Context) {
    val am = context.getSystemService(AlarmManager::class.java) ?: return
    for (slot in 0 until MAX_DAY_SLOTS) {
      for (prayerType in PrayerType.entries) {
        val rc = requestCodeFor(slot, prayerType)
        val intent = Intent(context, AdhanAlarmReceiver::class.java).apply {
          action = AdhanAlarmReceiver.ACTION_PRAYER_ALARM
        }
        val existing = PendingIntent.getBroadcast(
          context,
          rc,
          intent,
          PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        )
        if (existing != null) {
          am.cancel(existing)
          existing.cancel()
        }
      }
    }
  }

  private fun scheduleSinglePrayerAlarm(
    context: Context,
    alarm: ScheduledPrayerAlarm,
    location: SavedPrayerLocation,
  ) {
    val am = context.getSystemService(AlarmManager::class.java) ?: return
    val timeText = PrayerFormatters.formatTime12hArabic(alarm.triggerEpochMs, location.timeZoneId)
    val broadcastIntent = Intent(context, AdhanAlarmReceiver::class.java).apply {
      action = AdhanAlarmReceiver.ACTION_PRAYER_ALARM
      putExtra(AdhanNotificationHelper.EXTRA_PRAYER_KEY, alarm.prayerType.key)
      putExtra(AdhanNotificationHelper.EXTRA_DATE_KEY, alarm.dateKey)
      putExtra(AdhanNotificationHelper.EXTRA_PRAYER_TIME_TEXT, timeText)
      putExtra(AdhanNotificationHelper.EXTRA_LOCATION_NAME, location.resolvedLabel())
    }
    val pi = PendingIntent.getBroadcast(
      context,
      alarm.requestCode,
      broadcastIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val canExact = canScheduleExactAlarmsCompat(context)
    if (canExact) {
      val showIntent = PendingIntent.getActivity(
        context,
        alarm.requestCode + 500,
        Intent(context, MainActivity::class.java).apply {
          flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )
      runCatching {
        am.setAlarmClock(AlarmManager.AlarmClockInfo(alarm.triggerEpochMs, showIntent), pi)
      }.onFailure {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerEpochMs, pi)
      }
    } else {
      am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarm.triggerEpochMs, pi)
    }
  }

  private fun scheduleCountdownRefreshAlarm(
    context: Context,
    triggerMs: Long?,
    enabled: Boolean,
  ) {
    val am = context.getSystemService(AlarmManager::class.java) ?: return
    val intent = Intent(context, AdhanAlarmReceiver::class.java).apply {
      action = AdhanAlarmReceiver.ACTION_REFRESH_COUNTDOWN
    }
    if (!enabled || triggerMs == null || triggerMs <= System.currentTimeMillis()) {
      val existing = PendingIntent.getBroadcast(
        context,
        COUNTDOWN_REFRESH_REQUEST_CODE,
        intent,
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
      )
      if (existing != null) {
        am.cancel(existing)
        existing.cancel()
      }
      return
    }
    val pi = PendingIntent.getBroadcast(
      context,
      COUNTDOWN_REFRESH_REQUEST_CODE,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    if (canScheduleExactAlarmsCompat(context)) {
      runCatching {
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
      }.onFailure {
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
      }
    } else {
      am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pi)
    }
  }

  fun ensureDailyWorkerScheduled(context: Context) {
    runCatching {
      val wm = WorkManager.getInstance(context)
      val request = PeriodicWorkRequestBuilder<DailyAdhanRescheduleWorker>(
        repeatInterval = 12,
        repeatIntervalTimeUnit = TimeUnit.HOURS,
      ).build()
      wm.enqueueUniquePeriodicWork(
        WORK_NAME_DAILY_RESCHEDULE,
        ExistingPeriodicWorkPolicy.UPDATE,
        request,
      )
    }
  }
}
