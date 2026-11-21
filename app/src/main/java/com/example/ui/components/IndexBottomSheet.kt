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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.audio.VerifiedReciter
import com.example.data.quran.JuzMeta
import com.example.data.quran.MushafEdition
import com.example.data.quran.SurahMeta
import com.example.data.quran.VerseSearchHit
import com.example.player.AudioMiniUiState
import com.example.player.AudioPlaybackUiState
import com.example.ui.theme.AmiriFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val INDEX_DIACRITICS_REGEX = Regex("[\\u064B-\\u065F\\u0670]")

private fun padSurahId(surahId: Int): String = when {
  surahId < 10 -> "00$surahId"
  surahId < 100 -> "0$surahId"
  else -> surahId.toString()
}

private fun toArabicIndicDigits(number: Int): String = toArabicIndicDigits(number.toString())

private fun toArabicIndicDigits(input: String): String {
  val digits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
  return buildString(input.length) {
    for (ch in input) {
      append(if (ch in '0'..'9') digits[ch - '0'] else ch)
    }
  }
}

private fun arabicDigitsToAscii(input: String): String {
  return buildString(input.length) {
    for (ch in input) {
      when (ch) {
        in '٠'..'٩' -> append(('0'.code + (ch - '٠')).toChar())
        in '۰'..'۹' -> append(('0'.code + (ch - '۰')).toChar())
        else -> append(ch)
      }
    }
  }
}

private fun normalizeArabicQuery(input: String): String {
  return arabicDigitsToAscii(input)
    .replace("أ", "ا")
    .replace("إ", "ا")
    .replace("آ", "ا")
    .replace("ى", "ي")
    .replace("ة", "ه")
    .replace(INDEX_DIACRITICS_REGEX, "")
    .trim()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenQuranIndex(
  surahs: List<SurahMeta>,
  juzs: List<JuzMeta>,
  juzsLoading: Boolean,
  bookmarks: List<Int>,
  currentPage: Int,
  mushafEdition: MushafEdition,
  isDarkTheme: Boolean,
  activeReciterFlow: StateFlow<VerifiedReciter?>,
  miniAudioStateFlow: StateFlow<AudioMiniUiState>,
  audioStateFlow: StateFlow<AudioPlaybackUiState>,
  downloadedKeys: Set<String>,
  downloadProgress: Map<String, Float>,
  verseSearchResults: List<VerseSearchHit>,
  verseSearchLoading: Boolean,
  verseSearchError: String?,
  metadataError: String?,
  hasActiveReaderPage: Boolean,
  onRetryMetadata: () -> Unit,
  onSelectJuzTab: () -> Unit,
  onSearchVerses: (String) -> Unit,
  onJumpToPage: (Int) -> Unit,
  onJumpToVerse: (verseKey: String, pageNumber: Int) -> Unit,
  onPlaySurah: (Int) -> Unit,
  onDownloadSurah: (Int) -> Unit,
  onDeleteDownloadedSurah: (Int) -> Unit,
  onRemoveBookmark: (Int) -> Unit,
  onOpenReader: () -> Unit,
  onToggleEdition: (MushafEdition) -> Unit,
  onToggleTheme: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenCredits: () -> Unit,
  onOpenRangePicker: () -> Unit,
  onTogglePlayPause: () -> Unit,
  onSeekTo: (Long) -> Unit,
  onSeekDelta: (Long) -> Unit,
  onNextSurah: () -> Unit,
  onPrevSurah: () -> Unit,
  onCycleSpeed: (Float) -> Unit,
  onOpenReciterPicker: () -> Unit,
  modifier: Modifier = Modifier,
  activeMushafIsPdf: Boolean = false,
) {
  var isSearchOpen by rememberSaveable { mutableStateOf(false) }
  var searchQuery by rememberSaveable { mutableStateOf("") }
  var showOverflowMenu by remember { mutableStateOf(false) }
  var showGoToPageDialog by remember { mutableStateOf(false) }
  var showFullPlayerSheet by remember { mutableStateOf(false) }

  val searchFocusRequester = remember { FocusRequester() }
  val isMiniAudioVisible by remember(miniAudioStateFlow) {
    miniAudioStateFlow.map { it.isActive }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = miniAudioStateFlow.value.isActive)

  BackHandler(enabled = isSearchOpen) {
    isSearchOpen = false
    searchQuery = ""
    onSearchVerses("")
  }

  LaunchedEffect(isSearchOpen) {
    if (isSearchOpen) {
      searchFocusRequester.requestFocus()
    }
  }

  val asciiQuery = remember(searchQuery) { arabicDigitsToAscii(searchQuery).trim() }
  val numericPageQuery = asciiQuery.toIntOrNull()?.takeIf { it in 1..604 }

  Surface(
    modifier = modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // Compact Top Bar (content row strictly <= 48.dp)
      Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier
          .fillMaxWidth()
          .windowInsetsPadding(WindowInsets.statusBars),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .padding(horizontal = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (isSearchOpen) {
            InlineIndexSearchField(
              query = searchQuery,
              onQueryChange = { searchQuery = it },
              onClose = {
                isSearchOpen = false
                searchQuery = ""
                onSearchVerses("")
              },
              onSubmit = {
                if (numericPageQuery != null) {
                  onJumpToPage(numericPageQuery)
                  onOpenReader()
                } else if (asciiQuery.length >= 2) {
                  onSearchVerses(asciiQuery)
                }
              },
              focusRequester = searchFocusRequester,
              modifier = Modifier
                .weight(1f)
                .height(34.dp),
            )
            Spacer(Modifier.width(4.dp))
          } else {
            Text(
              text = "قبسات",
              style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = AmiriFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
              ),
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
              modifier = Modifier.padding(horizontal = 4.dp),
            )

            Spacer(Modifier.weight(1f))

            Box(
              modifier = Modifier
                .size(width = 36.dp, height = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { isSearchOpen = true }
                .testTag("index_search_toggle_button"),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "بحث",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
              )
            }
          }

          Box(
            modifier = Modifier
              .size(width = 36.dp, height = 44.dp)
              .clip(RoundedCornerShape(8.dp))
              .clickable(onClick = onOpenSettings)
              .testTag("index_settings_button"),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              imageVector = Icons.Filled.Settings,
              contentDescription = "الإعدادات",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
          }

          Spacer(Modifier.width(2.dp))

          CompactEditionSegment(
            mushafEdition = mushafEdition,
            activeMushafIsPdf = activeMushafIsPdf,
            onSelectEdition = onToggleEdition,
          )

          Box {
            Box(
              modifier = Modifier
                .size(width = 34.dp, height = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable { showOverflowMenu = true }
                .testTag("index_overflow_menu_button"),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "المزيد من الخيارات",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
            }

            DropdownMenu(
              expanded = showOverflowMenu,
              onDismissRequest = { showOverflowMenu = false },
            ) {
              if (hasActiveReaderPage) {
                DropdownMenuItem(
                  text = { Text("العودة للقراءة (ص ${toArabicIndicDigits(currentPage)})") },
                  leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                  },
                  onClick = {
                    showOverflowMenu = false
                    onOpenReader()
                  },
                  modifier = Modifier.testTag("overflow_return_reader"),
                )
              }
              DropdownMenuItem(
                text = { Text("الانتقال إلى صفحة") },
                leadingIcon = {
                  Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                },
                onClick = {
                  showOverflowMenu = false
                  showGoToPageDialog = true
                },
                modifier = Modifier.testTag("overflow_go_to_page"),
              )
              DropdownMenuItem(
                text = { Text("نطاق الآيات") },
                leadingIcon = {
                  Icon(Icons.Filled.PlayArrow, contentDescription = null)
                },
                onClick = {
                  showOverflowMenu = false
                  onOpenRangePicker()
                },
                modifier = Modifier.testTag("overflow_verse_range"),
              )
              DropdownMenuItem(
                text = { Text(if (isDarkTheme) "الوضع الفاتح" else "الوضع الداكن") },
                leadingIcon = {
                  Icon(
                    imageVector = if (isDarkTheme) Icons.Filled.Brightness7 else Icons.Filled.Brightness4,
                    contentDescription = null,
                  )
                },
                onClick = {
                  showOverflowMenu = false
                  onToggleTheme()
                },
                modifier = Modifier.testTag("overflow_toggle_theme"),
              )
              DropdownMenuItem(
                text = { Text("المصادر والتحقق") },
                leadingIcon = {
                  Icon(Icons.Filled.Info, contentDescription = null)
                },
                onClick = {
                  showOverflowMenu = false
                  onOpenCredits()
                },
                modifier = Modifier.testTag("overflow_info_credits"),
              )
            }
          }
        }
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

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
        QuranIndexContentHost(
          surahs = surahs,
          juzs = juzs,
          juzsLoading = juzsLoading,
          bookmarks = bookmarks,
          currentPage = currentPage,
          activeReciterFlow = activeReciterFlow,
          downloadedKeys = downloadedKeys,
          downloadProgress = downloadProgress,
          verseSearchResults = verseSearchResults,
          verseSearchLoading = verseSearchLoading,
          verseSearchError = verseSearchError,
          searchQuery = searchQuery,
          bottomExtraPadding = if (isMiniAudioVisible) 6.dp else 18.dp,
          onSelectJuzTab = onSelectJuzTab,
          onSearchVerses = onSearchVerses,
          onJumpToPage = remember(onJumpToPage, onOpenReader) {
            { page ->
              onJumpToPage(page)
              onOpenReader()
            }
          },
          onJumpToVerse = remember(onJumpToVerse, onOpenReader) {
            { key, page ->
              onJumpToVerse(key, page)
              onOpenReader()
            }
          },
          onPlaySurah = remember(onPlaySurah, onOpenReader) {
            { surahId ->
              onPlaySurah(surahId)
              onOpenReader()
            }
          },
          onDownloadSurah = onDownloadSurah,
          onDeleteDownloadedSurah = onDeleteDownloadedSurah,
          onRemoveBookmark = onRemoveBookmark,
          modifier = Modifier
            .weight(1f)
            .let { mod ->
              if (isMiniAudioVisible) mod else mod.windowInsetsPadding(WindowInsets.navigationBars)
            },
        )
      }

      IndexMiniAudioHost(
        miniAudioStateFlow = miniAudioStateFlow,
        onTogglePlayPause = onTogglePlayPause,
        onOpenFullPlayer = remember { { showFullPlayerSheet = true } },
      )
    }
  }

  if (showGoToPageDialog) {
    GoToPageDialog(
      currentPage = currentPage,
      onDismiss = { showGoToPageDialog = false },
      onConfirmPage = { page ->
        showGoToPageDialog = false
        onJumpToPage(page)
        onOpenReader()
      },
    )
  }

  if (showFullPlayerSheet) {
    IndexFullPlayerSheetHost(
      audioStateFlow = audioStateFlow,
      onDismiss = { showFullPlayerSheet = false },
      onTogglePlayPause = onTogglePlayPause,
      onSeekTo = onSeekTo,
      onSeekDelta = onSeekDelta,
      onNextSurah = onNextSurah,
      onPrevSurah = onPrevSurah,
      onCycleSpeed = onCycleSpeed,
      onOpenReciterPicker = {
        showFullPlayerSheet = false
        onOpenReciterPicker()
      },
    )
  }
}

@Composable
private fun QuranIndexContentHost(
  surahs: List<SurahMeta>,
  juzs: List<JuzMeta>,
  juzsLoading: Boolean,
  bookmarks: List<Int>,
  currentPage: Int,
  activeReciterFlow: StateFlow<VerifiedReciter?>,
  downloadedKeys: Set<String>,
  downloadProgress: Map<String, Float>,
  verseSearchResults: List<VerseSearchHit>,
  verseSearchLoading: Boolean,
  verseSearchError: String?,
  searchQuery: String,
  bottomExtraPadding: androidx.compose.ui.unit.Dp,
  onSelectJuzTab: () -> Unit,
  onSearchVerses: (String) -> Unit,
  onJumpToPage: (Int) -> Unit,
  onJumpToVerse: (verseKey: String, pageNumber: Int) -> Unit,
  onPlaySurah: (Int) -> Unit,
  onDownloadSurah: (Int) -> Unit,
  onDeleteDownloadedSurah: (Int) -> Unit,
  onRemoveBookmark: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val activeReciter by activeReciterFlow.collectAsStateWithLifecycle()
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
    searchQuery = searchQuery,
    bottomExtraPadding = bottomExtraPadding,
    onSearchVerses = onSearchVerses,
    onJumpToPage = onJumpToPage,
    onJumpToVerse = onJumpToVerse,
    onPlaySurah = onPlaySurah,
    onDownloadSurah = onDownloadSurah,
    onDeleteDownloadedSurah = onDeleteDownloadedSurah,
    onRemoveBookmark = onRemoveBookmark,
    modifier = modifier,
    juzsLoading = juzsLoading,
    onSelectJuzTab = onSelectJuzTab,
  )
}

@Composable
private fun IndexMiniAudioHost(
  miniAudioStateFlow: StateFlow<AudioMiniUiState>,
  onTogglePlayPause: () -> Unit,
  onOpenFullPlayer: () -> Unit,
) {
  val miniAudioState by miniAudioStateFlow.collectAsStateWithLifecycle()
  if (miniAudioState.isActive) {
    IndexMiniAudioBar(
      miniState = miniAudioState,
      onTogglePlayPause = onTogglePlayPause,
      onOpenFullPlayer = onOpenFullPlayer,
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IndexFullPlayerSheetHost(
  audioStateFlow: StateFlow<AudioPlaybackUiState>,
  onDismiss: () -> Unit,
  onTogglePlayPause: () -> Unit,
  onSeekTo: (Long) -> Unit,
  onSeekDelta: (Long) -> Unit,
  onNextSurah: () -> Unit,
  onPrevSurah: () -> Unit,
  onCycleSpeed: (Float) -> Unit,
  onOpenReciterPicker: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    val fullAudioState by audioStateFlow.collectAsStateWithLifecycle()
    PersistentAudioBar(
      audioState = fullAudioState,
      onTogglePlayPause = onTogglePlayPause,
      onSeekTo = onSeekTo,
      onSeekDelta = onSeekDelta,
      onNextSurah = onNextSurah,
      onPrevSurah = onPrevSurah,
      onCycleSpeed = { onCycleSpeed(fullAudioState.playbackSpeed) },
      onOpenReciterPicker = onOpenReciterPicker,
    )
  }
}

@Composable
private fun InlineIndexSearchField(
  query: String,
  onQueryChange: (String) -> Unit,
  onClose: () -> Unit,
  onSubmit: () -> Unit,
  focusRequester: FocusRequester,
  modifier: Modifier = Modifier,
) {
  Surface(
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
    modifier = modifier,
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Filled.Search,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(16.dp),
      )
      Spacer(Modifier.width(6.dp))
      Box(
        modifier = Modifier.weight(1f),
        contentAlignment = Alignment.CenterStart,
      ) {
        if (query.isEmpty()) {
          Text(
            text = "سورة، آية ٢:٢٥٥، أو صفحة",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        BasicTextField(
          value = query,
          onValueChange = onQueryChange,
          singleLine = true,
          textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
          ),
          cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
          modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .testTag("index_search_input"),
        )
      }
      Box(
        modifier = Modifier
          .size(28.dp)
          .clip(CircleShape)
          .clickable(onClick = onClose)
          .testTag("index_search_close_button"),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = "إغلاق البحث",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(16.dp),
        )
      }
    }
  }
}

@Composable
private fun CompactEditionSegment(
  mushafEdition: MushafEdition,
  activeMushafIsPdf: Boolean,
  onSelectEdition: (MushafEdition) -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.height(30.dp),
  ) {
    Row(
      modifier = Modifier.padding(2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      CompactSegmentPill(
        label = "المدينة",
        selected = !activeMushafIsPdf && mushafEdition == MushafEdition.MADINAH_1421,
        onClick = { onSelectEdition(MushafEdition.MADINAH_1421) },
        testTag = "index_edition_madinah",
      )
      CompactSegmentPill(
        label = "التجويد",
        selected = !activeMushafIsPdf && mushafEdition == MushafEdition.TAJWEED_V4,
        onClick = { onSelectEdition(MushafEdition.TAJWEED_V4) },
        testTag = "index_edition_tajweed",
      )
    }
  }
}

@Composable
private fun CompactSegmentPill(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  testTag: String,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier
      .fillMaxHeight()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .testTag(testTag),
  ) {
    Box(
      contentAlignment = Alignment.Center,
      modifier = Modifier.padding(horizontal = 8.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun IndexMiniAudioBar(
  miniState: AudioMiniUiState,
  onTogglePlayPause: () -> Unit,
  onOpenFullPlayer: () -> Unit,
) {
  val surahLabel = if (miniState.currentSurahName.isNotBlank()) {
    "سورة ${miniState.currentSurahName}"
  } else {
    "سورة ${toArabicIndicDigits(miniState.currentSurahId)}"
  }
  val reciterLabel = miniState.reciterName

  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 4.dp,
    shadowElevation = 6.dp,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("index_mini_audio_bar"),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .windowInsetsPadding(WindowInsets.navigationBars)
          .height(54.dp)
          .clickable(onClick = onOpenFullPlayer)
          .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.Filled.GraphicEq,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = surahLabel,
            style = MaterialTheme.typography.titleSmall.copy(
              fontFamily = AmiriFontFamily,
              fontSize = 14.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = reciterLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        IconButton(
          onClick = onTogglePlayPause,
          modifier = Modifier
            .size(40.dp)
            .testTag("index_mini_play_pause"),
        ) {
          if (miniState.isBuffering) {
            CircularProgressIndicator(
              strokeWidth = 2.dp,
              modifier = Modifier.size(18.dp),
            )
          } else {
            Icon(
              imageVector = if (miniState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
              contentDescription = "تشغيل أو إيقاف مؤقت",
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun GoToPageDialog(
  currentPage: Int,
  onDismiss: () -> Unit,
  onConfirmPage: (Int) -> Unit,
) {
  var pageText by remember { mutableStateOf("") }
  val parsedPage = arabicDigitsToAscii(pageText).trim().toIntOrNull()?.takeIf { it in 1..604 }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("الانتقال إلى صفحة") },
    text = {
      Column {
        Text(
          text = "أدخل رقم الصفحة (${toArabicIndicDigits(1)} - ${toArabicIndicDigits(604)})",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
          value = pageText,
          onValueChange = { pageText = it },
          placeholder = { Text(toArabicIndicDigits(currentPage)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Go,
          ),
          keyboardActions = KeyboardActions(
            onGo = {
              if (parsedPage != null) onConfirmPage(parsedPage)
            },
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("goto_page_input"),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { if (parsedPage != null) onConfirmPage(parsedPage) },
        enabled = parsedPage != null,
        modifier = Modifier.testTag("goto_page_confirm"),
      ) {
        Text("انتقال")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("إلغاء")
      }
    },
  )
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
  var searchQuery by remember { mutableStateOf("") }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .fillMaxHeight(0.88f)
        .padding(horizontal = 12.dp),
    ) {
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("ابحث عن سورة أو آية (٢:٢٥٥) أو صفحة") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
      )
      Spacer(Modifier.height(6.dp))
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
        searchQuery = searchQuery,
        bottomExtraPadding = 16.dp,
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
  searchQuery: String,
  bottomExtraPadding: androidx.compose.ui.unit.Dp,
  onSearchVerses: (String) -> Unit,
  onJumpToPage: (Int) -> Unit,
  onJumpToVerse: (verseKey: String, pageNumber: Int) -> Unit,
  onPlaySurah: (Int) -> Unit,
  onDownloadSurah: (Int) -> Unit,
  onDeleteDownloadedSurah: (Int) -> Unit,
  onRemoveBookmark: (Int) -> Unit,
  modifier: Modifier = Modifier,
  juzsLoading: Boolean = false,
  onSelectJuzTab: () -> Unit = {},
) {
  var selectedTab by rememberSaveable { mutableIntStateOf(0) }

  LaunchedEffect(selectedTab) {
    if (selectedTab == 1) {
      onSelectJuzTab()
    }
  }

  val asciiQuery = remember(searchQuery) { arabicDigitsToAscii(searchQuery).trim() }
  val normalizedQuery = remember(searchQuery) { normalizeArabicQuery(searchQuery) }
  val numericPageQuery = asciiQuery.toIntOrNull()?.takeIf { it in 1..604 }

  LaunchedEffect(asciiQuery) {
    if (numericPageQuery == null && asciiQuery.length >= 2) {
      delay(300L)
      onSearchVerses(asciiQuery)
    } else {
      onSearchVerses("")
    }
  }

  val normalizedSurahNames = remember(surahs) {
    surahs.associate { it.id to normalizeArabicQuery(it.nameArabic) }
  }
  val filteredSurahs = remember(surahs, normalizedSurahNames, normalizedQuery) {
    if (normalizedQuery.isBlank()) {
      surahs
    } else {
      surahs.filter { s ->
        val norm = normalizedSurahNames[s.id].orEmpty()
        norm.contains(normalizedQuery, ignoreCase = true) ||
          s.id.toString() == normalizedQuery
      }
    }
  }

  val surahsById = remember(surahs) { surahs.associateBy { it.id } }
  val availableSurahSet = remember(activeReciter) {
    activeReciter?.availableSurahs?.toHashSet() ?: emptySet()
  }
  val onOpenSurahPage = remember(onJumpToPage) { { startPage: Int -> onJumpToPage(startPage) } }

  Column(modifier = modifier.fillMaxWidth()) {
    val tabs = remember { listOf("سورة", "الجزء", "المرجعيات") }
    PrimaryTabRow(
      selectedTabIndex = selectedTab,
      containerColor = MaterialTheme.colorScheme.surface,
      modifier = Modifier
        .fillMaxWidth()
        .height(40.dp),
    ) {
      tabs.forEachIndexed { idx, label ->
        Tab(
          selected = selectedTab == idx,
          onClick = { selectedTab = idx },
          text = {
            Text(
              text = label,
              style = MaterialTheme.typography.labelLarge,
              maxLines = 1,
            )
          },
          modifier = Modifier
            .height(40.dp)
            .testTag("index_tab_$idx"),
        )
      }
    }

    if (numericPageQuery != null) {
      Surface(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 6.dp)
          .clip(RoundedCornerShape(10.dp))
          .clickable { onJumpToPage(numericPageQuery) }
          .testTag("jump_to_page_chip"),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(10.dp),
      ) {
        Text(
          text = "الانتقال إلى الصفحة ${toArabicIndicDigits(numericPageQuery)}",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
      }
    }

    if (numericPageQuery == null && asciiQuery.length >= 2 && selectedTab == 0) {
      LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(bottom = bottomExtraPadding),
      ) {
        if (filteredSurahs.isNotEmpty()) {
          item(key = "header_matching_surahs", contentType = "section_header") {
            Text(
              text = "السور المطابقة",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
          }
          items(filteredSurahs, key = { "s_${it.id}" }, contentType = { "surah_row" }) { surah ->
            val reciterKey = activeReciter?.key
            val dlKey = reciterKey?.let { "${it}_${padSurahId(surah.id)}" }
            SurahIndexRow(
              surah = surah,
              isAvailableForReciter = availableSurahSet.contains(surah.id),
              isDownloaded = dlKey != null && downloadedKeys.contains(dlKey),
              downloadProgress = dlKey?.let { downloadProgress[it] },
              onOpenPage = onOpenSurahPage,
              onPlay = onPlaySurah,
              onDownload = onDownloadSurah,
              onDeleteDownload = onDeleteDownloadedSurah,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
          }
        }

        item(key = "header_verse_hits", contentType = "section_header") {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
          ) {
            Text(
              text = "نتائج البحث في الآيات",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            if (verseSearchLoading) {
              CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
            }
          }
        }

        if (verseSearchError != null) {
          item(key = "verse_search_error", contentType = "status_banner") {
            Text(
              text = verseSearchError,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
          }
        }

        items(verseSearchResults, key = { "v_${it.verseKey}" }, contentType = { "verse_hit" }) { hit ->
          val surahName = surahsById[hit.surahId]?.nameArabic.orEmpty()
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 12.dp, vertical = 3.dp)
              .clip(RoundedCornerShape(10.dp))
              .clickable { onJumpToVerse(hit.verseKey, hit.pageNumber) }
              .testTag("verse_search_hit_${hit.verseKey}"),
          ) {
            Column(modifier = Modifier.padding(10.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "سورة $surahName • الآية ${toArabicIndicDigits(hit.ayahNumber)}",
                  style = MaterialTheme.typography.labelLarge.copy(fontFamily = AmiriFontFamily),
                  color = MaterialTheme.colorScheme.primary,
                )
                Text(
                  text = "ص ${toArabicIndicDigits(hit.pageNumber)}",
                  style = MaterialTheme.typography.labelMedium,
                  color = MaterialTheme.colorScheme.secondary,
                )
              }
              if (hit.snippetText.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                  text = hit.snippetText,
                  style = MaterialTheme.typography.bodySmall,
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
            contentPadding = PaddingValues(bottom = bottomExtraPadding),
          ) {
            items(filteredSurahs, key = { it.id }, contentType = { "surah_row" }) { surah ->
              val reciterKey = activeReciter?.key
              val dlKey = reciterKey?.let { "${it}_${padSurahId(surah.id)}" }
              val isDownloaded = dlKey != null && downloadedKeys.contains(dlKey)
              val progress = dlKey?.let { downloadProgress[it] }
              val isAvailableForReciter = availableSurahSet.contains(surah.id)

              SurahIndexRow(
                surah = surah,
                isAvailableForReciter = isAvailableForReciter,
                isDownloaded = isDownloaded,
                downloadProgress = progress,
                onOpenPage = onOpenSurahPage,
                onPlay = onPlaySurah,
                onDownload = onDownloadSurah,
                onDeleteDownload = onDeleteDownloadedSurah,
              )
              HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
            }
          }
        }

        1 -> {
          if (juzsLoading && juzs.isEmpty()) {
            Box(
              modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
              contentAlignment = Alignment.Center,
            ) {
              CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
          } else {
            LazyColumn(
              modifier = Modifier.weight(1f),
              contentPadding = PaddingValues(bottom = bottomExtraPadding),
            ) {
              items(juzs, key = { it.juzNumber }, contentType = { "juz_row" }) { juz ->
                val startSurahName = surahsById[juz.firstSurahId]?.nameArabic.orEmpty()
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .clickable { onJumpToPage(juz.startPage) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("juz_row_${juz.juzNumber}"),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                  ) {
                    Box(
                      modifier = Modifier
                        .size(32.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                      contentAlignment = Alignment.Center,
                    ) {
                      Text(
                        text = toArabicIndicDigits(juz.juzNumber),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                      )
                    }
                    Spacer(Modifier.width(10.dp))
                    Column {
                      Text(
                        text = "الجزء ${toArabicIndicDigits(juz.juzNumber)}",
                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = AmiriFontFamily),
                      )
                      if (startSurahName.isNotBlank()) {
                        Text(
                          text = "يبدأ من سورة $startSurahName (آية ${toArabicIndicDigits(juz.firstAyah)})",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
                        )
                      }
                    }
                  }
                  Text(
                    text = "ص ${toArabicIndicDigits(juz.startPage)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                  )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
              }
            }
          }
        }

        else -> {
          LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = bottomExtraPadding),
          ) {
            item(key = "last_read_banner", contentType = "banner") {
              Surface(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(horizontal = 12.dp, vertical = 6.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .clickable { onJumpToPage(currentPage) },
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(10.dp),
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Icon(
                    imageVector = Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                  )
                  Spacer(Modifier.width(10.dp))
                  Column {
                    Text(
                      text = "آخر موضع قراءة محفوظ تلقائياً",
                      style = MaterialTheme.typography.titleSmall,
                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                      text = "الصفحة ${toArabicIndicDigits(currentPage)}",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                  }
                }
              }
            }

            if (bookmarks.isEmpty()) {
              item(key = "empty_bookmarks", contentType = "empty_state") {
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
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
              items(bookmarks, key = { it }, contentType = { "bookmark_row" }) { page ->
                val surahsOnPage = remember(page, surahs) {
                  surahs.filter { page in it.startPage..it.endPage }
                    .joinToString(" • ") { it.nameArabic }
                }
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 58.dp)
                    .clickable { onJumpToPage(page) }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically,
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                  ) {
                    Icon(
                      imageVector = Icons.Filled.Bookmark,
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.secondary,
                      modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                      Text(
                        text = "صفحة ${toArabicIndicDigits(page)}",
                        style = MaterialTheme.typography.titleSmall,
                      )
                      if (surahsOnPage.isNotBlank()) {
                        Text(
                          text = "سورة $surahsOnPage",
                          style = MaterialTheme.typography.bodySmall,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis,
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
                      modifier = Modifier.size(20.dp),
                    )
                  }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
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
  onOpenPage: (Int) -> Unit,
  onPlay: (Int) -> Unit,
  onDownload: (Int) -> Unit,
  onDeleteDownload: (Int) -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .height(62.dp)
      .clickable { onOpenPage(surah.startPage) }
      .padding(horizontal = 10.dp)
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
          .size(32.dp)
          .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = toArabicIndicDigits(surah.id),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
      Spacer(Modifier.width(10.dp))
      Column(verticalArrangement = Arrangement.Center) {
        Text(
          text = "سورة ${surah.nameArabic}",
          style = MaterialTheme.typography.titleMedium.copy(
            fontFamily = AmiriFontFamily,
            fontSize = 16.sp,
          ),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = "${if (surah.makki) "مكية" else "مدنية"} • ${toArabicIndicDigits(surah.versesCount)} آية • ص ${toArabicIndicDigits(surah.startPage)}",
          style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(
        onClick = { onPlay(surah.id) },
        enabled = isAvailableForReciter,
        modifier = Modifier
          .size(38.dp)
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
          modifier = Modifier.size(20.dp),
        )
      }

      if (downloadProgress != null) {
        Box(
          modifier = Modifier.size(38.dp),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(
            progress = { downloadProgress },
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
          )
        }
      } else if (isDownloaded) {
        IconButton(
          onClick = { onDeleteDownload(surah.id) },
          modifier = Modifier
            .size(38.dp)
            .testTag("delete_download_surah_${surah.id}"),
        ) {
          Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "السورة محملة — اضغط للحذف من الذاكرة المحلية",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
        }
      } else {
        IconButton(
          onClick = { onDownload(surah.id) },
          enabled = isAvailableForReciter,
          modifier = Modifier
            .size(38.dp)
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
            modifier = Modifier.size(18.dp),
          )
        }
      }
    }
  }
}
