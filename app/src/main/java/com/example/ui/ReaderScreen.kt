package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.audio.RecitationRange
import com.example.data.audio.VerifiedReciter
import com.example.data.prefs.BackgroundTheme
import com.example.data.prefs.PageViewMode
import com.example.data.quran.MushafEdition
import com.example.data.quran.SurahMeta
import com.example.player.AudioPlaybackUiState
import com.example.ui.components.CreditsAndIntegrityDialog
import com.example.ui.components.FullScreenQuranIndex
import com.example.ui.components.MushafPageFrame
import com.example.ui.components.PersistentAudioBar
import com.example.ui.components.ReciterPickerSheet
import com.example.ui.components.SettingsSheet
import com.example.ui.components.TajweedLegendSheet
import com.example.ui.components.VerseInsightsSheet
import com.example.ui.theme.AmiriFontFamily
import com.example.update.AppUpdateState
import com.example.viewmodel.PageLoadState
import com.example.viewmodel.QabasatViewModel
import com.example.viewmodel.VerseInsightsUiState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

private const val MUSHAF_PAGE_ASPECT_RATIO = 0.62f

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}

@Composable
fun ReaderScreen(
  viewModel: QabasatViewModel,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val downloadedKeys by viewModel.downloadedAudioKeys.collectAsStateWithLifecycle()
  val downloadProgress by viewModel.audioDownloadProgress.collectAsStateWithLifecycle()
  val activeVerseState: State<String?> = viewModel.activePlayingVerseKeyFlow.collectAsStateWithLifecycle()
  val selectedVerseState: State<String?> = viewModel.selectedVerseKeyFlow.collectAsStateWithLifecycle()
  val activePlayingVerseKeyProvider = remember(activeVerseState) { { activeVerseState.value } }
  val selectedVerseKeyProvider = remember(selectedVerseState) { { selectedVerseState.value } }

  val isDarkTheme = uiState.isDarkMode
  val surahsById = remember(uiState.surahs) { uiState.surahs.associateBy { it.id } }
  val bookmarkSet = remember(uiState.bookmarks) { uiState.bookmarks.toHashSet() }

  var isIndexOpen by rememberSaveable { mutableStateOf(true) }
  var hasSelectedSurah by rememberSaveable { mutableStateOf(false) }
  var isChromeVisible by rememberSaveable { mutableStateOf(true) }
  var showReciterSheet by remember { mutableStateOf(false) }
  var showSettingsSheet by remember { mutableStateOf(false) }
  var showTajweedLegend by remember { mutableStateOf(false) }
  var showCreditsDialog by remember { mutableStateOf(false) }

  var topBarHeightPx by remember { mutableIntStateOf(0) }
  var bottomBarHeightPx by remember { mutableIntStateOf(0) }

  val speeds = remember { listOf(0.75f, 1.0f, 1.15f, 1.25f) }
  val onCycleSpeed: (Float) -> Unit = remember(viewModel, speeds) {
    { currentSpeed ->
      val curIdx = speeds.indexOfFirst { kotlin.math.abs(it - currentSpeed) < 0.05f }
      val nextSpeed = speeds[(curIdx + 1) % speeds.size]
      viewModel.playbackController.setSpeed(nextSpeed)
    }
  }
  val onVerseLongPressed: (String, Int, String) -> Unit = remember(viewModel) {
    { key, p, glyphs -> viewModel.onVerseTapped(key, p, glyphs) }
  }
  val onToggleBookmark: (Int) -> Unit = remember(viewModel) { { p -> viewModel.toggleBookmark(p) } }
  val onRetryPage: (Int) -> Unit = remember(viewModel) { { p -> viewModel.retryPage(p) } }
  val onPageChangedCallback: (Int) -> Unit = remember(viewModel) { { p -> viewModel.onPageChanged(p) } }
  val onToggleChrome: () -> Unit = remember { { isChromeVisible = !isChromeVisible } }
  val view = LocalView.current
  val density = LocalDensity.current

  LaunchedEffect(isChromeVisible, isIndexOpen) {
    val activity = view.context.findActivity() ?: return@LaunchedEffect
    val controller = WindowCompat.getInsetsController(activity.window, view)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (isChromeVisible || isIndexOpen) {
      controller.show(WindowInsetsCompat.Type.systemBars())
    } else {
      controller.hide(WindowInsetsCompat.Type.systemBars())
    }
  }

  DisposableEffect(view) {
    onDispose {
      val activity = view.context.findActivity() ?: return@onDispose
      WindowCompat.getInsetsController(activity.window, view)
        .show(WindowInsetsCompat.Type.systemBars())
    }
  }

  BackHandler(enabled = !isIndexOpen) {
    isChromeVisible = true
    isIndexOpen = true
  }

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background),
  ) {
    val isLandscape = maxWidth > maxHeight
    val effectiveTwoPageMode = when (uiState.pageViewMode) {
      PageViewMode.AUTO -> isLandscape
      PageViewMode.SINGLE -> false
      PageViewMode.TWO_PAGES -> true
    }
    val topBarDp = with(density) { topBarHeightPx.toDp() }
    val bottomBarDp = with(density) { bottomBarHeightPx.toDp() }
    val targetTopPadding = if (isChromeVisible) topBarDp else 0.dp
    val targetBottomPadding = if (isChromeVisible) bottomBarDp else 0.dp
    val chromeBoundsReady = !isChromeVisible || (topBarHeightPx > 0 && bottomBarHeightPx > 0)
    val settledAvailWidthDp = maxWidth
    val settledAvailHeightDp = (maxHeight - targetTopPadding - targetBottomPadding).coerceAtLeast(1.dp)

    val topPadding by animateDpAsState(
      targetValue = targetTopPadding,
      animationSpec = tween(durationMillis = 200),
      label = "chrome_top_padding",
    )
    val bottomPadding by animateDpAsState(
      targetValue = targetBottomPadding,
      animationSpec = tween(durationMillis = 200),
      label = "chrome_bottom_padding",
    )

    // Main Mushaf page area (deferred until the user opens a surah/page for fast startup)
    if (!isIndexOpen || hasSelectedSurah) {
      ReaderPagerHost(
        currentPage = uiState.currentPage,
        effectiveTwoPageMode = effectiveTwoPageMode,
        backgroundTheme = uiState.backgroundTheme,
        bookmarkSet = bookmarkSet,
        isDarkTheme = isDarkTheme,
        settledAvailWidthDp = settledAvailWidthDp,
        settledAvailHeightDp = settledAvailHeightDp,
        chromeBoundsReady = chromeBoundsReady,
        pagesStateFlow = viewModel.pagesState,
        activePlayingVerseKeyProvider = activePlayingVerseKeyProvider,
        selectedVerseKeyProvider = selectedVerseKeyProvider,
        onUpdateRenderSpec = remember(viewModel) { { w, h, d, fs -> viewModel.updatePageRenderSpec(w, h, d, fs) } },
        onPageChanged = onPageChangedCallback,
        onToggleBookmark = onToggleBookmark,
        onRetry = onRetryPage,
        onToggleChrome = onToggleChrome,
        onVerseLongPressed = onVerseLongPressed,
        modifier = Modifier
          .fillMaxSize()
          .padding(top = topPadding, bottom = bottomPadding),
      )

      // Top chrome (fades 200ms)
      AnimatedVisibility(
        visible = isChromeVisible && !isIndexOpen,
        enter = fadeIn(animationSpec = tween(durationMillis = 200)),
        exit = fadeOut(animationSpec = tween(durationMillis = 200)),
        modifier = Modifier.align(Alignment.TopCenter),
      ) {
        QabasatTopBar(
          mushafEdition = uiState.mushafEdition,
          isDarkTheme = isDarkTheme,
          effectiveTwoPageMode = effectiveTwoPageMode,
          isCompactWidth = maxWidth < 560.dp,
          onToggleEdition = remember(viewModel) { { edition -> viewModel.setMushafEdition(edition) } },
          onSelectPageViewMode = remember(viewModel) { { mode -> viewModel.setPageViewMode(mode) } },
          onOpenIndex = remember { { isIndexOpen = true } },
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
          modifier = Modifier.onSizeChanged { topBarHeightPx = it.height },
        )
      }

      // Bottom audio player chrome (fades 200ms, isolated audio state host)
      AnimatedVisibility(
        visible = isChromeVisible && !isIndexOpen,
        enter = fadeIn(animationSpec = tween(durationMillis = 200)),
        exit = fadeOut(animationSpec = tween(durationMillis = 200)),
        modifier = Modifier.align(Alignment.BottomCenter),
      ) {
        ReaderBottomAudioHost(
          audioStateFlow = viewModel.audioState,
          onTogglePlayPause = remember(viewModel) { { viewModel.playbackController.togglePlayPause() } },
          onSeekTo = remember(viewModel) { { pos -> viewModel.playbackController.seekTo(pos) } },
          onSeekDelta = remember(viewModel) { { delta -> viewModel.playbackController.seekByDelta(delta) } },
          onNextSurah = remember(viewModel) { { viewModel.playbackController.nextSurah() } },
          onPrevSurah = remember(viewModel) { { viewModel.playbackController.prevSurah() } },
          onCycleSpeed = onCycleSpeed,
          onOpenReciterPicker = remember { { showReciterSheet = true } },
          modifier = Modifier.onSizeChanged { bottomBarHeightPx = it.height },
        )
      }
    }

    if (isIndexOpen) {
      FullScreenQuranIndex(
        surahs = uiState.surahs,
        juzs = uiState.juzs,
        juzsLoading = uiState.juzsLoading,
        bookmarks = uiState.bookmarks,
        currentPage = uiState.currentPage,
        mushafEdition = uiState.mushafEdition,
        isDarkTheme = isDarkTheme,
        activeReciterFlow = viewModel.activeReciterFlow,
        miniAudioStateFlow = viewModel.miniAudioStateFlow,
        audioStateFlow = viewModel.audioState,
        downloadedKeys = downloadedKeys,
        downloadProgress = downloadProgress,
        verseSearchResults = uiState.verseSearchResults,
        verseSearchLoading = uiState.verseSearchLoading,
        verseSearchError = uiState.verseSearchError,
        metadataError = uiState.metadataError,
        hasActiveReaderPage = hasSelectedSurah,
        onRetryMetadata = { viewModel.retryBootstrap() },
        onSelectJuzTab = { viewModel.ensureJuzsLoaded() },
        onSearchVerses = { query -> viewModel.searchVerses(query) },
        onJumpToPage = { page ->
          hasSelectedSurah = true
          isIndexOpen = false
          viewModel.jumpToPage(page)
        },
        onJumpToVerse = { key, page ->
          hasSelectedSurah = true
          isIndexOpen = false
          viewModel.jumpToVerse(key, page)
        },
        onPlaySurah = { surahId ->
          val targetPage = surahsById[surahId]?.startPage ?: uiState.currentPage
          hasSelectedSurah = true
          isIndexOpen = false
          viewModel.jumpToPage(targetPage)
          viewModel.playSurah(surahId)
        },
        onDownloadSurah = { surahId -> viewModel.downloadSurahAudio(surahId) },
        onDeleteDownloadedSurah = { surahId -> viewModel.deleteDownloadedSurahAudio(surahId) },
        onRemoveBookmark = { page -> viewModel.removeBookmark(page) },
        onOpenReader = {
          hasSelectedSurah = true
          isIndexOpen = false
          viewModel.ensureCurrentPageLoaded()
        },
        onToggleEdition = { edition -> viewModel.setMushafEdition(edition) },
        onToggleTheme = { viewModel.toggleDarkMode() },
        onOpenSettings = { showSettingsSheet = true },
        onOpenCredits = {
          viewModel.ensureCreditsLoaded()
          showCreditsDialog = true
        },
        onOpenRangePicker = { showReciterSheet = true },
        onTogglePlayPause = { viewModel.playbackController.togglePlayPause() },
        onSeekTo = { pos -> viewModel.playbackController.seekTo(pos) },
        onSeekDelta = { delta -> viewModel.playbackController.seekByDelta(delta) },
        onNextSurah = { viewModel.playbackController.nextSurah() },
        onPrevSurah = { viewModel.playbackController.prevSurah() },
        onCycleSpeed = onCycleSpeed,
        onOpenReciterPicker = { showReciterSheet = true },
      )
    }

    if (showReciterSheet) {
      ReaderReciterPickerHost(
        audioStateFlow = viewModel.audioState,
        primaryReciters = uiState.primaryReciters,
        additionalReciters = uiState.additionalReciters,
        surahs = uiState.surahs,
        isLoading = uiState.recitersLoading,
        errorMessage = uiState.recitersError,
        onSelectReciter = { reciter -> viewModel.selectReciter(reciter) },
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
        onDismiss = { showReciterSheet = false },
      )
    }

    if (showSettingsSheet) {
      ReaderSettingsHost(
        viewModel = viewModel,
        isDarkMode = isDarkTheme,
        backgroundTheme = uiState.backgroundTheme,
        mushafEdition = uiState.mushafEdition,
        effectiveTwoPageMode = effectiveTwoPageMode,
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

    if (showTajweedLegend) {
      TajweedLegendSheet(
        rules = uiState.tajweedRules,
        onDismiss = { showTajweedLegend = false },
      )
    }

    if (showCreditsDialog) {
      CreditsAndIntegrityDialog(
        credits = uiState.credits,
        assetHashes = uiState.assetHashes,
        onDismiss = { showCreditsDialog = false },
      )
    }

    ReaderVerseInsightsHost(
      verseInsightsStateFlow = viewModel.verseInsightsState,
      surahsById = surahsById,
      onPlaySingleAyah = remember(viewModel) { { surahId, ayahNum -> viewModel.playSingleAyah(surahId, ayahNum) } },
      onPlayFromAyah = remember(viewModel) { { surahId, ayahNum -> viewModel.playFromAyah(surahId, ayahNum) } },
      onPlayAyahRange = remember(viewModel) { { range -> viewModel.playAyahRange(range) } },
      onOpenRangePicker = remember { { showReciterSheet = true } },
      onDismiss = remember(viewModel) { { viewModel.dismissVerseInsights() } },
    )

    AppUpdateDialogHost(viewModel = viewModel)
  }
}

@Composable
private fun ReaderPagerHost(
  currentPage: Int,
  effectiveTwoPageMode: Boolean,
  backgroundTheme: BackgroundTheme,
  bookmarkSet: Set<Int>,
  isDarkTheme: Boolean,
  settledAvailWidthDp: Dp,
  settledAvailHeightDp: Dp,
  chromeBoundsReady: Boolean,
  pagesStateFlow: StateFlow<Map<Int, PageLoadState>>,
  activePlayingVerseKeyProvider: () -> String?,
  selectedVerseKeyProvider: () -> String?,
  onUpdateRenderSpec: (widthPx: Int, heightPx: Int, density: Float, fontScale: Float) -> Unit,
  onPageChanged: (Int) -> Unit,
  onToggleBookmark: (Int) -> Unit,
  onRetry: (Int) -> Unit,
  onToggleChrome: () -> Unit,
  onVerseLongPressed: (String, Int, String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val pages by pagesStateFlow.collectAsStateWithLifecycle()
  val density = LocalDensity.current

  val (settledSlotW, settledSlotH) = remember(effectiveTwoPageMode, settledAvailWidthDp, settledAvailHeightDp) {
    if (effectiveTwoPageMode) {
      ((settledAvailWidthDp - 8.dp) / 2f).coerceAtLeast(1.dp) to settledAvailHeightDp.coerceAtLeast(1.dp)
    } else if (settledAvailHeightDp > 0.dp && (settledAvailWidthDp / settledAvailHeightDp) > MUSHAF_PAGE_ASPECT_RATIO) {
      (settledAvailHeightDp * MUSHAF_PAGE_ASPECT_RATIO) to settledAvailHeightDp
    } else {
      settledAvailWidthDp to (settledAvailWidthDp / MUSHAF_PAGE_ASPECT_RATIO)
    }
  }
  val bitmapWidthPx = with(density) { (settledSlotW - 6.dp).coerceAtLeast(1.dp).roundToPx() }
  val bitmapHeightPx = with(density) { (settledSlotH - 4.dp).coerceAtLeast(1.dp).roundToPx() }

  LaunchedEffect(chromeBoundsReady, bitmapWidthPx, bitmapHeightPx, density.density, density.fontScale) {
    if (chromeBoundsReady) {
      onUpdateRenderSpec(bitmapWidthPx, bitmapHeightPx, density.density, density.fontScale)
    }
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

      val toggleChromeIfNotSwiping: () -> Unit = remember(spreadPagerState, onToggleChrome) {
        {
          if (!spreadPagerState.isScrollInProgress) {
            onToggleChrome()
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
              .padding(horizontal = 4.dp),
          ) {
            MushafPageFrame(
              pageNumber = rightPage,
              pageState = rightState,
              backgroundTheme = backgroundTheme,
              isBookmarked = bookmarkSet.contains(rightPage),
              isDarkTheme = isDarkTheme,
              activePlayingVerseKeyProvider = activePlayingVerseKeyProvider,
              selectedVerseKeyProvider = selectedVerseKeyProvider,
              onToggleBookmark = remember(onToggleBookmark, rightPage) { { onToggleBookmark(rightPage) } },
              onRetry = remember(onRetry, rightPage) { { onRetry(rightPage) } },
              onPageTapped = toggleChromeIfNotSwiping,
              onVerseLongPressed = onVerseLongPressed,
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
                selectedVerseKeyProvider = selectedVerseKeyProvider,
                onToggleBookmark = remember(onToggleBookmark, leftPage) { { onToggleBookmark(leftPage) } },
                onRetry = remember(onRetry, leftPage) { { onRetry(leftPage) } },
                onPageTapped = toggleChromeIfNotSwiping,
                onVerseLongPressed = onVerseLongPressed,
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

      val toggleChromeIfNotSwiping: () -> Unit = remember(singlePagerState, onToggleChrome) {
        {
          if (!singlePagerState.isScrollInProgress) {
            onToggleChrome()
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
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
          ) {
            MushafPageFrame(
              pageNumber = pageNum,
              pageState = pageState,
              backgroundTheme = backgroundTheme,
              isBookmarked = bookmarkSet.contains(pageNum),
              isDarkTheme = isDarkTheme,
              activePlayingVerseKeyProvider = activePlayingVerseKeyProvider,
              selectedVerseKeyProvider = selectedVerseKeyProvider,
              onToggleBookmark = remember(onToggleBookmark, pageNum) { { onToggleBookmark(pageNum) } },
              onRetry = remember(onRetry, pageNum) { { onRetry(pageNum) } },
              onPageTapped = toggleChromeIfNotSwiping,
              onVerseLongPressed = onVerseLongPressed,
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
  surahsById: Map<Int, SurahMeta>,
  onPlaySingleAyah: (Int, Int) -> Unit,
  onPlayFromAyah: (Int, Int) -> Unit,
  onPlayAyahRange: (RecitationRange) -> Unit,
  onOpenRangePicker: () -> Unit,
  onDismiss: () -> Unit,
) {
  val state by verseInsightsStateFlow.collectAsStateWithLifecycle()
  VerseInsightsSheet(
    state = state,
    surahsById = surahsById,
    onPlaySingleAyah = onPlaySingleAyah,
    onPlayFromAyah = onPlayFromAyah,
    onPlayAyahRange = onPlayAyahRange,
    onOpenRangePicker = onOpenRangePicker,
    onDismiss = onDismiss,
  )
}

@Composable
private fun ReaderSettingsHost(
  viewModel: QabasatViewModel,
  isDarkMode: Boolean,
  backgroundTheme: com.example.data.prefs.BackgroundTheme,
  mushafEdition: MushafEdition,
  effectiveTwoPageMode: Boolean,
  onOpenTajweedLegend: () -> Unit,
  onOpenCredits: () -> Unit,
  onDismiss: () -> Unit,
) {
  val updateState by viewModel.updateState.collectAsStateWithLifecycle()
  SettingsSheet(
    isDarkMode = isDarkMode,
    backgroundTheme = backgroundTheme,
    mushafEdition = mushafEdition,
    effectiveTwoPageMode = effectiveTwoPageMode,
    onToggleDarkMode = { viewModel.toggleDarkMode() },
    onSelectBackgroundTheme = { theme -> viewModel.setBackgroundTheme(theme) },
    onSelectEdition = { edition -> viewModel.setMushafEdition(edition) },
    onSelectPageViewMode = { mode -> viewModel.setPageViewMode(mode) },
    onOpenTajweedLegend = onOpenTajweedLegend,
    onOpenCredits = onOpenCredits,
    onDismiss = onDismiss,
    isCheckingUpdate = updateState is AppUpdateState.Checking,
    onCheckForUpdate = { viewModel.checkForUpdates() },
  )
}

@Composable
private fun AppUpdateDialogHost(
  viewModel: QabasatViewModel,
) {
  val updateState by viewModel.updateState.collectAsStateWithLifecycle()
  val lifecycleOwner = LocalLifecycleOwner.current

  DisposableEffect(lifecycleOwner, updateState is AppUpdateState.NeedsInstallPermission) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME && updateState is AppUpdateState.NeedsInstallPermission) {
        viewModel.retryPendingUpdateInstall()
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
        title = { Text("يوجد تحديث") },
        text = { Text("يتوفر إصدار جديد (Build ${state.remoteBuildNumber}).") },
        confirmButton = {
          TextButton(
            onClick = { viewModel.downloadUpdate(state) },
            modifier = Modifier.testTag("update_download_button"),
          ) {
            Text("تحميل")
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
        onDismissRequest = { viewModel.dismissUpdateDialog() },
        title = { Text("جاري تحميل التحديث") },
        text = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp)
            Text(
              text = state.progressPercent?.let { "جاري التنزيل ($it%)" } ?: "جاري التنزيل...",
            )
          }
        },
        confirmButton = {
          TextButton(onClick = { viewModel.dismissUpdateDialog() }) {
            Text("إخفاء")
          }
        },
      )
    }

    AppUpdateState.NeedsInstallPermission -> {
      AlertDialog(
        onDismissRequest = { viewModel.dismissUpdateDialog() },
        title = { Text("إذن التثبيت") },
        text = { Text("يرجى السماح بالتثبيت من مصدر غير معروف لتثبيت التحديث.") },
        confirmButton = {
          TextButton(onClick = { viewModel.openUnknownSourcesSettings() }) {
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
private fun ReaderBottomAudioHost(
  audioStateFlow: StateFlow<AudioPlaybackUiState>,
  onTogglePlayPause: () -> Unit,
  onSeekTo: (Long) -> Unit,
  onSeekDelta: (Long) -> Unit,
  onNextSurah: () -> Unit,
  onPrevSurah: () -> Unit,
  onCycleSpeed: (Float) -> Unit,
  onOpenReciterPicker: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val audioState by audioStateFlow.collectAsStateWithLifecycle()
  PersistentAudioBar(
    audioState = audioState,
    onTogglePlayPause = onTogglePlayPause,
    onSeekTo = onSeekTo,
    onSeekDelta = onSeekDelta,
    onNextSurah = onNextSurah,
    onPrevSurah = onPrevSurah,
    onCycleSpeed = { onCycleSpeed(audioState.playbackSpeed) },
    onOpenReciterPicker = onOpenReciterPicker,
    modifier = modifier,
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
  onSelectReciter: (VerifiedReciter) -> Unit,
  onPlayFromAyah: (Int, Int) -> Unit,
  onPlayRange: (RecitationRange) -> Unit,
  onClearRange: () -> Unit,
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
  )
}

@Composable
private fun QabasatTopBar(
  mushafEdition: MushafEdition,
  isDarkTheme: Boolean,
  effectiveTwoPageMode: Boolean,
  isCompactWidth: Boolean,
  onToggleEdition: (MushafEdition) -> Unit,
  onSelectPageViewMode: (PageViewMode) -> Unit,
  onOpenIndex: () -> Unit,
  onOpenSettings: () -> Unit,
  onOpenTajweedLegend: () -> Unit,
  onToggleTheme: () -> Unit,
  onOpenCredits: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = MaterialTheme.colorScheme.background,
    tonalElevation = 0.dp,
    modifier = modifier
      .fillMaxWidth()
      .windowInsetsPadding(WindowInsets.statusBars),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpenIndex)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .testTag("app_title_index_button"),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = "فهرس السور والأجزاء والبحث عن الآيات",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
          )
          Spacer(Modifier.width(5.dp))
          Text(
            text = "قبسات",
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = AmiriFontFamily),
            color = MaterialTheme.colorScheme.primary,
          )
        }

        if (!isCompactWidth) {
          EditionSwitcherPill(
            mushafEdition = mushafEdition,
            onToggleEdition = onToggleEdition,
          )
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
              contentDescription = "دليل ألوان التجويد",
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
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          EditionSwitcherPill(
            mushafEdition = mushafEdition,
            onToggleEdition = onToggleEdition,
          )
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
private fun EditionSwitcherPill(
  mushafEdition: MushafEdition,
  onToggleEdition: (MushafEdition) -> Unit,
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
        label = "المدينة",
        selected = mushafEdition == MushafEdition.MADINAH_1421,
        onClick = { onToggleEdition(MushafEdition.MADINAH_1421) },
        testTag = "edition_madinah_button",
      )
      EditionPill(
        label = "التجويد",
        selected = mushafEdition == MushafEdition.TAJWEED_V4,
        onClick = { onToggleEdition(MushafEdition.TAJWEED_V4) },
        testTag = "edition_tajweed_button",
      )
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
