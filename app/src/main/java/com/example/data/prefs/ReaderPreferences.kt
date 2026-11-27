package com.example.data.prefs

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.prayer.AdhanSettingsState
import com.example.data.prayer.AsrMadhab
import com.example.data.prayer.CachedPrayerSchedule
import com.example.data.prayer.DailyPrayerTimesRecord
import com.example.data.prayer.PrayerAlarmConfig
import com.example.data.prayer.PrayerCalculationMethod
import com.example.data.prayer.PrayerHighLatitudeRule
import com.example.data.prayer.PrayerMinuteAdjustments
import com.example.data.prayer.PrayerSettingsState
import com.example.data.prayer.PrayerType
import com.example.data.prayer.SavedPrayerLocation
import com.example.data.quran.MushafEdition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

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

@Immutable
data class AyahBookmark(
  val surahId: Int,
  val ayahNumber: Int,
  val pageNumber: Int,
) {
  val verseKey: String
    get() = "$surahId:$ayahNumber"
}

@Immutable
data class PersistedReaderState(
  val lastPage: Int = 1,
  val bookmarks: List<Int> = emptyList(),
  val ayahBookmarks: List<AyahBookmark> = emptyList(),
  val mushafEdition: MushafEdition = MushafEdition.MADINAH_1421,
  val isDarkMode: Boolean = false,
  val backgroundTheme: BackgroundTheme = BackgroundTheme.WHITE,
  val pageViewMode: PageViewMode = PageViewMode.AUTO,
  val selectedReciterKey: String? = null,
  val lastSurahId: Int = 1,
  val lastPositionMs: Long = 0L,
  val playbackSpeed: Float = 1.0f,
  val downloadedMushafIds: Set<String> = emptySet(),
  val activeMushafId: String = "madinah_1421",
  val layoutCacheVersion: Int = 1,
  val betaChannelEnabled: Boolean = false,
  val betaChannelUnlocked: Boolean = false,
  val activePdfMushafId: String? = null,
  val activeMushafIsPdf: Boolean = false,
  val globalFontSizes: Map<String, CachedGlobalFontSizes> = emptyMap(),
  val checkedTafsirEditionIds: Set<String> = emptySet(),
  val reciterPreferredMoshafIds: Map<Int, Int> = emptyMap(),
)

@Immutable
data class CachedGlobalFontSizes(
  val introSp: Float,
  val bodySp: Float,
)

class ReaderPreferences(private val context: Context) {
  private object Keys {
    val LAST_PAGE = intPreferencesKey("last_page")
    val BOOKMARKS = stringSetPreferencesKey("bookmarks")
    val AYAH_BOOKMARKS = stringSetPreferencesKey("ayah_bookmarks")
    val MUSHAF_EDITION = stringPreferencesKey("mushaf_edition")
    val DARK_MODE = booleanPreferencesKey("dark_mode_override")
    val BACKGROUND_THEME = stringPreferencesKey("background_theme")
    val PAGE_VIEW_MODE = stringPreferencesKey("page_view_mode")
    val SELECTED_RECITER_KEY = stringPreferencesKey("selected_reciter_key")
    val LAST_SURAH_ID = intPreferencesKey("last_surah_id")
    val LAST_POSITION_MS = longPreferencesKey("last_position_ms")
    val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
    val DOWNLOADED_MUSHAF_IDS = stringSetPreferencesKey("downloaded_mushaf_ids")
    val ACTIVE_MUSHAF_ID = stringPreferencesKey("active_mushaf_id")
    val LAYOUT_CACHE_VERSION = intPreferencesKey("layout_cache_version")
    val BETA_CHANNEL_ENABLED = booleanPreferencesKey("beta_channel_enabled")
    val BETA_CHANNEL_UNLOCKED = booleanPreferencesKey("beta_channel_unlocked")
    val ACTIVE_PDF_MUSHAF_ID = stringPreferencesKey("active_pdf_mushaf_id")
    val ACTIVE_MUSHAF_IS_PDF = booleanPreferencesKey("active_mushaf_is_pdf")
    val GLOBAL_FONT_SIZES = stringPreferencesKey("global_mushaf_font_sizes")
    val PRAYER_LOCATION_JSON = stringPreferencesKey("prayer_location_json")
    val PRAYER_CALCULATION_METHOD = stringPreferencesKey("prayer_calculation_method")
    val PRAYER_METHOD_USER_SELECTED = booleanPreferencesKey("prayer_method_user_selected")
    val PRAYER_ASR_MADHAB = stringPreferencesKey("prayer_asr_madhab")
    val PRAYER_HIGH_LAT_RULE = stringPreferencesKey("prayer_high_lat_rule")
    val PRAYER_ADJUSTMENTS_JSON = stringPreferencesKey("prayer_adjustments_json")
    val PRAYER_SCHEDULE_CACHE_JSON = stringPreferencesKey("prayer_schedule_cache_json")
    val PRAYER_CHECKED_DATE_KEY = stringPreferencesKey("prayer_checked_date_key")
    val PRAYER_CHECKED_KEYS = stringSetPreferencesKey("prayer_checked_keys")
    val CHECKED_TAFSIR_EDITION_IDS = stringSetPreferencesKey("checked_tafsir_edition_ids")
    val RECITER_PREFERRED_MOSHAF_JSON = stringPreferencesKey("reciter_preferred_moshaf_json")
    val ADHAN_ALARMS_ENABLED = booleanPreferencesKey("adhan_alarms_enabled")
    val ADHAN_PERSISTENT_COUNTDOWN_ENABLED = booleanPreferencesKey("adhan_persistent_countdown_enabled")
    val ADHAN_PER_PRAYER_CONFIG_JSON = stringPreferencesKey("adhan_per_prayer_config_json")
  }

  data class PersistedPrayerPreferences(
    val settings: PrayerSettingsState = PrayerSettingsState(),
    val adhanSettings: AdhanSettingsState = AdhanSettingsState(),
    val cachedSchedule: CachedPrayerSchedule? = null,
    val checkedDateKey: String = "",
    val checkedPrayerKeys: Set<String> = emptySet(),
  )

  val prayerStateFlow: Flow<PersistedPrayerPreferences> = context.dataStore.data.map { prefs ->
    val location = decodePrayerLocation(prefs[Keys.PRAYER_LOCATION_JSON])
    val userSelectedMethod = prefs[Keys.PRAYER_METHOD_USER_SELECTED] ?: false
    val savedMethod = prefs[Keys.PRAYER_CALCULATION_METHOD]?.let { name ->
      runCatching { PrayerCalculationMethod.valueOf(name) }.getOrNull()
    }
    val effectiveMethod = savedMethod ?: if (location?.countryCode.equals("EG", ignoreCase = true)) {
      PrayerCalculationMethod.EGYPTIAN
    } else {
      PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE
    }
    val madhab = prefs[Keys.PRAYER_ASR_MADHAB]?.let { name ->
      runCatching { AsrMadhab.valueOf(name) }.getOrNull()
    } ?: AsrMadhab.SHAFI
    val highLat = prefs[Keys.PRAYER_HIGH_LAT_RULE]?.let { name ->
      runCatching { PrayerHighLatitudeRule.valueOf(name) }.getOrNull()
    } ?: PrayerHighLatitudeRule.MIDDLE_OF_THE_NIGHT
    val adjustments = decodePrayerAdjustments(prefs[Keys.PRAYER_ADJUSTMENTS_JSON])
    val adhanSettings = AdhanSettingsState(
      alarmsEnabled = prefs[Keys.ADHAN_ALARMS_ENABLED] ?: true,
      persistentCountdownEnabled = prefs[Keys.ADHAN_PERSISTENT_COUNTDOWN_ENABLED] ?: true,
      perPrayerConfig = AdhanSettingsState.decodeConfigsJson(prefs[Keys.ADHAN_PER_PRAYER_CONFIG_JSON]),
    )

    PersistedPrayerPreferences(
      settings = PrayerSettingsState(
        location = location,
        calculationMethod = effectiveMethod,
        isMethodUserSelected = userSelectedMethod,
        asrMadhab = madhab,
        highLatitudeRule = highLat,
        adjustments = adjustments,
      ),
      adhanSettings = adhanSettings,
      cachedSchedule = decodeCachedPrayerSchedule(prefs[Keys.PRAYER_SCHEDULE_CACHE_JSON]),
      checkedDateKey = prefs[Keys.PRAYER_CHECKED_DATE_KEY].orEmpty(),
      checkedPrayerKeys = prefs[Keys.PRAYER_CHECKED_KEYS].orEmpty(),
    )
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

    val ayahBookmarks = decodeAyahBookmarks(prefs[Keys.AYAH_BOOKMARKS].orEmpty())

    val downloadedIds = prefs[Keys.DOWNLOADED_MUSHAF_IDS] ?: emptySet()
    val activeId = prefs[Keys.ACTIVE_MUSHAF_ID] ?: (downloadedIds.firstOrNull() ?: "madinah_1421")
    val pdfId = prefs[Keys.ACTIVE_PDF_MUSHAF_ID]?.takeIf { it.isNotBlank() }
    val isPdf = (prefs[Keys.ACTIVE_MUSHAF_IS_PDF] ?: false) && pdfId != null

    PersistedReaderState(
      lastPage = (prefs[Keys.LAST_PAGE] ?: 1).coerceIn(1, 604),
      bookmarks = bookmarks,
      ayahBookmarks = ayahBookmarks,
      mushafEdition = edition,
      isDarkMode = isDark,
      backgroundTheme = bgTheme,
      pageViewMode = pageViewMode,
      selectedReciterKey = prefs[Keys.SELECTED_RECITER_KEY],
      lastSurahId = (prefs[Keys.LAST_SURAH_ID] ?: 1).coerceIn(1, 114),
      lastPositionMs = (prefs[Keys.LAST_POSITION_MS] ?: 0L).coerceAtLeast(0L),
      playbackSpeed = (prefs[Keys.PLAYBACK_SPEED] ?: 1.0f).coerceIn(0.75f, 1.25f),
      downloadedMushafIds = downloadedIds,
      activeMushafId = activeId,
      layoutCacheVersion = prefs[Keys.LAYOUT_CACHE_VERSION] ?: 1,
      betaChannelEnabled = prefs[Keys.BETA_CHANNEL_ENABLED] ?: false,
      betaChannelUnlocked = (prefs[Keys.BETA_CHANNEL_UNLOCKED] ?: false) || (prefs[Keys.BETA_CHANNEL_ENABLED] ?: false),
      activePdfMushafId = pdfId,
      activeMushafIsPdf = isPdf,
      globalFontSizes = decodeGlobalFontSizes(prefs[Keys.GLOBAL_FONT_SIZES]),
      checkedTafsirEditionIds = prefs[Keys.CHECKED_TAFSIR_EDITION_IDS].orEmpty(),
      reciterPreferredMoshafIds = decodeReciterPreferredMoshafs(prefs[Keys.RECITER_PREFERRED_MOSHAF_JSON]),
    )
  }

  private fun decodeGlobalFontSizes(raw: String?): Map<String, CachedGlobalFontSizes> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
      val root = JSONObject(raw)
      buildMap {
        val keys = root.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          val obj = root.optJSONObject(k) ?: continue
          val intro = obj.optDouble("intro", Double.NaN).toFloat()
          val body = obj.optDouble("body", Double.NaN).toFloat()
          if (intro.isFinite() && intro > 0f && body.isFinite() && body > 0f) {
            put(k, CachedGlobalFontSizes(introSp = intro, bodySp = body))
          }
        }
      }
    }.getOrDefault(emptyMap())
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

  private fun decodeAyahBookmarks(raw: Set<String>): List<AyahBookmark> {
    if (raw.isEmpty()) return emptyList()
    return raw.mapNotNull { entry ->
      val mainAndPage = entry.split("@")
      val versePart = mainAndPage.getOrNull(0) ?: return@mapNotNull null
      val page = mainAndPage.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 604) ?: 1
      val sp = versePart.split(":")
      val surah = sp.getOrNull(0)?.toIntOrNull()?.takeIf { it in 1..114 } ?: return@mapNotNull null
      val ayah = sp.getOrNull(1)?.toIntOrNull()?.takeIf { it >= 1 } ?: return@mapNotNull null
      AyahBookmark(surahId = surah, ayahNumber = ayah, pageNumber = page)
    }.distinctBy { it.verseKey }
      .sortedWith(compareBy({ it.surahId }, { it.ayahNumber }))
  }

  private fun encodeAyahBookmarks(items: List<AyahBookmark>): Set<String> {
    return items.distinctBy { it.verseKey }
      .map { "${it.surahId}:${it.ayahNumber}@${it.pageNumber.coerceIn(1, 604)}" }
      .toSet()
  }

  suspend fun toggleAyahBookmarks(items: List<AyahBookmark>): Boolean {
    if (items.isEmpty()) return false
    var added = false
    context.dataStore.edit { prefs ->
      val existing = decodeAyahBookmarks(prefs[Keys.AYAH_BOOKMARKS].orEmpty())
        .associateBy { it.verseKey }
        .toMutableMap()
      val allPresent = items.all { existing.containsKey(it.verseKey) }
      if (allPresent) {
        for (item in items) existing.remove(item.verseKey)
        added = false
      } else {
        for (item in items) existing[item.verseKey] = item
        added = true
      }
      prefs[Keys.AYAH_BOOKMARKS] = encodeAyahBookmarks(existing.values.toList())
    }
    return added
  }

  suspend fun removeAyahBookmark(surahId: Int, ayahNumber: Int) {
    val targetKey = "$surahId:$ayahNumber"
    context.dataStore.edit { prefs ->
      val existing = decodeAyahBookmarks(prefs[Keys.AYAH_BOOKMARKS].orEmpty())
        .filterNot { it.verseKey == targetKey }
      prefs[Keys.AYAH_BOOKMARKS] = encodeAyahBookmarks(existing)
    }
  }

  suspend fun setMushafEdition(edition: MushafEdition) {
    context.dataStore.edit {
      it[Keys.MUSHAF_EDITION] = edition.name
      it[Keys.ACTIVE_MUSHAF_IS_PDF] = false
    }
  }

  suspend fun addDownloadedMushaf(id: String) {
    context.dataStore.edit { prefs ->
      val current = prefs[Keys.DOWNLOADED_MUSHAF_IDS].orEmpty().toMutableSet()
      current.add(id)
      prefs[Keys.DOWNLOADED_MUSHAF_IDS] = current
      if (prefs[Keys.ACTIVE_MUSHAF_ID].isNullOrBlank()) {
        prefs[Keys.ACTIVE_MUSHAF_ID] = id
      }
    }
  }

  suspend fun removeDownloadedMushaf(id: String) {
    context.dataStore.edit { prefs ->
      val current = prefs[Keys.DOWNLOADED_MUSHAF_IDS].orEmpty().toMutableSet()
      if (current.size > 1 && current.contains(id)) {
        current.remove(id)
        prefs[Keys.DOWNLOADED_MUSHAF_IDS] = current
        val fallback = current.firstOrNull { it == "madinah_1421" } ?: current.first()
        if (prefs[Keys.ACTIVE_MUSHAF_ID] == id || prefs[Keys.ACTIVE_MUSHAF_ID].isNullOrBlank()) {
          prefs[Keys.ACTIVE_MUSHAF_ID] = fallback
        }
        val activeAfter = prefs[Keys.ACTIVE_MUSHAF_ID] ?: fallback
        if (id == "tajweed_v4" &&
          (prefs[Keys.MUSHAF_EDITION] == MushafEdition.TAJWEED_V4.name || activeAfter == "madinah_1421")
        ) {
          prefs[Keys.MUSHAF_EDITION] = if (activeAfter == "tajweed_v4") {
            MushafEdition.TAJWEED_V4.name
          } else {
            MushafEdition.MADINAH_1421.name
          }
        }
      }
    }
  }

  suspend fun setActiveMushaf(id: String) {
    context.dataStore.edit { prefs ->
      val current = prefs[Keys.DOWNLOADED_MUSHAF_IDS].orEmpty()
      if (current.contains(id)) {
        prefs[Keys.ACTIVE_MUSHAF_ID] = id
        prefs[Keys.ACTIVE_MUSHAF_IS_PDF] = false
      }
    }
  }

  suspend fun setActiveTextMushaf(id: String, edition: MushafEdition) {
    context.dataStore.edit { prefs ->
      prefs[Keys.ACTIVE_MUSHAF_ID] = id
      prefs[Keys.MUSHAF_EDITION] = edition.name
      prefs[Keys.ACTIVE_MUSHAF_IS_PDF] = false
    }
  }

  suspend fun setActivePdfMushaf(pdfMushafId: String) {
    context.dataStore.edit { prefs ->
      prefs[Keys.ACTIVE_PDF_MUSHAF_ID] = pdfMushafId
      prefs[Keys.ACTIVE_MUSHAF_IS_PDF] = true
    }
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

  suspend fun clearSelectedReciterKey() {
    context.dataStore.edit { prefs ->
      prefs.remove(Keys.SELECTED_RECITER_KEY)
      prefs.remove(Keys.LAST_POSITION_MS)
    }
  }

  suspend fun setLayoutCacheVersion(version: Int) {
    context.dataStore.edit { it[Keys.LAYOUT_CACHE_VERSION] = version }
  }

  suspend fun saveGlobalFontSizes(cacheKey: String, introSp: Float, bodySp: Float) {
    if (!introSp.isFinite() || introSp <= 0f || !bodySp.isFinite() || bodySp <= 0f) return
    context.dataStore.edit { prefs ->
      val current = decodeGlobalFontSizes(prefs[Keys.GLOBAL_FONT_SIZES]).toMutableMap()
      current[cacheKey] = CachedGlobalFontSizes(introSp = introSp, bodySp = bodySp)
      val out = JSONObject()
      for ((k, v) in current) {
        out.put(
          k,
          JSONObject().apply {
            put("intro", v.introSp.toDouble())
            put("body", v.bodySp.toDouble())
          },
        )
      }
      prefs[Keys.GLOBAL_FONT_SIZES] = out.toString()
    }
  }

  suspend fun clearGlobalFontSizes() {
    context.dataStore.edit { it.remove(Keys.GLOBAL_FONT_SIZES) }
  }

  suspend fun setBetaChannelEnabled(enabled: Boolean) {
    context.dataStore.edit { prefs ->
      prefs[Keys.BETA_CHANNEL_ENABLED] = enabled
      prefs[Keys.BETA_CHANNEL_UNLOCKED] = true
    }
  }

  suspend fun setBetaChannelUnlocked(unlocked: Boolean) {
    context.dataStore.edit { it[Keys.BETA_CHANNEL_UNLOCKED] = unlocked }
  }

  suspend fun savePrayerLocation(location: SavedPrayerLocation) {
    context.dataStore.edit { prefs ->
      val obj = JSONObject().apply {
        put("lat", location.latitude)
        put("lon", location.longitude)
        put("name", location.displayName)
        put("cc", location.countryCode.orEmpty())
        put("tz", location.timeZoneId)
        put("manual", location.isManual)
      }
      prefs[Keys.PRAYER_LOCATION_JSON] = obj.toString()
      val userSelected = prefs[Keys.PRAYER_METHOD_USER_SELECTED] ?: false
      if (!userSelected) {
        val autoMethod = if (location.countryCode.equals("EG", ignoreCase = true)) {
          PrayerCalculationMethod.EGYPTIAN
        } else {
          PrayerCalculationMethod.MUSLIM_WORLD_LEAGUE
        }
        prefs[Keys.PRAYER_CALCULATION_METHOD] = autoMethod.name
      }
    }
  }

  suspend fun savePrayerCalculationMethod(method: PrayerCalculationMethod, userSelected: Boolean) {
    context.dataStore.edit { prefs ->
      prefs[Keys.PRAYER_CALCULATION_METHOD] = method.name
      prefs[Keys.PRAYER_METHOD_USER_SELECTED] = userSelected
    }
  }

  suspend fun savePrayerAsrMadhab(madhab: AsrMadhab) {
    context.dataStore.edit { it[Keys.PRAYER_ASR_MADHAB] = madhab.name }
  }

  suspend fun savePrayerHighLatitudeRule(rule: PrayerHighLatitudeRule) {
    context.dataStore.edit { it[Keys.PRAYER_HIGH_LAT_RULE] = rule.name }
  }

  suspend fun savePrayerAdjustments(adjustments: PrayerMinuteAdjustments) {
    context.dataStore.edit { prefs ->
      val obj = JSONObject().apply {
        put("fajr", adjustments.fajr)
        put("sunrise", adjustments.sunrise)
        put("dhuhr", adjustments.dhuhr)
        put("asr", adjustments.asr)
        put("maghrib", adjustments.maghrib)
        put("isha", adjustments.isha)
      }
      prefs[Keys.PRAYER_ADJUSTMENTS_JSON] = obj.toString()
    }
  }

  suspend fun saveCachedPrayerSchedule(schedule: CachedPrayerSchedule) {
    context.dataStore.edit { prefs ->
      val daysArr = JSONArray()
      for (rec in schedule.daysByDateKey.values) {
        daysArr.put(
          JSONObject().apply {
            put("k", rec.dateKey)
            put("y", rec.year)
            put("m", rec.month)
            put("d", rec.day)
            put("f", rec.fajrEpochMs)
            put("s", rec.sunriseEpochMs)
            put("dh", rec.dhuhrEpochMs)
            put("a", rec.asrEpochMs)
            put("mg", rec.maghribEpochMs)
            put("i", rec.ishaEpochMs)
          },
        )
      }
      val root = JSONObject().apply {
        put("key", schedule.cacheKey)
        put("gen", schedule.generatedDateKey)
        put("days", daysArr)
      }
      prefs[Keys.PRAYER_SCHEDULE_CACHE_JSON] = root.toString()
    }
  }

  suspend fun togglePrayerCompleted(dateKey: String, prayerKey: String) {
    context.dataStore.edit { prefs ->
      val storedDate = prefs[Keys.PRAYER_CHECKED_DATE_KEY].orEmpty()
      val currentSet = if (storedDate == dateKey) {
        prefs[Keys.PRAYER_CHECKED_KEYS].orEmpty().toMutableSet()
      } else {
        mutableSetOf()
      }
      if (!currentSet.add(prayerKey)) {
        currentSet.remove(prayerKey)
      }
      prefs[Keys.PRAYER_CHECKED_DATE_KEY] = dateKey
      prefs[Keys.PRAYER_CHECKED_KEYS] = currentSet
    }
  }

  suspend fun markPrayerCompleted(dateKey: String, prayerKey: String) {
    context.dataStore.edit { prefs ->
      val storedDate = prefs[Keys.PRAYER_CHECKED_DATE_KEY].orEmpty()
      val currentSet = if (storedDate == dateKey) {
        prefs[Keys.PRAYER_CHECKED_KEYS].orEmpty().toMutableSet()
      } else {
        mutableSetOf()
      }
      currentSet.add(prayerKey)
      prefs[Keys.PRAYER_CHECKED_DATE_KEY] = dateKey
      prefs[Keys.PRAYER_CHECKED_KEYS] = currentSet
    }
  }

  suspend fun readPrayerPreferencesOnce(): PersistedPrayerPreferences = prayerStateFlow.first()

  suspend fun setAdhanAlarmsEnabled(enabled: Boolean) {
    context.dataStore.edit { it[Keys.ADHAN_ALARMS_ENABLED] = enabled }
  }

  suspend fun setPersistentCountdownEnabled(enabled: Boolean) {
    context.dataStore.edit { it[Keys.ADHAN_PERSISTENT_COUNTDOWN_ENABLED] = enabled }
  }

  suspend fun savePrayerAlarmConfig(prayerType: PrayerType, config: PrayerAlarmConfig) {
    context.dataStore.edit { prefs ->
      val current = AdhanSettingsState.decodeConfigsJson(prefs[Keys.ADHAN_PER_PRAYER_CONFIG_JSON]).toMutableMap()
      current[prayerType] = config
      prefs[Keys.ADHAN_PER_PRAYER_CONFIG_JSON] = AdhanSettingsState.encodeConfigsJson(current)
    }
  }

  suspend fun saveAllPrayersAlarmConfig(config: PrayerAlarmConfig, includeSunrise: Boolean = false) {
    context.dataStore.edit { prefs ->
      val current = AdhanSettingsState.decodeConfigsJson(prefs[Keys.ADHAN_PER_PRAYER_CONFIG_JSON]).toMutableMap()
      for (type in PrayerType.entries) {
        if (type == PrayerType.SUNRISE && !includeSunrise) continue
        current[type] = config
      }
      prefs[Keys.ADHAN_PER_PRAYER_CONFIG_JSON] = AdhanSettingsState.encodeConfigsJson(current)
    }
  }

  suspend fun setTafsirEditionChecked(editionId: String, checked: Boolean) {
    context.dataStore.edit { prefs ->
      val current = prefs[Keys.CHECKED_TAFSIR_EDITION_IDS].orEmpty().toMutableSet()
      if (checked) current.add(editionId) else current.remove(editionId)
      prefs[Keys.CHECKED_TAFSIR_EDITION_IDS] = current
    }
  }

  suspend fun saveReciterPreferredMoshaf(reciterId: Int, moshafId: Int) {
    if (reciterId <= 0 || moshafId <= 0) return
    context.dataStore.edit { prefs ->
      val current = decodeReciterPreferredMoshafs(prefs[Keys.RECITER_PREFERRED_MOSHAF_JSON]).toMutableMap()
      current[reciterId] = moshafId
      val root = JSONObject()
      for ((k, v) in current) {
        root.put(k.toString(), v)
      }
      prefs[Keys.RECITER_PREFERRED_MOSHAF_JSON] = root.toString()
    }
  }

  private fun decodeReciterPreferredMoshafs(raw: String?): Map<Int, Int> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
      val root = JSONObject(raw)
      buildMap {
        val keys = root.keys()
        while (keys.hasNext()) {
          val k = keys.next()
          val rId = k.toIntOrNull() ?: continue
          val mId = root.optInt(k, -1)
          if (rId > 0 && mId > 0) put(rId, mId)
        }
      }
    }.getOrDefault(emptyMap())
  }

  private fun decodePrayerLocation(raw: String?): SavedPrayerLocation? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
      val obj = JSONObject(raw)
      val lat = obj.optDouble("lat", Double.NaN)
      val lon = obj.optDouble("lon", Double.NaN)
      if (!lat.isFinite() || !lon.isFinite()) return null
      SavedPrayerLocation(
        latitude = lat,
        longitude = lon,
        displayName = obj.optString("name", ""),
        countryCode = obj.optString("cc", "").takeIf { it.isNotBlank() },
        timeZoneId = obj.optString("tz", "").ifBlank { java.util.TimeZone.getDefault().id },
        isManual = obj.optBoolean("manual", false),
      )
    }.getOrNull()
  }

  private fun decodePrayerAdjustments(raw: String?): PrayerMinuteAdjustments {
    if (raw.isNullOrBlank()) return PrayerMinuteAdjustments()
    return runCatching {
      val obj = JSONObject(raw)
      PrayerMinuteAdjustments(
        fajr = obj.optInt("fajr", 0).coerceIn(-60, 60),
        sunrise = obj.optInt("sunrise", 0).coerceIn(-60, 60),
        dhuhr = obj.optInt("dhuhr", 0).coerceIn(-60, 60),
        asr = obj.optInt("asr", 0).coerceIn(-60, 60),
        maghrib = obj.optInt("maghrib", 0).coerceIn(-60, 60),
        isha = obj.optInt("isha", 0).coerceIn(-60, 60),
      )
    }.getOrDefault(PrayerMinuteAdjustments())
  }

  private fun decodeCachedPrayerSchedule(raw: String?): CachedPrayerSchedule? {
    if (raw.isNullOrBlank()) return null
    return runCatching {
      val root = JSONObject(raw)
      val cacheKey = root.optString("key", "")
      val genKey = root.optString("gen", "")
      val daysArr = root.optJSONArray("days") ?: return null
      val map = LinkedHashMap<String, DailyPrayerTimesRecord>(daysArr.length())
      for (i in 0 until daysArr.length()) {
        val obj = daysArr.optJSONObject(i) ?: continue
        val k = obj.optString("k", "")
        if (k.isBlank()) continue
        map[k] = DailyPrayerTimesRecord(
          dateKey = k,
          year = obj.optInt("y"),
          month = obj.optInt("m"),
          day = obj.optInt("d"),
          fajrEpochMs = obj.optLong("f"),
          sunriseEpochMs = obj.optLong("s"),
          dhuhrEpochMs = obj.optLong("dh"),
          asrEpochMs = obj.optLong("a"),
          maghribEpochMs = obj.optLong("mg"),
          ishaEpochMs = obj.optLong("i"),
        )
      }
      if (cacheKey.isBlank() || map.isEmpty()) null
      else CachedPrayerSchedule(cacheKey = cacheKey, generatedDateKey = genKey, daysByDateKey = map)
    }.getOrNull()
  }

  @androidx.annotation.VisibleForTesting
  internal suspend fun resetForTest() {
    context.dataStore.edit { it.clear() }
  }
}
