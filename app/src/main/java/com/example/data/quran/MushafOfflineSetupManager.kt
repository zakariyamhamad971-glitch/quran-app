package com.example.data.quran

import android.os.Build
import androidx.compose.runtime.Immutable
import com.example.data.integrity.VerifiedDiskCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

@Immutable
data class EditionDownloadProgress(
  val totalFiles: Int,
  val completedFiles: Int,
) {
  val remainingFiles: Int
    get() = (totalFiles - completedFiles).coerceAtLeast(0)

  val percent: Int
    get() = if (totalFiles <= 0) 0 else ((completedFiles * 100L) / totalFiles).toInt().coerceIn(0, 100)

  val isDone: Boolean
    get() = totalFiles > 0 && completedFiles >= totalFiles
}

@Immutable
sealed interface MushafSetupState {
  @Immutable
  data object Checking : MushafSetupState

  @Immutable
  data class Required(
    val isScanningCache: Boolean,
    val isDownloading: Boolean,
    val madinah: EditionDownloadProgress,
    val tajweed: EditionDownloadProgress,
    val errorMessage: String? = null,
  ) : MushafSetupState

  @Immutable
  data object Completed : MushafSetupState
}

internal sealed interface SetupTask {
  val relativePath: String

  data object Chapters : SetupTask {
    override val relativePath: String = "api/chapters_ar.json"
  }

  data object Basmalah : SetupTask {
    override val relativePath: String = "api/verse_1_1.json"
  }

  data object Juzs : SetupTask {
    override val relativePath: String = "api/juzs_resolved.json"
  }

  data class PageJson(val page: Int) : SetupTask {
    override val relativePath: String = "api/page_$page.json"
  }

  data class MadinahFont(val page: Int) : SetupTask {
    override val relativePath: String = "fonts/qcf_v2/p$page.ttf"
  }

  data class TajweedFont(val page: Int) : SetupTask {
    override val relativePath: String = "fonts/qcf_v4_raw/p$page.ttf"
  }

  data class TajweedFallbackPage(val page: Int) : SetupTask {
    override val relativePath: String = "api/tajweed_page_$page.json"
  }
}

internal data class CacheScanResult(
  val madinahProgress: EditionDownloadProgress,
  val tajweedProgress: EditionDownloadProgress,
  val pendingMadinah: List<SetupTask>,
  val pendingTajweed: List<SetupTask>,
)

class MushafOfflineSetupManager(
  private val diskCache: VerifiedDiskCache,
  private val quranRepository: QuranRepository,
  private val fontManager: PageFontManager,
  private val scope: CoroutineScope,
  private val includeTajweedFallback: Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU,
) {
  companion object {
    private const val DOWNLOAD_CONCURRENCY = 6
    private const val PROGRESS_EMIT_INTERVAL_MS = 80L

    internal fun buildMadinahTasks(): List<SetupTask> = buildList(1211) {
      add(SetupTask.Chapters)
      add(SetupTask.Basmalah)
      add(SetupTask.Juzs)
      for (page in 1..604) {
        add(SetupTask.PageJson(page))
        add(SetupTask.MadinahFont(page))
      }
    }

    internal fun buildTajweedTasks(includeFallbackJson: Boolean): List<SetupTask> =
      buildList(if (includeFallbackJson) 1208 else 604) {
        for (page in 1..604) {
          add(SetupTask.TajweedFont(page))
          if (includeFallbackJson) {
            add(SetupTask.TajweedFallbackPage(page))
          }
        }
      }

    internal fun isValidFontBytes(bytes: ByteArray): Boolean {
      if (bytes.size < 256) return false
      val b0 = bytes[0].toInt() and 0xFF
      val b1 = bytes[1].toInt() and 0xFF
      val b2 = bytes[2].toInt() and 0xFF
      val b3 = bytes[3].toInt() and 0xFF
      val isSfntTtf = b0 == 0x00 && b1 == 0x01 && b2 == 0x00 && b3 == 0x00
      val isOpenTypeCff = b0 == 'O'.code && b1 == 'T'.code && b2 == 'T'.code && b3 == 'O'.code
      val isTrueTypeApple = b0 == 't'.code && b1 == 'r'.code && b2 == 'u'.code && b3 == 'e'.code
      val isTtc = b0 == 't'.code && b1 == 't'.code && b2 == 'c'.code && b3 == 'f'.code
      return isSfntTtf || isOpenTypeCff || isTrueTypeApple || isTtc
    }
  }

  private val _state = MutableStateFlow<MushafSetupState>(MushafSetupState.Checking)
  val state: StateFlow<MushafSetupState> = _state.asStateFlow()

  private var setupJob: Job? = null

  fun markCompleted() {
    setupJob?.cancel()
    setupJob = null
    _state.value = MushafSetupState.Completed
  }

  fun startOrResume(onCompleted: suspend () -> Unit) {
    if (_state.value is MushafSetupState.Completed) return
    if (setupJob?.isActive == true) return
    launchSetup(onCompleted)
  }

  fun retry(onCompleted: suspend () -> Unit) {
    if (_state.value is MushafSetupState.Completed) return
    setupJob?.cancel()
    launchSetup(onCompleted)
  }

  internal suspend fun inspectCachedProgress(): CacheScanResult = withContext(Dispatchers.IO) {
    val allMadinah = buildMadinahTasks()
    val allTajweed = buildTajweedTasks(includeTajweedFallback)

    val pendingMadinah = ArrayList<SetupTask>(allMadinah.size)
    var madinahDone = 0
    for (task in allMadinah) {
      if (diskCache.getVerifiedFile(task.relativePath) != null) {
        madinahDone++
      } else {
        pendingMadinah += task
      }
    }

    val pendingTajweed = ArrayList<SetupTask>(allTajweed.size)
    var tajweedDone = 0
    for (task in allTajweed) {
      if (diskCache.getVerifiedFile(task.relativePath) != null) {
        tajweedDone++
      } else {
        pendingTajweed += task
      }
    }

    CacheScanResult(
      madinahProgress = EditionDownloadProgress(
        totalFiles = allMadinah.size,
        completedFiles = madinahDone,
      ),
      tajweedProgress = EditionDownloadProgress(
        totalFiles = allTajweed.size,
        completedFiles = tajweedDone,
      ),
      pendingMadinah = pendingMadinah,
      pendingTajweed = pendingTajweed,
    )
  }

  private fun launchSetup(onCompleted: suspend () -> Unit) {
    setupJob = scope.launch {
      val initialMadinahTotal = 1211
      val initialTajweedTotal = if (includeTajweedFallback) 1208 else 604
      val prevRequired = _state.value as? MushafSetupState.Required
      _state.value = MushafSetupState.Required(
        isScanningCache = true,
        isDownloading = true,
        madinah = prevRequired?.madinah ?: EditionDownloadProgress(initialMadinahTotal, 0),
        tajweed = prevRequired?.tajweed ?: EditionDownloadProgress(initialTajweedTotal, 0),
        errorMessage = null,
      )

      val scan = try {
        inspectCachedProgress()
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        _state.value = MushafSetupState.Required(
          isScanningCache = false,
          isDownloading = false,
          madinah = prevRequired?.madinah ?: EditionDownloadProgress(initialMadinahTotal, 0),
          tajweed = prevRequired?.tajweed ?: EditionDownloadProgress(initialTajweedTotal, 0),
          errorMessage = formatFriendlyError(t),
        )
        return@launch
      }

      val madinahCompletedCount = AtomicInteger(scan.madinahProgress.completedFiles)
      val tajweedCompletedCount = AtomicInteger(scan.tajweedProgress.completedFiles)
      val madinahTotal = scan.madinahProgress.totalFiles
      val tajweedTotal = scan.tajweedProgress.totalFiles

      val emitMutex = Mutex()
      var lastEmitTimeMs = 0L

      suspend fun publishProgress(force: Boolean = false) {
        emitMutex.withLock {
          val now = System.currentTimeMillis()
          if (!force && (now - lastEmitTimeMs) < PROGRESS_EMIT_INTERVAL_MS) return@withLock
          lastEmitTimeMs = now
          _state.value = MushafSetupState.Required(
            isScanningCache = false,
            isDownloading = true,
            madinah = EditionDownloadProgress(
              totalFiles = madinahTotal,
              completedFiles = madinahCompletedCount.get().coerceAtMost(madinahTotal),
            ),
            tajweed = EditionDownloadProgress(
              totalFiles = tajweedTotal,
              completedFiles = tajweedCompletedCount.get().coerceAtMost(tajweedTotal),
            ),
            errorMessage = null,
          )
        }
      }

      publishProgress(force = true)

      try {
        if (scan.pendingMadinah.isNotEmpty()) {
          downloadBatch(
            tasks = scan.pendingMadinah,
            onTaskDone = {
              madinahCompletedCount.incrementAndGet()
              publishProgress(force = false)
            },
          )
          publishProgress(force = true)
        }

        if (scan.pendingTajweed.isNotEmpty()) {
          downloadBatch(
            tasks = scan.pendingTajweed,
            onTaskDone = {
              tajweedCompletedCount.incrementAndGet()
              publishProgress(force = false)
            },
          )
          publishProgress(force = true)
        }

        onCompleted()
        _state.value = MushafSetupState.Completed
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        _state.value = MushafSetupState.Required(
          isScanningCache = false,
          isDownloading = false,
          madinah = EditionDownloadProgress(
            totalFiles = madinahTotal,
            completedFiles = madinahCompletedCount.get().coerceAtMost(madinahTotal),
          ),
          tajweed = EditionDownloadProgress(
            totalFiles = tajweedTotal,
            completedFiles = tajweedCompletedCount.get().coerceAtMost(tajweedTotal),
          ),
          errorMessage = formatFriendlyError(t),
        )
      }
    }
  }

  private suspend fun downloadBatch(
    tasks: List<SetupTask>,
    onTaskDone: suspend () -> Unit,
  ) = withContext(Dispatchers.IO) {
    // Metadata tasks run first sequentially so Juz resolution (which fans out internal calls) doesn't contend with 6 workers
    val serialTasks = tasks.filter { it is SetupTask.Chapters || it is SetupTask.Basmalah || it is SetupTask.Juzs }
    for (task in serialTasks) {
      executeWithRetry(task)
      onTaskDone()
    }

    val parallelTasks = tasks.filterNot { it is SetupTask.Chapters || it is SetupTask.Basmalah || it is SetupTask.Juzs }
    if (parallelTasks.isEmpty()) return@withContext

    val semaphore = Semaphore(DOWNLOAD_CONCURRENCY)
    coroutineScope {
      parallelTasks.map { task ->
        async(Dispatchers.IO) {
          semaphore.withPermit {
            executeWithRetry(task)
            onTaskDone()
          }
        }
      }.awaitAll()
    }
  }

  private suspend fun executeWithRetry(task: SetupTask) {
    var lastError: Throwable? = null
    for (attempt in 0..2) {
      try {
        executeSingleTask(task)
        return
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        lastError = t
        if (isNoNetworkError(t)) {
          throw t
        }
        if (attempt < 2) {
          delay(350L * (attempt + 1))
        }
      }
    }
    throw lastError ?: IOException("تعذر تحميل ${task.relativePath}")
  }

  private suspend fun executeSingleTask(task: SetupTask) {
    when (task) {
      SetupTask.Chapters -> {
        quranRepository.getSurahs().getOrThrow()
      }

      SetupTask.Basmalah -> {
        quranRepository.ensureBasmalahCached()
      }

      SetupTask.Juzs -> {
        quranRepository.getJuzs().getOrThrow()
      }

      is SetupTask.PageJson -> {
        quranRepository.ensurePageJsonCached(task.page)
      }

      is SetupTask.MadinahFont -> {
        ensureFontCached(
          relativePath = task.relativePath,
          url = fontManager.fontUrl(task.page, MushafEdition.MADINAH_1421),
        )
      }

      is SetupTask.TajweedFont -> {
        ensureFontCached(
          relativePath = task.relativePath,
          url = fontManager.fontUrl(task.page, MushafEdition.TAJWEED_V4),
        )
      }

      is SetupTask.TajweedFallbackPage -> {
        quranRepository.ensureTajweedPageFallbackCached(task.page)
      }
    }
  }

  private suspend fun ensureFontCached(
    relativePath: String,
    url: String,
  ) = withContext(Dispatchers.IO) {
    if (diskCache.getVerifiedFile(relativePath) != null) return@withContext
    val req = Request.Builder().url(url).build()
    val bytes = QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) {
        throw IOException("فشل تحميل الخط (${resp.code})")
      }
      resp.body?.bytes() ?: throw IOException("ملف الخط فارغ")
    }
    if (!isValidFontBytes(bytes)) {
      throw IOException("ملف الخط المستلم غير صالح")
    }
    diskCache.writeVerifiedBytes(relativePath, bytes)
  }

  private fun isNoNetworkError(t: Throwable): Boolean =
    t is UnknownHostException ||
      t is ConnectException ||
      t is NoRouteToHostException

  private fun formatFriendlyError(t: Throwable): String = when {
    isNoNetworkError(t) ->
      "لا يوجد اتصال بالإنترنت. يرجى تفعيل الشبكة ثم الضغط على «إعادة المحاولة» لاستكمال التحميل من آخر ملف تم حفظه."
    t is SocketTimeoutException ->
      "انتهت مهلة الاتصال بالخادم. اضغط «إعادة المحاولة» لاستكمال التحميل من آخر ملف نجح."
    else ->
      "تعذر استكمال تنزيل ملفات المصحف. اضغط «إعادة المحاولة» للاستكمال من آخر ملف تم حفظه."
  }
}
