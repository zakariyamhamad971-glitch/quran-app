package com.example.ui.components

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Brightness4
import androidx.compose.material.icons.filled.Brightness7
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.prayer.AdhanSettingsState
import com.example.data.prayer.DailyPrayerTimesRecord
import com.example.data.prayer.NextPrayerTarget
import com.example.data.prayer.PrayerAlarmMode
import com.example.data.prayer.PrayerFormatters
import com.example.data.prayer.PrayerType
import com.example.data.quran.SurahMeta
import com.example.ui.theme.AmiriFontFamily
import com.example.ui.theme.QabasatDeepGreen
import com.example.ui.theme.QabasatGold
import com.example.ui.theme.QabasatGoldSoft
import com.example.ui.theme.QabasatNightGold
import com.example.ui.theme.QabasatNightGreen
import com.example.viewmodel.PrayerTimesUiState
import com.example.viewmodel.PrayerTimesViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.TimeZone

@Composable
fun PrayerHomeScreen(
  prayerViewModel: PrayerTimesViewModel,
  surahs: List<SurahMeta>,
  currentPage: Int,
  isDarkTheme: Boolean,
  onToggleTheme: () -> Unit,
  onOpenMushafIndex: () -> Unit,
  onResumeReader: () -> Unit,
  onOpenAppSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val uiState by prayerViewModel.uiState.collectAsStateWithLifecycle()
  var showCityPicker by rememberSaveable { mutableStateOf(false) }
  var showPrayerSettings by rememberSaveable { mutableStateOf(false) }
  var showAdhanSettings by rememberSaveable { mutableStateOf(false) }
  var editingSoundPrayer by rememberSaveable { mutableStateOf<PrayerType?>(null) }

  val locationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    prayerViewModel.onLocationPermissionResult(granted)
    if (!granted && uiState.settings.location == null) {
      showCityPicker = true
    }
  }

  val onRequestCoarseLocation = remember(locationPermissionLauncher, prayerViewModel, uiState.hasLocationPermission) {
    {
      if (uiState.hasLocationPermission) {
        prayerViewModel.refreshCoarseLocation()
      } else {
        locationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
      }
    }
  }

  val lastReadSurahName = remember(surahs, currentPage) {
    surahs.firstOrNull { currentPage in it.startPage..it.endPage }?.nameArabic
  }

  Box(modifier = modifier.fillMaxSize()) {
    IslamicAmbientBackground(isDarkTheme = isDarkTheme)

    Column(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
      HomeTopBar(
        isDarkTheme = isDarkTheme,
        onToggleTheme = onToggleTheme,
        onOpenPrayerSettings = { showPrayerSettings = true },
        onOpenAppSettings = onOpenAppSettings,
      )

      BoxWithConstraints(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f),
        contentAlignment = Alignment.TopCenter,
      ) {
        val isWideTablet = maxWidth >= 760.dp
        if (isWideTablet) {
          Row(
            modifier = Modifier
              .widthIn(max = 1120.dp)
              .fillMaxSize()
              .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top,
          ) {
            Column(
              modifier = Modifier
                .weight(1.15f)
                .verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              PrayerTimesMainCard(
                uiState = uiState,
                isDarkTheme = isDarkTheme,
                onSelectDayOffset = prayerViewModel::selectDayOffset,
                onRequestLocation = onRequestCoarseLocation,
                onOpenCityPicker = { showCityPicker = true },
                onTogglePrayerCompleted = prayerViewModel::togglePrayerCompleted,
                onOpenPrayerSound = { editingSoundPrayer = it },
              )
            }

            Column(
              modifier = Modifier
                .weight(0.85f)
                .verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
              MushafHomeTile(
                lastReadSurahName = lastReadSurahName,
                currentPage = currentPage,
                isDarkTheme = isDarkTheme,
                onOpenIndex = onOpenMushafIndex,
                onResumeReader = onResumeReader,
              )
              QuickPrayerMetaCard(
                uiState = uiState,
                onOpenPrayerSettings = { showPrayerSettings = true },
                onOpenAdhanSettings = { showAdhanSettings = true },
                onOpenCityPicker = { showCityPicker = true },
              )
            }
          }
        } else {
          Column(
            modifier = Modifier
              .widthIn(max = 620.dp)
              .fillMaxSize()
              .verticalScroll(rememberScrollState())
              .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
          ) {
            PrayerTimesMainCard(
              uiState = uiState,
              isDarkTheme = isDarkTheme,
              onSelectDayOffset = prayerViewModel::selectDayOffset,
              onRequestLocation = onRequestCoarseLocation,
              onOpenCityPicker = { showCityPicker = true },
              onTogglePrayerCompleted = prayerViewModel::togglePrayerCompleted,
              onOpenPrayerSound = { editingSoundPrayer = it },
            )

            MushafHomeTile(
              lastReadSurahName = lastReadSurahName,
              currentPage = currentPage,
              isDarkTheme = isDarkTheme,
              onOpenIndex = onOpenMushafIndex,
              onResumeReader = onResumeReader,
            )

            Spacer(Modifier.height(12.dp))
          }
        }
      }
    }
  }

  if (showCityPicker) {
    ManualCityPickerSheet(
      currentCityName = uiState.settings.location?.displayName,
      onUseCoarseLocation = onRequestCoarseLocation,
      onSelectCity = prayerViewModel::selectManualCity,
      onDismiss = { showCityPicker = false },
    )
  }

  if (showPrayerSettings) {
    PrayerSettingsSheet(
      settings = uiState.settings,
      onSelectMethod = prayerViewModel::setCalculationMethod,
      onResetMethodToCountryDefault = prayerViewModel::resetMethodToCountryDefault,
      onSelectMadhab = prayerViewModel::setAsrMadhab,
      onSelectHighLatitudeRule = prayerViewModel::setHighLatitudeRule,
      onAdjustMinutes = prayerViewModel::adjustPrayerMinutes,
      onResetAdjustments = prayerViewModel::resetAllAdjustments,
      onOpenAdhanSettings = { showAdhanSettings = true },
      onDismiss = { showPrayerSettings = false },
    )
  }

  if (showAdhanSettings) {
    AdhanAlarmsManagementSheet(
      adhanSettings = uiState.adhanSettings,
      isTestPlaying = uiState.isTestAdhanPlaying,
      onToggleAlarmsEnabled = prayerViewModel::setAdhanAlarmsEnabled,
      onTogglePersistentCountdown = prayerViewModel::setPersistentCountdownEnabled,
      onEditPrayerSound = { editingSoundPrayer = it },
      onTestAdhanNow = { prayerViewModel.startTestAdhan10s() },
      onStopTestAdhan = { prayerViewModel.stopTestAdhan() },
      onDismiss = {
        showAdhanSettings = false
        prayerViewModel.refreshAlarmsAndNotification()
      },
    )
  }

  editingSoundPrayer?.let { targetPrayer ->
    PrayerSoundConfigSheet(
      prayerType = targetPrayer,
      currentConfig = uiState.adhanSettings.configFor(targetPrayer),
      isTestPlaying = uiState.isTestAdhanPlaying,
      onSaveConfig = { cfg -> prayerViewModel.updatePrayerAlarmConfig(targetPrayer, cfg) },
      onApplyToAllPrayers = { cfg -> prayerViewModel.applyAlarmConfigToAllPrayers(cfg) },
      onTestAdhanNow = { cfg ->
        prayerViewModel.startTestAdhan10s(prayerType = targetPrayer, configOverride = cfg)
      },
      onStopTestAdhan = { prayerViewModel.stopTestAdhan() },
      onDismiss = { editingSoundPrayer = null },
    )
  }
}

@Composable
private fun HomeTopBar(
  isDarkTheme: Boolean,
  onToggleTheme: () -> Unit,
  onOpenPrayerSettings: () -> Unit,
  onOpenAppSettings: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Column {
      Text(
        text = stringResource(R.string.app_name),
        style = MaterialTheme.typography.headlineSmall.copy(
          fontFamily = AmiriFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 24.sp,
        ),
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = stringResource(R.string.prayer_times_title),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
      IconButton(
        onClick = onOpenPrayerSettings,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("home_prayer_settings_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.Tune,
          contentDescription = stringResource(R.string.prayer_settings_title),
          tint = MaterialTheme.colorScheme.primary,
        )
      }

      IconButton(
        onClick = onToggleTheme,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("home_theme_toggle_button"),
      ) {
        Icon(
          imageVector = if (isDarkTheme) Icons.Filled.Brightness7 else Icons.Filled.Brightness4,
          contentDescription = stringResource(R.string.toggle_theme),
          tint = MaterialTheme.colorScheme.primary,
        )
      }

      IconButton(
        onClick = onOpenAppSettings,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .testTag("home_app_settings_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.Settings,
          contentDescription = stringResource(R.string.home_settings_title),
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

@Composable
private fun PrayerTimesMainCard(
  uiState: PrayerTimesUiState,
  isDarkTheme: Boolean,
  onSelectDayOffset: (Int) -> Unit,
  onRequestLocation: () -> Unit,
  onOpenCityPicker: () -> Unit,
  onTogglePrayerCompleted: (PrayerType) -> Unit,
  onOpenPrayerSound: (PrayerType) -> Unit,
) {
  val cardContainer = if (isDarkTheme) {
    Color(0xFF0C201D).copy(alpha = 0.90f)
  } else {
    Color(0xFFFFFFFF).copy(alpha = 0.92f)
  }
  val borderColor = if (isDarkTheme) {
    QabasatNightGold.copy(alpha = 0.28f)
  } else {
    QabasatGold.copy(alpha = 0.38f)
  }

  Surface(
    shape = RoundedCornerShape(24.dp),
    color = cardContainer,
    tonalElevation = 2.dp,
    shadowElevation = 4.dp,
    border = BorderStroke(1.dp, borderColor),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("home_prayer_card"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
    ) {
      // Location header + Yesterday / Today / Tomorrow switch
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Surface(
          shape = RoundedCornerShape(50),
          color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
          modifier = Modifier
            .weight(1f, fill = false)
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onOpenCityPicker)
            .testTag("home_location_chip"),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Icon(
              imageVector = Icons.Filled.LocationOn,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
              text = uiState.settings.location?.resolvedLabel() ?: "اختر الموقع",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.onPrimaryContainer,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Spacer(Modifier.width(8.dp))

        DaySwitcherPill(
          selectedOffset = uiState.selectedDayOffset,
          onSelectOffset = onSelectDayOffset,
        )
      }

      Spacer(Modifier.height(14.dp))

      if (uiState.settings.location == null) {
        LocationSetupPrompt(
          isLoading = uiState.isLoadingLocation,
          permissionDenied = uiState.locationPermissionDenied,
          locationUnavailable = uiState.locationUnavailable,
          onRequestLocation = onRequestCoarseLocation(onRequestLocation),
          onOpenCityPicker = onOpenCityPicker,
        )
      } else {
        val tzId = uiState.settings.location.timeZoneId
        val next = uiState.nextPrayer
        NextPrayerHeroSection(
          nextPrayer = next,
          timeZoneId = tzId,
          isDaytime = uiState.isDaytime,
          gregorianDateText = uiState.gregorianDateText,
          hijriDateText = uiState.hijriDateText,
          isDarkTheme = isDarkTheme,
        )

        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Spacer(Modifier.height(10.dp))

        val record = uiState.displayedRecord
        if (record != null) {
          PrayerScheduleList(
            record = record,
            timeZoneId = tzId,
            highlightedPrayer = if (uiState.selectedDayOffset == 0 && next?.isTomorrow == false) {
              next.prayerType
            } else {
              null
            },
            canMarkPrayed = uiState.selectedDayOffset == 0,
            checkedKeysToday = uiState.checkedPrayerKeysToday,
            adjustments = uiState.settings.adjustments,
            adhanSettings = uiState.adhanSettings,
            isDarkTheme = isDarkTheme,
            onTogglePrayerCompleted = onTogglePrayerCompleted,
            onOpenPrayerSound = onOpenPrayerSound,
          )
        } else {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 24.dp),
            contentAlignment = Alignment.Center,
          ) {
            CircularProgressIndicator(
              color = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(28.dp),
            )
          }
        }
      }
    }
  }
}

private fun onRequestCoarseLocation(block: () -> Unit): () -> Unit = block

@Composable
private fun DaySwitcherPill(
  selectedOffset: Int,
  onSelectOffset: (Int) -> Unit,
) {
  val items = remember {
    listOf(
      -1 to "أمس",
      0 to "اليوم",
      1 to "غداً",
    )
  }
  Surface(
    shape = RoundedCornerShape(14.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
  ) {
    Row(
      modifier = Modifier.padding(3.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      items.forEach { (offset, label) ->
        val selected = selectedOffset == offset
        Surface(
          shape = RoundedCornerShape(11.dp),
          color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
          modifier = Modifier
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(11.dp))
            .clickable { onSelectOffset(offset) }
            .testTag("prayer_day_switch_$offset"),
        ) {
          Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = label,
              style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
              ),
              color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun NextPrayerHeroSection(
  nextPrayer: NextPrayerTarget?,
  timeZoneId: String,
  isDaytime: Boolean,
  gregorianDateText: String,
  hijriDateText: String,
  isDarkTheme: Boolean,
) {
  val heroBg = if (isDarkTheme) {
    Color(0xFF143530)
  } else {
    QabasatDeepGreen
  }
  val goldAccent = if (isDarkTheme) QabasatNightGold else QabasatGoldSoft

  Surface(
    shape = RoundedCornerShape(18.dp),
    color = heroBg,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("next_prayer_hero"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f),
        ) {
          Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.14f),
            modifier = Modifier.size(46.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = if (isDaytime) Icons.Filled.WbSunny else Icons.Filled.NightsStay,
                contentDescription = if (isDaytime) "النهار" else "الليل",
                tint = goldAccent,
                modifier = Modifier.size(26.dp),
              )
            }
          }
          Spacer(Modifier.width(12.dp))
          Column {
            Text(
              text = if (nextPrayer?.isTomorrow == true) {
                "${stringResource(R.string.prayer_next_label)} (غداً)"
              } else {
                stringResource(R.string.prayer_next_label)
              },
              style = MaterialTheme.typography.labelMedium,
              color = Color.White.copy(alpha = 0.8f),
            )
            if (nextPrayer != null) {
              Row(verticalAlignment = Alignment.Bottom) {
                Text(
                  text = nextPrayer.prayerType.arabicName,
                  style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = AmiriFontFamily,
                    fontWeight = FontWeight.Bold,
                  ),
                  color = Color.White,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                  text = PrayerFormatters.formatTime12hArabic(nextPrayer.targetEpochMs, timeZoneId),
                  style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                  color = goldAccent,
                  modifier = Modifier.padding(bottom = 2.dp),
                )
              }
            }
          }
        }

        if (nextPrayer != null) {
          Column(horizontalAlignment = Alignment.End) {
            Text(
              text = "المتبقي",
              style = MaterialTheme.typography.labelSmall,
              color = Color.White.copy(alpha = 0.75f),
            )
            // Isolated composable: ONLY this Text recomposes each second!
            NextPrayerCountdownText(
              targetEpochMs = nextPrayer.targetEpochMs,
              color = goldAccent,
            )
          }
        }
      }

      Spacer(Modifier.height(10.dp))
      HorizontalDivider(color = Color.White.copy(alpha = 0.14f))
      Spacer(Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = hijriDateText,
          style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
          color = goldAccent,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = gregorianDateText,
          style = MaterialTheme.typography.bodySmall,
          color = Color.White.copy(alpha = 0.85f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * Isolated 1-second ticker composable so countdown updates recompose ONLY this Text node.
 */
@Composable
private fun NextPrayerCountdownText(
  targetEpochMs: Long,
  color: Color,
) {
  var remainingMs by remember(targetEpochMs) {
    mutableLongStateOf((targetEpochMs - System.currentTimeMillis()).coerceAtLeast(0L))
  }

  LaunchedEffect(targetEpochMs) {
    while (isActive) {
      val now = System.currentTimeMillis()
      remainingMs = (targetEpochMs - now).coerceAtLeast(0L)
      val millisIntoSecond = now % 1000L
      val sleepMs = (1000L - millisIntoSecond).coerceIn(200L, 1000L)
      delay(sleepMs)
    }
  }

  Text(
    text = PrayerFormatters.formatCountdownHmsArabic(remainingMs),
    style = MaterialTheme.typography.titleLarge.copy(
      fontWeight = FontWeight.Bold,
      fontSize = 20.sp,
    ),
    color = color,
    maxLines = 1,
    modifier = Modifier.testTag("prayer_live_countdown"),
  )
}

@Composable
private fun PrayerScheduleList(
  record: DailyPrayerTimesRecord,
  timeZoneId: String,
  highlightedPrayer: PrayerType?,
  canMarkPrayed: Boolean,
  checkedKeysToday: Set<String>,
  adjustments: com.example.data.prayer.PrayerMinuteAdjustments,
  adhanSettings: AdhanSettingsState,
  isDarkTheme: Boolean,
  onTogglePrayerCompleted: (PrayerType) -> Unit,
  onOpenPrayerSound: (PrayerType) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    PrayerType.entries.forEach { prayerType ->
      val isNext = highlightedPrayer == prayerType
      val isChecked = canMarkPrayed && checkedKeysToday.contains(prayerType.key)
      val offsetMin = adjustments.forPrayer(prayerType)
      val timeText = remember(record, prayerType, timeZoneId) {
        PrayerFormatters.formatTime12hArabic(record.epochFor(prayerType), timeZoneId)
      }

      val rowBg = when {
        isNext && isDarkTheme -> QabasatNightGreen.copy(alpha = 0.22f)
        isNext -> QabasatDeepGreen.copy(alpha = 0.09f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
      }
      val rowBorder = if (isNext) {
        BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
      } else {
        null
      }

      Surface(
        shape = RoundedCornerShape(14.dp),
        color = rowBg,
        border = rowBorder,
        modifier = Modifier
          .fillMaxWidth()
          .testTag("prayer_row_${prayerType.key}"),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
          ) {
            // Small round check circle inside a 48dp accessible tap box
            val checkDesc = "${stringResource(R.string.prayer_mark_prayed)} ${prayerType.arabicName}"
            Box(
              modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(
                  enabled = canMarkPrayed,
                  role = Role.Checkbox,
                  onClick = { onTogglePrayerCompleted(prayerType) },
                )
                .semantics { contentDescription = checkDesc }
                .testTag("prayer_check_${prayerType.key}"),
              contentAlignment = Alignment.Center,
            ) {
              Box(
                modifier = Modifier
                  .size(22.dp)
                  .clip(CircleShape)
                  .background(
                    if (isChecked) MaterialTheme.colorScheme.primary else Color.Transparent,
                  )
                  .border(
                    width = 1.8.dp,
                    color = when {
                      isChecked -> MaterialTheme.colorScheme.primary
                      canMarkPrayed -> MaterialTheme.colorScheme.outline
                      else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                    },
                    shape = CircleShape,
                  ),
                contentAlignment = Alignment.Center,
              ) {
                if (isChecked) {
                  Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                  )
                }
              }
            }

            Spacer(Modifier.width(4.dp))

            Column {
              Text(
                text = prayerType.arabicName,
                style = MaterialTheme.typography.titleMedium.copy(
                  fontFamily = AmiriFontFamily,
                  fontWeight = if (isNext) FontWeight.Bold else FontWeight.SemiBold,
                  fontSize = 18.sp,
                ),
                color = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
              )
              if (offsetMin != 0) {
                Text(
                  text = "${PrayerFormatters.formatSignedMinutesArabic(offsetMin)} د",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
              text = timeText,
              style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = if (isNext) FontWeight.Bold else FontWeight.Medium,
              ),
              color = if (isNext) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(4.dp))
            val soundConfig = adhanSettings.configFor(prayerType)
            val isAudible = adhanSettings.alarmsEnabled && soundConfig.mode != PrayerAlarmMode.SILENT
            IconButton(
              onClick = { onOpenPrayerSound(prayerType) },
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("prayer_alarm_button_${prayerType.key}"),
            ) {
              Icon(
                imageVector = if (isAudible) {
                  Icons.AutoMirrored.Filled.VolumeUp
                } else {
                  Icons.AutoMirrored.Filled.VolumeOff
                },
                contentDescription = "${stringResource(R.string.prayer_adhan_sound_button)} ${prayerType.arabicName}",
                tint = if (isAudible) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                },
                modifier = Modifier.size(20.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun LocationSetupPrompt(
  isLoading: Boolean,
  permissionDenied: Boolean,
  locationUnavailable: Boolean,
  onRequestLocation: () -> Unit,
  onOpenCityPicker: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      Icon(
        imageVector = Icons.Filled.MyLocation,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(32.dp),
      )
      Spacer(Modifier.height(8.dp))
      Text(
        text = when {
          permissionDenied -> "لم يتم منح إذن الموقع. يمكنك اختيار مدينتك يدوياً لحساب المواقيت بدقة دون اتصال."
          locationUnavailable -> "تعذر الحصول على الموقع حالياً. يمكنك اختيار مدينتك يدوياً أو المحاولة لاحقاً."
          else -> stringResource(R.string.prayer_location_needed)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Spacer(Modifier.height(14.dp))

      if (isLoading) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(10.dp))
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Button(
          onClick = onRequestLocation,
          enabled = !isLoading,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("prayer_detect_location_button"),
        ) {
          Text(
            text = stringResource(R.string.prayer_location_detect),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        OutlinedButton(
          onClick = onOpenCityPicker,
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("prayer_manual_city_button"),
        ) {
          Text(
            text = stringResource(R.string.prayer_location_pick_city),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

@Composable
private fun MushafHomeTile(
  lastReadSurahName: String?,
  currentPage: Int,
  isDarkTheme: Boolean,
  onOpenIndex: () -> Unit,
  onResumeReader: () -> Unit,
) {
  val tileBg = if (isDarkTheme) {
    Color(0xFF102A26).copy(alpha = 0.94f)
  } else {
    Color(0xFFFFFFFF).copy(alpha = 0.95f)
  }
  val tileBorder = if (isDarkTheme) {
    QabasatNightGold.copy(alpha = 0.42f)
  } else {
    QabasatDeepGreen.copy(alpha = 0.25f)
  }

  val pageStr = PrayerFormatters.toArabicIndicDigits(currentPage)
  val lastReadLabel = if (!lastReadSurahName.isNullOrBlank()) {
    "آخر قراءة: سورة $lastReadSurahName • ص $pageStr"
  } else {
    "آخر قراءة: صفحة $pageStr"
  }

  Surface(
    shape = RoundedCornerShape(22.dp),
    color = tileBg,
    tonalElevation = 3.dp,
    shadowElevation = 4.dp,
    border = BorderStroke(1.2.dp, tileBorder),
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(22.dp))
      .clickable(onClick = onOpenIndex)
      .testTag("home_mushaf_tile"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(18.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f),
        ) {
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(54.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
              )
            }
          }
          Spacer(Modifier.width(14.dp))
          Column {
            Text(
              text = stringResource(R.string.home_mushaf_tile_title),
              style = MaterialTheme.typography.headlineSmall.copy(
                fontFamily = AmiriFontFamily,
                fontWeight = FontWeight.Bold,
              ),
              color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
              text = lastReadLabel,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowForward,
          contentDescription = stringResource(R.string.home_mushaf_tile_subtitle),
          tint = MaterialTheme.colorScheme.primary,
        )
      }

      Spacer(Modifier.height(14.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        FilledTonalButton(
          onClick = onOpenIndex,
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("home_mushaf_open_index_button"),
        ) {
          Text("الفهرس والسور")
        }
        Button(
          onClick = onResumeReader,
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("home_mushaf_resume_reader_button"),
        ) {
          Text("متابعة القراءة")
        }
      }
    }
  }
}

@Composable
private fun QuickPrayerMetaCard(
  uiState: PrayerTimesUiState,
  onOpenPrayerSettings: () -> Unit,
  onOpenAdhanSettings: () -> Unit,
  onOpenCityPicker: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(18.dp),
    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = stringResource(R.string.prayer_settings_title),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
      )
      Text(
        text = "${uiState.settings.calculationMethod.arabicLabel} • ${uiState.settings.asrMadhab.arabicLabel}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          onClick = onOpenCityPicker,
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp),
        ) {
          Text("تغيير المدينة", maxLines = 1)
        }
        OutlinedButton(
          onClick = onOpenPrayerSettings,
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp),
        ) {
          Text("ضبط المواقيت", maxLines = 1)
        }
      }
      FilledTonalButton(
        onClick = onOpenAdhanSettings,
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .testTag("home_open_adhan_alarms_button"),
      ) {
        Text("تنبيهات الأذان والإشعار الدائم", maxLines = 1)
      }
    }
  }
}
