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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.prefs.PageViewMode
import com.example.data.quran.MushafEdition
import com.example.ui.components.CreditsAndIntegrityDialog
import com.example.ui.components.FullScreenQuranIndex
import com.example.ui.components.MushafPageFrame
import com.example.ui.components.PersistentAudioBar
import com.example.ui.components.ReciterPickerSheet
import com.example.ui.components.SettingsSheet
import com.example.ui.components.TajweedLegendSheet
import com.example.ui.components.VerseInsightsSheet
import com.example.ui.theme.AmiriFontFamily
import com.example.viewmodel.QabasatViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

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
  val audioState by viewModel.audioState.collectAsStateWithLifecycle()
  val downloadedKeys by viewModel.downloadedAudioKeys.collectAsStateWithLifecycle()
  val downloadProgress by viewModel.audioDownloadProgress.collectAsStateWithLifecycle()

  val isDarkTheme = uiState.isDarkMode
  val surahsById = remember(uiState.surahs) { uiState.surahs.associateBy { it.id } }

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
    val scope = rememberCoroutineScope()

    val topPadding by animateDpAsState(
      targetValue = if (isChromeVisible) with(density) { topBarHeightPx.toDp() } else 0.dp,
      animationSpec = tween(durationMillis = 200),
      label = "chrome_top_padding",
    )
    val bottomPadding by animateDpAsState(
      targetValue = if (isChromeVisible) with(density) { bottomBarHeightPx.toDp() } else 0.dp,
      animationSpec = tween(durationMillis = 200),
      label = "chrome_bottom_padding",
    )

    // Main Mushaf page area
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxSize()
        .padding(top = topPadding, bottom = bottomPadding),
    ) {
      val availW = maxWidth
      val availH = maxHeight

      if (effectiveTwoPageMode) {
        // Two-Page Side-by-Side Spread (RTL: right = odd page, left = even page)
        val spreadCount = 302
        val targetSpreadIndex = ((uiState.currentPage - 1) / 2).coerceIn(0, spreadCount - 1)
        val spreadPagerState = rememberPagerState(
          initialPage = targetSpreadIndex,
          pageCount = { spreadCount },
        )

        LaunchedEffect(uiState.currentPage) {
          val expectedSpread = ((uiState.currentPage - 1) / 2).coerceIn(0, spreadCount - 1)
          if (spreadPagerState.currentPage != expectedSpread) {
            spreadPagerState.scrollToPage(expectedSpread)
          }
        }

        LaunchedEffect(spreadPagerState) {
          snapshotFlow { spreadPagerState.currentPage }
            .distinctUntilChanged()
            .collect { spreadIdx ->
              val oddPage = (spreadIdx * 2) + 1
              if (uiState.currentPage !in oddPage..(oddPage + 1)) {
                viewModel.onPageChanged(oddPage)
              }
            }
        }

        val toggleChromeIfNotSwiping: () -> Unit = {
          if (!spreadPagerState.isScrollInProgress) {
            isChromeVisible = !isChromeVisible
          }
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          HorizontalPager(
            state = spreadPagerState,
            modifier = Modifier
              .fillMaxSize()
              .testTag("mushaf_spread_pager"),
          ) { spreadIndex ->
            val rightPage = (spreadIndex * 2) + 1
            val leftPage = (rightPage + 1).coerceAtMost(604)
            Row(
              modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 4.dp),
            ) {
              MushafPageFrame(
                pageNumber = rightPage,
                pageState = uiState.pages[rightPage],
                mushafEdition = uiState.mushafEdition,
                backgroundTheme = uiState.backgroundTheme,
                surahsById = surahsById,
                isBookmarked = uiState.bookmarks.contains(rightPage),
                isDarkTheme = isDarkTheme,
                isRecitationPlaying = audioState.isPlaying,
                activePlayingVerseKey = audioState.activePlayingVerseKey,
                selectedVerseKey = uiState.selectedVerseKey,
                onToggleBookmark = { viewModel.toggleBookmark(rightPage) },
                onRetry = { viewModel.retryPage(rightPage) },
                onPageTapped = toggleChromeIfNotSwiping,
                onVerseLongPressed = { key, p, glyphs -> viewModel.onVerseTapped(key, p, glyphs) },
                modifier = Modifier
                  .weight(1f)
                  .fillMaxHeight(),
              )
              if (leftPage != rightPage) {
                MushafPageFrame(
                  pageNumber = leftPage,
                  pageState = uiState.pages[leftPage],
                  mushafEdition = uiState.mushafEdition,
                  backgroundTheme = uiState.backgroundTheme,
                  surahsById = surahsById,
                  isBookmarked = uiState.bookmarks.contains(leftPage),
                  isDarkTheme = isDarkTheme,
                  isRecitationPlaying = audioState.isPlaying,
                  activePlayingVerseKey = audioState.activePlayingVerseKey,
                  selectedVerseKey = uiState.selectedVerseKey,
                  onToggleBookmark = { viewModel.toggleBookmark(leftPage) },
                  onRetry = { viewModel.retryPage(leftPage) },
                  onPageTapped = toggleChromeIfNotSwiping,
                  onVerseLongPressed = { key, p, glyphs -> viewModel.onVerseTapped(key, p, glyphs) },
                  modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                )
              }
            }
          }
        }
      } else {
        // Single-Page RTL Pager (Page 1..604), uniformly scaled (~0.62 aspect ratio), centered, never stretched
        val singlePagerState = rememberPagerState(
          initialPage = (uiState.currentPage - 1).coerceIn(0, 603),
          pageCount = { 604 },
        )

        LaunchedEffect(uiState.currentPage) {
          val targetIdx = (uiState.currentPage - 1).coerceIn(0, 603)
          if (singlePagerState.currentPage != targetIdx) {
            singlePagerState.scrollToPage(targetIdx)
          }
        }

        LaunchedEffect(singlePagerState) {
          snapshotFlow { singlePagerState.currentPage }
            .distinctUntilChanged()
            .collect { idx ->
              viewModel.onPageChanged(idx + 1)
            }
        }

        val toggleChromeIfNotSwiping: () -> Unit = {
          if (!singlePagerState.isScrollInProgress) {
            isChromeVisible = !isChromeVisible
          }
        }

        val (pageWidth, pageHeight) = if (availH > 0.dp && (availW / availH) > MUSHAF_PAGE_ASPECT_RATIO) {
          (availH * MUSHAF_PAGE_ASPECT_RATIO) to availH
        } else {
          availW to (availW / MUSHAF_PAGE_ASPECT_RATIO)
        }

        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
          HorizontalPager(
            state = singlePagerState,
            modifier = Modifier
              .fillMaxSize()
              .testTag("mushaf_single_pager"),
          ) { pageIdx ->
            val pageNum = pageIdx + 1
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              MushafPageFrame(
                pageNumber = pageNum,
                pageState = uiState.pages[pageNum],
                mushafEdition = uiState.mushafEdition,
                backgroundTheme = uiState.backgroundTheme,
                surahsById = surahsById,
                isBookmarked = uiState.bookmarks.contains(pageNum),
                isDarkTheme = isDarkTheme,
                isRecitationPlaying = audioState.isPlaying,
                activePlayingVerseKey = audioState.activePlayingVerseKey,
                selectedVerseKey = uiState.selectedVerseKey,
                onToggleBookmark = { viewModel.toggleBookmark(pageNum) },
                onRetry = { viewModel.retryPage(pageNum) },
                onPageTapped = toggleChromeIfNotSwiping,
                onVerseLongPressed = { key, p, glyphs -> viewModel.onVerseTapped(key, p, glyphs) },
                modifier = Modifier.size(width = pageWidth, height = pageHeight),
              )
            }
          }
        }
      }
    }

    // Top chrome (fades 200ms)
    AnimatedVisibility(
      visible = isChromeVisible,
      enter = fadeIn(animationSpec = tween(durationMillis = 200)),
      exit = fadeOut(animationSpec = tween(durationMillis = 200)),
      modifier = Modifier.align(Alignment.TopCenter),
    ) {
      QabasatTopBar(
        mushafEdition = uiState.mushafEdition,
        isDarkTheme = isDarkTheme,
        effectiveTwoPageMode = effectiveTwoPageMode,
        isCompactWidth = maxWidth < 560.dp,
        onToggleEdition = { edition -> viewModel.setMushafEdition(edition) },
        onSelectPageViewMode = { mode -> viewModel.setPageViewMode(mode) },
        onOpenIndex = { isIndexOpen = true },
        onOpenSettings = { showSettingsSheet = true },
        onOpenTajweedLegend = { showTajweedLegend = true },
        onToggleTheme = { viewModel.toggleDarkMode() },
        onOpenCredits = { showCreditsDialog = true },
        modifier = Modifier.onSizeChanged { topBarHeightPx = it.height },
      )
    }

    // Bottom audio player chrome (fades 200ms)
    AnimatedVisibility(
      visible = isChromeVisible,
      enter = fadeIn(animationSpec = tween(durationMillis = 200)),
      exit = fadeOut(animationSpec = tween(durationMillis = 200)),
      modifier = Modifier.align(Alignment.BottomCenter),
    ) {
      PersistentAudioBar(
        audioState = audioState,
        onTogglePlayPause = { viewModel.playbackController.togglePlayPause() },
        onSeekTo = { pos -> viewModel.playbackController.seekTo(pos) },
        onSeekDelta = { delta -> viewModel.playbackController.seekByDelta(delta) },
        onNextSurah = { viewModel.playbackController.nextSurah() },
        onPrevSurah = { viewModel.playbackController.prevSurah() },
        onCycleSpeed = {
          val curIdx = speeds.indexOfFirst { kotlin.math.abs(it - audioState.playbackSpeed) < 0.05f }
          val nextSpeed = speeds[(curIdx + 1) % speeds.size]
          viewModel.playbackController.setSpeed(nextSpeed)
        },
        onOpenReciterPicker = { showReciterSheet = true },
        modifier = Modifier.onSizeChanged { bottomBarHeightPx = it.height },
      )
    }

    if (isIndexOpen) {
      FullScreenQuranIndex(
        surahs = uiState.surahs,
        juzs = uiState.juzs,
        bookmarks = uiState.bookmarks,
        currentPage = uiState.currentPage,
        activeReciter = audioState.currentReciter,
        downloadedKeys = downloadedKeys,
        downloadProgress = downloadProgress,
        verseSearchResults = uiState.verseSearchResults,
        verseSearchLoading = uiState.verseSearchLoading,
        verseSearchError = uiState.verseSearchError,
        metadataError = uiState.metadataError,
        hasActiveReaderPage = hasSelectedSurah,
        onRetryMetadata = { viewModel.retryBootstrap() },
        onSearchVerses = { query -> viewModel.searchVerses(query) },
        onJumpToPage = { page ->
          hasSelectedSurah = true
          isIndexOpen = false
          scope.launch { viewModel.jumpToPage(page) }
        },
        onJumpToVerse = { key, page ->
          hasSelectedSurah = true
          isIndexOpen = false
          scope.launch { viewModel.jumpToVerse(key, page) }
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
        },
        onOpenRangePicker = { showReciterSheet = true },
      )
    }

    if (showReciterSheet) {
      ReciterPickerSheet(
        primaryReciters = uiState.primaryReciters,
        additionalReciters = uiState.additionalReciters,
        activeReciter = audioState.currentReciter,
        currentSurahId = audioState.currentSurahId,
        activeRange = audioState.activeRange,
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
      SettingsSheet(
        isDarkMode = isDarkTheme,
        backgroundTheme = uiState.backgroundTheme,
        mushafEdition = uiState.mushafEdition,
        effectiveTwoPageMode = effectiveTwoPageMode,
        onToggleDarkMode = { viewModel.toggleDarkMode() },
        onSelectBackgroundTheme = { theme -> viewModel.setBackgroundTheme(theme) },
        onSelectEdition = { edition -> viewModel.setMushafEdition(edition) },
        onSelectPageViewMode = { mode -> viewModel.setPageViewMode(mode) },
        onOpenTajweedLegend = { showTajweedLegend = true },
        onOpenCredits = { showCreditsDialog = true },
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

    VerseInsightsSheet(
      state = uiState.verseInsights,
      surahsById = surahsById,
      onPlaySingleAyah = { surahId, ayahNum -> viewModel.playSingleAyah(surahId, ayahNum) },
      onPlayFromAyah = { surahId, ayahNum -> viewModel.playFromAyah(surahId, ayahNum) },
      onPlayAyahRange = { range -> viewModel.playAyahRange(range) },
      onOpenRangePicker = { showReciterSheet = true },
      onDismiss = { viewModel.dismissVerseInsights() },
    )
  }
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
    tonalElevation = 1.dp,
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
