package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.audio.VerifiedReciter
import com.example.data.quran.JuzMeta
import com.example.data.quran.SurahMeta
import com.example.data.quran.VerseSearchHit
import com.example.ui.theme.AmiriFontFamily
import kotlinx.coroutines.delay

private fun normalizeArabicQuery(input: String): String {
  return input
    .replace("أ", "ا")
    .replace("إ", "ا")
    .replace("آ", "ا")
    .replace("ى", "ي")
    .replace("ة", "ه")
    .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
    .trim()
}

@Composable
fun FullScreenQuranIndex(
  surahs: List<SurahMeta>,
  juzs: List<JuzMeta>,
  bookmarks: List<Int>,
  currentPage: Int,
  activeReciter: VerifiedReciter?,
  downloadedKeys: Set<String>,
  downloadProgress: Map<String, Float>,
  verseSearchResults: List<VerseSearchHit>,
  verseSearchLoading: Boolean,
  verseSearchError: String?,
  metadataError: String?,
  hasActiveReaderPage: Boolean,
  onRetryMetadata: () -> Unit,
  onSearchVerses: (String) -> Unit,
  onJumpToPage: (Int) -> Unit,
  onJumpToVerse: (verseKey: String, pageNumber: Int) -> Unit,
  onPlaySurah: (Int) -> Unit,
  onDownloadSurah: (Int) -> Unit,
  onDeleteDownloadedSurah: (Int) -> Unit,
  onRemoveBookmark: (Int) -> Unit,
  onOpenReader: () -> Unit,
  onOpenRangePicker: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "الفهرس الكامل",
          style = MaterialTheme.typography.titleLarge.copy(fontFamily = AmiriFontFamily),
          color = MaterialTheme.colorScheme.primary,
        )
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          OutlinedButton(
            onClick = onOpenRangePicker,
            modifier = Modifier.testTag("index_open_range_picker"),
          ) {
            Icon(
              imageVector = Icons.Filled.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("نطاق الآيات")
          }
          if (hasActiveReaderPage) {
            OutlinedButton(
              onClick = onOpenReader,
              modifier = Modifier.testTag("return_to_mushaf_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
              )
              Spacer(Modifier.width(4.dp))
              Text("ص $currentPage")
            }
          }
        }
      }
      Spacer(Modifier.height(10.dp))

      if (surahs.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          if (metadataError != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              Text(
                text = metadataError,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
              )
              Spacer(Modifier.height(12.dp))
              OutlinedButton(onClick = onRetryMetadata) {
                Text("إعادة تحميل الفهرس")
              }
            }
          } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
              Spacer(Modifier.height(12.dp))
              Text(
                text = "جاري تحميل فهرس القرآن الكريم...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      } else {
        QuranIndexContent(
          surahs = surahs,
          juzs = juzs,
          bookmarks = bookmarks,
          currentPage = currentPage,
          activeReciter = activeReciter,
          downloadedKeys = downloadedKeys,
          downloadProgress = downloadProgress,
          verseSearchResults = verseSearchResults,
          verseSearchLoading = verseSearchLoading,
          verseSearchError = verseSearchError,
          onSearchVerses = onSearchVerses,
          onJumpToPage = { page ->
            onJumpToPage(page)
            onOpenReader()
          },
          onJumpToVerse = { key, page ->
            onJumpToVerse(key, page)
            onOpenReader()
          },
          onPlaySurah = { surahId ->
            onPlaySurah(surahId)
            onOpenReader()
          },
          onDownloadSurah = onDownloadSurah,
          onDeleteDownloadedSurah = onDeleteDownloadedSurah,
          onRemoveBookmark = onRemoveBookmark,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexBottomSheet(
  surahs: List<SurahMeta>,
  juzs: List<JuzMeta>,
  bookmarks: List<Int>,
  currentPage: Int,
  activeReciter: VerifiedReciter?,
  downloadedKeys: Set<String>,
  downloadProgress: Map<String, Float>,
  verseSearchResults: List<VerseSearchHit>,
  verseSearchLoading: Boolean,
  verseSearchError: String?,
  onSearchVerses: (String) -> Unit,
  onJumpToPage: (Int) -> Unit,
  onJumpToVerse: (verseKey: String, pageNumber: Int) -> Unit,
  onPlaySurah: (Int) -> Unit,
  onDownloadSurah: (Int) -> Unit,
  onDeleteDownloadedSurah: (Int) -> Unit,
  onRemoveBookmark: (Int) -> Unit,
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
        .fillMaxHeight(0.88f)
        .padding(horizontal = 16.dp),
    ) {
      Text(
        text = "الفهرس والبحث والمرجعيات",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(Modifier.height(10.dp))

      QuranIndexContent(
        surahs = surahs,
        juzs = juzs,
        bookmarks = bookmarks,
        currentPage = currentPage,
        activeReciter = activeReciter,
        downloadedKeys = downloadedKeys,
        downloadProgress = downloadProgress,
        verseSearchResults = verseSearchResults,
        verseSearchLoading = verseSearchLoading,
        verseSearchError = verseSearchError,
        onSearchVerses = onSearchVerses,
        onJumpToPage = { page ->
          onJumpToPage(page)
          onDismiss()
        },
        onJumpToVerse = { key, page ->
          onJumpToVerse(key, page)
          onDismiss()
        },
        onPlaySurah = onPlaySurah,
        onDownloadSurah = onDownloadSurah,
        onDeleteDownloadedSurah = onDeleteDownloadedSurah,
        onRemoveBookmark = onRemoveBookmark,
        modifier = Modifier.weight(1f),
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuranIndexContent(
  surahs: List<SurahMeta>,
  juzs: List<JuzMeta>,
  bookmarks: List<Int>,
  currentPage: Int,
  activeReciter: VerifiedReciter?,
  downloadedKeys: Set<String>,
  downloadProgress: Map<String, Float>,
  verseSearchResults: List<VerseSearchHit>,
  verseSearchLoading: Boolean,
  verseSearchError: String?,
  onSearchVerses: (String) -> Unit,
  onJumpToPage: (Int) -> Unit,
  onJumpToVerse: (verseKey: String, pageNumber: Int) -> Unit,
  onPlaySurah: (Int) -> Unit,
  onDownloadSurah: (Int) -> Unit,
  onDeleteDownloadedSurah: (Int) -> Unit,
  onRemoveBookmark: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  var selectedTab by remember { mutableIntStateOf(0) }
  var searchQuery by remember { mutableStateOf("") }

  val normalizedQuery = remember(searchQuery) { normalizeArabicQuery(searchQuery) }
  val numericPageQuery = searchQuery.trim().toIntOrNull()?.takeIf { it in 1..604 }

  // Trigger debounced verse search whenever query has >= 2 chars and is not just a page number
  LaunchedEffect(searchQuery) {
    if (numericPageQuery == null && searchQuery.trim().length >= 2) {
      delay(350L)
      onSearchVerses(searchQuery)
    } else {
      onSearchVerses("")
    }
  }

  val filteredSurahs = remember(surahs, normalizedQuery) {
    if (normalizedQuery.isBlank()) {
      surahs
    } else {
      surahs.filter { s ->
        normalizeArabicQuery(s.nameArabic).contains(normalizedQuery, ignoreCase = true) ||
          s.id.toString() == normalizedQuery
      }
    }
  }

  val surahsById = remember(surahs) { surahs.associateBy { it.id } }

  Column(modifier = modifier.fillMaxWidth()) {

      // Search Surahs, Verses (e.g. text or 2:255), or Page Number (1..604)
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("ابحث عن سورة أو آية (أو 2:255) أو رقم صفحة") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(Icons.Filled.Clear, contentDescription = "مسح البحث")
            }
          }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
          onSearch = {
            if (numericPageQuery != null) {
              onJumpToPage(numericPageQuery)
            } else if (searchQuery.trim().length >= 2) {
              onSearchVerses(searchQuery)
            }
          },
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("index_search_input"),
      )

      if (numericPageQuery != null) {
        Spacer(Modifier.height(8.dp))
        Surface(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
              onJumpToPage(numericPageQuery)
            }
            .testTag("jump_to_page_chip"),
          color = MaterialTheme.colorScheme.primaryContainer,
          shape = RoundedCornerShape(12.dp),
        ) {
          Text(
            text = "الانتقال مباشرة إلى الصفحة $numericPageQuery",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          )
        }
      }

      Spacer(Modifier.height(10.dp))

      val tabs = listOf("سورة", "الجزء", "المرجعيات")
      PrimaryTabRow(
        selectedTabIndex = selectedTab,
        containerColor = MaterialTheme.colorScheme.surface,
      ) {
        tabs.forEachIndexed { idx, label ->
          Tab(
            selected = selectedTab == idx,
            onClick = { selectedTab = idx },
            text = { Text(label, style = MaterialTheme.typography.titleSmall) },
            modifier = Modifier.testTag("index_tab_$idx"),
          )
        }
      }

      Spacer(Modifier.height(8.dp))

      if (numericPageQuery == null && searchQuery.trim().length >= 2 && selectedTab == 0) {
        LazyColumn(
          modifier = Modifier.weight(1f),
          contentPadding = PaddingValues(bottom = 24.dp),
        ) {
          if (filteredSurahs.isNotEmpty()) {
            item {
              Text(
                text = "السور المطابقة",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 6.dp),
              )
            }
            items(filteredSurahs, key = { "s_${it.id}" }) { surah ->
              val reciterKey = activeReciter?.key
              val dlKey = reciterKey?.let { "${it}_${"%03d".format(surah.id)}" }
              SurahIndexRow(
                surah = surah,
                isAvailableForReciter = activeReciter?.availableSurahs?.contains(surah.id) == true,
                isDownloaded = dlKey != null && downloadedKeys.contains(dlKey),
                downloadProgress = dlKey?.let { downloadProgress[it] },
                onOpen = {
                  onJumpToPage(surah.startPage)
                },
                onPlay = { onPlaySurah(surah.id) },
                onDownload = { onDownloadSurah(surah.id) },
                onDeleteDownload = { onDeleteDownloadedSurah(surah.id) },
              )
              HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
            }
          }

          item {
            Spacer(Modifier.height(8.dp))
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.padding(vertical = 6.dp),
            ) {
              Text(
                text = "نتائج البحث في الآيات",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
              )
              if (verseSearchLoading) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
              }
            }
          }

          if (verseSearchError != null) {
            item {
              Text(
                text = verseSearchError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp),
              )
            }
          }

          items(verseSearchResults, key = { "v_${it.verseKey}" }) { hit ->
            val surahName = surahsById[hit.surahId]?.nameArabic.orEmpty()
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                  onJumpToVerse(hit.verseKey, hit.pageNumber)
                }
                .testTag("verse_search_hit_${hit.verseKey}"),
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Text(
                    text = "سورة $surahName • الآية ${hit.ayahNumber}",
                    style = MaterialTheme.typography.labelLarge.copy(fontFamily = AmiriFontFamily),
                    color = MaterialTheme.colorScheme.primary,
                  )
                  Text(
                    text = "ص ${hit.pageNumber}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary,
                  )
                }
                if (hit.snippetText.isNotBlank()) {
                  Spacer(Modifier.height(4.dp))
                  Text(
                    text = hit.snippetText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            }
          }
        }
      } else {
        when (selectedTab) {
          0 -> {
            LazyColumn(
              modifier = Modifier.weight(1f),
              contentPadding = PaddingValues(bottom = 24.dp),
            ) {
              items(filteredSurahs, key = { it.id }) { surah ->
                val reciterKey = activeReciter?.key
                val dlKey = reciterKey?.let { "${it}_${"%03d".format(surah.id)}" }
                val isDownloaded = dlKey != null && downloadedKeys.contains(dlKey)
                val progress = dlKey?.let { downloadProgress[it] }
                val isAvailableForReciter = activeReciter?.availableSurahs?.contains(surah.id) == true

                SurahIndexRow(
                  surah = surah,
                  isAvailableForReciter = isAvailableForReciter,
                  isDownloaded = isDownloaded,
                  downloadProgress = progress,
                  onOpen = {
                    onJumpToPage(surah.startPage)
                  },
                  onPlay = { onPlaySurah(surah.id) },
                  onDownload = { onDownloadSurah(surah.id) },
                  onDeleteDownload = { onDeleteDownloadedSurah(surah.id) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
              }
            }
          }

          1 -> {
            LazyColumn(
              modifier = Modifier.weight(1f),
              contentPadding = PaddingValues(bottom = 24.dp),
            ) {
              items(juzs, key = { it.juzNumber }) { juz ->
                val startSurahName = surahsById[juz.firstSurahId]?.nameArabic.orEmpty()
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                      onJumpToPage(juz.startPage)
                    }
                    .padding(horizontal = 8.dp, vertical = 12.dp)
                    .testTag("juz_row_${juz.juzNumber}"),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                      modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                      contentAlignment = Alignment.Center,
                    ) {
                      Text(
                        text = juz.juzNumber.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                      )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                      Text(
                        text = "الجزء ${juz.juzNumber}",
                        style = MaterialTheme.typography.titleMedium,
                      )
                      if (startSurahName.isNotBlank()) {
                        Text(
                          text = "يبدأ من سورة $startSurahName (آية ${juz.firstAyah})",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                    }
                  }
                  Text(
                    text = "ص ${juz.startPage}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
              }
            }
          }

          else -> {
            LazyColumn(
              modifier = Modifier.weight(1f),
              contentPadding = PaddingValues(bottom = 24.dp),
            ) {
              item {
                Surface(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                      onJumpToPage(currentPage)
                    },
                  color = MaterialTheme.colorScheme.primaryContainer,
                  shape = RoundedCornerShape(14.dp),
                ) {
                  Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                      )
                      Spacer(Modifier.width(10.dp))
                      Column {
                        Text(
                          text = "آخر موضع قراءة محفوظ تلقائياً",
                          style = MaterialTheme.typography.titleSmall,
                          color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                          text = "الصفحة $currentPage",
                          style = MaterialTheme.typography.bodyMedium,
                          color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                      }
                    }
                  }
                }
                Spacer(Modifier.height(6.dp))
              }

              if (bookmarks.isEmpty()) {
                item {
                  Box(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center,
                  ) {
                    Text(
                      text = "لا توجد علامات مرجعية مضافة بعد.\nاضغط على أيقونة العلامة أسفل أي صفحة لحفظها هنا.",
                      style = MaterialTheme.typography.bodyMedium,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              } else {
                items(bookmarks, key = { it }) { page ->
                  val surahsOnPage = surahs.filter { page in it.startPage..it.endPage }
                    .joinToString(" • ") { it.nameArabic }
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .clickable {
                        onJumpToPage(page)
                      }
                      .padding(horizontal = 6.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                  ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                      Icon(
                        imageVector = Icons.Filled.Bookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                      )
                      Spacer(Modifier.width(10.dp))
                      Column {
                        Text(
                          text = "صفحة $page",
                          style = MaterialTheme.typography.titleMedium,
                        )
                        if (surahsOnPage.isNotBlank()) {
                          Text(
                            text = "سورة $surahsOnPage",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                          )
                        }
                      }
                    }
                    IconButton(
                      onClick = { onRemoveBookmark(page) },
                      modifier = Modifier.minimumInteractiveComponentSize(),
                    ) {
                      Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "حذف العلامة المرجعية",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                      )
                    }
                  }
                  HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun SurahIndexRow(
  surah: SurahMeta,
  isAvailableForReciter: Boolean,
  isDownloaded: Boolean,
  downloadProgress: Float?,
  onOpen: () -> Unit,
  onPlay: () -> Unit,
  onDownload: () -> Unit,
  onDeleteDownload: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onOpen)
      .padding(horizontal = 4.dp, vertical = 8.dp)
      .testTag("surah_row_${surah.id}"),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
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
          text = surah.id.toString(),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      Spacer(Modifier.width(10.dp))
      Column {
        Text(
          text = "سورة ${surah.nameArabic}",
          style = MaterialTheme.typography.titleMedium.copy(fontFamily = AmiriFontFamily),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${if (surah.makki) "مكية" else "مدنية"} • ${surah.versesCount} آية • ص ${surah.startPage}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(
        onClick = onPlay,
        enabled = isAvailableForReciter,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("play_surah_${surah.id}"),
      ) {
        Icon(
          imageVector = Icons.Filled.PlayArrow,
          contentDescription = "استماع لسورة ${surah.nameArabic}",
          tint = if (isAvailableForReciter) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
          },
        )
      }

      if (downloadProgress != null) {
        Box(
          modifier = Modifier.size(48.dp),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(
            progress = { downloadProgress },
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(22.dp),
          )
        }
      } else if (isDownloaded) {
        IconButton(
          onClick = onDeleteDownload,
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("delete_download_surah_${surah.id}"),
        ) {
          Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "السورة محملة — اضغط للحذف من الذاكرة المحلية",
            tint = MaterialTheme.colorScheme.primary,
          )
        }
      } else {
        IconButton(
          onClick = onDownload,
          enabled = isAvailableForReciter,
          modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag("download_surah_${surah.id}"),
        ) {
          Icon(
            imageVector = Icons.Filled.Download,
            contentDescription = "تحميل السورة للاستماع بدون إنترنت",
            tint = if (isAvailableForReciter) {
              MaterialTheme.colorScheme.secondary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            },
          )
        }
      }
    }
  }
}
