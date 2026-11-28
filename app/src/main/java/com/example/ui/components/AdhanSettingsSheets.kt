package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.prayer.AdhanAlarmScheduler
import com.example.data.prayer.AdhanNotificationHelper
import com.example.data.prayer.AdhanSettingsState
import com.example.data.prayer.AdhanSoundRepository
import com.example.data.prayer.AsrMadhab
import com.example.data.prayer.PrayerAlarmConfig
import com.example.data.prayer.PrayerAlarmMode
import com.example.data.prayer.PrayerCalculationMethod
import com.example.data.prayer.PrayerFormatters
import com.example.data.prayer.PrayerHighLatitudeRule
import com.example.data.prayer.PrayerSettingsState
import com.example.data.prayer.PrayerType
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrayerSoundConfigSheet(
  prayerType: PrayerType,
  currentConfig: PrayerAlarmConfig,
  isTestPlaying: Boolean,
  onSaveConfig: (PrayerAlarmConfig) -> Unit,
  onApplyToAllPrayers: (PrayerAlarmConfig) -> Unit,
  onTestAdhanNow: (PrayerAlarmConfig) -> Unit,
  onStopTestAdhan: () -> Unit,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val soundRepository = remember(context) { AdhanSoundRepository(context.applicationContext) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var showMuadhinPicker by remember { mutableStateOf(false) }

  val openDocumentLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { pickedUri: Uri? ->
    if (pickedUri != null) {
      val displayName = soundRepository.persistReadPermissionAndResolveName(pickedUri)
      onSaveConfig(
        PrayerAlarmConfig(
          mode = PrayerAlarmMode.CUSTOM_URI,
          customUri = pickedUri.toString(),
          customDisplayName = displayName,
        ),
      )
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)
        .padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = if (currentConfig.mode == PrayerAlarmMode.SILENT) {
            Icons.AutoMirrored.Filled.VolumeOff
          } else {
            Icons.AutoMirrored.Filled.VolumeUp
          },
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column {
          Text(
            text = "تنبيه صلاة ${prayerType.arabicName}",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "اختر نغمة التنبيه أو أوقف الصوت لهذه الصلاة",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      PrayerAlarmMode.entries.forEach { mode ->
        val selected = currentConfig.mode == mode
        val handleModeTap = {
          when (mode) {
            PrayerAlarmMode.MUEZZIN -> {
              if (currentConfig.muezzinId.isNullOrBlank()) {
                showMuadhinPicker = true
              } else {
                onSaveConfig(currentConfig.copy(mode = PrayerAlarmMode.MUEZZIN))
              }
            }
            PrayerAlarmMode.CUSTOM_URI -> {
              if (currentConfig.customUri.isNullOrBlank() || !currentConfig.muezzinId.isNullOrBlank()) {
                openDocumentLauncher.launch(arrayOf("audio/*"))
              } else {
                onSaveConfig(currentConfig.copy(mode = PrayerAlarmMode.CUSTOM_URI))
              }
            }
            else -> onSaveConfig(currentConfig.copy(mode = mode))
          }
        }
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
          } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
          },
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { handleModeTap() }
            .testTag("prayer_sound_mode_${mode.name}"),
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 56.dp)
              .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            RadioButton(
              selected = selected,
              onClick = { handleModeTap() },
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = mode.arabicLabel,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
              )
              val sub = when {
                mode == PrayerAlarmMode.MUEZZIN &&
                  !currentConfig.muezzinId.isNullOrBlank() &&
                  !currentConfig.customDisplayName.isNullOrBlank() ->
                  "المؤذن المختار: ${currentConfig.customDisplayName}"
                mode == PrayerAlarmMode.CUSTOM_URI &&
                  currentConfig.muezzinId.isNullOrBlank() &&
                  !currentConfig.customDisplayName.isNullOrBlank() ->
                  "الملف المختار: ${currentConfig.customDisplayName}"
                else -> mode.subtitleAr
              }
              Text(
                text = sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }

      FilledTonalButton(
        onClick = { showMuadhinPicker = true },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .testTag("prayer_open_muadhin_library_button"),
      ) {
        Icon(Icons.Filled.Mosque, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
          if (currentConfig.muezzinId.isNullOrBlank()) {
            "اختيار مؤذن من مكتبة المؤذنين"
          } else {
            "تغيير المؤذن (${currentConfig.customDisplayName ?: "مؤذن"})"
          },
        )
      }

      OutlinedButton(
        onClick = { openDocumentLauncher.launch(arrayOf("audio/*")) },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .testTag("prayer_pick_custom_audio_button"),
      ) {
        Icon(Icons.Filled.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
          text = if (currentConfig.customUri.isNullOrBlank() || !currentConfig.muezzinId.isNullOrBlank()) {
            "اختيار ملف أذان من الجهاز"
          } else {
            "تغيير الملف الصوتي"
          },
        )
      }

      HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

      if (prayerType != PrayerType.SUNRISE) {
        FilledTonalButton(
          onClick = { onApplyToAllPrayers(currentConfig) },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("prayer_apply_sound_all_button"),
        ) {
          Icon(Icons.Filled.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(8.dp))
          Text("تطبيق على جميع الصلوات الخمس")
        }
      }

      Button(
        onClick = {
          if (isTestPlaying) {
            onStopTestAdhan()
          } else {
            onTestAdhanNow(currentConfig)
          }
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 50.dp)
          .testTag("prayer_test_adhan_button"),
      ) {
        Icon(
          imageVector = if (isTestPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
          contentDescription = null,
          modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(if (isTestPlaying) "إيقاف تجربة الأذان" else "تجربة الأذان الآن (١٠ ثوانٍ)")
      }
    }
  }

  if (showMuadhinPicker) {
    MuadhinPickerSheet(
      prayerType = prayerType,
      currentConfig = currentConfig,
      onSelectMuezzin = { entry ->
        val cfg = PrayerAlarmConfig(
          mode = PrayerAlarmMode.MUEZZIN,
          muezzinId = entry.id,
          customDisplayName = entry.muezzinAr,
          customUri = entry.audioUrl,
        )
        onSaveConfig(cfg)
        scope.launch {
          soundRepository.downloadMuezzinAudio(entry)
        }
      },
      onApplyToAllPrayers = { entry ->
        val cfg = PrayerAlarmConfig(
          mode = PrayerAlarmMode.MUEZZIN,
          muezzinId = entry.id,
          customDisplayName = entry.muezzinAr,
          customUri = entry.audioUrl,
        )
        onApplyToAllPrayers(cfg)
        scope.launch {
          soundRepository.downloadMuezzinAudio(entry)
        }
      },
      onDismiss = { showMuadhinPicker = false },
    )
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdhanAlarmsManagementSheet(
  adhanSettings: AdhanSettingsState,
  isTestPlaying: Boolean,
  onToggleAlarmsEnabled: (Boolean) -> Unit,
  onTogglePersistentCountdown: (Boolean) -> Unit,
  onSelectPreAdhanMinutes: (Int) -> Unit = {},
  onChangeVolumePercent: (Int) -> Unit = {},
  onToggleVibrate: (Boolean) -> Unit = {},
  onSelectBackground: (path: String?, label: String?) -> Unit = { _, _ -> },
  onEditPrayerSound: (PrayerType) -> Unit,
  onTestAdhanNow: () -> Unit,
  onStopTestAdhan: () -> Unit,
  prayerSettings: PrayerSettingsState? = null,
  initialTab: Int = 0,
  onSelectMethod: (PrayerCalculationMethod) -> Unit = {},
  onResetMethodToCountryDefault: () -> Unit = {},
  onSelectMadhab: (AsrMadhab) -> Unit = {},
  onSelectHighLatitudeRule: (PrayerHighLatitudeRule) -> Unit = {},
  onAdjustMinutes: (PrayerType, Int) -> Unit = { _, _ -> },
  onResetAdjustments: () -> Unit = {},
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)
        .padding(bottom = 28.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "المواقيت وتنبيهات الأذان",
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
      )

      if (prayerSettings != null) {
        PrimaryTabRow(
          selectedTabIndex = selectedTab,
          containerColor = MaterialTheme.colorScheme.surface,
        ) {
          Tab(
            selected = selectedTab == 0,
            onClick = { selectedTab = 0 },
            text = { Text("تنبيهات الأذان", maxLines = 1) },
            modifier = Modifier.testTag("adhan_sheet_tab_alarms"),
          )
          Tab(
            selected = selectedTab == 1,
            onClick = { selectedTab = 1 },
            text = { Text("الحساب والمواقيت", maxLines = 1) },
            modifier = Modifier.testTag("adhan_sheet_tab_calculation"),
          )
        }
      }

      if (prayerSettings != null && selectedTab == 1) {
        PrayerCalculationSettingsContent(
          settings = prayerSettings,
          onSelectMethod = onSelectMethod,
          onResetMethodToCountryDefault = onResetMethodToCountryDefault,
          onSelectMadhab = onSelectMadhab,
          onSelectHighLatitudeRule = onSelectHighLatitudeRule,
          onAdjustMinutes = onAdjustMinutes,
          onResetAdjustments = onResetAdjustments,
        )
      } else {
        AdhanControlsSection(
          adhanSettings = adhanSettings,
          isTestPlaying = isTestPlaying,
          onToggleAlarmsEnabled = onToggleAlarmsEnabled,
          onTogglePersistentCountdown = onTogglePersistentCountdown,
          onSelectPreAdhanMinutes = onSelectPreAdhanMinutes,
          onChangeVolumePercent = onChangeVolumePercent,
          onToggleVibrate = onToggleVibrate,
          onSelectBackground = onSelectBackground,
          onEditPrayerSound = onEditPrayerSound,
          onTestAdhanNow = onTestAdhanNow,
          onStopTestAdhan = onStopTestAdhan,
        )
      }
    }
  }
}

@Composable
fun AdhanControlsSection(
  adhanSettings: AdhanSettingsState,
  isTestPlaying: Boolean,
  onToggleAlarmsEnabled: (Boolean) -> Unit,
  onTogglePersistentCountdown: (Boolean) -> Unit,
  onSelectPreAdhanMinutes: (Int) -> Unit = {},
  onChangeVolumePercent: (Int) -> Unit = {},
  onToggleVibrate: (Boolean) -> Unit = {},
  onSelectBackground: (path: String?, label: String?) -> Unit = { _, _ -> },
  onEditPrayerSound: (PrayerType) -> Unit,
  onTestAdhanNow: () -> Unit,
  onStopTestAdhan: () -> Unit,
) {
  val context = LocalContext.current
  var refreshTick by remember { mutableIntStateOf(0) }
  var showBgPicker by remember { mutableStateOf(false) }
  val lifecycleOwner = LocalLifecycleOwner.current

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        refreshTick++
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  val notifPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) {
    refreshTick++
  }

  val statusSnapshot = remember(context, refreshTick) {
    readDeviceAlarmHealth(context)
  }

  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    MissingPermissionsCard()

    // Master Adhan Alarms toggle
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "تفعيل منبهات الأذان",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "جدولة تنبيهات دخول وقت الصلاة دون اتصال (٤٨ ساعة قادمة)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
          checked = adhanSettings.alarmsEnabled,
          onCheckedChange = onToggleAlarmsEnabled,
          modifier = Modifier.testTag("adhan_master_switch"),
        )
      }
    }

    // Persistent Countdown Notification toggle
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "إشعار المواقيت الدائم",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = "إشعار صامت ومستمر يظهر الصلاة القادمة والعد التنازلي",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
          checked = adhanSettings.persistentCountdownEnabled,
          onCheckedChange = onTogglePersistentCountdown,
          modifier = Modifier.testTag("adhan_persistent_notif_switch"),
        )
      }
    }

    // Pre-Adhan Reminder selector
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = "تنبيه قبل الأذان",
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = "إشعار تذكيري قبل دخول وقت الصلاة المفعّل منبهها",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val preOptions = remember { listOf(0, 5, 10, 15, 20, 30) }
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          preOptions.forEach { mins ->
            val label = if (mins == 0) "إيقاف" else "${PrayerFormatters.toArabicIndicDigits(mins)} د"
            FilterChip(
              selected = adhanSettings.preAdhanMinutes == mins,
              onClick = { onSelectPreAdhanMinutes(mins) },
              label = { Text(label) },
              modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("adhan_pre_alert_$mins"),
            )
          }
        }
      }
    }

    // Adhan Volume slider + Vibrate toggle
    var sliderVolume by remember(adhanSettings.adhanVolumePercent) {
      mutableIntStateOf(adhanSettings.adhanVolumePercent.coerceIn(10, 100))
    }
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.50f),
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = Icons.AutoMirrored.Filled.VolumeUp,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
              text = "مستوى صوت الأذان",
              style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          Text(
            text = "${PrayerFormatters.toArabicIndicDigits(sliderVolume)}٪",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Slider(
          value = sliderVolume / 100f,
          onValueChange = { fraction ->
            sliderVolume = (fraction * 100f).roundToInt().coerceIn(10, 100)
          },
          onValueChangeFinished = {
            onChangeVolumePercent(sliderVolume)
          },
          valueRange = 0.1f..1.0f,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("adhan_volume_slider"),
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
          ) {
            Icon(
              imageVector = Icons.Filled.Vibration,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Column {
              Text(
                text = "الاهتزاز مع الأذان",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
              )
              Text(
                text = "تشغيل نمط اهتزاز هادئ عند بدء الأذان",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
          Spacer(Modifier.width(8.dp))
          Switch(
            checked = adhanSettings.vibrateOnAdhan,
            onCheckedChange = onToggleVibrate,
            modifier = Modifier.testTag("adhan_vibrate_switch"),
          )
        }
      }
    }

    // Adhan screen background picker row
    Surface(
      shape = RoundedCornerShape(14.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(14.dp))
        .clickable { showBgPicker = true }
        .testTag("adhan_background_picker_row"),
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 56.dp)
          .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "خلفية شاشة الأذان",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = adhanSettings.backgroundLabel?.takeIf { it.isNotBlank() } ?: "خلفية قبسات الافتراضية",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
          )
        }
        Spacer(Modifier.width(8.dp))
        Text(
          text = "تغيير",
          style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }

    // 10s Test Adhan button
    Button(
      onClick = {
        if (isTestPlaying) onStopTestAdhan() else onTestAdhanNow()
      },
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 50.dp)
        .testTag("adhan_settings_test_button"),
    ) {
      Icon(
        imageVector = if (isTestPlaying) Icons.Filled.Stop else Icons.Filled.NotificationsActive,
        contentDescription = null,
        modifier = Modifier.size(20.dp),
      )
      Spacer(Modifier.width(8.dp))
      Text(if (isTestPlaying) "إيقاف التجربة الآن" else "تجربة الأذان الآن")
    }

    // Per-prayer summary rows
    Text(
      text = "صوت التنبيه لكل صلاة",
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.primary,
    )
    PrayerType.entries.forEach { prayerType ->
      val cfg = adhanSettings.configFor(prayerType)
      val audible = adhanSettings.alarmsEnabled && cfg.mode != PrayerAlarmMode.SILENT
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f),
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .clickable { onEditPrayerSound(prayerType) }
          .testTag("adhan_prayer_config_row_${prayerType.key}"),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
              imageVector = if (audible) {
                Icons.AutoMirrored.Filled.VolumeUp
              } else {
                Icons.AutoMirrored.Filled.VolumeOff
              },
              contentDescription = null,
              tint = if (audible) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
              text = prayerType.arabicName,
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
              color = MaterialTheme.colorScheme.onSurface,
            )
          }
          Text(
            text = cfg.summaryLabelAr(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }

    // Permissions & Battery Health Card
    DeviceAlarmReadinessCard(
      status = statusSnapshot,
      onRequestNotifications = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
          openAppNotificationSettings(context)
        }
      },
      onOpenNotificationSettings = { openAppNotificationSettings(context) },
      onOpenExactAlarmSettings = { openExactAlarmSettings(context) },
      onOpenFullScreenIntentSettings = { openFullScreenIntentSettings(context) },
      onOpenBatterySettings = { openBatteryOptimizationSettings(context) },
      onOpenAppDetails = { openAppDetailsSettings(context) },
    )
  }

  if (showBgPicker) {
    AdhanBackgroundPickerSheet(
      currentPath = adhanSettings.backgroundImagePath,
      currentLabel = adhanSettings.backgroundLabel,
      onSelectBackground = onSelectBackground,
      onDismiss = { showBgPicker = false },
    )
  }
}

private data class DeviceAlarmHealthStatus(
  val notificationsAllowed: Boolean,
  val exactAlarmsAllowed: Boolean,
  val fullScreenIntentAllowed: Boolean,
  val ignoringBatteryOptimizations: Boolean,
)

private fun readDeviceAlarmHealth(context: Context): DeviceAlarmHealthStatus {
  val pm = context.getSystemService(PowerManager::class.java)
  val ignoringBattery = if (pm != null) {
    runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }.getOrDefault(false)
  } else {
    false
  }
  return DeviceAlarmHealthStatus(
    notificationsAllowed = AdhanNotificationHelper.canPostNotifications(context),
    exactAlarmsAllowed = AdhanAlarmScheduler.canScheduleExactAlarmsCompat(context),
    fullScreenIntentAllowed = AdhanNotificationHelper.canUseFullScreenIntentCompat(context),
    ignoringBatteryOptimizations = ignoringBattery,
  )
}

@Composable
private fun DeviceAlarmReadinessCard(
  status: DeviceAlarmHealthStatus,
  onRequestNotifications: () -> Unit,
  onOpenNotificationSettings: () -> Unit,
  onOpenExactAlarmSettings: () -> Unit,
  onOpenFullScreenIntentSettings: () -> Unit,
  onOpenBatterySettings: () -> Unit,
  onOpenAppDetails: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("adhan_device_health_card"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Filled.PhoneAndroid,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
          text = "جاهزية المنبهات والبطارية",
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.primary,
        )
      }

      ReadinessRow(
        icon = Icons.Filled.Notifications,
        title = "إذن الإشعارات",
        ok = status.notificationsAllowed,
        okText = "مفعّل",
        issueText = "مطلوب لإظهار الأذان والإشعار الدائم",
        actionLabel = "تفعيل",
        onAction = {
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onRequestNotifications()
          } else {
            onOpenNotificationSettings()
          }
        },
      )

      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ReadinessRow(
          icon = Icons.Filled.Alarm,
          title = "المنبهات الدقيقة (Exact Alarms)",
          ok = status.exactAlarmsAllowed,
          okText = "مسموح",
          issueText = "يرجى السماح لضبط الأذان في الدقيقة المحددة",
          actionLabel = "ضبط",
          onAction = onOpenExactAlarmSettings,
        )
      }

      if (Build.VERSION.SDK_INT >= 34) {
        ReadinessRow(
          icon = Icons.Filled.NotificationsActive,
          title = "ظهور الأذان على شاشة القفل",
          ok = status.fullScreenIntentAllowed,
          okText = "مسموح",
          issueText = "اسمح بملء الشاشة لظهور تنبيه الأذان عند القفل",
          actionLabel = "إذن",
          onAction = onOpenFullScreenIntentSettings,
        )
      }

      ReadinessRow(
        icon = Icons.Filled.BatteryAlert,
        title = "استثناء تحسين البطارية",
        ok = status.ignoringBatteryOptimizations,
        okText = "غير مقيّد",
        issueText = "اجعل البطارية «غير مقيدة» لمنع إيقاف المنبه",
        actionLabel = "البطارية",
        onAction = onOpenBatterySettings,
      )

      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = "تنبيه لأجهزة سامسونج (Galaxy):",
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "افتح الضبط ← البطارية ← حدود استخدام الخلفية ← التطبيقات غير الموضوعة في السكون، ثم أضف تطبيق «قبسات» لضمان عمل الأذان دائماً.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
          )
          OutlinedButton(
            onClick = onOpenAppDetails,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
              .testTag("adhan_open_app_details_button"),
          ) {
            Text("فتح معلومات التطبيق (البطارية والإشعارات)")
          }
        }
      }
    }
  }
}

@Composable
private fun ReadinessRow(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  ok: Boolean,
  okText: String,
  issueText: String,
  actionLabel: String,
  onAction: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(
      modifier = Modifier.weight(1f),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = if (ok) Icons.Filled.CheckCircle else Icons.Filled.WarningAmber,
        contentDescription = null,
        tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = Modifier.size(18.dp),
      )
      Spacer(Modifier.width(8.dp))
      Column {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = if (ok) okText else issueText,
          style = MaterialTheme.typography.labelSmall,
          color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (!ok) {
      Spacer(Modifier.width(8.dp))
      FilledTonalButton(
        onClick = onAction,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text(actionLabel)
      }
    }
  }
}

private fun openAppNotificationSettings(context: Context) {
  val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
      putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
  } else {
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:${context.packageName}")
    }
  }
  runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun openExactAlarmSettings(context: Context) {
  val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
      data = Uri.parse("package:${context.packageName}")
    }
  } else {
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:${context.packageName}")
    }
  }
  runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    .onFailure { openAppDetailsSettings(context) }
}

private fun openFullScreenIntentSettings(context: Context) {
  val intent = if (Build.VERSION.SDK_INT >= 34) {
    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
      data = Uri.parse("package:${context.packageName}")
    }
  } else {
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:${context.packageName}")
    }
  }
  runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    .onFailure { openAppDetailsSettings(context) }
}

private fun openBatteryOptimizationSettings(context: Context) {
  val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
  runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    .onFailure { openAppDetailsSettings(context) }
}

private fun openAppDetailsSettings(context: Context) {
  val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
    data = Uri.parse("package:${context.packageName}")
  }
  runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
