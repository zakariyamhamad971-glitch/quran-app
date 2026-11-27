package com.example.data.prayer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.ui.AdhanActivity

object AdhanNotificationHelper {
  const val CHANNEL_ADHAN_ALARM = "adhan_alarm_channel"
  const val CHANNEL_PRAYER_COUNTDOWN = "prayer_countdown_channel"

  const val NOTIFICATION_ID_ADHAN_ALARM = 2001
  const val NOTIFICATION_ID_PRAYER_COUNTDOWN = 2002

  const val EXTRA_PRAYER_KEY = "extra_prayer_key"
  const val EXTRA_DATE_KEY = "extra_date_key"
  const val EXTRA_PRAYER_TIME_TEXT = "extra_prayer_time_text"
  const val EXTRA_LOCATION_NAME = "extra_location_name"
  const val EXTRA_IS_TEST = "extra_is_test"

  fun ensureChannels(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val nm = context.getSystemService(NotificationManager::class.java) ?: return

    val alarmChannel = NotificationChannel(
      CHANNEL_ADHAN_ALARM,
      "تنبيهات الأذان",
      NotificationManager.IMPORTANCE_HIGH,
    ).apply {
      description = "تنبيهات دخول وقت الصلاة مع شاشة الأذان"
      // Audio is managed cleanly by AdhanPlaybackService MediaPlayer so stopping works immediately.
      setSound(null, null)
      enableVibration(true)
      setBypassDnd(true)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }

    val countdownChannel = NotificationChannel(
      CHANNEL_PRAYER_COUNTDOWN,
      context.getString(R.string.prayer_persistent_notif_title),
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = "إشعار دائم يعرض الصلاة القادمة والوقت المتبقي"
      setSound(null, null)
      enableVibration(false)
      setShowBadge(false)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }

    nm.createNotificationChannel(alarmChannel)
    nm.createNotificationChannel(countdownChannel)
  }

  fun canPostNotifications(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val granted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
      if (!granted) return false
    }
    val nm = context.getSystemService(NotificationManager::class.java) ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      nm.areNotificationsEnabled()
    } else {
      true
    }
  }

  fun canUseFullScreenIntentCompat(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < 34) return true
    val nm = context.getSystemService(NotificationManager::class.java) ?: return false
    return runCatching { nm.canUseFullScreenIntent() }.getOrDefault(true)
  }

  fun buildAdhanAlarmNotification(
    context: Context,
    prayerType: PrayerType,
    dateKey: String,
    timeText: String,
    locationName: String,
    isTest: Boolean,
  ): Notification {
    ensureChannels(context)

    val fullScreenIntent = Intent(context, AdhanActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_CLEAR_TOP
      putExtra(EXTRA_PRAYER_KEY, prayerType.key)
      putExtra(EXTRA_DATE_KEY, dateKey)
      putExtra(EXTRA_PRAYER_TIME_TEXT, timeText)
      putExtra(EXTRA_LOCATION_NAME, locationName)
      putExtra(EXTRA_IS_TEST, isTest)
    }
    val fullScreenPi = PendingIntent.getActivity(
      context,
      9101,
      fullScreenIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val startPrayerPi = actionBroadcastIntent(
      context = context,
      action = AdhanNotificationActionReceiver.ACTION_START_PRAYER,
      requestCode = 9102,
      prayerKey = prayerType.key,
      dateKey = dateKey,
    )
    val markPrayedPi = actionBroadcastIntent(
      context = context,
      action = AdhanNotificationActionReceiver.ACTION_MARK_PRAYED,
      requestCode = 9103,
      prayerKey = prayerType.key,
      dateKey = dateKey,
    )
    val dismissPi = actionBroadcastIntent(
      context = context,
      action = AdhanNotificationActionReceiver.ACTION_DISMISS,
      requestCode = 9104,
      prayerKey = prayerType.key,
      dateKey = dateKey,
    )

    val title = if (isTest) {
      "تجربة الأذان الآن — ${prayerType.arabicName}"
    } else {
      "حان الآن موعد صلاة ${prayerType.arabicName}"
    }
    val content = buildString {
      if (timeText.isNotBlank()) append(timeText)
      if (locationName.isNotBlank()) {
        if (isNotEmpty()) append(" • ")
        append(locationName)
      }
      if (isEmpty()) append("حي على الصلاة")
    }

    val builder = NotificationCompat.Builder(context, CHANNEL_ADHAN_ALARM)
      .setSmallIcon(R.drawable.ic_adhan_notification)
      .setContentTitle(title)
      .setContentText(content)
      .setCategory(NotificationCompat.CATEGORY_ALARM)
      .setPriority(NotificationCompat.PRIORITY_MAX)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setOngoing(true)
      .setAutoCancel(false)
      .setContentIntent(fullScreenPi)
      .addAction(0, "بدء الصلاة", startPrayerPi)
      .addAction(0, "انتهيت من الصلاة", markPrayedPi)
      .addAction(0, "إغلاق", dismissPi)

    if (!isTest) {
      builder.setFullScreenIntent(fullScreenPi, true)
    }

    return builder.build()
  }

  fun refreshPersistentCountdownNotification(
    context: Context,
    settings: PrayerSettingsState,
    adhanSettings: AdhanSettingsState,
    cachedSchedule: CachedPrayerSchedule?,
    repository: PrayerTimesRepository = PrayerTimesRepository(),
  ) {
    ensureChannels(context)
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    val location = settings.location
    if (!adhanSettings.persistentCountdownEnabled || location == null || !canPostNotifications(context)) {
      nm.cancel(NOTIFICATION_ID_PRAYER_COUNTDOWN)
      return
    }

    val nowMs = System.currentTimeMillis()
    val tz = java.util.TimeZone.getTimeZone(location.timeZoneId)
    val todayCal = java.util.Calendar.getInstance(tz).apply { timeInMillis = nowMs }
    val tomorrowCal = (todayCal.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }

    val todayKey = PrayerFormatters.calendarDateKey(todayCal)
    val tomorrowKey = PrayerFormatters.calendarDateKey(tomorrowCal)

    val validCache = cachedSchedule?.takeIf { it.cacheKey == settings.cacheKey() }
    val todayRec = validCache?.daysByDateKey?.get(todayKey)
      ?: repository.computeDay(
        location = location,
        method = settings.calculationMethod,
        madhab = settings.asrMadhab,
        highLatitudeRule = settings.highLatitudeRule,
        adjustments = settings.adjustments,
        year = todayCal.get(java.util.Calendar.YEAR),
        month = todayCal.get(java.util.Calendar.MONTH) + 1,
        day = todayCal.get(java.util.Calendar.DAY_OF_MONTH),
      )
    val tomorrowRec = validCache?.daysByDateKey?.get(tomorrowKey)
      ?: repository.computeDay(
        location = location,
        method = settings.calculationMethod,
        madhab = settings.asrMadhab,
        highLatitudeRule = settings.highLatitudeRule,
        adjustments = settings.adjustments,
        year = tomorrowCal.get(java.util.Calendar.YEAR),
        month = tomorrowCal.get(java.util.Calendar.MONTH) + 1,
        day = tomorrowCal.get(java.util.Calendar.DAY_OF_MONTH),
      )

    val nextTarget = repository.resolveNextPrayer(todayRec, tomorrowRec, nowMs)
    if (nextTarget == null) {
      nm.cancel(NOTIFICATION_ID_PRAYER_COUNTDOWN)
      return
    }

    val formattedTime = PrayerFormatters.formatTime12hArabic(nextTarget.targetEpochMs, location.timeZoneId)
    val tomorrowSuffix = if (nextTarget.isTomorrow) " (غداً)" else ""
    val title = "الصلاة القادمة: ${nextTarget.prayerType.arabicName} • $formattedTime$tomorrowSuffix"
    val subText = location.resolvedLabel()

    val openAppIntent = Intent(context, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val contentPi = PendingIntent.getActivity(
      context,
      9201,
      openAppIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val deleteIntent = Intent(context, AdhanAlarmReceiver::class.java).apply {
      action = AdhanAlarmReceiver.ACTION_REFRESH_COUNTDOWN
    }
    val deletePi = PendingIntent.getBroadcast(
      context,
      9202,
      deleteIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val notification = NotificationCompat.Builder(context, CHANNEL_PRAYER_COUNTDOWN)
      .setSmallIcon(R.drawable.ic_adhan_notification)
      .setContentTitle(title)
      .setContentText(subText)
      .setSubText("المواقيت")
      .setWhen(nextTarget.targetEpochMs)
      .setShowWhen(true)
      .setUsesChronometer(true)
      .setChronometerCountDown(true)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setSilent(true)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setContentIntent(contentPi)
      .setDeleteIntent(deletePi)
      .build()

    runCatching {
      nm.notify(NOTIFICATION_ID_PRAYER_COUNTDOWN, notification)
    }
  }

  fun cancelAdhanAlarmNotification(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    nm.cancel(NOTIFICATION_ID_ADHAN_ALARM)
  }

  private fun actionBroadcastIntent(
    context: Context,
    action: String,
    requestCode: Int,
    prayerKey: String,
    dateKey: String,
  ): PendingIntent {
    val intent = Intent(context, AdhanNotificationActionReceiver::class.java).apply {
      this.action = action
      putExtra(EXTRA_PRAYER_KEY, prayerKey)
      putExtra(EXTRA_DATE_KEY, dateKey)
    }
    return PendingIntent.getBroadcast(
      context,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }
}
