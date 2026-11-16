package com.example.viewmodel

import android.app.Application
import android.graphics.Typeface
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.audio.AudioCacheManager
import com.example.data.audio.AudioRepository
import com.example.data.audio.RecitationRange
import com.example.data.audio.VerifiedReciter
import com.example.data.integrity.AssetHashStatus
import com.example.data.integrity.VerifiedDiskCache
import com.example.data.prefs.BackgroundTheme
import com.example.data.prefs.PersistedReaderState
import com.example.data.prefs.ReaderPreferences
import com.example.data.quran.CreditSourceInfo
import com.example.data.quran.JuzMeta
import com.example.data.quran.MushafEdition
import com.example.data.quran.MushafPageData
import com.example.data.quran.PageFontManager
import com.example.data.quran.QuranRepository
import com.example.data.quran.SurahMeta
import com.example.data.quran.TajweedRuleInfo
import com.example.data.quran.VerseInsightsData
import com.example.data.quran.VerseInsightsRepository
import com.example.data.quran.VerseSearchHit
import com.example.player.AudioPlaybackUiState
import com.example.player.PlaybackController
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PageLoadState {
  data object Loading : PageLoadState
  data class Ready(
    val pageData: MushafPageData,
    val pageTypeface: Typeface?,
    val basmalahTypeface: Typeface?,
    val usesTagFallbackTajweed: Boolean,
  ) : PageLoadState
  data class Error(val message: String) : PageLoadState
}

sealed interface VerseInsightsUiState {
  data object Hidden : VerseInsightsUiState
  data class Loading(val verseKey: String, val surahName: String, val ayahNumber: Int) : VerseInsightsUiState
  data class Ready(
    val insights: VerseInsightsData,
    val surahName: String,
    val pageTypeface: Typeface?,
  ) : VerseInsightsUiState
  data class Error(val verseKey: String, val message: String) : VerseInsightsUiState
}

data class ReaderUiState(
  val currentPage: Int = 1,
  val mushafEdition: MushafEdition = MushafEdition.MADINAH_1421,
  val isDarkMode: Boolean = false,
  val backgroundTheme: BackgroundTheme = BackgroundTheme.WHITE,
  val bookmarks: List<Int> = emptyList(),
  val surahs: List<SurahMeta> = emptyList(),
  val juzs: List<JuzMeta> = emptyList(),
  val pages: Map<Int, PageLoadState> = emptyMap(),
  val primaryReciters: List<VerifiedReciter> = emptyList(),
  val additionalReciters: List<VerifiedReciter> = emptyList(),
  val recitersLoading: Boolean = true,
  val recitersError: String? = null,
  val tajweedRules: List<TajweedRuleInfo> = emptyList(),
  val credits: List<CreditSourceInfo> = emptyList(),
  val assetHashes: List<AssetHashStatus> = emptyList(),
  val selectedVerseKey: String? = null,
  val verseInsights: VerseInsightsUiState = VerseInsightsUiState.Hidden,
  val verseSearchResults: List<VerseSearchHit> = emptyList(),
  val verseSearchLoading: Boolean = false,
  val verseSearchError: String? = null,
  val metadataError: String? = null,
)

class QabasatViewModel(application: Application) : AndroidViewModel(application) {
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

  private val _uiState = MutableStateFlow(ReaderUiState())
  val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

  val audioState: StateFlow<AudioPlaybackUiState> = playbackController.uiState
  val downloadedAudioKeys: StateFlow<Set<String>> = audioCacheManager.downloadedKeys
  val audioDownloadProgress: StateFlow<Map<String, Float>> = audioCacheManager.downloadProgress

  private val pageJobs = mutableMapOf<Int, Job>()
  private var searchJob: Job? = null

  init {
    playbackController.connect(
      onSurahEnded = { playbackController.nextSurah() },
      onActiveVerseChanged = { verseKey ->
        if (verseKey != null) {
          followPlayingVersePage(verseKey)
        }
      },
    )
    bootstrap()
  }

  private fun followPlayingVersePage(verseKey: String) {
    val pagesMap = _uiState.value.pages
    val currentPageNum = _uiState.value.currentPage
    val loadedCurrent = pagesMap[currentPageNum]
    if (loadedCurrent is PageLoadState.Ready) {
      val onCurrentPage = loadedCurrent.pageData.lines.any { line ->
        line is com.example.data.quran.MushafLine.AyahWordsLine &&
          line.words.any { it.verseKey == verseKey }
      }
      if (onCurrentPage) return
    }
    val cachedTargetPage = pagesMap.entries.firstOrNull { (_, state) ->
      state is PageLoadState.Ready && state.pageData.lines.any { line ->
        line is com.example.data.quran.MushafLine.AyahWordsLine &&
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
          bookmarks = saved.bookmarks,
        )
      }

      launch {
        preferences.stateFlow.collect { pref ->
          val prevDark = _uiState.value.isDarkMode
          val darkChanged = pref.isDarkMode != prevDark
          val activeEdition = _uiState.value.mushafEdition
          val activePage = _uiState.value.currentPage
          _uiState.update {
            it.copy(
              bookmarks = pref.bookmarks,
              isDarkMode = pref.isDarkMode,
              backgroundTheme = pref.backgroundTheme,
              pages = if (darkChanged && activeEdition == MushafEdition.TAJWEED_V4) emptyMap() else it.pages,
            )
          }
          if (darkChanged && activeEdition == MushafEdition.TAJWEED_V4) {
            loadPageWindow(activePage, MushafEdition.TAJWEED_V4, useWhiteInk = pref.isDarkMode)
          }
        }
      }

      launch {
        val hashes = quranRepository.verifyBundledAssets()
        val legend = quranRepository.loadTajweedLegend()
        val credits = quranRepository.loadCredits()
        _uiState.update {
          it.copy(
            assetHashes = hashes,
            tajweedRules = legend,
            credits = credits,
          )
        }
      }

      val surahsRes = quranRepository.getSurahs()
      val juzsRes = quranRepository.getJuzs()
      val surahs = surahsRes.getOrDefault(emptyList())
      val juzs = juzsRes.getOrDefault(emptyList())

      if (surahs.isNotEmpty()) {
        playbackController.updateSurahNames(surahs.associate { it.id to it.nameArabic })
      }

      _uiState.update {
        it.copy(
          surahs = surahs,
          juzs = juzs,
          metadataError = surahsRes.exceptionOrNull()?.localizedMessage,
        )
      }

      loadPageWindow(saved.lastPage, saved.mushafEdition, useWhiteInk = saved.isDarkMode)
      loadReciters(saved)
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
    if (clamped == _uiState.value.currentPage) return
    _uiState.update { it.copy(currentPage = clamped) }
    viewModelScope.launch { preferences.saveLastPage(clamped) }
    loadPageWindow(clamped, _uiState.value.mushafEdition, _uiState.value.isDarkMode)
  }

  fun jumpToPage(page: Int) {
    val clamped = page.coerceIn(1, 604)
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
    _uiState.update { it.copy(mushafEdition = edition, pages = emptyMap()) }
    viewModelScope.launch { preferences.setMushafEdition(edition) }
    loadPageWindow(_uiState.value.currentPage, edition, _uiState.value.isDarkMode)
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

  private fun applyThemeChange(nextDark: Boolean, nextBg: BackgroundTheme) {
    val state = _uiState.value
    val darkChanged = state.isDarkMode != nextDark
    _uiState.update {
      it.copy(
        isDarkMode = nextDark,
        backgroundTheme = nextBg,
        pages = if (darkChanged && it.mushafEdition == MushafEdition.TAJWEED_V4) emptyMap() else it.pages,
      )
    }
    if (darkChanged && state.mushafEdition == MushafEdition.TAJWEED_V4) {
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

  private fun loadPageWindow(
    centerPage: Int,
    edition: MushafEdition,
    useWhiteInk: Boolean = _uiState.value.isDarkMode,
  ) {
    val window = ((centerPage - 1)..(centerPage + 2)).filter { it in 1..604 }.toSet()
    _uiState.update { state ->
      val pruned = state.pages.filterKeys { it in window }
      state.copy(pages = pruned)
    }
    for (p in window) {
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
    val existing = _uiState.value.pages[page]
    if (!forceReload && existing is PageLoadState.Ready) return

    pageJobs[page]?.cancel()
    pageJobs[page] = viewModelScope.launch {
      _uiState.update { it.copy(pages = it.pages + (page to PageLoadState.Loading)) }

      val needsTagFallbackTajweed =
        edition == MushafEdition.TAJWEED_V4 && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

      val pageRes = quranRepository.getPage(page, includeTajweedFallback = needsTagFallbackTajweed)
      if (pageRes.isFailure) {
        _uiState.update {
          it.copy(
            pages = it.pages + (page to PageLoadState.Error(
              pageRes.exceptionOrNull()?.localizedMessage ?: "تعذر تحميل الصفحة $page",
            )),
          )
        }
        return@launch
      }

      val pageData = pageRes.getOrThrow()
      val fontEdition = if (needsTagFallbackTajweed) MushafEdition.MADINAH_1421 else edition
      val pageTypeface = fontManager.loadPageFont(page, fontEdition, useWhiteInk = useWhiteInk)
      val basmalahTypeface = fontManager.loadBasmalahFont(fontEdition, useWhiteInk = useWhiteInk)

      if (_uiState.value.mushafEdition != edition) return@launch
      if (edition == MushafEdition.TAJWEED_V4 && _uiState.value.isDarkMode != useWhiteInk) return@launch

      if (pageTypeface == null && !needsTagFallbackTajweed) {
        _uiState.update {
          it.copy(
            pages = it.pages + (page to PageLoadState.Error("تعذر تحميل خط الصفحة العثماني")),
          )
        }
        return@launch
      }

      _uiState.update {
        it.copy(
          pages = it.pages + (page to PageLoadState.Ready(
            pageData = pageData,
            pageTypeface = pageTypeface,
            basmalahTypeface = basmalahTypeface,
            usesTagFallbackTajweed = needsTagFallbackTajweed,
          )),
        )
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

    _uiState.update {
      it.copy(
        selectedVerseKey = verseKey,
        verseInsights = VerseInsightsUiState.Loading(verseKey, surahName, ayahNumber),
      )
    }

    viewModelScope.launch {
      val pageFont = fontManager.loadPageFont(pageNumber, edition, useWhiteInk = useWhiteInk)
        ?: fontManager.loadPageFont(pageNumber, MushafEdition.MADINAH_1421, useWhiteInk = false)
      val res = verseInsightsRepository.getVerseInsights(
        verseKey = verseKey,
        pageNumber = pageNumber,
        codeV2Line = verseCodeV2Line,
      )
      res.onSuccess { insights ->
        _uiState.update {
          it.copy(
            verseInsights = VerseInsightsUiState.Ready(
              insights = insights,
              surahName = surahName,
              pageTypeface = pageFont,
            ),
          )
        }
      }.onFailure { err ->
        _uiState.update {
          it.copy(
            verseInsights = VerseInsightsUiState.Error(
              verseKey = verseKey,
              message = err.localizedMessage ?: "تعذر تحميل التفسير والترجمات",
            ),
          )
        }
      }
    }
  }

  fun dismissVerseInsights() {
    _uiState.update {
      it.copy(
        selectedVerseKey = null,
        verseInsights = VerseInsightsUiState.Hidden,
      )
    }
  }

  fun selectReciter(reciter: VerifiedReciter) {
    if (!reciter.isPlayable) return
    val currentSurah = audioState.value.currentSurahId
    val surahToPlay = if (reciter.availableSurahs.contains(currentSurah)) {
      currentSurah
    } else {
      reciter.availableSurahs.minOrNull() ?: 1
    }
    playbackController.playSurah(reciter, surahToPlay, 0L, autoPlay = audioState.value.isPlaying)
  }

  fun playSurah(surahId: Int) {
    val reciter = audioState.value.currentReciter
      ?: (_uiState.value.primaryReciters + _uiState.value.additionalReciters).firstOrNull { it.isPlayable }
      ?: return
    playbackController.playSurah(reciter, surahId, 0L, autoPlay = true, clearActiveRange = true)
  }

  fun playAyahRange(range: RecitationRange) {
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
    followPlayingVersePage("$surahId:$ayahNumber")
    playbackController.playFromAyah(surahId, ayahNumber, endAyahNumber = ayahNumber)
  }

  fun playFromAyah(surahId: Int, ayahNumber: Int) {
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

  override fun onCleared() {
    playbackController.release()
    super.onCleared()
  }
}
