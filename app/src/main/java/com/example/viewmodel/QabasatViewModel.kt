package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.audio.AudioBatchUiSummary
import com.example.data.audio.AudioDownloadManager
import com.example.data.audio.AudioRepository
import com.example.data.audio.RecitationRange
import com.example.data.audio.ReciterStorageSummary
import com.example.data.audio.SurahTransferState
import com.example.data.audio.VerifiedReciter
import com.example.data.integrity.AssetHashStatus
import com.example.data.integrity.VerifiedDiskCache
import com.example.data.pdf.ColorsGuideParser
import com.example.data.pdf.PdfMushafEntry
import com.example.data.pdf.PdfMushafRepository
import com.example.data.pdf.PdfMushafTransferState
import com.example.data.pdf.PdfRendererBridge
import com.example.data.pdf.RiwayaColorGuideSection
import com.example.data.prefs.BackgroundTheme
import com.example.data.prefs.PageViewMode
import com.example.data.prefs.PersistedReaderState
import com.example.data.prefs.ReaderPreferences
import com.example.data.quran.CreditSourceInfo
import com.example.data.quran.JuzMeta
import com.example.data.quran.MushafDownloadManager
import com.example.data.quran.MushafEdition
import com.example.data.quran.MushafItem
import com.example.data.quran.MushafOfflineSetupManager
import com.example.data.quran.MushafRegistry
import com.example.data.quran.MushafSetupState
import com.example.data.quran.PageFontManager
import com.example.data.quran.QuranRepository
import com.example.data.quran.QuranWordSearchNormalizer
import com.example.data.quran.QuranWordSearchResult
import com.example.data.quran.SurahMeta
import com.example.data.quran.TajweedRuleInfo
import com.example.data.quran.VerseInsightsData
import com.example.data.quran.VerseInsightsRepository
import com.example.player.AudioMiniUiState
import com.example.player.AudioPlaybackUiState
import com.example.player.PlaybackController
import com.example.ui.components.MushafPageBitmapRenderer
import com.example.ui.components.PageRenderSpec
import com.example.ui.components.RenderedMushafPage
import com.example.update.AppUpdateManager
import com.example.update.AppUpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    val renderedPage: RenderedMushafPage,
    val effectiveDark: Boolean,
    val edition: MushafEdition,
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
  val colorsGuideSections: List<RiwayaColorGuideSection> = emptyList(),
  val colorsGuideLoading: Boolean = false,
  val colorsGuideError: String? = null,
  val credits: List<CreditSourceInfo> = emptyList(),
  val assetHashes: List<AssetHashStatus> = emptyList(),
  val selectedVerseKey: String? = null,
  val metadataError: String? = null,
  val activeMushafIsPdf: Boolean = false,
  val activePdfMushafId: String? = null,
  val pdfMushafs: List<PdfMushafEntry> = emptyList(),
  val pdfManifestLoading: Boolean = false,
  val pdfManifestError: String? = null,
)

@Immutable
data class QuranWordSearchUiState(
  val query: String = "",
  val isSubstringMode: Boolean = false,
  val isFullResultsMode: Boolean = false,
  val isLoading: Boolean = false,
  val isIndexWarming: Boolean = false,
  val errorMessage: String? = null,
  val result: QuranWordSearchResult? = null,
  val collapsedSurahIds: Set<Int> = emptySet(),
  val visibleResultLimit: Int = 40,
)

@Immutable
data class DownloadableRecitersUiState(
  val reciters: List<VerifiedReciter> = emptyList(),
  val isLoading: Boolean = false,
  val errorMessage: String? = null,
)

class QabasatViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val SINGLE_PAGE_CACHE_LIMIT = 8
    private const val TWO_PAGE_CACHE_LIMIT = 12
  }

  private val diskCache = VerifiedDiskCache(application)
  private val fontManager = PageFontManager(diskCache)
  private val quranRepository = QuranRepository(application, diskCache, fontManager)
  private val verseInsightsRepository = VerseInsightsRepository(diskCache)
  private val audioRepository = AudioRepository(application, diskCache)
  private val audioDownloadManager = AudioDownloadManager.getInstance(application)
  private val audioCacheManager = audioDownloadManager.cacheManager
  private val preferences = ReaderPreferences(application)
  private val pageBitmapRenderer = MushafPageBitmapRenderer(application)
  private val pdfMushafRepository = PdfMushafRepository(application, viewModelScope)
  private val pdfRendererBridge = PdfRendererBridge()

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
  private val setupManager = MushafOfflineSetupManager(
    diskCache = diskCache,
    quranRepository = quranRepository,
    fontManager = fontManager,
    scope = viewModelScope,
  )

  val mushafDownloadManager = MushafDownloadManager(
    diskCache = diskCache,
    quranRepository = quranRepository,
    fontManager = fontManager,
    scope = viewModelScope,
  )

  val downloadedMushafIdsState: StateFlow<Set<String>> = preferences.stateFlow
    .map { it.downloadedMushafIds }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

  val activeMushafIdState: StateFlow<String> = preferences.stateFlow
    .map { it.activeMushafId }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, "madinah_1421")

  val betaChannelEnabledState: StateFlow<Boolean> = preferences.stateFlow
    .map { it.betaChannelEnabled }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val betaChannelUnlockedState: StateFlow<Boolean> = preferences.stateFlow
    .map { it.betaChannelUnlocked || it.betaChannelEnabled }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val activeMushafIsPdfState: StateFlow<Boolean> = preferences.stateFlow
    .map { it.activeMushafIsPdf }
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.Eagerly, false)

  val pdfTransferStates: StateFlow<Map<String, PdfMushafTransferState>> = pdfMushafRepository.transferStates
  val offlinePdfIds: StateFlow<Set<String>> = pdfMushafRepository.offlineDownloadedIds
  val localOrCachedPdfIds: StateFlow<Set<String>> = pdfMushafRepository.localOrCachedIds

  val setupState: StateFlow<MushafSetupState> = setupManager.state

  private val _uiState = MutableStateFlow(ReaderUiState())
  val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

  // Isolated page states so adjacent page loading & LRU eviction never recompose root/chrome/index
  private val _pagesState = MutableStateFlow<Map<Int, PageLoadState>>(emptyMap())
  val pagesState: StateFlow<Map<Int, PageLoadState>> = _pagesState.asStateFlow()

  // Isolated verse insights state so opening/loading tafsir never recomposes the Mushaf pager
  private val _verseInsightsState = MutableStateFlow<VerseInsightsUiState>(VerseInsightsUiState.Hidden)
  val verseInsightsState: StateFlow<VerseInsightsUiState> = _verseInsightsState.asStateFlow()

  // Isolated word search state so typing/scrolling search results never recomposes reader/chrome
  private val _wordSearchState = MutableStateFlow(QuranWordSearchUiState())
  val wordSearchState: StateFlow<QuranWordSearchUiState> = _wordSearchState.asStateFlow()

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
  val audioTransferStates: StateFlow<Map<String, SurahTransferState>> = audioDownloadManager.transferStates
  val audioReciterSummaries: StateFlow<Map<String, ReciterStorageSummary>> = audioDownloadManager.reciterSummaries
  val audioBatchSummary: StateFlow<AudioBatchUiSummary> = audioDownloadManager.batchSummary

  private val _downloadableRecitersState = MutableStateFlow(DownloadableRecitersUiState())
  val downloadableRecitersState: StateFlow<DownloadableRecitersUiState> = _downloadableRecitersState.asStateFlow()
  private var downloadableRecitersJob: Job? = null

  private val pageJobs = mutableMapOf<Int, Job>()
  private val pageJobVisiblePriority = mutableMapOf<Int, Boolean>()
  private val pageLruOrder = ArrayDeque<Int>()
  private var prefetchJob: Job? = null
  @Volatile
  private var activeRenderSpec = PageRenderSpec()
  @Volatile
  private var cachedSurahsById: Map<Int, SurahMeta> = emptyMap()
  private var measuredWidthPx: Int = 0
  private var measuredHeightPx: Int = 0
  private var measuredDensity: Float = 0f
  private var measuredFontScale: Float = 1f
  private var measuredIsTwoPageMode: Boolean = false
  private val activePdfFileKeys = java.util.concurrent.ConcurrentHashMap<String, String>()
  private var searchJob: Job? = null
  private var indexWarmupJob: Job? = null
  private var hasReaderActivated: Boolean = false
  private var juzsLoaded: Boolean = false
  private var juzsJob: Job? = null
  private var tajweedLegendLoaded: Boolean = false
  private var tajweedLegendJob: Job? = null
  private var colorsGuideLoaded: Boolean = false
  private var colorsGuideJob: Job? = null
  private var creditsLoaded: Boolean = false
  private var creditsJob: Job? = null
  private var recitersJob: Job? = null
  private var pdfManifestJob: Job? = null

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
      onAutoDownloadSurah = { reciter, surahId, preferredUrl ->
        val url = preferredUrl ?: audioRepository.buildSurahMp3Url(reciter, surahId)
        if (!url.isNullOrBlank()) {
          val surahName = cachedSurahsById[surahId]?.nameArabic
            ?: _uiState.value.surahs.firstOrNull { it.id == surahId }?.nameArabic.orEmpty()
          audioDownloadManager.enqueueAutoDownloadOnPlay(
            reciter = reciter,
            surahId = surahId,
            surahName = surahName,
            url = url,
          )
        }
      },
    )
  }

  private fun followPlayingVersePage(verseKey: String) {
    val pagesMap = _pagesState.value
    val currentPageNum = _uiState.value.currentPage
    val loadedCurrent = pagesMap[currentPageNum]
    if (loadedCurrent is PageLoadState.Ready &&
      loadedCurrent.renderedPage.verseHighlightRects.containsKey(verseKey)
    ) {
      return
    }
    val cachedTargetPage = pagesMap.entries.firstOrNull { (_, state) ->
      state is PageLoadState.Ready && state.renderedPage.verseHighlightRects.containsKey(verseKey)
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

  private fun rebuildRenderSpec(): PageRenderSpec {
    val st = _uiState.value
    val pdfId = if (st.activeMushafIsPdf) st.activePdfMushafId else null
    return PageRenderSpec(
      widthPx = measuredWidthPx,
      heightPx = measuredHeightPx,
      density = measuredDensity,
      fontScale = measuredFontScale,
      edition = st.mushafEdition,
      isDarkTheme = st.isDarkMode,
      backgroundTheme = st.backgroundTheme,
      isTwoPageMode = measuredIsTwoPageMode,
      pdfMushafId = pdfId,
      pdfFileKey = pdfId?.let { activePdfFileKeys[it] },
    )
  }

  fun updatePageRenderSpec(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    fontScale: Float,
    isTwoPageMode: Boolean = false,
  ) {
    if (widthPx <= 32 || heightPx <= 32 || density <= 0f) return
    val sizeChanged = widthPx != measuredWidthPx ||
      heightPx != measuredHeightPx ||
      density != measuredDensity ||
      fontScale != measuredFontScale ||
      isTwoPageMode != measuredIsTwoPageMode
    if (!sizeChanged && activeRenderSpec.isValid) return

    measuredWidthPx = widthPx
    measuredHeightPx = heightPx
    measuredDensity = density
    measuredFontScale = fontScale
    measuredIsTwoPageMode = isTwoPageMode

    val nextSpec = rebuildRenderSpec()
    if (nextSpec == activeRenderSpec) return
    onRenderSpecUpdated(nextSpec)
  }

  private fun onRenderSpecUpdated(nextSpec: PageRenderSpec) {
    activeRenderSpec = nextSpec
    clearCachedPages()
    _pagesState.value = emptyMap()
    if (hasReaderActivated && nextSpec.isValid) {
      loadPageWindow(_uiState.value.currentPage, nextSpec)
    }
  }

  private fun bootstrap() {
    viewModelScope.launch {
      val saved: PersistedReaderState = preferences.stateFlow.first()
      val cachedManifest = pdfMushafRepository.readCachedManifestOrEmpty()
      val cachedGuide = pdfMushafRepository.readCachedColorsGuideOrNull()
      val orderedCachedSections = if (cachedGuide != null) {
        ColorsGuideParser.orderSectionsByManifest(cachedGuide.riwayat, cachedManifest)
      } else {
        emptyList()
      }
      _uiState.update {
        it.copy(
          currentPage = saved.lastPage,
          mushafEdition = saved.mushafEdition,
          isDarkMode = saved.isDarkMode,
          backgroundTheme = saved.backgroundTheme,
          pageViewMode = saved.pageViewMode,
          bookmarks = saved.bookmarks,
          activeMushafIsPdf = saved.activeMushafIsPdf,
          activePdfMushafId = saved.activePdfMushafId,
          pdfMushafs = cachedManifest,
          colorsGuideSections = orderedCachedSections,
        )
      }
      activeRenderSpec = rebuildRenderSpec()

      if (saved.layoutCacheVersion < QuranRepository.LAYOUT_CACHE_VERSION) {
        quranRepository.clearInMemoryPageCache()
        pageBitmapRenderer.clearAll()
        preferences.setLayoutCacheVersion(QuranRepository.LAYOUT_CACHE_VERSION)
      }

      launch {
        preferences.stateFlow.collect { pref ->
          val prev = _uiState.value
          val themeChanged = (prev.isDarkMode != pref.isDarkMode) ||
            (prev.backgroundTheme != pref.backgroundTheme)
          _uiState.update {
            it.copy(
              bookmarks = pref.bookmarks,
              isDarkMode = pref.isDarkMode,
              backgroundTheme = pref.backgroundTheme,
              pageViewMode = pref.pageViewMode,
            )
          }
          if (themeChanged) {
            val nextSpec = rebuildRenderSpec()
            if (nextSpec != activeRenderSpec) {
              onRenderSpecUpdated(nextSpec)
            }
          }
        }
      }

      finishPostSetupBootstrap(saved)
    }
  }

  private suspend fun finishPostSetupBootstrap(saved: PersistedReaderState) {
    // First screen only needs Surah index; defer playback binding, downloaded keys scan & reciters until after first draw.
    val surahsRes = quranRepository.getSurahs()
    val surahs = surahsRes.getOrDefault(emptyList())
    if (surahs.isNotEmpty()) {
      cachedSurahsById = surahs.associateBy { it.id }
    }

    _uiState.update {
      it.copy(
        surahs = surahs,
        metadataError = if (surahsRes.isFailure) "تعذر تحميل بيانات السور" else null,
      )
    }

    // Yield so Compose can commit the first frame of the Surah index before background init runs.
    yield()

    if (surahs.isNotEmpty()) {
      playbackController.updateSurahNames(surahs.associate { it.id to it.nameArabic })
    }
    ensurePlaybackConnected()
    viewModelScope.launch { audioCacheManager.refreshDownloadedKeys() }

    if (saved.activeMushafIsPdf || localOrCachedPdfIds.value.isNotEmpty() || _uiState.value.pdfMushafs.isNotEmpty()) {
      viewModelScope.launch {
        val currentList = _uiState.value.pdfMushafs
        if (currentList.isNotEmpty()) {
          pruneAndRedownloadStalePdfFiles(currentList)
        }
        pdfMushafRepository.fetchManifest(forceRefresh = false).onSuccess { list ->
          _uiState.update { it.copy(pdfMushafs = list, pdfManifestError = null) }
          pruneAndRedownloadStalePdfFiles(list)
        }
      }
    }

    loadReciters(saved)
    appUpdateManager.checkForUpdatesInBackground(saved.betaChannelEnabled)
  }

  fun startMushafDownload(item: MushafItem) {
    mushafDownloadManager.startDownload(item) {
      preferences.addDownloadedMushaf(item.id)
      preferences.setActiveTextMushaf(item.id, item.edition)
      val wasPdf = _uiState.value.activeMushafIsPdf
      _uiState.update {
        it.copy(
          mushafEdition = item.edition,
          activeMushafIsPdf = false,
        )
      }
      val nextSpec = rebuildRenderSpec()
      onRenderSpecUpdated(nextSpec)
      if (wasPdf) {
        if (audioState.value.currentReciter?.isPdfModeReciter == true) {
          playbackController.clearReciterSelection()
        }
        reloadRecitersForCurrentState(forceRefresh = false)
      }
    }
  }

  fun cancelMushafDownload(id: String) {
    mushafDownloadManager.cancelDownload(id)
  }

  fun selectActiveMushaf(id: String) {
    val item = MushafRegistry.getById(id)
    viewModelScope.launch {
      val wasPdf = _uiState.value.activeMushafIsPdf
      preferences.setActiveTextMushaf(item.id, item.edition)
      _uiState.update {
        it.copy(
          mushafEdition = item.edition,
          activeMushafIsPdf = false,
        )
      }
      val nextSpec = rebuildRenderSpec()
      onRenderSpecUpdated(nextSpec)
      if (wasPdf) {
        if (audioState.value.currentReciter?.isPdfModeReciter == true) {
          playbackController.clearReciterSelection()
        }
        reloadRecitersForCurrentState(forceRefresh = false)
      }
    }
  }

  fun removeMushaf(id: String) {
    viewModelScope.launch {
      val downloaded = preferences.stateFlow.first().downloadedMushafIds
      if (downloaded.size <= 1 || !downloaded.contains(id)) return@launch

      preferences.removeDownloadedMushaf(id)
      if (id == "tajweed_v4") {
        diskCache.deleteTajweedCacheFiles()
        mushafDownloadManager.resetState("tajweed_v4")
      }
      val persistedAfter = preferences.stateFlow.first()
      val nextItem = MushafRegistry.getById(persistedAfter.activeMushafId)
      _uiState.update { st ->
        if (st.activeMushafIsPdf) {
          st.copy(mushafEdition = if (id == "tajweed_v4" && st.mushafEdition == MushafEdition.TAJWEED_V4) MushafEdition.MADINAH_1421 else st.mushafEdition)
        } else {
          st.copy(mushafEdition = nextItem.edition)
        }
      }
      val nextSpec = rebuildRenderSpec()
      onRenderSpecUpdated(nextSpec)
    }
  }

  fun loadPdfManifest(forceRefresh: Boolean = false) {
    if (pdfManifestJob?.isActive == true && !forceRefresh) return
    pdfManifestJob?.cancel()
    pdfManifestJob = viewModelScope.launch {
      val cached = pdfMushafRepository.readCachedManifestOrEmpty()
      if (cached.isNotEmpty() && _uiState.value.pdfMushafs.isEmpty()) {
        _uiState.update { it.copy(pdfMushafs = cached) }
      }
      if (!forceRefresh && _uiState.value.pdfMushafs.isNotEmpty()) {
        _uiState.update { it.copy(pdfManifestLoading = false, pdfManifestError = null) }
        pruneAndRedownloadStalePdfFiles(_uiState.value.pdfMushafs)
        return@launch
      }
      _uiState.update { it.copy(pdfManifestLoading = true, pdfManifestError = null) }
      val res = pdfMushafRepository.fetchManifest(forceRefresh = forceRefresh)
      res.onSuccess { list ->
        _uiState.update {
          it.copy(
            pdfMushafs = list,
            pdfManifestLoading = false,
            pdfManifestError = null,
            colorsGuideSections = ColorsGuideParser.orderSectionsByManifest(
              sections = it.colorsGuideSections,
              manifest = list,
            ),
          )
        }
        pruneAndRedownloadStalePdfFiles(list)
      }.onFailure {
        _uiState.update {
          it.copy(
            pdfManifestLoading = false,
            pdfManifestError = "تعذر تحميل قائمة المصاحف",
          )
        }
      }
    }
  }

  private suspend fun pruneAndRedownloadStalePdfFiles(entries: List<PdfMushafEntry>) {
    if (entries.isEmpty()) return
    val staleOffline = pdfMushafRepository.pruneStaleFilesAndCollectRedownloadEntries(
      entries = entries,
      onBeforeDelete = { staleId ->
        activePdfFileKeys.remove(staleId)
        pdfRendererBridge.closeIfMatches(staleId)
        if (_uiState.value.activeMushafIsPdf && _uiState.value.activePdfMushafId == staleId) {
          activeRenderSpec = rebuildRenderSpec()
          clearCachedPages()
          _pagesState.value = emptyMap()
          pageBitmapRenderer.clearAll()
        }
      },
    )
    for (entry in staleOffline) {
      pdfMushafRepository.startOfflineDownload(entry) {
        if (_uiState.value.activeMushafIsPdf &&
          _uiState.value.activePdfMushafId == entry.id &&
          hasReaderActivated &&
          activeRenderSpec.isValid
        ) {
          loadPageWindow(_uiState.value.currentPage, activeRenderSpec)
        }
      }
    }
    val activePdfId = _uiState.value.activePdfMushafId
    val activeEntry = if (_uiState.value.activeMushafIsPdf && activePdfId != null) {
      entries.firstOrNull { it.id == activePdfId }
    } else {
      null
    }
    if (activeEntry != null &&
      staleOffline.none { it.id == activeEntry.id } &&
      hasReaderActivated &&
      activeRenderSpec.isValid &&
      _pagesState.value.isEmpty()
    ) {
      loadPageWindow(_uiState.value.currentPage, activeRenderSpec)
    }
  }

  suspend fun loadPdfThumbnail(entry: PdfMushafEntry): Bitmap? =
    pdfMushafRepository.loadThumbnail(entry)

  fun downloadPdfMushafOffline(entry: PdfMushafEntry) {
    pdfMushafRepository.startOfflineDownload(entry)
  }

  fun forceUpdatePdfMushaf(entry: PdfMushafEntry) {
    viewModelScope.launch {
      pdfMushafRepository.forceRedownloadOffline(
        entry = entry,
        onBeforeDelete = {
          activePdfFileKeys.remove(entry.id)
          pdfRendererBridge.closeIfMatches(entry.id)
          if (_uiState.value.activeMushafIsPdf && _uiState.value.activePdfMushafId == entry.id) {
            activeRenderSpec = rebuildRenderSpec()
            clearCachedPages()
            _pagesState.value = emptyMap()
            pageBitmapRenderer.clearAll()
          }
        },
        onCompleted = {
          if (_uiState.value.activeMushafIsPdf &&
            _uiState.value.activePdfMushafId == entry.id &&
            hasReaderActivated &&
            activeRenderSpec.isValid
          ) {
            loadPageWindow(_uiState.value.currentPage, activeRenderSpec)
          }
        },
      )
    }
  }

  fun cancelPdfTransfer(mushafId: String) {
    pdfMushafRepository.cancelTransfer(mushafId)
  }

  fun deletePdfMushafOffline(entry: PdfMushafEntry) {
    viewModelScope.launch {
      activePdfFileKeys.remove(entry.id)
      pdfRendererBridge.closeIfMatches(entry.id)
      pdfMushafRepository.deleteOfflinePdf(entry.id)
      if (_uiState.value.activeMushafIsPdf && _uiState.value.activePdfMushafId == entry.id) {
        activeRenderSpec = rebuildRenderSpec()
      }
    }
  }

  fun selectPdfMushaf(entry: PdfMushafEntry) {
    dismissVerseInsights()
    val currentReciter = audioState.value.currentReciter
    val compatible = currentReciter != null &&
      currentReciter.isPdfModeReciter &&
      currentReciter.riwayaId in entry.apiRiwayaIds
    if (!compatible) {
      playbackController.clearReciterSelection()
    }
    viewModelScope.launch {
      preferences.setActivePdfMushaf(entry.id)
      _uiState.update { st ->
        val updatedManifest = if (st.pdfMushafs.any { it.id == entry.id }) st.pdfMushafs else (st.pdfMushafs + entry).sortedBy { it.order }
        st.copy(
          activeMushafIsPdf = true,
          activePdfMushafId = entry.id,
          pdfMushafs = updatedManifest,
        )
      }
      onRenderSpecUpdated(rebuildRenderSpec())
      reloadRecitersForCurrentState(forceRefresh = false)
    }
  }

  fun retryOfflineSetup() {
    // Retained for backward compatibility
  }

  fun ensureCurrentPageLoaded() {
    hasReaderActivated = true
    val spec = activeRenderSpec
    if (spec.isValid) {
      loadPageWindow(_uiState.value.currentPage, spec)
    }
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
    if (!tajweedLegendLoaded && tajweedLegendJob?.isActive != true) {
      tajweedLegendJob = viewModelScope.launch {
        val legend = quranRepository.loadTajweedLegend()
        if (legend.isNotEmpty()) {
          tajweedLegendLoaded = true
        }
        _uiState.update { it.copy(tajweedRules = legend) }
      }
    }
    ensureColorsGuideLoaded(forceRefresh = false)
  }

  fun retryColorsGuide() {
    ensureColorsGuideLoaded(forceRefresh = true)
  }

  fun ensureColorsGuideLoaded(forceRefresh: Boolean = false) {
    if (!forceRefresh && (colorsGuideLoaded || colorsGuideJob?.isActive == true)) return
    if (forceRefresh) {
      colorsGuideJob?.cancel()
    }
    colorsGuideJob = viewModelScope.launch {
      val cachedManifest = _uiState.value.pdfMushafs.ifEmpty {
        pdfMushafRepository.readCachedManifestOrEmpty()
      }
      val cachedGuide = pdfMushafRepository.readCachedColorsGuideOrNull()
      if (cachedGuide != null && _uiState.value.colorsGuideSections.isEmpty()) {
        val orderedCached = ColorsGuideParser.orderSectionsByManifest(cachedGuide.riwayat, cachedManifest)
        _uiState.update {
          it.copy(
            pdfMushafs = if (it.pdfMushafs.isEmpty()) cachedManifest else it.pdfMushafs,
            colorsGuideSections = orderedCached,
            colorsGuideError = null,
          )
        }
      }

      if (forceRefresh || _uiState.value.colorsGuideSections.isEmpty()) {
        _uiState.update { it.copy(colorsGuideLoading = true, colorsGuideError = null) }
      }

      coroutineScope {
        val manifestDeferred = if (_uiState.value.pdfMushafs.isEmpty() || forceRefresh) {
          async { pdfMushafRepository.fetchManifest(forceRefresh = forceRefresh) }
        } else {
          null
        }
        val guideDeferred = async { pdfMushafRepository.fetchColorsGuide() }

        val manifestRes = manifestDeferred?.await()
        val guideRes = guideDeferred.await()

        val effectiveManifest = manifestRes?.getOrNull()
          ?.takeIf { it.isNotEmpty() }
          ?: _uiState.value.pdfMushafs.ifEmpty { cachedManifest }

        guideRes.onSuccess { guide ->
          val ordered = ColorsGuideParser.orderSectionsByManifest(guide.riwayat, effectiveManifest)
          colorsGuideLoaded = true
          _uiState.update {
            it.copy(
              pdfMushafs = effectiveManifest,
              colorsGuideSections = ordered,
              colorsGuideLoading = false,
              colorsGuideError = null,
            )
          }
        }.onFailure {
          _uiState.update { st ->
            val hasFallback = st.colorsGuideSections.isNotEmpty()
            st.copy(
              pdfMushafs = effectiveManifest,
              colorsGuideLoading = false,
              colorsGuideError = if (hasFallback) null else "تعذر تحميل دليل الألوان، تحقق من الاتصال",
            )
          }
        }
      }
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
    loadRecitersForCurrentMode(
      selectedReciterKey = saved.selectedReciterKey,
      lastSurahId = saved.lastSurahId,
      lastPositionMs = saved.lastPositionMs,
      playbackSpeed = saved.playbackSpeed,
      forceRefresh = false,
    )
  }

  private fun reloadRecitersForCurrentState(forceRefresh: Boolean) {
    viewModelScope.launch {
      val saved = preferences.stateFlow.first()
      loadRecitersForCurrentMode(
        selectedReciterKey = saved.selectedReciterKey,
        lastSurahId = saved.lastSurahId,
        lastPositionMs = saved.lastPositionMs,
        playbackSpeed = saved.playbackSpeed,
        forceRefresh = forceRefresh,
      )
    }
  }

  fun retryLoadReciters() {
    reloadRecitersForCurrentState(forceRefresh = true)
  }

  private fun loadRecitersForCurrentMode(
    selectedReciterKey: String?,
    lastSurahId: Int,
    lastPositionMs: Long,
    playbackSpeed: Float,
    forceRefresh: Boolean,
  ) {
    recitersJob?.cancel()
    recitersJob = viewModelScope.launch {
      val snapshot = _uiState.value
      if (snapshot.activeMushafIsPdf) {
        val targetPdfId = snapshot.activePdfMushafId
        var pdfEntry = snapshot.pdfMushafs.firstOrNull { it.id == targetPdfId }
        if (pdfEntry == null && targetPdfId != null) {
          val fetched = pdfMushafRepository.fetchManifest(forceRefresh = false).getOrNull().orEmpty()
          if (fetched.isNotEmpty()) {
            _uiState.update { it.copy(pdfMushafs = fetched) }
            pdfEntry = fetched.firstOrNull { it.id == targetPdfId }
          }
        }
        val riwayaIds = pdfEntry?.apiRiwayaIds.orEmpty()
        if (riwayaIds.isEmpty()) {
          _uiState.update {
            it.copy(
              primaryReciters = emptyList(),
              additionalReciters = emptyList(),
              recitersLoading = false,
              recitersError = null,
            )
          }
          if (audioState.value.currentReciter != null) {
            playbackController.clearReciterSelection()
          }
          return@launch
        }

        _uiState.update { it.copy(recitersLoading = true, recitersError = null) }
        val res = audioRepository.getPdfModeRecitersCatalog(riwayaIds, forceRefresh = forceRefresh)
        if (_uiState.value.activeMushafIsPdf != true || _uiState.value.activePdfMushafId != targetPdfId) {
          return@launch
        }
        res.onSuccess { list ->
          _uiState.update {
            it.copy(
              primaryReciters = list,
              additionalReciters = emptyList(),
              recitersLoading = false,
              recitersError = null,
            )
          }
          val allPlayable = list.filter { it.isPlayable }
          val restored = selectedReciterKey?.let { key ->
            allPlayable.firstOrNull { it.key == key }
          } ?: allPlayable.firstOrNull()
          val current = audioState.value.currentReciter
          if (restored != null) {
            if (current?.key != restored.key || current.riwayaId != restored.riwayaId || current.hasAyahTiming != restored.hasAyahTiming) {
              playbackController.restoreSelectionWithoutPlaying(
                reciter = restored,
                surahId = lastSurahId,
                positionMs = lastPositionMs,
                speed = playbackSpeed,
              )
            }
          } else if (current != null) {
            playbackController.clearReciterSelection()
          }
        }.onFailure {
          _uiState.update {
            it.copy(
              primaryReciters = emptyList(),
              additionalReciters = emptyList(),
              recitersLoading = false,
              recitersError = "تعذر تحميل قائمة القراء",
            )
          }
        }
      } else {
        _uiState.update { it.copy(recitersLoading = true, recitersError = null) }
        val res = audioRepository.getVerifiedRecitersCatalog()
        if (_uiState.value.activeMushafIsPdf) return@launch
        res.onSuccess { (primary, additional) ->
          _uiState.update {
            it.copy(
              primaryReciters = primary,
              additionalReciters = additional,
              recitersLoading = false,
              recitersError = null,
            )
          }
          val allPlayable = (primary + additional).filter { it.isPlayable }
          val restored = selectedReciterKey?.let { key ->
            allPlayable.firstOrNull { it.key == key }
          } ?: allPlayable.firstOrNull()
          if (restored != null) {
            val current = audioState.value.currentReciter
            if (current == null || current.isPdfModeReciter || current.key != restored.key) {
              playbackController.restoreSelectionWithoutPlaying(
                reciter = restored,
                surahId = lastSurahId,
                positionMs = lastPositionMs,
                speed = playbackSpeed,
              )
            }
          }
        }.onFailure {
          _uiState.update {
            it.copy(
              recitersLoading = false,
              recitersError = "تعذر تحميل قائمة القراء",
            )
          }
        }
      }
    }
  }

  fun retryBootstrap() {
    if (setupManager.state.value !is MushafSetupState.Completed) {
      retryOfflineSetup()
      return
    }
    viewModelScope.launch {
      val saved = preferences.stateFlow.first()
      finishPostSetupBootstrap(saved)
    }
  }

  fun onPageChanged(page: Int) {
    val clamped = page.coerceIn(1, 604)
    hasReaderActivated = true
    val spec = activeRenderSpec
    val currentState = _pagesState.value[clamped]
    val isFullReady = currentState is PageLoadState.Ready && !currentState.renderedPage.isPreview
    if (!spec.isTwoPageMode && clamped == _uiState.value.currentPage && isFullReady) {
      return
    }
    _uiState.update { it.copy(currentPage = clamped) }
    viewModelScope.launch { preferences.saveLastPage(clamped) }
    if (spec.isValid) {
      loadPageWindow(clamped, spec)
    }
  }

  fun jumpToPage(page: Int) {
    val clamped = page.coerceIn(1, 604)
    hasReaderActivated = true
    _uiState.update { it.copy(currentPage = clamped) }
    viewModelScope.launch { preferences.saveLastPage(clamped) }
    val spec = activeRenderSpec
    if (spec.isValid) {
      loadPageWindow(clamped, spec)
    }
  }

  fun jumpToVerse(verseKey: String, pageNumber: Int) {
    _uiState.update { it.copy(selectedVerseKey = verseKey) }
    jumpToPage(pageNumber)
  }

  fun setMushafEdition(edition: MushafEdition) {
    val wasPdf = _uiState.value.activeMushafIsPdf
    if (!wasPdf && edition == _uiState.value.mushafEdition) return
    val item = MushafRegistry.getByEdition(edition)
    _uiState.update { it.copy(mushafEdition = edition, activeMushafIsPdf = false) }
    viewModelScope.launch {
      preferences.setMushafEdition(edition)
      if (preferences.stateFlow.first().downloadedMushafIds.contains(item.id)) {
        preferences.setActiveTextMushaf(item.id, edition)
      }
    }
    val nextSpec = rebuildRenderSpec()
    if (nextSpec != activeRenderSpec) {
      onRenderSpecUpdated(nextSpec)
    }
    if (wasPdf) {
      if (audioState.value.currentReciter?.isPdfModeReciter == true) {
        playbackController.clearReciterSelection()
      }
      reloadRecitersForCurrentState(forceRefresh = false)
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
    val changed = (state.isDarkMode != nextDark) || (state.backgroundTheme != nextBg)
    _uiState.update {
      it.copy(
        isDarkMode = nextDark,
        backgroundTheme = nextBg,
      )
    }
    if (changed) {
      val nextSpec = rebuildRenderSpec()
      if (nextSpec != activeRenderSpec) {
        onRenderSpecUpdated(nextSpec)
      }
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
    val spec = activeRenderSpec
    if (spec.isValid) {
      loadSinglePage(page, spec, forceReload = true, isVisiblePriority = true)
    }
  }

  fun warmUpWordSearchIndex() {
    if (indexWarmupJob?.isActive == true) return
    indexWarmupJob = viewModelScope.launch {
      _wordSearchState.update { it.copy(isIndexWarming = true) }
      quranRepository.warmUpWordSearchIndex()
      _wordSearchState.update { it.copy(isIndexWarming = false) }
    }
  }

  fun onWordSearchQueryChanged(query: String) {
    searchJob?.cancel()
    val norm = QuranWordSearchNormalizer.normalizeKey(query.trim())
    _wordSearchState.update {
      it.copy(
        query = query,
        isFullResultsMode = false,
        collapsedSurahIds = emptySet(),
        visibleResultLimit = 40,
        errorMessage = null,
        isLoading = norm.isNotEmpty(),
        result = if (norm.isEmpty()) null else it.result,
      )
    }
    if (norm.isEmpty()) return

    val isSubstring = _wordSearchState.value.isSubstringMode
    searchJob = viewModelScope.launch {
      delay(300L)
      executeWordSearch(query = query, isSubstring = isSubstring, fullMode = false)
    }
  }

  fun setWordSearchSubstringMode(enabled: Boolean) {
    searchJob?.cancel()
    val current = _wordSearchState.value
    val norm = QuranWordSearchNormalizer.normalizeKey(current.query.trim())
    _wordSearchState.update {
      it.copy(
        isSubstringMode = enabled,
        collapsedSurahIds = emptySet(),
        visibleResultLimit = 40,
        errorMessage = null,
        isLoading = norm.isNotEmpty(),
      )
    }
    if (norm.isEmpty()) return

    searchJob = viewModelScope.launch {
      executeWordSearch(
        query = current.query,
        isSubstring = enabled,
        fullMode = current.isFullResultsMode,
      )
    }
  }

  fun submitWordSearchFullResults() {
    searchJob?.cancel()
    val current = _wordSearchState.value
    val norm = QuranWordSearchNormalizer.normalizeKey(current.query.trim())
    if (norm.isEmpty()) {
      _wordSearchState.update {
        it.copy(
          isFullResultsMode = true,
          isLoading = false,
          errorMessage = null,
          result = null,
        )
      }
      return
    }

    val alreadyHasExact = current.result != null &&
      current.result.query == current.query &&
      current.result.isSubstringMode == current.isSubstringMode
    if (alreadyHasExact) {
      _wordSearchState.update {
        it.copy(
          isFullResultsMode = true,
          isLoading = false,
          errorMessage = null,
          collapsedSurahIds = emptySet(),
          visibleResultLimit = 40,
        )
      }
      return
    }

    _wordSearchState.update {
      it.copy(
        isFullResultsMode = true,
        isLoading = true,
        errorMessage = null,
        collapsedSurahIds = emptySet(),
        visibleResultLimit = 40,
      )
    }
    searchJob = viewModelScope.launch {
      executeWordSearch(
        query = current.query,
        isSubstring = current.isSubstringMode,
        fullMode = true,
      )
    }
  }

  fun toggleWordSearchSurahCollapsed(surahId: Int) {
    _wordSearchState.update { st ->
      val next = st.collapsedSurahIds.toMutableSet()
      if (!next.add(surahId)) {
        next.remove(surahId)
      }
      st.copy(collapsedSurahIds = next)
    }
  }

  fun loadMoreWordSearchResults() {
    _wordSearchState.update { st ->
      val max = st.result?.totalMatchingAyahs ?: return@update st
      if (st.visibleResultLimit >= max) return@update st
      st.copy(visibleResultLimit = (st.visibleResultLimit + 40).coerceAtMost(max))
    }
  }

  fun retryWordSearch() {
    val current = _wordSearchState.value
    if (QuranWordSearchNormalizer.normalizeKey(current.query.trim()).isEmpty()) return
    searchJob?.cancel()
    _wordSearchState.update { it.copy(isLoading = true, errorMessage = null) }
    searchJob = viewModelScope.launch {
      executeWordSearch(
        query = current.query,
        isSubstring = current.isSubstringMode,
        fullMode = current.isFullResultsMode,
      )
    }
  }

  fun clearWordSearch() {
    searchJob?.cancel()
    _wordSearchState.value = QuranWordSearchUiState()
  }

  private suspend fun executeWordSearch(
    query: String,
    isSubstring: Boolean,
    fullMode: Boolean,
  ) {
    val res = quranRepository.searchQuranWords(
      query = query,
      isSubstring = isSubstring,
    )
    res.onSuccess { searchResult ->
      _wordSearchState.update { st ->
        if (st.query != query || st.isSubstringMode != isSubstring) {
          st
        } else {
          st.copy(
            isFullResultsMode = fullMode || st.isFullResultsMode,
            isLoading = false,
            errorMessage = null,
            result = searchResult,
          )
        }
      }
    }.onFailure { err ->
      if (err is CancellationException) throw err
      _wordSearchState.update { st ->
        if (st.query != query || st.isSubstringMode != isSubstring) {
          st
        } else {
          st.copy(
            isLoading = false,
            errorMessage = "تعذر البحث في القرآن الكريم",
            result = null,
          )
        }
      }
    }
  }

  private fun clearCachedPages() {
    prefetchJob?.cancel()
    prefetchJob = null
    pageJobs.values.forEach { it.cancel() }
    pageJobs.clear()
    pageJobVisiblePriority.clear()
    pageLruOrder.clear()
  }

  private fun retainPageWindowLru(
    orderedTargets: List<Int>,
    maxRetained: Int,
  ): Set<Int> {
    for (p in orderedTargets.asReversed()) {
      pageLruOrder.remove(p)
      pageLruOrder.addLast(p)
    }
    while (pageLruOrder.size > maxRetained) {
      val evicted = pageLruOrder.removeFirst()
      pageJobs.remove(evicted)?.cancel()
      pageJobVisiblePriority.remove(evicted)
    }
    return pageLruOrder.toSet()
  }

  private fun loadPageWindow(
    centerPage: Int,
    spec: PageRenderSpec,
  ) {
    if (!spec.isValid) return
    prefetchJob?.cancel()

    val visiblePages = if (spec.isTwoPageMode) {
      val rightPage = (((centerPage - 1) / 2) * 2 + 1).coerceIn(1, 604)
      val leftPage = (rightPage + 1).coerceAtMost(604)
      listOf(rightPage, leftPage).distinct()
    } else {
      listOf(centerPage.coerceIn(1, 604))
    }

    val prefetchPages = if (spec.isTwoPageMode) {
      val rightPage = visiblePages.first()
      listOf(
        rightPage + 2,
        rightPage + 3,
        rightPage + 4,
        rightPage + 5,
        rightPage - 1,
        rightPage - 2,
        rightPage - 3,
        rightPage - 4,
      ).filter { it in 1..604 && it !in visiblePages }
    } else {
      listOf(
        centerPage + 1,
        centerPage + 2,
        centerPage - 1,
      ).filter { it in 1..604 && it !in visiblePages }
    }

    val allTargets = visiblePages + prefetchPages
    val maxRetained = if (spec.isTwoPageMode) TWO_PAGE_CACHE_LIMIT else SINGLE_PAGE_CACHE_LIMIT
    val retainedKeys = retainPageWindowLru(allTargets, maxRetained)

    // Cancel any active prefetch jobs for pages not in the new visible/prefetch window
    val iter = pageJobs.entries.iterator()
    while (iter.hasNext()) {
      val entry = iter.next()
      val p = entry.key
      if (p !in allTargets) {
        if (pageJobVisiblePriority[p] != true) {
          entry.value.cancel()
          iter.remove()
          pageJobVisiblePriority.remove(p)
        }
      }
    }

    _pagesState.update { currentMap ->
      val pruned = currentMap.filterKeys { it in retainedKeys }
      if (pruned.size == currentMap.size) currentMap else pruned
    }

    val visibleJobs = visiblePages.mapNotNull { p ->
      loadSinglePage(p, spec, forceReload = false, isVisiblePriority = true)
    }

    if (prefetchPages.isNotEmpty()) {
      prefetchJob = viewModelScope.launch {
        visibleJobs.forEach { it.join() }
        for (p in prefetchPages) {
          if (activeRenderSpec != spec || !pageLruOrder.contains(p)) break
          val job = loadSinglePage(p, spec, forceReload = false, isVisiblePriority = false)
          if (spec.isPdfMode) {
            job?.join()
          }
        }
      }
    }
  }

  private suspend fun renderPdfBitmapWithRecovery(
    entry: PdfMushafEntry,
    initialFile: java.io.File,
    page: Int,
    bodyW: Int,
    isTwoPageMode: Boolean,
    scaleMultiplier: Float,
    isVisiblePriority: Boolean,
  ): Pair<java.io.File, com.example.data.pdf.PdfRenderedPageBitmap> {
    var activeFile = initialFile
    try {
      val bmp = pdfRendererBridge.renderPage(
        mushafId = entry.id,
        pdfFile = activeFile,
        pageNumber = page,
        onScreenWidthPx = bodyW,
        cropLeftPt = entry.cropLeftPt,
        cropRightPt = entry.cropRightPt,
        isTwoPageMode = isTwoPageMode,
        scaleMultiplier = scaleMultiplier,
        isVisiblePriority = isVisiblePriority,
      )
      return activeFile to bmp
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // Automatic recovery step 1: close open renderer & retry once on the same local file
      pdfRendererBridge.closeIfMatches(entry.id)
      try {
        val retryBmp = pdfRendererBridge.renderPage(
          mushafId = entry.id,
          pdfFile = activeFile,
          pageNumber = page,
          onScreenWidthPx = bodyW,
          cropLeftPt = entry.cropLeftPt,
          cropRightPt = entry.cropRightPt,
          isTwoPageMode = isTwoPageMode,
          scaleMultiplier = scaleMultiplier,
          isVisiblePriority = isVisiblePriority,
        )
        return activeFile to retryBmp
      } catch (e2: CancellationException) {
        throw e2
      } catch (_: Exception) {
        // Automatic recovery step 2: delete local file, re-download once, and re-open cleanly
        activeFile = pdfMushafRepository.invalidateAndRedownloadForReader(
          entry = entry,
          onBeforeDelete = {
            activePdfFileKeys.remove(entry.id)
            pdfRendererBridge.closeIfMatches(entry.id)
          },
        ).getOrThrow()
        val redownloadedBmp = pdfRendererBridge.renderPage(
          mushafId = entry.id,
          pdfFile = activeFile,
          pageNumber = page,
          onScreenWidthPx = bodyW,
          cropLeftPt = entry.cropLeftPt,
          cropRightPt = entry.cropRightPt,
          isTwoPageMode = isTwoPageMode,
          scaleMultiplier = scaleMultiplier,
          isVisiblePriority = isVisiblePriority,
        )
        return activeFile to redownloadedBmp
      }
    }
  }

  private fun loadSinglePage(
    page: Int,
    spec: PageRenderSpec,
    forceReload: Boolean,
    isVisiblePriority: Boolean = true,
  ): Job? {
    if (page !in 1..604 || !spec.isValid) return null

    if (!forceReload) {
      val cached = pageBitmapRenderer.getCached(page, spec, isPreview = false)
      if (cached != null) {
        val currentState = _pagesState.value[page]
        if (currentState !is PageLoadState.Ready || currentState.renderedPage !== cached) {
          _pagesState.update {
            it + (page to PageLoadState.Ready(
              renderedPage = cached,
              effectiveDark = spec.effectiveDark,
              edition = spec.edition,
            ))
          }
        }
        return null
      }
      val activeJob = pageJobs[page]
      if (activeJob?.isActive == true) {
        val existingIsVisible = pageJobVisiblePriority[page] == true
        if (!isVisiblePriority || existingIsVisible) {
          return activeJob
        }
      }
    }

    pageJobs[page]?.cancel()
    pageJobVisiblePriority[page] = isVisiblePriority
    val job = viewModelScope.launch {
      _pagesState.update { current ->
        if (current[page] is PageLoadState.Ready && !forceReload) current
        else current + (page to PageLoadState.Loading)
      }

      if (spec.isPdfMode) {
        try {
          val pdfId = spec.pdfMushafId
          var entry = _uiState.value.pdfMushafs.firstOrNull { it.id == pdfId }
            ?: pdfMushafRepository.readCachedManifestOrEmpty().firstOrNull { it.id == pdfId }
          if (entry == null) {
            val fetched = pdfMushafRepository.fetchManifest(forceRefresh = false).getOrNull().orEmpty()
            if (fetched.isNotEmpty()) {
              _uiState.update { it.copy(pdfMushafs = fetched) }
              entry = fetched.firstOrNull { it.id == pdfId }
            }
          }
          if (entry == null) {
            _pagesState.update {
              it + (page to PageLoadState.Error("تعذر العثور على بيانات المصحف المختار"))
            }
            return@launch
          }

          val fileRes = pdfMushafRepository.ensurePdfReadyForReader(
            entry = entry,
            onBeforeDeleteStale = { staleId ->
              activePdfFileKeys.remove(staleId)
              pdfRendererBridge.closeIfMatches(staleId)
            },
          )
          var pdfFile = fileRes.getOrElse {
            _pagesState.update {
              it + (page to PageLoadState.Error("تعذر فتح المصحف"))
            }
            return@launch
          }

          if (activeRenderSpec != spec || !pageLruOrder.contains(page)) return@launch

          var pdfFileKey = "${pdfFile.absolutePath}|${pdfFile.length()}|${pdfFile.lastModified()}"
          activePdfFileKeys[entry.id] = pdfFileKey
          var fileSpec = spec.copy(pdfFileKey = pdfFileKey)
          if (!forceReload) {
            pageBitmapRenderer.getCached(page, fileSpec, isPreview = false)?.let { cached ->
              _pagesState.update {
                it + (page to PageLoadState.Ready(
                  renderedPage = cached,
                  effectiveDark = spec.effectiveDark,
                  edition = spec.edition,
                ))
              }
              return@launch
            }
          }

          val headerMeta = quranRepository.getExistingPageHeaderMeta(page)
          val (bodyW, _) = pageBitmapRenderer.computePdfBodySizePx(fileSpec, page)
          val surahsMap = cachedSurahsById.ifEmpty {
            quranRepository.getSurahs().getOrDefault(emptyList()).associateBy { it.id }.also {
              if (it.isNotEmpty()) cachedSurahsById = it
            }
          }
          val madaniTopSurahId = com.example.data.quran.QulPageLayoutLoader
            .getPageLines(getApplication(), MushafEdition.MADINAH_1421, page)
            .minByOrNull { it.line }
            ?.takeIf { it.type == "surah_name" }
            ?.surah
          val topSurah = madaniTopSurahId?.let { surahsMap[it] }

          val hasFullPdfCached = !forceReload && pdfRendererBridge.getCached(
            pdfFile = pdfFile,
            pageNumber = page,
            onScreenWidthPx = bodyW,
            isTwoPageMode = fileSpec.isTwoPageMode,
            scaleMultiplier = 1f,
          ) != null

          // Progressive stage 1: fast 0.4x preview for visible pages so swipes never stall on spinners
          if (isVisiblePriority && !hasFullPdfCached) {
            val (previewFile, previewPdf) = renderPdfBitmapWithRecovery(
              entry = entry,
              initialFile = pdfFile,
              page = page,
              bodyW = bodyW,
              isTwoPageMode = fileSpec.isTwoPageMode,
              scaleMultiplier = PdfRendererBridge.PREVIEW_SCALE_FACTOR,
              isVisiblePriority = true,
            )
            if (previewFile.absolutePath != pdfFile.absolutePath ||
              previewFile.length() != pdfFile.length() ||
              previewFile.lastModified() != pdfFile.lastModified()
            ) {
              pdfFile = previewFile
              pdfFileKey = "${pdfFile.absolutePath}|${pdfFile.length()}|${pdfFile.lastModified()}"
              activePdfFileKeys[entry.id] = pdfFileKey
              fileSpec = spec.copy(pdfFileKey = pdfFileKey)
            }
            if (activeRenderSpec != spec || !pageLruOrder.contains(page)) return@launch

            val previewRendered = pageBitmapRenderer.renderPdfPageOrGet(
              pageNumber = page,
              headerMeta = headerMeta,
              renderedPdf = previewPdf,
              topSurah = topSurah,
              spec = fileSpec,
              surahsById = surahsMap,
              isPreview = true,
            )
            if (activeRenderSpec != spec || !pageLruOrder.contains(page)) return@launch
            _pagesState.update { cur ->
              val existingReady = (cur[page] as? PageLoadState.Ready)?.renderedPage
              if (!forceReload && existingReady != null && !existingReady.isPreview) {
                cur
              } else {
                cur + (page to PageLoadState.Ready(
                  renderedPage = previewRendered,
                  effectiveDark = spec.effectiveDark,
                  edition = spec.edition,
                ))
              }
            }
          }

          // Progressive stage 2 (or direct full stage for cache hits / background prefetch)
          val (resolvedFile, fullPdf) = renderPdfBitmapWithRecovery(
            entry = entry,
            initialFile = pdfFile,
            page = page,
            bodyW = bodyW,
            isTwoPageMode = fileSpec.isTwoPageMode,
            scaleMultiplier = 1f,
            isVisiblePriority = isVisiblePriority,
          )
          if (resolvedFile.absolutePath != pdfFile.absolutePath ||
            resolvedFile.length() != pdfFile.length() ||
            resolvedFile.lastModified() != pdfFile.lastModified()
          ) {
            pdfFile = resolvedFile
            pdfFileKey = "${pdfFile.absolutePath}|${pdfFile.length()}|${pdfFile.lastModified()}"
            activePdfFileKeys[entry.id] = pdfFileKey
            fileSpec = spec.copy(pdfFileKey = pdfFileKey)
          }

          if (activeRenderSpec != spec || !pageLruOrder.contains(page)) {
            return@launch
          }

          val rendered = pageBitmapRenderer.renderPdfPageOrGet(
            pageNumber = page,
            headerMeta = headerMeta,
            renderedPdf = fullPdf,
            topSurah = topSurah,
            spec = fileSpec,
            surahsById = surahsMap,
            isPreview = false,
          )

          if (activeRenderSpec != spec || !pageLruOrder.contains(page)) return@launch

          _pagesState.update {
            it + (page to PageLoadState.Ready(
              renderedPage = rendered,
              effectiveDark = spec.effectiveDark,
              edition = spec.edition,
            ))
          }
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          _pagesState.update {
            it + (page to PageLoadState.Error("تعذر فتح المصحف"))
          }
        }
        return@launch
      }

      val edition = spec.edition
      val useWhiteInk = spec.effectiveDark
      val needsTagFallbackTajweed =
        edition == MushafEdition.TAJWEED_V4 && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

      val pageRes = quranRepository.getPage(
        pageNumber = page,
        edition = edition,
        includeTajweedFallback = needsTagFallbackTajweed,
      )
      if (pageRes.isFailure) {
        _pagesState.update {
          it + (page to PageLoadState.Error("تعذر تحميل الصفحة $page"))
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

      if (activeRenderSpec != spec) return@launch
      if (!pageLruOrder.contains(page)) return@launch

      if (pageTypeface == null && !needsTagFallbackTajweed) {
        _pagesState.update {
          it + (page to PageLoadState.Error("تعذر تحميل خط الصفحة العثماني"))
        }
        return@launch
      }

      val surahsMap = cachedSurahsById.ifEmpty {
        quranRepository.getSurahs().getOrDefault(emptyList()).associateBy { it.id }.also {
          if (it.isNotEmpty()) cachedSurahsById = it
        }
      }

      val rendered = pageBitmapRenderer.renderOrGet(
        pageData = pageData,
        spec = spec,
        pageTypeface = pageTypeface,
        basmalahTypeface = basmalahTypeface,
        usesTagFallbackTajweed = needsTagFallbackTajweed,
        surahsById = surahsMap,
      )

      if (activeRenderSpec != spec) return@launch
      if (!pageLruOrder.contains(page)) return@launch

      _pagesState.update {
        it + (page to PageLoadState.Ready(
          renderedPage = rendered,
          effectiveDark = useWhiteInk,
          edition = edition,
        ))
      }
    }
    pageJobs[page] = job
    return job
  }

  fun onVerseTapped(verseKey: String, pageNumber: Int, verseCodeV2Line: String) {
    if (_uiState.value.activeMushafIsPdf) return
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
      }.onFailure {
        _verseInsightsState.value = VerseInsightsUiState.Error(
          verseKey = verseKey,
          message = "تعذر تحميل التفسير والترجمات",
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
    downloadSingleSurahForReciter(reciter, surahId)
  }

  fun deleteDownloadedSurahAudio(surahId: Int) {
    val reciter = audioState.value.currentReciter ?: return
    deleteSurahForReciter(reciter.key, surahId)
  }

  fun ensureAllDownloadableRecitersLoaded(forceRefresh: Boolean = false) {
    val current = _downloadableRecitersState.value
    if (!forceRefresh && (current.reciters.isNotEmpty() || downloadableRecitersJob?.isActive == true)) {
      return
    }
    downloadableRecitersJob?.cancel()
    downloadableRecitersJob = viewModelScope.launch {
      _downloadableRecitersState.update { it.copy(isLoading = true, errorMessage = null) }
      audioCacheManager.refreshDownloadedKeys()
      val manifestEntries = _uiState.value.pdfMushafs.ifEmpty {
        pdfMushafRepository.readCachedManifestOrEmpty().ifEmpty {
          pdfMushafRepository.fetchManifest(forceRefresh = false).getOrDefault(emptyList())
        }
      }
      if (_uiState.value.pdfMushafs.isEmpty() && manifestEntries.isNotEmpty()) {
        _uiState.update { it.copy(pdfMushafs = manifestEntries) }
      }
      val manifestRiwayaIds = manifestEntries.flatMap { it.apiRiwayaIds }.distinct()
      val res = audioRepository.getAllDownloadableRecitersCatalog(manifestRiwayaIds)
      res.onSuccess { list ->
        _downloadableRecitersState.value = DownloadableRecitersUiState(
          reciters = list,
          isLoading = false,
          errorMessage = null,
        )
      }.onFailure {
        _downloadableRecitersState.update {
          it.copy(
            isLoading = false,
            errorMessage = "تعذر تحميل قائمة القراء",
          )
        }
      }
    }
  }

  fun downloadSingleSurahForReciter(reciter: VerifiedReciter, surahId: Int) {
    val url = audioRepository.buildSurahMp3Url(reciter, surahId) ?: return
    val surahName = cachedSurahsById[surahId]?.nameArabic
      ?: _uiState.value.surahs.firstOrNull { it.id == surahId }?.nameArabic.orEmpty()
    audioDownloadManager.enqueueSingleSurah(
      reciter = reciter,
      surahId = surahId,
      surahName = surahName,
      url = url,
    )
  }

  fun cancelSurahDownloadForReciter(reciterKey: String, surahId: Int) {
    audioDownloadManager.cancelSurah(reciterKey, surahId)
  }

  fun downloadAllSurahsForReciter(reciter: VerifiedReciter) {
    val surahNames = cachedSurahsById.mapValues { it.value.nameArabic }
      .ifEmpty { _uiState.value.surahs.associate { it.id to it.nameArabic } }
    audioDownloadManager.enqueueReciterBatch(
      reciter = reciter,
      surahNamesById = surahNames,
      urlBuilder = { r, sId -> audioRepository.buildSurahMp3Url(r, sId) },
    )
  }

  fun cancelAllDownloadsForReciter(reciterKey: String) {
    audioDownloadManager.cancelReciter(reciterKey)
  }

  fun deleteSurahForReciter(reciterKey: String, surahId: Int) {
    audioDownloadManager.deleteSurah(reciterKey, surahId)
  }

  fun deleteAllSurahsForReciter(reciterKey: String) {
    audioDownloadManager.deleteAllForReciter(reciterKey)
  }

  fun clearAudioLowStorageMessage() {
    audioDownloadManager.clearLowStorageMessage()
  }

  fun setBetaChannelEnabled(enabled: Boolean) {
    viewModelScope.launch {
      preferences.setBetaChannelEnabled(enabled)
    }
  }

  fun unlockBetaChannel() {
    viewModelScope.launch {
      preferences.setBetaChannelUnlocked(true)
    }
  }

  fun checkForUpdates() {
    appUpdateManager.checkForUpdates(betaChannelEnabledState.value)
  }

  fun downloadUpdate(update: AppUpdateState.UpdateAvailable) {
    appUpdateManager.startDownloadAndInstall(update)
  }

  fun cancelUpdateDownload() {
    appUpdateManager.cancelDownload()
  }

  fun openUnknownSourcesSettings(activity: android.app.Activity? = null, markLaunchedFromAuto: Boolean = false) {
    appUpdateManager.openUnknownSourcesSettings(activity, markLaunchedFromAuto)
  }

  fun resumePendingUpdateInstall() {
    appUpdateManager.resumePendingInstallIfNeeded()
  }

  fun launchApkInstaller(activity: android.app.Activity?, apkFile: java.io.File) {
    appUpdateManager.launchApkInstallerFromUi(activity, apkFile)
  }

  fun dismissUpdateDialog() {
    appUpdateManager.dismiss()
  }

  override fun onCleared() {
    pdfRendererBridge.closeBlocking()
    appUpdateManager.release()
    playbackController.release()
    super.onCleared()
  }
}
