package com.example.data.prayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.prefs.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AdhanAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    val pendingResult = goAsync()
    val appCtx = context.applicationContext

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val prefs = ReaderPreferences(appCtx)
        val persisted = prefs.readPrayerPreferencesOnce()

        if (action == ACTION_PRAYER_ALARM) {
          val prayerKey = intent.getStringExtra(AdhanNotificationHelper.EXTRA_PRAYER_KEY).orEmpty()
          val prayerType = PrayerType.entries.firstOrNull { it.key == prayerKey }
          val dateKey = intent.getStringExtra(AdhanNotificationHelper.EXTRA_DATE_KEY).orEmpty()
          val timeText = intent.getStringExtra(AdhanNotificationHelper.EXTRA_PRAYER_TIME_TEXT).orEmpty()
          val locationName = intent.getStringExtra(AdhanNotificationHelper.EXTRA_LOCATION_NAME)
            ?: persisted.settings.location?.resolvedLabel().orEmpty()

          if (prayerType != null && persisted.adhanSettings.alarmsEnabled) {
            val cfg = persisted.adhanSettings.configFor(prayerType)
            if (cfg.mode != PrayerAlarmMode.SILENT) {
              AdhanPlaybackService.startPrayerAlarm(
                context = appCtx,
                prayerType = prayerType,
                dateKey = dateKey,
                timeText = timeText,
                locationName = locationName,
                config = cfg,
                volumePercent = persisted.adhanSettings.adhanVolumePercent,
                vibrateEnabled = persisted.adhanSettings.vibrateOnAdhan,
              )
            }
          }
        } else if (action == ACTION_PRE_ADHAN_REMINDER) {
          val prayerKey = intent.getStringExtra(AdhanNotificationHelper.EXTRA_PRAYER_KEY).orEmpty()
          val prayerType = PrayerType.entries.firstOrNull { it.key == prayerKey }
          val timeText = intent.getStringExtra(AdhanNotificationHelper.EXTRA_PRAYER_TIME_TEXT).orEmpty()
          val locationName = intent.getStringExtra(AdhanNotificationHelper.EXTRA_LOCATION_NAME)
            ?: persisted.settings.location?.resolvedLabel().orEmpty()
          val minutesBefore = intent.getIntExtra(
            AdhanAlarmScheduler.EXTRA_MINUTES_BEFORE,
            persisted.adhanSettings.preAdhanMinutes,
          )
          if (prayerType != null && persisted.adhanSettings.alarmsEnabled && minutesBefore > 0) {
            AdhanNotificationHelper.showPreAdhanNotification(
              context = appCtx,
              prayerType = prayerType,
              minutesBefore = minutesBefore,
              timeText = timeText,
              locationName = locationName,
            )
          }
        }

        // Refresh persistent countdown notification + top-up next 48h alarms
        AdhanAlarmScheduler.syncFromPreferences(appCtx, persisted)
      } finally {
        pendingResult.finish()
      }
    }
  }

  companion object {
    const val ACTION_PRAYER_ALARM = "com.example.data.prayer.ACTION_PRAYER_ALARM"
    const val ACTION_PRE_ADHAN_REMINDER = "com.example.data.prayer.ACTION_PRE_ADHAN_REMINDER"
    const val ACTION_REFRESH_COUNTDOWN = "com.example.data.prayer.ACTION_REFRESH_COUNTDOWN"
  }
}

class AdhanBootRescheduleReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    if (action != Intent.ACTION_BOOT_COMPLETED &&
      action != Intent.ACTION_TIME_CHANGED &&
      action != Intent.ACTION_TIMEZONE_CHANGED &&
      action != Intent.ACTION_MY_PACKAGE_REPLACED
    ) {
      return
    }
    val pendingResult = goAsync()
    val appCtx = context.applicationContext

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val prefs = ReaderPreferences(appCtx)
        val repository = PrayerTimesRepository()
        val persisted = prefs.readPrayerPreferencesOnce()
        val loc = persisted.settings.location
        val updatedSchedule = if (loc != null) {
          repository.compute30DaySchedule(persisted.settings)?.also {
            prefs.saveCachedPrayerSchedule(it)
          } ?: persisted.cachedSchedule
        } else {
          persisted.cachedSchedule
        }
        AdhanAlarmScheduler.syncFromPreferences(
          context = appCtx,
          persisted = persisted.copy(cachedSchedule = updatedSchedule),
          repository = repository,
        )
      } finally {
        pendingResult.finish()
      }
    }
  }
}

class AdhanNotificationActionReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val action = intent?.action ?: return
    val pendingResult = goAsync()
    val appCtx = context.applicationContext

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val prayerKey = intent.getStringExtra(AdhanNotificationHelper.EXTRA_PRAYER_KEY).orEmpty()
        val dateKey = intent.getStringExtra(AdhanNotificationHelper.EXTRA_DATE_KEY).orEmpty()
        if (action == ACTION_MARK_PRAYED && prayerKey.isNotBlank() && dateKey.isNotBlank()) {
          ReaderPreferences(appCtx).markPrayerCompleted(dateKey = dateKey, prayerKey = prayerKey)
        }
        AdhanPlaybackService.stopAdhan(appCtx)
      } finally {
        pendingResult.finish()
      }
    }
  }

  companion object {
    const val ACTION_START_PRAYER = "com.example.data.prayer.NOTIF_START_PRAYER"
    const val ACTION_MARK_PRAYED = "com.example.data.prayer.NOTIF_MARK_PRAYED"
    const val ACTION_DISMISS = "com.example.data.prayer.NOTIF_DISMISS"
  }
}
