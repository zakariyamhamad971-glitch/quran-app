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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.example.data.quran.EditionDownloadProgress
import com.example.data.quran.MushafSetupState
import com.example.ui.theme.AmiriFontFamily

@Composable
fun MushafSetupScreen(
  state: MushafSetupState.Required,
  onRetry: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val view = LocalView.current
  val keepAwake = state.isDownloading && state.errorMessage == null
  DisposableEffect(view, keepAwake) {
    view.keepScreenOn = keepAwake
    onDispose {
      view.keepScreenOn = false
    }
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
        drawRect(
          brush = Brush.radialGradient(
            colors = listOf(
              primary.copy(alpha = 0.10f),
              bgColor.copy(alpha = 0f),
            ),
            center = Offset(size.width * 0.2f, size.height * 0.85f),
            radius = size.maxDimension * 0.55f,
          ),
        )
      }
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .padding(horizontal = 24.dp, vertical = 20.dp),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 560.dp)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState()),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
        modifier = Modifier.size(76.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = "تجهيز المصحف",
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(38.dp),
          )
        }
      }

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = "تجهيز المصحف",
          fontFamily = AmiriFontFamily,
          style = MaterialTheme.typography.headlineMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onBackground,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag("setup_screen_title"),
        )
        Text(
          text = "جاري تنزيل بيانات الصفحات وخطوط المصحفين للاستخدام الكامل بدون إنترنت (للمرة الأولى فقط).",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }

      Spacer(modifier = Modifier.height(4.dp))

      MushafEditionProgressCard(
        title = "مصحف المدينة",
        progress = state.madinah,
        progressTestTag = "setup_madinah_progress",
      )

      MushafEditionProgressCard(
        title = "مصحف التجويد",
        progress = state.tajweed,
        progressTestTag = "setup_tajweed_progress",
      )

      if (state.errorMessage != null) {
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = MaterialTheme.colorScheme.errorContainer,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("setup_error_banner"),
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.WifiOff,
                contentDescription = "فشل الاتصال",
                tint = MaterialTheme.colorScheme.onErrorContainer,
              )
              Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
              )
            }

            Button(
              onClick = onRetry,
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .minimumInteractiveComponentSize()
                .testTag("setup_retry_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
              Spacer(modifier = Modifier.size(8.dp))
              Text(
                text = "إعادة المحاولة",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
              )
            }
          }
        }
      } else {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.CloudDownload,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
            Text(
              text = if (state.isScanningCache) {
                "جاري فحص الملفات المحفوظة مسبقًا..."
              } else {
                "يرجى عدم إغلاق التطبيق حتى يكتمل التجهيز؛ يُحفظ التقدم تلقائيًا بعد كل ملف."
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MushafEditionProgressCard(
  title: String,
  progress: EditionDownloadProgress,
  progressTestTag: String,
) {
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    border = BorderStroke(
      width = 1.dp,
      color = if (progress.isDone) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
      } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
      },
    ),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "$title ${progress.percent}%",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        if (progress.isDone) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.CheckCircle,
              contentDescription = "مكتمل",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = "مكتمل",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Medium,
            )
          }
        } else {
          Text(
            text = "متبقي ${progress.remainingFiles} ملف",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      LinearProgressIndicator(
        progress = { (progress.percent / 100f).coerceIn(0f, 1f) },
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
          .fillMaxWidth()
          .height(8.dp)
          .clip(RoundedCornerShape(999.dp))
          .testTag(progressTestTag),
      )

      Text(
        text = "${progress.completedFiles} من ${progress.totalFiles} ملف",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
