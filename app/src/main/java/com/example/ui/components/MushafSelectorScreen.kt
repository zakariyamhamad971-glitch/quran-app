package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.quran.MushafDownloadState
import com.example.data.quran.MushafItem
import com.example.data.quran.MushafRegistry
import com.example.ui.theme.AmiriFontFamily

@Composable
fun MushafSelectorScreen(
  downloadStates: Map<String, MushafDownloadState>,
  onStartDownload: (MushafItem) -> Unit,
  onCancelDownload: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val view = LocalView.current
  val isAnyDownloading = downloadStates.values.any { it.isDownloading }
  DisposableEffect(view, isAnyDownloading) {
    view.keepScreenOn = isAnyDownloading
    onDispose { view.keepScreenOn = false }
  }

  val bgColor = MaterialTheme.colorScheme.background
  val primary = MaterialTheme.colorScheme.primary
  val gold = MaterialTheme.colorScheme.secondary

  Box(
    modifier = modifier
      .fillMaxSize()
      .drawBehind {
        drawRect(color = bgColor)
        drawRect(
          brush = Brush.radialGradient(
            colors = listOf(
              gold.copy(alpha = 0.14f),
              bgColor.copy(alpha = 0f),
            ),
            center = Offset(size.width * 0.5f, size.height * 0.18f),
            radius = size.maxDimension * 0.65f,
          ),
        )
      }
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .padding(horizontal = 20.dp, vertical = 18.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 580.dp)
        .fillMaxSize(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.size(72.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(36.dp),
          )
        }
      }

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = "مرحبًا بك في قبسات",
          fontFamily = AmiriFontFamily,
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onBackground,
          textAlign = TextAlign.Center,
        )
        Text(
          text = "اختر ونزّل مصحفًا واحدًا على الأقل للبدء بالقراءة والاستماع بدون إنترنت.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        items(MushafRegistry.allMushafs, key = { it.id }) { item ->
          val state = downloadStates[item.id] ?: MushafDownloadState(mushafId = item.id)
          MushafItemCard(
            item = item,
            state = state,
            onDownload = { onStartDownload(item) },
            onCancel = { onCancelDownload(item.id) },
          )
        }
      }
    }
  }
}

@Composable
fun MushafItemCard(
  item: MushafItem,
  state: MushafDownloadState,
  onDownload: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isDownloading = state.isDownloading
  val progress = state.progress
  val errorMessage = state.errorMessage

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    border = BorderStroke(
      width = 1.dp,
      color = if (isDownloading) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
      else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
    ),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(Modifier.height(2.dp))
          Text(
            text = "${item.subtitle} • الحجم تقريبًا ${item.sizeMb} ميجابايت",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (isDownloading) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = "جاري التنزيل (${progress.percent}%)",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = "متبقي ${progress.remainingFiles} ملف",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          LinearProgressIndicator(
            progress = { (progress.percent / 100f).coerceIn(0f, 1f) },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
              .fillMaxWidth()
              .height(8.dp)
              .clip(RoundedCornerShape(999.dp)),
          )
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
          ) {
            OutlinedButton(
              onClick = onCancel,
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Text("إلغاء")
            }
          }
        }
      } else if (errorMessage != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.WifiOff,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = errorMessage,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
          Button(
            onClick = onDownload,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .minimumInteractiveComponentSize(),
          ) {
            Icon(imageVector = Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("إعادة المحاولة")
          }
        }
      } else {
        Button(
          onClick = onDownload,
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize(),
        ) {
          Icon(imageVector = Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("تنزيل المصحف (${item.sizeMb} ميجابايت)")
        }
      }
    }
  }
}
