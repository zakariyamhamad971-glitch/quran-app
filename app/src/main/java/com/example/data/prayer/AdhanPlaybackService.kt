package com.example.data.prayer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdhanPlaybackService : Service() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private var mediaPlayer: MediaPlayer? = null
  private var wakeLock: PowerManager.WakeLock? = null
  private var autoStopJob: Job? = null
  private var vibrator: Vibrator? = null
  private lateinit var soundRepository: AdhanSoundRepository

  override fun onCreate() {
    super.onCreate()
    soundRepository = AdhanSoundRepository(applicationContext)
    AdhanNotificationHelper.ensureChannels(applicationContext)
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP_ADHAN -> {
        stopPlaybackAndExit()
        return START_NOT_STICKY
      }
      ACTION_START_ADHAN,
      ACTION_TEST_ADHAN_10S,
      -> {
        val isTest = intent.action == ACTION_TEST_ADHAN_10S ||
          intent.getBooleanExtra(AdhanNotificationHelper.EXTRA_IS_TEST, false)
        val prayerKey = intent.getStringExtra(AdhanNotificationHelper.EXTRA_PRAYER_KEY).orEmpty()
        val prayerType = PrayerType.entries.firstOrNull { it.key == prayerKey } ?: PrayerType.FAJR
        val dateKey = intent.getStringExtra(AdhanNotificationHelper.EXTRA_DATE_KEY).orEmpty()
        val timeText = intent.getStringExtra(AdhanNotificationHelper.EXTRA_PRAYER_TIME_TEXT).orEmpty()
        val locationName = intent.getStringExtra(AdhanNotificationHelper.EXTRA_LOCATION_NAME).orEmpty()
        val modeName = intent.getStringExtra(EXTRA_ALARM_MODE).orEmpty()
        val customUriStr = intent.getStringExtra(EXTRA_CUSTOM_URI)
        val muezzinIdStr = intent.getStringExtra(EXTRA_MUEZZIN_ID)
        val volumePercent = intent.getIntExtra(EXTRA_VOLUME_PERCENT, 100).coerceIn(10, 100)
        val vibrateEnabled = intent.getBooleanExtra(EXTRA_VIBRATE_ENABLED, true)
        val mode = runCatching { PrayerAlarmMode.valueOf(modeName) }
          .getOrDefault(PrayerAlarmMode.SYSTEM_DEFAULT)

        val config = PrayerAlarmConfig(
          mode = if (isTest && mode == PrayerAlarmMode.SILENT) PrayerAlarmMode.SYSTEM_DEFAULT else mode,
          customUri = customUriStr,
          muezzinId = muezzinIdStr,
        )

        val notification = AdhanNotificationHelper.buildAdhanAlarmNotification(
          context = applicationContext,
          prayerType = prayerType,
          dateKey = dateKey,
          timeText = timeText,
          locationName = locationName,
          isTest = isTest,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          startForeground(
            AdhanNotificationHelper.NOTIFICATION_ID_ADHAN_ALARM,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
          )
        } else {
          startForeground(
            AdhanNotificationHelper.NOTIFICATION_ID_ADHAN_ALARM,
            notification,
          )
        }

        acquireSafetyWakeLock(timeoutMs = if (isTest) 15_000L else 4 * 60_000L)
        _isTestPlaying.value = isTest
        _isAnyAdhanActive.value = true

        if (vibrateEnabled && config.mode != PrayerAlarmMode.SILENT) {
          startGentleVibration()
        } else {
          stopVibration()
        }

        val uri = soundRepository.resolvePlayableUri(config)
        if (uri != null && config.mode != PrayerAlarmMode.SILENT) {
          startAudioUri(
            uri = uri,
            loopIfShortAlarm = !isTest && config.mode == PrayerAlarmMode.SYSTEM_DEFAULT,
            volumePercent = volumePercent,
          )
        }

        autoStopJob?.cancel()
        val maxDurationMs = if (isTest) TEST_DURATION_MS else MAX_ALARM_DURATION_MS
        autoStopJob = serviceScope.launch {
          delay(maxDurationMs)
          stopPlaybackAndExit()
        }
      }
    }
    return START_NOT_STICKY
  }

  private fun startGentleVibration() {
    stopVibration()
    runCatching {
      val v: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java)?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        getSystemService(Vibrator::class.java)
      }
      if (v != null && v.hasVibrator()) {
        vibrator = v
        val pattern = longArrayOf(0L, 450L, 650L, 450L, 1200L)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          v.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
          @Suppress("DEPRECATION")
          v.vibrate(pattern, 0)
        }
      }
    }
  }

  private fun stopVibration() {
    runCatching { vibrator?.cancel() }
    vibrator = null
  }

  private fun startAudioUri(uri: Uri, loopIfShortAlarm: Boolean, volumePercent: Int) {
    releasePlayer()
    val vol = (volumePercent.coerceIn(10, 100) / 100f)
    runCatching {
      val mp = MediaPlayer().apply {
        setAudioAttributes(
          AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build(),
        )
        setDataSource(applicationContext, uri)
        isLooping = loopIfShortAlarm
        setVolume(vol, vol)
        setOnErrorListener { _, _, _ ->
          val fallback = soundRepository.resolveSystemDefaultAlarmUri()
          if (fallback != null && fallback != uri) {
            startAudioUri(fallback, loopIfShortAlarm = loopIfShortAlarm, volumePercent = volumePercent)
          } else {
            stopPlaybackAndExit()
          }
          true
        }
        setOnCompletionListener {
          if (!loopIfShortAlarm) {
            stopPlaybackAndExit()
          }
        }
        prepareAsync()
        setOnPreparedListener { player ->
          runCatching {
            player.setVolume(vol, vol)
            player.start()
          }
        }
      }
      mediaPlayer = mp
    }.onFailure {
      val fallback = soundRepository.resolveSystemDefaultAlarmUri()
      if (fallback != null && fallback != uri) {
        startAudioUri(fallback, loopIfShortAlarm = loopIfShortAlarm, volumePercent = volumePercent)
      }
    }
  }

  private fun acquireSafetyWakeLock(timeoutMs: Long) {
    releaseWakeLock()
    runCatching {
      val pm = getSystemService(PowerManager::class.java) ?: return
      val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Qabasat:AdhanPlaybackWakeLock").apply {
        setReferenceCounted(false)
        acquire(timeoutMs)
      }
      wakeLock = wl
    }
  }

  private fun releaseWakeLock() {
    runCatching {
      wakeLock?.let { if (it.isHeld) it.release() }
    }
    wakeLock = null
  }

  private fun releasePlayer() {
    runCatching {
      mediaPlayer?.let { mp ->
        if (mp.isPlaying) mp.stop()
        mp.reset()
        mp.release()
      }
    }
    mediaPlayer = null
  }

  private fun stopPlaybackAndExit() {
    autoStopJob?.cancel()
    autoStopJob = null
    releasePlayer()
    stopVibration()
    releaseWakeLock()
    _isTestPlaying.value = false
    _isAnyAdhanActive.value = false
    AdhanNotificationHelper.cancelAdhanAlarmNotification(applicationContext)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE)
    } else {
      @Suppress("DEPRECATION")
      stopForeground(true)
    }
    stopSelf()
  }

  override fun onDestroy() {
    autoStopJob?.cancel()
    releasePlayer()
    stopVibration()
    releaseWakeLock()
    _isTestPlaying.value = false
    _isAnyAdhanActive.value = false
    serviceScope.cancel()
    super.onDestroy()
  }

  companion object {
    const val ACTION_START_ADHAN = "com.example.data.prayer.ACTION_START_ADHAN"
    const val ACTION_STOP_ADHAN = "com.example.data.prayer.ACTION_STOP_ADHAN"
    const val ACTION_TEST_ADHAN_10S = "com.example.data.prayer.ACTION_TEST_ADHAN_10S"

    const val EXTRA_ALARM_MODE = "extra_alarm_mode"
    const val EXTRA_CUSTOM_URI = "extra_custom_uri"
    const val EXTRA_MUEZZIN_ID = "extra_muezzin_id"
    const val EXTRA_VOLUME_PERCENT = "extra_volume_percent"
    const val EXTRA_VIBRATE_ENABLED = "extra_vibrate_enabled"

    private const val TEST_DURATION_MS = 10_000L
    private const val MAX_ALARM_DURATION_MS = 180_000L

    private val _isTestPlaying = MutableStateFlow(false)
    val isTestPlayingFlow: StateFlow<Boolean> = _isTestPlaying.asStateFlow()

    private val _isAnyAdhanActive = MutableStateFlow(false)
    val isAnyAdhanActiveFlow: StateFlow<Boolean> = _isAnyAdhanActive.asStateFlow()

    fun startPrayerAlarm(
      context: Context,
      prayerType: PrayerType,
      dateKey: String,
      timeText: String,
      locationName: String,
      config: PrayerAlarmConfig,
      volumePercent: Int = 100,
      vibrateEnabled: Boolean = true,
    ) {
      val intent = Intent(context, AdhanPlaybackService::class.java).apply {
        action = ACTION_START_ADHAN
        putExtra(AdhanNotificationHelper.EXTRA_PRAYER_KEY, prayerType.key)
        putExtra(AdhanNotificationHelper.EXTRA_DATE_KEY, dateKey)
        putExtra(AdhanNotificationHelper.EXTRA_PRAYER_TIME_TEXT, timeText)
        putExtra(AdhanNotificationHelper.EXTRA_LOCATION_NAME, locationName)
        putExtra(AdhanNotificationHelper.EXTRA_IS_TEST, false)
        putExtra(EXTRA_ALARM_MODE, config.mode.name)
        putExtra(EXTRA_CUSTOM_URI, config.customUri)
        putExtra(EXTRA_MUEZZIN_ID, config.muezzinId)
        putExtra(EXTRA_VOLUME_PERCENT, volumePercent)
        putExtra(EXTRA_VIBRATE_ENABLED, vibrateEnabled)
      }
      ContextCompat.startForegroundService(context, intent)
    }

    fun startTestAdhan10s(
      context: Context,
      prayerType: PrayerType = PrayerType.FAJR,
      config: PrayerAlarmConfig,
      locationName: String,
      volumePercent: Int = 100,
      vibrateEnabled: Boolean = true,
    ) {
      val intent = Intent(context, AdhanPlaybackService::class.java).apply {
        action = ACTION_TEST_ADHAN_10S
        putExtra(AdhanNotificationHelper.EXTRA_PRAYER_KEY, prayerType.key)
        putExtra(AdhanNotificationHelper.EXTRA_DATE_KEY, "")
        putExtra(AdhanNotificationHelper.EXTRA_PRAYER_TIME_TEXT, "تجربة ١٠ ثوانٍ")
        putExtra(AdhanNotificationHelper.EXTRA_LOCATION_NAME, locationName)
        putExtra(AdhanNotificationHelper.EXTRA_IS_TEST, true)
        putExtra(EXTRA_ALARM_MODE, config.mode.name)
        putExtra(EXTRA_CUSTOM_URI, config.customUri)
        putExtra(EXTRA_MUEZZIN_ID, config.muezzinId)
        putExtra(EXTRA_VOLUME_PERCENT, volumePercent)
        putExtra(EXTRA_VIBRATE_ENABLED, vibrateEnabled)
      }
      ContextCompat.startForegroundService(context, intent)
    }

    fun stopAdhan(context: Context) {
      val intent = Intent(context, AdhanPlaybackService::class.java).apply {
        action = ACTION_STOP_ADHAN
      }
      runCatching { context.startService(intent) }
      _isTestPlaying.value = false
      _isAnyAdhanActive.value = false
      AdhanNotificationHelper.cancelAdhanAlarmNotification(context)
    }
  }
}
