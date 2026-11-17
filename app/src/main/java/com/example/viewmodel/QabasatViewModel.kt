package com.example.viewmodel

import android.app.Application
import android.graphics.Typeface
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.audio.AudioCacheManager
import com.example.data.audio.AudioRepository
import com.example.data.audio.RecitationRange
import com.example.data.audio.VerifiedReciter
import com.example.data.integrity.AssetHashStatus
import com.example.data.integrity.VerifiedDiskCache
import com.example.data.prefs.BackgroundTheme
import com.example.data.prefs.PageViewMode
import com.example.data.prefs.PersistedReaderState
import com.example.data.prefs.ReaderPreferences
import com.example.data.quran.CreditSourceInfo
import com.example.data.quran.JuzMeta
import com.example.data.quran.MushafEdition
import com.example.data.quran.MushafLine
import com.example.data.quran.MushafPageData
import com.example.data.quran.PageFontManager
import com.example.data.quran.QuranRepository
import com.example.data.quran.SurahMeta
import com.example.data.quran.TajweedRuleInfo
import com.example.data.quran.VerseInsightsData
import com.example.data.quran.VerseInsightsRepository
import com.example.data.quran.VerseSearchHit
import com.example.player.AudioMiniUiState
import com.example.player.AudioPlaybackUiState
import com.example.player.PlaybackController
import com.example.update.AppUpdateManager
import com.example.update.AppUpdateState
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

@Immutable
sealed interface PageLoadState {
  @Immutable
  data object Loading : PageLoadState

  @Immutable
  data class Ready(
    val pageData: MushafPageData,
    val pageTypeface: Typeface?,
    val basmalahTypeface: Typeface?,
    val usesTagFallbackTajweed: Boolean,
  ) : PageLoadState

  @Immutable
  data class Error(val message: String) : PageLoadState
}

@Immutable
sealed interface VerseInsightsUiState {
  @Immutable
  data object Hidden : VerseInsightsUiState

  @Immutable
  data class Loading(val verseKey: String, val surahName: String, val ayahNumber: Int) : VerseInsightsUiState

  @Immutable
  data class Ready(
    val insights: VerseInsightsData,
    val surahName: String,
    val pageTypeface: Typeface?,
  ) : VerseInsightsUiState

  @Immutable
  data class Error(val verseKey: String, val message: String) : VerseInsightsUiState
}

data class ReaderUiState(
  val currentPage: Int = 1,
  val mushafEdition: MushafEdition = MushafEdition.MADINAH_1421,
  val isDarkMode: Boolean = false,
  val backgroundTheme: BackgroundTheme = BackgroundTheme.WHITE,
  val pageViewMode: PageViewMode = PageViewMode.AUTO,
  val bookmarks: List<Int> = emptyList(),
  val surahs: List<SurahMeta> = emptyList(),
  val juzs: List<JuzMeta> = emptyList(),
  val juzsLoading: Boolean = false,
  val primaryReciters: List<VerifiedReciter> = emptyList(),
  val additionalReciters: List<VerifiedReciter> = emptyList(),
  val recitersLoading: Boolean = true,
  val recitersError: String? = null,
  val tajweedRules: List<TajweedRuleInfo> = emptyList(),
  val credits: List<CreditSourceInfo> = emptyList(),
  val assetHashes: List<AssetHashStatus> = emptyList(),
  val selectedVerseKey: String? = null,
  val verseSearchResults: List<VerseSearchHit> = emptyList(),
  val verseSearchLoading: Boolean = false,
  val verseSearchError: String? = null,
  val metadataError: String? = null,
)

class QabasatViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val PAGE_CACHE_LIMIT = 6
  }

  private val diskCache = VerifiedDiskCache(application)
  private val fontManager = PageFontManager(diskCache)
  private val quranRepository = QuranRepository(application, diskCache, fontManager)
  private val verseInsightsRepository = VerseInsightsRepository(diskCache)
  private val audioRepository = AudioRepository(diskCache)
  private val audioCacheManager = AudioCacheManager(application)
  private val preferences = ReaderPreferences(application)

  val playbackController = PlaybackController(
    context = application,
    audioRepository = audioRepository,
    audioCacheManager = audioCacheManager,
    preferences = preferences,
    scope = viewModelScope,
  )

  private val appUpdateManager = AppUpdateManager(
    context = application,
    scope = viewModelScope,
  )

  private val _uiState = MutableStateFlow(ReaderUiState())
  val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

  // Isolated page states so adjacent page loading & LRU eviction never recompose root/chrome/index
  private val _pagesState = MutableStateFlow<Map<Int, PageLoadState>>(emptyMap())
  val pagesState: StateFlow<Map<Int, PageLoadState>> = _pagesState.asStateFlow()

  // Isolated verse insights state so opening/loading tafsir never recomposes the Mushaf pager
  private val _verseInsightsState = MutableStateFlow<VerseInsightsUiState>(VerseInsightsUiState.Hidden)
  val verseInsightsState: StateFlow<VerseInsightsUiState> = _verseInsightsState.asStateFlow()

  val themeStateFlow: StateFlow<Pair<BackgroundTheme, Boolean>> = _uiState
    .map { it.backgroundTheme to it.isDarkMode }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, BackgroundTheme.WHITE to false)

  val selectedVerseKeyFlow: StateFlow<String?> = _uiState
    .map { it.selectedVerseKey }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  val updateState: StateFlow<AppUpdateState> = appUpdateManager.state
  val audioState: StateFlow<AudioPlaybackUiState> = playbackController.uiState

  // Isolated audio slices so frequent positionMs ticks never recompose Mushaf pages, Index, or MiniBar
  val activePlayingVerseKeyFlow: StateFlow<String?> = playbackController.uiState
    .map { it.activePlayingVerseKey }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  val isAudioPlayingFlow: StateFlow<Boolean> = playbackController.uiState
    .map { it.isPlaying }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val isAudioSessionActiveFlow: StateFlow<Boolean> = playbackController.uiState
    .map { it.isPlaying || it.isBuffering || it.durationMs > 0L || it.activePlayingVerseKey != null }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val miniAudioStateFlow: StateFlow<AudioMiniUiState> = playbackController.uiState
    .map { s ->
      AudioMiniUiState(
        isActive = s.isPlaying || s.isBuffering || s.durationMs > 0L || s.activePlayingVerseKey != null,
        isPlaying = s.isPlaying,
        isBuffering = s.isBuffering,
        currentSurahId = s.currentSurahId,
        currentSurahName = s.currentSurahName,
        reciterName = s.currentReciter?.displayName ?: "اختر القارئ",
      )
    }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, AudioMiniUiState())

  val activeReciterFlow: StateFlow<VerifiedReciter?> = playbackController.uiState
    .map { it.currentReciter }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)

  val downloadedAudioKeys: StateFlow<Set<String>> = audioCacheManager.downloadedKeys
  val audioDownloadProgress: StateFlow<Map<String, Float>> = audioCacheManager.downloadProgress

  private val pageJobs = mutableMapOf<Int, Job>()
  private val pageLruOrder = ArrayDeque<Int>()
  private var searchJob: Job? = null
  private var hasReaderActivated: Boolean = false
  private var juzsLoaded: Boolean = false
  private var juzsJob: Job? = null
  private var tajweedLegendLoaded: Boolean = false
  private var tajweedLegendJob: Job? = null
  private var creditsLoaded: Boolean = false
  private var creditsJob: Job? = null

  init {
    bootstrap()
  }

  private fun ensurePlaybackConnected() {
    playbackController.connect(
      onSurahEnded = { playbackController.nextSurah() },
      onActiveVerseChanged = { verseKey ->
        if (verseKey != null) {
          followPlayingVersePage(verseKey)
        }
      },
    )
  }

  private fun followPlayingVersePage(verseKey: String) {
    val pagesMap = _pagesState.value
    val currentPageNum = _uiState.value.currentPage
    val loadedCurrent = pagesMap[currentPageNum]
    if (loadedCurrent is PageLoadState.Ready) {
      val onCurrentPage = loadedCurrent.pageData.lines.any { line ->
        line is MushafLine.AyahWordsLine &&
          line.words.any { it.verseKey == verseKey }
      }
      if (onCurrentPage) return
    }
    val cachedTargetPage = pagesMap.entries.firstOrNull { (_, state) ->
      state is PageLoadState.Ready && state.pageData.lines.any { line ->
        line is MushafLine.AyahWordsLine &&
          line.words.any { it.verseKey == verseKey }
      }
    }?.key
    if (cachedTargetPage != null && cachedTargetPage != currentPageNum) {
      jumpToPage(cachedTargetPage)
      return
    }
    viewModelScope.launch {
      val page = quranRepository.getVersePageNumber(verseKey)
      if (page != null && page != _uiState.value.currentPage) {
        jumpToPage(page)
      }
    }
  }

  private fun bootstrap() {
    viewModelScope.launch {
      val saved: PersistedReaderState = preferences.stateFlow.first()
      _uiState.update {
        it.copy(
          currentPage = saved.lastPage,
          mushafEdition = saved.mushafEdition,
          isDarkMode = saved.isDarkMode,
          backgroundTheme = saved.backgroundTheme,
          pageViewMode = saved.pageViewMode,
          bookmarks = saved.bookmarks,
        )
      }

      launch {
        preferences.stateFlow.collect { pref ->
          val prevDark = _uiState.value.isDarkMode
          val darkChanged = pref.isDarkMode != prevDark
          val activeEdition = _uiState.value.mushafEdition
          val activePage = _uiState.value.currentPage
          if (darkChanged && activeEdition == MushafEdition.TAJWEED_V4) {
            clearCachedPages()
            _pagesState.value = emptyMap()
          }
          _uiState.update {
            it.copy(
              bookmarks = pref.bookmarks,
              isDarkMode = pref.isDarkMode,
              backgroundTheme = pref.backgroundTheme,
              pageViewMode = pref.pageViewMode,
            )
          }
          if (darkChanged && activeEdition == MushafEdition.TAJWEED_V4 && hasReaderActivated) {
            loadPageWindow(activePage, MushafEdition.TAJWEED_V4, useWhiteInk = pref.isDarkMode)
          }
        }
      }

      // First screen only needs Surah index; defer playback binding, downloaded keys scan & reciters until after first draw.
      val surahsRes = quranRepository.getSurahs()
      val surahs = surahsRes.getOrDefault(emptyList())

      _uiState.update {
        it.copy(
          surahs = surahs,
          metadataError = surahsRes.exceptionOrNull()?.localizedMessage,
        )
      }

      // Yield so Compose can commit the first frame of the Surah index before background init runs.
      yield()

      if (surahs.isNotEmpty()) {
        playbackController.updateSurahNames(surahs.associate { it.id to it.nameArabic })
      }
      ensurePlaybackConnected()
      launch { audioCacheManager.refreshDownloadedKeys() }
      loadReciters(saved)
    }
  }

  fun ensureCurrentPageLoaded() {
    hasReaderActivated = true
    val state = _uiState.value
    loadPageWindow(state.currentPage, state.mushafEdition, state.isDarkMode)
  }

  fun ensureJuzsLoaded() {
    if (juzsLoaded || juzsJob?.isActive == true) return
    juzsJob = viewModelScope.launch {
      _uiState.update { it.copy(juzsLoading = true) }
      val juzsRes = quranRepository.getJuzs()
      val juzs = juzsRes.getOrDefault(emptyList())
      if (juzs.isNotEmpty()) {
        juzsLoaded = true
      }
      _uiState.update { it.copy(juzs = juzs, juzsLoading = false) }
    }
  }

  fun ensureTajweedLegendLoaded() {
    if (tajweedLegendLoaded || tajweedLegendJob?.isActive == true) return
    tajweedLegendJob = viewModelScope.launch {
      val legend = quranRepository.loadTajweedLegend()
      if (legend.isNotEmpty()) {
        tajweedLegendLoaded = true
      }
      _uiState.update { it.copy(tajweedRules = legend) }
    }
  }

  fun ensureCreditsLoaded() {
    if (creditsLoaded || creditsJob?.isActive == true) return
    creditsJob = viewModelScope.launch {
      coroutineScope {
        val hashesDeferred = async { quranRepository.verifyBundledAssets() }
        val creditsDeferred = async { quranRepository.loadCredits() }
        val hashes = hashesDeferred.await()
        val credits = creditsDeferred.await()
        creditsLoaded = true
        _uiState.update {
          it.copy(
            assetHashes = hashes,
            credits = credits,
          )
        }
      }
    }
  }

  private fun loadReciters(saved: PersistedReaderState) {
    viewModelScope.launch {
      _uiState.update { it.copy(recitersLoading = true, recitersError = null) }
      val res = audioRepository.getVerifiedRecitersCatalog()
      res.onSuccess { (primary, additional) ->
        _uiState.update {
          it.copy(
            primaryReciters = primary,
            additionalReciters = additional,
            recitersLoading = false,
          )
        }
        val allPlayable = (primary + additional).filter { it.isPlayable }
        val restored = saved.selectedReciterKey?.let { key ->
          allPlayable.firstOrNull { it.key == key }
        } ?: allPlayable.firstOrNull()
        if (restored != null) {
          playbackController.restoreSelectionWithoutPlaying(
            reciter = restored,
            surahId = saved.lastSurahId,
            positionMs = saved.lastPositionMs,
            speed = saved.playbackSpeed,
          )
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            recitersLoading = false,
            recitersError = err.localizedMessage ?: "تعذر تحميل قائمة القراء",
          )
        }
      }
    }
  }

  fun retryBootstrap() {
    bootstrap()
  }

  fun onPageChanged(page: Int) {
    val clamped = page.coerceIn(1, 604)
    hasReaderActivated = true
    if (clamped == _uiState.value.currentPage && _pagesState.value[clamped] is PageLoadState.Ready) {
      return
    }
    _uiState.update { it.copy(currentPage = clamped) }
    viewModelScope.launch { preferences.saveLastPage(clamped) }
    loadPageWindow(clamped, _uiState.value.mushafEdition, _uiState.value.isDarkMode)
  }

  fun jumpToPage(page: Int) {
    val clamped = page.coerceIn(1, 604)
    hasReaderActivated = true
    _uiState.update { it.copy(currentPage = clamped) }
    viewModelScope.launch { preferences.saveLastPage(clamped) }
    loadPageWindow(clamped, _uiState.value.mushafEdition, _uiState.value.isDarkMode)
  }

  fun jumpToVerse(verseKey: String, pageNumber: Int) {
    _uiState.update { it.copy(selectedVerseKey = verseKey) }
    jumpToPage(pageNumber)
  }

  fun setMushafEdition(edition: MushafEdition) {
    if (edition == _uiState.value.mushafEdition) return
    clearCachedPages()
    _pagesState.value = emptyMap()
    _uiState.update { it.copy(mushafEdition = edition) }
    viewModelScope.launch { preferences.setMushafEdition(edition) }
    if (hasReaderActivated) {
      loadPageWindow(_uiState.value.currentPage, edition, _uiState.value.isDarkMode)
    }
  }

  fun toggleDarkMode() {
    val nextDark = !_uiState.value.isDarkMode
    val nextBg = if (nextDark) BackgroundTheme.BLACK else BackgroundTheme.WHITE
    applyThemeChange(nextDark, nextBg)
    viewModelScope.launch { preferences.setDarkMode(nextDark) }
  }

  fun setBackgroundTheme(theme: BackgroundTheme) {
    val nextDark = theme == BackgroundTheme.BLACK
    applyThemeChange(nextDark, theme)
    viewModelScope.launch { preferences.setBackgroundTheme(theme) }
  }

  fun setPageViewMode(mode: PageViewMode) {
    _uiState.update { it.copy(pageViewMode = mode) }
    viewModelScope.launch { preferences.setPageViewMode(mode) }
  }

  private fun applyThemeChange(nextDark: Boolean, nextBg: BackgroundTheme) {
    val state = _uiState.value
    val darkChanged = state.isDarkMode != nextDark
    if (darkChanged && state.mushafEdition == MushafEdition.TAJWEED_V4) {
      clearCachedPages()
      _pagesState.value = emptyMap()
    }
    _uiState.update {
      it.copy(
        isDarkMode = nextDark,
        backgroundTheme = nextBg,
      )
    }
    if (darkChanged && state.mushafEdition == MushafEdition.TAJWEED_V4 && hasReaderActivated) {
      loadPageWindow(state.currentPage, MushafEdition.TAJWEED_V4, useWhiteInk = nextDark)
    }
  }

  fun toggleBookmark(page: Int) {
    viewModelScope.launch { preferences.toggleBookmark(page) }
  }

  fun removeBookmark(page: Int) {
    viewModelScope.launch { preferences.removeBookmark(page) }
  }

  fun retryPage(page: Int) {
    hasReaderActivated = true
    loadSinglePage(page, _uiState.value.mushafEdition, _uiState.value.isDarkMode, forceReload = true)
  }

  fun searchVerses(query: String) {
    searchJob?.cancel()
    if (query.trim().length < 2) {
      _uiState.update {
        it.copy(verseSearchResults = emptyList(), verseSearchLoading = false, verseSearchError = null)
      }
      return
    }
    searchJob = viewModelScope.launch {
      _uiState.update { it.copy(verseSearchLoading = true, verseSearchError = null) }
      val res = quranRepository.searchVerses(query)
      res.onSuccess { hits ->
        _uiState.update { it.copy(verseSearchResults = hits, verseSearchLoading = false) }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            verseSearchResults = emptyList(),
            verseSearchLoading = false,
            verseSearchError = err.localizedMessage ?: "تعذر البحث",
          )
        }
      }
    }
  }

  private fun clearCachedPages() {
    pageJobs.values.forEach { it.cancel() }
    pageJobs.clear()
    pageLruOrder.clear()
  }

  private fun retainPageWindowLru(orderedTargets: List<Int>): Set<Int> {
    for (p in orderedTargets.asReversed()) {
      pageLruOrder.remove(p)
      pageLruOrder.addLast(p)
    }
    while (pageLruOrder.size > PAGE_CACHE_LIMIT) {
      val evicted = pageLruOrder.removeFirst()
      pageJobs.remove(evicted)?.cancel()
    }
    return pageLruOrder.toSet()
  }

  private fun loadPageWindow(
    centerPage: Int,
    edition: MushafEdition,
    useWhiteInk: Boolean = _uiState.value.isDarkMode,
  ) {
    val activeTargets = listOfNotNull(
      centerPage.takeIf { it in 1..604 },
      (centerPage + 1).takeIf { it in 1..604 },
      (centerPage - 1).takeIf { it in 1..604 },
      if (centerPage % 2 == 1 && (centerPage + 2) in 1..604) centerPage + 2 else null,
    )
    val retainedKeys = retainPageWindowLru(activeTargets)
    _pagesState.update { currentMap ->
      val pruned = currentMap.filterKeys { it in retainedKeys }
      if (pruned.size == currentMap.size) currentMap else pruned
    }
    for (p in activeTargets) {
      loadSinglePage(p, edition, useWhiteInk = useWhiteInk, forceReload = false)
    }
  }

  private fun loadSinglePage(
    page: Int,
    edition: MushafEdition,
    useWhiteInk: Boolean,
    forceReload: Boolean,
  ) {
    if (page !in 1..604) return
    val existing = _pagesState.value[page]
    if (!forceReload && existing is PageLoadState.Ready) return
    if (!forceReload && existing is PageLoadState.Loading && pageJobs[page]?.isActive == true) return

    pageJobs[page]?.cancel()
    pageJobs[page] = viewModelScope.launch {
      _pagesState.update { it + (page to PageLoadState.Loading) }

      val needsTagFallbackTajweed =
        edition == MushafEdition.TAJWEED_V4 && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

      val pageRes = quranRepository.getPage(page, includeTajweedFallback = needsTagFallbackTajweed)
      if (pageRes.isFailure) {
        _pagesState.update {
          it + (page to PageLoadState.Error(
            pageRes.exceptionOrNull()?.localizedMessage ?: "تعذر تحميل الصفحة $page",
          ))
        }
        return@launch
      }

      val pageData = pageRes.getOrThrow()
      val fontEdition = if (needsTagFallbackTajweed) MushafEdition.MADINAH_1421 else edition
      val (pageTypeface, basmalahTypeface) = coroutineScope {
        val pageFontDeferred = async { fontManager.loadPageFont(page, fontEdition, useWhiteInk = useWhiteInk) }
        val basmalahFontDeferred = async { fontManager.loadBasmalahFont(fontEdition, useWhiteInk = useWhiteInk) }
        pageFontDeferred.await() to basmalahFontDeferred.await()
      }

      if (_uiState.value.mushafEdition != edition) return@launch
      if (edition == MushafEdition.TAJWEED_V4 && _uiState.value.isDarkMode != useWhiteInk) return@launch
      if (!pageLruOrder.contains(page)) return@launch

      if (pageTypeface == null && !needsTagFallbackTajweed) {
        _pagesState.update {
          it + (page to PageLoadState.Error("تعذر تحميل خط الصفحة العثماني"))
        }
        return@launch
      }

      _pagesState.update {
        it + (page to PageLoadState.Ready(
          pageData = pageData,
          pageTypeface = pageTypeface,
          basmalahTypeface = basmalahTypeface,
          usesTagFallbackTajweed = needsTagFallbackTajweed,
        ))
      }
    }
  }

  fun onVerseTapped(verseKey: String, pageNumber: Int, verseCodeV2Line: String) {
    val parts = verseKey.split(":")
    val surahId = parts.getOrNull(0)?.toIntOrNull() ?: 1
    val ayahNumber = parts.getOrNull(1)?.toIntOrNull() ?: 1
    val surahName = _uiState.value.surahs.firstOrNull { it.id == surahId }?.nameArabic.orEmpty()
    val edition = _uiState.value.mushafEdition
    val useWhiteInk = _uiState.value.isDarkMode

    _uiState.update { it.copy(selectedVerseKey = verseKey) }
    _verseInsightsState.value = VerseInsightsUiState.Loading(verseKey, surahName, ayahNumber)

    viewModelScope.launch {
      val pageFont = fontManager.loadPageFont(pageNumber, edition, useWhiteInk = useWhiteInk)
        ?: fontManager.loadPageFont(pageNumber, MushafEdition.MADINAH_1421, useWhiteInk = false)
      val res = verseInsightsRepository.getVerseInsights(
        verseKey = verseKey,
        pageNumber = pageNumber,
        codeV2Line = verseCodeV2Line,
      )
      res.onSuccess { insights ->
        _verseInsightsState.value = VerseInsightsUiState.Ready(
          insights = insights,
          surahName = surahName,
          pageTypeface = pageFont,
        )
      }.onFailure { err ->
        _verseInsightsState.value = VerseInsightsUiState.Error(
          verseKey = verseKey,
          message = err.localizedMessage ?: "تعذر تحميل التفسير والترجمات",
        )
      }
    }
  }

  fun dismissVerseInsights() {
    _uiState.update { it.copy(selectedVerseKey = null) }
    _verseInsightsState.value = VerseInsightsUiState.Hidden
  }

  fun selectReciter(reciter: VerifiedReciter) {
    if (!reciter.isPlayable) return
    ensurePlaybackConnected()
    val currentSurah = audioState.value.currentSurahId
    val surahToPlay = if (reciter.availableSurahs.contains(currentSurah)) {
      currentSurah
    } else {
      reciter.availableSurahs.minOrNull() ?: 1
    }
    playbackController.playSurah(reciter, surahToPlay, 0L, autoPlay = audioState.value.isPlaying)
  }

  fun playSurah(surahId: Int) {
    ensurePlaybackConnected()
    val reciter = audioState.value.currentReciter
      ?: (_uiState.value.primaryReciters + _uiState.value.additionalReciters).firstOrNull { it.isPlayable }
      ?: return
    playbackController.playSurah(reciter, surahId, 0L, autoPlay = true, clearActiveRange = true)
  }

  fun playAyahRange(range: RecitationRange) {
    ensurePlaybackConnected()
    val startSurah = minOf(range.startSurah, range.endSurah)
    val startAyah = if (range.startSurah <= range.endSurah) {
      if (range.startSurah == range.endSurah) minOf(range.startAyah, range.endAyah) else range.startAyah
    } else {
      range.endAyah
    }
    followPlayingVersePage("$startSurah:$startAyah")
    playbackController.playRange(range)
  }

  fun playSingleAyah(surahId: Int, ayahNumber: Int) {
    ensurePlaybackConnected()
    followPlayingVersePage("$surahId:$ayahNumber")
    playbackController.playFromAyah(surahId, ayahNumber, endAyahNumber = ayahNumber)
  }

  fun playFromAyah(surahId: Int, ayahNumber: Int) {
    ensurePlaybackConnected()
    followPlayingVersePage("$surahId:$ayahNumber")
    playbackController.playFromAyah(surahId, ayahNumber, endAyahNumber = null)
  }

  fun clearAyahRange() {
    playbackController.clearRange()
  }

  fun downloadSurahAudio(surahId: Int) {
    val reciter = audioState.value.currentReciter ?: return
    val url = audioRepository.buildSurahMp3Url(reciter, surahId) ?: return
    viewModelScope.launch {
      audioCacheManager.downloadSurah(reciter.key, surahId, url)
    }
  }

  fun deleteDownloadedSurahAudio(surahId: Int) {
    val reciter = audioState.value.currentReciter ?: return
    viewModelScope.launch {
      audioCacheManager.deleteCachedSurah(reciter.key, surahId)
    }
  }

  fun checkForUpdates() {
    appUpdateManager.checkForUpdates()
  }

  fun downloadUpdate(update: AppUpdateState.UpdateAvailable) {
    appUpdateManager.startDownloadAndInstall(update)
  }

  fun openUnknownSourcesSettings() {
    appUpdateManager.openUnknownSourcesSettings()
  }

  fun retryPendingUpdateInstall() {
    appUpdateManager.retryPendingInstallIfPermitted()
  }

  fun dismissUpdateDialog() {
    appUpdateManager.dismiss()
  }

  override fun onCleared() {
    appUpdateManager.release()
    playbackController.release()
    super.onCleared()
  }
}
