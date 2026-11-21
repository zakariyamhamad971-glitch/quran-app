package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.os.SystemClock
import androidx.compose.ui.text.style.TextAlign
import com.example.BuildConfig
import com.example.data.prefs.BackgroundTheme
import com.example.data.prefs.PageViewMode

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
  isDarkMode: Boolean,
  backgroundTheme: BackgroundTheme,
  effectiveTwoPageMode: Boolean,
  activeMushafLabel: String,
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

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 18.dp, vertical = 6.dp)
        .verticalScroll(rememberScrollState()),
    ) {
      Text(
        text = "الإعدادات",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.height(14.dp))

      // Light / Dark mode toggle
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
          Row(verticalAlignment = Alignment.CenterVertically) {
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

      Spacer(Modifier.height(16.dp))
      Text(
        text = "لون الخلفية",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.height(8.dp))

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

      Spacer(Modifier.height(16.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
      Spacer(Modifier.height(14.dp))

      // Mushaf Management Button -> opens dedicated full-screen in-app "المصاحف" page
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

      Spacer(Modifier.height(16.dp))
      Text(
        text = "عرض الصفحات",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        EditionChoiceCard(
          title = "صفحة واحدة",
          subtitle = "عرض صفحة كاملة متناسبة في المنتصف",
          selected = !effectiveTwoPageMode,
          onClick = { onSelectPageViewMode(PageViewMode.SINGLE) },
          modifier = Modifier
            .weight(1f)
            .testTag("settings_page_mode_single"),
        )
        EditionChoiceCard(
          title = "صفحتان",
          subtitle = "عرض صفحتين متقابلتين كالمصحف المطبوع",
          selected = effectiveTwoPageMode,
          onClick = { onSelectPageViewMode(PageViewMode.TWO_PAGES) },
          modifier = Modifier
            .weight(1f)
            .testTag("settings_page_mode_two"),
        )
      }

      Spacer(Modifier.height(16.dp))
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
          Text("دليل التجويد")
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

      Spacer(Modifier.height(10.dp))
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
        Spacer(Modifier.height(10.dp))
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

      Spacer(Modifier.height(14.dp))
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
      Spacer(Modifier.height(8.dp))

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

      Spacer(Modifier.height(22.dp))
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
