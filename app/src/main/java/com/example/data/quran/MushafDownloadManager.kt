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
import kotlinx.coroutines.flow.update
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
data class MushafDownloadState(
  val mushafId: String,
  val isScanningCache: Boolean = false,
  val isDownloading: Boolean = false,
  val progress: EditionDownloadProgress = EditionDownloadProgress(0, 0),
  val errorMessage: String? = null,
  val isCompleted: Boolean = false,
)

class MushafDownloadManager(
  private val diskCache: VerifiedDiskCache,
  private val quranRepository: QuranRepository,
  private val fontManager: PageFontManager,
  private val scope: CoroutineScope,
) {
  private val activeJobs = mutableMapOf<String, Job>()
  private val _states = MutableStateFlow<Map<String, MushafDownloadState>>(emptyMap())
  val states: StateFlow<Map<String, MushafDownloadState>> = _states.asStateFlow()

  fun getState(mushafId: String): MushafDownloadState =
    _states.value[mushafId] ?: MushafDownloadState(mushafId = mushafId)

  fun startDownload(
    item: MushafItem,
    onSuccess: suspend () -> Unit,
  ) {
    val id = item.id
    if (activeJobs[id]?.isActive == true) return

    activeJobs[id]?.cancel()
    activeJobs[id] = scope.launch {
      val tasks = buildTasksForEdition(item.edition)
      val total = tasks.size

      updateState(id) {
        it.copy(
          isScanningCache = true,
          isDownloading = false,
          progress = EditionDownloadProgress(total, 0),
          errorMessage = null,
          isCompleted = false,
        )
      }

      val pendingTasks = try {
        withContext(Dispatchers.IO) {
          val pending = ArrayList<SetupTask>(total)
          var completedCount = 0
          for (task in tasks) {
            if (diskCache.getVerifiedFile(task.relativePath) != null) {
              completedCount++
            } else {
              pending += task
            }
          }
          completedCount to pending
        }
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        updateState(id) {
          it.copy(
            isScanningCache = false,
            isDownloading = false,
            errorMessage = formatFriendlyError(t),
          )
        }
        return@launch
      }

      val initialCompleted = pendingTasks.first
      val pending = pendingTasks.second

      if (pending.isEmpty()) {
        updateState(id) {
          it.copy(
            isScanningCache = false,
            isDownloading = false,
            progress = EditionDownloadProgress(total, total),
            isCompleted = true,
            errorMessage = null,
          )
        }
        onSuccess()
        return@launch
      }

      val completedCounter = AtomicInteger(initialCompleted)
      updateState(id) {
        it.copy(
          isScanningCache = false,
          isDownloading = true,
          progress = EditionDownloadProgress(total, initialCompleted),
          errorMessage = null,
        )
      }

      val emitMutex = Mutex()
      var lastEmitTimeMs = 0L

      suspend fun publishProgress(force: Boolean = false) {
        emitMutex.withLock {
          val now = System.currentTimeMillis()
          if (!force && (now - lastEmitTimeMs) < 80L) return@withLock
          lastEmitTimeMs = now
          updateState(id) {
            it.copy(
              isDownloading = true,
              progress = EditionDownloadProgress(
                totalFiles = total,
                completedFiles = completedCounter.get().coerceAtMost(total),
              ),
            )
          }
        }
      }

      try {
        downloadBatch(
          edition = item.edition,
          tasks = pending,
          onTaskDone = {
            completedCounter.incrementAndGet()
            publishProgress(force = false)
          },
        )
        publishProgress(force = true)
        updateState(id) {
          it.copy(
            isDownloading = false,
            progress = EditionDownloadProgress(total, total),
            isCompleted = true,
            errorMessage = null,
          )
        }
        onSuccess()
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        updateState(id) {
          it.copy(
            isDownloading = false,
            progress = EditionDownloadProgress(
              totalFiles = total,
              completedFiles = completedCounter.get().coerceAtMost(total),
            ),
            errorMessage = formatFriendlyError(t),
          )
        }
      }
    }
  }

  fun cancelDownload(mushafId: String) {
    activeJobs[mushafId]?.cancel()
    activeJobs.remove(mushafId)
    _states.update { current ->
      val st = current[mushafId] ?: return@update current
      current + (mushafId to st.copy(isDownloading = false, isScanningCache = false))
    }
  }

  fun resetState(mushafId: String) {
    activeJobs[mushafId]?.cancel()
    activeJobs.remove(mushafId)
    _states.update { current -> current - mushafId }
  }

  private fun updateState(id: String, transform: (MushafDownloadState) -> MushafDownloadState) {
    _states.update { current ->
      val old = current[id] ?: MushafDownloadState(mushafId = id)
      current + (id to transform(old))
    }
  }

  private fun buildTasksForEdition(edition: MushafEdition): List<SetupTask> {
    val includeFallback = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
    return buildList {
      add(SetupTask.Chapters)
      add(SetupTask.Basmalah)
      add(SetupTask.Juzs)
      for (page in 1..604) {
        add(SetupTask.PageJson(page))
        when (edition) {
          MushafEdition.MADINAH_1421 -> add(SetupTask.MadinahFont(page))
          MushafEdition.TAJWEED_V4 -> {
            add(SetupTask.TajweedFont(page))
            if (includeFallback) {
              add(SetupTask.TajweedFallbackPage(page))
            }
          }
        }
      }
    }
  }

  private suspend fun downloadBatch(
    edition: MushafEdition,
    tasks: List<SetupTask>,
    onTaskDone: suspend () -> Unit,
  ) = withContext(Dispatchers.IO) {
    val serialTasks = tasks.filter { it is SetupTask.Chapters || it is SetupTask.Basmalah || it is SetupTask.Juzs }
    for (task in serialTasks) {
      executeWithRetry(edition, task)
      onTaskDone()
    }

    val parallelTasks = tasks.filterNot { it is SetupTask.Chapters || it is SetupTask.Basmalah || it is SetupTask.Juzs }
    if (parallelTasks.isEmpty()) return@withContext

    val semaphore = Semaphore(6)
    coroutineScope {
      parallelTasks.map { task ->
        async(Dispatchers.IO) {
          semaphore.withPermit {
            executeWithRetry(edition, task)
            onTaskDone()
          }
        }
      }.awaitAll()
    }
  }

  private suspend fun executeWithRetry(edition: MushafEdition, task: SetupTask) {
    var lastError: Throwable? = null
    for (attempt in 0..2) {
      try {
        executeSingleTask(edition, task)
        return
      } catch (cancel: CancellationException) {
        throw cancel
      } catch (t: Throwable) {
        lastError = t
        if (isNoNetworkError(t)) throw t
        if (attempt < 2) delay(350L * (attempt + 1))
      }
    }
    throw lastError ?: IOException("تعذر تحميل ${task.relativePath}")
  }

  private suspend fun executeSingleTask(edition: MushafEdition, task: SetupTask) {
    when (task) {
      SetupTask.Chapters -> quranRepository.getSurahs().getOrThrow()
      SetupTask.Basmalah -> quranRepository.ensureBasmalahCached()
      SetupTask.Juzs -> quranRepository.getJuzs().getOrThrow()
      is SetupTask.PageJson -> quranRepository.ensurePageJsonCached(task.page)
      is SetupTask.MadinahFont -> ensureFontCached(task.relativePath, fontManager.fontUrl(task.page, MushafEdition.MADINAH_1421))
      is SetupTask.TajweedFont -> ensureFontCached(task.relativePath, fontManager.fontUrl(task.page, MushafEdition.TAJWEED_V4))
      is SetupTask.TajweedFallbackPage -> quranRepository.ensureTajweedPageFallbackCached(task.page)
    }
  }

  private suspend fun ensureFontCached(relativePath: String, url: String) = withContext(Dispatchers.IO) {
    if (diskCache.getVerifiedFile(relativePath) != null) return@withContext
    val req = Request.Builder().url(url).build()
    val bytes = QuranApiClient.okHttpClient.newCall(req).execute().use { resp ->
      if (!resp.isSuccessful) throw IOException("فشل تحميل الخط (${resp.code})")
      resp.body?.bytes() ?: throw IOException("ملف الخط فارغ")
    }
    if (!MushafOfflineSetupManager.isValidFontBytes(bytes)) {
      throw IOException("ملف الخط غير صالح")
    }
    diskCache.writeVerifiedBytes(relativePath, bytes)
  }

  private fun isNoNetworkError(t: Throwable): Boolean =
    t is UnknownHostException || t is ConnectException || t is NoRouteToHostException

  private fun formatFriendlyError(t: Throwable): String = when {
    isNoNetworkError(t) -> "لا يوجد اتصال بالإنترنت. يرجى تفعيل الشبكة والضغط على إعادة المحاولة للاستكمال من آخر ملف نجح."
    t is SocketTimeoutException -> "انتهت مهلة الاتصال بالخادم. اضغط إعادة المحاولة للاستكمال."
    else -> "تعذر التنزيل. اضغط إعادة المحاولة للاستكمال من حيث انتهيت."
  }
}
