package com.example.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.launch

private data class MoshafAudioTestState(
  val url: String = "",
  val httpCode: Int? = null,
  val isTesting: Boolean = false,
  val isPlaying: Boolean = false,
  val isSuccess: Boolean = false,
  val statusText: String = "",
)

private class ActiveAudioProbeTracker {
  var activeKey: String? = null
  var activeHttpProbe: AudioHttpProbeResult? = null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RiwayatApiDebugScreen(
  onClose: () -> Unit,
) {
  BackHandler(onBack = onClose)

  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  var reloadNonce by remember { mutableIntStateOf(0) }
  var isLoading by remember { mutableStateOf(true) }
  var loadingProgress by remember { mutableStateOf("جارٍ تحميل الروايات من mp3quran.net...") }
  var fatalError by remember { mutableStateOf<String?>(null) }
  var snapshot by remember { mutableStateOf<DebugFetchSnapshot?>(null) }

  var selectedRiwayaId by remember { mutableStateOf<String?>(null) }
  var dropdownExpanded by remember { mutableStateOf(false) }
  var riwayatSectionExpanded by remember { mutableStateOf(true) }
  var searchQuery by remember { mutableStateOf("") }
  var expandedReciterKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

  var audioTests by remember { mutableStateOf<Map<String, MoshafAudioTestState>>(emptyMap()) }
  val probeTracker = remember { ActiveAudioProbeTracker() }

  val exoPlayer = remember(context) {
    ExoPlayer.Builder(context.applicationContext).build()
  }

  DisposableEffect(exoPlayer) {
    val listener = object : Player.Listener {
      override fun onPlaybackStateChanged(playbackState: Int) {
        val key = probeTracker.activeKey ?: return
        val current = audioTests[key] ?: return
        val http = probeTracker.activeHttpProbe
        when (playbackState) {
          Player.STATE_READY -> {
            val statusMsg = if (http?.httpCode != null) {
              "OK (HTTP ${http.httpCode})"
            } else {
              "OK"
            }
            audioTests = audioTests + (
              key to current.copy(
                isTesting = false,
                isPlaying = exoPlayer.isPlaying,
                isSuccess = true,
                statusText = statusMsg,
              )
            )
          }

          Player.STATE_ENDED -> {
            audioTests = audioTests + (key to current.copy(isPlaying = false))
          }

          else -> Unit
        }
      }

      override fun onIsPlayingChanged(isPlaying: Boolean) {
        val key = probeTracker.activeKey ?: return
        val current = audioTests[key] ?: return
        if (!current.isTesting) {
          audioTests = audioTests + (key to current.copy(isPlaying = isPlaying))
        }
      }

      override fun onPlayerError(error: PlaybackException) {
        val key = probeTracker.activeKey ?: return
        val current = audioTests[key] ?: return
        val http = probeTracker.activeHttpProbe
        val realErr = buildString {
          if (http?.httpCode != null) {
            append("HTTP ${http.httpCode} | ")
          } else if (!http?.errorMessage.isNullOrBlank()) {
            append("${http?.errorMessage} | ")
          }
          append("${error.errorCodeName}: ${error.cause?.localizedMessage ?: error.localizedMessage ?: "تعذر التشغيل"}")
        }
        audioTests = audioTests + (
          key to current.copy(
            isTesting = false,
            isPlaying = false,
            isSuccess = false,
            statusText = realErr,
          )
        )
      }
    }
    exoPlayer.addListener(listener)
    onDispose {
      exoPlayer.removeListener(listener)
      exoPlayer.release()
    }
  }

  LaunchedEffect(reloadNonce) {
    isLoading = true
    fatalError = null
    loadingProgress = "جارٍ الاتصال بـ mp3quran.net..."
    val result = RiwayatApiDebugRepository.fetchAllRiwayatAndReciters(
      onProgress = { msg -> loadingProgress = msg },
    )
    result.onSuccess { data ->
      snapshot = data
      isLoading = false
    }.onFailure { err ->
      fatalError = err.localizedMessage ?: "تعذر تحميل بيانات API الروايات"
      isLoading = false
    }
  }

  val allRiwayat = snapshot?.riwayat.orEmpty()
  val allReciters = snapshot?.reciters.orEmpty()
  val filteredReciters = remember(allReciters, selectedRiwayaId, searchQuery) {
    RiwayatApiDebugRepository.filterReciters(
      all = allReciters,
      selectedRiwayaId = selectedRiwayaId,
      searchQuery = searchQuery,
    )
  }

  val selectedRiwayaLabel = remember(selectedRiwayaId, allRiwayat) {
    if (selectedRiwayaId == null) {
      "كل الروايات"
    } else {
      allRiwayat.firstOrNull { it.id == selectedRiwayaId }?.displayLabel ?: selectedRiwayaId.orEmpty()
    }
  }

  fun startAudioTest(moshafKey: String, moshaf: DebugMoshaf) {
    if (moshaf.server == RIWAYAT_MISSING_VALUE || moshaf.server.isBlank()) {
      audioTests = audioTests + (
        moshafKey to MoshafAudioTestState(
          url = RIWAYAT_MISSING_VALUE,
          isTesting = false,
          isPlaying = false,
          isSuccess = false,
          statusText = "خطأ: قيمة server غير موجودة",
        )
      )
      return
    }
    val surahNum = moshaf.firstSurahNumber ?: 1
    val url = RiwayatApiDebugRepository.buildMoshafAudioUrl(moshaf.server, surahNum)
    probeTracker.activeKey = moshafKey
    probeTracker.activeHttpProbe = null
    exoPlayer.stop()
    exoPlayer.clearMediaItems()
    audioTests = audioTests + (
      moshafKey to MoshafAudioTestState(
        url = url,
        isTesting = true,
        isPlaying = false,
        isSuccess = false,
        statusText = "جارٍ فحص الرابط ومحاولة التشغيل...",
      )
    )
    scope.launch {
      val probe = RiwayatApiDebugRepository.probeAudioUrlHttpStatus(url)
      if (probeTracker.activeKey != moshafKey) return@launch
      probeTracker.activeHttpProbe = probe
      audioTests = audioTests + (
        moshafKey to (audioTests[moshafKey] ?: MoshafAudioTestState(url = url)).copy(
          httpCode = probe.httpCode,
          statusText = if (probe.httpCode != null) {
            "HTTP ${probe.httpCode} — جارٍ تشغيل الصوت..."
          } else {
            "${probe.errorMessage ?: "فشل الفحص"} — جارٍ محاولة التشغيل..."
          },
        )
      )
      exoPlayer.setMediaItem(MediaItem.fromUri(url))
      exoPlayer.prepare()
      exoPlayer.playWhenReady = true
    }
  }

  fun stopAudioTest(moshafKey: String) {
    if (probeTracker.activeKey == moshafKey) {
      exoPlayer.stop()
      exoPlayer.clearMediaItems()
    }
    val cur = audioTests[moshafKey] ?: return
    audioTests = audioTests + (moshafKey to cur.copy(isPlaying = false, isTesting = false))
  }

  Surface(
    color = MaterialTheme.colorScheme.background,
    modifier = Modifier
      .fillMaxSize()
      .testTag("riwayat_api_debug_screen"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.statusBars),
    ) {
      // Top header bar
      Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
          ) {
            IconButton(
              onClick = onClose,
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("riwayat_debug_close_button"),
            ) {
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "رجوع",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            Column {
              Text(
                text = "تجربة API الروايات",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text = "شاشة فحص مؤقتة • استجابات mp3quran.net الفعلية",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }

          Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
              onClick = { reloadNonce++ },
              enabled = !isLoading,
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("riwayat_debug_refresh_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "إعادة التحميل",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
            OutlinedButton(
              onClick = {
                val text = RiwayatApiDebugRepository.formatFilteredResultsForClipboard(
                  filtered = filteredReciters,
                  selectedRiwayaLabel = selectedRiwayaLabel,
                )
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("mp3quran_riwayat_debug", text))
                Toast.makeText(
                  context,
                  "تم نسخ النتائج (${filteredReciters.size} قارئ)",
                  Toast.LENGTH_SHORT,
                ).show()
              },
              enabled = snapshot != null,
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
              modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag("riwayat_debug_copy_button"),
            ) {
              Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(Modifier.width(4.dp))
              Text("Copy results", style = MaterialTheme.typography.labelMedium)
            }
          }
        }
      }

      if (isLoading && snapshot == null) {
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
              text = loadingProgress,
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      } else if (fatalError != null && snapshot == null) {
        Box(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = fatalError.orEmpty(),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
            )
            Button(
              onClick = { reloadNonce++ },
              modifier = Modifier.minimumInteractiveComponentSize(),
            ) {
              Text("إعادة المحاولة")
            }
          }
        }
      } else {
        // Filter bar + Riwayat summary + Reciters list
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          // 1) Riwayat returned as "id - name"
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(modifier = Modifier.fillMaxWidth()) {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable { riwayatSectionExpanded = !riwayatSectionExpanded }
                  .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = "الروايات (${allRiwayat.size}): id - name",
                  style = MaterialTheme.typography.labelLarge,
                  color = MaterialTheme.colorScheme.primary,
                )
                Icon(
                  imageVector = if (riwayatSectionExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                )
              }
              AnimatedVisibility(visible = riwayatSectionExpanded) {
                FlowRow(
                  modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                  verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  allRiwayat.forEach { r ->
                    Surface(
                      shape = RoundedCornerShape(8.dp),
                      color = if (selectedRiwayaId == r.id) {
                        MaterialTheme.colorScheme.primaryContainer
                      } else {
                        MaterialTheme.colorScheme.surface
                      },
                      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                      modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                          selectedRiwayaId = if (selectedRiwayaId == r.id) null else r.id
                        },
                    ) {
                      Text(
                        text = r.displayLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selectedRiwayaId == r.id) {
                          MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                          MaterialTheme.colorScheme.onSurface
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                      )
                    }
                  }
                }
              }
            }
          }

          if (!snapshot?.partialErrors.isNullOrEmpty()) {
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                text = "أخطاء جزئية أثناء الجلب:\n" + snapshot?.partialErrors.orEmpty().joinToString("\n"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(8.dp),
              )
            }
          }

          // 3) Filter Bar: Dropdown + Search + Live Counter
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Box(modifier = Modifier.weight(1f)) {
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(48.dp)
                  .clip(RoundedCornerShape(10.dp))
                  .clickable { dropdownExpanded = true }
                  .testTag("riwayat_filter_dropdown"),
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                  Text(
                    text = selectedRiwayaLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                  )
                  Icon(Icons.Filled.ArrowDropDown, contentDescription = "اختر الرواية")
                }
              }

              DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false },
                modifier = Modifier.heightIn(max = 360.dp),
              ) {
                DropdownMenuItem(
                  text = { Text("كل الروايات") },
                  onClick = {
                    selectedRiwayaId = null
                    dropdownExpanded = false
                  },
                )
                HorizontalDivider()
                allRiwayat.forEach { riwaya ->
                  DropdownMenuItem(
                    text = { Text(riwaya.displayLabel) },
                    onClick = {
                      selectedRiwayaId = riwaya.id
                      dropdownExpanded = false
                    },
                  )
                }
              }
            }

            Surface(
              shape = RoundedCornerShape(10.dp),
              color = MaterialTheme.colorScheme.primaryContainer,
              modifier = Modifier.testTag("riwayat_reciters_count"),
            ) {
              Text(
                text = "عدد القرّاء: ${filteredReciters.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
              )
            }
          }

          OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("ابحث عن قارئ (تجاهل التشكيل)...") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
              if (searchQuery.isNotEmpty()) {
                IconButton(onClick = { searchQuery = "" }) {
                  Icon(Icons.Filled.Clear, contentDescription = "مسح البحث")
                }
              }
            },
            singleLine = true,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("riwayat_search_input"),
          )
        }

        // 4) Reciters List
        if (filteredReciters.isEmpty()) {
          Box(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth()
              .windowInsetsPadding(WindowInsets.navigationBars)
              .padding(24.dp),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = "لا يوجد قرّاء مطابقون للفلتر الحالي",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        } else {
          LazyColumn(
            modifier = Modifier
              .weight(1f)
              .fillMaxWidth(),
            contentPadding = PaddingValues(
              start = 12.dp,
              end = 12.dp,
              top = 4.dp,
              bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            items(filteredReciters, key = { it.uniqueKey }) { entry ->
              val isExpanded = expandedReciterKeys.contains(entry.uniqueKey)
              DebugReciterCard(
                entry = entry,
                isExpanded = isExpanded,
                audioTests = audioTests,
                onToggleExpand = {
                  expandedReciterKeys = if (isExpanded) {
                    expandedReciterKeys - entry.uniqueKey
                  } else {
                    expandedReciterKeys + entry.uniqueKey
                  }
                },
                onTestAudio = ::startAudioTest,
                onStopAudio = ::stopAudioTest,
              )
            }
            item {
              Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
            }
          }
        }
      }
    }
  }
}

@Composable
private fun DebugReciterCard(
  entry: DebugReciterEntry,
  isExpanded: Boolean,
  audioTests: Map<String, MoshafAudioTestState>,
  onToggleExpand: () -> Unit,
  onTestAudio: (String, DebugMoshaf) -> Unit,
  onStopAudio: (String) -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surface,
    tonalElevation = 2.dp,
    border = BorderStroke(
      width = if (isExpanded) 1.5.dp else 1.dp,
      color = if (isExpanded) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
      },
    ),
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onToggleExpand)
      .testTag("debug_reciter_row_${entry.uniqueKey}"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = entry.reciterName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Spacer(Modifier.height(2.dp))
          Text(
            text = "الرواية: ${entry.riwayaId} - ${entry.riwayaName}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
          )
          Text(
            text = "عدد المصاحف: ${entry.moshafList.size}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Icon(
          imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
          contentDescription = if (isExpanded) "طي" else "توسيع",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      AnimatedVisibility(visible = isExpanded) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))

          if (entry.moshafList.isEmpty()) {
            Text(
              text = "المصاحف: $RIWAYAT_MISSING_VALUE",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.error,
            )
          } else {
            entry.moshafList.forEachIndexed { idx, moshaf ->
              val moshafKey = "${entry.uniqueKey}_m_$idx"
              val testState = audioTests[moshafKey]
              val surahStartText = if (moshaf.first10Surahs.isEmpty()) {
                RIWAYAT_MISSING_VALUE
              } else {
                moshaf.first10Surahs.joinToString(", ")
              }

              Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth(),
              ) {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                  Text(
                    text = "المصحف: ${moshaf.name}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                  )
                  SelectionContainer {
                    Text(
                      text = "server: ${moshaf.server}",
                      style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        textDirection = TextDirection.Ltr,
                      ),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                  Text(
                    text = "surah_list (أول ١٠): $surahStartText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )

                  Spacer(Modifier.height(4.dp))

                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    OutlinedButton(
                      onClick = { onTestAudio(moshafKey, moshaf) },
                      enabled = testState?.isTesting != true,
                      modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("debug_test_audio_${entry.uniqueKey}_$idx"),
                    ) {
                      if (testState?.isTesting == true) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                      } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                      }
                      Spacer(Modifier.width(4.dp))
                      Text("Test audio")
                    }

                    if (testState?.isPlaying == true || testState?.isTesting == true) {
                      TextButton(
                        onClick = { onStopAudio(moshafKey) },
                        modifier = Modifier.minimumInteractiveComponentSize(),
                      ) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("إيقاف")
                      }
                    }
                  }

                  if (testState != null) {
                    if (testState.url.isNotEmpty()) {
                      SelectionContainer {
                        Text(
                          text = testState.url,
                          style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            textDirection = TextDirection.Ltr,
                          ),
                          color = MaterialTheme.colorScheme.primary,
                        )
                      }
                    }
                    if (testState.statusText.isNotEmpty()) {
                      Text(
                        text = testState.statusText,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = when {
                          testState.isTesting -> MaterialTheme.colorScheme.onSurfaceVariant
                          testState.isSuccess -> MaterialTheme.colorScheme.primary
                          else -> MaterialTheme.colorScheme.error
                        },
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
  }
}
