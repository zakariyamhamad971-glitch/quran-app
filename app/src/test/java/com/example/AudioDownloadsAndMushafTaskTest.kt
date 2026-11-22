package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.audio.AudioCacheManager
import com.example.data.audio.AudioDownloadManager
import com.example.data.audio.LowStorageException
import com.example.data.audio.NetworkMonitor
import com.example.data.audio.ReciterMatchStatus
import com.example.data.audio.SurahDownloadStatus
import com.example.data.audio.VerifiedReciter
import com.example.data.integrity.VerifiedDiskCache
import com.example.data.prefs.ReaderPreferences
import com.example.data.quran.MushafEdition
import com.example.data.quran.MushafRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

private class FakeNetworkMonitor(initialOnline: Boolean) : NetworkMonitor {
  private val _isOnline = MutableStateFlow(initialOnline)
  override val isOnline: StateFlow<Boolean> = _isOnline

  fun setOnline(online: Boolean) {
    _isOnline.value = online
  }

  override fun currentOnline(): Boolean = _isOnline.value
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioDownloadsAndMushafTaskTest {

  private fun sampleReciter(
    reciterId: Int = 1,
    moshafId: Int = 10,
    availableSurahs: Set<Int> = setOf(1, 2, 3, 4),
  ) = VerifiedReciter(
    orderIndex = 1,
    requestedName = "عبد الباسط",
    reciterId = reciterId,
    displayName = "عبد الباسط عبد الصمد",
    moshafId = moshafId,
    moshafName = "حفص عن عاصم - مرتل",
    serverUrl = "https://example.invalid/audio/",
    availableSurahs = availableSurahs,
    status = ReciterMatchStatus.VERIFIED,
    statusNote = "",
    riwayaName = "حفص عن عاصم",
    riwayaId = 1,
    hasAyahTiming = false,
  )

  private fun cleanAudioRoot(cacheManager: AudioCacheManager) {
    cacheManager.getAudioRootDir().listFiles()?.forEach { it.deleteRecursively() }
  }

  private fun isolatedContext(base: Context): Context {
    val root = kotlin.io.path.createTempDirectory("qabasat_test_").toFile()
    val filesDir = java.io.File(root, "files").apply { mkdirs() }
    val cacheDir = java.io.File(root, "cache").apply { mkdirs() }
    return object : android.content.ContextWrapper(base) {
      override fun getApplicationContext(): Context = this
      override fun getFilesDir(): java.io.File = filesDir
      override fun getCacheDir(): java.io.File = cacheDir
    }
  }

  @Test
  fun `task1 and task2 - preferences preserve existing tajweed and fall back to madinah when deleted`() = runBlocking {
    val appCtx = ApplicationProvider.getApplicationContext<Context>()
    val isoCtx = isolatedContext(appCtx)
    val prefs = ReaderPreferences(appCtx)
    val diskCache = VerifiedDiskCache(isoCtx)
    prefs.resetForTest()

    try {
      // Pre-existing user who already has both Madinah and Tajweed downloaded with Tajweed active
      prefs.addDownloadedMushaf("madinah_1421")
      prefs.addDownloadedMushaf("tajweed_v4")
      prefs.setActiveTextMushaf("tajweed_v4", MushafEdition.TAJWEED_V4)

      val beforeDelete = prefs.stateFlow.first()
      assertEquals("tajweed_v4", beforeDelete.activeMushafId)
      assertEquals(MushafEdition.TAJWEED_V4, beforeDelete.mushafEdition)
      assertTrue(beforeDelete.downloadedMushafIds.contains("tajweed_v4"))

      // Write both Madinah and Tajweed cached artifacts
      diskCache.writeVerifiedBytes("fonts/qcf_v2/p1.ttf", ByteArray(400) { 1 })
      diskCache.writeVerifiedBytes("fonts/qcf_v4_raw/p1.ttf", ByteArray(400) { 2 })
      diskCache.writeVerifiedBytes("api/tajweed_page_1.json", """{"verses":[]}""".encodeToByteArray())

      // Deleting Tajweed deletes its files and automatically falls back to Madinah
      prefs.removeDownloadedMushaf("tajweed_v4")
      diskCache.deleteTajweedCacheFiles()

      val afterDelete = prefs.stateFlow.first()
      assertEquals("madinah_1421", afterDelete.activeMushafId)
      assertEquals(MushafEdition.MADINAH_1421, afterDelete.mushafEdition)
      assertFalse(afterDelete.downloadedMushafIds.contains("tajweed_v4"))
      assertTrue(afterDelete.downloadedMushafIds.contains("madinah_1421"))

      assertNotNull(diskCache.getVerifiedFile("fonts/qcf_v2/p1.ttf"))
      assertNull(diskCache.getVerifiedFile("fonts/qcf_v4_raw/p1.ttf"))
      assertNull(diskCache.getVerifiedFile("api/tajweed_page_1.json"))

      // Verify Madinah item exists in registry
      val madinahItem = MushafRegistry.getByEdition(MushafEdition.MADINAH_1421)
      assertEquals("madinah_1421", madinahItem.id)
    } finally {
      prefs.resetForTest()
    }
  }

  @Test
  fun `task3 - cache manager atomic tmp rename range resume and clean deletion`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cacheManager = AudioCacheManager(context)
    cleanAudioRoot(cacheManager)
    cacheManager.refreshDownloadedKeys(force = true)

    val fullPayload = ByteArray(4096) { idx -> (idx % 251).toByte() }
    val splitOffset = 1500
    val requestCount = AtomicInteger(0)

    val server = ServerSocket(0)
    val serverThread = thread(start = true) {
      while (!server.isClosed) {
        val socket = runCatching { server.accept() }.getOrNull() ?: break
        try {
          socket.use { sock ->
            val reader = sock.getInputStream().bufferedReader()
            val reqLine = reader.readLine() ?: return@use
            var rangeStart = -1L
            while (true) {
              val line = reader.readLine() ?: break
              if (line.isEmpty()) break
              if (line.startsWith("Range:", ignoreCase = true)) {
                rangeStart = line.substringAfter("bytes=").substringBefore("-").trim().toLongOrNull() ?: -1L
              }
            }
            val n = requestCount.incrementAndGet()
            val out = sock.getOutputStream()
            when {
              reqLine.contains("/truncated.mp3") -> {
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: ${fullPayload.size}\r\nConnection: close\r\n\r\n"
                out.write(headers.toByteArray())
                out.write(fullPayload, 0, 800)
                out.flush()
              }
              reqLine.contains("/resumed.mp3") && rangeStart == splitOffset.toLong() -> {
                val remaining = fullPayload.size - splitOffset
                val headers = "HTTP/1.1 206 Partial Content\r\nContent-Type: audio/mpeg\r\nContent-Range: bytes $splitOffset-${fullPayload.size - 1}/${fullPayload.size}\r\nContent-Length: $remaining\r\nConnection: close\r\n\r\n"
                out.write(headers.toByteArray())
                out.write(fullPayload, splitOffset, remaining)
                out.flush()
              }
              else -> {
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: ${fullPayload.size}\r\nConnection: close\r\n\r\n"
                out.write(headers.toByteArray())
                out.write(fullPayload)
                out.flush()
              }
            }
            if (n > 20) server.close()
          }
        } catch (_: Exception) {
          // Ignore test server close
        }
      }
    }

    try {
      val baseUrl = "http://127.0.0.1:${server.localPort}"

      // 1) Truncated download fails cleanly and leaves no broken .mp3 file
      val failRes = cacheManager.downloadSurah("r_trunc", 1, "$baseUrl/truncated.mp3")
      assertTrue(failRes.isFailure)
      assertNull(cacheManager.getLocalAudioFileIfValid("r_trunc", 1))
      assertFalse(cacheManager.audioFile("r_trunc", 1).exists())

      // 2) Pre-existing partial .tmp file resumes via HTTP Range and completes atomically
      val partialTmp = cacheManager.tempAudioFile("r_resume", 2)
      partialTmp.parentFile?.mkdirs()
      partialTmp.writeBytes(fullPayload.copyOfRange(0, splitOffset))

      val resumeRes = cacheManager.downloadSurah("r_resume", 2, "$baseUrl/resumed.mp3")
      assertTrue(resumeRes.isSuccess)
      val savedFile = cacheManager.getLocalAudioFileIfValid("r_resume", 2)
      assertNotNull(savedFile)
      assertFalse(partialTmp.exists())
      assertEquals(fullPayload.size, savedFile!!.readBytes().size)
      assertTrue(savedFile.readBytes().contentEquals(fullPayload))

      // 3) Summary tracks downloaded surah and byte size, and deleteAllForReciter clears cleanly
      val summary = cacheManager.reciterSummaries.value["r_resume"]
      assertNotNull(summary)
      assertEquals(setOf(2), summary!!.downloadedSurahs)
      assertEquals(fullPayload.size.toLong(), summary.totalBytes)

      cacheManager.deleteAllForReciter("r_resume")
      assertNull(cacheManager.getLocalAudioFileIfValid("r_resume", 2))
      assertNull(cacheManager.reciterSummaries.value["r_resume"])
      assertFalse(cacheManager.downloadedKeys.value.any { it.startsWith("r_resume_") })
    } finally {
      runCatching { server.close() }
      serverThread.join(1000L)
      cleanAudioRoot(cacheManager)
    }
  }

  @Test
  fun `task3 - download manager caps concurrency at 2, skips downloaded, pauses offline and auto-resumes`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cacheManager = AudioCacheManager(context)
    cleanAudioRoot(cacheManager)
    cacheManager.refreshDownloadedKeys(force = true)

    val reciter = sampleReciter(reciterId = 1, moshafId = 10, availableSurahs = setOf(1, 2, 3, 4))
    val rKey = reciter.key

    // Pre-populate surah 1 so "تحميل الكل" must skip it
    val existingFile = cacheManager.audioFile(rKey, 1)
    existingFile.parentFile?.mkdirs()
    existingFile.writeBytes(ByteArray(2048) { 7 })
    cacheManager.refreshDownloadedKeys(force = true)

    val networkMonitor = FakeNetworkMonitor(initialOnline = true)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val inFlight = AtomicInteger(0)
    val maxObservedInFlight = AtomicInteger(0)
    val startedSurahs = mutableListOf<Int>()
    val surah2Gate = CompletableDeferred<Unit>()
    val surah3Gate = CompletableDeferred<Unit>()
    val surah4Gate = CompletableDeferred<Unit>()

    val manager = AudioDownloadManager(
      appContext = context,
      cacheManager = cacheManager,
      networkMonitor = networkMonitor,
      scope = scope,
      onStartForegroundService = { /* no-op in JVM test */ },
      downloadExecutor = { item, _ ->
        synchronized(startedSurahs) { startedSurahs += item.surahId }
        val active = inFlight.incrementAndGet()
        maxObservedInFlight.updateAndGet { prev -> maxOf(prev, active) }
        try {
          when (item.surahId) {
            2 -> surah2Gate.await()
            3 -> surah3Gate.await()
            4 -> surah4Gate.await()
          }
          val out = cacheManager.audioFile(item.reciterKey, item.surahId)
          out.parentFile?.mkdirs()
          out.writeBytes(ByteArray(2048) { item.surahId.toByte() })
          cacheManager.refreshDownloadedKeys(force = true)
          Result.success(out)
        } finally {
          inFlight.decrementAndGet()
        }
      },
    )

    try {
      manager.enqueueReciterBatch(
        reciter = reciter,
        surahNamesById = mapOf(1 to "الفاتحة", 2 to "البقرة", 3 to "آل عمران", 4 to "النساء"),
        urlBuilder = { r, s -> "${r.serverUrl}${s.toString().padStart(3, '0')}.mp3" },
      )

      // Wait until 2 concurrent downloads have started
      val deadline = System.currentTimeMillis() + 3_000L
      while (synchronized(startedSurahs) { startedSurahs.size } < 2 && System.currentTimeMillis() < deadline) {
        delay(20)
      }
      delay(80)

      // Surah 1 skipped, at most 2 running concurrently (surahs 2 & 3), surah 4 waiting in queue
      assertFalse(synchronized(startedSurahs) { startedSurahs.contains(1) })
      assertEquals(2, maxObservedInFlight.get())
      assertEquals(SurahDownloadStatus.QUEUED, manager.transferStates.value["${rKey}_004"]?.status)

      // Finish surah 2 & 3 while online -> surah 4 begins, then network drops offline!
      surah2Gate.complete(Unit)
      surah3Gate.complete(Unit)

      val deadline4 = System.currentTimeMillis() + 3_000L
      while (!synchronized(startedSurahs) { startedSurahs.contains(4) } && System.currentTimeMillis() < deadline4) {
        delay(20)
      }

      networkMonitor.setOnline(false)
      val pauseDeadline = System.currentTimeMillis() + 3_000L
      while (manager.transferStates.value["${rKey}_004"]?.status != SurahDownloadStatus.PAUSED_OFFLINE &&
        System.currentTimeMillis() < pauseDeadline
      ) {
        delay(20)
      }
      assertEquals(SurahDownloadStatus.PAUSED_OFFLINE, manager.transferStates.value["${rKey}_004"]?.status)

      // Network reconnects -> surah 4 auto-resumes and finishes!
      surah4Gate.complete(Unit)
      networkMonitor.setOnline(true)

      val finishDeadline = System.currentTimeMillis() + 3_000L
      while (manager.transferStates.value.containsKey("${rKey}_004") && System.currentTimeMillis() < finishDeadline) {
        delay(20)
      }
      assertTrue(cacheManager.isSurahCachedQuick(rKey, 2))
      assertTrue(cacheManager.isSurahCachedQuick(rKey, 3))
      assertTrue(cacheManager.isSurahCachedQuick(rKey, 4))
    } finally {
      scope.cancel()
      cleanAudioRoot(cacheManager)
    }
  }

  @Test
  fun `task3 - persisted queue restores and completes after manager restart`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cacheManager = AudioCacheManager(context)
    cleanAudioRoot(cacheManager)
    cacheManager.refreshDownloadedKeys(force = true)

    val reciter = sampleReciter(reciterId = 9, moshafId = 20, availableSurahs = setOf(5))
    val offlineMonitor = FakeNetworkMonitor(initialOnline = false)
    val scope1 = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val manager1 = AudioDownloadManager(
      appContext = context,
      cacheManager = cacheManager,
      networkMonitor = offlineMonitor,
      scope = scope1,
      onStartForegroundService = {},
      downloadExecutor = { _, _ -> Result.failure(IllegalStateException("should not run while offline")) },
    )

    manager1.enqueueSingleSurah(
      reciter = reciter,
      surahId = 5,
      surahName = "المائدة",
      url = "https://example.invalid/005.mp3",
    )
    val waitPersist = System.currentTimeMillis() + 2_000L
    while (manager1.transferStates.value["${reciter.key}_005"] == null && System.currentTimeMillis() < waitPersist) {
      delay(20)
    }
    scope1.cancel()

    // Recreate manager while back online -> persisted queue must restore & finish surah 5
    val onlineMonitor = FakeNetworkMonitor(initialOnline = true)
    val scope2 = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val manager2 = AudioDownloadManager(
      appContext = context,
      cacheManager = cacheManager,
      networkMonitor = onlineMonitor,
      scope = scope2,
      onStartForegroundService = {},
      downloadExecutor = { item, _ ->
        val out = cacheManager.audioFile(item.reciterKey, item.surahId)
        out.parentFile?.mkdirs()
        out.writeBytes(ByteArray(2048) { 5 })
        cacheManager.refreshDownloadedKeys(force = true)
        Result.success(out)
      },
    )

    try {
      val waitFinish = System.currentTimeMillis() + 3_000L
      while (!cacheManager.isSurahCachedQuick(reciter.key, 5) && System.currentTimeMillis() < waitFinish) {
        delay(20)
      }
      assertTrue(cacheManager.isSurahCachedQuick(reciter.key, 5))
    } finally {
      scope2.cancel()
      cleanAudioRoot(cacheManager)
    }
  }

  @Test
  fun `task3 - low storage stops queue cleanly and sets clear error message`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val cacheManager = AudioCacheManager(context)
    cleanAudioRoot(cacheManager)
    cacheManager.refreshDownloadedKeys(force = true)

    val networkMonitor = FakeNetworkMonitor(initialOnline = true)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val manager = AudioDownloadManager(
      appContext = context,
      cacheManager = cacheManager,
      networkMonitor = networkMonitor,
      scope = scope,
      onStartForegroundService = {},
      downloadExecutor = { item, _ ->
        if (item.surahId == 1) {
          val tmp = cacheManager.tempAudioFile(item.reciterKey, 2)
          tmp.parentFile?.mkdirs()
          tmp.writeBytes(ByteArray(128))
          Result.failure(LowStorageException("مساحة التخزين منخفضة، تعذر إكمال التحميل"))
        } else {
          delay(200)
          Result.failure(LowStorageException("مساحة التخزين منخفضة، تعذر إكمال التحميل"))
        }
      },
    )

    try {
      val reciter = sampleReciter(reciterId = 2, moshafId = 30, availableSurahs = setOf(1, 2, 3))
      manager.enqueueReciterBatch(
        reciter = reciter,
        surahNamesById = mapOf(1 to "الفاتحة", 2 to "البقرة", 3 to "آل عمران"),
        urlBuilder = { r, s -> "${r.serverUrl}${s.toString().padStart(3, '0')}.mp3" },
      )

      val deadline = System.currentTimeMillis() + 3_000L
      while (manager.batchSummary.value.lowStorageMessage == null && System.currentTimeMillis() < deadline) {
        delay(20)
      }

      assertEquals("مساحة التخزين منخفضة، تعذر إكمال التحميل", manager.batchSummary.value.lowStorageMessage)
      assertFalse(manager.batchSummary.value.hasWork)
      assertFalse(cacheManager.tempAudioFile(reciter.key, 2).exists())
    } finally {
      scope.cancel()
      cleanAudioRoot(cacheManager)
    }
  }
}
