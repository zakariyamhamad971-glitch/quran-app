package com.example.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.MainActivity

class QabasatPlaybackService : Service() {

  inner class LocalBinder : Binder() {
    fun getService(): QabasatPlaybackService = this@QabasatPlaybackService
  }

  private val binder = LocalBinder()
  private lateinit var exoPlayer: ExoPlayer
  private lateinit var platformSession: MediaSession
  private lateinit var notificationManager: NotificationManager

  private var currentTitle: String = "قبسات"
  private var currentArtist: String = ""
  private var onSkipNext: (() -> Unit)? = null
  private var onSkipPrev: (() -> Unit)? = null
  private var isForegroundStarted: Boolean = false

  val player: ExoPlayer
    get() = exoPlayer

  override fun onCreate() {
    super.onCreate()
    notificationManager = getSystemService(NotificationManager::class.java)
    ensureNotificationChannel()

    val audioAttributes = AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA)
      .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
      .build()

    exoPlayer = ExoPlayer.Builder(this)
      .setAudioAttributes(audioAttributes, true)
      .setHandleAudioBecomingNoisy(true)
      .setSeekBackIncrementMs(10_000L)
      .setSeekForwardIncrementMs(10_000L)
      .build()

    val launchIntent = Intent(this, MainActivity::class.java).apply {
      flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    val sessionActivity = PendingIntent.getActivity(
      this,
      0,
      launchIntent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    platformSession = MediaSession(this, "QabasatPlaybackSession").apply {
      setSessionActivity(sessionActivity)
      setCallback(object : MediaSession.Callback() {
        override fun onPlay() {
          exoPlayer.play()
        }

        override fun onPause() {
          exoPlayer.pause()
        }

        override fun onSeekTo(pos: Long) {
          exoPlayer.seekTo(pos.coerceAtLeast(0L))
        }

        override fun onSkipToNext() {
          onSkipNext?.invoke()
        }

        override fun onSkipToPrevious() {
          onSkipPrev?.invoke()
        }

        override fun onFastForward() {
          exoPlayer.seekForward()
        }

        override fun onRewind() {
          exoPlayer.seekBack()
        }

        override fun onStop() {
          exoPlayer.pause()
          stopForegroundCompat(removeNotification = true)
          stopSelf()
        }
      })
      isActive = true
    }

    exoPlayer.addListener(object : Player.Listener {
      override fun onIsPlayingChanged(isPlaying: Boolean) {
        syncPlatformSessionAndNotification(isPlaying)
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        syncPlatformSessionAndNotification(exoPlayer.isPlaying)
      }
    })
  }

  override fun onBind(intent: Intent?): IBinder = binder

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_PLAY_PAUSE -> {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
      }
      ACTION_NEXT -> onSkipNext?.invoke()
      ACTION_PREV -> onSkipPrev?.invoke()
      ACTION_STOP -> {
        exoPlayer.pause()
        stopForegroundCompat(removeNotification = true)
        stopSelf()
      }
    }
    return START_NOT_STICKY
  }

  fun configureMetadata(
    title: String,
    artist: String,
    onPrevSurah: () -> Unit,
    onNextSurah: () -> Unit,
  ) {
    currentTitle = title.ifBlank { "قبسات" }
    currentArtist = artist
    onSkipPrev = onPrevSurah
    onSkipNext = onNextSurah

    val meta = MediaMetadata.Builder()
      .putString(MediaMetadata.METADATA_KEY_TITLE, currentTitle)
      .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
      .putString(MediaMetadata.METADATA_KEY_ALBUM, "قبسات — حفص عن عاصم")
      .apply {
        val dur = exoPlayer.duration
        if (dur > 0L) putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
      }
      .build()
    platformSession.setMetadata(meta)
    syncPlatformSessionAndNotification(exoPlayer.isPlaying)
  }

  private fun syncPlatformSessionAndNotification(isPlaying: Boolean) {
    val stateCode = when {
      isPlaying -> PlaybackState.STATE_PLAYING
      exoPlayer.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
      exoPlayer.playbackState == Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
      else -> PlaybackState.STATE_PAUSED
    }
    val actions = PlaybackState.ACTION_PLAY or
      PlaybackState.ACTION_PAUSE or
      PlaybackState.ACTION_PLAY_PAUSE or
      PlaybackState.ACTION_SEEK_TO or
      PlaybackState.ACTION_SKIP_TO_NEXT or
      PlaybackState.ACTION_SKIP_TO_PREVIOUS or
      PlaybackState.ACTION_FAST_FORWARD or
      PlaybackState.ACTION_REWIND or
      PlaybackState.ACTION_STOP

    val playbackState = PlaybackState.Builder()
      .setActions(actions)
      .setState(
        stateCode,
        exoPlayer.currentPosition.coerceAtLeast(0L),
        exoPlayer.playbackParameters.speed,
      )
      .build()
    platformSession.setPlaybackState(playbackState)

    if (isPlaying || exoPlayer.playbackState == Player.STATE_BUFFERING) {
      val notification = buildNotification(isPlaying = isPlaying)
      runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
          )
        } else {
          startForeground(NOTIFICATION_ID, notification)
        }
        isForegroundStarted = true
      }
    } else if (isForegroundStarted) {
      stopForegroundCompat(removeNotification = false)
      runCatching {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(isPlaying = false))
      }
    }
  }

  private fun stopForegroundCompat(removeNotification: Boolean) {
    if (!isForegroundStarted) return
    val flags = if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH
    stopForeground(flags)
    isForegroundStarted = false
  }

  private fun buildNotification(isPlaying: Boolean): Notification {
    val contentIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val prevAction = Notification.Action.Builder(
      android.R.drawable.ic_media_previous,
      "السورة السابقة",
      serviceActionPendingIntent(ACTION_PREV, 1),
    ).build()

    val playPauseAction = Notification.Action.Builder(
      if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
      if (isPlaying) "إيقاف مؤقت" else "تشغيل",
      serviceActionPendingIntent(ACTION_PLAY_PAUSE, 2),
    ).build()

    val nextAction = Notification.Action.Builder(
      android.R.drawable.ic_media_next,
      "السورة التالية",
      serviceActionPendingIntent(ACTION_NEXT, 3),
    ).build()

    val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      Notification.Builder(this, CHANNEL_ID)
    } else {
      @Suppress("DEPRECATION")
      Notification.Builder(this)
    }

    return builder
      .setContentTitle(currentTitle)
      .setContentText(currentArtist.ifBlank { "تلاوة القرآن الكريم" })
      .setSmallIcon(android.R.drawable.ic_media_play)
      .setContentIntent(contentIntent)
      .setOngoing(isPlaying)
      .setVisibility(Notification.VISIBILITY_PUBLIC)
      .addAction(prevAction)
      .addAction(playPauseAction)
      .addAction(nextAction)
      .setStyle(
        Notification.MediaStyle()
          .setMediaSession(platformSession.sessionToken)
          .setShowActionsInCompactView(0, 1, 2),
      )
      .build()
  }

  private fun serviceActionPendingIntent(action: String, requestCode: Int): PendingIntent {
    val intent = Intent(this, QabasatPlaybackService::class.java).setAction(action)
    return PendingIntent.getService(
      this,
      requestCode,
      intent,
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
  }

  private fun ensureNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "تلاوة القرآن الكريم",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "عناصر التحكم بتشغيل التلاوة الصوتية"
        setShowBadge(false)
      }
      notificationManager.createNotificationChannel(channel)
    }
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    if (!exoPlayer.playWhenReady || exoPlayer.playbackState == Player.STATE_ENDED) {
      stopForegroundCompat(removeNotification = true)
      stopSelf()
    }
  }

  override fun onDestroy() {
    stopForegroundCompat(removeNotification = true)
    platformSession.isActive = false
    platformSession.release()
    exoPlayer.release()
    super.onDestroy()
  }

  companion object {
    private const val CHANNEL_ID = "qabasat_playback"
    private const val NOTIFICATION_ID = 1001
    private const val ACTION_PLAY_PAUSE = "com.example.player.ACTION_PLAY_PAUSE"
    private const val ACTION_NEXT = "com.example.player.ACTION_NEXT"
    private const val ACTION_PREV = "com.example.player.ACTION_PREV"
    private const val ACTION_STOP = "com.example.player.ACTION_STOP"
  }
}
