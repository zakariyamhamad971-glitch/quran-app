package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.audio.RecitationRange
import com.example.data.quran.SurahMeta
import com.example.ui.theme.AmiriFontFamily
import com.example.viewmodel.VerseInsightsUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerseInsightsSheet(
  state: VerseInsightsUiState,
  surahsById: Map<Int, SurahMeta>,
  onPlaySingleAyah: (surahId: Int, ayahNumber: Int) -> Unit,
  onPlayFromAyah: (surahId: Int, ayahNumber: Int) -> Unit,
  onPlayAyahRange: (RecitationRange) -> Unit,
  onOpenRangePicker: () -> Unit,
  onDismiss: () -> Unit,
) {
  if (state is VerseInsightsUiState.Hidden) return
  BackHandler(onBack = onDismiss)
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showEnglishTafsir by remember { mutableStateOf(false) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.86f)
        .padding(horizontal = 18.dp)
        .verticalScroll(rememberScrollState()),
    ) {
      when (state) {
        is VerseInsightsUiState.Loading -> {
          Text(
            text = "سورة ${state.surahName} • الآية ${state.ayahNumber}",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = AmiriFontFamily),
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.height(28.dp))
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 32.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
              Spacer(Modifier.height(10.dp))
              Text(
                text = "جاري جلب الترجمات وتفسير ابن كثير...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        is VerseInsightsUiState.Error -> {
          Text(
            text = "الآية ${state.verseKey}",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.height(12.dp))
          Text(
            text = state.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
          )
        }

        is VerseInsightsUiState.Ready -> {
          val insights = state.insights
          Text(
            text = "سورة ${state.surahName} • الآية ${insights.ayahNumber}",
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = AmiriFontFamily),
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.height(8.dp))

          val maxSurahAyahs = surahsById[insights.surahId]?.versesCount ?: insights.ayahNumber
          var quickEndAyah by remember(insights.verseKey, maxSurahAyahs) {
            mutableIntStateOf(minOf(insights.ayahNumber + 4, maxSurahAyahs))
          }

          // Direct verse recitation action buttons
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            FilledTonalButton(
              onClick = {
                onPlaySingleAyah(insights.surahId, insights.ayahNumber)
                onDismiss()
              },
              modifier = Modifier
                .weight(1f)
                .testTag("insights_play_single_ayah"),
            ) {
              Icon(Icons.Filled.PlayArrow, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("تلاوة هذه الآية")
            }
            OutlinedButton(
              onClick = {
                onPlayFromAyah(insights.surahId, insights.ayahNumber)
                onDismiss()
              },
              modifier = Modifier
                .weight(1f)
                .testTag("insights_play_from_ayah"),
            ) {
              Icon(Icons.Filled.PlaylistPlay, contentDescription = null)
              Spacer(Modifier.width(4.dp))
              Text("ابدأ التلاوة من هنا")
            }
          }

          Spacer(Modifier.height(8.dp))
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.padding(10.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "تلاوة من آية ${insights.ayahNumber} إلى آية:",
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                  IconButton(
                    onClick = {
                      quickEndAyah = (quickEndAyah - 1).coerceAtLeast(insights.ayahNumber)
                    },
                  ) {
                    Icon(Icons.Filled.Remove, contentDescription = "إنقاص آية النهاية")
                  }
                  Text(
                    text = "$quickEndAyah",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  IconButton(
                    onClick = {
                      quickEndAyah = (quickEndAyah + 1).coerceAtMost(maxSurahAyahs)
                    },
                  ) {
                    Icon(Icons.Filled.Add, contentDescription = "زيادة آية النهاية")
                  }
                }
              }
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Button(
                  onClick = {
                    onPlayAyahRange(
                      RecitationRange(
                        startSurah = insights.surahId,
                        startAyah = insights.ayahNumber,
                        endSurah = insights.surahId,
                        endAyah = quickEndAyah,
                      ),
                    )
                    onDismiss()
                  },
                  modifier = Modifier.testTag("insights_play_range_button"),
                ) {
                  Text("تشغيل (${insights.ayahNumber} - $quickEndAyah)")
                }
                TextButton(
                  onClick = {
                    onDismiss()
                    onOpenRangePicker()
                  },
                ) {
                  Icon(Icons.Filled.Tune, contentDescription = null)
                  Spacer(Modifier.width(4.dp))
                  Text("نطاق بسورة أخرى")
                }
              }
            }
          }

          if (state.pageTypeface != null && insights.codeV2Line.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            val qcfFont = remember(state.pageTypeface) { FontFamily(state.pageTypeface) }
            Surface(
              color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Text(
                  text = insights.codeV2Line,
                  style = TextStyle(
                    fontFamily = qcfFont,
                    fontSize = 20.sp,
                    textAlign = TextAlign.Center,
                    textDirection = TextDirection.Rtl,
                  ),
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                )
              }
            }
          }

          Spacer(Modifier.height(16.dp))
          Text(
            text = "الترجمات المعتمدة (English • Français • Español)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.height(8.dp))

          for (tr in insights.translations) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = "${tr.languageLabelAr} (${tr.languageCode})",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                  )
                  Text(
                    text = tr.translatorName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Spacer(Modifier.height(6.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                  Text(
                    text = tr.text.ifBlank { "غير متوفر لهذه الآية" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                }
              }
            }
          }

          Spacer(Modifier.height(16.dp))
          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
          Spacer(Modifier.height(14.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "تفسير ابن كثير",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(
                selected = !showEnglishTafsir,
                onClick = { showEnglishTafsir = false },
                label = { Text("العربية") },
              )
              FilterChip(
                selected = showEnglishTafsir,
                onClick = { showEnglishTafsir = true },
                label = { Text("English") },
              )
            }
          }

          Spacer(Modifier.height(8.dp))

          val tafsirTitle = if (showEnglishTafsir) insights.tafsirEnglishTitle else insights.tafsirArabicTitle
          val tafsirBody = if (showEnglishTafsir) insights.tafsirEnglishText else insights.tafsirArabicText
          val tafsirDir = if (showEnglishTafsir) LayoutDirection.Ltr else LayoutDirection.Rtl

          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxWidth(),
          ) {
            CompositionLocalProvider(LocalLayoutDirection provides tafsirDir) {
              Column(modifier = Modifier.padding(14.dp)) {
                Text(
                  text = tafsirTitle,
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                  text = tafsirBody.ifBlank { "التفسير غير متوفر لهذه الآية حالياً." },
                  style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }

          Spacer(Modifier.height(28.dp))
        }

        VerseInsightsUiState.Hidden -> Unit
      }
    }
  }
}
