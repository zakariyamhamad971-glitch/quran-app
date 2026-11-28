package com.example.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import com.example.ui.components.FullReciterManagerScreen
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.audio.RecitationRange
import com.example.data.audio.ReciterMoshafStyle
import com.example.data.audio.VerifiedReciter
import com.example.data.prayer.PrayerType
import com.example.data.prefs.BackgroundTheme
import com.example.data.prefs.PageViewMode
import com.example.data.quran.MushafDownloadState
import com.example.data.quran.MushafEdition
import com.example.data.quran.MushafRegistry
import com.example.data.quran.SurahMeta
import com.example.player.AudioPlaybackUiState
import com.example.ui.components.AdhanAlarmsManagementSheet
import com.example.ui.components.AudioPickerSheetMode
import com.example.ui.components.CompactDockedAudioPlayer
import com.example.ui.components.CompactRecitersPopup
import com.example.ui.components.CreditsAndIntegrityDialog
import com.example.ui.components.FullScreenQuranIndex
import com.example.ui.components.InsightsSourceFilter
import com.example.ui.components.MushafPageFrame
import com.example.ui.components.MushafSelectorScreen
import com.example.ui.components.MushafsManagerScreen
import com.example.ui.components.PageSelectionRootRect
import com.example.ui.components.PlaybackControlSheet
import com.example.ui.components.PrayerHomeScreen
import com.example.ui.components.PrayerSoundConfigSheet
import com.example.ui.components.ReaderBottomAudioButtons
import com.example.ui.components.ReciterDownloadsScreen
import com.example.ui.components.ReciterPickerSheet
import com.example.ui.components.SettingsSheet
import com.example.ui.components.TafsirTranslationsManagerScreen
import com.example.ui.components.TajweedLegendSheet
import com.example.ui.components.VerseInsightsSheet
import com.example.ui.components.rememberNotificationPermissionRequester
import com.example.ui.theme.AmiriFontFamily
import com.example.ui.theme.QabasatNightGold
import com.example.update.AppUpdateState
import com.example.viewmodel.PageLoadState
import com.example.viewmodel.PrayerTimesViewModel
import com.example.viewmodel.QabasatViewModel
import com.example.viewmodel.VerseInsightsUiState
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val MUSHAF_PAGE_ASPECT_RATIO = 0.62f

private enum class ReaderAudioSheet {
  RECITERS,
  PLAYBACK,
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

@Composable
fun ReaderScreen(
  viewModel: QabasatViewModel,
) {
  val downloadedMushafIds by viewModel.downloadedMushafIdsState.collectAsStateWithLifecycle()
  val activeMushafIsPdf by viewModel.activeMushafIsPdfState.collectAsStateWithLifecycle()
  val downloadStates by viewModel.mushafDownloadManager.states.collectAsStateWithLifecycle()

  if (downloadedMushafIds.isEmpty() && !activeMushafIsPdf) {
    MushafSelectorScreen(
      downloadStates = downloadStates,
      onStartDownload = remember(viewModel) { { item -> viewModel.startMushafDownload(item) } },
      onCancelDownload = remember(viewModel) { { id -> viewModel.cancelMushafDownload(id) } },
    )
  } else {
    ReaderScreenContent(viewModel = viewModel)
  }
}

@Composable
private fun ReaderScreenContent(
  viewModel: QabasatViewModel,
  prayerViewModel: PrayerTimesViewModel = viewModel(),
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val downloadedKeys by viewModel.downloadedAudioKeys.collectAsStateWithLifecycle()
  val downloadProgress by viewModel.audioDownloadProgress.collectAsStateWithLifecycle()
  val activeVerseState: State<String?> = viewModel.activePlayingVerseKeyFlow.collectAsStateWithLifecycle()
  val selectedVerseKeysState: State<Set<String>> = viewModel.selectedVerseKeysFlow.collectAsStateWithLifecycle()
  val insightsState by viewModel.verseInsightsState.collectAsStateWithLifecycle()
  val isInsightsOpen = insightsState !is VerseInsightsUiState.Hidden
  val activePlayingVerseKeyProvider = remember(activeVerseState) { { activeVerseState.value } }
  val selectedVerseKeysProvider = remember(selectedVerseKeysState) { { selectedVerseKeysState.value } }
  val selectedVerseKeys = selectedVerseKeysState.value

  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  val isDarkTheme = uiState.isDarkMode
  val surahsById = remember(uiState.surahs) { uiState.surahs.associateBy { it.id } }
  val bookmarkSet = remember(uiState.bookmarks) { uiState.bookmarks.toHashSet() }
  val ayahBookmarkKeySet = remember(uiState.ayahBookmarks) {
    uiState.ayahBookmarks.mapTo(HashSet()) { it.verseKey }
  }

  var isHomeOpen by rememberSaveable { mutableStateOf(true) }
  var isIndexOpen by rememberSaveable { mutableStateOf(false) }
  var hasSelectedSurah by rememberSaveable { mutableStateOf(false) }
  var isChromeVisible by rememberSaveable { mutableStateOf(true) }
  var isCompactPlayerDocked by rememberSaveable { mutableStateOf(false) }
  var showCompactReciterPopup by remember { mutableStateOf(false) }
  var activeAudioSheet by remember { mutableStateOf<ReaderAudioSheet?>(null) }
  var showSettingsSheet by remember { mutableStateOf(false) }
  var showMushafsManager by rememberSaveable { mutableStateOf(false) }
  var showRecitersManager by rememberSaveable { mutableStateOf(false) }
  var showTafsirManager by rememberSaveable { mutableStateOf(false) }
  var showAdhanSettingsSheet by rememberSaveable { mutableStateOf(false) }
  var editingAdhanPrayer by rememberSaveable { mutableStateOf<PrayerType?>(null) }
  var downloadReciterKey by rememberSaveable { mutableStateOf<String?>(null) }
  var showTajweedLegend by remember { mutableStateOf(false) }
  var showCreditsDialog by remember { mutableStateOf(false) }
  var insightsInitialFilter by remember { mutableStateOf(InsightsSourceFilter.ALL) }
  val requestNotifPermissionIfNeeded = rememberNotificationPermissionRequester()

  var rootOriginInWindow by remember { mutableStateOf(Offset.Zero) }
  var sheetTopInRootWindow by remember { mutableStateOf<Float?>(null) }
  val pageSelectionBoundsMap = remember { mutableStateMapOf<Int, PageSelectionRootRect>() }
  LaunchedEffect(selectedVerseKeys) {
    if (selectedVerseKeys.isEmpty()) {
      pageSelectionBoundsMap.clear()
    }
  }

  val isAudioPlaying by viewModel.isAudioPlayingFlow.collectAsStateWithLifecycle()
  LaunchedEffect(isAudioPlaying) {
    if (isAudioPlaying) {
      isCompactPlayerDocked = true
    }
  }
  LaunchedEffect(isChromeVisible, isIndexOpen, isHomeOpen) {
    if (!isChromeVisible || isIndexOpen || isHomeOpen) {
      showCompactReciterPopup = false
    }
  }

  var activeQuarterBannerText by remember { mutableStateOf<String?>(null) }

  val speeds = remember { listOf(0.75f, 1.0f, 1.15f, 1.25f) }
  val onCycleSpeed: (Float) -> Unit = remember(viewModel, speeds) {
    { currentSpeed ->
      val curIdx = speeds.indexOfFirst { kotlin.math.abs(it - currentSpeed) < 0.05f }
      val nextSpeed = speeds[(curIdx + 1) % speeds.size]
      viewModel.playbackController.setSpeed(nextSpeed)
    }
  }
  val onWordTapped: (String, Int, String) -> Unit = remember(viewModel) {
    { key, p, glyphs ->
      if (viewModel.uiState.value.selectedVerseKeys.isNotEmpty()) {
        viewModel.onWordTapped(key, p, glyphs)
      } else {
        isChromeVisible = !isChromeVisible
      }
    }
  }
  val onWordLongPressed: (String, Int, String) -> Unit = remember(viewModel) {
    { key, p, glyphs ->
      viewModel.onWordLongPressed(key, p, glyphs)
    }
  }
  val onSelectionHandleDragged: (Boolean, String, Int, String) -> Unit = remember(viewModel) {
    { isStart, targetKey, p, glyphs ->
      viewModel.onSelectionHandleDragged(isStart, targetKey, p, glyphs)
    }
  }
  val onToggleBookmark: (Int) -> Unit = remember(viewModel) { { p -> viewModel.toggleBookmark(p) } }
  val onRetryPage: (Int) -> Unit = remember(viewModel) { { p -> viewModel.retryPage(p) } }
  val onPageChangedCallback: (Int) -> Unit = remember(viewModel) { { p -> viewModel.onPageChanged(p) } }
  val onNonWordTapped: () -> Unit = remember(viewModel) {
    {
      if (viewModel.uiState.value.selectedVerseKeys.isNotEmpty()) {
        viewModel.clearVerseSelection()
      } else {
        isChromeVisible = !isChromeVisible
      }
    }
  }
  val onSelectionBoundsChanged: (Int, PageSelectionRootRect?) -> Unit = remember {
    { pageNum, rect ->
      if (rect == null) {
        pageSelectionBoundsMap.remove(pageNum)
      } else {
        pageSelectionBoundsMap[pageNum] = rect
      }
    }
  }
  val view = LocalView.current

  LaunchedEffect(Unit) {
    val activity = view.context.findActivity() ?: return@LaunchedEffect
    val controller = WindowCompat.getInsetsController(activity.window, view)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    controller.hide(WindowInsetsCompat.Type.systemBars())
  }

  BackHandler(enabled = !isHomeOpen) {
    when {
      isInsightsOpen -> viewModel.dismissVerseInsights()
      selectedVerseKeys.isNotEmpty() -> viewModel.clearVerseSelection()
      isIndexOpen -> {
        isIndexOpen = false
        isHomeOpen = true
      }
      else -> {
        isChromeVisible = true
        isIndexOpen = true
      }
    }
  }

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      .onGloballyPositioned { coords ->
        if (coords.isAttached) {
          rootOriginInWindow = coords.localToRoot(Offset.Zero)
        }
      },
  ) {
    val density = LocalDensity.current
    val rootWidthPx = with(density) { maxWidth.toPx() }
    val rootHeightPx = with(density) { maxHeight.toPx() }
    val isLandscape = maxWidth > maxHeight
    val effectiveTwoPageMode = when (uiState.pageViewMode) {
      PageViewMode.AUTO -> isLandscape
      PageViewMode.SINGLE -> false
      PageViewMode.TWO_PAGES -> true
    }

    val visiblePagesSet = remember(uiState.currentPage, effectiveTwoPageMode) {
      if (effectiveTwoPageMode) {
        val rightPage = (((uiState.currentPage - 1) / 2) * 2 + 1).coerceIn(1, 604)
        val leftPage = (rightPage + 1).coerceAtMost(604)
        setOf(rightPage, leftPage)
      } else {
        setOf(uiState.currentPage)
      }
    }

    LaunchedEffect(visiblePagesSet, isHomeOpen, isIndexOpen) {
      if (isHomeOpen || isIndexOpen) {
        activeQuarterBannerText = null
        return@LaunchedEffect
      }
      val bannerText = visiblePagesSet.sorted().firstNotNullOfOrNull { p ->
        com.example.data.quran.QuranQuartersCatalog.getQuarterBannerForPage(p)
      }
      if (bannerText != null) {
        activeQuarterBannerText = bannerText
        kotlinx.coroutines.delay(3500L)
        activeQuarterBannerText = null
      } else {
        activeQuarterBannerText = null
      }
    }

    val activeSelectionRectLocal = remember(
      selectedVerseKeys,
      visiblePagesSet,
      pageSelectionBoundsMap.toMap(),
      rootOriginInWindow,
    ) {
      if (selectedVerseKeys.isEmpty()) {
        null
      } else {
        var minL = Float.POSITIVE_INFINITY
        var minT = Float.POSITIVE_INFINITY
        var maxR = Float.NEGATIVE_INFINITY
        var maxB = Float.NEGATIVE_INFINITY
        var any = false
        for ((pageNum, rect) in pageSelectionBoundsMap) {
          if (pageNum !in visiblePagesSet) continue
          if (rect.left < minL) minL = rect.left
          if (rect.top < minT) minT = rect.top
          if (rect.right > maxR) maxR = rect.right
          if (rect.bottom > maxB) maxB = rect.bottom
          any = true
        }
        if (!any) {
          null
        } else {
          PageSelectionRootRect(
            left = minL - rootOriginInWindow.x,
            top = minT - rootOriginInWindow.y,
            right = maxR - rootOriginInWindow.x,
            bottom = maxB - rootOriginInWindow.y,
          )
        }
      }
    }

    val targetPageShiftPx = if (isInsightsOpen && activeSelectionRectLocal != null && sheetTopInRootWindow != null) {
      val sheetTopLocal = sheetTopInRootWindow!! - rootOriginInWindow.y
      val marginPx = with(density) { 14.dp.toPx() }
      val desiredShift = (activeSelectionRectLocal.bottom + marginPx - sheetTopLocal).coerceAtLeast(0f)
      val safeTopPx = with(density) { 12.dp.toPx() }
      val maxSafeShift = (activeSelectionRectLocal.top - safeTopPx).coerceAtLeast(0f)
      minOf(desiredShift, maxSafeShift)
    } else {
      0f
    }
    val animatedPageShiftPx by animateFloatAsState(
      targetValue = targetPageShiftPx,
      animationSpec = tween(durationMillis = 220),
      label = "mushaf_sheet_scroll_shift",
    )

    // Main Mushaf page area stays fixed unless shifted upward to keep selected ayahs visible above insights sheet
    if ((!isHomeOpen && !isIndexOpen) || hasSelectedSurah) {
      ReaderPagerHost(
        currentPage = uiState.currentPage,
        effectiveTwoPageMode = effectiveTwoPageMode,
        backgroundTheme = uiState.backgroundTheme,
        bookmarkSet = bookmarkSet,
        isDarkTheme = isDarkTheme,
        isPdfMode = uiState.activeMushafIsPdf,
        settledAvailWidthDp = maxWidth,
        settledAvailHeightDp = maxHeight.coerceAtLeast(1.dp),
        pagesStateFlow = viewModel.pagesState,
        activePlayingVerseKeyProvider = activePlayingVerseKeyProvider,
        selectedVerseKeysProvider = selectedVerseKeysProvider,
        onUpdateRenderSpec = remember(viewModel) { { w, h, d, fs, twoPage -> viewModel.updatePageRenderSpec(w, h, d, fs, twoPage) } },
        onPageChanged = onPageChangedCallback,
        onToggleBookmark = onToggleBookmark,
        onRetry = onRetryPage,
        onNonWordTapped = onNonWordTapped,
        onWordTapped = onWordTapped,
        onWordLongPressed = onWordLongPressed,
        onSelectionHandleDragged = onSelectionHandleDragged,
        onSelectionBoundsChanged = onSelectionBoundsChanged,
        modifier = Modifier
          .fillMaxSize()
          .clipToBounds()
          .graphicsLayer { translationY = -animatedPageShiftPx },
      )

      // Top overlay bar (slides down from top without shifting the page)
      AnimatedVisibility(
        visible = isChromeVisible && !isIndexOpen && !isHomeOpen,
        enter = slideInVertically(animationSpec = tween(durationMillis = 240)) { -it } +
          fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = slideOutVertically(animationSpec = tween(durationMillis = 220)) { -it } +
          fadeOut(animationSpec = tween(durationMillis = 160)),
        modifier = Modifier.align(Alignment.TopCenter),
      ) {
        QabasatTopBar(
          isDarkTheme = isDarkTheme,
          effectiveTwoPageMode = effectiveTwoPageMode,
          isCompactWidth = maxWidth < 560.dp,
          onSelectPageViewMode = remember(viewModel) { { mode -> viewModel.setPageViewMode(mode) } },
          onOpenIndex = remember { { isIndexOpen = true } },
          onOpenMushafsManager = remember(viewModel) {
            {
              viewModel.loadPdfManifest()
              showMushafsManager = true
            }
          },
          onOpenSettings = remember { { showSettingsSheet = true } },
          onOpenTajweedLegend = remember(viewModel) {
            {
              viewModel.ensureTajweedLegendLoaded()
              showTajweedLegend = true
            }
          },
          onToggleTheme = remember(viewModel) { { viewModel.toggleDarkMode() } },
          onOpenCredits = remember(viewModel) {
            {
              viewModel.ensureCreditsLoaded()
              showCreditsDialog = true
            }
          },
        )
      }

      // Bottom audio overlay: docked compact player (plus anchored reciters popup) above the two round buttons
      AnimatedVisibility(
        visible = isChromeVisible && !isIndexOpen && !isHomeOpen,
        enter = slideInVertically(animationSpec = tween(durationMillis = 240)) { it } +
          fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = slideOutVertically(animationSpec = tween(durationMillis = 220)) { it } +
          fadeOut(animationSpec = tween(durationMillis = 160)),
        modifier = Modifier.align(Alignment.BottomCenter),
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (isCompactPlayerDocked) {
            if (showCompactReciterPopup) {
              CompactRecitersPopupHost(
                audioStateFlow = viewModel.audioState,
                primaryReciters = uiState.primaryReciters,
                additionalReciters = uiState.additionalReciters,
                isLoading = uiState.recitersLoading,
                errorMessage = uiState.recitersError,
                isPdfMode = uiState.activeMushafIsPdf,
                onRetryLoad = remember(viewModel) { { viewModel.retryLoadReciters() } },
                onSelectReciter = remember(viewModel) { { reciter -> viewModel.selectReciter(reciter) } },
                onSelectReciterStyle = remember(viewModel) { { reciter, style -> viewModel.selectReciterStyle(reciter, style) } },
                onDismiss = remember { { showCompactReciterPopup = false } },
              )
            }
            CompactDockedAudioPlayerHost(
              audioStateFlow = viewModel.audioState,
              isReciterPopupOpen = showCompactReciterPopup,
              onToggleReciterPopup = remember { { showCompactReciterPopup = !showCompactReciterPopup } },
              onExpandFullSheet = remember {
                {
                  showCompactReciterPopup = false
                  activeAudioSheet = ReaderAudioSheet.PLAYBACK
                }
              },
              onTogglePlayPause = remember(viewModel) { { viewModel.playbackController.togglePlayPause() } },
              onSeekTo = remember(viewModel) { { pos -> viewModel.playbackController.seekTo(pos) } },
              onSeekDelta = remember(viewModel) { { delta -> viewModel.playbackController.seekByDelta(delta) } },
              onNextSurah = remember(viewModel) { { viewModel.playbackController.nextSurah() } },
              onPrevSurah = remember(viewModel) { { viewModel.playbackController.prevSurah() } },
              onCycleSpeed = onCycleSpeed,
            )
          }
          ReaderBottomAudioButtonsHost(
            audioStateFlow = viewModel.audioState,
            onOpenReciters = remember {
              {
                showCompactReciterPopup = false
                activeAudioSheet = ReaderAudioSheet.RECITERS
              }
            },
            onOpenPlayback = remember {
              {
                showCompactReciterPopup = false
                activeAudioSheet = ReaderAudioSheet.PLAYBACK
              }
            },
          )
        }
      }
    }

    if (isHomeOpen && !isIndexOpen) {
      PrayerHomeScreen(
        prayerViewModel = prayerViewModel,
        surahs = uiState.surahs,
        currentPage = uiState.currentPage,
        isDarkTheme = isDarkTheme,
        onToggleTheme = { viewModel.toggleDarkMode() },
        onOpenMushafIndex = {
          isHomeOpen = false
          isIndexOpen = true
        },
        onResumeReader = {
          hasSelectedSurah = true
          isHomeOpen = false
          isIndexOpen = false
          viewModel.ensureCurrentPageLoaded()
        },
        onOpenAppSettings = { showSettingsSheet = true },
      )
    }

    if (isIndexOpen) {
      FullScreenQuranIndex(
        surahs = uiState.surahs,
        juzs = uiState.juzs,
        juzsLoading = uiState.juzsLoading,
        bookmarks = uiState.bookmarks,
        ayahBookmarks = uiState.ayahBookmarks,
        currentPage = uiState.currentPage,
        isDarkTheme = isDarkTheme,
        activeReciterFlow = viewModel.activeReciterFlow,
        miniAudioStateFlow = viewModel.miniAudioStateFlow,
        audioStateFlow = viewModel.audioState,
        downloadedKeys = downloadedKeys,
        downloadProgress = downloadProgress,
        wordSearchStateFlow = viewModel.wordSearchState,
        metadataError = uiState.metadataError,
        hasActiveReaderPage = hasSelectedSurah,
        onRetryMetadata = { viewModel.retryBootstrap() },
        onSelectJuzTab = { viewModel.ensureJuzsLoaded() },
        onOpenWordSearch = { viewModel.warmUpWordSearchIndex() },
        onWordSearchQueryChanged = { query -> viewModel.onWordSearchQueryChanged(query) },
        onToggleWordSearchSubstring = { enabled -> viewModel.setWordSearchSubstringMode(enabled) },
        onSubmitWordSearchFull = { viewModel.submitWordSearchFullResults() },
        onToggleWordSearchSurahCollapsed = { surahId -> viewModel.toggleWordSearchSurahCollapsed(surahId) },
        onLoadMoreWordSearchResults = { viewModel.loadMoreWordSearchResults() },
        onRetryWordSearch = { viewModel.retryWordSearch() },
        onClearWordSearch = { viewModel.clearWordSearch() },
        onJumpToPage = { page ->
          hasSelectedSurah = true
          isHomeOpen = false
          isIndexOpen = false
          viewModel.jumpToPage(page)
        },
        onJumpToVerse = { key, page ->
          hasSelectedSurah = true
          isHomeOpen = false
          isIndexOpen = false
          viewModel.jumpToVerse(key, page)
        },
        onPlaySurah = { surahId ->
          val targetPage = surahsById[surahId]?.startPage ?: uiState.currentPage
          hasSelectedSurah = true
          isHomeOpen = false
          isIndexOpen = false
          viewModel.jumpToPage(targetPage)
          viewModel.playSurah(surahId)
        },
        onDownloadSurah = { surahId ->
          requestNotifPermissionIfNeeded()
          viewModel.downloadSurahAudio(surahId)
        },
        onDeleteDownloadedSurah = { surahId -> viewModel.deleteDownloadedSurahAudio(surahId) },
        onRemoveBookmark = { page -> viewModel.removeBookmark(page) },
        onRemoveAyahBookmark = { surahId, ayahNum -> viewModel.removeAyahBookmark(surahId, ayahNum) },
        onOpenReader = {
          hasSelectedSurah = true
          isHomeOpen = false
          isIndexOpen = false
          viewModel.ensureCurrentPageLoaded()
        },
        onToggleTheme = { viewModel.toggleDarkMode() },
        onOpenSettings = { showSettingsSheet = true },
        onOpenCredits = {
          viewModel.ensureCreditsLoaded()
          showCreditsDialog = true
        },
        onOpenRangePicker = { activeAudioSheet = ReaderAudioSheet.PLAYBACK },
        onTogglePlayPause = { viewModel.playbackController.togglePlayPause() },
        onSeekTo = { pos -> viewModel.playbackController.seekTo(pos) },
        onSeekDelta = { delta -> viewModel.playbackController.seekByDelta(delta) },
        onNextSurah = { viewModel.playbackController.nextSurah() },
        onPrevSurah = { viewModel.playbackController.prevSurah() },
        onCycleSpeed = onCycleSpeed,
        onOpenReciterPicker = { activeAudioSheet = ReaderAudioSheet.RECITERS },
        onBackToHome = {
          isIndexOpen = false
          isHomeOpen = true
        },
      )
    }

    when (activeAudioSheet) {
      ReaderAudioSheet.RECITERS -> {
        ReaderReciterPickerHost(
          audioStateFlow = viewModel.audioState,
          primaryReciters = uiState.primaryReciters,
          additionalReciters = uiState.additionalReciters,
          surahs = uiState.surahs,
          isLoading = uiState.recitersLoading,
          errorMessage = uiState.recitersError,
          mode = AudioPickerSheetMode.RECITERS_ONLY,
          isPdfMode = uiState.activeMushafIsPdf,
          onRetryLoad = { viewModel.retryLoadReciters() },
          onSelectReciter = { reciter -> viewModel.selectReciter(reciter) },
          onSelectReciterStyle = { reciter, style -> viewModel.selectReciterStyle(reciter, style) },
          onPlayFromAyah = { surahId, ayahNum ->
            hasSelectedSurah = true
            isIndexOpen = false
            viewModel.playFromAyah(surahId, ayahNum)
          },
          onPlayRange = { range ->
            hasSelectedSurah = true
            isIndexOpen = false
            viewModel.playAyahRange(range)
          },
          onClearRange = { viewModel.clearAyahRange() },
          onOpenReciterDownloads = { reciter ->
            activeAudioSheet = null
            viewModel.ensureAllDownloadableRecitersLoaded()
            downloadReciterKey = reciter.key
          },
          onDismiss = { activeAudioSheet = null },
        )
      }

      ReaderAudioSheet.PLAYBACK -> {
        val recitersEmptyForRiwaya = uiState.activeMushafIsPdf &&
          !uiState.recitersLoading &&
          uiState.recitersError == null &&
          uiState.primaryReciters.isEmpty()
        ReaderPlaybackSheetHost(
          audioStateFlow = viewModel.audioState,
          surahs = uiState.surahs,
          isPdfMode = uiState.activeMushafIsPdf,
          recitersEmptyForRiwaya = recitersEmptyForRiwaya,
          onTogglePlayPause = remember(viewModel) { { viewModel.playbackController.togglePlayPause() } },
          onSeekTo = remember(viewModel) { { pos -> viewModel.playbackController.seekTo(pos) } },
          onSeekDelta = remember(viewModel) { { delta -> viewModel.playbackController.seekByDelta(delta) } },
          onNextSurah = remember(viewModel) { { viewModel.playbackController.nextSurah() } },
          onPrevSurah = remember(viewModel) { { viewModel.playbackController.prevSurah() } },
          onCycleSpeed = onCycleSpeed,
          onPlayFromAyah = { surahId, ayahNum ->
            hasSelectedSurah = true
            isIndexOpen = false
            viewModel.playFromAyah(surahId, ayahNum)
          },
          onPlayRange = { range ->
            hasSelectedSurah = true
            isIndexOpen = false
            viewModel.playAyahRange(range)
          },
          onClearRange = { viewModel.clearAyahRange() },
          onDismiss = { activeAudioSheet = null },
          onPlaybackStarted = {
            isCompactPlayerDocked = true
            activeAudioSheet = null
          },
        )
      }

      null -> Unit
    }

    if (showSettingsSheet) {
      ReaderSettingsHost(
        viewModel = viewModel,
        isDarkMode = isDarkTheme,
        backgroundTheme = uiState.backgroundTheme,
        effectiveTwoPageMode = effectiveTwoPageMode,
        activeMushafEdition = uiState.mushafEdition,
        activeMushafIsPdf = uiState.activeMushafIsPdf,
        activePdfMushafLabel = uiState.pdfMushafs.firstOrNull { it.id == uiState.activePdfMushafId }?.riwayaAr,
        onOpenReciterDownloads = { reciter -> downloadReciterKey = reciter.key },
        onOpenReciterManager = {
          viewModel.ensureAllDownloadableRecitersLoaded()
          showRecitersManager = true
        },
        onOpenTafsirManager = {
          viewModel.ensureTafsirCatalogLoaded()
          showTafsirManager = true
        },
        onOpenAdhanSettings = { showAdhanSettingsSheet = true },
        onOpenMushafsManager = {
          viewModel.loadPdfManifest()
          showMushafsManager = true
        },
        onOpenTajweedLegend = {
          viewModel.ensureTajweedLegendLoaded()
          showTajweedLegend = true
        },
        onOpenCredits = {
          viewModel.ensureCreditsLoaded()
          showCreditsDialog = true
        },
        onDismiss = { showSettingsSheet = false },
      )
    }

    if (showAdhanSettingsSheet) {
      val prayerUiState by prayerViewModel.uiState.collectAsStateWithLifecycle()
      AdhanAlarmsManagementSheet(
        adhanSettings = prayerUiState.adhanSettings,
        isTestPlaying = prayerUiState.isTestAdhanPlaying,
        onToggleAlarmsEnabled = prayerViewModel::setAdhanAlarmsEnabled,
        onTogglePersistentCountdown = prayerViewModel::setPersistentCountdownEnabled,
        onSelectPreAdhanMinutes = prayerViewModel::setAdhanPreAlertMinutes,
        onChangeVolumePercent = prayerViewModel::setAdhanVolumePercent,
        onToggleVibrate = prayerViewModel::setAdhanVibrateEnabled,
        onToggleShortAlertOnly = prayerViewModel::setAdhanShortAlertOnly,
        onSelectBackground = prayerViewModel::setAdhanBackground,
        onEditPrayerSound = { editingAdhanPrayer = it },
        onSavePrayerConfig = prayerViewModel::updatePrayerAlarmConfig,
        onApplyMuezzinToAllWithFajr = prayerViewModel::applyMuezzinToAllPrayersWithFajrPreference,
        onUpdateIqamaConfig = prayerViewModel::updateIqamaConfig,
        onTestAdhanNow = { prayerViewModel.startTestAdhan10s() },
        onStopTestAdhan = { prayerViewModel.stopTestAdhan() },
        prayerSettings = prayerUiState.settings,
        onSelectMethod = prayerViewModel::setCalculationMethod,
        onResetMethodToCountryDefault = prayerViewModel::resetMethodToCountryDefault,
        onSelectMadhab = prayerViewModel::setAsrMadhab,
        onSelectHighLatitudeRule = prayerViewModel::setHighLatitudeRule,
        onAdjustMinutes = prayerViewModel::adjustPrayerMinutes,
        onResetAdjustments = prayerViewModel::resetAllAdjustments,
        onDismiss = {
          showAdhanSettingsSheet = false
          prayerViewModel.refreshAlarmsAndNotification()
        },
      )
    }

    editingAdhanPrayer?.let { targetPrayer ->
      val prayerUiState by prayerViewModel.uiState.collectAsStateWithLifecycle()
      PrayerSoundConfigSheet(
        prayerType = targetPrayer,
        currentConfig = prayerUiState.adhanSettings.configFor(targetPrayer),
        isTestPlaying = prayerUiState.isTestAdhanPlaying,
        onSaveConfig = { cfg -> prayerViewModel.updatePrayerAlarmConfig(targetPrayer, cfg) },
        onApplyToAllPrayers = { cfg -> prayerViewModel.applyAlarmConfigToAllPrayers(cfg) },
        onApplyMuezzinToAllWithFajr = prayerViewModel::applyMuezzinToAllPrayersWithFajrPreference,
        onTestAdhanNow = { cfg ->
          prayerViewModel.startTestAdhan10s(prayerType = targetPrayer, configOverride = cfg)
        },
        onStopTestAdhan = { prayerViewModel.stopTestAdhan() },
        onDismiss = { editingAdhanPrayer = null },
      )
    }

    if (showMushafsManager) {
      ReaderMushafsManagerHost(
        viewModel = viewModel,
        activeMushafIsPdf = uiState.activeMushafIsPdf,
        activePdfMushafId = uiState.activePdfMushafId,
        pdfMushafs = uiState.pdfMushafs,
        pdfManifestLoading = uiState.pdfManifestLoading,
        pdfManifestError = uiState.pdfManifestError,
        onClose = { showMushafsManager = false },
        onMushafActivated = {
          showMushafsManager = false
          hasSelectedSurah = true
          isIndexOpen = false
        },
      )
    }

    if (showRecitersManager || downloadReciterKey != null) {
      ReaderReciterManagerHost(
        viewModel = viewModel,
        surahs = uiState.surahs,
        initialReciterKey = downloadReciterKey,
        onClose = {
          showRecitersManager = false
          downloadReciterKey = null
        },
      )
    }

    if (showTajweedLegend) {
      val activePdfEntry = uiState.pdfMushafs.firstOrNull { it.id == uiState.activePdfMushafId }
      TajweedLegendSheet(
        rules = uiState.tajweedRules,
        colorsGuideSections = uiState.colorsGuideSections,
        colorsGuideLoading = uiState.colorsGuideLoading,
        colorsGuideError = uiState.colorsGuideError,
        activeMushafIsPdf = uiState.activeMushafIsPdf,
        activePdfMushafId = uiState.activePdfMushafId,
        activePdfRiwayaAr = activePdfEntry?.riwayaAr,
        onRetryColorsGuide = { viewModel.retryColorsGuide() },
        onDismiss = { showTajweedLegend = false },
      )
    }

    if (showCreditsDialog) {
      CreditsAndIntegrityDialog(
        credits = uiState.credits,
        assetHashes = uiState.assetHashes,
        tajweedRenderPathLabel = uiState.tajweedRenderPathLabel,
        androidApiLevelText = uiState.androidApiLevelText,
        onDismiss = { showCreditsDialog = false },
      )
    }

    if (selectedVerseKeys.isNotEmpty() && !isInsightsOpen && !isIndexOpen && !isHomeOpen && !uiState.activeMushafIsPdf) {
      val allSelectedBookmarked = selectedVerseKeys.all { ayahBookmarkKeySet.contains(it) }
      AyahSelectionActionBar(
        selectionRect = activeSelectionRectLocal,
        rootWidthPx = rootWidthPx,
        rootHeightPx = rootHeightPx,
        isTopChromeVisible = isChromeVisible,
        isBookmarked = allSelectedBookmarked,
        onPlay = {
          viewModel.playFromSelectedAyahs()
          isCompactPlayerDocked = true
        },
        onOpenTafsir = {
          insightsInitialFilter = InsightsSourceFilter.TAFSIR
          viewModel.openInsightsForSelectedAyahs()
        },
        onOpenTranslation = {
          insightsInitialFilter = InsightsSourceFilter.TRANSLATION
          viewModel.openInsightsForSelectedAyahs()
        },
        onCopy = {
          coroutineScope.launch {
            viewModel.buildSharePayloadForSelectedAyahs()
              .onSuccess { (copyText, _) ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("الآية", copyText))
                Toast.makeText(context, "تم نسخ نص الآية", Toast.LENGTH_SHORT).show()
              }
              .onFailure {
                Toast.makeText(context, "تعذر تجهيز نص الآية للنسخ", Toast.LENGTH_SHORT).show()
              }
          }
        },
        onShare = {
          coroutineScope.launch {
            viewModel.buildSharePayloadForSelectedAyahs()
              .onSuccess { (shareText, _) ->
                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_TEXT, shareText)
                }
                context.startActivity(Intent.createChooser(sendIntent, "مشاركة الآية"))
              }
              .onFailure {
                Toast.makeText(context, "تعذر تجهيز نص الآية للمشاركة", Toast.LENGTH_SHORT).show()
              }
          }
        },
        onBookmark = {
          coroutineScope.launch {
            val (added, _) = viewModel.toggleBookmarkForSelectedAyahs()
            Toast.makeText(
              context,
              if (added) "تمت إضافة المرجعية" else "تمت إزالة المرجعية",
              Toast.LENGTH_SHORT,
            ).show()
          }
        },
        onClose = { viewModel.clearVerseSelection() },
      )
    }

    AnimatedVisibility(
      visible = activeQuarterBannerText != null && !isHomeOpen && !isIndexOpen,
      enter = fadeIn() + slideInVertically { it / 2 },
      exit = fadeOut() + slideOutVertically { it / 2 },
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(bottom = 72.dp),
    ) {
      Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF143833).copy(alpha = 0.95f),
        border = BorderStroke(1.dp, QabasatNightGold.copy(alpha = 0.6f)),
        shadowElevation = 8.dp,
        modifier = Modifier.testTag("quarter_hizb_banner"),
      ) {
        Text(
          text = activeQuarterBannerText.orEmpty(),
          style = MaterialTheme.typography.titleMedium.copy(
            fontFamily = AmiriFontFamily,
            fontWeight = FontWeight.Bold,
          ),
          color = QabasatNightGold,
          modifier = Modifier.padding(horizontal = 22.dp, vertical = 9.dp),
        )
      }
    }

    ReaderVerseInsightsHost(
      verseInsightsStateFlow = viewModel.verseInsightsState,
      tafsirCatalogStateFlow = viewModel.tafsirCatalogState,
      checkedTafsirIdsFlow = viewModel.checkedTafsirEditionIdsState,
      surahsById = surahsById,
      initialSourceFilter = insightsInitialFilter,
      onToggleSourceChecked = remember(viewModel) { { id, checked -> viewModel.toggleTafsirSourceChecked(id, checked) } },
      onOpenTafsirManager = remember(viewModel) {
        {
          viewModel.ensureTafsirCatalogLoaded()
          showTafsirManager = true
        }
      },
      onPlaySingleAyah = remember(viewModel) { { surahId, ayahNum -> viewModel.playSingleAyah(surahId, ayahNum) } },
      onPlayFromAyah = remember(viewModel) { { surahId, ayahNum -> viewModel.playFromAyah(surahId, ayahNum) } },
      onPlayAyahRange = remember(viewModel) { { range -> viewModel.playAyahRange(range) } },
      onOpenRangePicker = remember { { activeAudioSheet = ReaderAudioSheet.PLAYBACK } },
      onDismiss = remember(viewModel) { { viewModel.dismissVerseInsights() } },
      onSheetTopInRootChanged = remember { { top -> sheetTopInRootWindow = top } },
      modifier = Modifier.align(Alignment.BottomCenter),
    )

    if (showTafsirManager) {
      ReaderTafsirManagerHost(
        viewModel = viewModel,
        onClose = { showTafsirManager = false },
      )
    }

    AppUpdateDialogHost(viewModel = viewModel)
  }
}

@Composable
private fun AyahSelectionActionBar(
  selectionRect: PageSelectionRootRect?,
  rootWidthPx: Float,
  rootHeightPx: Float,
  isTopChromeVisible: Boolean,
  isBookmarked: Boolean,
  onPlay: () -> Unit,
  onOpenTafsir: () -> Unit,
  onOpenTranslation: () -> Unit,
  onCopy: () -> Unit,
  onShare: () -> Unit,
  onBookmark: () -> Unit,
  onClose: () -> Unit,
) {
  val density = LocalDensity.current
  var barSize by remember { mutableStateOf(IntSize.Zero) }
  val edgePadPx = with(density) { 10.dp.roundToPx() }
  val gapPx = with(density) { 14.dp.roundToPx() }
  val minTopPx = with(density) { if (isTopChromeVisible) 88.dp.roundToPx() else 18.dp.roundToPx() }
  val bottomFallbackPx = with(density) { 86.dp.roundToPx() }

  val offset = if (barSize.width > 0 && barSize.height > 0 && rootWidthPx > 0f && rootHeightPx > 0f) {
    val maxX = (rootWidthPx.roundToInt() - barSize.width - edgePadPx).coerceAtLeast(edgePadPx)
    val maxY = (rootHeightPx.roundToInt() - barSize.height - edgePadPx).coerceAtLeast(minTopPx)
    if (selectionRect != null) {
      val centerX = ((selectionRect.left + selectionRect.right) * 0.5f).roundToInt()
      val x = (centerX - barSize.width / 2).coerceIn(edgePadPx, maxX)
      val aboveY = (selectionRect.top - barSize.height - gapPx).roundToInt()
      val y = if (aboveY >= minTopPx) {
        aboveY.coerceAtMost(maxY)
      } else {
        (selectionRect.bottom + gapPx + with(density) { 18.dp.roundToPx() }).roundToInt().coerceIn(minTopPx, maxY)
      }
      IntOffset(x, y)
    } else {
      val x = ((rootWidthPx.roundToInt() - barSize.width) / 2).coerceIn(edgePadPx, maxX)
      val y = (rootHeightPx.roundToInt() - barSize.height - bottomFallbackPx).coerceIn(minTopPx, maxY)
      IntOffset(x, y)
    }
  } else {
    IntOffset(edgePadPx, minTopPx)
  }

  Surface(
    shape = RoundedCornerShape(26.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 6.dp,
    shadowElevation = 10.dp,
    modifier = Modifier
      .offset { offset }
      .onSizeChanged { barSize = it }
      .border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
        shape = RoundedCornerShape(26.dp),
      )
      .testTag("ayah_selection_action_bar"),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      modifier = Modifier
        .horizontalScroll(rememberScrollState())
        .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
      SelectionPillAction(
        icon = Icons.Filled.PlayArrow,
        label = "تلاوة",
        contentDescription = "تشغيل التلاوة",
        onClick = onPlay,
        testTag = "selection_action_play",
      )
      SelectionPillAction(
        icon = Icons.AutoMirrored.Filled.MenuBook,
        label = "تفسير",
        contentDescription = "تفسير الآية",
        onClick = onOpenTafsir,
        testTag = "selection_action_tafsir",
      )
      SelectionPillAction(
        icon = Icons.Filled.Translate,
        label = "ترجمة",
        contentDescription = "ترجمة الآية",
        onClick = onOpenTranslation,
        testTag = "selection_action_translation",
      )
      SelectionPillAction(
        icon = Icons.Filled.ContentCopy,
        label = "نسخ",
        contentDescription = "نسخ الآية",
        onClick = onCopy,
        testTag = "selection_action_copy",
      )
      IconButton(
        onClick = onShare,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("selection_action_share"),
      ) {
        Icon(
          imageVector = Icons.Filled.Share,
          contentDescription = "مشاركة الآية",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(19.dp),
        )
      }
      IconButton(
        onClick = onBookmark,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("selection_action_bookmark"),
      ) {
        Icon(
          imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
          contentDescription = "إضافة علامة مرجعية",
          tint = if (isBookmarked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(19.dp),
        )
      }
      IconButton(
        onClick = onClose,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("selection_action_close"),
      ) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = "إلغاء التحديد",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(19.dp),
        )
      }
    }
  }
}

@Composable
private fun SelectionPillAction(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  contentDescription: String,
  onClick: () -> Unit,
  testTag: String,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    modifier = Modifier
      .clip(RoundedCornerShape(18.dp))
      .clickable(onClick = onClick)
      .minimumInteractiveComponentSize()
      .padding(horizontal = 8.dp, vertical = 6.dp)
      .testTag(testTag),
  ) {
    Icon(
      imageVector = icon,
      contentDescription = contentDescription,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(18.dp),
    )
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun ReaderPagerHost(
  currentPage: Int,
  effectiveTwoPageMode: Boolean,
  backgroundTheme: BackgroundTheme,
  bookmarkSet: Set<Int>,
  isDarkTheme: Boolean,
  isPdfMode: Boolean,
  settledAvailWidthDp: Dp,
  settledAvailHeightDp: Dp,
  pagesStateFlow: StateFlow<Map<Int, PageLoadState>>,
  activePlayingVerseKeyProvider: () -> String?,
  selectedVerseKeysProvider: () -> Set<String>,
  onUpdateRenderSpec: (widthPx: Int, heightPx: Int, density: Float, fontScale: Float, isTwoPageMode: Boolean) -> Unit,
  onPageChanged: (Int) -> Unit,
  onToggleBookmark: (Int) -> Unit,
  onRetry: (Int) -> Unit,
  onNonWordTapped: () -> Unit,
  onWordTapped: (String, Int, String) -> Unit,
  onWordLongPressed: (String, Int, String) -> Unit,
  onSelectionHandleDragged: (Boolean, String, Int, String) -> Unit,
  onSelectionBoundsChanged: (Int, PageSelectionRootRect?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val pages by pagesStateFlow.collectAsStateWithLifecycle()
  val density = LocalDensity.current

  val (settledSlotW, settledSlotH) = remember(effectiveTwoPageMode, settledAvailWidthDp, settledAvailHeightDp) {
    if (effectiveTwoPageMode) {
      (settledAvailWidthDp / 2f).coerceAtLeast(1.dp) to settledAvailHeightDp.coerceAtLeast(1.dp)
    } else if (settledAvailHeightDp > 0.dp && (settledAvailWidthDp / settledAvailHeightDp) > MUSHAF_PAGE_ASPECT_RATIO) {
      (settledAvailHeightDp * MUSHAF_PAGE_ASPECT_RATIO) to settledAvailHeightDp
    } else {
      settledAvailWidthDp to (settledAvailWidthDp / MUSHAF_PAGE_ASPECT_RATIO)
    }
  }
  val bitmapWidthPx = with(density) { settledSlotW.coerceAtLeast(1.dp).roundToPx() }
  val bitmapHeightPx = with(density) { settledSlotH.coerceAtLeast(1.dp).roundToPx() }

  LaunchedEffect(bitmapWidthPx, bitmapHeightPx, density.density, density.fontScale, effectiveTwoPageMode) {
    onUpdateRenderSpec(bitmapWidthPx, bitmapHeightPx, density.density, density.fontScale, effectiveTwoPageMode)
  }

  BoxWithConstraints(modifier = modifier) {
    val availW = maxWidth
    val availH = maxHeight

    if (effectiveTwoPageMode) {
      val spreadCount = 302
      val targetSpreadIndex = ((currentPage - 1) / 2).coerceIn(0, spreadCount - 1)
      val spreadPagerState = rememberPagerState(
        initialPage = targetSpreadIndex,
        pageCount = { spreadCount },
      )

      LaunchedEffect(currentPage) {
        val expectedSpread = ((currentPage - 1) / 2).coerceIn(0, spreadCount - 1)
        if (spreadPagerState.currentPage != expectedSpread) {
          spreadPagerState.scrollToPage(expectedSpread)
        }
      }

      LaunchedEffect(spreadPagerState) {
        snapshotFlow { spreadPagerState.currentPage }
          .distinctUntilChanged()
          .collect { spreadIdx ->
            val oddPage = (spreadIdx * 2) + 1
            if (currentPage !in oddPage..(oddPage + 1)) {
              onPageChanged(oddPage)
            }
          }
      }

      val onEmptyAreaTapIfNotSwiping: () -> Unit = remember(spreadPagerState, onNonWordTapped) {
        {
          if (!spreadPagerState.isScrollInProgress) {
            onNonWordTapped()
          }
        }
      }

      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        HorizontalPager(
          state = spreadPagerState,
          beyondViewportPageCount = 1,
          key = { spreadIndex -> spreadIndex },
          modifier = Modifier
            .fillMaxSize()
            .testTag("mushaf_spread_pager"),
        ) { spreadIndex ->
          val rightPage = (spreadIndex * 2) + 1
          val leftPage = (rightPage + 1).coerceAtMost(604)
          val rightState = pages[rightPage]
          val leftState = pages[leftPage]
          Row(
            modifier = Modifier
              .fillMaxSize()
              .pointerInput(onEmptyAreaTapIfNotSwiping) {
                detectTapGestures(onTap = { onEmptyAreaTapIfNotSwiping() })
              },
          ) {
            MushafPageFrame(
              pageNumber = rightPage,
              pageState = rightState,
              backgroundTheme = backgroundTheme,
              isBookmarked = bookmarkSet.contains(rightPage),
              isDarkTheme = isDarkTheme,
              activePlayingVerseKeyProvider = activePlayingVerseKeyProvider,
              selectedVerseKeysProvider = selectedVerseKeysProvider,
              onToggleBookmark = remember(onToggleBookmark, rightPage) { { onToggleBookmark(rightPage) } },
              onRetry = remember(onRetry, rightPage) { { onRetry(rightPage) } },
              onNonWordTapped = onEmptyAreaTapIfNotSwiping,
              onWordTapped = onWordTapped,
              onWordLongPressed = onWordLongPressed,
              onSelectionHandleDragged = onSelectionHandleDragged,
              onSelectionBoundsChanged = onSelectionBoundsChanged,
              isPdfMode = isPdfMode,
              modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            )
            if (leftPage != rightPage) {
              MushafPageFrame(
                pageNumber = leftPage,
                pageState = leftState,
                backgroundTheme = backgroundTheme,
                isBookmarked = bookmarkSet.contains(leftPage),
                isDarkTheme = isDarkTheme,
                activePlayingVerseKeyProvider = activePlayingVerseKeyProvider,
                selectedVerseKeysProvider = selectedVerseKeysProvider,
                onToggleBookmark = remember(onToggleBookmark, leftPage) { { onToggleBookmark(leftPage) } },
                onRetry = remember(onRetry, leftPage) { { onRetry(leftPage) } },
                onNonWordTapped = onEmptyAreaTapIfNotSwiping,
                onWordTapped = onWordTapped,
                onWordLongPressed = onWordLongPressed,
                onSelectionHandleDragged = onSelectionHandleDragged,
                onSelectionBoundsChanged = onSelectionBoundsChanged,
                isPdfMode = isPdfMode,
                modifier = Modifier
                  .weight(1f)
                  .fillMaxHeight(),
              )
            }
          }
        }
      }
    } else {
      val singlePagerState = rememberPagerState(
        initialPage = (currentPage - 1).coerceIn(0, 603),
        pageCount = { 604 },
      )

      LaunchedEffect(currentPage) {
        val targetIdx = (currentPage - 1).coerceIn(0, 603)
        if (singlePagerState.currentPage != targetIdx) {
          singlePagerState.scrollToPage(targetIdx)
        }
      }

      LaunchedEffect(singlePagerState) {
        snapshotFlow { singlePagerState.currentPage }
          .distinctUntilChanged()
          .collect { idx ->
            onPageChanged(idx + 1)
          }
      }

      val onEmptyAreaTapIfNotSwiping: () -> Unit = remember(singlePagerState, onNonWordTapped) {
        {
          if (!singlePagerState.isScrollInProgress) {
            onNonWordTapped()
          }
        }
      }

      val (pageWidth, pageHeight) = remember(availW, availH) {
        if (availH > 0.dp && (availW / availH) > MUSHAF_PAGE_ASPECT_RATIO) {
          (availH * MUSHAF_PAGE_ASPECT_RATIO) to availH
        } else {
          availW to (availW / MUSHAF_PAGE_ASPECT_RATIO)
        }
      }

      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        HorizontalPager(
          state = singlePagerState,
          beyondViewportPageCount = 1,
          key = { pageIdx -> pageIdx + 1 },
          modifier = Modifier
            .fillMaxSize()
            .testTag("mushaf_single_pager"),
        ) { pageIdx ->
          val pageNum = pageIdx + 1
          val pageState = pages[pageNum]
          Box(
            modifier = Modifier
              .fillMaxSize()
              .pointerInput(onEmptyAreaTapIfNotSwiping) {
                detectTapGestures(onTap = { onEmptyAreaTapIfNotSwiping() })
              },
            contentAlignment = Alignment.Center,
          ) {
            MushafPageFrame(
              pageNumber = pageNum,
              pageState = pageState,
              backgroundTheme = backgroundTheme,
              isBookmarked = bookmarkSet.contains(pageNum),
              isDarkTheme = isDarkTheme,
              activePlayingVerseKeyProvider = activePlayingVerseKeyProvider,
              selectedVerseKeysProvider = selectedVerseKeysProvider,
              onToggleBookmark = remember(onToggleBookmark, pageNum) { { onToggleBookmark(pageNum) } },
              onRetry = remember(onRetry, pageNum) { { onRetry(pageNum) } },
              onNonWordTapped = onEmptyAreaTapIfNotSwiping,
              onWordTapped = onWordTapped,
              onWordLongPressed = onWordLongPressed,
              onSelectionHandleDragged = onSelectionHandleDragged,
              onSelectionBoundsChanged = onSelectionBoundsChanged,
              isPdfMode = isPdfMode,
              modifier = Modifier.size(width = pageWidth, height = pageHeight),
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ReaderVerseInsightsHost(
  verseInsightsStateFlow: StateFlow<VerseInsightsUiState>,
  tafsirCatalogStateFlow: StateFlow<com.example.data.quran.TafsirCatalogUiState>,
  checkedTafsirIdsFlow: StateFlow<Set<String>>,
  surahsById: Map<Int, SurahMeta>,
  initialSourceFilter: InsightsSourceFilter,
  onToggleSourceChecked: (String, Boolean) -> Unit,
  onOpenTafsirManager: () -> Unit,
  onPlaySingleAyah: (Int, Int) -> Unit,
  onPlayFromAyah: (Int, Int) -> Unit,
  onPlayAyahRange: (RecitationRange) -> Unit,
  onOpenRangePicker: () -> Unit,
  onDismiss: () -> Unit,
  onSheetTopInRootChanged: (Float?) -> Unit,
  modifier: Modifier = Modifier,
) {
  val state by verseInsightsStateFlow.collectAsStateWithLifecycle()
  val catalog by tafsirCatalogStateFlow.collectAsStateWithLifecycle()
  val checkedIds by checkedTafsirIdsFlow.collectAsStateWithLifecycle()
  VerseInsightsSheet(
    state = state,
    surahsById = surahsById,
    downloadedSources = catalog.downloadedEditions,
    checkedEditionIds = checkedIds,
    onToggleSourceChecked = onToggleSourceChecked,
    onOpenTafsirManager = onOpenTafsirManager,
    onPlaySingleAyah = onPlaySingleAyah,
    onPlayFromAyah = onPlayFromAyah,
    onPlayAyahRange = onPlayAyahRange,
    onOpenRangePicker = onOpenRangePicker,
    onDismiss = onDismiss,
    onSheetTopInRootChanged = onSheetTopInRootChanged,
    initialSourceFilter = initialSourceFilter,
    modifier = modifier,
  )
}

@Composable
private fun ReaderTafsirManagerHost(
  viewModel: QabasatViewModel,
  onClose: () -> Unit,
) {
  val catalogState by viewModel.tafsirCatalogState.collectAsStateWithLifecycle()
  val downloadStates by viewModel.tafsirDownloadStates.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    viewModel.ensureTafsirCatalogLoaded(forceRefresh = false)
  }

  TafsirTranslationsManagerScreen(
    catalogState = catalogState,
    downloadStates = downloadStates,
    onRetryLoadCatalog = { viewModel.ensureTafsirCatalogLoaded(forceRefresh = true) },
    onStartOrResumeDownload = { edition -> viewModel.startOrResumeTafsirDownload(edition) },
    onCancelDownload = { editionId -> viewModel.cancelTafsirDownload(editionId) },
    onUpdateDownloaded = { dl -> viewModel.updateDownloadedTafsir(dl) },
    onDeleteDownloaded = { editionId -> viewModel.deleteDownloadedTafsir(editionId) },
    onClose = onClose,
  )
}

@Composable
private fun ReaderSettingsHost(
  viewModel: QabasatViewModel,
  isDarkMode: Boolean,
  backgroundTheme: com.example.data.prefs.BackgroundTheme,
  effectiveTwoPageMode: Boolean,
  activeMushafEdition: MushafEdition,
  activeMushafIsPdf: Boolean,
  activePdfMushafLabel: String?,
  onOpenReciterDownloads: (VerifiedReciter) -> Unit,
  onOpenReciterManager: () -> Unit,
  onOpenTafsirManager: () -> Unit,
  onOpenAdhanSettings: () -> Unit,
  onOpenMushafsManager: () -> Unit,
  onOpenTajweedLegend: () -> Unit,
  onOpenCredits: () -> Unit,
  onDismiss: () -> Unit,
) {
  val updateState by viewModel.updateState.collectAsStateWithLifecycle()
  val activeId by viewModel.activeMushafIdState.collectAsStateWithLifecycle()
  val downloadedTextMushafIds by viewModel.downloadedMushafIdsState.collectAsStateWithLifecycle()
  val textDownloadStates by viewModel.mushafDownloadManager.states.collectAsStateWithLifecycle()
  val isBetaChannelUnlocked by viewModel.betaChannelUnlockedState.collectAsStateWithLifecycle()
  val isBetaChannelEnabled by viewModel.betaChannelEnabledState.collectAsStateWithLifecycle()
  val downloadableRecitersState by viewModel.downloadableRecitersState.collectAsStateWithLifecycle()
  val reciterSummaries by viewModel.audioReciterSummaries.collectAsStateWithLifecycle()
  val audioTransferStates by viewModel.audioTransferStates.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    viewModel.ensureAllDownloadableRecitersLoaded()
  }

  val activeLabel = if (activeMushafIsPdf) {
    activePdfMushafLabel?.takeIf { it.isNotBlank() } ?: "مصحف الروايات"
  } else {
    MushafRegistry.getById(activeId).title
  }

  val tajweedDownloadState = textDownloadStates["tajweed_v4"]
    ?: MushafDownloadState(mushafId = "tajweed_v4")

  SettingsSheet(
    isDarkMode = isDarkMode,
    backgroundTheme = backgroundTheme,
    effectiveTwoPageMode = effectiveTwoPageMode,
    activeMushafLabel = activeLabel,
    activeMushafIsPdf = activeMushafIsPdf,
    activeTextMushafId = activeId,
    activeMushafEdition = activeMushafEdition,
    downloadedTextMushafIds = downloadedTextMushafIds,
    tajweedDownloadState = tajweedDownloadState,
    onDownloadTajweed = {
      viewModel.startMushafDownload(MushafRegistry.getByEdition(MushafEdition.TAJWEED_V4))
    },
    onCancelTajweedDownload = { viewModel.cancelMushafDownload("tajweed_v4") },
    onDeleteTajweed = { viewModel.removeMushaf("tajweed_v4") },
    onSelectPrimaryMushaf = { edition ->
      viewModel.selectActiveMushaf(MushafRegistry.getByEdition(edition).id)
    },
    downloadableReciters = downloadableRecitersState.reciters,
    downloadableRecitersLoading = downloadableRecitersState.isLoading,
    downloadableRecitersError = downloadableRecitersState.errorMessage,
    reciterSummaries = reciterSummaries,
    audioTransferStates = audioTransferStates,
    onRetryLoadDownloadableReciters = { viewModel.ensureAllDownloadableRecitersLoaded(forceRefresh = true) },
    onOpenReciterDownloads = onOpenReciterDownloads,
    onOpenReciterManager = onOpenReciterManager,
    onOpenTafsirManager = onOpenTafsirManager,
    onOpenAdhanSettings = onOpenAdhanSettings,
    onOpenMushafsManager = onOpenMushafsManager,
    onToggleDarkMode = { viewModel.toggleDarkMode() },
    onSelectBackgroundTheme = { theme -> viewModel.setBackgroundTheme(theme) },
    onSelectPageViewMode = { mode -> viewModel.setPageViewMode(mode) },
    onOpenTajweedLegend = onOpenTajweedLegend,
    onOpenCredits = onOpenCredits,
    onDismiss = onDismiss,
    isCheckingUpdate = updateState is AppUpdateState.Checking,
    onCheckForUpdate = { viewModel.checkForUpdates() },
    isBetaChannelUnlocked = isBetaChannelUnlocked,
    isBetaChannelEnabled = isBetaChannelEnabled,
    onUnlockBetaChannel = { viewModel.unlockBetaChannel() },
    onToggleBetaChannel = { enabled -> viewModel.setBetaChannelEnabled(enabled) },
  )
}

@Composable
private fun ReaderReciterManagerHost(
  viewModel: QabasatViewModel,
  surahs: List<SurahMeta>,
  initialReciterKey: String?,
  onClose: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val downloadableRecitersState by viewModel.downloadableRecitersState.collectAsStateWithLifecycle()
  val downloadedKeys by viewModel.downloadedAudioKeys.collectAsStateWithLifecycle()
  val transferStates by viewModel.audioTransferStates.collectAsStateWithLifecycle()
  val reciterSummaries by viewModel.audioReciterSummaries.collectAsStateWithLifecycle()
  val batchSummary by viewModel.audioBatchSummary.collectAsStateWithLifecycle()

  LaunchedEffect(Unit) {
    viewModel.ensureAllDownloadableRecitersLoaded()
  }

  FullReciterManagerScreen(
    allReciters = downloadableRecitersState.reciters,
    pdfMushafs = uiState.pdfMushafs,
    surahs = surahs,
    downloadedKeys = downloadedKeys,
    transferStates = transferStates,
    reciterSummaries = reciterSummaries,
    batchSummary = batchSummary,
    recitersLoading = downloadableRecitersState.isLoading,
    recitersError = downloadableRecitersState.errorMessage,
    onRetryLoadReciters = { viewModel.ensureAllDownloadableRecitersLoaded(forceRefresh = true) },
    onDownloadSurah = { reciter, surahId ->
      viewModel.downloadSingleSurahForReciter(reciter, surahId)
    },
    onCancelSurah = { reciterKey, surahId ->
      viewModel.cancelSurahDownloadForReciter(reciterKey, surahId)
    },
    onDeleteSurah = { reciter, surahId ->
      viewModel.deleteSurahForReciter(reciter.key, surahId)
    },
    onDownloadAll = { reciter ->
      viewModel.downloadAllSurahsForReciter(reciter)
    },
    onCancelAllForReciter = { reciterKey ->
      viewModel.cancelAllDownloadsForReciter(reciterKey)
    },
    onDeleteAllForReciter = { reciter ->
      viewModel.deleteAllSurahsForReciter(reciter.key)
    },
    onDismissLowStorageMessage = {
      viewModel.clearAudioLowStorageMessage()
    },
    onClose = onClose,
    initialReciterKey = initialReciterKey,
  )
}

@Composable
private fun ReaderMushafsManagerHost(
  viewModel: QabasatViewModel,
  activeMushafIsPdf: Boolean,
  activePdfMushafId: String?,
  pdfMushafs: List<com.example.data.pdf.PdfMushafEntry>,
  pdfManifestLoading: Boolean,
  pdfManifestError: String?,
  onClose: () -> Unit,
  onMushafActivated: () -> Unit,
) {
  val downloadedTextIds by viewModel.downloadedMushafIdsState.collectAsStateWithLifecycle()
  val activeTextId by viewModel.activeMushafIdState.collectAsStateWithLifecycle()
  val textDownloadStates by viewModel.mushafDownloadManager.states.collectAsStateWithLifecycle()
  val pdfTransferStates by viewModel.pdfTransferStates.collectAsStateWithLifecycle()
  val offlinePdfIds by viewModel.offlinePdfIds.collectAsStateWithLifecycle()
  val localOrCachedPdfIds by viewModel.localOrCachedPdfIds.collectAsStateWithLifecycle()

  MushafsManagerScreen(
    downloadedTextMushafIds = downloadedTextIds,
    activeTextMushafId = activeTextId,
    activeMushafIsPdf = activeMushafIsPdf,
    activePdfMushafId = activePdfMushafId,
    textDownloadStates = textDownloadStates,
    pdfMushafs = pdfMushafs,
    pdfManifestLoading = pdfManifestLoading,
    pdfManifestError = pdfManifestError,
    pdfTransferStates = pdfTransferStates,
    offlinePdfIds = offlinePdfIds,
    localOrCachedPdfIds = localOrCachedPdfIds,
    onLoadThumbnail = { entry -> viewModel.loadPdfThumbnail(entry) },
    onRefreshManifest = { viewModel.loadPdfManifest(forceRefresh = true) },
    onSelectTextMushaf = { id ->
      viewModel.selectActiveMushaf(id)
      onMushafActivated()
    },
    onStartTextDownload = { item -> viewModel.startMushafDownload(item) },
    onCancelTextDownload = { id -> viewModel.cancelMushafDownload(id) },
    onRemoveTextMushaf = { id -> viewModel.removeMushaf(id) },
    onSelectPdfMushaf = { entry ->
      viewModel.selectPdfMushaf(entry)
      onMushafActivated()
    },
    onDownloadPdfMushaf = { entry -> viewModel.downloadPdfMushafOffline(entry) },
    onCancelPdfTransfer = { id -> viewModel.cancelPdfTransfer(id) },
    onDeletePdfMushaf = { entry -> viewModel.deletePdfMushafOffline(entry) },
    onForceUpdatePdfMushaf = { entry -> viewModel.forceUpdatePdfMushaf(entry) },
    onClose = onClose,
  )
}

@Composable
private fun AppUpdateDialogHost(
  viewModel: QabasatViewModel,
) {
  val updateState by viewModel.updateState.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current
  val context = LocalContext.current
  val activity = remember(context) { context.findActivity() }

  DisposableEffect(lifecycleOwner, viewModel) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        viewModel.resumePendingUpdateInstall()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  when (val state = updateState) {
    AppUpdateState.Idle, AppUpdateState.Checking -> Unit

    is AppUpdateState.UpdateAvailable -> {
      AlertDialog(
        onDismissRequest = { viewModel.dismissUpdateDialog() },
        text = {
          Text(
            text = "يتوفر تحديث جديد للتطبيق",
            style = MaterialTheme.typography.bodyLarge,
          )
        },
        confirmButton = {
          TextButton(
            onClick = { viewModel.downloadUpdate(state) },
            modifier = Modifier.testTag("update_download_button"),
          ) {
            Text("تحديث")
          }
        },
        dismissButton = {
          TextButton(
            onClick = { viewModel.dismissUpdateDialog() },
            modifier = Modifier.testTag("update_later_button"),
          ) {
            Text("لاحقًا")
          }
        },
      )
    }

    AppUpdateState.UpToDate -> {
      AlertDialog(
        onDismissRequest = { viewModel.dismissUpdateDialog() },
        title = { Text("تحديث التطبيق") },
        text = { Text("أنت على آخر نسخة") },
        confirmButton = {
          TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
            Text("حسناً")
          }
        },
      )
    }

    is AppUpdateState.Downloading -> {
      AlertDialog(
        onDismissRequest = {},
        title = { Text("جاري تحميل التحديث") },
        text = {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            val pct = state.progressPercent
            if (pct != null) {
              LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("update_download_progress_bar"),
              )
              Text(text = "جاري التنزيل ($pct%)")
            } else {
              LinearProgressIndicator(
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("update_download_progress_bar"),
              )
              Text(text = "جاري التنزيل...")
            }
          }
        },
        confirmButton = {
          TextButton(onClick = { viewModel.cancelUpdateDownload() }) {
            Text("إلغاء")
          }
        },
      )
    }

    AppUpdateState.PreparingInstall -> {
      AlertDialog(
        onDismissRequest = {},
        title = { Text("تحديث التطبيق") },
        text = {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            LinearProgressIndicator(
              modifier = Modifier
                .fillMaxWidth()
                .testTag("update_preparing_progress_bar"),
            )
            Text(text = "جارٍ فتح التثبيت...")
          }
        },
        confirmButton = {
          TextButton(onClick = { viewModel.cancelUpdateDownload() }) {
            Text("إلغاء")
          }
        },
      )
    }

    is AppUpdateState.OpeningInstaller -> {
      LaunchedEffect(state) {
        viewModel.launchApkInstaller(activity, state.apkFile)
      }
      AlertDialog(
        onDismissRequest = {},
        title = { Text("تحديث التطبيق") },
        text = {
          Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            LinearProgressIndicator(
              modifier = Modifier
                .fillMaxWidth()
                .testTag("update_opening_progress_bar"),
            )
            Text(text = "جارٍ فتح التثبيت...")
          }
        },
        confirmButton = {},
      )
    }

    is AppUpdateState.NeedsInstallPermission -> {
      if (state.autoLaunchPending) {
        LaunchedEffect(state) {
          viewModel.openUnknownSourcesSettings(activity, markLaunchedFromAuto = true)
        }
      }
      AlertDialog(
        onDismissRequest = { viewModel.dismissUpdateDialog() },
        title = { Text("إذن التثبيت") },
        text = { Text("يرجى السماح بالتثبيت من مصدر غير معروف لإكمال التحديث.") },
        confirmButton = {
          TextButton(onClick = { viewModel.openUnknownSourcesSettings(activity) }) {
            Text("فتح الإعدادات")
          }
        },
        dismissButton = {
          TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
            Text("إلغاء")
          }
        },
      )
    }

    is AppUpdateState.Error -> {
      AlertDialog(
        onDismissRequest = { viewModel.dismissUpdateDialog() },
        title = { Text("تحديث التطبيق") },
        text = { Text(state.message) },
        confirmButton = {
          TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
            Text("حسناً")
          }
        },
      )
    }
  }
}

@Composable
private fun ReaderBottomAudioButtonsHost(
  audioStateFlow: StateFlow<AudioPlaybackUiState>,
  onOpenReciters: () -> Unit,
  onOpenPlayback: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val playIndicator by remember(audioStateFlow) {
    audioStateFlow.map { it.isPlaying to it.isBuffering }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = audioStateFlow.value.isPlaying to audioStateFlow.value.isBuffering)

  ReaderBottomAudioButtons(
    isPlaying = playIndicator.first,
    isBuffering = playIndicator.second,
    onOpenReciters = onOpenReciters,
    onOpenPlayback = onOpenPlayback,
    modifier = modifier,
  )
}

@Composable
private fun CompactDockedAudioPlayerHost(
  audioStateFlow: StateFlow<AudioPlaybackUiState>,
  isReciterPopupOpen: Boolean,
  onToggleReciterPopup: () -> Unit,
  onExpandFullSheet: () -> Unit,
  onTogglePlayPause: () -> Unit,
  onSeekTo: (Long) -> Unit,
  onSeekDelta: (Long) -> Unit,
  onNextSurah: () -> Unit,
  onPrevSurah: () -> Unit,
  onCycleSpeed: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  val audioState by audioStateFlow.collectAsStateWithLifecycle()
  CompactDockedAudioPlayer(
    audioState = audioState,
    isReciterPopupOpen = isReciterPopupOpen,
    onToggleReciterPopup = onToggleReciterPopup,
    onExpandFullSheet = onExpandFullSheet,
    onTogglePlayPause = onTogglePlayPause,
    onSeekTo = onSeekTo,
    onSeekDelta = onSeekDelta,
    onNextSurah = onNextSurah,
    onPrevSurah = onPrevSurah,
    onCycleSpeed = { onCycleSpeed(audioState.playbackSpeed) },
    modifier = modifier,
  )
}

@Composable
private fun CompactRecitersPopupHost(
  audioStateFlow: StateFlow<AudioPlaybackUiState>,
  primaryReciters: List<VerifiedReciter>,
  additionalReciters: List<VerifiedReciter>,
  isLoading: Boolean,
  errorMessage: String?,
  isPdfMode: Boolean,
  onRetryLoad: () -> Unit,
  onSelectReciter: (VerifiedReciter) -> Unit,
  onSelectReciterStyle: (VerifiedReciter, ReciterMoshafStyle) -> Unit,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val activeReciter by remember(audioStateFlow) {
    audioStateFlow.map { it.currentReciter }.distinctUntilChanged()
  }.collectAsStateWithLifecycle(initialValue = audioStateFlow.value.currentReciter)

  CompactRecitersPopup(
    primaryReciters = primaryReciters,
    additionalReciters = additionalReciters,
    activeReciter = activeReciter,
    isLoading = isLoading,
    errorMessage = errorMessage,
    isPdfMode = isPdfMode,
    onRetryLoad = onRetryLoad,
    onSelectReciter = onSelectReciter,
    onDismiss = onDismiss,
    modifier = modifier,
    onSelectReciterStyle = onSelectReciterStyle,
  )
}

@Composable
private fun ReaderPlaybackSheetHost(
  audioStateFlow: StateFlow<AudioPlaybackUiState>,
  surahs: List<SurahMeta>,
  isPdfMode: Boolean,
  recitersEmptyForRiwaya: Boolean,
  onTogglePlayPause: () -> Unit,
  onSeekTo: (Long) -> Unit,
  onSeekDelta: (Long) -> Unit,
  onNextSurah: () -> Unit,
  onPrevSurah: () -> Unit,
  onCycleSpeed: (Float) -> Unit,
  onPlayFromAyah: (Int, Int) -> Unit,
  onPlayRange: (RecitationRange) -> Unit,
  onClearRange: () -> Unit,
  onDismiss: () -> Unit,
  onPlaybackStarted: () -> Unit,
) {
  val audioState by audioStateFlow.collectAsStateWithLifecycle()
  PlaybackControlSheet(
    audioState = audioState,
    surahs = surahs,
    onTogglePlayPause = onTogglePlayPause,
    onSeekTo = onSeekTo,
    onSeekDelta = onSeekDelta,
    onNextSurah = onNextSurah,
    onPrevSurah = onPrevSurah,
    onCycleSpeed = { onCycleSpeed(audioState.playbackSpeed) },
    onPlayFromAyah = onPlayFromAyah,
    onPlayRange = onPlayRange,
    onClearRange = onClearRange,
    onDismiss = onDismiss,
    onPlaybackStarted = onPlaybackStarted,
    isPdfMode = isPdfMode,
    recitersEmptyForRiwaya = recitersEmptyForRiwaya,
  )
}

@Composable
private fun ReaderReciterPickerHost(
  audioStateFlow: StateFlow<AudioPlaybackUiState>,
  primaryReciters: List<VerifiedReciter>,
  additionalReciters: List<VerifiedReciter>,
  surahs: List<SurahMeta>,
  isLoading: Boolean,
  errorMessage: String?,
  mode: AudioPickerSheetMode,
  isPdfMode: Boolean,
  onRetryLoad: () -> Unit,
  onSelectReciter: (VerifiedReciter) -> Unit,
  onSelectReciterStyle: (VerifiedReciter, ReciterMoshafStyle) -> Unit,
  onPlayFromAyah: (Int, Int) -> Unit,
  onPlayRange: (RecitationRange) -> Unit,
  onClearRange: () -> Unit,
  onOpenReciterDownloads: ((VerifiedReciter) -> Unit)? = null,
  onDismiss: () -> Unit,
) {
  val audioState by audioStateFlow.collectAsStateWithLifecycle()
  ReciterPickerSheet(
    primaryReciters = primaryReciters,
    additionalReciters = additionalReciters,
    activeReciter = audioState.currentReciter,
    currentSurahId = audioState.currentSurahId,
    activeRange = audioState.activeRange,
    surahs = surahs,
    isLoading = isLoading,
    errorMessage = errorMessage,
    onSelectReciter = onSelectReciter,
    onPlayFromAyah = onPlayFromAyah,
    onPlayRange = onPlayRange,
    onClearRange = onClearRange,
    onDismiss = onDismiss,
    mode = mode,
    isPdfMode = isPdfMode,
    onRetryLoad = onRetryLoad,
    onSelectReciterStyle = onSelectReciterStyle,
    onOpenReciterDownloads = onOpenReciterDownloads,
  )
}

@Composable
private fun QabasatTopBar(
  isDarkTheme: Boolean,
  effectiveTwoPageMode: Boolean,
  isCompactWidth: Boolean,
  onSelectPageViewMode: (PageViewMode) -> Unit,
  onOpenIndex: () -> Unit,
  onOpenMushafsManager: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenTajweedLegend: () -> Unit,
  onToggleTheme: () -> Unit,
  onOpenCredits: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val noopInteraction = remember { MutableInteractionSource() }
  Surface(
    color = MaterialTheme.colorScheme.background,
    tonalElevation = 0.dp,
    modifier = modifier
      .fillMaxWidth()
      .clickable(interactionSource = noopInteraction, indication = null, onClick = {})
      .windowInsetsPadding(WindowInsets.statusBars),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
              .clip(RoundedCornerShape(12.dp))
              .clickable(onClick = onOpenIndex)
              .padding(horizontal = 4.dp, vertical = 4.dp)
              .testTag("app_title_index_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.MenuBook,
              contentDescription = "فهرس السور والأجزاء والبحث عن الآيات",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(21.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
              text = "قبسات",
              style = MaterialTheme.typography.headlineSmall.copy(fontFamily = AmiriFontFamily),
              color = MaterialTheme.colorScheme.primary,
            )
          }

          Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .clip(RoundedCornerShape(8.dp))
              .clickable(onClick = onOpenMushafsManager)
              .testTag("top_bar_mushafs_button"),
          ) {
            Text(
              text = "مصاحف",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onPrimary,
              maxLines = 1,
              modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            )
          }
        }

        if (!isCompactWidth) {
          PageViewModeSwitcherPill(
            effectiveTwoPageMode = effectiveTwoPageMode,
            onSelectPageViewMode = onSelectPageViewMode,
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          IconButton(
            onClick = onOpenTajweedLegend,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .size(36.dp)
              .testTag("tajweed_legend_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.ColorLens,
              contentDescription = "دليل الألوان",
              tint = MaterialTheme.colorScheme.secondary,
              modifier = Modifier.size(20.dp),
            )
          }
          IconButton(
            onClick = onToggleTheme,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .size(36.dp)
              .testTag("toggle_theme_button"),
          ) {
            Icon(
              imageVector = if (isDarkTheme) Icons.Filled.Brightness7 else Icons.Filled.Brightness4,
              contentDescription = "تبديل الوضع الليلي والنهاري",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
          }
          IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .size(36.dp)
              .testTag("open_settings_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.Settings,
              contentDescription = "الإعدادات",
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
          }
          IconButton(
            onClick = onOpenCredits,
            modifier = Modifier
              .minimumInteractiveComponentSize()
              .size(36.dp)
              .testTag("open_credits_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.Info,
              contentDescription = "المصادر والتحقق",
              tint = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }

      if (isCompactWidth) {
        Spacer(Modifier.height(4.dp))
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
          horizontalArrangement = Arrangement.Center,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          PageViewModeSwitcherPill(
            effectiveTwoPageMode = effectiveTwoPageMode,
            onSelectPageViewMode = onSelectPageViewMode,
          )
        }
      }
    }
  }
}

@Composable
private fun PageViewModeSwitcherPill(
  effectiveTwoPageMode: Boolean,
  onSelectPageViewMode: (PageViewMode) -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier.padding(horizontal = 2.dp),
  ) {
    Row(
      modifier = Modifier.padding(3.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      EditionPill(
        label = "صفحة واحدة",
        selected = !effectiveTwoPageMode,
        onClick = { onSelectPageViewMode(PageViewMode.SINGLE) },
        testTag = "page_mode_single_button",
      )
      EditionPill(
        label = "صفحتان",
        selected = effectiveTwoPageMode,
        onClick = { onSelectPageViewMode(PageViewMode.TWO_PAGES) },
        testTag = "page_mode_two_button",
      )
    }
  }
}

@Composable
private fun EditionPill(
  label: String,
  selected: Boolean,
  onClick: () -> Unit,
  testTag: String,
) {
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
    modifier = Modifier
      .clip(RoundedCornerShape(14.dp))
      .clickable(onClick = onClick)
      .testTag(testTag),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
    )
  }
}


