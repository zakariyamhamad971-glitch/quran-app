package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.audio.VerifiedReciter
import com.example.data.quran.AyahWordHit
import com.example.data.quran.JuzMeta
import com.example.data.quran.SurahMeta
import com.example.data.quran.SurahSearchGroup
import com.example.player.AudioMiniUiState
import com.example.player.AudioPlaybackUiState
import com.example.ui.theme.AmiriFontFamily
import com.example.viewmodel.QuranWordSearchUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenQuranIndex(
  surahs: List<SurahMeta>,
  juzs: List<JuzMeta>,
  juzsLoading: Boolean,
  bookmarks: List<Int>,
  currentPage: Int,
  isDarkTheme: Boolean,
  activeReciterFlow: StateFlow<VerifiedReciter?>,
  miniAudioStateFlow: StateFlow<AudioMiniUiState>,
  audioStateFlow: StateFlow<AudioPlaybackUiState>,
  downloadedKeys: Set<String>,
  downloadProgress: Map<String, Float>,
  wordSearchStateFlow: StateFlow<QuranWordSearchUiState>,
  metadataError: String?,
  hasActiveReaderPage: Boolean,
  onRetryMetadata: () -> Unit,
  onSelectJuzTab: () -> Unit,
  onOpenWordSearch: () -> Unit,
  onWordSearchQueryChanged: (String) -> Unit,
  onToggleWordSearchSubstring: (Boolean) -> Unit,
  onSubmitWordSearchFull: () -> Unit,
  onToggleWordSearchSurahCollapsed: (Int) -> Unit,
  onLoadMoreWordSearchResults: () -> Unit,
  onRetryWordSearch: () -> Unit,
  onClearWordSearch: () -> Unit,
  onJumpToPage: (Int) -> Unit,
  onJumpToVerse: (verseKey: String, pageNumber: Int) -> Unit,
  onPlaySurah: (Int) -> Unit,
  onDownloadSurah: (Int) -> Unit,
  onDeleteDownloadedSurah: (Int) -> Unit,
  onRemoveBookmark: (Int) -> Unit,
  onOpenReader: () -> Unit,
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
  onBackToHome: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var isSearchOpen by rememberSaveable { mutableStateOf(false) }
  var showOverflowMenu by remember { mutableStateOf(false) }
  var showGoToPageDialog by remember { mutableStateOf(false) }
  var showFullPlayerSheet by remember { mutableStateOf(false) }

  val wordSearchState by wordSearchStateFlow.collectAsStateWithLifecycle()
  val searchFocusRequester = remember { FocusRequester() }
  val isMiniAudioVisible by remember(miniAudioStateFlow) {
    miniAudioStateFlow.map { it.isActive }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = miniAudioStateFlow.value.isActive)

  BackHandler(enabled = isSearchOpen) {
    isSearchOpen = false
    onClearWordSearch()
  }

  LaunchedEffect(isSearchOpen) {
    if (isSearchOpen) {
      onOpenWordSearch()
      searchFocusRequester.requestFocus()
    }
  }

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
        if (isSearchOpen) {
          Column(modifier = Modifier.fillMaxWidth()) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              InlineIndexSearchField(
                query = wordSearchState.query,
                onQueryChange = onWordSearchQueryChanged,
                onClose = {
                  isSearchOpen = false
                  onClearWordSearch()
                },
                onSubmit = onSubmitWordSearchFull,
                focusRequester = searchFocusRequester,
                modifier = Modifier
                  .weight(1f)
                  .height(36.dp),
              )
              Spacer(Modifier.width(6.dp))
              FilledTonalButton(
                onClick = onSubmitWordSearchFull,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier
                  .height(36.dp)
                  .testTag("quran_search_submit_button"),
              ) {
                Text(
                  text = "بحث",
                  style = MaterialTheme.typography.labelLarge,
                  maxLines = 1,
                )
              }
            }

            // Word-search mode bar ("جزء من الكلمة" switch)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                  .clip(RoundedCornerShape(8.dp))
                  .clickable {
                    onToggleWordSearchSubstring(!wordSearchState.isSubstringMode)
                  }
                  .padding(horizontal = 4.dp, vertical = 2.dp),
              ) {
                Switch(
                  checked = wordSearchState.isSubstringMode,
                  onCheckedChange = onToggleWordSearchSubstring,
                  modifier = Modifier.testTag("quran_search_substring_switch"),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                  text = "جزء من الكلمة",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }

              if (wordSearchState.isIndexWarming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                  )
                  Spacer(Modifier.width(6.dp))
                  Text(
                    text = "تجهيز الفهرس...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        } else {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(
              modifier = Modifier
                .size(width = 40.dp, height = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onBackToHome)
                .testTag("index_back_home_button"),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                imageVector = Icons.Filled.Home,
                contentDescription = "الرئيسية والمواقيت",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
              )
            }

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
      } else if (isSearchOpen) {
        val surahsById = remember(surahs) { surahs.associateBy { it.id } }
        QuranWordSearchResultsView(
          state = wordSearchState,
          surahsById = surahsById,
          bottomExtraPadding = if (isMiniAudioVisible) 6.dp else 18.dp,
          onSubmitFullSearch = onSubmitWordSearchFull,
          onToggleSurahCollapsed = onToggleWordSearchSurahCollapsed,
          onLoadMoreResults = onLoadMoreWordSearchResults,
          onRetrySearch = onRetryWordSearch,
          onJumpToVerse = remember(onJumpToVerse, onOpenReader) {
            { key, page ->
              onJumpToVerse(key, page)
              onOpenReader()
            }
          },
          modifier = Modifier
            .weight(1f)
            .let { mod ->
              if (isMiniAudioVisible) mod else mod.windowInsetsPadding(WindowInsets.navigationBars)
            },
        )
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
          bottomExtraPadding = if (isMiniAudioVisible) 6.dp else 18.dp,
          onSelectJuzTab = onSelectJuzTab,
          onJumpToPage = remember(onJumpToPage, onOpenReader) {
            { page ->
              onJumpToPage(page)
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
  bottomExtraPadding: androidx.compose.ui.unit.Dp,
  onSelectJuzTab: () -> Unit,
  onJumpToPage: (Int) -> Unit,
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
    bottomExtraPadding = bottomExtraPadding,
    onJumpToPage = onJumpToPage,
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
            text = "ابحث عن كلمة في القرآن الكريم",
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
            fontSize = 14.sp,
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
      if (query.isNotEmpty()) {
        Box(
          modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable { onQueryChange("") }
            .testTag("index_search_clear_button"),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "مسح نص البحث",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
          )
        }
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
          imageVector = Icons.AutoMirrored.Filled.MenuBook,
          contentDescription = "إغلاق البحث",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(16.dp),
        )
      }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuranWordSearchResultsView(
  state: QuranWordSearchUiState,
  surahsById: Map<Int, SurahMeta>,
  bottomExtraPadding: androidx.compose.ui.unit.Dp,
  onSubmitFullSearch: () -> Unit,
  onToggleSurahCollapsed: (Int) -> Unit,
  onLoadMoreResults: () -> Unit,
  onRetrySearch: () -> Unit,
  onJumpToVerse: (verseKey: String, pageNumber: Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val trimmed = state.query.trim()
  if (trimmed.isEmpty()) {
    // Empty query shows nothing
    Box(modifier = modifier.fillMaxWidth())
    return
  }

  val result = state.result
  if (state.isLoading && result == null) {
    Box(
      modifier = modifier.fillMaxWidth(),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Text(
          text = "جاري البحث في القرآن الكريم...",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    return
  }

  if (state.errorMessage != null && result == null) {
    Box(
      modifier = modifier
        .fillMaxWidth()
        .padding(20.dp),
      contentAlignment = Alignment.Center,
    ) {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = state.errorMessage,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onRetrySearch) {
          Text("إعادة المحاولة")
        }
      }
    }
    return
  }

  if (result == null) {
    Box(modifier = modifier.fillMaxWidth())
    return
  }

  if (result.totalMatchingAyahs == 0) {
    Box(
      modifier = modifier
        .fillMaxWidth()
        .padding(24.dp)
        .testTag("quran_search_empty"),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "لا توجد نتائج",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    return
  }

  val isDark = MaterialTheme.colorScheme.background.red < 0.25f
  val highlightBgColor = if (isDark) {
    Color(0xFFE0B45C).copy(alpha = 0.34f)
  } else {
    Color(0xFFFFE082)
  }
  val highlightTextColor = if (isDark) {
    Color(0xFFFFECB3)
  } else {
    Color(0xFF0D2E2A)
  }

  if (!state.isFullResultsMode) {
    // Live preview mode (~300ms debounce): first 20 results + total count
    Column(modifier = modifier.fillMaxWidth()) {
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
          ) {
            if (state.isLoading) {
              CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
              Spacer(Modifier.width(8.dp))
            }
            Text(
              text = "عدد المواضع: ${toArabicIndicDigits(result.totalOccurrences)} في ${toArabicIndicDigits(result.surahCount)} سورة (معاينة أول ${toArabicIndicDigits(result.previewHits.size)})",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.testTag("quran_search_preview_summary"),
            )
          }
          if (result.totalMatchingAyahs > result.previewHits.size) {
            TextButton(
              onClick = onSubmitFullSearch,
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
              Text("عرض الكل")
            }
          }
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
      LazyColumn(
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(top = 6.dp, bottom = bottomExtraPadding),
      ) {
        items(
          items = result.previewHits,
          key = { "preview_${it.verseKey}" },
          contentType = { "word_search_hit" },
        ) { hit ->
          val surahName = surahsById[hit.surahId]?.nameArabic.orEmpty()
          WordSearchAyahRow(
            hit = hit,
            surahName = surahName,
            showSurahLabel = true,
            highlightBgColor = highlightBgColor,
            highlightTextColor = highlightTextColor,
            onClick = { onJumpToVerse(hit.verseKey, hit.pageNumber) },
          )
        }
      }
    }
    return
  }

  // Full results mode: summary line + sticky surah headers + expand/collapse + lazy paging
  val pagedGroups = remember(result.groups, state.collapsedSurahIds, state.visibleResultLimit) {
    buildPagedSurahGroups(
      groups = result.groups,
      collapsedSurahIds = state.collapsedSurahIds,
      visibleLimit = state.visibleResultLimit,
    )
  }
  val listState = rememberLazyListState()

  Column(modifier = modifier.fillMaxWidth()) {
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "عدد المواضع: ${toArabicIndicDigits(result.totalOccurrences)} في ${toArabicIndicDigits(result.surahCount)} سورة",
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.testTag("quran_search_summary"),
        )
        if (state.isLoading) {
          CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        }
      }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

    LazyColumn(
      state = listState,
      modifier = Modifier
        .weight(1f)
        .testTag("quran_search_full_list"),
      contentPadding = PaddingValues(bottom = bottomExtraPadding),
    ) {
      for (paged in pagedGroups.items) {
        val group = paged.group
        val isCollapsed = state.collapsedSurahIds.contains(group.surahId)

        stickyHeader(
          key = "search_surah_header_${group.surahId}",
          contentType = "surah_sticky_header",
        ) {
          SurahSearchStickyHeader(
            group = group,
            isCollapsed = isCollapsed,
            onToggle = { onToggleSurahCollapsed(group.surahId) },
          )
        }

        if (!isCollapsed && paged.visibleHits.isNotEmpty()) {
          items(
            items = paged.visibleHits,
            key = { "hit_${it.verseKey}" },
            contentType = { "word_search_hit" },
          ) { hit ->
            WordSearchAyahRow(
              hit = hit,
              surahName = group.surahName,
              showSurahLabel = false,
              highlightBgColor = highlightBgColor,
              highlightTextColor = highlightTextColor,
              onClick = { onJumpToVerse(hit.verseKey, hit.pageNumber) },
            )
          }
        }
      }

      if (pagedGroups.hasMore) {
        item(key = "search_load_more_sentinel", contentType = "load_more") {
          LaunchedEffect(state.visibleResultLimit, pagedGroups.renderedRows) {
            onLoadMoreResults()
          }
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(
              strokeWidth = 2.dp,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }
    }
  }
}

private data class PagedSurahGroupSlice(
  val group: SurahSearchGroup,
  val visibleHits: List<AyahWordHit>,
)

private data class PagedSurahGroupsResult(
  val items: List<PagedSurahGroupSlice>,
  val renderedRows: Int,
  val hasMore: Boolean,
)

private fun buildPagedSurahGroups(
  groups: List<SurahSearchGroup>,
  collapsedSurahIds: Set<Int>,
  visibleLimit: Int,
): PagedSurahGroupsResult {
  var remaining = visibleLimit.coerceAtLeast(1)
  val slices = ArrayList<PagedSurahGroupSlice>(groups.size)
  var rendered = 0
  var hasMore = false

  for (idx in groups.indices) {
    val g = groups[idx]
    if (collapsedSurahIds.contains(g.surahId)) {
      slices += PagedSurahGroupSlice(group = g, visibleHits = emptyList())
      continue
    }
    if (remaining <= 0) {
      hasMore = true
      break
    }
    if (g.hits.size <= remaining) {
      slices += PagedSurahGroupSlice(group = g, visibleHits = g.hits)
      remaining -= g.hits.size
      rendered += g.hits.size
    } else {
      slices += PagedSurahGroupSlice(group = g, visibleHits = g.hits.subList(0, remaining))
      rendered += remaining
      remaining = 0
      hasMore = true
      break
    }
  }

  return PagedSurahGroupsResult(
    items = slices,
    renderedRows = rendered,
    hasMore = hasMore,
  )
}

@Composable
private fun SurahSearchStickyHeader(
  group: SurahSearchGroup,
  isCollapsed: Boolean,
  onToggle: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surfaceContainerHigh,
    tonalElevation = 2.dp,
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onToggle)
      .testTag("search_surah_header_${group.surahId}"),
  ) {
    Column {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f),
        ) {
          Text(
            text = "سورة ${group.surahName}",
            style = MaterialTheme.typography.titleMedium.copy(
              fontFamily = AmiriFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 17.sp,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
          ) {
            Text(
              text = toArabicIndicDigits(group.occurrenceCount),
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
            )
          }
          Spacer(Modifier.width(8.dp))
          Icon(
            imageVector = if (isCollapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = if (isCollapsed) "توسيع سورة ${group.surahName}" else "طي سورة ${group.surahName}",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
          )
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    }
  }
}

@Composable
private fun WordSearchAyahRow(
  hit: AyahWordHit,
  surahName: String,
  showSurahLabel: Boolean,
  highlightBgColor: Color,
  highlightTextColor: Color,
  onClick: () -> Unit,
) {
  val annotatedAyah = remember(hit.originalText, hit.highlightRanges, highlightBgColor, highlightTextColor) {
    buildHighlightedAyahAnnotatedString(
      originalText = hit.originalText,
      highlightRanges = hit.highlightRanges,
      highlightBgColor = highlightBgColor,
      highlightTextColor = highlightTextColor,
    )
  }

  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 4.dp)
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .testTag("verse_search_hit_${hit.verseKey}"),
  ) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = if (showSurahLabel && surahName.isNotBlank()) {
            "سورة $surahName • الآية ${toArabicIndicDigits(hit.ayahNumber)}"
          } else {
            "الآية ${toArabicIndicDigits(hit.ayahNumber)}"
          },
          style = MaterialTheme.typography.labelLarge.copy(
            fontFamily = AmiriFontFamily,
            fontWeight = FontWeight.Bold,
          ),
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "ص ${toArabicIndicDigits(hit.pageNumber)}",
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.secondary,
        )
      }
      Spacer(Modifier.height(6.dp))
      Text(
        text = annotatedAyah,
        style = MaterialTheme.typography.bodyLarge.copy(
          fontFamily = AmiriFontFamily,
          fontSize = 18.sp,
          lineHeight = 31.sp,
        ),
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

private fun buildHighlightedAyahAnnotatedString(
  originalText: String,
  highlightRanges: List<IntRange>,
  highlightBgColor: Color,
  highlightTextColor: Color,
): AnnotatedString = buildAnnotatedString {
  append(originalText)
  val len = originalText.length
  for (range in highlightRanges) {
    val start = range.first.coerceIn(0, len)
    val endExcl = (range.last + 1).coerceIn(start, len)
    if (start < endExcl) {
      addStyle(
        style = SpanStyle(
          background = highlightBgColor,
          color = highlightTextColor,
        ),
        start = start,
        end = endExcl,
      )
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
  wordSearchState: QuranWordSearchUiState,
  onWordSearchQueryChanged: (String) -> Unit,
  onToggleWordSearchSubstring: (Boolean) -> Unit,
  onSubmitWordSearchFull: () -> Unit,
  onToggleWordSearchSurahCollapsed: (Int) -> Unit,
  onLoadMoreWordSearchResults: () -> Unit,
  onRetryWordSearch: () -> Unit,
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
  val surahsById = remember(surahs) { surahs.associateBy { it.id } }

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
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedTextField(
          value = wordSearchState.query,
          onValueChange = onWordSearchQueryChanged,
          placeholder = { Text("ابحث عن كلمة في القرآن الكريم") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
          keyboardActions = KeyboardActions(onSearch = { onSubmitWordSearchFull() }),
          modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = onSubmitWordSearchFull) {
          Text("بحث")
        }
      }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Switch(
          checked = wordSearchState.isSubstringMode,
          onCheckedChange = onToggleWordSearchSubstring,
        )
        Spacer(Modifier.width(8.dp))
        Text("جزء من الكلمة")
      }
      Spacer(Modifier.height(4.dp))
      if (wordSearchState.query.trim().isNotEmpty()) {
        QuranWordSearchResultsView(
          state = wordSearchState,
          surahsById = surahsById,
          bottomExtraPadding = 16.dp,
          onSubmitFullSearch = onSubmitWordSearchFull,
          onToggleSurahCollapsed = onToggleWordSearchSurahCollapsed,
          onLoadMoreResults = onLoadMoreWordSearchResults,
          onRetrySearch = onRetryWordSearch,
          onJumpToVerse = { key, page ->
            onJumpToVerse(key, page)
            onDismiss()
          },
          modifier = Modifier.weight(1f),
        )
      } else {
        QuranIndexContent(
          surahs = surahs,
          juzs = juzs,
          bookmarks = bookmarks,
          currentPage = currentPage,
          activeReciter = activeReciter,
          downloadedKeys = downloadedKeys,
          downloadProgress = downloadProgress,
          bottomExtraPadding = 16.dp,
          onJumpToPage = { page ->
            onJumpToPage(page)
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
  bottomExtraPadding: androidx.compose.ui.unit.Dp,
  onJumpToPage: (Int) -> Unit,
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

    when (selectedTab) {
      0 -> {
        LazyColumn(
          modifier = Modifier.weight(1f),
          contentPadding = PaddingValues(bottom = bottomExtraPadding),
        ) {
          items(surahs, key = { it.id }, contentType = { "surah_row" }) { surah ->
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
