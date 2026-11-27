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
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import com.example.data.prayer.PrayerAlarmConfig
import com.example.data.prayer.PrayerAlarmMode
import com.example.data.prayer.PrayerType

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
  val soundRepository = remember(context) { AdhanSoundRepository(context.applicationContext) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            .clickable {
              if (mode == PrayerAlarmMode.CUSTOM_URI && currentConfig.customUri.isNullOrBlank()) {
                openDocumentLauncher.launch(arrayOf("audio/*"))
              } else {
                onSaveConfig(currentConfig.copy(mode = mode))
              }
            }
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
              onClick = {
                if (mode == PrayerAlarmMode.CUSTOM_URI && currentConfig.customUri.isNullOrBlank()) {
                  openDocumentLauncher.launch(arrayOf("audio/*"))
                } else {
                  onSaveConfig(currentConfig.copy(mode = mode))
                }
              },
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = mode.arabicLabel,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
              )
              val sub = if (mode == PrayerAlarmMode.CUSTOM_URI && !currentConfig.customDisplayName.isNullOrBlank()) {
                "الملف المختار: ${currentConfig.customDisplayName}"
              } else {
                mode.subtitleAr
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
          text = if (currentConfig.customUri.isNullOrBlank()) {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdhanAlarmsManagementSheet(
  adhanSettings: AdhanSettingsState,
  isTestPlaying: Boolean,
  onToggleAlarmsEnabled: (Boolean) -> Unit,
  onTogglePersistentCountdown: (Boolean) -> Unit,
  onEditPrayerSound: (PrayerType) -> Unit,
  onTestAdhanNow: () -> Unit,
  onStopTestAdhan: () -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
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
        text = "تنبيهات الأذان والمواقيت",
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
      )

      AdhanControlsSection(
        adhanSettings = adhanSettings,
        isTestPlaying = isTestPlaying,
        onToggleAlarmsEnabled = onToggleAlarmsEnabled,
        onTogglePersistentCountdown = onTogglePersistentCountdown,
        onEditPrayerSound = onEditPrayerSound,
        onTestAdhanNow = onTestAdhanNow,
        onStopTestAdhan = onStopTestAdhan,
      )
    }
  }
}

@Composable
fun AdhanControlsSection(
  adhanSettings: AdhanSettingsState,
  isTestPlaying: Boolean,
  onToggleAlarmsEnabled: (Boolean) -> Unit,
  onTogglePersistentCountdown: (Boolean) -> Unit,
  onEditPrayerSound: (PrayerType) -> Unit,
  onTestAdhanNow: () -> Unit,
  onStopTestAdhan: () -> Unit,
) {
  val context = LocalContext.current
  var refreshTick by remember { mutableIntStateOf(0) }
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
