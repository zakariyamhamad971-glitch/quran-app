package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.data.audio.AudioBatchUiSummary
import com.example.data.audio.AudioCacheManager
import com.example.data.audio.ReciterStorageSummary
import com.example.data.audio.SurahDownloadStatus
import com.example.data.audio.SurahTransferState
import com.example.data.audio.VerifiedReciter
import com.example.data.quran.SurahMeta
import com.example.ui.theme.AmiriFontFamily

@Composable
fun rememberNotificationPermissionRequester(): () -> Unit {
  val context = LocalContext.current
  var askedOnce by rememberSaveable { mutableStateOf(false) }
  val launcher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { _ ->
    // Downloads run in foreground service / background regardless of grant or denial
  }

  return remember(context, launcher) {
    {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedOnce) {
        val granted = ContextCompat.checkSelfPermission(
          context,
          Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
          askedOnce = true
          launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
      }
    }
  }
}

private fun toArabicDigits(number: Int): String {
  val digits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
  val s = number.toString()
  return buildString(s.length) {
    for (ch in s) {
      append(if (ch in '0'..'9') digits[ch - '0'] else ch)
    }
  }
}

private fun padSurah(surahId: Int): String = when {
  surahId < 10 -> "00$surahId"
  surahId < 100 -> "0$surahId"
  else -> surahId.toString()
}

@Composable
fun ReciterDownloadsScreen(
  reciter: VerifiedReciter,
  surahs: List<SurahMeta>,
  downloadedKeys: Set<String>,
  transferStates: Map<String, SurahTransferState>,
  reciterSummary: ReciterStorageSummary,
  batchSummary: AudioBatchUiSummary,
  onDownloadSurah: (Int) -> Unit,
  onCancelSurah: (Int) -> Unit,
  onDeleteSurah: (Int) -> Unit,
  onDownloadAll: () -> Unit,
  onCancelAllForReciter: () -> Unit,
  onDeleteAllForReciter: () -> Unit,
  onDismissLowStorageMessage: () -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onClose)

  val requestNotifPermissionIfNeeded = rememberNotificationPermissionRequester()
  var showDeleteAllConfirm by remember { mutableStateOf(false) }
  val listState = rememberLazyListState()

  val orderedSurahs = remember(surahs) {
    if (surahs.size >= 114) {
      surahs.sortedBy { it.id }
    } else {
      val byId = surahs.associateBy { it.id }
      (1..114).map { id ->
        byId[id] ?: SurahMeta(
          id = id,
          nameArabic = "سورة ${toArabicDigits(id)}",
          revelationOrder = 0,
          versesCount = 0,
          bismillahPre = true,
          startPage = 1,
          endPage = 1,
          makki = true,
        )
      }
    }
  }

  val reciterTransfers = remember(transferStates, reciter.key) {
    transferStates.values.filter { it.reciterKey == reciter.key }
  }
  val hasActiveOrQueued = remember(reciterTransfers) {
    reciterTransfers.any {
      it.status == SurahDownloadStatus.DOWNLOADING ||
        it.status == SurahDownloadStatus.QUEUED ||
        it.status == SurahDownloadStatus.PAUSED_OFFLINE
    }
  }
  val hasPausedOffline = remember(reciterTransfers) {
    reciterTransfers.any { it.status == SurahDownloadStatus.PAUSED_OFFLINE }
  }

  val availableCount = reciter.availableSurahs.size
  val downloadedCount = reciterSummary.downloadedCount
  val missingAvailableCount = (availableCount - downloadedCount).coerceAtLeast(0)

  Surface(
    modifier = modifier
      .fillMaxSize()
      .testTag("reciter_downloads_screen"),
    color = MaterialTheme.colorScheme.background,
  ) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.TopCenter,
    ) {
      Column(
        modifier = Modifier
          .widthIn(max = 860.dp)
          .fillMaxSize(),
      ) {
        // Top Bar
        Surface(
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 2.dp,
          modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              IconButton(
                onClick = onClose,
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("reciter_downloads_back_button"),
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                  contentDescription = "رجوع",
                  tint = MaterialTheme.colorScheme.primary,
                )
              }

              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = reciter.displayName,
                  style = MaterialTheme.typography.titleLarge.copy(fontFamily = AmiriFontFamily),
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                val riwayaLabel = reciter.riwayaName.ifBlank { "حفص عن عاصم" }
                val sizeLabel = AudioCacheManager.formatBytesArabic(reciterSummary.totalBytes)
                Text(
                  text = "$riwayaLabel • ${toArabicDigits(downloadedCount)} من ${toArabicDigits(availableCount)} محملة • $sizeLabel",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }
            }

            // Action row beside reciter name: تحميل الكل / حذف الكل (+ إلغاء التحميل when active)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (hasActiveOrQueued) {
                OutlinedButton(
                  onClick = onCancelAllForReciter,
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier
                    .weight(1f)
                    .minimumInteractiveComponentSize()
                    .testTag("reciter_cancel_all_button"),
                ) {
                  Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(6.dp))
                  Text("إلغاء التحميل", maxLines = 1)
                }
              }

              Button(
                onClick = {
                  requestNotifPermissionIfNeeded()
                  onDownloadAll()
                },
                enabled = missingAvailableCount > 0,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .weight(1f)
                  .minimumInteractiveComponentSize()
                  .testTag("reciter_download_all_button"),
              ) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("تحميل الكل", maxLines = 1)
              }

              OutlinedButton(
                onClick = { showDeleteAllConfirm = true },
                enabled = downloadedCount > 0 || hasActiveOrQueued,
                colors = ButtonDefaults.outlinedButtonColors(
                  contentColor = MaterialTheme.colorScheme.error,
                ),
                border = BorderStroke(
                  1.dp,
                  if (downloadedCount > 0 || hasActiveOrQueued) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                  } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                  },
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .weight(1f)
                  .minimumInteractiveComponentSize()
                  .testTag("reciter_delete_all_button"),
              ) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("حذف الكل", maxLines = 1)
              }
            }
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        if (batchSummary.lowStorageMessage != null) {
          Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .testTag("reciter_low_storage_banner"),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Filled.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = batchSummary.lowStorageMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
              )
              TextButton(onClick = onDismissLowStorageMessage) {
                Text("إغلاق")
              }
            }
          }
        } else if (hasPausedOffline) {
          Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .testTag("reciter_offline_paused_banner"),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
              )
              Spacer(Modifier.width(8.dp))
              Text(
                text = "تم إيقاف التحميل مؤقتاً لعدم توفر الإنترنت — سيستأنف تلقائياً عند عودة الاتصال.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
              )
            }
          }
        }

        // Scrollable 114 surahs list preserving scroll position via rememberLazyListState + stable key
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag("reciter_surahs_list"),
          contentPadding = PaddingValues(vertical = 6.dp),
        ) {
          items(
            items = orderedSurahs,
            key = { it.id },
            contentType = { "reciter_surah_row" },
          ) { surah ->
            val key = "${reciter.key}_${padSurah(surah.id)}"
            val isAvailable = reciter.availableSurahs.contains(surah.id)
            val isDownloaded = downloadedKeys.contains(key) ||
              reciterSummary.downloadedSurahs.contains(surah.id)
            val transfer = transferStates[key]

            ReciterSurahDownloadRow(
              surah = surah,
              isAvailable = isAvailable,
              isDownloaded = isDownloaded,
              transferState = transfer,
              onDownload = {
                requestNotifPermissionIfNeeded()
                onDownloadSurah(surah.id)
              },
              onCancel = { onCancelSurah(surah.id) },
              onDelete = { onDeleteSurah(surah.id) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f))
          }
        }
      }
    }
  }

  if (showDeleteAllConfirm) {
    AlertDialog(
      onDismissRequest = { showDeleteAllConfirm = false },
      title = { Text("حذف جميع التلاوات") },
      text = {
        Text("هل تريد حذف جميع السور المحملة للقارئ ${reciter.displayName}؟")
      },
      confirmButton = {
        TextButton(
          onClick = {
            showDeleteAllConfirm = false
            onDeleteAllForReciter()
          },
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
          modifier = Modifier.testTag("confirm_delete_all_reciter_audio"),
        ) {
          Text("حذف الكل")
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteAllConfirm = false }) {
          Text("إلغاء")
        }
      },
    )
  }
}

@Composable
private fun ReciterSurahDownloadRow(
  surah: SurahMeta,
  isAvailable: Boolean,
  isDownloaded: Boolean,
  transferState: SurahTransferState?,
  onDownload: () -> Unit,
  onCancel: () -> Unit,
  onDelete: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 14.dp, vertical = 8.dp)
      .testTag("reciter_surah_row_${surah.id}"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    // Surah info (non-clickable — tapping the surah name itself does nothing)
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(34.dp)
          .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = toArabicDigits(surah.id),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      Spacer(Modifier.width(12.dp))
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = "سورة ${surah.nameArabic}",
          style = MaterialTheme.typography.titleMedium.copy(fontFamily = AmiriFontFamily),
          color = if (isAvailable) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val statusSubtext = when {
          !isAvailable -> "غير متوفرة في مصحف هذا القارئ"
          transferState?.status == SurahDownloadStatus.FAILED && !transferState.errorMessage.isNullOrBlank() ->
            transferState.errorMessage
          surah.versesCount > 0 ->
            "${if (surah.makki) "مكية" else "مدنية"} • ${toArabicDigits(surah.versesCount)} آية"
          else -> "سورة ${toArabicDigits(surah.id)}"
        }
        Text(
          text = statusSubtext,
          style = MaterialTheme.typography.bodySmall,
          color = if (transferState?.status == SurahDownloadStatus.FAILED) {
            MaterialTheme.colorScheme.error
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    Spacer(Modifier.width(8.dp))

    // Action side
    when {
      !isAvailable -> {
        Text(
          text = "غير متوفرة",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
          modifier = Modifier.padding(horizontal = 8.dp),
        )
      }

      transferState?.status == SurahDownloadStatus.DOWNLOADING -> {
        Row(verticalAlignment = Alignment.CenterVertically) {
          CircularProgressIndicator(
            progress = { transferState.progress.coerceIn(0.02f, 1f) },
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(20.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(
            text = "${(transferState.progress * 100).toInt().coerceIn(0, 100)}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
          )
          Spacer(Modifier.width(4.dp))
          TextButton(
            onClick = onCancel,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("reciter_surah_cancel_${surah.id}"),
          ) {
            Text("إلغاء")
          }
        }
      }

      transferState?.status == SurahDownloadStatus.QUEUED -> {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "في الانتظار",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          TextButton(
            onClick = onCancel,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("reciter_surah_cancel_${surah.id}"),
          ) {
            Text("إلغاء")
          }
        }
      }

      transferState?.status == SurahDownloadStatus.PAUSED_OFFLINE -> {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = "متوقف مؤقتاً",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.secondary,
          )
          TextButton(
            onClick = onCancel,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("reciter_surah_cancel_${surah.id}"),
          ) {
            Text("إلغاء")
          }
        }
      }

      isDownloaded -> {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "تم التحميل",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
          )
          Spacer(Modifier.width(4.dp))
          OutlinedButton(
            onClick = onDelete,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
            colors = ButtonDefaults.outlinedButtonColors(
              contentColor = MaterialTheme.colorScheme.error,
            ),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("reciter_surah_delete_${surah.id}"),
          ) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("حذف")
          }
        }
      }

      transferState?.status == SurahDownloadStatus.FAILED -> {
        FilledTonalButton(
          onClick = onDownload,
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("reciter_surah_retry_${surah.id}"),
        ) {
          Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("إعادة المحاولة")
        }
      }

      else -> {
        FilledTonalButton(
          onClick = onDownload,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("reciter_surah_download_${surah.id}"),
        ) {
          Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(Modifier.width(4.dp))
          Text("تحميل")
        }
      }
    }
  }
}
