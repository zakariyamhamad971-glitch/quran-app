package com.example.ui.components

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.unit.sp
import com.example.data.prayer.AdhanSoundRegistryEntry
import com.example.data.prayer.AdhanSoundRepository
import com.example.data.prayer.PrayerAlarmConfig
import com.example.data.prayer.PrayerAlarmMode
import com.example.data.prayer.PrayerType
import com.example.ui.theme.AmiriFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuadhinPickerSheet(
  prayerType: PrayerType,
  currentConfig: PrayerAlarmConfig,
  onSelectMuezzin: (AdhanSoundRegistryEntry) -> Unit,
  onApplyToAllPrayers: (AdhanSoundRegistryEntry) -> Unit,
  onDismiss: () -> Unit,
) {
  BackHandler(onBack = onDismiss)
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val soundRepository = remember(context) { AdhanSoundRepository(context.applicationContext) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var muadhinList by remember { mutableStateOf<List<AdhanSoundRegistryEntry>>(emptyList()) }
  var isLoadingRegistry by remember { mutableStateOf(true) }
  var statusMessage by remember { mutableStateOf<String?>(null) }
  var searchQuery by remember { mutableStateOf("") }
  var onlyDownloaded by remember { mutableStateOf(false) }

  var playingMuezzinId by remember { mutableStateOf<String?>(null) }
  var isBufferingPreview by remember { mutableStateOf(false) }
  var playerInstance by remember { mutableStateOf<MediaPlayer?>(null) }

  val downloadingMap = remember { mutableStateMapOf<String, Boolean>() }
  val downloadedMap = remember { mutableStateMapOf<String, Boolean>() }

  fun loadCatalog(forceRefresh: Boolean = false) {
    isLoadingRegistry = true
    statusMessage = null
    scope.launch {
      val snapshot = soundRepository.loadRegistry(forceRefresh = forceRefresh)
      muadhinList = snapshot.sounds
      statusMessage = snapshot.loadError
      isLoadingRegistry = false
      withContext(Dispatchers.IO) {
        snapshot.sounds.forEach { entry ->
          downloadedMap[entry.id] = soundRepository.isMuezzinDownloaded(entry.id)
        }
      }
    }
  }

  LaunchedEffect(Unit) {
    loadCatalog(forceRefresh = false)
  }

  fun stopPreview() {
    playerInstance?.runCatching {
      if (isPlaying) stop()
      reset()
      release()
    }
    playerInstance = null
    playingMuezzinId = null
    isBufferingPreview = false
  }

  DisposableEffect(Unit) {
    onDispose {
      stopPreview()
    }
  }

  fun startPreview(entry: AdhanSoundRegistryEntry) {
    if (playingMuezzinId == entry.id) {
      stopPreview()
      return
    }
    stopPreview()
    statusMessage = null

    val localFile = soundRepository.getLocalMuezzinAudioFile(entry.id)
    val hasLocal = localFile.exists() && localFile.length() > 4096L
    val targetUri = if (hasLocal) {
      Uri.fromFile(localFile)
    } else {
      entry.audioUrl?.let { Uri.parse(it) }
    }
    if (targetUri == null) {
      statusMessage = "لا يوجد رابط صوتي متاح لهذا المؤذن"
      return
    }

    playingMuezzinId = entry.id
    isBufferingPreview = true

    // MediaPlayer MUST be created on main thread (with Looper) so prepareAsync callbacks fire.
    scope.launch(Dispatchers.Main.immediate) {
      runCatching {
        val mp = MediaPlayer().apply {
          setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build(),
          )
          setDataSource(context, targetUri)
          setOnPreparedListener { player ->
            isBufferingPreview = false
            runCatching { player.start() }
              .onFailure {
                statusMessage = "تعذر بدء تشغيل صوت الأذان"
                stopPreview()
              }
          }
          setOnCompletionListener {
            stopPreview()
          }
          setOnErrorListener { _, _, _ ->
            statusMessage = "تعذر تحميل أو تشغيل صوت هذا المؤذن"
            stopPreview()
            true
          }
          prepareAsync()
        }
        playerInstance = mp
      }.onFailure {
        statusMessage = "تعذر تشغيل المعاينة لهذا المؤذن"
        stopPreview()
      }
    }
  }

  fun triggerDownload(entry: AdhanSoundRegistryEntry) {
    if (downloadingMap[entry.id] == true) return
    downloadingMap[entry.id] = true
    statusMessage = null
    scope.launch {
      val res = soundRepository.downloadMuezzinAudio(entry)
      downloadingMap[entry.id] = false
      if (res.isSuccess) {
        downloadedMap[entry.id] = true
      } else {
        statusMessage = res.exceptionOrNull()?.message ?: "تعذر تنزيل صوت المؤذن"
      }
    }
  }

  fun triggerDelete(muezzinId: String) {
    soundRepository.deleteDownloadedMuezzin(muezzinId)
    downloadedMap[muezzinId] = false
  }

  val filteredList = remember(muadhinList, searchQuery, onlyDownloaded, downloadedMap.toMap()) {
    val q = searchQuery.trim().lowercase()
    muadhinList.filter { entry ->
      val matchesQuery = q.isEmpty() ||
        entry.muezzinAr.lowercase().contains(q) ||
        entry.nameAr.lowercase().contains(q) ||
        entry.attribution.lowercase().contains(q)
      val matchesDownload = !onlyDownloaded || (downloadedMap[entry.id] == true)
      matchesQuery && matchesDownload
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
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "مكتبة المؤذنين (${muadhinList.size})",
            style = MaterialTheme.typography.titleLarge.copy(
              fontFamily = AmiriFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 22.sp,
            ),
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "استمع لمعاينة الأذان وحمّله للعمل بدون إنترنت لصلاة ${prayerType.arabicName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        IconButton(
          onClick = { loadCatalog(forceRefresh = true) },
          modifier = Modifier.size(48.dp),
        ) {
          Icon(Icons.Filled.Refresh, contentDescription = "تحديث قائمة المؤذنين")
        }
        IconButton(
          onClick = onDismiss,
          modifier = Modifier.size(48.dp),
        ) {
          Icon(Icons.Filled.Close, contentDescription = "إغلاق")
        }
      }

      if (!statusMessage.isNullOrBlank()) {
        Spacer(Modifier.height(8.dp))
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              Icons.Filled.ErrorOutline,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.error,
              modifier = Modifier.size(18.dp),
            )
            Text(
              text = statusMessage.orEmpty(),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.weight(1f),
            )
          }
        }
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("ابحث باسم المؤذن أو الحرم (مثل: مكة، العفاسي، المنشاوي)") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(Icons.Filled.Close, contentDescription = "مسح")
            }
          }
        },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("muadhin_search_input"),
      )

      Spacer(Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilterChip(
          selected = !onlyDownloaded,
          onClick = { onlyDownloaded = false },
          label = { Text("الكل (${muadhinList.size})") },
        )
        val downloadedCount = downloadedMap.values.count { it }
        FilterChip(
          selected = onlyDownloaded,
          onClick = { onlyDownloaded = true },
          label = { Text("المحفوظة بدون إنترنت ($downloadedCount)") },
        )
      }

      Spacer(Modifier.height(10.dp))

      if (isLoadingRegistry) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp))
            Spacer(Modifier.height(10.dp))
            Text(
              text = "جاري جلب أصوات المؤذنين...",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else if (filteredList.isEmpty()) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 180.dp)
            .padding(vertical = 20.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Text(
            text = when {
              onlyDownloaded -> "لا توجد أصوات أذان محمّلة بعد"
              muadhinList.isEmpty() -> "لم تتوفر أصوات مؤذنين حالياً"
              else -> "لا توجد نتائج مطابقة للبحث"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (muadhinList.isEmpty() && !onlyDownloaded) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { loadCatalog(forceRefresh = true) }) {
              Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(Modifier.width(6.dp))
              Text("إعادة المحاولة")
            }
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(filteredList, key = { it.id }) { entry ->
            val isPlaying = playingMuezzinId == entry.id
            val isBuffering = isPlaying && isBufferingPreview
            val isDownloaded = downloadedMap[entry.id] == true
            val isDownloading = downloadingMap[entry.id] == true
            val isCurrentPrayerSelected = currentConfig.mode == PrayerAlarmMode.MUEZZIN &&
              currentConfig.muezzinId == entry.id

            MuadhinItemCard(
              entry = entry,
              prayerName = prayerType.arabicName,
              isPlaying = isPlaying,
              isBuffering = isBuffering,
              isDownloaded = isDownloaded,
              isDownloading = isDownloading,
              isSelected = isCurrentPrayerSelected,
              onTogglePreview = { startPreview(entry) },
              onDownload = { triggerDownload(entry) },
              onDelete = { triggerDelete(entry.id) },
              onSelectForPrayer = {
                onSelectMuezzin(entry)
                onDismiss()
              },
              onApplyToAll = {
                onApplyToAllPrayers(entry)
                onDismiss()
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MuadhinItemCard(
  entry: AdhanSoundRegistryEntry,
  prayerName: String,
  isPlaying: Boolean,
  isBuffering: Boolean,
  isDownloaded: Boolean,
  isDownloading: Boolean,
  isSelected: Boolean,
  onTogglePreview: () -> Unit,
  onDownload: () -> Unit,
  onDelete: () -> Unit,
  onSelectForPrayer: () -> Unit,
  onApplyToAll: () -> Unit,
) {
  val cardBg = if (isSelected) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
  } else {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
  }
  val border = if (isSelected) {
    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
  } else {
    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
  }

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = cardBg,
    border = border,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("muadhin_item_${entry.id}"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Surface(
            shape = CircleShape,
            color = if (isPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
            modifier = Modifier
              .size(48.dp)
              .clip(CircleShape)
              .clickable(onClick = onTogglePreview)
              .testTag("muadhin_preview_${entry.id}"),
          ) {
            Box(contentAlignment = Alignment.Center) {
              if (isBuffering) {
                CircularProgressIndicator(
                  modifier = Modifier.size(22.dp),
                  strokeWidth = 2.dp,
                  color = if (isPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                )
              } else {
                Icon(
                  imageVector = if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                  contentDescription = if (isPlaying) "إيقاف المعاينة" else "استماع تجريبي",
                  tint = if (isPlaying) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(24.dp),
                )
              }
            }
          }

          Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = entry.muezzinAr,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (isSelected) {
                Spacer(Modifier.width(6.dp))
                Icon(
                  imageVector = Icons.Filled.CheckCircle,
                  contentDescription = "تم التحديد",
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
            Text(
              text = entry.nameAr,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          if (isDownloading) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
              CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
          } else if (isDownloaded) {
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
              modifier = Modifier.padding(end = 4.dp),
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Icon(
                  Icons.Filled.Check,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                  text = "أوفلاين",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.primary,
                )
              }
            }
            IconButton(
              onClick = onDelete,
              modifier = Modifier.size(48.dp),
            ) {
              Icon(
                Icons.Filled.Delete,
                contentDescription = "حذف التنزيل",
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
              )
            }
          } else {
            IconButton(
              onClick = onDownload,
              modifier = Modifier
                .size(48.dp)
                .testTag("muadhin_download_${entry.id}"),
            ) {
              Icon(
                Icons.Filled.Download,
                contentDescription = "تحميل للعمل بدون إنترنت",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          }
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Button(
          onClick = onSelectForPrayer,
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
          ),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("muadhin_set_${entry.id}"),
        ) {
          Text(
            text = "تعيين لـ $prayerName",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
          )
        }

        FilledTonalButton(
          onClick = onApplyToAll,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier
            .weight(1f)
            .heightIn(min = 48.dp)
            .testTag("muadhin_set_all_${entry.id}"),
        ) {
          Text(
            text = "تعيين لجميع الصلوات",
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
          )
        }
      }
    }
  }
}
