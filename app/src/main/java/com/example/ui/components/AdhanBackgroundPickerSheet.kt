package com.example.ui.components

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mosque
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.prayer.AdhanBackgroundRepository
import com.example.data.prayer.OnlineAdhanBackgroundOption
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdhanBackgroundPickerSheet(
  currentPath: String?,
  currentLabel: String?,
  onSelectBackground: (path: String?, label: String?) -> Unit,
  onDismiss: () -> Unit,
) {
  BackHandler(onBack = onDismiss)
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val repo = remember(context) { AdhanBackgroundRepository(context.applicationContext) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var onlineOptions by remember { mutableStateOf<List<OnlineAdhanBackgroundOption>>(emptyList()) }
  var isLoadingOnline by remember { mutableStateOf(true) }
  var statusError by remember { mutableStateOf<String?>(null) }
  var downloadingId by remember { mutableStateOf<String?>(null) }

  fun loadOnline() {
    isLoadingOnline = true
    statusError = null
    scope.launch {
      val res = repo.fetchOnlineMosqueBackgrounds()
      isLoadingOnline = false
      res.onSuccess { onlineOptions = it }
        .onFailure { statusError = it.message ?: "تعذر تحميل قائمة الخلفيات" }
    }
  }

  LaunchedEffect(Unit) {
    loadOnline()
  }

  // Zero-permission Android Photo Picker (compliant with Google Play policy)
  val photoPickerLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickVisualMedia(),
  ) { uri ->
    if (uri != null) {
      scope.launch {
        val saved = withContext(Dispatchers.IO) { repo.savePickedImage(uri) }
        saved.onSuccess { (path, label) ->
          onSelectBackground(path, label)
          onDismiss()
        }.onFailure { err ->
          statusError = err.message ?: "تعذر حفظ الصورة المختارة"
        }
      }
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
        .padding(horizontal = 16.dp)
        .padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "خلفية شاشة الأذان",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "الخلفية الحالية: ${currentLabel?.takeIf { it.isNotBlank() } ?: "خلفية قبسات الافتراضية"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
          Icon(Icons.Filled.Close, contentDescription = "إغلاق")
        }
      }

      // Preview of currently selected local image if any
      val currentBitmap = remember(currentPath) {
        currentPath?.takeIf { it.isNotBlank() }?.let { p ->
          val f = File(p)
          if (f.exists()) runCatching { BitmapFactory.decodeFile(p)?.asImageBitmap() }.getOrNull() else null
        }
      }
      if (currentBitmap != null) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(110.dp)
            .clip(RoundedCornerShape(14.dp)),
        ) {
          Image(
            bitmap = currentBitmap,
            contentDescription = "معاينة الخلفية الحالية",
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
          )
          Box(
            modifier = Modifier
              .matchParentSize()
              .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.25f)),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = {
            photoPickerLauncher.launch(
              PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
          },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("adhan_bg_pick_gallery_button"),
        ) {
          Icon(Icons.Filled.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("من المعرض", maxLines = 1)
        }

        OutlinedButton(
          onClick = {
            onSelectBackground(null, null)
            onDismiss()
          },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("adhan_bg_reset_default_button"),
        ) {
          Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(18.dp))
          Spacer(Modifier.width(6.dp))
          Text("الافتراضية", maxLines = 1)
        }
      }

      if (!statusError.isNullOrBlank()) {
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = statusError.orEmpty(),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { loadOnline() }, modifier = Modifier.size(48.dp)) {
              Icon(Icons.Filled.Refresh, contentDescription = "إعادة المحاولة")
            }
          }
        }
      }

      Text(
        text = "خلفيات مساجد إسلامية (تنزيل للاستخدام دون اتصال)",
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
      )

      if (isLoadingOnline) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
          contentAlignment = Alignment.Center,
        ) {
          CircularProgressIndicator(modifier = Modifier.size(32.dp))
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 260.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(onlineOptions, key = { it.id }) { option ->
            val isDownloading = downloadingId == option.id
            val isSelected = currentLabel == option.titleAr
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
              } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
              },
              border = BorderStroke(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
              ),
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = downloadingId == null) {
                  downloadingId = option.id
                  statusError = null
                  scope.launch {
                    val res = repo.downloadOnlineBackground(option)
                    downloadingId = null
                    res.onSuccess { (path, label) ->
                      onSelectBackground(path, label)
                      onDismiss()
                    }.onFailure { err ->
                      statusError = err.message ?: "تعذر تنزيل الخلفية"
                    }
                  }
                },
            ) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(min = 56.dp)
                  .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier.weight(1f),
                ) {
                  Icon(
                    imageVector = Icons.Filled.Mosque,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                  )
                  Spacer(Modifier.width(10.dp))
                  Column {
                    Text(
                      text = option.titleAr,
                      style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                      color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                      text = option.attribution,
                      style = MaterialTheme.typography.labelSmall,
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
                when {
                  isDownloading -> CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                  isSelected -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "محددة",
                    tint = MaterialTheme.colorScheme.primary,
                  )
                  else -> Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "تنزيل وتطبيق",
                    tint = MaterialTheme.colorScheme.primary,
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}
