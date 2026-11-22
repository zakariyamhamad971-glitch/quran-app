package com.example.data.audio

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

enum class SurahDownloadStatus {
  NOT_DOWNLOADED,
  QUEUED,
  DOWNLOADING,
  PAUSED_OFFLINE,
  FAILED,
  DOWNLOADED,
}

@Immutable
data class SurahTransferState(
  val reciterKey: String,
  val surahId: Int,
  val status: SurahDownloadStatus,
  val progress: Float = 0f,
  val errorMessage: String? = null,
  val isLowStorage: Boolean = false,
)

@Immutable
data class AudioBatchUiSummary(
  val activeCount: Int = 0,
  val queuedCount: Int = 0,
  val pausedOfflineCount: Int = 0,
  val completedInRunCount: Int = 0,
  val totalInRunCount: Int = 0,
  val currentReciterName: String = "",
  val currentSurahName: String = "",
  val overallProgressPercent: Int = 0,
  val pausedOffline: Boolean = false,
  val lowStorageMessage: String? = null,
) {
  val hasWork: Boolean
    get() = activeCount > 0 || queuedCount > 0 || pausedOfflineCount > 0
}

interface NetworkMonitor {
  val isOnline: StateFlow<Boolean>
  fun currentOnline(): Boolean
  fun release() {}
}

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
  private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
  private val _isOnline = MutableStateFlow(checkOnlineNow())
  override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      refresh()
    }

    override fun onLost(network: Network) {
      refresh()
    }

    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
      refresh()
    }
  }

  init {
    runCatching {
      val req = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()
      cm?.registerNetworkCallback(req, callback)
    }
  }

  private fun checkOnlineNow(): Boolean {
    val active = cm?.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(active) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
  }

  private fun refresh() {
    _isOnline.value = checkOnlineNow()
  }

  override fun currentOnline(): Boolean {
    val now = checkOnlineNow()
    _isOnline.value = now
    return now
  }

  override fun release() {
    runCatching { cm?.unregisterNetworkCallback(callback) }
  }
}

internal data class AudioDownloadQueueItem(
  val key: String,
  val reciterKey: String,
  val surahId: Int,
  val url: String,
  val reciterName: String,
  val surahName: String,
)

class AudioDownloadManager internal constructor(
  private val appContext: Context,
  val cacheManager: AudioCacheManager,
  private val networkMonitor: NetworkMonitor,
  private val scope: CoroutineScope,
  private val onStartForegroundService: (Context) -> Unit = { ctx -> AudioDownloadService.start(ctx) },
  private val downloadExecutor: suspend (AudioDownloadQueueItem, (Float) -> Unit) -> Result<File> = { item, onProgress ->
    cacheManager.downloadSurah(
      reciterKey = item.reciterKey,
      surahId = item.surahId,
      url = item.url,
      onProgress = onProgress,
    )
  },
) {
  companion object {
    const val MAX_PARALLEL_DOWNLOADS = 2
    private val BACKOFF_DELAYS_MS = longArrayOf(1_000L, 2_500L, 5_000L)

    @Volatile
    private var instance: AudioDownloadManager? = null

    fun getInstance(context: Context): AudioDownloadManager {
      return instance ?: synchronized(this) {
        instance ?: createDefault(context.applicationContext).also { instance = it }
      }
    }

    private fun createDefault(appContext: Context): AudioDownloadManager {
      val cache = AudioCacheManager.getInstance(appContext)
      val monitor = AndroidNetworkMonitor(appContext)
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
      return AudioDownloadManager(
        appContext = appContext,
        cacheManager = cache,
        networkMonitor = monitor,
        scope = scope,
      )
    }
  }

  private val queueFile = File(cacheManager.getAudioRootDir(), "download_queue.json")
  private val stateMutex = Mutex()
  private val pendingQueue = LinkedHashMap<String, AudioDownloadQueueItem>()
  private val activeJobs = HashMap<String, Job>()
  private val activeItems = HashMap<String, AudioDownloadQueueItem>()
  private var runTotalCount = 0
  private var runCompletedCount = 0

  private val _transferStates = MutableStateFlow<Map<String, SurahTransferState>>(emptyMap())
  val transferStates: StateFlow<Map<String, SurahTransferState>> = _transferStates.asStateFlow()

  private val _batchSummary = MutableStateFlow(AudioBatchUiSummary())
  val batchSummary: StateFlow<AudioBatchUiSummary> = _batchSummary.asStateFlow()

  val downloadedKeys: StateFlow<Set<String>> get() = cacheManager.downloadedKeys
  val reciterSummaries: StateFlow<Map<String, ReciterStorageSummary>> get() = cacheManager.reciterSummaries

  init {
    scope.launch {
      cacheManager.refreshDownloadedKeys()
      restorePersistedQueue()
    }
    scope.launch {
      networkMonitor.isOnline.collect { online ->
        handleConnectivityChanged(online)
      }
    }
  }

  private suspend fun restorePersistedQueue() {
    val restored = loadPersistedQueue()
    if (restored.isEmpty()) return
    stateMutex.withLock {
      for (item in restored) {
        if (!cacheManager.isSurahCachedQuick(item.reciterKey, item.surahId) && !pendingQueue.containsKey(item.key)) {
          pendingQueue[item.key] = item
          runTotalCount++
          val initialStatus = if (networkMonitor.currentOnline()) {
            SurahDownloadStatus.QUEUED
          } else {
            SurahDownloadStatus.PAUSED_OFFLINE
          }
          _transferStates.update { current ->
            current + (item.key to SurahTransferState(item.reciterKey, item.surahId, initialStatus))
          }
        }
      }
      persistQueueLocked()
      publishSummaryLocked()
    }
    if (networkMonitor.currentOnline() && restored.isNotEmpty()) {
      dispatchIfPossible()
    }
  }

  fun enqueueSingleSurah(
    reciter: VerifiedReciter,
    surahId: Int,
    surahName: String,
    url: String,
  ) {
    if (surahId !in 1..114 || url.isBlank()) return
    val key = cacheManager.cacheKey(reciter.key, surahId)
    scope.launch {
      if (cacheManager.getLocalAudioFileIfValid(reciter.key, surahId) != null) {
        return@launch
      }
      enqueueItems(
        listOf(
          AudioDownloadQueueItem(
            key = key,
            reciterKey = reciter.key,
            surahId = surahId,
            url = url,
            reciterName = reciter.displayName,
            surahName = surahName.ifBlank { "سورة $surahId" },
          ),
        ),
        clearLowStorage = true,
      )
    }
  }

  fun enqueueAutoDownloadOnPlay(
    reciter: VerifiedReciter,
    surahId: Int,
    surahName: String,
    url: String,
  ) {
    if (surahId !in 1..114 || url.isBlank()) return
    val key = cacheManager.cacheKey(reciter.key, surahId)
    scope.launch {
      if (cacheManager.getLocalAudioFileIfValid(reciter.key, surahId) != null) {
        return@launch
      }
      val alreadyTracked = stateMutex.withLock {
        pendingQueue.containsKey(key) || activeItems.containsKey(key)
      }
      if (alreadyTracked) return@launch

      enqueueItems(
        listOf(
          AudioDownloadQueueItem(
            key = key,
            reciterKey = reciter.key,
            surahId = surahId,
            url = url,
            reciterName = reciter.displayName,
            surahName = surahName.ifBlank { "سورة $surahId" },
          ),
        ),
        clearLowStorage = false,
      )
    }
  }

  fun enqueueReciterBatch(
    reciter: VerifiedReciter,
    surahNamesById: Map<Int, String>,
    urlBuilder: (VerifiedReciter, Int) -> String?,
  ) {
    val surahs = reciter.availableSurahs.sorted()
    if (surahs.isEmpty()) return
    scope.launch {
      cacheManager.refreshDownloadedKeys()
      val items = ArrayList<AudioDownloadQueueItem>(surahs.size)
      for (surahId in surahs) {
        if (cacheManager.isSurahCachedQuick(reciter.key, surahId)) continue
        val url = urlBuilder(reciter, surahId) ?: continue
        val key = cacheManager.cacheKey(reciter.key, surahId)
        items += AudioDownloadQueueItem(
          key = key,
          reciterKey = reciter.key,
          surahId = surahId,
          url = url,
          reciterName = reciter.displayName,
          surahName = surahNamesById[surahId].orEmpty().ifBlank { "سورة $surahId" },
        )
      }
      if (items.isNotEmpty()) {
        enqueueItems(items, clearLowStorage = true)
      }
    }
  }

  private suspend fun enqueueItems(
    items: List<AudioDownloadQueueItem>,
    clearLowStorage: Boolean,
  ) {
    if (items.isEmpty()) return
    val online = networkMonitor.currentOnline()
    var shouldStartService = false

    stateMutex.withLock {
      if (clearLowStorage) {
        _batchSummary.update { it.copy(lowStorageMessage = null) }
      }
      if (!hasAnyWorkLocked()) {
        runTotalCount = 0
        runCompletedCount = 0
      }

      for (item in items) {
        if (activeItems.containsKey(item.key) || pendingQueue.containsKey(item.key)) {
          continue
        }
        pendingQueue[item.key] = item
        runTotalCount++
        val initialStatus = if (online) SurahDownloadStatus.QUEUED else SurahDownloadStatus.PAUSED_OFFLINE
        _transferStates.update { current ->
          current + (
            item.key to SurahTransferState(
              reciterKey = item.reciterKey,
              surahId = item.surahId,
              status = initialStatus,
              progress = 0f,
              errorMessage = null,
              isLowStorage = false,
            )
            )
        }
      }
      persistQueueLocked()
      publishSummaryLocked()
      shouldStartService = online && pendingQueue.isNotEmpty()
    }

    if (shouldStartService) {
      runCatching { onStartForegroundService(appContext) }
      dispatchIfPossible()
    }
  }

  fun cancelSurah(reciterKey: String, surahId: Int) {
    val key = cacheManager.cacheKey(reciterKey, surahId)
    scope.launch {
      stateMutex.withLock {
        pendingQueue.remove(key)
        activeItems.remove(key)
        activeJobs.remove(key)?.cancel()
        _transferStates.update { it - key }
        persistQueueLocked()
        if (!hasAnyWorkLocked()) {
          runTotalCount = 0
          runCompletedCount = 0
        }
        publishSummaryLocked()
      }
      cacheManager.deleteTempFile(reciterKey, surahId)
    }
  }

  fun cancelReciter(reciterKey: String) {
    scope.launch {
      val canceledSurahs = mutableListOf<Int>()
      stateMutex.withLock {
        val pendingKeys = pendingQueue.values.filter { it.reciterKey == reciterKey }.map { it.key to it.surahId }
        for ((k, surahId) in pendingKeys) {
          pendingQueue.remove(k)
          canceledSurahs += surahId
          _transferStates.update { it - k }
        }
        val activeKeys = activeItems.values.filter { it.reciterKey == reciterKey }.map { it.key to it.surahId }
        for ((k, surahId) in activeKeys) {
          activeItems.remove(k)
          activeJobs.remove(k)?.cancel()
          canceledSurahs += surahId
          _transferStates.update { it - k }
        }
        persistQueueLocked()
        if (!hasAnyWorkLocked()) {
          runTotalCount = 0
          runCompletedCount = 0
        }
        publishSummaryLocked()
      }
      for (s in canceledSurahs) {
        cacheManager.deleteTempFile(reciterKey, s)
      }
      if (networkMonitor.currentOnline()) {
        dispatchIfPossible()
      }
    }
  }

  fun cancelAll() {
    scope.launch {
      val itemsToClean = mutableListOf<Pair<String, Int>>()
      stateMutex.withLock {
        pendingQueue.values.forEach { itemsToClean += it.reciterKey to it.surahId }
        activeItems.values.forEach { itemsToClean += it.reciterKey to it.surahId }
        pendingQueue.clear()
        activeItems.clear()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        runTotalCount = 0
        runCompletedCount = 0
        _transferStates.update { current ->
          current.filterValues {
            it.status != SurahDownloadStatus.QUEUED &&
              it.status != SurahDownloadStatus.DOWNLOADING &&
              it.status != SurahDownloadStatus.PAUSED_OFFLINE
          }
        }
        persistQueueLocked()
        publishSummaryLocked()
      }
      for ((rKey, sId) in itemsToClean) {
        cacheManager.deleteTempFile(rKey, sId)
      }
    }
  }

  fun deleteSurah(reciterKey: String, surahId: Int) {
    val key = cacheManager.cacheKey(reciterKey, surahId)
    scope.launch {
      stateMutex.withLock {
        pendingQueue.remove(key)
        activeItems.remove(key)
        activeJobs.remove(key)?.cancel()
        _transferStates.update { it - key }
        persistQueueLocked()
        publishSummaryLocked()
      }
      cacheManager.deleteCachedSurah(reciterKey, surahId)
    }
  }

  fun deleteAllForReciter(reciterKey: String) {
    scope.launch {
      stateMutex.withLock {
        val pendingKeys = pendingQueue.values.filter { it.reciterKey == reciterKey }.map { it.key }
        pendingKeys.forEach { pendingQueue.remove(it) }
        val activeKeys = activeItems.values.filter { it.reciterKey == reciterKey }.map { it.key }
        activeKeys.forEach {
          activeItems.remove(it)
          activeJobs.remove(it)?.cancel()
        }
        _transferStates.update { current ->
          current.filterValues { it.reciterKey != reciterKey }
        }
        persistQueueLocked()
        if (!hasAnyWorkLocked()) {
          runTotalCount = 0
          runCompletedCount = 0
        }
        publishSummaryLocked()
      }
      cacheManager.deleteAllForReciter(reciterKey)
    }
  }

  fun clearLowStorageMessage() {
    _batchSummary.update { it.copy(lowStorageMessage = null) }
  }

  private suspend fun handleConnectivityChanged(online: Boolean) {
    if (!online) {
      stateMutex.withLock {
        val pausedItems = ArrayList<AudioDownloadQueueItem>()
        activeItems.values.forEach { pausedItems += it }
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        activeItems.clear()

        // Put interrupted active items back at front of queue so they resume first when online
        if (pausedItems.isNotEmpty() || pendingQueue.isNotEmpty()) {
          val reordered = LinkedHashMap<String, AudioDownloadQueueItem>(pausedItems.size + pendingQueue.size)
          pausedItems.forEach { reordered[it.key] = it }
          pendingQueue.forEach { (k, v) -> reordered.putIfAbsent(k, v) }
          pendingQueue.clear()
          pendingQueue.putAll(reordered)
        }

        _transferStates.update { current ->
          val next = current.toMutableMap()
          for (item in pendingQueue.values) {
            val prev = next[item.key]
            next[item.key] = SurahTransferState(
              reciterKey = item.reciterKey,
              surahId = item.surahId,
              status = SurahDownloadStatus.PAUSED_OFFLINE,
              progress = prev?.progress ?: 0f,
            )
          }
          next
        }
        persistQueueLocked()
        publishSummaryLocked()
      }
    } else {
      val hasPending = stateMutex.withLock {
        if (pendingQueue.isEmpty()) {
          publishSummaryLocked()
          false
        } else {
          _transferStates.update { current ->
            val next = current.toMutableMap()
            for (item in pendingQueue.values) {
              if (!activeItems.containsKey(item.key)) {
                val prev = next[item.key]
                next[item.key] = SurahTransferState(
                  reciterKey = item.reciterKey,
                  surahId = item.surahId,
                  status = SurahDownloadStatus.QUEUED,
                  progress = prev?.progress ?: 0f,
                )
              }
            }
            next
          }
          publishSummaryLocked()
          true
        }
      }
      if (hasPending) {
        runCatching { onStartForegroundService(appContext) }
        dispatchIfPossible()
      }
    }
  }

  private suspend fun dispatchIfPossible() {
    if (!networkMonitor.currentOnline()) {
      handleConnectivityChanged(false)
      return
    }

    val toLaunch = mutableListOf<AudioDownloadQueueItem>()
    stateMutex.withLock {
      if (!networkMonitor.currentOnline()) return@withLock
      while (activeItems.size < MAX_PARALLEL_DOWNLOADS && pendingQueue.isNotEmpty()) {
        val nextKey = pendingQueue.keys.first()
        val item = pendingQueue.remove(nextKey) ?: continue
        activeItems[item.key] = item
        _transferStates.update { current ->
          current + (
            item.key to SurahTransferState(
              reciterKey = item.reciterKey,
              surahId = item.surahId,
              status = SurahDownloadStatus.DOWNLOADING,
              progress = current[item.key]?.progress?.takeIf { it > 0f } ?: 0.01f,
            )
            )
        }
        toLaunch += item
      }
      if (toLaunch.isNotEmpty()) {
        persistQueueLocked()
      }
      publishSummaryLocked()
    }

    for (item in toLaunch) {
      val job = scope.launch {
        executeItemWithRetries(item)
      }
      stateMutex.withLock {
        if (activeItems.containsKey(item.key)) {
          activeJobs[item.key] = job
        } else {
          job.cancel()
        }
      }
    }
  }

  private suspend fun executeItemWithRetries(item: AudioDownloadQueueItem) {
    var finalResult: Result<File>? = null
    for (attempt in 0..BACKOFF_DELAYS_MS.size) {
      if (!networkMonitor.currentOnline()) {
        pauseSingleItemOffline(item)
        return
      }

      val res = try {
        downloadExecutor(item) { pct ->
          _transferStates.update { current ->
            val st = current[item.key]
            if (st != null && st.status == SurahDownloadStatus.DOWNLOADING) {
              current + (item.key to st.copy(progress = pct))
            } else {
              current
            }
          }
          val nowOnline = networkMonitor.isOnline.value
          if (nowOnline) {
            scope.launch {
              stateMutex.withLock { publishSummaryLocked() }
            }
          }
        }
      } catch (cancel: CancellationException) {
        throw cancel
      }

      if (res.isSuccess) {
        finalResult = res
        break
      }

      val err = res.exceptionOrNull()
      if (err is LowStorageException) {
        handleLowStorageAbort(item, err.message ?: "مساحة التخزين منخفضة، تعذر إكمال التحميل")
        return
      }

      if (!networkMonitor.currentOnline() || isConnectivityError(err)) {
        pauseSingleItemOffline(item)
        return
      }

      if (err is PermanentHttpException) {
        finalResult = res
        break
      }

      finalResult = res
      if (attempt < BACKOFF_DELAYS_MS.size) {
        delay(BACKOFF_DELAYS_MS[attempt])
      }
    }

    stateMutex.withLock {
      activeItems.remove(item.key)
      activeJobs.remove(item.key)
      if (finalResult?.isSuccess == true) {
        runCompletedCount++
        _transferStates.update { it - item.key }
      } else {
        val msg = finalResult?.exceptionOrNull()?.localizedMessage ?: "تعذر تحميل السورة"
        _transferStates.update { current ->
          current + (
            item.key to SurahTransferState(
              reciterKey = item.reciterKey,
              surahId = item.surahId,
              status = SurahDownloadStatus.FAILED,
              progress = 0f,
              errorMessage = msg,
            )
            )
        }
      }
      persistQueueLocked()
      if (!hasAnyWorkLocked()) {
        runTotalCount = 0
        runCompletedCount = 0
      }
      publishSummaryLocked()
    }

    dispatchIfPossible()
  }

  private suspend fun pauseSingleItemOffline(item: AudioDownloadQueueItem) {
    stateMutex.withLock {
      val wasActive = activeItems.remove(item.key) != null
      activeJobs.remove(item.key)
      if (wasActive || !pendingQueue.containsKey(item.key)) {
        val reordered = LinkedHashMap<String, AudioDownloadQueueItem>(pendingQueue.size + 1)
        reordered[item.key] = item
        pendingQueue.forEach { (k, v) -> reordered.putIfAbsent(k, v) }
        pendingQueue.clear()
        pendingQueue.putAll(reordered)
      }
      _transferStates.update { current ->
        val prev = current[item.key]
        current + (
          item.key to SurahTransferState(
            reciterKey = item.reciterKey,
            surahId = item.surahId,
            status = SurahDownloadStatus.PAUSED_OFFLINE,
            progress = prev?.progress ?: 0f,
          )
          )
      }
      persistQueueLocked()
      publishSummaryLocked()
    }
  }

  private suspend fun handleLowStorageAbort(triggerItem: AudioDownloadQueueItem, message: String) {
    val jobsToCancel: List<Job>
    val itemsToClean = mutableListOf<Pair<String, Int>>()
    stateMutex.withLock {
      pendingQueue.values.forEach { itemsToClean += it.reciterKey to it.surahId }
      activeItems.values.forEach { itemsToClean += it.reciterKey to it.surahId }
      pendingQueue.clear()
      activeItems.clear()
      jobsToCancel = activeJobs.values.toList()
      activeJobs.clear()
      runTotalCount = 0
      runCompletedCount = 0
      _transferStates.update { current ->
        val cleaned = current.filterValues {
          it.status != SurahDownloadStatus.QUEUED &&
            it.status != SurahDownloadStatus.DOWNLOADING &&
            it.status != SurahDownloadStatus.PAUSED_OFFLINE
        }.toMutableMap()
        cleaned[triggerItem.key] = SurahTransferState(
          reciterKey = triggerItem.reciterKey,
          surahId = triggerItem.surahId,
          status = SurahDownloadStatus.FAILED,
          progress = 0f,
          errorMessage = message,
          isLowStorage = true,
        )
        cleaned
      }
      persistQueueLocked()
      _batchSummary.value = AudioBatchUiSummary(
        lowStorageMessage = message,
      )
    }
    val currentJob = currentCoroutineContext()[Job]
    for (j in jobsToCancel) {
      if (j != currentJob) j.cancel()
    }
    for ((rKey, sId) in itemsToClean) {
      cacheManager.deleteTempFile(rKey, sId)
    }
  }

  private fun hasAnyWorkLocked(): Boolean =
    activeItems.isNotEmpty() || pendingQueue.isNotEmpty()

  private fun publishSummaryLocked() {
    val transfers = _transferStates.value
    val active = activeItems.size
    val pausedOfflineCount = if (!networkMonitor.isOnline.value) {
      pendingQueue.size
    } else {
      transfers.values.count { it.status == SurahDownloadStatus.PAUSED_OFFLINE }
    }
    val queuedCount = pendingQueue.size
    val firstActive = activeItems.values.firstOrNull() ?: pendingQueue.values.firstOrNull()
    val total = runTotalCount.coerceAtLeast(active + queuedCount + runCompletedCount)
    val activeProgressSum = activeItems.keys.sumOf { k ->
      (transfers[k]?.progress ?: 0f).coerceIn(0f, 1f).toDouble()
    }
    val overallPct = if (total <= 0) {
      0
    } else {
      (((runCompletedCount + activeProgressSum) / total.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    }

    _batchSummary.update { current ->
      AudioBatchUiSummary(
        activeCount = active,
        queuedCount = if (networkMonitor.isOnline.value) queuedCount else 0,
        pausedOfflineCount = pausedOfflineCount,
        completedInRunCount = runCompletedCount,
        totalInRunCount = total,
        currentReciterName = firstActive?.reciterName.orEmpty(),
        currentSurahName = firstActive?.surahName.orEmpty(),
        overallProgressPercent = overallPct,
        pausedOffline = !networkMonitor.isOnline.value && (pendingQueue.isNotEmpty() || activeItems.isNotEmpty()),
        lowStorageMessage = current.lowStorageMessage,
      )
    }
  }

  private fun isConnectivityError(t: Throwable?): Boolean {
    var cur = t
    while (cur != null) {
      if (cur is UnknownHostException || cur is ConnectException || cur is NoRouteToHostException) {
        return true
      }
      cur = cur.cause
    }
    return false
  }

  private fun persistQueueLocked() {
    runCatching {
      val allItems = LinkedHashMap<String, AudioDownloadQueueItem>(activeItems.size + pendingQueue.size)
      activeItems.forEach { (k, v) -> allItems[k] = v }
      pendingQueue.forEach { (k, v) -> allItems.putIfAbsent(k, v) }
      if (allItems.isEmpty()) {
        if (queueFile.exists()) queueFile.delete()
        return@runCatching
      }
      val arr = JSONArray()
      for (item in allItems.values) {
        arr.put(
          JSONObject().apply {
            put("key", item.key)
            put("reciterKey", item.reciterKey)
            put("surahId", item.surahId)
            put("url", item.url)
            put("reciterName", item.reciterName)
            put("surahName", item.surahName)
          },
        )
      }
      queueFile.parentFile?.mkdirs()
      val tmp = File(queueFile.parentFile, "${queueFile.name}.tmp")
      tmp.writeText(arr.toString())
      if (queueFile.exists()) queueFile.delete()
      if (!tmp.renameTo(queueFile)) {
        queueFile.writeText(arr.toString())
        tmp.delete()
      }
    }
  }

  private fun loadPersistedQueue(): List<AudioDownloadQueueItem> {
    if (!queueFile.exists() || queueFile.length() == 0L) return emptyList()
    return runCatching {
      val arr = JSONArray(queueFile.readText())
      buildList(arr.length()) {
        for (i in 0 until arr.length()) {
          val obj = arr.optJSONObject(i) ?: continue
          val rKey = obj.optString("reciterKey")
          val surahId = obj.optInt("surahId", 0)
          val url = obj.optString("url")
          val key = obj.optString("key").ifBlank { cacheManager.cacheKey(rKey, surahId) }
          if (rKey.isNotBlank() && surahId in 1..114 && url.isNotBlank()) {
            add(
              AudioDownloadQueueItem(
                key = key,
                reciterKey = rKey,
                surahId = surahId,
                url = url,
                reciterName = obj.optString("reciterName"),
                surahName = obj.optString("surahName"),
              ),
            )
          }
        }
      }
    }.getOrDefault(emptyList())
  }
}
