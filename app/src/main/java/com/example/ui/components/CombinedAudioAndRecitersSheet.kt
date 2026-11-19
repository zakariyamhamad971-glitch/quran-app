package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.audio.VerifiedReciter
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CombinedAudioAndRecitersSheet(
  audioState: AudioPlaybackUiState,
  primaryReciters: List<VerifiedReciter>,
  additionalReciters: List<VerifiedReciter>,
  isLoading: Boolean,
  errorMessage: String?,
  onTogglePlayPause: () -> Unit,
  onSeekTo: (Long) -> Unit,
  onSeekDelta: (Long) -> Unit,
  onNextSurah: () -> Unit,
  onPrevSurah: () -> Unit,
  onCycleSpeed: () -> Unit,
  onSelectReciter: (VerifiedReciter) -> Unit,
  onOpenRangePicker: () -> Unit,
  onDismiss: () -> Unit,
) {
  BackHandler(onBack = onDismiss)
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var searchQuery by remember { mutableStateOf("") }

  val allReciters = remember(primaryReciters, additionalReciters) {
    primaryReciters + additionalReciters
  }
  val filteredReciters = remember(allReciters, searchQuery) {
    if (searchQuery.isBlank()) allReciters
    else allReciters.filter { it.displayName.contains(searchQuery, ignoreCase = true) }
  }

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

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.75f)
        .navigationBarsPadding()
        .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
      // 3-a) Compact playback mini-bar at the top of the sheet
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp),
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              modifier = Modifier.weight(1f),
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
                    Spacer(Modifier.width(4.dp))
                    Icon(
                      imageVector = Icons.Filled.OfflinePin,
                      contentDescription = "يعمل دون اتصال",
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(13.dp),
                    )
                  }
                }
                Text(
                  text = playbackStatusText,
                  style = MaterialTheme.typography.labelSmall,
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
              horizontalArrangement = Arrangement.spacedBy(4.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              // Range Picker Button ("تحديد الآيات")
              Surface(
                modifier = Modifier
                  .clip(RoundedCornerShape(10.dp))
                  .clickable(onClick = onOpenRangePicker)
                  .testTag("audio_range_button"),
                color = if (audioState.activeRange != null) {
                  MaterialTheme.colorScheme.primaryContainer
                } else {
                  MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(10.dp),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Icon(
                    imageVector = Icons.Filled.PlaylistPlay,
                    contentDescription = null,
                    tint = if (audioState.activeRange != null) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(15.dp),
                  )
                  Spacer(Modifier.width(4.dp))
                  Text(
                    text = audioState.activeRange?.let { "${it.startSurah}:${it.startAyah}-${it.endSurah}:${it.endAyah}" } ?: "تحديد الآيات",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (audioState.activeRange != null) {
                      MaterialTheme.colorScheme.primary
                    } else {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                  )
                }
              }

              // Speed button
              Surface(
                modifier = Modifier
                  .clip(RoundedCornerShape(10.dp))
                  .clickable(onClick = onCycleSpeed)
                  .testTag("audio_speed_button"),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
              ) {
                Text(
                  text = String.format(Locale.US, "%.2fx", audioState.playbackSpeed),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                )
              }
            }
          }

          Spacer(Modifier.height(4.dp))

          // Playback timeline slider
          val duration = audioState.durationMs.coerceAtLeast(1L)
          val position = audioState.positionMs.coerceIn(0L, duration)
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = formatDurationMs(position),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
              value = position.toFloat(),
              onValueChange = { newVal -> onSeekTo(newVal.toLong()) },
              valueRange = 0f..duration.toFloat(),
              colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
              ),
              modifier = Modifier
                .weight(1f)
                .height(20.dp)
                .padding(horizontal = 6.dp)
                .testTag("audio_slider"),
            )
            Text(
              text = formatDurationMs(duration),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }

          // Controls row: Previous Surah, Replay 10s, Play/Pause, Forward 10s, Next Surah
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            IconButton(
              onClick = onPrevSurah,
              modifier = Modifier.size(32.dp),
            ) {
              Icon(Icons.Filled.SkipPrevious, contentDescription = "السورة السابقة", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
              onClick = { onSeekDelta(-10000L) },
              modifier = Modifier.size(32.dp),
            ) {
              Icon(Icons.Filled.Replay10, contentDescription = "تأخير 10 ثواني", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
              onClick = onTogglePlayPause,
              modifier = Modifier
                .size(40.dp)
                .testTag("audio_play_pause_button"),
            ) {
              if (audioState.isBuffering) {
                CircularProgressIndicator(
                  strokeWidth = 2.dp,
                  color = MaterialTheme.colorScheme.onPrimary,
                  modifier = Modifier.size(20.dp),
                )
              } else {
                Icon(
                  imageVector = if (audioState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                  contentDescription = if (audioState.isPlaying) "إيقاف مؤقت" else "تشغيل",
                  modifier = Modifier.size(22.dp),
                )
              }
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
              onClick = { onSeekDelta(10000L) },
              modifier = Modifier.size(32.dp),
            ) {
              Icon(Icons.Filled.Forward10, contentDescription = "تقديم 10 ثواني", tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.width(8.dp))
            IconButton(
              onClick = onNextSurah,
              modifier = Modifier.size(32.dp),
            ) {
              Icon(Icons.Filled.SkipNext, contentDescription = "السورة التالية", tint = MaterialTheme.colorScheme.primary)
            }
          }
        }
      }

      Spacer(Modifier.height(10.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
      Spacer(Modifier.height(8.dp))

      // 3-b) Reciters search and list
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("بحث عن قارئ...") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
      )

      Spacer(Modifier.height(8.dp))

      if (isLoading && allReciters.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          items(filteredReciters, key = { it.key }) { reciter ->
            val isSelected = audioState.currentReciter?.key == reciter.key
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
              } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
              },
              border = BorderStroke(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
              ),
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onSelectReciter(reciter) }
                .testTag("reciter_row_${reciter.key}"),
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(10.dp),
                  modifier = Modifier.weight(1f),
                ) {
                  if (isSelected) {
                    Surface(
                      shape = CircleShape,
                      color = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(20.dp),
                    ) {
                      Box(contentAlignment = Alignment.Center) {
                        Icon(
                          imageVector = Icons.Filled.Check,
                          contentDescription = "القارئ الحالي",
                          tint = MaterialTheme.colorScheme.onPrimary,
                          modifier = Modifier.size(12.dp),
                        )
                      }
                    }
                  }
                  Column {
                    Text(
                      text = reciter.displayName,
                      style = MaterialTheme.typography.titleSmall,
                      color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                      text = reciter.statusNote,
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
