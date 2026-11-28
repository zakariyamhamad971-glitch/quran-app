package com.example.ui

import android.app.KeyguardManager
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.data.prayer.AdhanNotificationHelper
import com.example.data.prayer.AdhanPlaybackService
import com.example.data.prayer.PrayerType
import com.example.data.prefs.BackgroundTheme
import com.example.data.prefs.ReaderPreferences
import com.example.ui.components.IslamicAmbientBackground
import com.example.ui.theme.AmiriFontFamily
import com.example.ui.theme.QabasatGoldSoft
import com.example.ui.theme.QabasatNightGold
import com.example.ui.theme.QabasatTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdhanActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    configureLockScreenDisplay()
    enableEdgeToEdge()

    val prayerKey = intent.getStringExtra(AdhanNotificationHelper.EXTRA_PRAYER_KEY).orEmpty()
    val prayerType = PrayerType.entries.firstOrNull { it.key == prayerKey } ?: PrayerType.FAJR
    val dateKey = intent.getStringExtra(AdhanNotificationHelper.EXTRA_DATE_KEY).orEmpty()
    val timeText = intent.getStringExtra(AdhanNotificationHelper.EXTRA_PRAYER_TIME_TEXT).orEmpty()
    val locationName = intent.getStringExtra(AdhanNotificationHelper.EXTRA_LOCATION_NAME).orEmpty()
    val isIqama = intent.getBooleanExtra(AdhanNotificationHelper.EXTRA_IS_IQAMA, false)

    setContent {
      val context = LocalContext.current
      val prefs = remember(context) { ReaderPreferences(context.applicationContext) }
      val prayerPrefs by prefs.prayerStateFlow.collectAsStateWithLifecycle(
        initialValue = ReaderPreferences.PersistedPrayerPreferences(),
      )
      val bgPath = prayerPrefs.adhanSettings.backgroundImagePath

      CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        QabasatTheme(
          backgroundTheme = BackgroundTheme.BLACK,
          darkTheme = true,
        ) {
          BackHandler {
            dismissAndFinish()
          }
          AdhanLockScreenContent(
            prayerName = prayerType.arabicName,
            timeText = timeText,
            locationName = locationName,
            backgroundImagePath = bgPath,
            isIqama = isIqama,
            onStartPrayer = { dismissAndFinish() },
            onFinishedPrayer = {
              if (dateKey.isNotBlank()) {
                lifecycleScope.launch {
                  ReaderPreferences(applicationContext).markPrayerCompleted(
                    dateKey = dateKey,
                    prayerKey = prayerType.key,
                  )
                }
              }
              dismissAndFinish()
            },
            onDismiss = { dismissAndFinish() },
          )
        }
      }
    }
  }

  private fun configureLockScreenDisplay() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
      setShowWhenLocked(true)
      setTurnScreenOn(true)
      val km = getSystemService(KeyguardManager::class.java)
      km?.requestDismissKeyguard(this, null)
    } else {
      @Suppress("DEPRECATION")
      window.addFlags(
        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
          WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
          WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
      )
    }
  }

  private fun dismissAndFinish() {
    AdhanPlaybackService.stopAdhan(applicationContext)
    finish()
  }
}

@Composable
private fun AdhanLockScreenContent(
  prayerName: String,
  timeText: String,
  locationName: String,
  backgroundImagePath: String?,
  isIqama: Boolean = false,
  onStartPrayer: () -> Unit,
  onFinishedPrayer: () -> Unit,
  onDismiss: () -> Unit,
) {
  var customBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

  LaunchedEffect(backgroundImagePath) {
    customBitmap = if (backgroundImagePath.isNullOrBlank()) {
      null
    } else {
      withContext(Dispatchers.IO) {
        val file = File(backgroundImagePath)
        if (file.exists() && file.length() > 512L) {
          runCatching { BitmapFactory.decodeFile(backgroundImagePath)?.asImageBitmap() }.getOrNull()
        } else {
          null
        }
      }
    }
  }

  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    if (customBitmap != null) {
      Image(
        bitmap = customBitmap!!,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(
            Brush.verticalGradient(
              colors = listOf(
                Color(0x99061613),
                Color(0xB8061613),
                Color(0xE0040E0C),
              ),
            ),
          ),
      )
    } else {
      IslamicAmbientBackground(isDarkTheme = true)
    }

    Column(
      modifier = Modifier
        .widthIn(max = 520.dp)
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.statusBars)
        .windowInsetsPadding(WindowInsets.navigationBars)
        .padding(horizontal = 24.dp, vertical = 28.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.SpaceBetween,
    ) {
      Spacer(Modifier.height(12.dp))

      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Surface(
          shape = CircleShape,
          color = Color(0xFF143833),
          border = BorderStroke(1.5.dp, QabasatNightGold.copy(alpha = 0.55f)),
          modifier = Modifier.size(96.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Filled.Mosque,
              contentDescription = null,
              tint = QabasatNightGold,
              modifier = Modifier.size(48.dp),
            )
          }
        }

        Text(
          text = if (isIqama) "حان الآن وقت إقامة صلاة" else "حان الآن موعد صلاة",
          style = MaterialTheme.typography.titleMedium,
          color = QabasatGoldSoft.copy(alpha = 0.9f),
          textAlign = TextAlign.Center,
        )

        Text(
          text = prayerName,
          style = MaterialTheme.typography.displaySmall.copy(
            fontFamily = AmiriFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 44.sp,
          ),
          color = Color.White,
          textAlign = TextAlign.Center,
          modifier = Modifier.testTag("adhan_screen_prayer_name"),
        )

        if (timeText.isNotBlank()) {
          Surface(
            shape = RoundedCornerShape(50),
            color = Color.White.copy(alpha = 0.14f),
          ) {
            Text(
              text = timeText,
              style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
              color = QabasatNightGold,
              modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
          }
        }

        if (locationName.isNotBlank()) {
          Text(
            text = locationName,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
          )
        }
      }

      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        if (!isIqama) {
          Button(
            onClick = onStartPrayer,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
              containerColor = QabasatNightGold,
              contentColor = Color(0xFF0A1D19),
            ),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 56.dp)
              .testTag("adhan_start_prayer_button"),
          ) {
            Icon(Icons.Filled.NotificationsActive, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
              text = "بدء الصلاة",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            )
          }

          FilledTonalButton(
            onClick = onFinishedPrayer,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 54.dp)
              .testTag("adhan_finished_prayer_button"),
          ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
              text = "انتهيت من الصلاة",
              style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
          }
        }

        OutlinedButton(
          onClick = onDismiss,
          shape = RoundedCornerShape(16.dp),
          border = BorderStroke(1.5.dp, Color.White.copy(alpha = 0.50f)),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .testTag("adhan_stop_button"),
        ) {
          Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White)
          Spacer(Modifier.width(8.dp))
          Text(
            text = if (isIqama) "إغلاق" else "إيقاف الأذان",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
          )
        }
      }
    }
  }
}
