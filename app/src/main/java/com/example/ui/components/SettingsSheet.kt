package com.example.ui.components

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.prefs.BackgroundTheme
import com.example.data.quran.MushafEdition

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
  isDarkMode: Boolean,
  backgroundTheme: BackgroundTheme,
  mushafEdition: MushafEdition,
  onToggleDarkMode: () -> Unit,
  onSelectBackgroundTheme: (BackgroundTheme) -> Unit,
  onSelectEdition: (MushafEdition) -> Unit,
  onOpenTajweedLegend: () -> Unit,
  onOpenCredits: () -> Unit,
  onDismiss: () -> Unit,
) {
  BackHandler(onBack = onDismiss)
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

      Text(
        text = "نوع المصحف",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        EditionChoiceCard(
          title = "مصحف المدينة ١٤٢١",
          subtitle = "الرسم العثماني KFGQPC",
          selected = mushafEdition == MushafEdition.MADINAH_1421,
          onClick = { onSelectEdition(MushafEdition.MADINAH_1421) },
          modifier = Modifier.weight(1f),
        )
        EditionChoiceCard(
          title = "مصحف التجويد",
          subtitle = "أسود على خلفية بيضاء وأبيض على خلفية سوداء",
          selected = mushafEdition == MushafEdition.TAJWEED_V4,
          onClick = { onSelectEdition(MushafEdition.TAJWEED_V4) },
          modifier = Modifier.weight(1f),
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

      Spacer(Modifier.height(26.dp))
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
