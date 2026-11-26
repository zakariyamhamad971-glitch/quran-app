package com.example.viewmodel

import android.app.Application
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.prayer.AsrMadhab
import com.example.data.prayer.CachedPrayerSchedule
import com.example.data.prayer.DailyPrayerTimesRecord
import com.example.data.prayer.LocationLookupHelper
import com.example.data.prayer.LocationLookupResult
import com.example.data.prayer.NextPrayerTarget
import com.example.data.prayer.OfflineCityOption
import com.example.data.prayer.PrayerCalculationMethod
import com.example.data.prayer.PrayerFormatters
import com.example.data.prayer.PrayerHighLatitudeRule
import com.example.data.prayer.PrayerMinuteAdjustments
import com.example.data.prayer.PrayerSettingsState
import com.example.data.prayer.PrayerTimesRepository
import com.example.data.prayer.PrayerType
import com.example.data.prayer.SavedPrayerLocation
import com.example.data.prefs.ReaderPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.TimeZone

@Immutable
data class PrayerTimesUiState(
  val settings: PrayerSettingsState = PrayerSettingsState(),
  val selectedDayOffset: Int = 0, // -1 yesterday, 0 today, +1 tomorrow
  val todayRecord: DailyPrayerTimesRecord? = null,
  val displayedRecord: DailyPrayerTimesRecord? = null,
  val nextPrayer: NextPrayerTarget? = null,
  val isDaytime: Boolean = true,
  val gregorianDateText: String = "",
  val hijriDateText: String = "",
  val todayDateKey: String = "",
  val displayedDateKey: String = "",
  val checkedPrayerKeysToday: Set<String> = emptySet(),
  val isLoadingLocation: Boolean = false,
  val isComputingSchedule: Boolean = false,
  val locationPermissionDenied: Boolean = false,
  val locationUnavailable: Boolean = false,
  val hasLocationPermission: Boolean = false,
)

class PrayerTimesViewModel(application: Application) : AndroidViewModel(application) {
  private val preferences = ReaderPreferences(application)
  private val repository = PrayerTimesRepository()
  private val locationHelper = LocationLookupHelper(application)

  private val _uiState = MutableStateFlow(
    PrayerTimesUiState(
      hasLocationPermission = locationHelper.hasCoarseLocationPermission(),
    ),
  )
  val uiState: StateFlow<PrayerTimesUiState> = _uiState.asStateFlow()

  private var latestPrefs = ReaderPreferences.PersistedPrayerPreferences()
  private var inMemorySchedule: CachedPrayerSchedule? = null

  init {
    observePreferences()
    observePrayerRolloverTicker()
  }

  private fun observePreferences() {
    viewModelScope.launch {
      preferences.prayerStateFlow.collectLatest { persisted ->
        latestPrefs = persisted
        if (persisted.cachedSchedule != null &&
          persisted.cachedSchedule.cacheKey == persisted.settings.cacheKey()
        ) {
          inMemorySchedule = persisted.cachedSchedule
        }
        rebuildUiSnapshot(persisted)

        val loc = persisted.settings.location
        if (loc != null && needsScheduleRefresh(persisted.settings, inMemorySchedule)) {
          recomputeAndCache30Days(persisted.settings)
        }
      }
    }
  }

  private fun observePrayerRolloverTicker() {
    viewModelScope.launch {
      while (isActive) {
        val nextTargetMs = _uiState.value.nextPrayer?.targetEpochMs
        val now = System.currentTimeMillis()
        val waitMs = if (nextTargetMs != null && nextTargetMs > now) {
          (nextTargetMs - now + 150L).coerceAtMost(60_000L)
        } else {
          60_000L
        }
        delay(waitMs)
        rebuildUiSnapshot(latestPrefs)
      }
    }
  }

  fun selectDayOffset(offset: Int) {
    val clamped = offset.coerceIn(-1, 1)
    if (_uiState.value.selectedDayOffset == clamped) return
    _uiState.update { it.copy(selectedDayOffset = clamped) }
    viewModelScope.launch {
      rebuildUiSnapshot(latestPrefs)
    }
  }

  fun onLocationPermissionResult(granted: Boolean) {
    _uiState.update {
      it.copy(
        hasLocationPermission = granted,
        locationPermissionDenied = !granted,
      )
    }
    if (granted) {
      refreshCoarseLocation()
    }
  }

  fun refreshCoarseLocation() {
    if (_uiState.value.isLoadingLocation) return
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          isLoadingLocation = true,
          locationUnavailable = false,
        )
      }
      when (val result = locationHelper.fetchCoarseLocation()) {
        is LocationLookupResult.Success -> {
          _uiState.update {
            it.copy(
              isLoadingLocation = false,
              locationPermissionDenied = false,
              locationUnavailable = false,
              hasLocationPermission = true,
            )
          }
          preferences.savePrayerLocation(result.location)
        }

        LocationLookupResult.PermissionDenied -> {
          _uiState.update {
            it.copy(
              isLoadingLocation = false,
              locationPermissionDenied = true,
              hasLocationPermission = false,
            )
          }
        }

        LocationLookupResult.Unavailable -> {
          _uiState.update {
            it.copy(
              isLoadingLocation = false,
              locationUnavailable = true,
            )
          }
        }
      }
    }
  }

  fun selectManualCity(city: OfflineCityOption) {
    viewModelScope.launch {
      _uiState.update {
        it.copy(
          locationPermissionDenied = false,
          locationUnavailable = false,
        )
      }
      preferences.savePrayerLocation(city.toSavedLocation())
    }
  }

  fun setCalculationMethod(method: PrayerCalculationMethod) {
    viewModelScope.launch {
      preferences.savePrayerCalculationMethod(method = method, userSelected = true)
    }
  }

  fun resetMethodToCountryDefault() {
    val autoMethod = repository.defaultMethodForCountry(latestPrefs.settings.location?.countryCode)
    viewModelScope.launch {
      preferences.savePrayerCalculationMethod(method = autoMethod, userSelected = false)
    }
  }

  fun setAsrMadhab(madhab: AsrMadhab) {
    viewModelScope.launch {
      preferences.savePrayerAsrMadhab(madhab)
    }
  }

  fun setHighLatitudeRule(rule: PrayerHighLatitudeRule) {
    viewModelScope.launch {
      preferences.savePrayerHighLatitudeRule(rule)
    }
  }

  fun adjustPrayerMinutes(prayerType: PrayerType, deltaMinutes: Int) {
    val updated = latestPrefs.settings.adjustments.withDelta(prayerType, deltaMinutes)
    viewModelScope.launch {
      preferences.savePrayerAdjustments(updated)
    }
  }

  fun resetAllAdjustments() {
    viewModelScope.launch {
      preferences.savePrayerAdjustments(PrayerMinuteAdjustments())
    }
  }

  fun togglePrayerCompleted(prayerType: PrayerType) {
    val todayKey = _uiState.value.todayDateKey.ifBlank { return }
    viewModelScope.launch {
      preferences.togglePrayerCompleted(dateKey = todayKey, prayerKey = prayerType.key)
    }
  }

  private fun needsScheduleRefresh(
    settings: PrayerSettingsState,
    cached: CachedPrayerSchedule?,
  ): Boolean {
    if (settings.location == null) return false
    if (cached == null || cached.cacheKey != settings.cacheKey()) return true
    val tz = TimeZone.getTimeZone(settings.location.timeZoneId)
    val todayKey = PrayerFormatters.calendarDateKey(Calendar.getInstance(tz))
    if (!cached.daysByDateKey.containsKey(todayKey)) return true
    // Ensure at least today + next 25 days are present in cache
    val horizonCal = Calendar.getInstance(tz).apply { add(Calendar.DAY_OF_YEAR, 25) }
    val horizonKey = PrayerFormatters.calendarDateKey(horizonCal)
    return !cached.daysByDateKey.containsKey(horizonKey)
  }

  private suspend fun recomputeAndCache30Days(settings: PrayerSettingsState) {
    _uiState.update { it.copy(isComputingSchedule = true) }
    val computed = repository.compute30DaySchedule(settings)
    if (computed != null) {
      inMemorySchedule = computed
      preferences.saveCachedPrayerSchedule(computed)
      rebuildUiSnapshot(latestPrefs.copy(settings = settings, cachedSchedule = computed))
    }
    _uiState.update { it.copy(isComputingSchedule = false) }
  }

  private suspend fun rebuildUiSnapshot(persisted: ReaderPreferences.PersistedPrayerPreferences) {
    val offset = _uiState.value.selectedDayOffset
    val nowMs = System.currentTimeMillis()
    val snapshot = withContext(Dispatchers.Default) {
      val settings = persisted.settings
      val tzId = settings.location?.timeZoneId ?: TimeZone.getDefault().id
      val tz = TimeZone.getTimeZone(tzId)

      val todayCal = Calendar.getInstance(tz).apply { timeInMillis = nowMs }
      val tomorrowCal = (todayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
      val selectedCal = (todayCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }

      val todayKey = PrayerFormatters.calendarDateKey(todayCal)
      val tomorrowKey = PrayerFormatters.calendarDateKey(tomorrowCal)
      val selectedKey = PrayerFormatters.calendarDateKey(selectedCal)

      val schedule = inMemorySchedule?.takeIf { it.cacheKey == settings.cacheKey() }
        ?: persisted.cachedSchedule?.takeIf { it.cacheKey == settings.cacheKey() }

      val loc = settings.location
      val todayRec = schedule?.daysByDateKey?.get(todayKey)
        ?: loc?.let {
          repository.computeDay(
            location = it,
            method = settings.calculationMethod,
            madhab = settings.asrMadhab,
            highLatitudeRule = settings.highLatitudeRule,
            adjustments = settings.adjustments,
            year = todayCal.get(Calendar.YEAR),
            month = todayCal.get(Calendar.MONTH) + 1,
            day = todayCal.get(Calendar.DAY_OF_MONTH),
          )
        }
      val tomorrowRec = schedule?.daysByDateKey?.get(tomorrowKey)
        ?: loc?.let {
          repository.computeDay(
            location = it,
            method = settings.calculationMethod,
            madhab = settings.asrMadhab,
            highLatitudeRule = settings.highLatitudeRule,
            adjustments = settings.adjustments,
            year = tomorrowCal.get(Calendar.YEAR),
            month = tomorrowCal.get(Calendar.MONTH) + 1,
            day = tomorrowCal.get(Calendar.DAY_OF_MONTH),
          )
        }
      val selectedRec = schedule?.daysByDateKey?.get(selectedKey)
        ?: loc?.let {
          repository.computeDay(
            location = it,
            method = settings.calculationMethod,
            madhab = settings.asrMadhab,
            highLatitudeRule = settings.highLatitudeRule,
            adjustments = settings.adjustments,
            year = selectedCal.get(Calendar.YEAR),
            month = selectedCal.get(Calendar.MONTH) + 1,
            day = selectedCal.get(Calendar.DAY_OF_MONTH),
          )
        }

      val nextTarget = repository.resolveNextPrayer(todayRec, tomorrowRec, nowMs)
      val daytime = repository.isDaytime(todayRec, nowMs)

      val selYear = selectedCal.get(Calendar.YEAR)
      val selMonth = selectedCal.get(Calendar.MONTH) + 1
      val selDay = selectedCal.get(Calendar.DAY_OF_MONTH)

      val gregorianText = PrayerFormatters.formatGregorianDateArabic(selYear, selMonth, selDay, tzId)
      val hijriText = PrayerFormatters.formatHijriDateArabic(selYear, selMonth, selDay, tzId)

      val checkedToday = if (persisted.checkedDateKey == todayKey) {
        persisted.checkedPrayerKeys
      } else {
        emptySet()
      }

      UiSnapshotComputation(
        settings = settings,
        todayRecord = todayRec,
        displayedRecord = selectedRec,
        nextPrayer = nextTarget,
        isDaytime = daytime,
        gregorianDateText = gregorianText,
        hijriDateText = hijriText,
        todayDateKey = todayKey,
        displayedDateKey = selectedKey,
        checkedPrayerKeysToday = checkedToday,
      )
    }

    _uiState.update { current ->
      current.copy(
        settings = snapshot.settings,
        todayRecord = snapshot.todayRecord,
        displayedRecord = snapshot.displayedRecord,
        nextPrayer = snapshot.nextPrayer,
        isDaytime = snapshot.isDaytime,
        gregorianDateText = snapshot.gregorianDateText,
        hijriDateText = snapshot.hijriDateText,
        todayDateKey = snapshot.todayDateKey,
        displayedDateKey = snapshot.displayedDateKey,
        checkedPrayerKeysToday = snapshot.checkedPrayerKeysToday,
        hasLocationPermission = locationHelper.hasCoarseLocationPermission(),
      )
    }
  }

  private data class UiSnapshotComputation(
    val settings: PrayerSettingsState,
    val todayRecord: DailyPrayerTimesRecord?,
    val displayedRecord: DailyPrayerTimesRecord?,
    val nextPrayer: NextPrayerTarget?,
    val isDaytime: Boolean,
    val gregorianDateText: String,
    val hijriDateText: String,
    val todayDateKey: String,
    val displayedDateKey: String,
    val checkedPrayerKeysToday: Set<String>,
  )
}
