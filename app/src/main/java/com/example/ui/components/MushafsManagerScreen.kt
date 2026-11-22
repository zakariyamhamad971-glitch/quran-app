package com.example.ui.components

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.pdf.PdfMushafEntry
import com.example.data.pdf.PdfMushafTransferState
import com.example.data.quran.MushafDownloadState
import com.example.data.quran.MushafItem
import com.example.data.quran.MushafRegistry
import com.example.ui.theme.AmiriFontFamily
import java.util.Locale

@Composable
fun MushafsManagerScreen(
  downloadedTextMushafIds: Set<String>,
  activeTextMushafId: String,
  activeMushafIsPdf: Boolean,
  activePdfMushafId: String?,
  textDownloadStates: Map<String, MushafDownloadState>,
  pdfMushafs: List<PdfMushafEntry>,
  pdfManifestLoading: Boolean,
  pdfManifestError: String?,
  pdfTransferStates: Map<String, PdfMushafTransferState>,
  offlinePdfIds: Set<String>,
  localOrCachedPdfIds: Set<String> = emptySet(),
  onLoadThumbnail: suspend (PdfMushafEntry) -> Bitmap?,
  onRefreshManifest: () -> Unit,
  onSelectTextMushaf: (String) -> Unit,
  onStartTextDownload: (MushafItem) -> Unit,
  onCancelTextDownload: (String) -> Unit,
  onRemoveTextMushaf: (String) -> Unit,
  onSelectPdfMushaf: (PdfMushafEntry) -> Unit,
  onDownloadPdfMushaf: (PdfMushafEntry) -> Unit,
  onCancelPdfTransfer: (String) -> Unit,
  onDeletePdfMushaf: (PdfMushafEntry) -> Unit,
  onForceUpdatePdfMushaf: (PdfMushafEntry) -> Unit = {},
  onClose: () -> Unit,
) {
  BackHandler(onBack = onClose)
  var deleteWarningMessage by remember { mutableStateOf<String?>(null) }
  val sortedPdfMushafs = remember(pdfMushafs) { pdfMushafs.sortedBy { it.order } }

  if (deleteWarningMessage != null) {
    AlertDialog(
      onDismissRequest = { deleteWarningMessage = null },
      title = { Text("تنبيه") },
      text = { Text(deleteWarningMessage.orEmpty()) },
      confirmButton = {
        TextButton(onClick = { deleteWarningMessage = null }) {
          Text("حسناً")
        }
      },
    )
  }

  Surface(
    modifier = Modifier
      .fillMaxSize()
      .testTag("mushafs_manager_screen"),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Top bar
      Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier
          .fillMaxWidth()
          .windowInsetsPadding(WindowInsets.statusBars),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
              onClick = onClose,
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("mushafs_manager_back"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "رجوع",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            Spacer(Modifier.width(4.dp))
            Text(
              text = "المصاحف",
              style = MaterialTheme.typography.titleLarge.copy(fontFamily = AmiriFontFamily),
              color = MaterialTheme.colorScheme.primary,
            )
          }
          IconButton(
            onClick = onRefreshManifest,
            enabled = !pdfManifestLoading,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("mushafs_manager_refresh"),
          ) {
            Icon(
              imageVector = Icons.Filled.Refresh,
              contentDescription = "تحديث قائمة المصاحف",
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Top guidance note
        item(key = "pdf_guidance_note", contentType = "info_note") {
          Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
              Text(
                text = "للتفسير والترجمة والإعراب استخدم المصحف العادي",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          }
        }

        // Section 1: Standard Text Mushafs
        item(key = "section_text_mushafs", contentType = "section_header") {
          Text(
            text = "المصاحف النصية (مع التفسير والإعراب والتفاعل مع الآيات)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
          )
        }

        items(
          items = MushafRegistry.allMushafs,
          key = { "text_${it.id}" },
          contentType = { "text_mushaf_card" },
        ) { item ->
          val isDownloaded = downloadedTextMushafIds.contains(item.id)
          val isActive = !activeMushafIsPdf && activeTextMushafId == item.id
          val state = textDownloadStates[item.id] ?: MushafDownloadState(mushafId = item.id)

          TextMushafManagerCard(
            item = item,
            isDownloaded = isDownloaded,
            isActive = isActive,
            state = state,
            onSelect = { onSelectTextMushaf(item.id) },
            onDownload = { onStartTextDownload(item) },
            onCancel = { onCancelTextDownload(item.id) },
            onRemove = {
              if (downloadedTextMushafIds.size <= 1) {
                deleteWarningMessage = "يجب الاحتفاظ بمصحف نصي واحد على الأقل داخل التطبيق."
              } else {
                onRemoveTextMushaf(item.id)
              }
            },
          )
        }

        // Section 2: PDF Mushafs (19 Qira'at / Riwayat)
        item(key = "section_pdf_mushafs", contentType = "section_header") {
          Column(modifier = Modifier.padding(top = 6.dp)) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            Spacer(Modifier.height(10.dp))
            Text(
              text = "مصاحف القراءات والروايات (PDF)",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }

        if (pdfManifestLoading && sortedPdfMushafs.isEmpty()) {
          item(key = "pdf_loading", contentType = "loading") {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 28.dp),
              contentAlignment = Alignment.Center,
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(10.dp))
                Text(
                  text = "جاري تحميل قائمة المصاحف...",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        } else if (pdfManifestError != null && sortedPdfMushafs.isEmpty()) {
          item(key = "pdf_error", contentType = "error") {
            Surface(
              shape = RoundedCornerShape(14.dp),
              color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
              ) {
                Text(
                  text = pdfManifestError,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.error,
                  textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onRefreshManifest) {
                  Text("إعادة المحاولة")
                }
              }
            }
          }
        } else {
          items(
            items = sortedPdfMushafs,
            key = { "pdf_${it.id}" },
            contentType = { "pdf_mushaf_card" },
          ) { entry ->
            val isActive = activeMushafIsPdf && activePdfMushafId == entry.id
            val isOffline = offlinePdfIds.contains(entry.id)
            val hasLocalOrCachedFile = isOffline || localOrCachedPdfIds.contains(entry.id)
            val transferState = pdfTransferStates[entry.id] ?: PdfMushafTransferState(mushafId = entry.id)

            PdfMushafWideCard(
              entry = entry,
              isActive = isActive,
              isOfflineDownloaded = isOffline,
              hasLocalOrCachedFile = hasLocalOrCachedFile,
              transferState = transferState,
              onLoadThumbnail = onLoadThumbnail,
              onSelect = { onSelectPdfMushaf(entry) },
              onDownload = { onDownloadPdfMushaf(entry) },
              onCancel = { onCancelPdfTransfer(entry.id) },
              onDelete = { onDeletePdfMushaf(entry) },
              onForceUpdate = { onForceUpdatePdfMushaf(entry) },
            )
          }
        }

        item(key = "bottom_nav_spacer", contentType = "spacer") {
          Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
      }
    }
  }
}

@Composable
private fun TextMushafManagerCard(
  item: MushafItem,
  isDownloaded: Boolean,
  isActive: Boolean,
  state: MushafDownloadState,
  onSelect: () -> Unit,
  onDownload: () -> Unit,
  onCancel: () -> Unit,
  onRemove: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    border = BorderStroke(
      width = if (isActive) 2.dp else 1.dp,
      color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
    ),
    modifier = Modifier
      .fillMaxWidth()
      .then(if (isDownloaded) Modifier.clickable(onClick = onSelect) else Modifier)
      .testTag("text_mushaf_card_${item.id}"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = item.title,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = AmiriFontFamily),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "${item.subtitle} • ${"%.1f".format(Locale.US, item.sizeMb)} ميجابايت",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (isDownloaded) {
          IconButton(
            onClick = onRemove,
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Filled.Delete,
              contentDescription = "حذف المصحف",
              tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
            )
          }
        }
      }

      if (state.isDownloading) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "جاري التنزيل (${state.progress.percent}%)",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = onCancel) {
              Text("إلغاء")
            }
          }
          LinearProgressIndicator(
            progress = { (state.progress.percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
              .fillMaxWidth()
              .height(6.dp)
              .clip(RoundedCornerShape(999.dp)),
          )
        }
      } else if (isDownloaded) {
        Button(
          onClick = onSelect,
          colors = if (isActive) {
            ButtonDefaults.buttonColors(
              containerColor = MaterialTheme.colorScheme.primaryContainer,
              contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          } else {
            ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
          },
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .testTag("select_text_mushaf_${item.id}"),
        ) {
          if (isActive) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("المصحف النشط")
          } else {
            Text("اختيار المصحف")
          }
        }
      } else {
        Button(
          onClick = onDownload,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize(),
        ) {
          Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("تنزيل المصحف")
        }
      }
    }
  }
}

@Composable
private fun PdfMushafWideCard(
  entry: PdfMushafEntry,
  isActive: Boolean,
  isOfflineDownloaded: Boolean,
  hasLocalOrCachedFile: Boolean,
  transferState: PdfMushafTransferState,
  onLoadThumbnail: suspend (PdfMushafEntry) -> Bitmap?,
  onSelect: () -> Unit,
  onDownload: () -> Unit,
  onCancel: () -> Unit,
  onDelete: () -> Unit,
  onForceUpdate: () -> Unit,
) {
  var thumbBitmap by remember(entry.id, entry.thumb) { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(entry.id, entry.thumb) {
    thumbBitmap = onLoadThumbnail(entry)
  }

  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    border = BorderStroke(
      width = if (isActive) 2.dp else 1.dp,
      color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("pdf_mushaf_card_${entry.id}"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Compact page thumbnail on the side with clean 3-4 line placeholder while loading
      Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFFAF6EC),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        modifier = Modifier.size(width = 66.dp, height = 98.dp),
      ) {
        val loadedThumb = thumbBitmap
        if (loadedThumb != null) {
          Image(
            bitmap = loadedThumb.asImageBitmap(),
            contentDescription = entry.riwayaAr,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          // Placeholder with 3-4 lines near the top representing a Mushaf page
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            repeat(4) { idx ->
              Box(
                modifier = Modifier
                  .fillMaxWidth(if (idx == 0) 0.72f else if (idx == 3) 0.85f else 1f)
                  .height(3.dp)
                  .clip(RoundedCornerShape(2.dp))
                  .background(Color(0xFF12403C).copy(alpha = 0.28f)),
              )
            }
          }
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          text = entry.riwayaAr,
          style = MaterialTheme.typography.titleMedium.copy(fontFamily = AmiriFontFamily),
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )

        if (entry.titleAr.isNotBlank() && entry.titleAr != entry.riwayaAr) {
          Text(
            text = entry.titleAr,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Text(
          text = "${entry.pages} صفحة • ${"%.1f".format(Locale.US, entry.sizeMB)} ميجابايت",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.weight(1f),
          ) {
            Icon(
              imageVector = if (isOfflineDownloaded) Icons.Filled.OfflinePin else Icons.Filled.Public,
              contentDescription = null,
              tint = if (isOfflineDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
              modifier = Modifier.size(15.dp),
            )
            Text(
              text = if (isOfflineDownloaded) "محمّل - يعمل بدون إنترنت" else "أونلاين",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Medium,
              color = if (isOfflineDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }

          if (hasLocalOrCachedFile && !transferState.isBusy) {
            TextButton(
              onClick = onForceUpdate,
              contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
              modifier = Modifier.testTag("force_update_pdf_${entry.id}"),
            ) {
              Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
              )
              Spacer(Modifier.width(3.dp))
              Text("تحديث الملف", style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
          }
        }

        if (transferState.errorMessage != null) {
          Text(
            text = transferState.errorMessage,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }

        if (transferState.isBusy) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                text = if (transferState.isDownloadingOffline) {
                  "جاري التنزيل (${transferState.progressPercent}%)"
                } else {
                  "جاري التحميل للفتح (${transferState.progressPercent}%)"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
              )
              TextButton(
                onClick = onCancel,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
              ) {
                Text("إلغاء", style = MaterialTheme.typography.labelSmall)
              }
            }
            LinearProgressIndicator(
              progress = { (transferState.progressPercent / 100f).coerceIn(0f, 1f) },
              modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
            )
          }
        } else {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Button(
              onClick = onSelect,
              colors = if (isActive) {
                ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )
              } else {
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
              },
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
              modifier = Modifier
                .weight(1f)
                .minimumInteractiveComponentSize()
                .testTag("select_pdf_mushaf_${entry.id}"),
            ) {
              if (isActive) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("المصحف النشط", maxLines = 1, overflow = TextOverflow.Ellipsis)
              } else {
                Text("اختيار المصحف", maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }

            if (isOfflineDownloaded) {
              OutlinedButton(
                onClick = onDelete,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("delete_pdf_mushaf_${entry.id}"),
              ) {
                Icon(
                  imageVector = Icons.Filled.Delete,
                  contentDescription = "حذف المصحف",
                  tint = MaterialTheme.colorScheme.error,
                  modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("حذف", color = MaterialTheme.colorScheme.error, maxLines = 1)
              }
            } else {
              OutlinedButton(
                onClick = onDownload,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("download_pdf_mushaf_${entry.id}"),
              ) {
                Icon(
                  imageVector = Icons.Filled.CloudDownload,
                  contentDescription = "تنزيل المصحف",
                  modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text("تنزيل", maxLines = 1)
              }
            }
          }
        }
      }
    }
  }
}
