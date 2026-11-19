package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.audio.RecitationRange
import com.example.data.quran.SurahMeta
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
fun ReaderBottomAudioButtons(
  isPlaying: Boolean,
  isBuffering: Boolean,
  onOpenReciters: () -> Unit,
  onOpenPlayback: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Row(
    modifier = modifier
      .windowInsetsPadding(WindowInsets.navigationBars)
      .padding(bottom = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(16.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
      tonalElevation = 3.dp,
      shadowElevation = 3.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)),
      modifier = Modifier
        .size(48.dp)
        .clip(CircleShape)
        .clickable(role = Role.Button, onClick = onOpenReciters)
        .testTag("bottom_reciters_button"),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Filled.Person,
          contentDescription = "القرّاء",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(22.dp),
        )
      }
    }

    Surface(
      shape = CircleShape,
      color = if (isPlaying) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.86f)
      } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
      },
      tonalElevation = 3.dp,
      shadowElevation = 3.dp,
      border = BorderStroke(
        width = if (isPlaying) 1.5.dp else 1.dp,
        color = MaterialTheme.colorScheme.primary.copy(alpha = if (isPlaying) 0.75f else 0.28f),
      ),
      modifier = Modifier
        .size(48.dp)
        .clip(CircleShape)
        .clickable(role = Role.Button, onClick = onOpenPlayback)
        .testTag("bottom_playback_button"),
    ) {
      Box(contentAlignment = Alignment.Center) {
        if (isBuffering) {
          CircularProgressIndicator(
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
        } else {
          Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "التشغيل",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackControlSheet(
  audioState: AudioPlaybackUiState,
  surahs: List<SurahMeta>,
  onTogglePlayPause: () -> Unit,
  onSeekTo: (Long) -> Unit,
  onSeekDelta: (Long) -> Unit,
  onNextSurah: () -> Unit,
  onPrevSurah: () -> Unit,
  onCycleSpeed: () -> Unit,
  onPlayFromAyah: (surahId: Int, ayahNumber: Int) -> Unit,
  onPlayRange: (RecitationRange) -> Unit,
  onClearRange: () -> Unit,
  onDismiss: () -> Unit,
) {
  BackHandler(onBack = onDismiss)
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  val surahsById = remember(surahs) { surahs.associateBy { it.id } }
  val activeRange = audioState.activeRange
  var startSurahId by remember(activeRange, audioState.currentSurahId) {
    mutableIntStateOf(activeRange?.startSurah ?: audioState.currentSurahId)
  }
  var startAyahText by remember(activeRange) {
    mutableStateOf((activeRange?.startAyah ?: 1).toString())
  }
  var endSurahId by remember(activeRange, audioState.currentSurahId) {
    mutableIntStateOf(activeRange?.endSurah ?: audioState.currentSurahId)
  }
  var endAyahText by remember(activeRange, audioState.currentSurahId, surahs) {
    val maxAyah = surahsById[activeRange?.endSurah ?: audioState.currentSurahId]?.versesCount ?: 7
    mutableStateOf((activeRange?.endAyah ?: maxAyah).toString())
  }
  var loopRange by remember(activeRange) {
    mutableStateOf(activeRange?.loop ?: false)
  }

  var pickingStartSurah by remember { mutableStateOf(false) }
  var pickingEndSurah by remember { mutableStateOf(false) }
  var pickingStartAyah by remember { mutableStateOf(false) }
  var pickingEndAyah by remember { mutableStateOf(false) }

  val startMaxAyah = surahsById[startSurahId]?.versesCount ?: 286
  val endMaxAyah = surahsById[endSurahId]?.versesCount ?: 286

  val reciterName = audioState.currentReciter?.displayName ?: "لم يتم اختيار قارئ"
  val surahLabel = if (audioState.currentSurahName.isNotBlank()) {
    "سورة ${audioState.currentSurahName}"
  } else {
    "سورة ${audioState.currentSurahId}"
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
        append("جاهز للتشغيل")
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
        .fillMaxHeight(0.88f)
        .padding(horizontal = 16.dp)
        .verticalScroll(rememberScrollState()),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "التشغيل",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "القارئ: $reciterName",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("playback_current_reciter_name"),
          )
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Filled.Close, contentDescription = "إغلاق")
        }
      }

      Spacer(Modifier.height(10.dp))

      // Current Surah + Status + Speed
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
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
                    text = surahLabel,
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
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }

            Surface(
              modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onCycleSpeed)
                .testTag("audio_speed_button"),
              color = MaterialTheme.colorScheme.secondaryContainer,
              shape = RoundedCornerShape(12.dp),
            ) {
              Text(
                text = "السرعة ${audioState.playbackSpeed}x",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
              )
            }
          }

          if (audioState.errorMessage != null) {
            Spacer(Modifier.height(4.dp))
            Text(
              text = audioState.errorMessage,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.error,
            )
          }

          Spacer(Modifier.height(8.dp))
          AudioSeekBarSection(
            positionMs = audioState.positionMs,
            durationMs = audioState.durationMs,
            onSeekTo = onSeekTo,
          )
          Spacer(Modifier.height(6.dp))

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
                .size(50.dp)
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

      Spacer(Modifier.height(12.dp))

      // Ayah Navigation & Repeat section (no reciter list here)
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "الانتقال بين الآيات والتكرار",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.primary,
            )
            if (activeRange != null) {
              TextButton(
                onClick = onClearRange,
                modifier = Modifier.testTag("clear_recitation_range"),
              ) {
                Text("إلغاء النطاق")
              }
            }
          }

          Spacer(Modifier.height(8.dp))

          RangeEndpointRow(
            label = "البداية:",
            surah = surahsById[startSurahId],
            surahId = startSurahId,
            ayahText = startAyahText,
            maxAyah = startMaxAyah,
            ayahInputTag = "range_start_ayah_input",
            onOpenSurahPicker = { pickingStartSurah = true },
            onOpenAyahGrid = { pickingStartAyah = true },
            onAyahTextChange = { startAyahText = it },
            onDecrementAyah = {
              val cur = (startAyahText.toIntOrNull() ?: 1).coerceIn(1, startMaxAyah)
              startAyahText = (cur - 1).coerceAtLeast(1).toString()
            },
            onIncrementAyah = {
              val cur = (startAyahText.toIntOrNull() ?: 1).coerceIn(1, startMaxAyah)
              startAyahText = (cur + 1).coerceAtMost(startMaxAyah).toString()
            },
          )

          Spacer(Modifier.height(8.dp))

          RangeEndpointRow(
            label = "النهاية:",
            surah = surahsById[endSurahId],
            surahId = endSurahId,
            ayahText = endAyahText,
            maxAyah = endMaxAyah,
            ayahInputTag = "range_end_ayah_input",
            onOpenSurahPicker = { pickingEndSurah = true },
            onOpenAyahGrid = { pickingEndAyah = true },
            onAyahTextChange = { endAyahText = it },
            onDecrementAyah = {
              val cur = (endAyahText.toIntOrNull() ?: endMaxAyah).coerceIn(1, endMaxAyah)
              endAyahText = (cur - 1).coerceAtLeast(1).toString()
            },
            onIncrementAyah = {
              val cur = (endAyahText.toIntOrNull() ?: endMaxAyah).coerceIn(1, endMaxAyah)
              endAyahText = (cur + 1).coerceAtMost(endMaxAyah).toString()
            },
          )

          Spacer(Modifier.height(8.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Icon(
                imageVector = Icons.Filled.Repeat,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(6.dp))
              Text("تكرار النطاق", style = MaterialTheme.typography.bodyMedium)
            }
            Switch(
              checked = loopRange,
              onCheckedChange = { loopRange = it },
              modifier = Modifier.testTag("range_loop_switch"),
            )
          }

          Spacer(Modifier.height(8.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = {
                val sAyah = (startAyahText.toIntOrNull() ?: 1).coerceIn(1, startMaxAyah)
                onPlayFromAyah(startSurahId, sAyah)
              },
              modifier = Modifier
                .weight(1f)
                .testTag("play_from_start_ayah_button"),
            ) {
              Text("ابدأ من الآية", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(
              onClick = {
                val sAyah = (startAyahText.toIntOrNull() ?: 1).coerceIn(1, startMaxAyah)
                val eAyah = (endAyahText.toIntOrNull() ?: endMaxAyah).coerceIn(1, endMaxAyah)
                onPlayRange(
                  RecitationRange(
                    startSurah = startSurahId,
                    startAyah = sAyah,
                    endSurah = endSurahId,
                    endAyah = eAyah,
                    loop = loopRange,
                  ),
                )
              },
              modifier = Modifier
                .weight(1.1f)
                .testTag("play_selected_range_button"),
            ) {
              Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(4.dp))
              Text("تشغيل النطاق", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }

      Spacer(Modifier.height(24.dp))
    }
  }

  if (pickingStartSurah) {
    SearchableSurahPickerDialog(
      title = "اختر سورة البداية",
      surahs = surahs,
      selectedSurahId = startSurahId,
      onSelect = { s ->
        startSurahId = s.id
        startAyahText = "1"
        if (endSurahId < s.id) {
          endSurahId = s.id
          endAyahText = s.versesCount.toString()
        }
        pickingStartSurah = false
      },
      onDismiss = { pickingStartSurah = false },
    )
  }

  if (pickingEndSurah) {
    SearchableSurahPickerDialog(
      title = "اختر سورة النهاية",
      surahs = surahs,
      selectedSurahId = endSurahId,
      onSelect = { s ->
        endSurahId = s.id
        endAyahText = s.versesCount.toString()
        pickingEndSurah = false
      },
      onDismiss = { pickingEndSurah = false },
    )
  }

  if (pickingStartAyah) {
    AyahGridPickerDialog(
      title = "اختر آية البداية — ${surahsById[startSurahId]?.nameArabic ?: ""}",
      versesCount = startMaxAyah,
      selectedAyah = (startAyahText.toIntOrNull() ?: 1).coerceIn(1, startMaxAyah),
      onSelect = { ayah ->
        startAyahText = ayah.toString()
        if (endSurahId == startSurahId && (endAyahText.toIntOrNull() ?: 1) < ayah) {
          endAyahText = endMaxAyah.toString()
        }
        pickingStartAyah = false
      },
      onDismiss = { pickingStartAyah = false },
    )
  }

  if (pickingEndAyah) {
    AyahGridPickerDialog(
      title = "اختر آية النهاية — ${surahsById[endSurahId]?.nameArabic ?: ""}",
      versesCount = endMaxAyah,
      selectedAyah = (endAyahText.toIntOrNull() ?: endMaxAyah).coerceIn(1, endMaxAyah),
      onSelect = { ayah ->
        endAyahText = ayah.toString()
        pickingEndAyah = false
      },
      onDismiss = { pickingEndAyah = false },
    )
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
