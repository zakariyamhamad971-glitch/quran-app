package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.audio.RecitationRange
import com.example.data.audio.ReciterMatchStatus
import com.example.data.audio.VerifiedReciter
import com.example.data.quran.SurahMeta
import com.example.ui.theme.AmiriFontFamily

enum class AudioPickerSheetMode {
  RECITERS_ONLY,
  AYAH_RANGE_ONLY,
  COMBINED,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReciterPickerSheet(
  primaryReciters: List<VerifiedReciter>,
  additionalReciters: List<VerifiedReciter>,
  activeReciter: VerifiedReciter?,
  currentSurahId: Int,
  activeRange: RecitationRange?,
  surahs: List<SurahMeta>,
  isLoading: Boolean,
  errorMessage: String?,
  onSelectReciter: (VerifiedReciter) -> Unit,
  onPlayFromAyah: (surahId: Int, ayahNumber: Int) -> Unit,
  onPlayRange: (RecitationRange) -> Unit,
  onClearRange: () -> Unit,
  onDismiss: () -> Unit,
  mode: AudioPickerSheetMode = AudioPickerSheetMode.COMBINED,
) {
  BackHandler(onBack = onDismiss)
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showAllCatalog by remember { mutableStateOf(false) }

  val surahsById = remember(surahs) { surahs.associateBy { it.id } }
  var startSurahId by remember(activeRange, currentSurahId) {
    mutableIntStateOf(activeRange?.startSurah ?: currentSurahId)
  }
  var startAyahText by remember(activeRange) {
    mutableStateOf((activeRange?.startAyah ?: 1).toString())
  }
  var endSurahId by remember(activeRange, currentSurahId) {
    mutableIntStateOf(activeRange?.endSurah ?: currentSurahId)
  }
  var endAyahText by remember(activeRange, currentSurahId, surahs) {
    val maxAyah = surahsById[activeRange?.endSurah ?: currentSurahId]?.versesCount ?: 7
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

  val displayed = remember(primaryReciters, additionalReciters, showAllCatalog) {
    if (showAllCatalog) primaryReciters + additionalReciters else primaryReciters
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    val sheetHeightModifier = if (mode == AudioPickerSheetMode.AYAH_RANGE_ONLY) {
      Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(bottom = 24.dp)
    } else {
      Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.92f)
        .padding(horizontal = 16.dp)
    }
    Column(
      modifier = sheetHeightModifier,
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = when (mode) {
              AudioPickerSheetMode.RECITERS_ONLY -> "اختيار القارئ"
              AudioPickerSheetMode.AYAH_RANGE_ONLY -> "تحديد الآيات"
              AudioPickerSheetMode.COMBINED -> "القراء ونطاق الآيات والسور"
            },
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = when (mode) {
              AudioPickerSheetMode.RECITERS_ONLY -> "اختر القارئ للتلاوة الصوتية"
              AudioPickerSheetMode.AYAH_RANGE_ONLY -> "حدد سورة وآية البداية والنهاية"
              AudioPickerSheetMode.COMBINED -> "اختر القارئ وحدد سورة وآية البداية والنهاية"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Filled.Close, contentDescription = "إغلاق")
        }
      }
      Spacer(Modifier.height(10.dp))

      if (mode != AudioPickerSheetMode.RECITERS_ONLY) {
        // Range selection: Where recitation starts & ends
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
          modifier = Modifier.fillMaxWidth(),
        ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "تحديد البداية والنهاية (من آية إلى آية)",
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

          // Start Surah + Start Ayah
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

          // End Surah + End Ayah
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
              Spacer(Modifier.width(6.dp))
              Switch(
                checked = loopRange,
                onCheckedChange = { loopRange = it },
                modifier = Modifier.testTag("range_loop_switch"),
              )
            }
          }

          Spacer(Modifier.height(6.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            OutlinedButton(
              onClick = {
                val sAyah = (startAyahText.toIntOrNull() ?: 1).coerceIn(1, startMaxAyah)
                onPlayFromAyah(startSurahId, sAyah)
                onDismiss()
              },
              modifier = Modifier
                .weight(1f)
                .testTag("play_from_start_ayah_button"),
            ) {
              Text("ابدأ من البداية فقط", maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                onDismiss()
              },
              modifier = Modifier
                .weight(1.1f)
                .testTag("play_selected_range_button"),
            ) {
              Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(4.dp))
              Text("تشغيل من - إلى", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
          }
        }
      }
      }

      if (mode != AudioPickerSheetMode.AYAH_RANGE_ONLY) {
        if (mode == AudioPickerSheetMode.COMBINED) {
          Spacer(Modifier.height(10.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
          selected = !showAllCatalog,
          onClick = { showAllCatalog = false },
          label = { Text("القائمة المعتمدة (${primaryReciters.size})") },
        )
        if (additionalReciters.isNotEmpty()) {
          FilterChip(
            selected = showAllCatalog,
            onClick = { showAllCatalog = true },
            label = { Text("جميع القراء (${primaryReciters.size + additionalReciters.size})") },
          )
        }
      }

      Spacer(Modifier.height(6.dp))

      when {
        isLoading -> {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
          }
        }

        errorMessage != null -> {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = errorMessage,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }

        else -> {
          LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
          ) {
            items(displayed, key = { "${it.orderIndex}_${it.key}" }, contentType = { "reciter_row" }) { reciter ->
              val isSelected = activeReciter?.key == reciter.key && reciter.isPlayable
              val enabled = reciter.isPlayable
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .alpha(if (enabled) 1f else 0.52f)
                  .clickable(enabled = enabled) {
                    onSelectReciter(reciter)
                  }
                  .padding(horizontal = 8.dp, vertical = 11.dp)
                  .testTag("reciter_row_${reciter.orderIndex}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = reciter.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                  )
                  Spacer(Modifier.height(2.dp))
                  Text(
                    text = reciter.statusNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (reciter.status == ReciterMatchStatus.VERIFIED) {
                      MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                      MaterialTheme.colorScheme.error
                    },
                  )
                }

                Spacer(Modifier.width(8.dp))

                if (isSelected) {
                  Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                  ) {
                    Icon(
                      imageVector = Icons.Filled.Check,
                      contentDescription = "القارئ المختار",
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier
                        .padding(6.dp)
                        .size(18.dp),
                    )
                  }
                } else if (!enabled) {
                  Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = "غير متاح",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                  )
                }
              }
              HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            }
          }
        }
      }
      }
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
private fun RangeEndpointRow(
  label: String,
  surah: SurahMeta?,
  surahId: Int,
  ayahText: String,
  maxAyah: Int,
  ayahInputTag: String,
  onOpenSurahPicker: () -> Unit,
  onOpenAyahGrid: () -> Unit,
  onAyahTextChange: (String) -> Unit,
  onDecrementAyah: () -> Unit,
  onIncrementAyah: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.width(46.dp),
      )

      OutlinedButton(
        onClick = onOpenSurahPicker,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
        modifier = Modifier.weight(1.25f),
      ) {
        Text(
          text = surah?.let { "${it.id}. ${it.nameArabic}" } ?: "سورة $surahId",
          style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AmiriFontFamily),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
      }

      // Ayah picker & stepper
      Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
        modifier = Modifier.weight(1.05f),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
          modifier = Modifier.padding(horizontal = 2.dp),
        ) {
          IconButton(onClick = onDecrementAyah, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "إنقاص رقم الآية", modifier = Modifier.size(16.dp))
          }
          Text(
            text = "آية ${ayahText.ifBlank { "1" }}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
              .clip(RoundedCornerShape(6.dp))
              .clickable(onClick = onOpenAyahGrid)
              .padding(horizontal = 6.dp, vertical = 6.dp),
          )
          IconButton(onClick = onIncrementAyah, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "زيادة رقم الآية", modifier = Modifier.size(16.dp))
          }
        }
      }
    }

    Spacer(Modifier.height(4.dp))
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = 54.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = ayahText,
        onValueChange = { v -> onAyahTextChange(v.filter { it.isDigit() }) },
        label = { Text("أو اكتب رقم الآية (١-$maxAyah)") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
          .weight(1f)
          .testTag(ayahInputTag),
      )
      TextButton(onClick = onOpenAyahGrid) {
        Text("قائمة الآيات")
      }
    }
  }
}

@Composable
private fun SearchableSurahPickerDialog(
  title: String,
  surahs: List<SurahMeta>,
  selectedSurahId: Int,
  onSelect: (SurahMeta) -> Unit,
  onDismiss: () -> Unit,
) {
  var query by remember { mutableStateOf("") }
  val filtered = remember(surahs, query) {
    val q = query.trim()
    if (q.isBlank()) {
      surahs
    } else {
      surahs.filter { s ->
        s.nameArabic.contains(q, ignoreCase = true) || s.id.toString() == q
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, style = MaterialTheme.typography.titleMedium) },
    text = {
      Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
          value = query,
          onValueChange = { query = it },
          placeholder = { Text("ابحث باسم السورة أو رقمها") },
          leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
          items(filtered, key = { it.id }, contentType = { "surah_picker_row" }) { surah ->
            val selected = surah.id == selectedSurahId
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(surah) }
                .padding(horizontal = 8.dp, vertical = 10.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = "${surah.id}. سورة ${surah.nameArabic}",
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = AmiriFontFamily),
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "${surah.versesCount} آية",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("إلغاء")
      }
    },
  )
}

@Composable
private fun AyahGridPickerDialog(
  title: String,
  versesCount: Int,
  selectedAyah: Int,
  onSelect: (Int) -> Unit,
  onDismiss: () -> Unit,
) {
  val ayahs = remember(versesCount) { (1..versesCount.coerceAtLeast(1)).toList() }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(title, style = MaterialTheme.typography.titleMedium) },
    text = {
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 54.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 320.dp),
      ) {
        items(ayahs, key = { it }, contentType = { "ayah_grid_cell" }) { num ->
          val isSelected = num == selectedAyah
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
              .clip(RoundedCornerShape(10.dp))
              .clickable { onSelect(num) },
          ) {
            Text(
              text = num.toString(),
              textAlign = TextAlign.Center,
              style = MaterialTheme.typography.labelLarge,
              color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
              modifier = Modifier.padding(vertical = 10.dp),
            )
          }
        }
      }
    },
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text("إغلاق")
      }
    },
  )
}
