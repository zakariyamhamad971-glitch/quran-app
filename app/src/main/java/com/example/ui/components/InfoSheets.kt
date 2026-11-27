package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.integrity.AssetHashStatus
import com.example.data.pdf.ColorSwatchStyle
import com.example.data.pdf.ColorsGuideParser
import com.example.data.pdf.RiwayaColorGuideSection
import com.example.data.quran.CreditSourceInfo
import com.example.data.quran.TajweedRuleInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TajweedLegendSheet(
  rules: List<TajweedRuleInfo>,
  colorsGuideSections: List<RiwayaColorGuideSection> = emptyList(),
  colorsGuideLoading: Boolean = false,
  colorsGuideError: String? = null,
  activeMushafIsPdf: Boolean = false,
  activePdfMushafId: String? = null,
  activePdfRiwayaAr: String? = null,
  onRetryColorsGuide: () -> Unit = {},
  onDismiss: () -> Unit,
) {
  BackHandler(onBack = onDismiss)
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val listState = rememberLazyListState()

  val matchedSectionIndex = remember(
    colorsGuideSections,
    activeMushafIsPdf,
    activePdfMushafId,
    activePdfRiwayaAr,
  ) {
    if (!activeMushafIsPdf || colorsGuideSections.isEmpty()) {
      -1
    } else {
      val normActiveId = ColorsGuideParser.normalizeId(activePdfMushafId)
      val byId = if (normActiveId.isNotEmpty()) {
        colorsGuideSections.indexOfFirst {
          ColorsGuideParser.normalizeId(it.id) == normActiveId
        }
      } else {
        -1
      }
      if (byId >= 0) {
        byId
      } else {
        val trimmedRiwaya = activePdfRiwayaAr?.trim().orEmpty()
        if (trimmedRiwaya.isNotEmpty()) {
          colorsGuideSections.indexOfFirst { it.riwayaAr.trim() == trimmedRiwaya }
        } else {
          -1
        }
      }
    }
  }

  var hafsExpanded by remember { mutableStateOf(true) }
  val expandedRiwayaKeys = remember { mutableStateMapOf<String, Boolean>() }
  var autoScrolledMatchKey by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(matchedSectionIndex, colorsGuideSections) {
    if (matchedSectionIndex >= 0 && matchedSectionIndex < colorsGuideSections.size) {
      val matched = colorsGuideSections[matchedSectionIndex]
      val key = "${matchedSectionIndex}_${matched.id}"
      expandedRiwayaKeys[key] = true
      if (autoScrolledMatchKey != key) {
        autoScrolledMatchKey = key
        // Index 0 in LazyColumn is the Hafs section; matched riwaya starts at 1 + matchedSectionIndex
        listState.scrollToItem(1 + matchedSectionIndex)
      }
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 18.dp),
      ) {
        Text(
          text = "دليل الألوان",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.testTag("colors_guide_title"),
        )
        Spacer(Modifier.height(6.dp))
        Surface(
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = "معاني الألوان تختلف من رواية لأخرى، فراجع قسم الرواية التي تقرأ بها",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .testTag("colors_guide_top_note"),
          )
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false),
          contentPadding = PaddingValues(bottom = 24.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          item(key = "hafs_tajweed_section") {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
              border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
              ),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("colors_guide_hafs_section"),
            ) {
              Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable { hafsExpanded = !hafsExpanded }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = "مصحف التجويد (حفص عن عاصم)",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                  )
                  Icon(
                    imageVector = if (hafsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (hafsExpanded) "طي القسم" else "توسيع القسم",
                    tint = MaterialTheme.colorScheme.primary,
                  )
                }

                AnimatedVisibility(visible = hafsExpanded) {
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                  ) {
                    rules.forEachIndexed { idx, rule ->
                      val fallbackColor = MaterialTheme.colorScheme.primary
                      val swatch = remember(rule.colorHex, fallbackColor) {
                        parseGuideColorOrNull(rule.colorHex) ?: fallbackColor
                      }

                      Row(
                        modifier = Modifier
                          .fillMaxWidth()
                          .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.Top,
                      ) {
                        Box(
                          modifier = Modifier
                            .padding(top = 4.dp)
                            .size(18.dp)
                            .background(swatch, CircleShape),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                          Text(
                            text = rule.nameAr,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                          )
                          Text(
                            text = rule.descriptionAr,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                        }
                      }
                      if (idx < rules.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                      }
                    }
                  }
                }
              }
            }
          }

          if (colorsGuideLoading && colorsGuideSections.isEmpty()) {
            item(key = "colors_guide_loading") {
              Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.Center,
                ) {
                  CircularProgressIndicator(
                    strokeWidth = 2.5.dp,
                    modifier = Modifier.size(20.dp),
                  )
                  Spacer(Modifier.width(10.dp))
                  Text(
                    text = "جاري تحميل دليل الألوان للروايات...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          } else if (colorsGuideError != null && colorsGuideSections.isEmpty()) {
            item(key = "colors_guide_error") {
              Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("colors_guide_error_card"),
              ) {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  Text(
                    text = "تعذر تحميل دليل الألوان، تحقق من الاتصال",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Spacer(Modifier.height(10.dp))
                  Button(
                    onClick = onRetryColorsGuide,
                    modifier = Modifier.testTag("colors_guide_retry_button"),
                  ) {
                    Icon(
                      imageVector = Icons.Filled.Refresh,
                      contentDescription = null,
                      modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("إعادة المحاولة")
                  }
                }
              }
            }
          } else {
            itemsIndexed(
              items = colorsGuideSections,
              key = { idx, sec -> "riwaya_${idx}_${sec.id}" },
            ) { idx, section ->
              val sectionKey = "${idx}_${section.id}"
              val isMatchedActive = idx == matchedSectionIndex
              val isExpanded = expandedRiwayaKeys[sectionKey] ?: isMatchedActive

              Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (isMatchedActive) {
                  MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                } else {
                  MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                },
                border = BorderStroke(
                  width = if (isMatchedActive) 1.5.dp else 1.dp,
                  color = if (isMatchedActive) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                  } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                  },
                ),
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(14.dp))
                  .testTag("colors_guide_section_${section.id}"),
              ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .heightIn(min = 48.dp)
                      .clickable { expandedRiwayaKeys[sectionKey] = !isExpanded }
                      .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        text = section.riwayaAr,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (isMatchedActive) {
                          MaterialTheme.colorScheme.primary
                        } else {
                          MaterialTheme.colorScheme.onSurface
                        },
                      )
                      if (isMatchedActive) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                          text = "المصحف المفتوح حاليًا",
                          style = MaterialTheme.typography.labelSmall,
                          color = MaterialTheme.colorScheme.primary,
                        )
                      }
                    }
                    Icon(
                      imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                      contentDescription = if (isExpanded) "طي القسم" else "توسيع القسم",
                      tint = MaterialTheme.colorScheme.primary,
                    )
                  }

                  AnimatedVisibility(visible = isExpanded) {
                    Column(
                      modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
                    ) {
                      section.items.forEachIndexed { itemIdx, item ->
                        val fallbackColor = MaterialTheme.colorScheme.primary
                        val swatch = remember(item.colorHex, fallbackColor) {
                          parseGuideColorOrNull(item.colorHex) ?: fallbackColor
                        }

                        Row(
                          modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                          verticalAlignment = Alignment.CenterVertically,
                        ) {
                          if (item.style == ColorSwatchStyle.RING) {
                            Box(
                              modifier = Modifier
                                .size(18.dp)
                                .border(width = 2.dp, color = swatch, shape = CircleShape),
                            )
                          } else {
                            Box(
                              modifier = Modifier
                                .size(18.dp)
                                .background(swatch, CircleShape),
                            )
                          }
                          Spacer(Modifier.width(12.dp))
                          Text(
                            text = item.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                          )
                        }
                        if (itemIdx < section.items.lastIndex) {
                          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
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
    }
  }
}

private fun parseGuideColorOrNull(rawHex: String): Color? {
  val trimmed = rawHex.trim()
  if (trimmed.isEmpty()) return null
  val normalized = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
  return runCatching { Color(android.graphics.Color.parseColor(normalized)) }.getOrNull()
}

@Composable
fun CreditsAndIntegrityDialog(
  credits: List<CreditSourceInfo>,
  assetHashes: List<AssetHashStatus>,
  tajweedRenderPathLabel: String = "",
  androidApiLevelText: String = "",
  onDismiss: () -> Unit,
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = "المصادر والتراخيص والتحقق",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
      )
    },
    text = {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
      ) {
        if (tajweedRenderPathLabel.isNotBlank() || androidApiLevelText.isNotBlank()) {
          Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .padding(bottom = 8.dp)
              .testTag("about_tajweed_diagnostics_card"),
          ) {
            Column(
              modifier = Modifier.padding(10.dp),
              verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
              Text(
                text = "معلومات عرض التجويد والنظام",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
              )
              if (tajweedRenderPathLabel.isNotBlank()) {
                Text(
                  text = "مسار التجويد النشط: $tajweedRenderPathLabel",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.testTag("about_tajweed_render_path"),
                )
              }
              if (androidApiLevelText.isNotBlank()) {
                Text(
                  text = "مستوى النظام: $androidApiLevelText",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.testTag("about_android_api_level"),
                )
              }
            }
          }
        }
        for (src in credits) {
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 4.dp),
          ) {
            Column(modifier = Modifier.padding(10.dp)) {
              Text(
                text = src.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
              )
              Text(
                text = src.organization,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
              )
              Spacer(Modifier.height(3.dp))
              Text(
                text = src.usage,
                style = MaterialTheme.typography.bodySmall,
              )
              Text(
                text = "الترخيص: ${src.license}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (src.url.isNotBlank()) {
                Text(
                  text = src.url,
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
          }
        }

        Spacer(Modifier.height(10.dp))
        Text(
          text = "التحقق من سلامة الملفات (SHA-256)",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))

        for (item in assetHashes) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Filled.VerifiedUser,
                contentDescription = null,
                tint = if (item.isValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
              )
              Spacer(Modifier.width(6.dp))
              Column {
                Text(
                  text = item.path,
                  style = MaterialTheme.typography.labelMedium,
                )
                Text(
                  text = item.actualSha256.take(18) + "…",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }
            Text(
              text = if (item.isValid) "سليم" else "تالف",
              style = MaterialTheme.typography.labelSmall,
              color = if (item.isValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
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
