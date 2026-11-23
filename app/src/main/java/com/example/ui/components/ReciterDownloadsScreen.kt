package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.data.audio.AudioBatchUiSummary
import com.example.data.audio.AudioCacheManager
import com.example.data.audio.AudioRepository
import com.example.data.audio.ReciterStorageSummary
import com.example.data.audio.SurahDownloadStatus
import com.example.data.audio.SurahTransferState
import com.example.data.audio.VerifiedReciter
import com.example.data.pdf.PdfMushafEntry
import com.example.data.quran.SurahMeta
import com.example.ui.theme.AmiriFontFamily

@Composable
fun rememberNotificationPermissionRequester(): () -> Unit {
  val context = LocalContext.current
  var askedOnce by rememberSaveable { mutableStateOf(false) }
  val launcher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { _ -> }

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

data class RiwayaUiGroup(
  val id: String,
  val displayName: String,
  val apiRiwayaIds: Set<Int> = emptySet(),
)

private val HAFS_RIWAYA_GROUP = RiwayaUiGroup(
  id = "hafs",
  displayName = "حفص عن عاصم",
  apiRiwayaIds = setOf(1),
)

// Fallback labels in exact manifest order when manifest entries are not yet cached/fetched offline
private val FALLBACK_PDF_RIWAYAT_GROUPS = listOf(
  RiwayaUiGroup("fallback_qaloon", "قالون عن نافع"),
  RiwayaUiGroup("fallback_warsh", "ورش عن نافع"),
  RiwayaUiGroup("fallback_bazzi", "البزي عن ابن كثير"),
  RiwayaUiGroup("fallback_qunbul", "قنبل عن ابن كثير"),
  RiwayaUiGroup("fallback_duri_abu_amr", "الدوري عن أبي عمرو"),
  RiwayaUiGroup("fallback_soosi", "السوسي عن أبي عمرو"),
  RiwayaUiGroup("fallback_hisham", "هشام عن ابن عامر"),
  RiwayaUiGroup("fallback_ibn_dhakwan", "ابن ذكوان عن ابن عامر"),
  RiwayaUiGroup("fallback_shubah", "شعبة عن عاصم"),
  RiwayaUiGroup("fallback_khalaf", "خلف عن حمزة"),
  RiwayaUiGroup("fallback_khallad", "خلاد عن حمزة"),
  RiwayaUiGroup("fallback_abu_harith", "أبو الحارث عن الكسائي"),
  RiwayaUiGroup("fallback_duri_kisai", "الدوري عن الكسائي"),
  RiwayaUiGroup("fallback_ibn_wardan", "ابن وردان عن أبي جعفر"),
  RiwayaUiGroup("fallback_ibn_jammaz", "ابن جماز عن أبي جعفر"),
  RiwayaUiGroup("fallback_ruways", "رويس عن يعقوب الحضرمي"),
  RiwayaUiGroup("fallback_rawh", "روح عن يعقوب الحضرمي"),
  RiwayaUiGroup("fallback_idris", "إدريس الحداد عن خلف العاشر"),
  RiwayaUiGroup("fallback_ishaq", "إسحاق الوراق عن خلف العاشر"),
)

/**
 * Builds Page A's ordered riwayat groups (Hafs first, then the 19 PDF mushaf entries in exact manifest order,
 * plus "تلاوات أخرى" at bottom if any unmapped reciters exist) and assigns reciters strictly via
 * Hafs default catalog + `PdfMushafEntry.apiRiwayaIds` without keyword guessing.
 */
fun buildRiwayaGroupsAndAssignReciters(
  allReciters: List<VerifiedReciter>,
  pdfMushafs: List<PdfMushafEntry>,
): Pair<List<RiwayaUiGroup>, Map<String, List<VerifiedReciter>>> {
  val pdfGroups = if (pdfMushafs.isNotEmpty()) {
    pdfMushafs
      .sortedBy { it.order }
      .filterNot { it.id == "hafs" || it.apiRiwayaIds == listOf(1) }
      .map { entry ->
        RiwayaUiGroup(
          id = "pdf_${entry.id}",
          displayName = entry.riwayaAr.ifBlank { entry.titleAr },
          apiRiwayaIds = entry.apiRiwayaIds.toSet(),
        )
      }
  } else {
    FALLBACK_PDF_RIWAYAT_GROUPS
  }

  val byRiwayaId = HashMap<Int, RiwayaUiGroup>()
  for (group in pdfGroups) {
    for (rId in group.apiRiwayaIds) {
      if (rId != 1 && !byRiwayaId.containsKey(rId)) {
        byRiwayaId[rId] = group
      }
    }
  }

  val recitersByGroup = HashMap<String, MutableList<VerifiedReciter>>()
  for (r in allReciters) {
    val targetGroupId = when {
      (!r.isPdfModeReciter && (r.riwayaId == null || r.riwayaId == 1)) || r.riwayaId == 1 -> HAFS_RIWAYA_GROUP.id
      r.riwayaId != null -> byRiwayaId[r.riwayaId]?.id ?: "other"
      else -> "other"
    }
    recitersByGroup.getOrPut(targetGroupId) { ArrayList() }.add(r)
  }

  val displayGroups = ArrayList<RiwayaUiGroup>(1 + pdfGroups.size + 1)
  displayGroups += HAFS_RIWAYA_GROUP
  displayGroups += pdfGroups
  if (recitersByGroup["other"].orEmpty().isNotEmpty()) {
    displayGroups += RiwayaUiGroup(id = "other", displayName = "تلاوات أخرى")
  }
  return displayGroups to recitersByGroup
}

sealed interface ReciterNavDestination {
  data object PageARiwayatList : ReciterNavDestination
  data class PageBRiwayaReciters(val riwayaId: String, val riwayaName: String) : ReciterNavDestination
  data class PageCReciterSurahs(val riwayaId: String, val riwayaName: String, val reciterKey: String) : ReciterNavDestination
}

/**
 * Three-screen full-screen reciter manager:
 * Page A: 20 Riwayat list (Hafs first, 19 PDF mushaf riwayat in exact manifest order, then others).
 * Page B: Searchable, alphabetically sorted reciters of chosen riwaya.
 * Page C: 114 surahs download list for chosen reciter.
 */
@Composable
fun FullReciterManagerScreen(
  allReciters: List<VerifiedReciter>,
  surahs: List<SurahMeta>,
  downloadedKeys: Set<String>,
  transferStates: Map<String, SurahTransferState>,
  reciterSummaries: Map<String, ReciterStorageSummary>,
  batchSummary: AudioBatchUiSummary,
  onDownloadSurah: (VerifiedReciter, Int) -> Unit,
  onCancelSurah: (String, Int) -> Unit,
  onDeleteSurah: (VerifiedReciter, Int) -> Unit,
  onDownloadAll: (VerifiedReciter) -> Unit,
  onCancelAllForReciter: (String) -> Unit,
  onDeleteAllForReciter: (VerifiedReciter) -> Unit,
  onDismissLowStorageMessage: () -> Unit,
  onClose: () -> Unit,
  pdfMushafs: List<PdfMushafEntry> = emptyList(),
  recitersLoading: Boolean = false,
  recitersError: String? = null,
  onRetryLoadReciters: () -> Unit = {},
  initialReciterKey: String? = null,
  modifier: Modifier = Modifier,
) {
  val (displayGroups, recitersByGroup) = remember(allReciters, pdfMushafs) {
    buildRiwayaGroupsAndAssignReciters(allReciters = allReciters, pdfMushafs = pdfMushafs)
  }

  val initialDest = remember(initialReciterKey, allReciters, displayGroups, recitersByGroup) {
    if (!initialReciterKey.isNullOrBlank()) {
      val r = allReciters.firstOrNull { it.key == initialReciterKey }
      if (r != null) {
        val matchedGroup = displayGroups.firstOrNull { group ->
          recitersByGroup[group.id].orEmpty().any { it.key == r.key }
        }
        ReciterNavDestination.PageCReciterSurahs(
          riwayaId = matchedGroup?.id ?: "other",
          riwayaName = matchedGroup?.displayName ?: r.riwayaName.ifBlank { "تلاوات أخرى" },
          reciterKey = r.key,
        )
      } else {
        ReciterNavDestination.PageARiwayatList
      }
    } else {
      ReciterNavDestination.PageARiwayatList
    }
  }

  var currentDest by remember { mutableStateOf<ReciterNavDestination>(initialDest) }

  when (val dest = currentDest) {
    is ReciterNavDestination.PageARiwayatList -> {
      PageARiwayatListScreen(
        groups = displayGroups,
        recitersByGroup = recitersByGroup,
        reciterSummaries = reciterSummaries,
        allRecitersEmpty = allReciters.isEmpty(),
        recitersLoading = recitersLoading,
        recitersError = recitersError,
        onRetryLoadReciters = onRetryLoadReciters,
        onSelectRiwaya = { group ->
          currentDest = ReciterNavDestination.PageBRiwayaReciters(
            riwayaId = group.id,
            riwayaName = group.displayName,
          )
        },
        onClose = onClose,
        modifier = modifier,
      )
    }

    is ReciterNavDestination.PageBRiwayaReciters -> {
      val reciters = remember(dest.riwayaId, recitersByGroup) {
        recitersByGroup[dest.riwayaId].orEmpty()
      }
      PageBRiwayaRecitersScreen(
        riwayaName = dest.riwayaName,
        reciters = reciters,
        reciterSummaries = reciterSummaries,
        onSelectReciter = { reciter ->
          currentDest = ReciterNavDestination.PageCReciterSurahs(
            riwayaId = dest.riwayaId,
            riwayaName = dest.riwayaName,
            reciterKey = reciter.key,
          )
        },
        onBack = { currentDest = ReciterNavDestination.PageARiwayatList },
        modifier = modifier,
      )
    }

    is ReciterNavDestination.PageCReciterSurahs -> {
      val reciter = remember(dest.reciterKey, allReciters) {
        allReciters.firstOrNull { it.key == dest.reciterKey }
      }
      if (reciter == null) {
        currentDest = ReciterNavDestination.PageBRiwayaReciters(dest.riwayaId, dest.riwayaName)
      } else {
        PageCReciterSurahsScreen(
          reciter = reciter,
          riwayaName = dest.riwayaName,
          surahs = surahs,
          downloadedKeys = downloadedKeys,
          transferStates = transferStates,
          reciterSummary = reciterSummaries[reciter.key] ?: ReciterStorageSummary(),
          batchSummary = batchSummary,
          onDownloadSurah = { sId -> onDownloadSurah(reciter, sId) },
          onCancelSurah = { sId -> onCancelSurah(reciter.key, sId) },
          onDeleteSurah = { sId -> onDeleteSurah(reciter, sId) },
          onDownloadAll = { onDownloadAll(reciter) },
          onCancelAllForReciter = { onCancelAllForReciter(reciter.key) },
          onDeleteAllForReciter = { onDeleteAllForReciter(reciter) },
          onDismissLowStorageMessage = onDismissLowStorageMessage,
          onBack = {
            currentDest = ReciterNavDestination.PageBRiwayaReciters(dest.riwayaId, dest.riwayaName)
          },
          modifier = modifier,
        )
      }
    }
  }
}

// -------------------------------------------------------------
// PAGE A: 20 Riwayat List Screen
// -------------------------------------------------------------
@Composable
private fun PageARiwayatListScreen(
  groups: List<RiwayaUiGroup>,
  recitersByGroup: Map<String, List<VerifiedReciter>>,
  reciterSummaries: Map<String, ReciterStorageSummary>,
  allRecitersEmpty: Boolean,
  recitersLoading: Boolean,
  recitersError: String?,
  onRetryLoadReciters: () -> Unit,
  onSelectRiwaya: (RiwayaUiGroup) -> Unit,
  onClose: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onClose)

  Surface(
    modifier = modifier
      .fillMaxSize()
      .testTag("reciters_page_a_riwayat_screen"),
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
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            IconButton(
              onClick = onClose,
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("reciters_page_a_back_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "رجوع",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            Spacer(Modifier.width(8.dp))
            Column {
              Text(
                text = "إدارة القراء",
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = AmiriFontFamily),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "اختر الرواية لاستعراض القراء وتحميل التلاوات",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        if (allRecitersEmpty && recitersLoading) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
              .padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(strokeWidth = 2.5.dp)
              Spacer(Modifier.height(12.dp))
              Text(
                text = "جاري تحميل قائمة القراء...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        } else if (allRecitersEmpty && !recitersError.isNullOrBlank()) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
              .padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Text(
                text = recitersError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
              )
              Button(
                onClick = onRetryLoadReciters,
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("reciters_page_a_retry_button"),
              ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("إعادة المحاولة")
              }
            }
          }
        } else {
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
              .windowInsetsPadding(WindowInsets.navigationBars)
              .testTag("reciters_riwayat_list"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(
              items = groups,
              key = { "riwaya_group_${it.id}" },
            ) { group ->
              val reciters = recitersByGroup[group.id].orEmpty()
              val hasReciters = reciters.isNotEmpty()

              val downloadedSurahs = if (hasReciters) {
                reciters.sumOf { reciterSummaries[it.key]?.downloadedCount ?: 0 }
              } else 0
              val totalBytes = if (hasReciters) {
                reciters.sumOf { reciterSummaries[it.key]?.totalBytes ?: 0L }
              } else 0L

              val subtitleText = when {
                !hasReciters -> "لا يوجد قارئ متاح لهذه الرواية حاليًا"
                downloadedSurahs > 0 ->
                  "${toArabicDigits(reciters.size)} قارئ • ${toArabicDigits(downloadedSurahs)} سورة محملة • ${AudioCacheManager.formatBytesArabic(totalBytes)}"
                else -> "${toArabicDigits(reciters.size)} قارئ"
              }

              Surface(
                onClick = { if (hasReciters) onSelectRiwaya(group) },
                enabled = hasReciters,
                shape = RoundedCornerShape(12.dp),
                color = if (hasReciters) {
                  MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                } else {
                  MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
                },
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("riwaya_row_${group.id}"),
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(
                    modifier = Modifier
                      .weight(1f)
                      .alpha(if (hasReciters) 1f else 0.65f),
                  ) {
                    Text(
                      text = group.displayName,
                      style = MaterialTheme.typography.titleMedium.copy(fontFamily = AmiriFontFamily),
                      fontWeight = FontWeight.Bold,
                      color = if (hasReciters) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                      text = subtitleText,
                      style = MaterialTheme.typography.bodySmall,
                      color = if (hasReciters) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline,
                    )
                  }

                  if (hasReciters) {
                    Icon(
                      imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                    )
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

// -------------------------------------------------------------
// PAGE B: Searchable Reciters List for Chosen Riwaya
// -------------------------------------------------------------
@Composable
private fun PageBRiwayaRecitersScreen(
  riwayaName: String,
  reciters: List<VerifiedReciter>,
  reciterSummaries: Map<String, ReciterStorageSummary>,
  onSelectReciter: (VerifiedReciter) -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onBack)
  var searchQuery by rememberSaveable { mutableStateOf("") }

  val sortedReciters = remember(reciters) {
    reciters.sortedBy { AudioRepository.normalizeArabicForSearch(it.displayName) }
  }

  val filteredReciters = remember(sortedReciters, searchQuery) {
    val q = AudioRepository.normalizeArabicForSearch(searchQuery)
    if (q.isEmpty()) {
      sortedReciters
    } else {
      sortedReciters.filter { r ->
        AudioRepository.normalizeArabicForSearch(r.displayName).contains(q) ||
          AudioRepository.normalizeArabicForSearch(r.requestedName).contains(q)
      }
    }
  }

  Surface(
    modifier = modifier
      .fillMaxSize()
      .testTag("reciters_page_b_screen"),
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
                onClick = onBack,
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("reciters_page_b_back_button"),
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                  contentDescription = "رجوع",
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
              Spacer(Modifier.width(8.dp))
              Column {
                Text(
                  text = riwayaName,
                  style = MaterialTheme.typography.titleLarge.copy(fontFamily = AmiriFontFamily),
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = "${toArabicDigits(reciters.size)} قارئ متوفر",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }

            // Search box
            OutlinedTextField(
              value = searchQuery,
              onValueChange = { searchQuery = it },
              placeholder = { Text("ابحث عن قارئ بالاسم...") },
              leadingIcon = {
                Icon(Icons.Filled.Search, contentDescription = null)
              },
              trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                  IconButton(onClick = { searchQuery = "" }) {
                    Icon(Icons.Filled.Close, contentDescription = "مسح البحث")
                  }
                }
              },
              singleLine = true,
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier
                .fillMaxWidth()
                .testTag("reciters_page_b_search_input"),
            )
          }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

        if (filteredReciters.isEmpty()) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
              .padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = if (searchQuery.isNotBlank()) "لا يوجد قارئ مطابق لبحثك" else "لا يوجد قراء متوفرين",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              textAlign = TextAlign.Center,
            )
          }
        } else {
          LazyColumn(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f)
              .windowInsetsPadding(WindowInsets.navigationBars)
              .testTag("reciters_page_b_list"),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(
              items = filteredReciters,
              key = { "reciter_item_${it.key}" },
            ) { reciter ->
              val summary = reciterSummaries[reciter.key]
              val downloadedCount = summary?.downloadedCount ?: 0
              val totalBytes = summary?.totalBytes ?: 0L

              val subtitle = if (downloadedCount == 0) {
                "لم يتم تحميل سور بعد"
              } else {
                "${toArabicDigits(downloadedCount)} من ${toArabicDigits(reciter.availableSurahs.size)} محملة • ${AudioCacheManager.formatBytesArabic(totalBytes)}"
              }

              Surface(
                onClick = { onSelectReciter(reciter) },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("reciter_row_${reciter.key}"),
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = reciter.displayName,
                      style = MaterialTheme.typography.titleMedium.copy(fontFamily = AmiriFontFamily),
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                      text = subtitle,
                      style = MaterialTheme.typography.bodySmall,
                      color = if (downloadedCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }

                  Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

// -------------------------------------------------------------
// PAGE C: 114 Surahs Download List for Chosen Reciter
// -------------------------------------------------------------
@Composable
private fun PageCReciterSurahsScreen(
  reciter: VerifiedReciter,
  riwayaName: String,
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
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  BackHandler(onBack = onBack)

  val requestNotifPermissionIfNeeded = rememberNotificationPermissionRequester()
  var showDeleteAllConfirm by remember { mutableStateOf(false) }
  var showOverflowMenu by remember { mutableStateOf(false) }
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
  val overallProgressFraction = remember(reciter.availableSurahs, reciter.key, downloadedKeys, reciterSummary, transferStates) {
    if (availableCount <= 0) {
      0f
    } else {
      var sum = 0f
      for (sId in reciter.availableSurahs) {
        val key = "${reciter.key}_${padSurah(sId)}"
        sum += when {
          downloadedKeys.contains(key) || reciterSummary.downloadedSurahs.contains(sId) -> 1f
          else -> {
            val st = transferStates[key]
            if (st != null && (st.status == SurahDownloadStatus.DOWNLOADING || st.status == SurahDownloadStatus.PAUSED_OFFLINE)) {
              st.progress.coerceIn(0f, 1f)
            } else {
              0f
            }
          }
        }
      }
      (sum / availableCount.toFloat()).coerceIn(0f, 1f)
    }
  }
  val overallProgressPercent = (overallProgressFraction * 100f).toInt().coerceIn(0, 100)

  Surface(
    modifier = modifier
      .fillMaxSize()
      .testTag("reciters_page_c_screen"),
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
        // Top Bar (with Download All / Stop action and overflow Delete All inside the top bar row)
        Surface(
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 2.dp,
          modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars),
        ) {
          Column(modifier = Modifier.fillMaxWidth()) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              IconButton(
                onClick = onBack,
                modifier = Modifier
                  .minimumInteractiveComponentSize()
                  .testTag("reciters_page_c_back_button"),
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
                  style = MaterialTheme.typography.titleMedium.copy(fontFamily = AmiriFontFamily),
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                val sizeLabel = AudioCacheManager.formatBytesArabic(reciterSummary.totalBytes)
                Text(
                  text = "$riwayaName • ${toArabicDigits(downloadedCount)} من ${toArabicDigits(availableCount)} • $sizeLabel",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
              }

              Spacer(Modifier.width(4.dp))

              if (hasActiveOrQueued) {
                FilledTonalButton(
                  onClick = onCancelAllForReciter,
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                  shape = RoundedCornerShape(10.dp),
                  colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                  ),
                  modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("reciter_stop_all_button"),
                ) {
                  CircularProgressIndicator(
                    progress = { overallProgressFraction.coerceAtLeast(0.05f) },
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(14.dp),
                  )
                  Spacer(Modifier.width(6.dp))
                  Text(
                    text = "إيقاف ${toArabicDigits(overallProgressPercent)}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                  )
                }
              } else {
                FilledTonalButton(
                  onClick = {
                    requestNotifPermissionIfNeeded()
                    onDownloadAll()
                  },
                  enabled = missingAvailableCount > 0,
                  contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("reciter_download_all_button"),
                ) {
                  Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(Modifier.width(4.dp))
                  Text(
                    text = "تحميل الكل",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                  )
                }
              }

              // Overflow menu with Delete All
              Box {
                IconButton(
                  onClick = { showOverflowMenu = true },
                  modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("reciter_surahs_overflow_menu_button"),
                ) {
                  Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "المزيد من الخيارات",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }

                DropdownMenu(
                  expanded = showOverflowMenu,
                  onDismissRequest = { showOverflowMenu = false },
                ) {
                  DropdownMenuItem(
                    text = {
                      val totalSizeStr = AudioCacheManager.formatBytesArabic(reciterSummary.totalBytes)
                      Text("حذف الكل ($totalSizeStr)", color = MaterialTheme.colorScheme.error)
                    },
                    leadingIcon = {
                      Icon(
                        Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                      )
                    },
                    enabled = downloadedCount > 0 || hasActiveOrQueued,
                    onClick = {
                      showOverflowMenu = false
                      showDeleteAllConfirm = true
                    },
                  )
                }
              }
            }

            if (hasActiveOrQueued) {
              LinearProgressIndicator(
                progress = { overallProgressFraction },
                modifier = Modifier.fillMaxWidth(),
              )
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

            SurahDownloadItemRow(
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
        val totalSizeStr = AudioCacheManager.formatBytesArabic(reciterSummary.totalBytes)
        Text("هل تريد حذف جميع السور المحملة للقارئ ${reciter.displayName}؟ الحجم الإجمالي: $totalSizeStr")
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
private fun SurahDownloadItemRow(
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
    // Surah info
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

    // Action side with circular download button + tiny label "تحميل هذه السورة"
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
            modifier = Modifier.size(22.dp),
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
        // Circular download button with tiny label "تحميل هذه السورة"
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier.padding(horizontal = 4.dp),
        ) {
          FilledTonalButton(
            onClick = onDownload,
            shape = CircleShape,
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
              .size(38.dp)
              .testTag("reciter_surah_download_${surah.id}"),
          ) {
            Icon(
              imageVector = Icons.Filled.Download,
              contentDescription = "تحميل هذه السورة",
              modifier = Modifier.size(18.dp),
            )
          }
          Spacer(Modifier.height(2.dp))
          Text(
            text = "تحميل هذه السورة",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}

/**
 * Backward-compatible single-reciter download screen.
 */
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
  PageCReciterSurahsScreen(
    reciter = reciter,
    riwayaName = reciter.riwayaName.ifBlank { "حفص عن عاصم" },
    surahs = surahs,
    downloadedKeys = downloadedKeys,
    transferStates = transferStates,
    reciterSummary = reciterSummary,
    batchSummary = batchSummary,
    onDownloadSurah = onDownloadSurah,
    onCancelSurah = onCancelSurah,
    onDeleteSurah = onDeleteSurah,
    onDownloadAll = onDownloadAll,
    onCancelAllForReciter = onCancelAllForReciter,
    onDeleteAllForReciter = onDeleteAllForReciter,
    onDismissLowStorageMessage = onDismissLowStorageMessage,
    onBack = onClose,
    modifier = modifier,
  )
}
