package com.example.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.compose.runtime.Immutable
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import com.example.data.audio.AudioCacheManager
import com.example.data.audio.AudioRepository
import com.example.data.audio.AyahTimingDto
import com.example.data.audio.RecitationRange
import com.example.data.audio.VerifiedReciter
import com.example.data.prefs.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class AudioPlaybackUiState(
  val isConnected: Boolean = false,
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val currentReciter: VerifiedReciter? = null,
  val currentSurahId: Int = 1,
  val currentSurahName: String = "",
  val positionMs: Long = 0L,
  val durationMs: Long = 0L,
  val playbackSpeed: Float = 1.0f,
  val isOfflineSource: Boolean = false,
  val activePlayingVerseKey: String? = null,
  val hasAyahTiming: Boolean = false,
  val activeRange: RecitationRange? = null,
  val errorMessage: String? = null,
)

@Immutable
data class AudioMiniUiState(
  val isActive: Boolean = false,
  val isPlaying: Boolean = false,
  val isBuffering: Boolean = false,
  val currentSurahId: Int = 1,
  val currentSurahName: String = "",
  val reciterName: String = "",
)

class PlaybackController(
  private val context: Context,
  private val audioRepository: AudioRepository,
  private val audioCacheManager: AudioCacheManager,
  private val preferences: ReaderPreferences,
  private val scope: CoroutineScope,
) {
  private val _uiState = MutableStateFlow(AudioPlaybackUiState())
  val uiState: StateFlow<AudioPlaybackUiState> = _uiState.asStateFlow()

  private var playbackService: QabasatPlaybackService? = null
  private var player: Player? = null
  private var isBound: Boolean = false
  private var pendingMediaQueue: PendingMediaQueue? = null
  private var tickerJob: Job? = null
  private var currentAyahTimings: List<AyahTimingDto> = emptyList()
  private var currentVersePlaylist: List<com.example.data.audio.VerseAudioSegment> = emptyList()
  private var isVerseByVerseMode: Boolean = false
  private var surahNamesMap: Map<Int, String> = emptyMap()
  private var onSurahCompletedCallback: ((Int) -> Unit)? = null
  private var onActiveVerseChangedCallback: ((String?) -> Unit)? = null

  private data class PendingMediaQueue(
    val items: List<MediaItem>,
    val startIndex: Int,
    val startPositionMs: Long,
    val autoPlay: Boolean,
    val title: String,
    val artist: String,
  )

  companion object {
    private const val PROGRESS_TICK_MS = 350L
  }

  private val playerListener = object : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
      val pos = player?.currentPosition?.coerceAtLeast(0L) ?: _uiState.value.positionMs
      val dur = player?.duration?.takeIf { it > 0L } ?: _uiState.value.durationMs
      _uiState.update { it.copy(isPlaying = isPlaying, positionMs = pos, durationMs = dur) }
      if (isPlaying) {
        startPositionTicker()
      } else {
        stopPositionTickerIfIdle()
        persistCurrentPosition()
      }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
      if (isVerseByVerseMode) {
        val activePlayer = player
        updateActiveVerseFromPlaylist(activePlayer)
        if (activePlayer != null) {
          checkVersePlaylistRangeBoundary(activePlayer)
        }
      }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
      val buffering = playbackState == Player.STATE_BUFFERING
      val duration = player?.duration?.takeIf { it > 0L } ?: _uiState.value.durationMs
      val pos = player?.currentPosition?.coerceAtLeast(0L) ?: _uiState.value.positionMs
      _uiState.update {
        it.copy(
          isBuffering = buffering,
          durationMs = duration,
          positionMs = pos,
        )
      }
      if (buffering || player?.isPlaying == true) {
        startPositionTicker()
      } else {
        stopPositionTickerIfIdle()
      }
      if (playbackState == Player.STATE_ENDED) {
        handleSurahEnded(_uiState.value.currentSurahId)
      }
    }

    override fun onPlayerError(error: PlaybackException) {
      stopPositionTicker()
      _uiState.update {
        it.copy(
          isPlaying = false,
          isBuffering = false,
          errorMessage = "تعذر تشغيل التلاوة: ${error.localizedMessage ?: "خطأ في الشبكة"}",
        )
      }
    }
  }

  private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
      val localBinder = service as? QabasatPlaybackService.LocalBinder ?: return
      val svc = localBinder.getService()
      playbackService = svc
      val exo = svc.player
      player = exo
      exo.addListener(playerListener)
      exo.playbackParameters = PlaybackParameters(_uiState.value.playbackSpeed)
      _uiState.update { it.copy(isConnected = true) }

      val pending = pendingMediaQueue
      if (pending != null) {
        pendingMediaQueue = null
        svc.configureMetadata(
          title = pending.title,
          artist = pending.artist,
          onPrevSurah = { prevSurah() },
          onNextSurah = { nextSurah() },
        )
        exo.setMediaItems(pending.items, pending.startIndex, pending.startPositionMs)
        exo.prepare()
        if (pending.autoPlay) {
          ensureStartedForPlayback()
          exo.play()
        }
        if (isVerseByVerseMode) {
          updateActiveVerseFromPlaylist(exo)
        }
      }
      if (exo.isPlaying || exo.playbackState == Player.STATE_BUFFERING) {
        startPositionTicker()
      }
    }

    override fun onServiceDisconnected(name: ComponentName?) {
      player?.removeListener(playerListener)
      player = null
      playbackService = null
      _uiState.update { it.copy(isConnected = false) }
    }
  }

  private fun ensureStartedForPlayback() {
    runCatching {
      context.startService(Intent(context, QabasatPlaybackService::class.java))
    }
  }

  fun connect(
    onSurahEnded: (Int) -> Unit,
    onActiveVerseChanged: (String?) -> Unit,
  ) {
    onSurahCompletedCallback = onSurahEnded
    onActiveVerseChangedCallback = onActiveVerseChanged
    if (isBound) return
    val intent = Intent(context, QabasatPlaybackService::class.java)
    isBound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
  }

  private fun handleSurahEnded(endedSurah: Int) {
    val range = _uiState.value.activeRange
    val reciter = _uiState.value.currentReciter
    if (range != null && reciter != null) {
      if (endedSurah < range.endSurah) {
        val next = reciter.availableSurahs.filter { it > endedSurah && it <= range.endSurah }.minOrNull()
        if (next != null) {
          playSurahInternal(reciter, next, startPositionMs = 0L, targetAyah = null, autoPlay = true)
          return
        }
      }
      if (range.loop) {
        playRange(range)
      } else {
        _uiState.update { it.copy(isPlaying = false, activePlayingVerseKey = null) }
      }
      return
    }
    onSurahCompletedCallback?.invoke(endedSurah)
  }

  fun updateSurahNames(names: Map<Int, String>) {
    surahNamesMap = names
    val currentId = _uiState.value.currentSurahId
    if (names.containsKey(currentId)) {
      _uiState.update { it.copy(currentSurahName = names[currentId].orEmpty()) }
    }
  }

  fun restoreSelectionWithoutPlaying(
    reciter: VerifiedReciter,
    surahId: Int,
    positionMs: Long,
    speed: Float,
  ) {
    _uiState.update {
      it.copy(
        currentReciter = reciter,
        currentSurahId = surahId,
        currentSurahName = surahNamesMap[surahId].orEmpty(),
        positionMs = positionMs,
        playbackSpeed = speed.coerceIn(0.75f, 1.25f),
      )
    }
    player?.playbackParameters = PlaybackParameters(speed.coerceIn(0.75f, 1.25f))
  }

  fun setAyahRange(range: RecitationRange?) {
    _uiState.update { it.copy(activeRange = range) }
  }

  fun playRange(range: RecitationRange) {
    val normalized = if (range.startSurah > range.endSurah ||
      (range.startSurah == range.endSurah && range.startAyah > range.endAyah)
    ) {
      RecitationRange(
        startSurah = range.endSurah,
        startAyah = range.endAyah,
        endSurah = range.startSurah,
        endAyah = range.startAyah,
        loop = range.loop,
      )
    } else {
      range
    }

    _uiState.update { it.copy(activeRange = normalized) }
    val reciter = _uiState.value.currentReciter ?: return
    playSurahInternal(
      reciter = reciter,
      surahId = normalized.startSurah,
      startPositionMs = 0L,
      targetAyah = normalized.startAyah,
      autoPlay = true,
    )
  }

  fun clearRange() {
    _uiState.update { it.copy(activeRange = null) }
  }

  fun playSurah(
    reciter: VerifiedReciter,
    surahId: Int,
    startPositionMs: Long = 0L,
    autoPlay: Boolean = true,
    clearActiveRange: Boolean = false,
  ) {
    if (clearActiveRange) {
      _uiState.update { it.copy(activeRange = null) }
    }
    playSurahInternal(
      reciter = reciter,
      surahId = surahId,
      startPositionMs = startPositionMs,
      targetAyah = null,
      autoPlay = autoPlay,
    )
  }

  fun playFromAyah(surahId: Int, ayahNumber: Int, endAyahNumber: Int? = null) {
    val reciter = _uiState.value.currentReciter ?: return
    if (endAyahNumber != null) {
      playRange(
        RecitationRange(
          startSurah = surahId,
          startAyah = ayahNumber,
          endSurah = surahId,
          endAyah = endAyahNumber,
          loop = _uiState.value.activeRange?.loop ?: false,
        ),
      )
    } else {
      _uiState.update { it.copy(activeRange = null) }
      playSurahInternal(
        reciter = reciter,
        surahId = surahId,
        startPositionMs = 0L,
        targetAyah = ayahNumber,
        autoPlay = true,
      )
    }
  }

  private fun playSurahInternal(
    reciter: VerifiedReciter,
    surahId: Int,
    startPositionMs: Long,
    targetAyah: Int?,
    autoPlay: Boolean,
  ) {
    if (!reciter.isPlayable || !reciter.availableSurahs.contains(surahId)) {
      _uiState.update {
        it.copy(errorMessage = "السورة غير متوفرة في مصحف هذا القارئ")
      }
      return
    }

    val surahName = surahNamesMap[surahId].orEmpty()
    _uiState.update {
      it.copy(
        currentReciter = reciter,
        currentSurahId = surahId,
        currentSurahName = surahName,
        isBuffering = true,
        errorMessage = null,
        activePlayingVerseKey = null,
      )
    }

    scope.launch {
      val localFile = audioCacheManager.getLocalAudioFileIfValid(reciter.key, surahId)
      var isOffline = false
      var resolved: com.example.data.audio.ResolvedRecitationAudio? = null

      if (localFile != null) {
        isOffline = true
        val localTimings = audioRepository.resolveLocalTimingsOnlyIfMatched(reciter, surahId)
        resolved = if (localTimings.isNotEmpty()) {
          com.example.data.audio.ResolvedRecitationAudio.SingleTimedTrack(
            streamUrl = Uri.fromFile(localFile).toString(),
            ayahTimings = localTimings,
            timingSourceLabel = "ملف محلي متزامن",
            matchesSelectedMoshafUrl = true,
          )
        } else {
          com.example.data.audio.ResolvedRecitationAudio.UntimedSingleTrack(
            streamUrl = Uri.fromFile(localFile).toString(),
          )
        }
      } else {
        resolved = audioRepository.resolveRecitationAudio(reciter, surahId)
      }

      if (resolved == null) {
        _uiState.update {
          it.copy(
            isBuffering = false,
            isPlaying = false,
            errorMessage = "رابط التلاوة غير متاح حالياً من الخادم",
          )
        }
        return@launch
      }

      val resolvedTitle = surahName.ifBlank { "سورة $surahId" }
      val metadata = MediaMetadata.Builder()
        .setTitle(resolvedTitle)
        .setArtist(reciter.displayName)
        .setAlbumTitle("قبسات — حفص عن عاصم")
        .build()

      val itemsToPlay: List<MediaItem>
      val startItemIndex: Int
      val initialPosMs: Long
      val hasTiming: Boolean

      when (resolved) {
        is com.example.data.audio.ResolvedRecitationAudio.SingleTimedTrack -> {
          isVerseByVerseMode = false
          currentVersePlaylist = emptyList()
          currentAyahTimings = resolved.ayahTimings.filter { it.ayah > 0 }.sortedBy { it.start_time }
          hasTiming = currentAyahTimings.isNotEmpty()
          startItemIndex = 0
          initialPosMs = if (targetAyah != null && currentAyahTimings.isNotEmpty()) {
            currentAyahTimings.firstOrNull { it.ayah == targetAyah }?.start_time ?: startPositionMs
          } else {
            startPositionMs
          }
          itemsToPlay = listOf(
            MediaItem.Builder()
              .setMediaId("${reciter.key}_$surahId")
              .setUri(Uri.parse(resolved.streamUrl))
              .setMediaMetadata(metadata)
              .build(),
          )
        }

        is com.example.data.audio.ResolvedRecitationAudio.VerseByVersePlaylist -> {
          isVerseByVerseMode = true
          currentAyahTimings = emptyList()
          currentVersePlaylist = resolved.segments
          hasTiming = currentVersePlaylist.isNotEmpty()
          startItemIndex = if (targetAyah != null) {
            currentVersePlaylist.indexOfFirst { it.ayahNumber == targetAyah }.coerceAtLeast(0)
          } else {
            0
          }
          initialPosMs = 0L
          itemsToPlay = currentVersePlaylist.map { seg ->
            MediaItem.Builder()
              .setMediaId("$surahId:${seg.ayahNumber}")
              .setUri(Uri.parse(seg.audioUrl))
              .setMediaMetadata(metadata)
              .build()
          }
        }

        is com.example.data.audio.ResolvedRecitationAudio.UntimedSingleTrack -> {
          isVerseByVerseMode = false
          currentVersePlaylist = emptyList()
          currentAyahTimings = emptyList()
          hasTiming = false
          startItemIndex = 0
          initialPosMs = startPositionMs
          if (targetAyah != null) {
            _uiState.update {
              it.copy(errorMessage = "توقيت الآيات غير متوفر لهذا القارئ؛ يتم تشغيل السورة كاملة")
            }
          }
          itemsToPlay = listOf(
            MediaItem.Builder()
              .setMediaId("${reciter.key}_$surahId")
              .setUri(Uri.parse(resolved.streamUrl))
              .setMediaMetadata(metadata)
              .build(),
          )
        }
      }

      val activePlayer = player
      if (activePlayer != null) {
        playbackService?.configureMetadata(
          title = resolvedTitle,
          artist = reciter.displayName,
          onPrevSurah = { prevSurah() },
          onNextSurah = { nextSurah() },
        )
        activePlayer.setMediaItems(itemsToPlay, startItemIndex, initialPosMs)
        activePlayer.playbackParameters = PlaybackParameters(_uiState.value.playbackSpeed)
        activePlayer.prepare()
        if (autoPlay) {
          ensureStartedForPlayback()
          activePlayer.play()
        }
      } else {
        pendingMediaQueue = PendingMediaQueue(
          items = itemsToPlay,
          startIndex = startItemIndex,
          startPositionMs = initialPosMs,
          autoPlay = autoPlay,
          title = resolvedTitle,
          artist = reciter.displayName,
        )
      }

      _uiState.update {
        it.copy(
          isOfflineSource = isOffline,
          hasAyahTiming = hasTiming,
          positionMs = initialPosMs,
        )
      }
      if (isVerseByVerseMode) {
        val initialSeg = currentVersePlaylist.getOrNull(startItemIndex)
        val initialKey = initialSeg?.let { "$surahId:${it.ayahNumber}" }
        emitActiveVerseKey(initialKey)
      } else {
        updateActiveVerseForPosition(initialPosMs)
      }

      preferences.saveAudioPlaybackState(
        reciterKey = reciter.key,
        surahId = surahId,
        positionMs = initialPosMs,
      )
    }
  }

  fun togglePlayPause() {
    val activePlayer = player ?: return
    if (activePlayer.mediaItemCount == 0) {
      val state = _uiState.value
      val range = state.activeRange
      val reciter = state.currentReciter ?: return
      if (range != null) {
        playRange(range)
      } else {
        playSurahInternal(reciter, state.currentSurahId, state.positionMs, targetAyah = null, autoPlay = true)
      }
      return
    }
    if (activePlayer.isPlaying) {
      activePlayer.pause()
    } else {
      ensureStartedForPlayback()
      activePlayer.play()
    }
  }

  fun seekTo(positionMs: Long) {
    val activePlayer = player ?: return
    val clamped = positionMs.coerceAtLeast(0L)
    activePlayer.seekTo(clamped)
    _uiState.update { it.copy(positionMs = clamped) }
    if (isVerseByVerseMode) {
      updateActiveVerseFromPlaylist(activePlayer)
    } else {
      updateActiveVerseForPosition(clamped)
    }
    persistCurrentPosition()
  }

  fun seekByDelta(deltaMs: Long) {
    val current = player?.currentPosition ?: _uiState.value.positionMs
    val maxDur = player?.duration?.takeIf { it > 0L } ?: Long.MAX_VALUE
    seekTo((current + deltaMs).coerceIn(0L, maxDur))
  }

  fun setSpeed(speed: Float) {
    val clamped = speed.coerceIn(0.75f, 1.25f)
    player?.playbackParameters = PlaybackParameters(clamped)
    _uiState.update { it.copy(playbackSpeed = clamped) }
    scope.launch { preferences.saveAudioPlaybackState(speed = clamped) }
  }

  fun nextSurah() {
    val state = _uiState.value
    val reciter = state.currentReciter ?: return
    val next = reciter.availableSurahs.filter { it > state.currentSurahId }.minOrNull() ?: return
    playSurah(reciter, next, 0L, autoPlay = true, clearActiveRange = true)
  }

  fun prevSurah() {
    val state = _uiState.value
    val reciter = state.currentReciter ?: return
    val prev = reciter.availableSurahs.filter { it < state.currentSurahId }.maxOrNull() ?: return
    playSurah(reciter, prev, 0L, autoPlay = true, clearActiveRange = true)
  }

  fun clearError() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  private fun stopPositionTicker() {
    tickerJob?.cancel()
    tickerJob = null
  }

  private fun stopPositionTickerIfIdle() {
    val active = player
    if (active == null || (!active.isPlaying && active.playbackState != Player.STATE_BUFFERING)) {
      stopPositionTicker()
    }
  }

  private fun startPositionTicker() {
    if (tickerJob?.isActive == true) return
    tickerJob = scope.launch(Dispatchers.Main) {
      var saveCounter = 0
      while (true) {
        val activePlayer = player ?: break
        val isActivePlayback = activePlayer.isPlaying || activePlayer.playbackState == Player.STATE_BUFFERING
        if (!isActivePlayback) {
          break
        }
        if (activePlayer.mediaItemCount > 0) {
          val pos = activePlayer.currentPosition.coerceAtLeast(0L)
          val dur = activePlayer.duration.takeIf { it > 0L } ?: _uiState.value.durationMs
          val cur = _uiState.value
          if (pos != cur.positionMs || dur != cur.durationMs) {
            _uiState.update { it.copy(positionMs = pos, durationMs = dur) }
          }
          if (isVerseByVerseMode) {
            updateActiveVerseFromPlaylist(activePlayer)
            checkVersePlaylistRangeBoundary(activePlayer)
          } else {
            updateActiveVerseForPosition(pos)
            checkRangeBoundary(pos, activePlayer)
          }

          if (activePlayer.isPlaying) {
            saveCounter++
            if (saveCounter >= 8) {
              saveCounter = 0
              persistCurrentPosition()
            }
          }
        }
        delay(PROGRESS_TICK_MS)
      }
      tickerJob = null
    }
  }

  private fun checkVersePlaylistRangeBoundary(activePlayer: Player) {
    val range = _uiState.value.activeRange ?: return
    if (!activePlayer.isPlaying) return
    val curSurah = _uiState.value.currentSurahId
    if (curSurah != range.endSurah) return
    val idx = activePlayer.currentMediaItemIndex
    val curAyah = currentVersePlaylist.getOrNull(idx)?.ayahNumber ?: return
    if (curAyah > range.endAyah) {
      if (range.loop) {
        if (range.startSurah == range.endSurah) {
          val startIdx = currentVersePlaylist.indexOfFirst { it.ayahNumber == range.startAyah }.coerceAtLeast(0)
          activePlayer.seekTo(startIdx, 0L)
        } else {
          playRange(range)
        }
      } else {
        activePlayer.pause()
      }
    }
  }

  private fun checkRangeBoundary(positionMs: Long, activePlayer: Player) {
    val range = _uiState.value.activeRange ?: return
    if (!activePlayer.isPlaying) return
    val curSurah = _uiState.value.currentSurahId
    if (curSurah != range.endSurah || currentAyahTimings.isEmpty()) return

    val endTiming = currentAyahTimings.firstOrNull { it.ayah == range.endAyah } ?: return
    if (endTiming.end_time > 0L && positionMs >= endTiming.end_time) {
      if (range.loop) {
        if (range.startSurah == range.endSurah) {
          val startMs = currentAyahTimings.firstOrNull { it.ayah == range.startAyah }?.start_time ?: 0L
          activePlayer.seekTo(startMs)
        } else {
          playRange(range)
        }
      } else {
        activePlayer.pause()
      }
    }
  }

  private fun updateActiveVerseFromPlaylist(activePlayer: Player?) {
    if (activePlayer == null || currentVersePlaylist.isEmpty()) {
      emitActiveVerseKey(null)
      return
    }
    val mediaIdKey = activePlayer.currentMediaItem?.mediaId?.takeIf { it.contains(":") }
    val resolvedKey = mediaIdKey ?: currentVersePlaylist
      .getOrNull(activePlayer.currentMediaItemIndex)
      ?.let { "${_uiState.value.currentSurahId}:${it.ayahNumber}" }
    emitActiveVerseKey(resolvedKey)
  }

  private fun updateActiveVerseForPosition(positionMs: Long) {
    if (currentAyahTimings.isEmpty()) {
      emitActiveVerseKey(null)
      return
    }
    val exactMatch = currentAyahTimings.firstOrNull { t ->
      t.ayah > 0 && positionMs >= t.start_time && (t.end_time <= t.start_time || positionMs < t.end_time)
    }
    val match = exactMatch ?: currentAyahTimings.lastOrNull { t ->
      t.ayah > 0 && positionMs >= t.start_time
    }?.takeIf { candidate ->
      val next = currentAyahTimings.firstOrNull { it.start_time > candidate.start_time }
      next == null || (next.start_time - positionMs) <= 2500L
    }
    val newKey = match?.let { "${_uiState.value.currentSurahId}:${it.ayah}" }
    emitActiveVerseKey(newKey)
  }

  private fun emitActiveVerseKey(newKey: String?) {
    if (newKey != _uiState.value.activePlayingVerseKey) {
      _uiState.update { it.copy(activePlayingVerseKey = newKey) }
      onActiveVerseChangedCallback?.invoke(newKey)
    }
  }

  private fun persistCurrentPosition() {
    val state = _uiState.value
    scope.launch {
      preferences.saveAudioPlaybackState(
        reciterKey = state.currentReciter?.key,
        surahId = state.currentSurahId,
        positionMs = state.positionMs,
      )
    }
  }

  fun release() {
    persistCurrentPosition()
    tickerJob?.cancel()
    player?.removeListener(playerListener)
    if (isBound) {
      runCatching { context.unbindService(serviceConnection) }
      isBound = false
    }
    playbackService = null
    player = null
  }
}
