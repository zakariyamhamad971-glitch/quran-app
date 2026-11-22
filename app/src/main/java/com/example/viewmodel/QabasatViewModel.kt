package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
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
import com.example.data.quran.SurahMeta
import com.example.data.quran.TajweedRuleInfo
import com.example.data.quran.VerseInsightsData
import com.example.data.quran.VerseInsightsRepository
import com.example.data.quran.VerseSearchHit
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
  val verseSearchResults: List<VerseSearchHit> = emptyList(),
  val verseSearchLoading: Boolean = false,
  val verseSearchError: String? = null,
  val metadataError: String? = null,
  val activeMushafIsPdf: Boolean = false,
  val activePdfMushafId: String? = null,
  val pdfMushafs: List<PdfMushafEntry> = emptyList(),
  val pdfManifestLoading: Boolean = false,
  val pdfManifestError: String? = null,
)

class QabasatViewModel(application: Application) : AndroidViewModel(application) {
  companion object {
    private const val PAGE_CACHE_LIMIT = 8
  }

  private val diskCache = VerifiedDiskCache(application)
  private val fontManager = PageFontManager(diskCache)
  private val quranRepository = QuranRepository(application, diskCache, fontManager)
  private val verseInsightsRepository = VerseInsightsRepository(diskCache)
  private val audioRepository = AudioRepository(application, diskCache)
  private val audioCacheManager = AudioCacheManager(application)
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

  val setupState: StateFlow<MushafSetupState> = setupManager.state

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
  @Volatile
  private var activeRenderSpec = PageRenderSpec()
  @Volatile
  private var cachedSurahsById: Map<Int, SurahMeta> = emptyMap()
  private var measuredWidthPx: Int = 0
  private var measuredHeightPx: Int = 0
  private var measuredDensity: Float = 0f
  private var measuredFontScale: Float = 1f
  private var searchJob: Job? = null
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
    return PageRenderSpec(
      widthPx = measuredWidthPx,
      heightPx = measuredHeightPx,
      density = measuredDensity,
      fontScale = measuredFontScale,
      edition = st.mushafEdition,
      isDarkTheme = st.isDarkMode,
      backgroundTheme = st.backgroundTheme,
      pdfMushafId = if (st.activeMushafIsPdf) st.activePdfMushafId else null,
    )
  }

  fun updatePageRenderSpec(
    widthPx: Int,
    heightPx: Int,
    density: Float,
    fontScale: Float,
  ) {
    if (widthPx <= 32 || heightPx <= 32 || density <= 0f) return
    val sizeChanged = widthPx != measuredWidthPx ||
      heightPx != measuredHeightPx ||
      density != measuredDensity ||
      fontScale != measuredFontScale
    if (!sizeChanged && activeRenderSpec.isValid) return

    measuredWidthPx = widthPx
    measuredHeightPx = heightPx
    measuredDensity = density
    measuredFontScale = fontScale

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
        metadataError = surahsRes.exceptionOrNull()?.localizedMessage,
      )
    }

    // Yield so Compose can commit the first frame of the Surah index before background init runs.
    yield()

    if (surahs.isNotEmpty()) {
      playbackController.updateSurahNames(surahs.associate { it.id to it.nameArabic })
    }
    ensurePlaybackConnected()
    viewModelScope.launch { audioCacheManager.refreshDownloadedKeys() }

    if (saved.activeMushafIsPdf && _uiState.value.pdfMushafs.none { it.id == saved.activePdfMushafId }) {
      val fetchedManifest = pdfMushafRepository.fetchManifest(forceRefresh = false)
      fetchedManifest.onSuccess { list ->
        _uiState.update { it.copy(pdfMushafs = list, pdfManifestError = null) }
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
      preferences.removeDownloadedMushaf(id)
      val currentActive = preferences.stateFlow.first().activeMushafId
      val item = MushafRegistry.getById(currentActive)
      _uiState.update { it.copy(mushafEdition = item.edition) }
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
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            pdfManifestLoading = false,
            pdfManifestError = err.localizedMessage ?: "تعذر تحميل قائمة المصاحف",
          )
        }
      }
    }
  }

  suspend fun loadPdfThumbnail(entry: PdfMushafEntry): Bitmap? =
    pdfMushafRepository.loadThumbnail(entry)

  fun downloadPdfMushafOffline(entry: PdfMushafEntry) {
    pdfMushafRepository.startOfflineDownload(entry)
  }

  fun cancelPdfTransfer(mushafId: String) {
    pdfMushafRepository.cancelTransfer(mushafId)
  }

  fun deletePdfMushafOffline(entry: PdfMushafEntry) {
    viewModelScope.launch {
      pdfRendererBridge.closeIfMatches(entry.id)
      pdfMushafRepository.deleteOfflinePdf(entry.id)
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
        }.onFailure { err ->
          _uiState.update {
            it.copy(
              primaryReciters = emptyList(),
              additionalReciters = emptyList(),
              recitersLoading = false,
              recitersError = err.localizedMessage ?: "تعذر تحميل قائمة القراء",
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
    if (clamped == _uiState.value.currentPage && _pagesState.value[clamped] is PageLoadState.Ready) {
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
      loadSinglePage(page, spec, forceReload = true)
    }
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
    spec: PageRenderSpec,
  ) {
    if (!spec.isValid) return
    // Current page plus 2 pages on each side (+3 for right-spread partner prefetch)
    val activeTargets = listOfNotNull(
      centerPage.takeIf { it in 1..604 },
      (centerPage + 1).takeIf { it in 1..604 },
      (centerPage - 1).takeIf { it in 1..604 },
      (centerPage + 2).takeIf { it in 1..604 },
      (centerPage - 2).takeIf { it in 1..604 },
      if (centerPage % 2 == 1 && (centerPage + 3) in 1..604) centerPage + 3 else null,
    )
    val retainedKeys = retainPageWindowLru(activeTargets)
    _pagesState.update { currentMap ->
      val pruned = currentMap.filterKeys { it in retainedKeys }
      if (pruned.size == currentMap.size) currentMap else pruned
    }
    for (p in activeTargets) {
      loadSinglePage(p, spec, forceReload = false)
    }
  }

  private fun loadSinglePage(
    page: Int,
    spec: PageRenderSpec,
    forceReload: Boolean,
  ) {
    if (page !in 1..604 || !spec.isValid) return

    if (!forceReload) {
      val cached = pageBitmapRenderer.getCached(page, spec)
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
        return
      }
      val existing = _pagesState.value[page]
      if (existing is PageLoadState.Loading && pageJobs[page]?.isActive == true) return
    }

    pageJobs[page]?.cancel()
    pageJobs[page] = viewModelScope.launch {
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

          val fileRes = pdfMushafRepository.ensurePdfReadyForReader(entry)
          val pdfFile = fileRes.getOrElse { err ->
            _pagesState.update {
              it + (page to PageLoadState.Error(err.localizedMessage ?: "تعذر تحميل صفحة المصحف PDF"))
            }
            return@launch
          }

          if (activeRenderSpec != spec || !pageLruOrder.contains(page)) return@launch

          val headerMeta = quranRepository.getExistingPageHeaderMeta(page)
          val (bodyW, bodyH) = pageBitmapRenderer.computePdfBodySizePx(spec, page)
          val pdfBitmap = pdfRendererBridge.renderPage(
            mushafId = entry.id,
            pdfFile = pdfFile,
            pageNumber = page,
            targetWidthPx = bodyW,
            targetHeightPx = bodyH,
          )

          if (activeRenderSpec != spec || !pageLruOrder.contains(page)) {
            pdfBitmap.recycle()
            return@launch
          }

          val surahsMap = cachedSurahsById.ifEmpty {
            quranRepository.getSurahs().getOrDefault(emptyList()).associateBy { it.id }.also {
              if (it.isNotEmpty()) cachedSurahsById = it
            }
          }

          val rendered = try {
            pageBitmapRenderer.renderPdfPageOrGet(
              pageNumber = page,
              headerMeta = headerMeta,
              pdfBitmap = pdfBitmap,
              spec = spec,
              surahsById = surahsMap,
            )
          } finally {
            pdfBitmap.recycle()
          }

          if (activeRenderSpec != spec || !pageLruOrder.contains(page)) return@launch

          _pagesState.update {
            it + (page to PageLoadState.Ready(
              renderedPage = rendered,
              effectiveDark = false,
              edition = spec.edition,
            ))
          }
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          _pagesState.update {
            it + (page to PageLoadState.Error(e.localizedMessage ?: "تعذر عرض الصفحة $page"))
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
