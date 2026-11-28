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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.audio.RecitationRange
import com.example.data.quran.DownloadedTafsirEdition
import com.example.data.quran.SurahMeta
import com.example.ui.theme.AmiriFontFamily
import com.example.viewmodel.VerseInsightsUiState

private enum class InsightsSheetTab {
  GLOBE,
  PLAY,
}

enum class InsightsSourceFilter {
  ALL,
  TAFSIR,
  TRANSLATION,
}

private fun toArabicIndicDigitsSheet(number: Int): String {
  val digits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
  val raw = number.toString()
  return buildString(raw.length) {
    for (ch in raw) {
      append(if (ch in '0'..'9') digits[ch - '0'] else ch)
    }
  }
}

@Composable
fun VerseInsightsSheet(
  state: VerseInsightsUiState,
  surahsById: Map<Int, SurahMeta>,
  downloadedSources: List<DownloadedTafsirEdition>,
  checkedEditionIds: Set<String>,
  onToggleSourceChecked: (editionId: String, checked: Boolean) -> Unit,
  onOpenTafsirManager: () -> Unit,
  onPlaySingleAyah: (surahId: Int, ayahNumber: Int) -> Unit,
  onPlayFromAyah: (surahId: Int, ayahNumber: Int) -> Unit,
  onPlayAyahRange: (RecitationRange) -> Unit,
  onOpenRangePicker: () -> Unit,
  onDismiss: () -> Unit,
  onSheetTopInRootChanged: (Float?) -> Unit = {},
  initialSourceFilter: InsightsSourceFilter = InsightsSourceFilter.ALL,
  modifier: Modifier = Modifier,
) {
  if (state is VerseInsightsUiState.Hidden) {
    DisposableEffect(Unit) {
      onSheetTopInRootChanged(null)
      onDispose {}
    }
    return
  }
  DisposableEffect(Unit) {
    onDispose { onSheetTopInRootChanged(null) }
  }
  BackHandler(onBack = onDismiss)

  var activeTab by remember { mutableStateOf(InsightsSheetTab.GLOBE) }
  var sourceFilter by remember(initialSourceFilter) { mutableStateOf(initialSourceFilter) }
  var sourcesDropdownExpanded by remember { mutableStateOf(false) }
  val consumeTapInteraction = remember { MutableInteractionSource() }

  Surface(
    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 4.dp,
    shadowElevation = 14.dp,
    modifier = modifier
      .fillMaxWidth()
      .fillMaxHeight(0.47f)
      .onGloballyPositioned { coords ->
        if (coords.isAttached) {
          onSheetTopInRootChanged(coords.positionInRoot().y)
        }
      }
      .clickable(
        interactionSource = consumeTapInteraction,
        indication = null,
        onClick = {},
      )
      .testTag("verse_insights_bottom_sheet"),
  ) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .windowInsetsPadding(WindowInsets.navigationBars),
      ) {
        // Drag handle pill
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            modifier = Modifier
              .width(36.dp)
              .height(4.dp)
              .clip(CircleShape)
              .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)),
          )
        }

        // Top tabs bar: Play, Globe, Collapse Arrow
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            SheetTabButton(
              selected = activeTab == InsightsSheetTab.GLOBE,
              icon = Icons.Filled.Language,
              label = "الترجمات والتفسير",
              onClick = { activeTab = InsightsSheetTab.GLOBE },
              testTag = "insights_tab_globe",
            )
            SheetTabButton(
              selected = activeTab == InsightsSheetTab.PLAY,
              icon = Icons.Filled.PlayArrow,
              label = "التلاوة",
              onClick = { activeTab = InsightsSheetTab.PLAY },
              testTag = "insights_tab_play",
            )
          }

          IconButton(
            onClick = onDismiss,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("insights_collapse_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.KeyboardArrowDown,
              contentDescription = "إغلاق النافذة",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))

        when (state) {
          is VerseInsightsUiState.Loading -> {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 18.dp, vertical = 14.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center,
            ) {
              Text(
                text = "سورة ${state.surahName} • الآية ${toArabicIndicDigitsSheet(state.ayahNumber)}",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = AmiriFontFamily),
                color = MaterialTheme.colorScheme.primary,
              )
              Spacer(Modifier.height(16.dp))
              CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
              Spacer(Modifier.height(10.dp))
              Text(
                text = "جاري تجهيز الآيات والمصادر...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          is VerseInsightsUiState.Error -> {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
              Text(
                text = "الآية ${state.verseKey}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
              )
              Spacer(Modifier.height(10.dp))
              Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
              )
            }
          }

          is VerseInsightsUiState.Ready -> {
            val insights = state.insights
            if (activeTab == InsightsSheetTab.GLOBE) {
              val completeDownloaded = remember(downloadedSources, sourceFilter) {
                downloadedSources.filter { it.isComplete || it.completedSurahs > 0 }.filter { src ->
                  when (sourceFilter) {
                    InsightsSourceFilter.ALL -> true
                    InsightsSourceFilter.TAFSIR -> src.type == com.example.data.quran.InsightSourceType.TAFSIR || src.type == com.example.data.quran.InsightSourceType.IRAB
                    InsightsSourceFilter.TRANSLATION -> src.type == com.example.data.quran.InsightSourceType.TRANSLATION
                  }
                }
              }
              val checkedCount = completeDownloaded.count { it.editionId in checkedEditionIds }

              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                listOf(
                  InsightsSourceFilter.ALL to "الكل",
                  InsightsSourceFilter.TAFSIR to "تفسير",
                  InsightsSourceFilter.TRANSLATION to "ترجمة",
                ).forEach { (filter, label) ->
                  val selected = sourceFilter == filter
                  Surface(
                    onClick = { sourceFilter = filter },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) {
                      MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                    } else {
                      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                    border = BorderStroke(
                      1.dp,
                      if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                    ),
                    modifier = Modifier.testTag("insights_filter_${filter.name.lowercase()}"),
                  ) {
                    Text(
                      text = label,
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                  }
                }
              }

              // Dropdown selector bar + downloaded sources checkboxes + "المزيد من الترجمات والتفاسير"
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 16.dp, vertical = 8.dp),
              ) {
                Surface(
                  onClick = { sourcesDropdownExpanded = true },
                  shape = RoundedCornerShape(12.dp),
                  color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                  border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
                  modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("insights_sources_dropdown_button"),
                ) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    val summaryText = when {
                      completeDownloaded.isEmpty() -> "اختر مصادر التفسير والترجمة (لا توجد ملفات محملة)"
                      checkedCount == 0 -> "اختر من المصادر المحملة (${toArabicIndicDigitsSheet(completeDownloaded.size)})"
                      else -> "المصادر المحددة (${toArabicIndicDigitsSheet(checkedCount)})"
                    }
                    Text(
                      text = summaryText,
                      style = MaterialTheme.typography.labelLarge,
                      color = MaterialTheme.colorScheme.onSurface,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f),
                    )
                    Icon(
                      imageVector = Icons.Filled.ArrowDropDown,
                      contentDescription = "فتح قائمة المصادر",
                      tint = MaterialTheme.colorScheme.primary,
                    )
                  }
                }

                DropdownMenu(
                  expanded = sourcesDropdownExpanded,
                  onDismissRequest = { sourcesDropdownExpanded = false },
                  modifier = Modifier
                    .fillMaxWidth(0.86f)
                    .testTag("insights_sources_dropdown_menu"),
                ) {
                  for (src in completeDownloaded) {
                    val isChecked = src.editionId in checkedEditionIds
                    DropdownMenuItem(
                      text = {
                        Row(
                          verticalAlignment = Alignment.CenterVertically,
                          modifier = Modifier.fillMaxWidth(),
                        ) {
                          Checkbox(
                            checked = isChecked,
                            onCheckedChange = null,
                          )
                          Spacer(Modifier.width(8.dp))
                          Column(modifier = Modifier.weight(1f)) {
                            Text(
                              text = src.nameAr,
                              style = MaterialTheme.typography.bodyMedium,
                              color = MaterialTheme.colorScheme.onSurface,
                              maxLines = 1,
                              overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                              text = "${src.type.labelAr} • ${src.languageLabelAr}",
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                          }
                        }
                      },
                      onClick = {
                        onToggleSourceChecked(src.editionId, !isChecked)
                      },
                      modifier = Modifier.testTag("insights_source_checkbox_${src.slug}"),
                    )
                  }
                  if (completeDownloaded.isNotEmpty()) {
                    HorizontalDivider()
                  }
                  DropdownMenuItem(
                    text = {
                      Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                          imageVector = Icons.Filled.CloudDownload,
                          contentDescription = null,
                          tint = MaterialTheme.colorScheme.primary,
                          modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                          text = "المزيد من الترجمات والتفاسير",
                          style = MaterialTheme.typography.titleSmall,
                          color = MaterialTheme.colorScheme.primary,
                          fontWeight = FontWeight.Bold,
                        )
                      }
                    },
                    onClick = {
                      sourcesDropdownExpanded = false
                      onOpenTafsirManager()
                    },
                    modifier = Modifier.testTag("insights_more_translations_row"),
                  )
                }
              }

              // Scrollable sequential ayahs & checked source texts
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .weight(1f)
                  .padding(horizontal = 16.dp)
                  .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                if (insights.referenceSummary.isNotBlank()) {
                  Text(
                    text = insights.referenceSummary,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = AmiriFontFamily),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                  )
                }

                if (completeDownloaded.isEmpty()) {
                  Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth(),
                  ) {
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                      verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      Text(
                        text = "لم تقم بتنزيل أي مصدر تفسير أو ترجمة بعد. يمكنك تنزيلها للقراءة دون اتصال من مدير الترجمات والتفاسير.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                      )
                      FilledTonalButton(
                        onClick = onOpenTafsirManager,
                        modifier = Modifier.testTag("insights_empty_open_manager_button"),
                      ) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("المزيد من الترجمات والتفاسير")
                      }
                    }
                  }
                } else if (checkedCount == 0) {
                  Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                  ) {
                    Text(
                      text = "حدّد مصدراً واحداً على الأقل من القائمة أعلاه لعرض التفسير أو الترجمة أسفل كل آية.",
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      modifier = Modifier.padding(12.dp),
                    )
                  }
                }

                for (section in insights.sections) {
                  Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)),
                    modifier = Modifier
                      .fillMaxWidth()
                      .testTag("insights_ayah_section_${section.verseKey}"),
                  ) {
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                      verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                      // Ayah exact text from official data files
                      Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.38f),
                        modifier = Modifier.fillMaxWidth(),
                      ) {
                        Column(
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                          Text(
                            text = "سورة ${section.surahName} • آية ${toArabicIndicDigitsSheet(section.ayahNumber)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                          )
                          Spacer(Modifier.height(4.dp))
                          Text(
                            text = if (section.exactAyahText.isNotBlank()) {
                              "${section.exactAyahText} ﴿${toArabicIndicDigitsSheet(section.ayahNumber)}﴾"
                            } else {
                              "﴿${toArabicIndicDigitsSheet(section.ayahNumber)}﴾"
                            },
                            style = MaterialTheme.typography.titleMedium.copy(
                              fontFamily = AmiriFontFamily,
                              lineHeight = 30.sp,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                          )
                        }
                      }

                      val filteredEntries = remember(section.sourceEntries, sourceFilter) {
                        when (sourceFilter) {
                          InsightsSourceFilter.ALL -> section.sourceEntries
                          InsightsSourceFilter.TAFSIR -> section.sourceEntries.filter {
                            it.typeLabelAr == "تفسير" || it.typeLabelAr == "إعراب"
                          }
                          InsightsSourceFilter.TRANSLATION -> section.sourceEntries.filter { it.typeLabelAr == "ترجمة" }
                        }
                      }
                      if (filteredEntries.isEmpty() && checkedCount > 0) {
                        Text(
                          text = when (sourceFilter) {
                            InsightsSourceFilter.TAFSIR -> "لا توجد مصادر تفسير محددة لهذه الآية."
                            InsightsSourceFilter.TRANSLATION -> "لا توجد مصادر ترجمة محددة لهذه الآية."
                            InsightsSourceFilter.ALL -> ""
                          },
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                      // Each checked source text for this ayah in order
                      for (entry in filteredEntries) {
                        Column(
                          modifier = Modifier.fillMaxWidth(),
                          verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                          Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                          ) {
                            Text(
                              text = entry.sourceTitle,
                              style = MaterialTheme.typography.labelLarge,
                              fontWeight = FontWeight.Bold,
                              color = MaterialTheme.colorScheme.secondary,
                              modifier = Modifier.weight(1f),
                            )
                            Text(
                              text = "${entry.typeLabelAr} • ${entry.languageLabelAr}",
                              style = MaterialTheme.typography.labelSmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                          }
                          val dir = if (entry.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr
                          CompositionLocalProvider(LocalLayoutDirection provides dir) {
                            Text(
                              text = entry.text.ifBlank { "غير متوفر لهذه الآية في هذا المصدر." },
                              style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 25.sp),
                              color = MaterialTheme.colorScheme.onSurface,
                              modifier = Modifier.fillMaxWidth(),
                            )
                          }
                        }
                        if (entry != filteredEntries.last()) {
                          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                        }
                      }
                    }
                  }
                }

                Spacer(Modifier.height(20.dp))
              }
            } else {
              // PLAY tab
              val maxSurahAyahs = surahsById[insights.surahId]?.versesCount ?: insights.ayahNumber
              var quickEndAyah by remember(insights.verseKey, maxSurahAyahs) {
                mutableIntStateOf(minOf(insights.ayahNumber + 4, maxSurahAyahs))
              }
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .weight(1f)
                  .padding(horizontal = 18.dp, vertical = 10.dp)
                  .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Text(
                  text = insights.referenceSummary.ifBlank {
                    "سورة ${state.surahName} • الآية ${toArabicIndicDigitsSheet(insights.ayahNumber)}"
                  },
                  style = MaterialTheme.typography.titleLarge.copy(fontFamily = AmiriFontFamily),
                  color = MaterialTheme.colorScheme.primary,
                )

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
                        text = "تلاوة من آية ${toArabicIndicDigitsSheet(insights.ayahNumber)} إلى آية:",
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
                          text = toArabicIndicDigitsSheet(quickEndAyah),
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
                        Text("تشغيل (${toArabicIndicDigitsSheet(insights.ayahNumber)} - ${toArabicIndicDigitsSheet(quickEndAyah)})")
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
              }
            }
          }

          VerseInsightsUiState.Hidden -> Unit
        }
      }
    }
  }
}

@Composable
private fun SheetTabButton(
  selected: Boolean,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  onClick: () -> Unit,
  testTag: String,
) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(12.dp),
    color = if (selected) {
      MaterialTheme.colorScheme.primaryContainer
    } else {
      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    },
    border = if (selected) {
      BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
    } else {
      null
    },
    modifier = Modifier
      .minimumInteractiveComponentSize()
      .testTag(testTag),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
