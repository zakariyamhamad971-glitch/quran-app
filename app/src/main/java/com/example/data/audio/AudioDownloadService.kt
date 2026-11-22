package com.example.data.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.example.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AudioDownloadService : Service() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var observerJob: Job? = null
  private lateinit var notificationManager: NotificationManager
  private var isForegroundActive = false

  override fun onCreate() {
    super.onCreate()
    notificationManager = getSystemService(NotificationManager::class.java)
    ensureNotificationChannel()
    observeDownloads()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_CANCEL_ALL) {
      AudioDownloadManager.getInstance(applicationContext).cancelAll()
      stopForegroundCompat()
      stopSelf()
      return START_NOT_STICKY
    }

    val summary = AudioDownloadManager.getInstance(applicationContext).batchSummary.value
    promoteToForeground(buildNotification(summary))
    if (!summary.hasWork) {
      stopForegroundCompat()
      stopSelf()
    }
    return START_STICKY
  }

  private fun observeDownloads() {
    observerJob?.cancel()
    observerJob = serviceScope.launch {
      val manager = AudioDownloadManager.getInstance(applicationContext)
      manager.batchSummary.collectLatest { summary ->
        if (!summary.hasWork) {
          stopForegroundCompat()
          stopSelf()
        } else {
          val notification = buildNotification(summary)
          if (!isForegroundActive) {
            promoteToForeground(notification)
          } else {
            runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
          }
        }
      }
    }
  }

  private fun promoteToForeground(notification: Notification) {
    runCatching {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        startForeground(
          NOTIFICATION_ID,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
      } else {
        startForeground(NOTIFICATION_ID, notification)
      }
      isForegroundActive = true
    }
  }

  private fun stopForegroundCompat() {
    if (isForegroundActive) {
      runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
      isForegroundActive = false
    }
    runCatching { notificationManager.cancel(NOTIFICATION_ID) }
  }

  private fun buildNotification(summary: AudioBatchUiSummary): Notification {
    val contentIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val cancelIntent = Intent(this, AudioDownloadService::class.java).setAction(ACTION_CANCEL_ALL)
    val cancelPendingIntent = PendingIntent.getService(
      this,
      1,
      cancelIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val title = when {
      summary.pausedOffline -> "تحميل التلاوات متوقف مؤقتاً"
      summary.currentReciterName.isNotBlank() -> summary.currentReciterName
      else -> "تحميل التلاوات"
    }

    val text = when {
      summary.pausedOffline -> "بانتظار الاتصال بالإنترنت لاستئناف التحميل"
      summary.totalInRunCount > 1 ->
        "${summary.currentSurahName} • ${summary.completedInRunCount} من ${summary.totalInRunCount} (${summary.overallProgressPercent}%)"
      summary.currentSurahName.isNotBlank() ->
        "${summary.currentSurahName} (${summary.overallProgressPercent}%)"
      else -> "جاري تحميل التلاوة (${summary.overallProgressPercent}%)"
    }

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }

    return builder
      .setContentTitle(title)
      .setContentText(text)
      .setSmallIcon(android.R.drawable.stat_sys_download)
      .setContentIntent(contentIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setProgress(100, summary.overallProgressPercent.coerceIn(0, 100), summary.pausedOffline)
      .addAction(
        Notification.Action.Builder(
          android.R.drawable.ic_menu_close_clear_cancel,
          "إلغاء",
          cancelPendingIntent,
        ).build(),
      )
      .build()
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "تحميل التلاوات",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "عرض تقدم تنزيل تلاوات القرآن الكريم"
        setShowBadge(false)
      }
      notificationManager.createNotificationChannel(channel)
    }
  }

  override fun onDestroy() {
    observerJob?.cancel()
    serviceScope.cancel()
    stopForegroundCompat()
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL_ID = "qabasat_audio_downloads"
    private const val NOTIFICATION_ID = 1002
    private const val ACTION_CANCEL_ALL = "com.example.data.audio.ACTION_CANCEL_ALL"

    fun start(context: Context) {
      val appCtx = context.applicationContext
      val intent = Intent(appCtx, AudioDownloadService::class.java)
      runCatching {
        ContextCompat.startForegroundService(appCtx, intent)
      }
    }
  }
}
