package com.example.ui.components

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.audio.AudioCacheManager
import com.example.data.audio.AudioRepository
import com.example.data.audio.ReciterStorageSummary
import com.example.data.audio.SurahDownloadStatus
import com.example.data.audio.SurahTransferState
import com.example.data.audio.VerifiedReciter
import com.example.data.prefs.BackgroundTheme
import com.example.data.prefs.PageViewMode
import com.example.data.quran.MushafDownloadState
import com.example.data.quran.MushafEdition
import com.example.ui.theme.AmiriFontFamily

internal object BetaUnlockTapTracker {
  const val REQUIRED_TAPS = 10
  const val MAX_INTERVAL_MS = 2_000L

  data class TapState(
    val count: Int = 0,
    val lastTapMs: Long = 0L,
    val unlocked: Boolean = false,
  )

  fun onTap(current: TapState, nowMs: Long): TapState {
    if (current.unlocked) return current
    val withinWindow = current.count > 0 && (nowMs - current.lastTapMs) in 0L until MAX_INTERVAL_MS
    val nextCount = if (withinWindow) current.count + 1 else 1
    return if (nextCount >= REQUIRED_TAPS) {
      TapState(count = 0, lastTapMs = nowMs, unlocked = true)
    } else {
      TapState(count = nextCount, lastTapMs = nowMs, unlocked = false)
    }
  }
}

private fun toArabicIndic(number: Int): String {
  val digits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
  val s = number.toString()
  return buildString(s.length) {
    for (ch in s) {
      append(if (ch in '0'..'9') digits[ch - '0'] else ch)
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
  isDarkMode: Boolean,
  backgroundTheme: BackgroundTheme,
  effectiveTwoPageMode: Boolean,
  activeMushafLabel: String,
  activeMushafIsPdf: Boolean,
  activeTextMushafId: String,
  activeMushafEdition: MushafEdition,
  downloadedTextMushafIds: Set<String>,
  tajweedDownloadState: MushafDownloadState,
  onDownloadTajweed: () -> Unit,
  onCancelTajweedDownload: () -> Unit,
  onDeleteTajweed: () -> Unit,
  onSelectPrimaryMushaf: (MushafEdition) -> Unit,
  downloadableReciters: List<VerifiedReciter>,
  downloadableRecitersLoading: Boolean,
  downloadableRecitersError: String?,
  reciterSummaries: Map<String, ReciterStorageSummary>,
  audioTransferStates: Map<String, SurahTransferState>,
  onRetryLoadDownloadableReciters: () -> Unit,
  onOpenReciterDownloads: (VerifiedReciter) -> Unit,
  onOpenReciterManager: () -> Unit = {},
  onOpenTafsirManager: () -> Unit = {},
  onOpenAdhanSettings: () -> Unit = {},
  onOpenMushafsManager: () -> Unit,
  onToggleDarkMode: () -> Unit,
  onSelectBackgroundTheme: (BackgroundTheme) -> Unit,
  onSelectPageViewMode: (PageViewMode) -> Unit,
  onOpenTajweedLegend: () -> Unit,
  onOpenCredits: () -> Unit,
  onDismiss: () -> Unit,
  isCheckingUpdate: Boolean = false,
  onCheckForUpdate: () -> Unit = {},
  isBetaChannelUnlocked: Boolean = false,
  isBetaChannelEnabled: Boolean = false,
  onUnlockBetaChannel: () -> Unit = {},
  onToggleBetaChannel: (Boolean) -> Unit = {},
) {
  BackHandler(onBack = onDismiss)
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var tapState by remember(isBetaChannelUnlocked, isBetaChannelEnabled) {
    mutableStateOf(
      BetaUnlockTapTracker.TapState(
        unlocked = isBetaChannelUnlocked || isBetaChannelEnabled,
      ),
    )
  }
  var showDeleteTajweedConfirm by remember { mutableStateOf(false) }

  val isMadinahDownloaded = downloadedTextMushafIds.contains("madinah_1421")
  val isTajweedDownloaded = downloadedTextMushafIds.contains("tajweed_v4")
  val isTajweedSelected = !activeMushafIsPdf &&
    (activeTextMushafId == "tajweed_v4" || activeMushafEdition == MushafEdition.TAJWEED_V4)
  val isMadinahSelected = !activeMushafIsPdf && !isTajweedSelected

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    LazyColumn(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.92f)
        .padding(horizontal = 18.dp),
      contentPadding = PaddingValues(top = 6.dp, bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      item(key = "header") {
        Text(
          text = "الإعدادات",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      }

      // Light / Dark mode toggle
      item(key = "dark_mode") {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(1f),
            ) {
              Icon(
                imageVector = if (isDarkMode) Icons.Filled.Brightness4 else Icons.Filled.Brightness7,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
              )
              Spacer(Modifier.width(10.dp))
              Column {
                Text(
                  text = if (isDarkMode) "الوضع الداكن" else "الوضع الفاتح",
                  style = MaterialTheme.typography.titleMedium,
                )
                Text(
                  text = "التبديل السريع بين الإضاءة النهارية والليلية",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            Switch(
              checked = isDarkMode,
              onCheckedChange = { onToggleDarkMode() },
              modifier = Modifier.testTag("settings_dark_mode_switch"),
            )
          }
        }
      }

      // Background color
      item(key = "bg_theme") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "لون الخلفية",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            BackgroundColorOption(
              label = "أبيض",
              swatchColor = Color(0xFFFFFFFF),
              selected = backgroundTheme == BackgroundTheme.WHITE,
              onClick = { onSelectBackgroundTheme(BackgroundTheme.WHITE) },
              modifier = Modifier
                .weight(1f)
                .testTag("bg_option_white"),
            )
            BackgroundColorOption(
              label = "أسود",
              swatchColor = Color(0xFF000000),
              selected = backgroundTheme == BackgroundTheme.BLACK,
              onClick = { onSelectBackgroundTheme(BackgroundTheme.BLACK) },
              modifier = Modifier
                .weight(1f)
                .testTag("bg_option_black"),
            )
          }
        }
      }

      // Mushafs page link + Tajweed section + Primary Mushaf selector
      item(key = "mushaf_management") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text(
                text = "المصحف الحالي: $activeMushafLabel",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Button(
                onClick = {
                  onDismiss()
                  onOpenMushafsManager()
                },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .minimumInteractiveComponentSize()
                  .testTag("settings_open_mushafs_button"),
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.MenuBook,
                  contentDescription = null,
                  modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("المصاحف")
              }
            }
          }

          // Tajweed mushaf section (TASK 2)
          TajweedMushafSettingsCard(
            isDownloaded = isTajweedDownloaded,
            canDelete = isTajweedDownloaded && isMadinahDownloaded,
            state = tajweedDownloadState,
            onDownload = onDownloadTajweed,
            onCancel = onCancelTajweedDownload,
            onRequestDelete = { showDeleteTajweedConfirm = true },
          )

          // Primary Mushaf selector (TASK 2)
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
              text = "المصحف الأساسي",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              EditionChoiceCard(
                title = "المدينة",
                subtitle = if (isMadinahDownloaded) "مصحف المدينة ١٤٢١" else "غير محمّل",
                selected = isMadinahSelected,
                enabled = isMadinahDownloaded,
                onClick = { onSelectPrimaryMushaf(MushafEdition.MADINAH_1421) },
                modifier = Modifier
                  .weight(1f)
                  .testTag("settings_primary_mushaf_madinah"),
              )
              EditionChoiceCard(
                title = "التجويد",
                subtitle = if (isTajweedDownloaded) "مصحف التجويد الملون" else "غير محمّل",
                selected = isTajweedSelected,
                enabled = isTajweedDownloaded,
                onClick = { onSelectPrimaryMushaf(MushafEdition.TAJWEED_V4) },
                modifier = Modifier
                  .weight(1f)
                  .testTag("settings_primary_mushaf_tajweed"),
              )
            }
          }
        }
      }

      // Page view mode
      item(key = "page_view_mode") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "عرض الصفحات",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            EditionChoiceCard(
              title = "صفحة واحدة",
              subtitle = "عرض صفحة كاملة متناسبة في المنتصف",
              selected = !effectiveTwoPageMode,
              enabled = true,
              onClick = { onSelectPageViewMode(PageViewMode.SINGLE) },
              modifier = Modifier
                .weight(1f)
                .testTag("settings_page_mode_single"),
            )
            EditionChoiceCard(
              title = "صفحتان",
              subtitle = "عرض صفحتين متقابلتين كالمصحف المطبوع",
              selected = effectiveTwoPageMode,
              enabled = true,
              onClick = { onSelectPageViewMode(PageViewMode.TWO_PAGES) },
              modifier = Modifier
                .weight(1f)
                .testTag("settings_page_mode_two"),
            )
          }
        }
      }

      // Reciter audio management entry (Task B)
      item(key = "reciter_management_entry") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
          Surface(
            onClick = {
              onDismiss()
              onOpenReciterManager()
            },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("settings_open_reciters_manager"),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Box(
                  modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                  contentAlignment = Alignment.Center,
                ) {
                  Icon(
                    imageVector = Icons.Filled.RecordVoiceOver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                  )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                  Text(
                    text = "إدارة القراء",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Spacer(Modifier.height(2.dp))
                  Text(
                    text = "تحميل وإدارة تلاوات القراء",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          Surface(
            onClick = {
              onDismiss()
              onOpenTafsirManager()
            },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("settings_open_tafsir_manager"),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Box(
                  modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                  contentAlignment = Alignment.Center,
                ) {
                  Icon(
                    imageVector = Icons.Filled.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                  )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                  Text(
                    text = "الترجمات والتفاسير",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Spacer(Modifier.height(2.dp))
                  Text(
                    text = "تنزيل وإدارة التفاسير والترجمات للقراءة دون اتصال",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }

          Surface(
            onClick = {
              onDismiss()
              onOpenAdhanSettings()
            },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("settings_open_adhan_alarms"),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Box(
                  modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                  contentAlignment = Alignment.Center,
                ) {
                  Icon(
                    imageVector = Icons.Filled.NotificationsActive,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                  )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                  Text(
                    text = "المواقيت وتنبيهات الأذان",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Spacer(Modifier.height(2.dp))
                  Text(
                    text = "طريقة الحساب، أصوات الأذان، الإشعار الدائم، والتنبيه قبل الأذان",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
              Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      }

      // Legend / Credits / Update / Beta / Version footer
      item(key = "footer") {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedButton(
              onClick = {
                onDismiss()
                onOpenTajweedLegend()
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.ColorLens, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("دليل الألوان")
            }
            OutlinedButton(
              onClick = {
                onDismiss()
                onOpenCredits()
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("المصادر والتحقق")
            }
          }

          OutlinedButton(
            onClick = onCheckForUpdate,
            enabled = !isCheckingUpdate,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("check_update_button"),
          ) {
            if (isCheckingUpdate) {
              CircularProgressIndicator(
                strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(8.dp))
              Text("جاري التحقق من التحديث...")
            } else {
              Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(8.dp))
              Text("تحقق من التحديث")
            }
          }

          val showBetaSwitch = isBetaChannelUnlocked || isBetaChannelEnabled || tapState.unlocked
          if (showBetaSwitch) {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable { onToggleBetaChannel(!isBetaChannelEnabled) },
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "قناة التجربة",
                    style = MaterialTheme.typography.titleSmall,
                  )
                  Text(
                    text = "استلام التحديثات التجريبية (Beta) قبل الإصدار الرسمي",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
                Spacer(Modifier.width(10.dp))
                Switch(
                  checked = isBetaChannelEnabled,
                  onCheckedChange = onToggleBetaChannel,
                  modifier = Modifier.testTag("settings_beta_channel_switch"),
                )
              }
            }
          }

          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))

          Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(10.dp))
                .clickable {
                  val next = BetaUnlockTapTracker.onTap(tapState, SystemClock.elapsedRealtime())
                  tapState = next
                  if (next.unlocked && !isBetaChannelUnlocked) {
                    onUnlockBetaChannel()
                  }
                }
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("settings_version_row"),
            ) {
              Text(
                text = "رقم الإصدار ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
              )
            }
            Text(
              text = "تم صنعه و تطويره بواسطه محمد زكريا المخزنجي.",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
              textAlign = TextAlign.Center,
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp)
                .testTag("settings_developer_credit"),
            )
          }
        }
      }
    }
  }

  if (showDeleteTajweedConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteTajweedConfirm = false },
      title = { Text("حذف مصحف التجويد") },
      text = {
        Text("هل تريد حذف ملفات مصحف التجويد؟ سيتم التبديل تلقائياً إلى مصحف المدينة إذا كان التجويد هو المختار.")
      },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteTajweedConfirm = false
            onDeleteTajweed()
          },
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.testTag("confirm_delete_tajweed_button"),
        ) {
          Text("حذف")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteTajweedConfirm = false }) {
          Text("إلغاء")
        }
      },
    )
  }
}

@Composable
private fun TajweedMushafSettingsCard(
  isDownloaded: Boolean,
  canDelete: Boolean,
  state: MushafDownloadState,
  onDownload: () -> Unit,
  onCancel: () -> Unit,
  onRequestDelete: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("settings_tajweed_mushaf_section"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "مصحف التجويد",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          val statusText = when {
            state.isScanningCache -> "جاري فحص الملفات..."
            state.isDownloading -> "جاري التنزيل (${state.progress.percent}%)"
            isDownloaded -> "محمّل • متاح للاستخدام"
            state.errorMessage != null -> state.errorMessage
            else -> "غير محمّل • الحجم تقريبًا ٦٤ ميجابايت"
          }
          Text(
            text = statusText,
            style = MaterialTheme.typography.bodySmall,
            color = if (state.errorMessage != null && !state.isDownloading && !isDownloaded) {
              MaterialTheme.colorScheme.error
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          )
        }

        if (state.isDownloading || state.isScanningCache) {
          OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("settings_tajweed_cancel_button"),
          ) {
            Text("إلغاء")
          }
        } else if (isDownloaded) {
          OutlinedButton(
            onClick = onRequestDelete,
            enabled = canDelete,
            colors = ButtonDefaults.outlinedButtonColors(
              contentColor = MaterialTheme.colorScheme.error,
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("settings_tajweed_delete_button"),
          ) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("حذف")
          }
        } else {
          FilledTonalButton(
            onClick = onDownload,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("settings_tajweed_download_button"),
          ) {
            Icon(
              imageVector = if (state.errorMessage != null) Icons.Filled.Refresh else Icons.Filled.CloudDownload,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(if (state.errorMessage != null) "إعادة المحاولة" else "تنزيل")
          }
        }
      }

      if (state.isDownloading) {
        LinearProgressIndicator(
          progress = { (state.progress.percent / 100f).coerceIn(0f, 1f) },
          modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp)),
        )
      }
    }
  }
}


@Composable
private fun BackgroundColorOption(
  label: String,
  swatchColor: Color,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick),
    shape = RoundedCornerShape(12.dp),
    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    border = BorderStroke(
      width = if (selected) 2.dp else 1.dp,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    ),
  ) {
    Column(
      modifier = Modifier.padding(vertical = 10.dp, horizontal = 8.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Surface(
        modifier = Modifier.size(30.dp),
        shape = CircleShape,
        color = swatchColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      ) {
        Box(contentAlignment = Alignment.Center) {
          if (selected) {
            Icon(
              imageVector = Icons.Filled.Check,
              contentDescription = null,
              tint = if (swatchColor == Color.White) Color(0xFF000000) else Color.White,
              modifier = Modifier.size(16.dp),
            )
          }
        }
      }
      Spacer(Modifier.height(6.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun EditionChoiceCard(
  title: String,
  subtitle: String,
  selected: Boolean,
  enabled: Boolean = true,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier
      .alpha(if (enabled) 1f else 0.48f)
      .clip(RoundedCornerShape(12.dp))
      .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
    shape = RoundedCornerShape(12.dp),
    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    border = BorderStroke(
      width = if (selected) 1.5.dp else 1.dp,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    ),
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(Modifier.height(2.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
