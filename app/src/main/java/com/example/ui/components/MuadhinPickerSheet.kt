package com.example.ui.components

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.data.prayer.AdhanRecordingItem
import com.example.data.prayer.AdhanSoundRepository
import com.example.data.prayer.KiwifuAdhanParser
import com.example.data.prayer.MuezzinGroup
import com.example.data.prayer.PrayerAlarmConfig
import com.example.data.prayer.PrayerAlarmMode
import com.example.data.prayer.PrayerFormatters
import com.example.data.prayer.PrayerType
import com.example.ui.theme.AmiriFontFamily
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MuadhinPickerSheet(
  prayerType: PrayerType? = null,
  currentConfig: PrayerAlarmConfig = PrayerAlarmConfig(),
  selectedMuezzinIds: Set<String> = emptySet(),
  onSelectForPrayer: (targetPrayer: PrayerType, group: MuezzinGroup, recording: AdhanRecordingItem) -> Unit,
  onSelectForAllPrayers: (group: MuezzinGroup, recording: AdhanRecordingItem) -> Unit,
  onDismiss: () -> Unit,
) {
  BackHandler(onBack = onDismiss)
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val soundRepository = remember(context) { AdhanSoundRepository(context.applicationContext) }
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  var groups by remember { mutableStateOf<List<MuezzinGroup>>(emptyList()) }
  var countries by remember { mutableStateOf<List<String>>(emptyList()) }
  var isLoadingCatalog by remember { mutableStateOf(true) }
  var statusMessage by remember { mutableStateOf<String?>(null) }

  var searchQuery by remember { mutableStateOf("") }
  var selectedCountry by remember { mutableStateOf<String?>(null) } // null = All
  var onlyDownloaded by remember { mutableStateOf(false) }

  // Target scope when opened generally (prayerType == null): null = All 5 prayers, else specific PrayerType
  var applyTargetPrayer by remember(prayerType) { mutableStateOf(prayerType) }

  // Expanded muezzin groups
  val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

  // Download tracking maps
  val downloadProgressMap = remember { mutableStateMapOf<String, Float>() }
  val downloadedMap = remember { mutableStateMapOf<String, Boolean>() }
  var selectingRecordingId by remember { mutableStateOf<String?>(null) }

  // Full-adhan preview player state
  var activeRecording by remember { mutableStateOf<AdhanRecordingItem?>(null) }
  var activeGroupName by remember { mutableStateOf("") }
  var isBufferingPreview by remember { mutableStateOf(false) }
  var isPlayingPreview by remember { mutableStateOf(false) }
  var previewPositionMs by remember { mutableIntStateOf(0) }
  var previewDurationMs by remember { mutableIntStateOf(0) }
  var playerInstance by remember { mutableStateOf<MediaPlayer?>(null) }
  var isUserSeeking by remember { mutableStateOf(false) }
  var seekSliderMs by remember { mutableFloatStateOf(0f) }

  fun stopPreview() {
    playerInstance?.runCatching {
      if (isPlaying) stop()
      reset()
      release()
    }
    playerInstance = null
    activeRecording = null
    activeGroupName = ""
    isBufferingPreview = false
    isPlayingPreview = false
    previewPositionMs = 0
    previewDurationMs = 0
  }

  DisposableEffect(Unit) {
    onDispose {
      stopPreview()
    }
  }

  // Poll MediaPlayer position while preview is active
  LaunchedEffect(playerInstance, isPlayingPreview) {
    val mp = playerInstance ?: return@LaunchedEffect
    while (isActive && playerInstance === mp) {
      runCatching {
        if (!isUserSeeking) {
          val dur = mp.duration.coerceAtLeast(0)
          val pos = mp.currentPosition.coerceAtLeast(0)
          previewDurationMs = dur
          previewPositionMs = pos
          seekSliderMs = pos.toFloat()
        }
        isPlayingPreview = mp.isPlaying
      }
      delay(250L)
    }
  }

  fun startPreviewUri(
    recording: AdhanRecordingItem,
    groupName: String,
    uri: Uri,
    fallbackRemoteUrl: String? = null,
  ) {
    scope.launch(Dispatchers.Main.immediate) {
      runCatching {
        val mp = MediaPlayer().apply {
          setAudioAttributes(
            AudioAttributes.Builder()
              .setUsage(AudioAttributes.USAGE_MEDIA)
              .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
              .build(),
          )
          setDataSource(context, uri)
          setOnPreparedListener { player ->
            isBufferingPreview = false
            previewDurationMs = player.duration.coerceAtLeast(0)
            previewPositionMs = 0
            runCatching {
              player.start()
              isPlayingPreview = true
            }.onFailure {
              statusMessage = "تعذر بدء تشغيل صوت الأذان"
              stopPreview()
            }
          }
          setOnCompletionListener {
            isPlayingPreview = false
            previewPositionMs = 0
            seekSliderMs = 0f
            runCatching { it.seekTo(0) }
          }
          setOnErrorListener { _, _, _ ->
            if (!fallbackRemoteUrl.isNullOrBlank() && uri.toString() != fallbackRemoteUrl) {
              playerInstance?.runCatching { reset(); release() }
              playerInstance = null
              startPreviewUri(recording, groupName, Uri.parse(fallbackRemoteUrl), null)
            } else {
              statusMessage = "تعذر تحميل أو تشغيل هذا التسجيل"
              stopPreview()
            }
            true
          }
          prepareAsync()
        }
        playerInstance = mp
      }.onFailure {
        if (!fallbackRemoteUrl.isNullOrBlank() && uri.toString() != fallbackRemoteUrl) {
          startPreviewUri(recording, groupName, Uri.parse(fallbackRemoteUrl), null)
        } else {
          statusMessage = "تعذر تشغيل المعاينة"
          stopPreview()
        }
      }
    }
  }

  fun togglePlayRecording(group: MuezzinGroup, recording: AdhanRecordingItem) {
    if (activeRecording?.id == recording.id && playerInstance != null) {
      val mp = playerInstance ?: return
      runCatching {
        if (mp.isPlaying) {
          mp.pause()
          isPlayingPreview = false
        } else if (!isBufferingPreview) {
          mp.start()
          isPlayingPreview = true
        }
      }
      return
    }
    stopPreview()
    statusMessage = null
    activeRecording = recording
    activeGroupName = group.arabicName
    isBufferingPreview = true

    val localFile = soundRepository.getLocalMuezzinAudioFile(recording.id)
    if (localFile.exists() && localFile.length() > 4096L) {
      startPreviewUri(recording, group.arabicName, Uri.fromFile(localFile), recording.primaryUrl)
    } else {
      startPreviewUri(
        recording = recording,
        groupName = group.arabicName,
        uri = Uri.parse(recording.primaryUrl),
        fallbackRemoteUrl = recording.fallbackUrl,
      )
    }
  }

  fun seekPreviewTo(targetMs: Int) {
    val mp = playerInstance ?: return
    val clamped = targetMs.coerceIn(0, previewDurationMs.coerceAtLeast(0))
    runCatching {
      mp.seekTo(clamped)
      previewPositionMs = clamped
      seekSliderMs = clamped.toFloat()
    }
  }

  fun loadCatalog(forceRefresh: Boolean = false) {
    isLoadingCatalog = true
    statusMessage = null
    scope.launch {
      val snapshot = soundRepository.loadMuezzinGroups(forceRefresh = forceRefresh)
      groups = snapshot.groups
      countries = snapshot.countries
      statusMessage = snapshot.loadError
      isLoadingCatalog = false
      withContext(Dispatchers.IO) {
        snapshot.groups.forEach { grp ->
          grp.recordings.forEach { rec ->
            downloadedMap[rec.id] = soundRepository.isMuezzinDownloaded(rec.id)
          }
        }
      }
    }
  }

  LaunchedEffect(Unit) {
    loadCatalog(forceRefresh = false)
  }

  fun triggerDownload(recording: AdhanRecordingItem) {
    if (downloadProgressMap.containsKey(recording.id)) return
    downloadProgressMap[recording.id] = 0f
    statusMessage = null
    scope.launch {
      val res = soundRepository.downloadRecording(recording) { written, total ->
        val frac = if (total > 0L) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f
        downloadProgressMap[recording.id] = frac
      }
      downloadProgressMap.remove(recording.id)
      if (res.isSuccess) {
        downloadedMap[recording.id] = true
      } else {
        statusMessage = res.exceptionOrNull()?.message ?: "تعذر تنزيل التسجيل"
      }
    }
  }

  fun triggerDelete(recording: AdhanRecordingItem) {
    if (activeRecording?.id == recording.id) {
      stopPreview()
    }
    soundRepository.deleteDownloadedMuezzin(recording.id)
    downloadedMap[recording.id] = false
  }

  fun handleSelectRecording(group: MuezzinGroup, recording: AdhanRecordingItem) {
    if (selectingRecordingId != null) return
    statusMessage = null
    selectingRecordingId = recording.id
    scope.launch {
      val target = applyTargetPrayer
      // Ensure primary chosen recording is downloaded first
      if (!soundRepository.isMuezzinDownloaded(recording.id)) {
        downloadProgressMap[recording.id] = 0f
        val res = soundRepository.downloadRecording(recording) { written, total ->
          val frac = if (total > 0L) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f
          downloadProgressMap[recording.id] = frac
        }
        downloadProgressMap.remove(recording.id)
        if (res.isFailure) {
          selectingRecordingId = null
          statusMessage = res.exceptionOrNull()?.message ?: "تعذر تنزيل التسجيل قبل التعيين"
          return@launch
        }
        downloadedMap[recording.id] = true
      }

      if (target == null) {
        // Apply to all 5 prayers — also pre-download Fajr recording if distinct and available
        val fajrRec = KiwifuAdhanParser.selectRecordingForPrayer(group, recording, PrayerType.FAJR)
        if (fajrRec.id != recording.id && !soundRepository.isMuezzinDownloaded(fajrRec.id)) {
          downloadProgressMap[fajrRec.id] = 0f
          val fajrRes = soundRepository.downloadRecording(fajrRec) { written, total ->
            val frac = if (total > 0L) (written.toFloat() / total.toFloat()).coerceIn(0f, 1f) else -1f
            downloadProgressMap[fajrRec.id] = frac
          }
          downloadProgressMap.remove(fajrRec.id)
          if (fajrRes.isSuccess) {
            downloadedMap[fajrRec.id] = true
          }
        }
        selectingRecordingId = null
        onSelectForAllPrayers(group, recording)
        onDismiss()
      } else {
        // If single prayer is Fajr and user picked another recording, respect selected recording directly
        selectingRecordingId = null
        onSelectForPrayer(target, group, recording)
        onDismiss()
      }
    }
  }

  val downloadedStateSnapshot = downloadedMap.toMap()
  val filteredGroups = remember(
    groups,
    searchQuery,
    selectedCountry,
    onlyDownloaded,
    downloadedStateSnapshot,
  ) {
    groups.filter { grp ->
      val matchesCountry = selectedCountry == null || grp.countryAr == selectedCountry
      val matchesSearch = KiwifuAdhanParser.matchesSearchQuery(grp, searchQuery)
      val matchesDl = !onlyDownloaded || grp.recordings.any { downloadedStateSnapshot[it.id] == true }
      matchesCountry && matchesSearch && matchesDl
    }
  }

  val activeConfigIds = remember(currentConfig, selectedMuezzinIds) {
    buildSet {
      currentConfig.muezzinId?.let { add(it) }
      currentConfig.muezzinGroupId?.let { add(it) }
      addAll(selectedMuezzinIds)
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
        .padding(bottom = 20.dp),
    ) {
      // Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          val countText = if (groups.isNotEmpty()) {
            " (${PrayerFormatters.toArabicIndicDigits(groups.size)})"
          } else {
            ""
          }
          Text(
            text = "المؤذنون$countText",
            style = MaterialTheme.typography.titleLarge.copy(
              fontFamily = AmiriFontFamily,
              fontWeight = FontWeight.Bold,
              fontSize = 24.sp,
            ),
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = if (applyTargetPrayer != null) {
              "استمع وحمّل واختر مؤذن صلاة ${applyTargetPrayer?.arabicName}"
            } else {
              "استمع للأذان كاملاً وحمّله للعمل بدون إنترنت لجميع الصلوات"
            },
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
        Spacer(Modifier.height(6.dp))
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

      // Target prayer scope selector (visible when opened standalone or allowing scope switch)
      Spacer(Modifier.height(8.dp))
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilterChip(
          selected = applyTargetPrayer == null,
          onClick = { applyTargetPrayer = null },
          label = { Text("جميع الصلوات") },
          modifier = Modifier.heightIn(min = 40.dp),
        )
        PrayerType.entries.filter { it != PrayerType.SUNRISE }.forEach { pt ->
          FilterChip(
            selected = applyTargetPrayer == pt,
            onClick = { applyTargetPrayer = pt },
            label = { Text(pt.arabicName) },
            modifier = Modifier.heightIn(min = 40.dp),
          )
        }
      }

      Spacer(Modifier.height(8.dp))

      // Arabic normalized Search Box
      OutlinedTextField(
        value = searchQuery,
        onValueChange = { searchQuery = it },
        placeholder = { Text("ابحث باسم المؤذن أو البلد (مثل: مصطفى، السديس، مصر)") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
          if (searchQuery.isNotEmpty()) {
            IconButton(onClick = { searchQuery = "" }) {
              Icon(Icons.Filled.Close, contentDescription = "مسح البحث")
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

      // Country + Offline Filter Chips
      val totalDownloadedCount = downloadedStateSnapshot.values.count { it }
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilterChip(
          selected = selectedCountry == null && !onlyDownloaded,
          onClick = {
            selectedCountry = null
            onlyDownloaded = false
          },
          label = { Text("الكل (${PrayerFormatters.toArabicIndicDigits(groups.size)})") },
        )
        FilterChip(
          selected = onlyDownloaded,
          onClick = { onlyDownloaded = !onlyDownloaded },
          label = { Text("المحفوظة (${PrayerFormatters.toArabicIndicDigits(totalDownloadedCount)})") },
        )
        countries.forEach { country ->
          FilterChip(
            selected = selectedCountry == country,
            onClick = {
              selectedCountry = if (selectedCountry == country) null else country
            },
            label = { Text(country) },
          )
        }
      }

      // Live Full-Adhan Audio Controller Bar when playing/paused
      AnimatedVisibility(visible = activeRecording != null) {
        val currentRec = activeRecording
        if (currentRec != null) {
          Spacer(Modifier.height(8.dp))
          Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("muadhin_preview_player_bar"),
          ) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = activeGroupName,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                  Text(
                    text = currentRec.arabicTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                IconButton(
                  onClick = { stopPreview() },
                  modifier = Modifier.size(48.dp),
                ) {
                  Icon(Icons.Filled.Stop, contentDescription = "إيقاف المعاينة")
                }
              }

              val dur = previewDurationMs.coerceAtLeast(0)
              val sliderMax = dur.coerceAtLeast(1).toFloat()
              val sliderVal = if (isUserSeeking) {
                seekSliderMs.coerceIn(0f, sliderMax)
              } else {
                previewPositionMs.coerceIn(0, dur).toFloat()
              }

              Slider(
                value = sliderVal,
                onValueChange = {
                  isUserSeeking = true
                  seekSliderMs = it
                },
                onValueChangeFinished = {
                  isUserSeeking = false
                  seekPreviewTo(seekSliderMs.toInt())
                },
                valueRange = 0f..sliderMax,
                enabled = dur > 0 && !isBufferingPreview,
                modifier = Modifier.fillMaxWidth(),
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  text = formatMmSsArabic(previewPositionMs),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  IconButton(
                    onClick = { seekPreviewTo(previewPositionMs - 10_000) },
                    enabled = dur > 0 && !isBufferingPreview,
                    modifier = Modifier.size(48.dp),
                  ) {
                    Icon(Icons.Filled.Replay10, contentDescription = "تأخير ١٠ ثوانٍ")
                  }
                  Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                      .size(48.dp)
                      .clip(CircleShape)
                      .clickable {
                        groups.firstOrNull { g -> g.recordings.any { it.id == currentRec.id } }?.let { grp ->
                          togglePlayRecording(grp, currentRec)
                        }
                      },
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      if (isBufferingPreview) {
                        CircularProgressIndicator(
                          modifier = Modifier.size(22.dp),
                          strokeWidth = 2.dp,
                          color = MaterialTheme.colorScheme.onPrimary,
                        )
                      } else {
                        Icon(
                          imageVector = if (isPlayingPreview) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                          contentDescription = if (isPlayingPreview) "إيقاف مؤقت" else "تشغيل",
                          tint = MaterialTheme.colorScheme.onPrimary,
                        )
                      }
                    }
                  }
                  IconButton(
                    onClick = { seekPreviewTo(previewPositionMs + 10_000) },
                    enabled = dur > 0 && !isBufferingPreview,
                    modifier = Modifier.size(48.dp),
                  ) {
                    Icon(Icons.Filled.Forward10, contentDescription = "تقديم ١٠ ثوانٍ")
                  }
                }
                Text(
                  text = formatMmSsArabic(dur),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }
      }

      Spacer(Modifier.height(8.dp))

      if (isLoadingCatalog) {
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
              text = "جاري جلب قائمة المؤذنين...",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else if (filteredGroups.isEmpty()) {
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
              onlyDownloaded -> "لا توجد تسجيلات محمّلة بعد"
              groups.isEmpty() -> "لم تتوفر قائمة المؤذنين حالياً"
              else -> "لا توجد نتائج مطابقة للبحث"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (groups.isEmpty() && !onlyDownloaded) {
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
            .heightIn(max = 460.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          items(filteredGroups, key = { it.id }) { group ->
            val isExpanded = expandedGroups[group.id] == true
            val isGroupSelected = activeConfigIds.contains(group.id) ||
              group.recordings.any { activeConfigIds.contains(it.id) }

            MuezzinGroupCard(
              group = group,
              isExpanded = isExpanded,
              isSelected = isGroupSelected,
              activeRecordingId = activeRecording?.id,
              isBufferingPreview = isBufferingPreview,
              isPlayingPreview = isPlayingPreview,
              selectingRecordingId = selectingRecordingId,
              activeConfigIds = activeConfigIds,
              downloadedMap = downloadedStateSnapshot,
              downloadProgressMap = downloadProgressMap.toMap(),
              onToggleExpand = {
                expandedGroups[group.id] = !isExpanded
              },
              onPlayRecording = { rec -> togglePlayRecording(group, rec) },
              onDownloadRecording = { rec -> triggerDownload(rec) },
              onDeleteRecording = { rec -> triggerDelete(rec) },
              onSelectRecording = { rec -> handleSelectRecording(group, rec) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MuezzinGroupCard(
  group: MuezzinGroup,
  isExpanded: Boolean,
  isSelected: Boolean,
  activeRecordingId: String?,
  isBufferingPreview: Boolean,
  isPlayingPreview: Boolean,
  selectingRecordingId: String?,
  activeConfigIds: Set<String>,
  downloadedMap: Map<String, Boolean>,
  downloadProgressMap: Map<String, Float>,
  onToggleExpand: () -> Unit,
  onPlayRecording: (AdhanRecordingItem) -> Unit,
  onDownloadRecording: (AdhanRecordingItem) -> Unit,
  onDeleteRecording: (AdhanRecordingItem) -> Unit,
  onSelectRecording: (AdhanRecordingItem) -> Unit,
) {
  val cardBg = if (isSelected) {
    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f)
  } else {
    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.34f)
  }
  val border = if (isSelected) {
    BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
  } else {
    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.16f))
  }

  Surface(
    shape = RoundedCornerShape(16.dp),
    color = cardBg,
    border = border,
    modifier = Modifier
      .fillMaxWidth()
      .testTag("muadhin_group_${group.id}"),
  ) {
    Column(modifier = Modifier.fillMaxWidth()) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 60.dp)
          .clickable(onClick = onToggleExpand)
          .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              text = group.arabicName,
              style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = AmiriFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
              ),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false),
            )
            if (isSelected) {
              Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "مختار حالياً",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
              )
            }
            if (group.hasFajr) {
              Surface(
                shape = RoundedCornerShape(6.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
              ) {
                Text(
                  text = "فجر",
                  style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSecondaryContainer,
                  modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
              }
            }
          }

          Spacer(Modifier.height(2.dp))

          val count = group.recordings.size
          val countLabel = if (count == 1) "تسجيل واحد" else "${PrayerFormatters.toArabicIndicDigits(count)} تسجيلات"
          val placePart = buildList {
            if (group.countryAr.isNotBlank()) add(group.countryAr)
            group.placeDetailAr?.takeIf { it.isNotBlank() && it != group.countryAr }?.let { add(it) }
            add(countLabel)
          }.joinToString(" • ")

          Text(
            text = placePart,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }

        Icon(
          imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
          contentDescription = if (isExpanded) "طي التسجيلات" else "عرض التسجيلات",
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(24.dp),
        )
      }

      AnimatedVisibility(visible = isExpanded) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
          group.recordings.forEach { rec ->
            val isPlayingThis = activeRecordingId == rec.id
            val isDownloaded = downloadedMap[rec.id] == true
            val progress = downloadProgressMap[rec.id]
            val isDownloading = progress != null
            val isSelectingThis = selectingRecordingId == rec.id
            val isRecSelected = activeConfigIds.contains(rec.id)

            RecordingRowItem(
              recording = rec,
              isPlayingThis = isPlayingThis,
              isBuffering = isPlayingThis && isBufferingPreview,
              isActivelyPlaying = isPlayingThis && isPlayingPreview,
              isDownloaded = isDownloaded,
              isDownloading = isDownloading,
              downloadProgress = progress ?: 0f,
              isSelecting = isSelectingThis,
              isSelected = isRecSelected,
              onPlay = { onPlayRecording(rec) },
              onDownload = { onDownloadRecording(rec) },
              onDelete = { onDeleteRecording(rec) },
              onSelect = { onSelectRecording(rec) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun RecordingRowItem(
  recording: AdhanRecordingItem,
  isPlayingThis: Boolean,
  isBuffering: Boolean,
  isActivelyPlaying: Boolean,
  isDownloaded: Boolean,
  isDownloading: Boolean,
  downloadProgress: Float,
  isSelecting: Boolean,
  isSelected: Boolean,
  onPlay: () -> Unit,
  onDownload: () -> Unit,
  onDelete: () -> Unit,
  onSelect: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = if (isSelected) {
      MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
      MaterialTheme.colorScheme.surface.copy(alpha = 0.86f)
    },
    border = BorderStroke(
      width = if (isSelected) 1.2.dp else 1.dp,
      color = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
      } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
      },
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("muadhin_recording_${recording.id}"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Surface(
          shape = CircleShape,
          color = if (isPlayingThis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
          modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onPlay)
            .testTag("muadhin_preview_${recording.id}"),
        ) {
          Box(contentAlignment = Alignment.Center) {
            if (isBuffering) {
              CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary,
              )
            } else {
              Icon(
                imageVector = if (isActivelyPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isActivelyPlaying) "إيقاف مؤقت" else "تشغيل",
                tint = if (isPlayingThis) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
              )
            }
          }
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = recording.arabicTitle,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
          val sizeLabel = formatBytesArabic(recording.sizeBytes)
          if (sizeLabel != null) {
            Text(
              text = if (isDownloaded) "محفوظ • $sizeLabel" else "الحجم: $sizeLabel",
              style = MaterialTheme.typography.labelSmall,
              color = if (isDownloaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        if (isDownloaded) {
          Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = "تم التحميل",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
          IconButton(
            onClick = onDelete,
            modifier = Modifier.size(48.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Delete,
              contentDescription = "حذف الملف المحفوظ",
              tint = MaterialTheme.colorScheme.outline,
              modifier = Modifier.size(18.dp),
            )
          }
        } else if (!isDownloading) {
          IconButton(
            onClick = onDownload,
            modifier = Modifier
              .size(48.dp)
              .testTag("muadhin_download_${recording.id}"),
          ) {
            Icon(
              imageVector = Icons.Filled.Download,
              contentDescription = "تنزيل التسجيل",
              tint = MaterialTheme.colorScheme.primary,
            )
          }
        }

        Button(
          onClick = onSelect,
          enabled = !isSelecting,
          shape = RoundedCornerShape(10.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
          ),
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("muadhin_select_${recording.id}"),
        ) {
          if (isSelecting) {
            CircularProgressIndicator(
              modifier = Modifier.size(16.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimary,
            )
            Spacer(Modifier.width(6.dp))
          }
          Text(
            text = if (isSelected) "مختار" else "اختيار",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
          )
        }
      }

      if (isDownloading) {
        if (downloadProgress >= 0f) {
          LinearProgressIndicator(
            progress = { downloadProgress.coerceIn(0f, 1f) },
            modifier = Modifier
              .fillMaxWidth()
              .height(5.dp)
              .clip(RoundedCornerShape(3.dp)),
          )
        } else {
          LinearProgressIndicator(
            modifier = Modifier
              .fillMaxWidth()
              .height(5.dp)
              .clip(RoundedCornerShape(3.dp)),
          )
        }
      }
    }
  }
}

private fun formatMmSsArabic(ms: Int): String {
  val totalSec = (ms / 1000).coerceAtLeast(0)
  val mins = totalSec / 60
  val secs = totalSec % 60
  val raw = String.format(Locale.US, "%d:%02d", mins, secs)
  return PrayerFormatters.toArabicIndicDigits(raw)
}

private fun formatBytesArabic(bytes: Long): String? {
  if (bytes <= 0L) return null
  return if (bytes >= 1024L * 1024L) {
    val mb = bytes / (1024.0 * 1024.0)
    "${PrayerFormatters.toArabicIndicDigits(String.format(Locale.US, "%.1f", mb))} م.ب"
  } else {
    val kb = (bytes / 1024L).coerceAtLeast(1L)
    "${PrayerFormatters.toArabicIndicDigits(kb.toString())} ك.ب"
  }
}
