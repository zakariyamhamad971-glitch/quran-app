package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.player.AudioPlaybackUiState
import com.example.ui.theme.AmiriFontFamily
import java.util.Locale

private fun formatDurationMs(ms: Long): String {
  if (ms <= 0L) return "00:00"
  val totalSec = ms / 1000L
  val hours = totalSec / 3600L
  val mins = (totalSec % 3600L) / 60L
  val secs = totalSec % 60L
  return if (hours > 0L) {
    String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
  } else {
    String.format(Locale.US, "%02d:%02d", mins, secs)
  }
}

@Composable
fun PersistentAudioBar(
  audioState: AudioPlaybackUiState,
  onTogglePlayPause: () -> Unit,
  onSeekTo: (Long) -> Unit,
  onSeekDelta: (Long) -> Unit,
  onNextSurah: () -> Unit,
  onPrevSurah: () -> Unit,
  onCycleSpeed: () -> Unit,
  onOpenReciterPicker: () -> Unit,
  modifier: Modifier = Modifier,
  onOpenRangePicker: () -> Unit = onOpenReciterPicker,
) {
  val playbackStatusText = remember(
    audioState.activeRange,
    audioState.activePlayingVerseKey,
    audioState.isPlaying,
    audioState.hasAyahTiming,
  ) {
    buildString {
      val activeKey = audioState.activePlayingVerseKey
      if (activeKey != null) {
        append("الآية ${activeKey.substringAfter(":")}")
      } else if (audioState.isPlaying && !audioState.hasAyahTiming) {
        append("تشغيل متصل")
      } else {
        append("لوحة التشغيل")
      }
      audioState.activeRange?.let { r ->
        append(" • نطاق ${r.startSurah}:${r.startAyah}-${r.endSurah}:${r.endAyah}")
      }
    }
  }
  val reciterName = audioState.currentReciter?.displayName ?: "اختر القارئ"
  val noopInteraction = remember { MutableInteractionSource() }

  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clickable(interactionSource = noopInteraction, indication = null, onClick = {})
      .testTag("persistent_audio_bar"),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 6.dp,
    shadowElevation = 8.dp,
    shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
      // Dedicated Reciter Selection element (separate from playback controls)
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .clickable(onClick = onOpenReciterPicker)
          .testTag("reciter_picker_button"),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(12.dp),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 7.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.Person,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text = "القارئ:",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text = reciterName,
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Icon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = "فتح قائمة القراء",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
      }

      Spacer(Modifier.height(6.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
      Spacer(Modifier.height(6.dp))

      // Playback settings & info header: Surah + status + Ayah Range + Speed
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          modifier = Modifier
            .weight(1f)
            .padding(horizontal = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Icon(
            imageVector = Icons.Filled.GraphicEq,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(6.dp))
          Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = if (audioState.currentSurahName.isNotBlank()) {
                  "سورة ${audioState.currentSurahName}"
                } else {
                  "سورة ${audioState.currentSurahId}"
                },
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = AmiriFontFamily),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (audioState.isOfflineSource) {
                Spacer(Modifier.width(6.dp))
                Icon(
                  imageVector = Icons.Filled.OfflinePin,
                  contentDescription = "يعمل دون اتصال",
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(14.dp),
                )
              }
            }
            Text(
              text = playbackStatusText,
              style = MaterialTheme.typography.labelMedium,
              color = if (audioState.activePlayingVerseKey != null) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Surface(
            modifier = Modifier
              .clip(RoundedCornerShape(12.dp))
              .clickable(onClick = onOpenRangePicker)
              .testTag("audio_range_button"),
            color = if (audioState.activeRange != null) {
              MaterialTheme.colorScheme.primaryContainer
            } else {
              MaterialTheme.colorScheme.surfaceVariant
            },
            shape = RoundedCornerShape(12.dp),
          ) {
            val rangeLabel = audioState.activeRange?.let { r ->
              "${r.startSurah}:${r.startAyah}-${r.endSurah}:${r.endAyah}"
            } ?: "تحديد الآيات"
            Text(
              text = rangeLabel,
              style = MaterialTheme.typography.labelMedium,
              color = if (audioState.activeRange != null) {
                MaterialTheme.colorScheme.onPrimaryContainer
              } else {
                MaterialTheme.colorScheme.onSurfaceVariant
              },
              modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            )
          }

          // Speed toggle pill (0.75x .. 1.25x)
          Surface(
            modifier = Modifier
              .clip(RoundedCornerShape(12.dp))
              .clickable(onClick = onCycleSpeed)
              .testTag("audio_speed_button"),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
          ) {
            Text(
              text = "${audioState.playbackSpeed}x",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
          }
        }
      }

      if (audioState.errorMessage != null) {
        Text(
          text = audioState.errorMessage,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
      }

      AudioSeekBarSection(
        positionMs = audioState.positionMs,
        durationMs = audioState.durationMs,
        onSeekTo = onSeekTo,
      )

      // Playback controls row: Prev Surah, -10s, Play/Pause, +10s, Next Surah
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(
          onClick = onPrevSurah,
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("audio_prev_surah"),
        ) {
          Icon(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = "السورة السابقة",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }

        IconButton(
          onClick = { onSeekDelta(-10_000L) },
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("audio_back_10s"),
        ) {
          Icon(
            imageVector = Icons.Filled.Replay10,
            contentDescription = "تأخير ١٠ ثوانٍ",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }

        FilledIconButton(
          onClick = onTogglePlayPause,
          colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
          ),
          modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .testTag("audio_play_pause"),
        ) {
          if (audioState.isBuffering) {
            CircularProgressIndicator(
              color = MaterialTheme.colorScheme.onPrimary,
              strokeWidth = 2.dp,
              modifier = Modifier.size(22.dp),
            )
          } else {
            Icon(
              imageVector = if (audioState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
              contentDescription = "تشغيل أو إيقاف مؤقت",
            )
          }
        }

        IconButton(
          onClick = { onSeekDelta(10_000L) },
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("audio_forward_10s"),
        ) {
          Icon(
            imageVector = Icons.Filled.Forward10,
            contentDescription = "تقديم ١٠ ثوانٍ",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }

        IconButton(
          onClick = onNextSurah,
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("audio_next_surah"),
        ) {
          Icon(
            imageVector = Icons.Filled.SkipPrevious,
            contentDescription = "السورة التالية",
            tint = MaterialTheme.colorScheme.onSurface,
          )
        }
      }
    }
  }
}

@Composable
private fun AudioSeekBarSection(
  positionMs: Long,
  durationMs: Long,
  onSeekTo: (Long) -> Unit,
) {
  var isDragging by remember { mutableStateOf(false) }
  var dragFraction by remember { mutableFloatStateOf(0f) }

  val currentFraction = if (durationMs > 0L) {
    (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
  } else {
    0f
  }
  val displayedFraction = if (isDragging) dragFraction else currentFraction

  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = formatDurationMs(
        if (isDragging) (dragFraction * durationMs).toLong() else positionMs,
      ),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Slider(
      value = displayedFraction,
      onValueChange = { f ->
        isDragging = true
        dragFraction = f
      },
      onValueChangeFinished = {
        isDragging = false
        if (durationMs > 0L) {
          onSeekTo((dragFraction * durationMs).toLong())
        }
      },
      enabled = durationMs > 0L,
      colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.secondary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
      ),
      modifier = Modifier
        .weight(1f)
        .height(28.dp)
        .padding(horizontal = 6.dp)
        .testTag("audio_seek_slider"),
    )
    Text(
      text = formatDurationMs(durationMs),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
