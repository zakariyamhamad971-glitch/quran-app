package com.example.ui.components

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.QabasatNightGold

enum class AppPermissionType(
  val id: String,
  val titleAr: String,
  val descriptionAr: String,
  val icon: ImageVector,
) {
  LOCATION(
    id = "location",
    titleAr = "إذن تحديد الموقع الدقيق",
    descriptionAr = "مطلوب لتحديد موقعك الفعلي تلقائياً وحساب مواقيت الصلاة واتجاه القبلة بدقة.",
    icon = Icons.Filled.LocationOn,
  ),
  NOTIFICATIONS(
    id = "notifications",
    titleAr = "إذن الإشعارات",
    descriptionAr = "مطلوب لإظهار تنبيهات دخول وقت الصلاة والإشعار الدائم لمواقيت اليوم.",
    icon = Icons.Filled.NotificationsActive,
  ),
  EXACT_ALARMS(
    id = "exact_alarms",
    titleAr = "تنبيهات الأذان في الوقت الدقيق",
    descriptionAr = "مطلوب لإطلاق الأذان في اللحظة المحددة بالضبط حتى عند قفل الشاشة.",
    icon = Icons.Filled.Alarm,
  ),
  FULL_SCREEN_INTENT(
    id = "full_screen_intent",
    titleAr = "الإشعار بملء الشاشة فوق القفل",
    descriptionAr = "يسمح بظهور شاشة الأذان الكاملة فوق شاشة القفل عند دخول وقت الصلاة (أندرويد 14+).",
    icon = Icons.Filled.PictureInPicture,
  ),
  OVERLAY(
    id = "overlay",
    titleAr = "الظهور فوق التطبيقات",
    descriptionAr = "يسمح بعرض شاشة الأذان فوراً حتى لو كنت تستخدم تطبيقاً آخر.",
    icon = Icons.Filled.Security,
  ),
  BATTERY_OPTIMIZATION(
    id = "battery_optimization",
    titleAr = "استثناء توفير البطارية",
    descriptionAr = "يمنع النظام من إيقاف تنبيهات الأذان والخدمة الدائمة أثناء سكون الجهاز.",
    icon = Icons.Filled.BatteryAlert,
  );
}

@Composable
fun MissingPermissionsCard(
  onLocationPermissionGranted: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current

  var resumeCheckTrigger by remember { mutableStateOf(0) }
  val dismissedMap = remember { mutableStateMapOf<String, Boolean>() }

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        resumeCheckTrigger++
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  val locationLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions(),
  ) { perms ->
    val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
      perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    if (granted) {
      onLocationPermissionGranted()
    }
    resumeCheckTrigger++
  }

  val notificationLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) {
    resumeCheckTrigger++
  }

  fun checkMissingPermissions(): List<AppPermissionType> {
    val missing = mutableListOf<AppPermissionType>()

    // 1. Location
    val fineGranted = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarseGranted = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    if (!fineGranted && !coarseGranted) {
      missing.add(AppPermissionType.LOCATION)
    }

    // 2. Notifications (Android 13+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val notifGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
      if (!notifGranted) {
        missing.add(AppPermissionType.NOTIFICATIONS)
      }
    }

    // 3. Exact Alarms (Android 12+)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      val am = context.getSystemService(AlarmManager::class.java)
      if (am != null && !am.canScheduleExactAlarms()) {
        missing.add(AppPermissionType.EXACT_ALARMS)
      }
    }

    // 4. Full screen intent (Android 14+)
    if (Build.VERSION.SDK_INT >= 34) {
      val nm = context.getSystemService(NotificationManager::class.java)
      if (nm != null && !nm.canUseFullScreenIntent()) {
        missing.add(AppPermissionType.FULL_SCREEN_INTENT)
      }
    }

    // 5. Overlay / Display over other apps
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (!Settings.canDrawOverlays(context)) {
        missing.add(AppPermissionType.OVERLAY)
      }
    }

    // 6. Battery optimizations
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      val pm = context.getSystemService(PowerManager::class.java)
      if (pm != null && !pm.isIgnoringBatteryOptimizations(context.packageName)) {
        missing.add(AppPermissionType.BATTERY_OPTIMIZATION)
      }
    }

    return missing
  }

  val missingList = remember(resumeCheckTrigger) {
    checkMissingPermissions()
  }

  val visibleMissing = remember(missingList, dismissedMap.toMap()) {
    missingList.filter { dismissedMap[it.id] != true }
  }

  if (visibleMissing.isEmpty()) return

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
    ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    modifier = modifier
      .fillMaxWidth()
      .testTag("missing_permissions_card"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Security,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(22.dp),
        )
        Text(
          text = "أذونات مطلوبة لضمان دقة الأذان والمواقيت",
          style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      visibleMissing.forEach { perm ->
        SinglePermissionRow(
          perm = perm,
          onActivate = {
            when (perm) {
              AppPermissionType.LOCATION -> {
                locationLauncher.launch(
                  arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                  ),
                )
              }
              AppPermissionType.NOTIFICATIONS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
              }
              AppPermissionType.EXACT_ALARMS -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                  openSettingSafely(context, Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                }
              }
              AppPermissionType.FULL_SCREEN_INTENT -> {
                if (Build.VERSION.SDK_INT >= 34) {
                  openSettingSafely(context, "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT")
                }
              }
              AppPermissionType.OVERLAY -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                  openSettingSafely(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                }
              }
              AppPermissionType.BATTERY_OPTIMIZATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                  openSettingSafely(context, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                }
              }
            }
          },
          onDismissLater = {
            dismissedMap[perm.id] = true
          },
        )
      }
    }
  }
}

@Composable
private fun SinglePermissionRow(
  perm: AppPermissionType,
  onActivate: () -> Unit,
  onDismissLater: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = perm.icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(20.dp),
        )
        Text(
          text = perm.titleAr,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      Text(
        text = perm.descriptionAr,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(
          onClick = onDismissLater,
          shape = RoundedCornerShape(8.dp),
          modifier = Modifier.testTag("perm_later_${perm.id}"),
        ) {
          Text("لاحقاً", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(8.dp))
        Button(
          onClick = onActivate,
          shape = RoundedCornerShape(8.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
          ),
          modifier = Modifier.testTag("perm_activate_${perm.id}"),
        ) {
          Text("تفعيل", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
      }
    }
  }
}

private fun openSettingSafely(context: Context, action: String) {
  val intent = Intent(action).apply {
    data = Uri.parse("package:${context.packageName}")
    flags = Intent.FLAG_ACTIVITY_NEW_TASK
  }
  runCatching {
    context.startActivity(intent)
  }.onFailure {
    // Fallback to app details
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
      data = Uri.parse("package:${context.packageName}")
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    runCatching { context.startActivity(fallback) }
  }
}
