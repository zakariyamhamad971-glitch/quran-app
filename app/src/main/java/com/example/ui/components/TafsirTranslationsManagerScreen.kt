package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.quran.DownloadedTafsirEdition
import com.example.data.quran.TafsirCatalogUiState
import com.example.data.quran.TafsirDownloadState
import com.example.data.quran.TafsirEditionInfo
import com.example.data.quran.TafsirRegistryRepository

private fun toArabicIndicDigitsMgr(number: Int): String {
  val digits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
  val s = number.toString()
  return buildString(s.length) {
    for (ch in s) {
      append(if (ch in '0'..'9') digits[ch - '0'] else ch)
    }
  }
}

@Composable
fun TafsirTranslationsManagerScreen(
  catalogState: TafsirCatalogUiState,
  downloadStates: Map<String, TafsirDownloadState>,
  onRetryLoadCatalog: () -> Unit,
  onStartOrResumeDownload: (TafsirEditionInfo) -> Unit,
  onCancelDownload: (String) -> Unit,
  onUpdateDownloaded: (DownloadedTafsirEdition) -> Unit,
  onDeleteDownloaded: (String) -> Unit,
  onClose: () -> Unit,
) {
  BackHandler(onBack = onClose)

  var searchQuery by rememberSaveable { mutableStateOf("") }
  var pendingDeleteTarget by remember { mutableStateOf<DownloadedTafsirEdition?>(null) }

  val normalizedQuery = remember(searchQuery) {
    TafsirRegistryRepository.normalizeSearchText(searchQuery)
  }

  val downloadedById = remember(catalogState.downloadedEditions) {
    catalogState.downloadedEditions.associateBy { it.editionId }
  }

  val filteredDownloaded = remember(catalogState.downloadedEditions, normalizedQuery) {
    val completeOrPartial = catalogState.downloadedEditions.filter { it.isComplete }
    if (normalizedQuery.isBlank()) {
      completeOrPartial
    } else {
      completeOrPartial.filter { item ->
        TafsirRegistryRepository.normalizeSearchText(
          "${item.nameAr} ${item.authorName} ${item.languageLabelAr} ${item.languageKey} ${item.slug}",
        ).contains(normalizedQuery)
      }
    }
  }

  val groupedAvailable = remember(catalogState.availableEditions, downloadedById, normalizedQuery) {
    val filtered = catalogState.availableEditions.filter { item ->
      val existing = downloadedById[item.id]
      if (existing != null && existing.isComplete) return@filter false
      if (normalizedQuery.isBlank()) return@filter true
      TafsirRegistryRepository.normalizeSearchText(
        "${item.nameAr} ${item.authorName} ${item.languageLabelAr} ${item.languageKey} ${item.slug}",
      ).contains(normalizedQuery)
    }

    val grouped = filtered.groupBy { it.languageLabelAr }
    grouped.entries.sortedWith(
      compareBy<Map.Entry<String, List<TafsirEditionInfo>>>(
        {
          when (it.value.firstOrNull()?.languageKey) {
            "arabic" -> 0
            "english" -> 1
            "urdu" -> 2
            else -> 3
          }
        },
        { it.key },
      ),
    )
  }

  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Surface(
      color = MaterialTheme.colorScheme.background,
      modifier = Modifier
        .fillMaxSize()
        .testTag("tafsir_translations_manager_screen"),
    ) {
      Column(
        modifier = Modifier
          .fillMaxSize()
          .windowInsetsPadding(WindowInsets.statusBars),
      ) {
        // Header bar
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
          ) {
            IconButton(
              onClick = onClose,
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("tafsir_manager_back_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "رجوع",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            Spacer(Modifier.width(4.dp))
            Column {
              Text(
                text = "الترجمات والتفاسير",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
              )
              Text(
                text = "تنزيل المصادر كاملة للقراءة دون اتصال",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          IconButton(
            onClick = onRetryLoadCatalog,
            enabled = !catalogState.isLoading,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("tafsir_manager_refresh_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.Refresh,
              contentDescription = "تحديث القائمة",
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        }

        // Search input
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          placeholder = { Text("ابحث باسم التفسير أو الترجمة أو اللغة...") },
          singleLine = true,
          leadingIcon = {
            Icon(
              imageVector = Icons.Filled.Search,
              contentDescription = null,
            )
          },
          trailingIcon = {
            if (searchQuery.isNotEmpty()) {
              IconButton(onClick = { searchQuery = "" }) {
                Icon(
                  imageVector = Icons.Filled.Close,
                  contentDescription = "مسح البحث",
                )
              }
            }
          },
          shape = RoundedCornerShape(14.dp),
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .testTag("tafsir_manager_search_input"),
        )

        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          // Section 1: الملفات المحملة
          item(key = "header_downloaded") {
            Text(
              text = "الملفات المحملة",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier
                .padding(top = 4.dp)
                .testTag("tafsir_section_downloaded"),
            )
          }

          if (filteredDownloaded.isEmpty()) {
            item(key = "downloaded_empty") {
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Text(
                  text = if (normalizedQuery.isBlank()) {
                    "لا توجد تفاسير أو ترجمات محملة بعد. اختر من القائمة بالأسفل للتنزيل."
                  } else {
                    "لا توجد ملفات محملة مطابقة للبحث."
                  },
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  modifier = Modifier.padding(14.dp),
                )
              }
            }
          } else {
            items(
              items = filteredDownloaded,
              key = { "dl_${it.editionId}" },
            ) { dl ->
              val dlState = downloadStates[dl.editionId]
              DownloadedTafsirCard(
                item = dl,
                transferState = dlState,
                onUpdate = { onUpdateDownloaded(dl) },
                onCancel = { onCancelDownload(dl.editionId) },
                onDelete = { pendingDeleteTarget = dl },
              )
            }
          }

          item(key = "divider_sections") {
            HorizontalDivider(
              color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
              modifier = Modifier.padding(vertical = 4.dp),
            )
          }

          // Section 2: المتاحة للتحميل
          item(key = "header_available") {
            Text(
              text = "المتاحة للتحميل",
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.testTag("tafsir_section_available"),
            )
          }

          if (catalogState.isLoading && catalogState.availableEditions.isEmpty()) {
            item(key = "catalog_loading") {
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
                    text = "جاري تحميل قائمة التفاسير والترجمات...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          } else if (catalogState.errorMessage != null && catalogState.availableEditions.isEmpty()) {
            item(key = "catalog_error") {
              Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                  horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                  Text(
                    text = catalogState.errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  Spacer(Modifier.height(10.dp))
                  Button(onClick = onRetryLoadCatalog) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("إعادة المحاولة")
                  }
                }
              }
            }
          } else if (groupedAvailable.isEmpty()) {
            item(key = "available_empty") {
              Text(
                text = "لا توجد نتائج مطابقة للبحث في القائمة المتاحة.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 12.dp),
              )
            }
          } else {
            for ((languageLabel, editions) in groupedAvailable) {
              item(key = "lang_hdr_$languageLabel") {
                Text(
                  text = "$languageLabel (${toArabicIndicDigitsMgr(editions.size)})",
                  style = MaterialTheme.typography.titleSmall,
                  fontWeight = FontWeight.SemiBold,
                  color = MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
              }
              items(
                items = editions,
                key = { "avail_${it.id}" },
              ) { edition ->
                val partial = downloadedById[edition.id]?.takeUnless { it.isComplete }
                val transfer = downloadStates[edition.id]
                AvailableTafsirCard(
                  edition = edition,
                  partialDownloaded = partial,
                  transferState = transfer,
                  onStartOrResume = { onStartOrResumeDownload(edition) },
                  onCancel = { onCancelDownload(edition.id) },
                )
              }
            }
          }

          item(key = "bottom_spacer") {
            Spacer(Modifier.height(24.dp))
          }
        }
      }
    }
  }

  pendingDeleteTarget?.let { target ->
    AlertDialog(
      onDismissRequest = { pendingDeleteTarget = null },
      title = { Text("حذف المصدر") },
      text = { Text("هل تريد حذف \"${target.nameAr}\" من ذاكرة الجهاز؟") },
      confirmButton = {
        TextButton(
          onClick = {
            val id = target.editionId
            pendingDeleteTarget = null
            onDeleteDownloaded(id)
          },
          colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
          Text("حذف")
        }
      },
      dismissButton = {
        TextButton(onClick = { pendingDeleteTarget = null }) {
          Text("إلغاء")
        }
      },
    )
  }
}

@Composable
private fun DownloadedTafsirCard(
  item: DownloadedTafsirEdition,
  transferState: TafsirDownloadState?,
  onUpdate: () -> Unit,
  onCancel: () -> Unit,
  onDelete: () -> Unit,
) {
  val isUpdating = transferState?.isDownloading == true
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("downloaded_tafsir_card_${item.slug}"),
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
        verticalAlignment = Alignment.Top,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.Filled.CheckCircle,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text = item.nameAr,
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.Bold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          Spacer(Modifier.height(2.dp))
          val metaParts = listOfNotNull(
            item.type.labelAr,
            item.languageLabelAr,
            item.authorName.takeIf { it.isNotBlank() && it != item.nameAr },
          )
          Text(
            text = metaParts.joinToString(" • "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "الحجم: ${TafsirRegistryRepository.formatBytesAr(item.downloadedBytes)} • ${toArabicIndicDigitsMgr(item.completedSurahs)}/${toArabicIndicDigitsMgr(item.totalSurahs)} سورة",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
      }

      if (isUpdating && transferState != null) {
        LinearProgressIndicator(
          progress = { transferState.progressFraction },
          modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp)),
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "جاري التحديث (${toArabicIndicDigitsMgr(transferState.completedSurahs)}/${toArabicIndicDigitsMgr(transferState.totalSurahs)}) • ${TafsirRegistryRepository.formatBytesAr(transferState.downloadedBytes)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedButton(onClick = onCancel, modifier = Modifier.minimumInteractiveComponentSize()) {
            Text("إيقاف")
          }
        }
      } else {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedButton(
            onClick = onUpdate,
            modifier = Modifier
              .weight(1f)
              .minimumInteractiveComponentSize()
              .testTag("update_tafsir_${item.slug}"),
          ) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("تحديث")
          }
          OutlinedButton(
            onClick = onDelete,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
              .weight(1f)
              .minimumInteractiveComponentSize()
              .testTag("delete_tafsir_${item.slug}"),
          ) {
            Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("حذف")
          }
        }
      }
    }
  }
}

@Composable
private fun AvailableTafsirCard(
  edition: TafsirEditionInfo,
  partialDownloaded: DownloadedTafsirEdition?,
  transferState: TafsirDownloadState?,
  onStartOrResume: () -> Unit,
  onCancel: () -> Unit,
) {
  val isDownloading = transferState?.isDownloading == true
  val doneSurahs = transferState?.completedSurahs ?: partialDownloaded?.completedSurahs ?: 0
  val doneBytes = transferState?.downloadedBytes ?: partialDownloaded?.downloadedBytes ?: 0L
  val hasError = !isDownloading && transferState?.errorMessage != null

  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("available_tafsir_card_${edition.slug}"),
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
            text = edition.nameAr,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          Spacer(Modifier.height(2.dp))
          val metaParts = listOfNotNull(
            edition.type.labelAr,
            edition.authorName.takeIf { it.isNotBlank() && it != edition.nameAr },
            edition.sourceName.takeIf { it.isNotBlank() },
          )
          if (metaParts.isNotEmpty()) {
            Text(
              text = metaParts.joinToString(" • "),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis,
            )
          }
          val sizeLabel = when {
            isDownloading -> "${toArabicIndicDigitsMgr(doneSurahs)}/${toArabicIndicDigitsMgr(114)} سورة • ${TafsirRegistryRepository.formatBytesAr(doneBytes)}"
            doneSurahs > 0 -> "محمّل جزئياً: ${toArabicIndicDigitsMgr(doneSurahs)}/${toArabicIndicDigitsMgr(114)} سورة (${TafsirRegistryRepository.formatBytesAr(doneBytes)})"
            else -> "١١٤ سورة • الحجم الكامل يُحسب أثناء التحميل"
          }
          Text(
            text = sizeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = if (hasError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
          )
          if (hasError && transferState?.errorMessage != null) {
            Text(
              text = transferState.errorMessage,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }

        Spacer(Modifier.width(10.dp))

        if (isDownloading) {
          OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("pause_tafsir_${edition.slug}"),
          ) {
            Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("إيقاف")
          }
        } else {
          FilledTonalButton(
            onClick = onStartOrResume,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .testTag("download_tafsir_${edition.slug}"),
          ) {
            Icon(
              imageVector = if (hasError || doneSurahs > 0) Icons.Filled.Refresh else Icons.Filled.CloudDownload,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(if (doneSurahs > 0) "استكمال" else "تحميل")
          }
        }
      }

      if (isDownloading || doneSurahs > 0) {
        val frac = (doneSurahs.toFloat() / 114f).coerceIn(0f, 1f)
        LinearProgressIndicator(
          progress = { frac },
          modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(999.dp)),
        )
      }
    }
  }
}
