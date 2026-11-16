package com.example.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.quran.MushafEdition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "qabasat_reader_prefs")

enum class BackgroundTheme {
  WHITE,
  BLACK,
}

enum class PageViewMode {
  AUTO,
  SINGLE,
  TWO_PAGES,
}

data class PersistedReaderState(
  val lastPage: Int = 1,
  val bookmarks: List<Int> = emptyList(),
  val mushafEdition: MushafEdition = MushafEdition.MADINAH_1421,
  val isDarkMode: Boolean = false,
  val backgroundTheme: BackgroundTheme = BackgroundTheme.WHITE,
  val pageViewMode: PageViewMode = PageViewMode.AUTO,
  val selectedReciterKey: String? = null,
  val lastSurahId: Int = 1,
  val lastPositionMs: Long = 0L,
  val playbackSpeed: Float = 1.0f,
)

class ReaderPreferences(private val context: Context) {
  private object Keys {
    val LAST_PAGE = intPreferencesKey("last_page")
    val BOOKMARKS = stringSetPreferencesKey("bookmarks")
    val MUSHAF_EDITION = stringPreferencesKey("mushaf_edition")
    val DARK_MODE = booleanPreferencesKey("dark_mode_override")
    val BACKGROUND_THEME = stringPreferencesKey("background_theme")
    val PAGE_VIEW_MODE = stringPreferencesKey("page_view_mode")
    val SELECTED_RECITER_KEY = stringPreferencesKey("selected_reciter_key")
    val LAST_SURAH_ID = intPreferencesKey("last_surah_id")
    val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
    val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
  }

  val stateFlow: Flow<PersistedReaderState> = context.dataStore.data.map { prefs ->
    val editionName = prefs[Keys.MUSHAF_EDITION] ?: MushafEdition.MADINAH_1421.name
    val edition = runCatching { MushafEdition.valueOf(editionName) }
      .getOrDefault(MushafEdition.MADINAH_1421)

    val isDark = prefs[Keys.DARK_MODE] ?: false
    val bgName = prefs[Keys.BACKGROUND_THEME] ?: if (isDark) BackgroundTheme.BLACK.name else BackgroundTheme.WHITE.name
    val bgTheme = runCatching { BackgroundTheme.valueOf(bgName) }
      .getOrDefault(if (isDark) BackgroundTheme.BLACK else BackgroundTheme.WHITE)

    val pageModeName = prefs[Keys.PAGE_VIEW_MODE] ?: PageViewMode.AUTO.name
    val pageViewMode = runCatching { PageViewMode.valueOf(pageModeName) }
      .getOrDefault(PageViewMode.AUTO)

    val bookmarks = prefs[Keys.BOOKMARKS]
      .orEmpty()
      .mapNotNull { it.toIntOrNull() }
      .filter { it in 1..604 }
      .sorted()

    PersistedReaderState(
      lastPage = (prefs[Keys.LAST_PAGE] ?: 1).coerceIn(1, 604),
      bookmarks = bookmarks,
      mushafEdition = edition,
      isDarkMode = isDark,
      backgroundTheme = bgTheme,
      pageViewMode = pageViewMode,
      selectedReciterKey = prefs[Keys.SELECTED_RECITER_KEY],
      lastSurahId = (prefs[Keys.LAST_SURAH_ID] ?: 1).coerceIn(1, 114),
      lastPositionMs = (prefs[Keys.LAST_POSITION_MS] ?: 0L).coerceAtLeast(0L),
      playbackSpeed = (prefs[Keys.PLAYBACK_SPEED] ?: 1.0f).coerceIn(0.75f, 1.25f),
    )
  }

  suspend fun saveLastPage(page: Int) {
    context.dataStore.edit { it[Keys.LAST_PAGE] = page.coerceIn(1, 604) }
  }

  suspend fun toggleBookmark(page: Int) {
    context.dataStore.edit { prefs ->
      val current = prefs[Keys.BOOKMARKS].orEmpty().toMutableSet()
      val key = page.toString()
      if (current.contains(key)) current.remove(key) else current.add(key)
      prefs[Keys.BOOKMARKS] = current
    }
  }

  suspend fun removeBookmark(page: Int) {
    context.dataStore.edit { prefs ->
      val current = prefs[Keys.BOOKMARKS].orEmpty().toMutableSet()
      current.remove(page.toString())
      prefs[Keys.BOOKMARKS] = current
    }
  }

  suspend fun setMushafEdition(edition: MushafEdition) {
    context.dataStore.edit { it[Keys.MUSHAF_EDITION] = edition.name }
  }

  suspend fun setDarkMode(isDark: Boolean) {
    context.dataStore.edit {
      it[Keys.DARK_MODE] = isDark
      it[Keys.BACKGROUND_THEME] = if (isDark) BackgroundTheme.BLACK.name else BackgroundTheme.WHITE.name
    }
  }

  suspend fun setBackgroundTheme(theme: BackgroundTheme) {
    context.dataStore.edit {
      it[Keys.BACKGROUND_THEME] = theme.name
      it[Keys.DARK_MODE] = theme == BackgroundTheme.BLACK
    }
  }

  suspend fun setPageViewMode(mode: PageViewMode) {
    context.dataStore.edit {
      it[Keys.PAGE_VIEW_MODE] = mode.name
    }
  }

  suspend fun saveAudioPlaybackState(
    reciterKey: String? = null,
    surahId: Int? = null,
    positionMs: Long? = null,
    speed: Float? = null,
  ) {
    context.dataStore.edit { prefs ->
      reciterKey?.let { prefs[Keys.SELECTED_RECITER_KEY] = it }
      surahId?.let { prefs[Keys.LAST_SURAH_ID] = it.coerceIn(1, 114) }
      positionMs?.let { prefs[Keys.LAST_POSITION_MS] = it.coerceAtLeast(0L) }
      speed?.let { prefs[Keys.PLAYBACK_SPEED] = it.coerceIn(0.75f, 1.25f) }
    }
  }
}
