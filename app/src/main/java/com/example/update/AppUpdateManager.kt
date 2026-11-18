package com.example.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.quran.QuranApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface AppUpdateState {
  data object Idle : AppUpdateState
  data object Checking : AppUpdateState
  data class UpdateAvailable(
    val remoteBuildNumber: Int,
    val apkUrl: String,
    val fileName: String,
  ) : AppUpdateState
  data object UpToDate : AppUpdateState
  data class Downloading(
    val progressPercent: Int?,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
  ) : AppUpdateState
  data object NeedsInstallPermission : AppUpdateState
  data class Error(val message: String) : AppUpdateState
}

class AppUpdateManager(
  private val context: Context,
  private val scope: CoroutineScope,
) {
  internal data class ReleaseCandidate(
    val remoteBuildNumber: Int,
    val apkUrl: String?,
    val fileName: String,
  )

  internal sealed interface ReleaseFetchResult {
    data class Found(val candidate: ReleaseCandidate) : ReleaseFetchResult
    data object NotFound : ReleaseFetchResult
    data class Failed(val message: String) : ReleaseFetchResult
  }

  companion object {
    private const val LATEST_RELEASE_API_URL =
      "https://api.github.com/repos/zakariyamhamad971-glitch/quran-app/releases/tags/latest"
    private const val BETA_RELEASE_API_URL =
      "https://api.github.com/repos/zakariyamhamad971-glitch/quran-app/releases/tags/beta"
    private val BUILD_NUMBER_REGEX = Regex("""Build\s+(\d+)""", RegexOption.IGNORE_CASE)

    internal fun parseReleaseCandidateJson(rawJson: String): ReleaseCandidate? {
      val json = JSONObject(rawJson)
      val bodyText = json.optString("body", "")
      val match = BUILD_NUMBER_REGEX.find(bodyText) ?: return null
      val remoteBuild = match.groupValues.getOrNull(1)?.toIntOrNull() ?: return null

      val assetsArray = json.optJSONArray("assets")
      var apkUrl: String? = null
      var apkName = "qabasat-update.apk"
      if (assetsArray != null) {
        for (i in 0 until assetsArray.length()) {
          val asset = assetsArray.optJSONObject(i) ?: continue
          val name = asset.optString("name", "")
          val url = asset.optString("browser_download_url", "")
          val contentType = asset.optString("content_type", "")
          val isApk = name.endsWith(".apk", ignoreCase = true) ||
            url.endsWith(".apk", ignoreCase = true) ||
            contentType.equals("application/vnd.android.package-archive", ignoreCase = true)
          if (isApk && url.isNotBlank()) {
            apkUrl = url
            if (name.isNotBlank()) apkName = name
            break
          }
        }
      }

      return ReleaseCandidate(
        remoteBuildNumber = remoteBuild,
        apkUrl = apkUrl,
        fileName = apkName,
      )
    }

    internal fun evaluateCandidateAgainstBuild(
      candidate: ReleaseCandidate,
      currentBuildNumber: Int,
    ): AppUpdateState {
      if (candidate.remoteBuildNumber <= currentBuildNumber) {
        return AppUpdateState.UpToDate
      }
      val url = candidate.apkUrl
      return if (url.isNullOrBlank()) {
        AppUpdateState.Error("ملف التحديث غير متاح حاليًا")
      } else {
        AppUpdateState.UpdateAvailable(
          remoteBuildNumber = candidate.remoteBuildNumber,
          apkUrl = url,
          fileName = candidate.fileName,
        )
      }
    }

    internal fun pickHigherBuildCandidate(
      latest: ReleaseCandidate?,
      beta: ReleaseCandidate?,
    ): ReleaseCandidate? {
      return when {
        latest != null && beta != null -> if (beta.remoteBuildNumber > latest.remoteBuildNumber) beta else latest
        latest != null -> latest
        else -> beta
      }
    }
  }

  private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
  val state: StateFlow<AppUpdateState> = _state.asStateFlow()

  private var autoCheckJob: Job? = null
  private var checkJob: Job? = null
  private var downloadJob: Job? = null
  private var hasAutoChecked = false
  private var pendingInstallFile: File? = null

  /**
   * Silent startup update check on a background thread.
   * Never blocks app startup and silently ignores offline / failed checks.
   */
  fun checkForUpdatesInBackground(betaChannelEnabled: Boolean = false) {
    if (hasAutoChecked) return
    hasAutoChecked = true
    if (_state.value !is AppUpdateState.Idle) return

    autoCheckJob = scope.launch(Dispatchers.IO) {
      if (!hasActiveNetwork()) return@launch
      val result = fetchReleaseUpdateState(betaChannelEnabled)
      if (result is AppUpdateState.UpdateAvailable) {
        withContext(Dispatchers.Main) {
          if (_state.value is AppUpdateState.Idle) {
            _state.value = result
          }
        }
      }
    }
  }

  /**
   * User-triggered check from Settings. Shows progress, up-to-date notice, or Arabic error messages.
   */
  fun checkForUpdates(betaChannelEnabled: Boolean = false) {
    if (_state.value is AppUpdateState.Checking || _state.value is AppUpdateState.Downloading) return
    autoCheckJob?.cancel()
    checkJob?.cancel()
    checkJob = scope.launch {
      _state.value = AppUpdateState.Checking
      if (!hasActiveNetwork()) {
        _state.value = AppUpdateState.Error("لا يوجد اتصال بالإنترنت")
        return@launch
      }

      val result = withContext(Dispatchers.IO) {
        fetchReleaseUpdateState(betaChannelEnabled)
      }
      _state.value = result
    }
  }

  private fun fetchReleaseUpdateState(betaChannelEnabled: Boolean): AppUpdateState {
    if (!betaChannelEnabled) {
      return when (val latestRes = fetchSingleRelease(LATEST_RELEASE_API_URL)) {
        is ReleaseFetchResult.Found -> evaluateCandidateAgainstBuild(latestRes.candidate, BuildConfig.BUILD_NUMBER)
        ReleaseFetchResult.NotFound -> AppUpdateState.Error("تعذر التحقق من التحديث (404)")
        is ReleaseFetchResult.Failed -> AppUpdateState.Error(latestRes.message)
      }
    }

    val latestRes = fetchSingleRelease(LATEST_RELEASE_API_URL)
    val betaRes = fetchSingleRelease(BETA_RELEASE_API_URL)

    val latestCandidate = (latestRes as? ReleaseFetchResult.Found)?.candidate
    val betaCandidate = (betaRes as? ReleaseFetchResult.Found)?.candidate
    val winner = pickHigherBuildCandidate(latest = latestCandidate, beta = betaCandidate)

    if (winner != null) {
      return evaluateCandidateAgainstBuild(winner, BuildConfig.BUILD_NUMBER)
    }

    val firstError = (betaRes as? ReleaseFetchResult.Failed)?.message
      ?: (latestRes as? ReleaseFetchResult.Failed)?.message
    if (firstError != null) {
      return AppUpdateState.Error(firstError)
    }
    return AppUpdateState.Error("تعذر التحقق من التحديث")
  }

  private fun fetchSingleRelease(url: String): ReleaseFetchResult {
    return try {
      val request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "Qabasat-Android")
        .build()

      QuranApiClient.okHttpClient.newCall(request).execute().use { response ->
        if (response.code == 404) {
          return ReleaseFetchResult.NotFound
        }
        if (!response.isSuccessful) {
          return ReleaseFetchResult.Failed("تعذر التحقق من التحديث (${response.code})")
        }
        val rawJson = response.body?.string()
          ?: return ReleaseFetchResult.Failed("تعذر قراءة بيانات التحديث")

        val candidate = parseReleaseCandidateJson(rawJson)
          ?: return ReleaseFetchResult.Failed("تعذر معرفة رقم الإصدار الجديد")
        ReleaseFetchResult.Found(candidate)
      }
    } catch (_: UnknownHostException) {
      ReleaseFetchResult.Failed("لا يوجد اتصال بالإنترنت")
    } catch (_: ConnectException) {
      ReleaseFetchResult.Failed("لا يوجد اتصال بالإنترنت")
    } catch (_: SocketTimeoutException) {
      ReleaseFetchResult.Failed("انتهت مهلة الاتصال بالخادم")
    } catch (_: Exception) {
      ReleaseFetchResult.Failed("تعذر التحقق من التحديث")
    }
  }

  /**
   * Downloads the APK in-app over OkHttp with progress updates, then launches Android installer via FileProvider.
   */
  fun startDownloadAndInstall(update: AppUpdateState.UpdateAvailable) {
    if (_state.value is AppUpdateState.Downloading) return
    if (!hasActiveNetwork()) {
      _state.value = AppUpdateState.Error("لا يوجد اتصال بالإنترنت")
      return
    }

    downloadJob?.cancel()
    val safeName = update.fileName.replace(Regex("""[^A-Za-z0-9._-]"""), "_").let {
      if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk"
    }
    val updatesDir = File(context.filesDir, "updates")
    if (!updatesDir.exists()) updatesDir.mkdirs()
    val targetFile = File(updatesDir, safeName)
    val tempFile = File(updatesDir, "$safeName.part")

    _state.value = AppUpdateState.Downloading(progressPercent = 0)

    downloadJob = scope.launch(Dispatchers.IO) {
      try {
        // Clean stale update APKs to avoid wasted internal storage
        updatesDir.listFiles()?.forEach { old ->
          if (old.name != safeName && old.name != tempFile.name) {
            runCatching { old.delete() }
          }
        }
        if (tempFile.exists()) tempFile.delete()

        val request = Request.Builder()
          .url(update.apkUrl)
          .header("Accept", "application/octet-stream")
          .header("User-Agent", "Qabasat-Android")
          .build()

        QuranApiClient.okHttpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}")
          }
          val body = response.body ?: throw IllegalStateException("Empty APK body")
          val totalBytes = body.contentLength()

          body.byteStream().use { input ->
            FileOutputStream(tempFile).use { output ->
              val buffer = ByteArray(16 * 1024)
              var downloaded = 0L
              var lastEmitMs = 0L
              var lastPercent = -1

              while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                downloaded += read

                val now = SystemClock.elapsedRealtime()
                val pct = if (totalBytes > 0L) {
                  ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 100)
                } else {
                  null
                }
                if ((pct != null && pct != lastPercent && now - lastEmitMs >= 80L) ||
                  now - lastEmitMs >= 200L
                ) {
                  lastEmitMs = now
                  if (pct != null) lastPercent = pct
                  _state.value = AppUpdateState.Downloading(
                    progressPercent = pct,
                    downloadedBytes = downloaded,
                    totalBytes = totalBytes,
                  )
                }
              }
              output.flush()
            }
          }
        }

        if (targetFile.exists()) targetFile.delete()
        if (!tempFile.renameTo(targetFile) || !targetFile.exists() || targetFile.length() == 0L) {
          throw IllegalStateException("Failed to finalize APK file")
        }

        withContext(Dispatchers.Main) {
          onApkDownloaded(targetFile)
        }
      } catch (ce: CancellationException) {
        runCatching { tempFile.delete() }
        throw ce
      } catch (_: UnknownHostException) {
        runCatching { tempFile.delete() }
        _state.value = AppUpdateState.Error("لا يوجد اتصال بالإنترنت")
      } catch (_: ConnectException) {
        runCatching { tempFile.delete() }
        _state.value = AppUpdateState.Error("لا يوجد اتصال بالإنترنت")
      } catch (_: SocketTimeoutException) {
        runCatching { tempFile.delete() }
        _state.value = AppUpdateState.Error("انتهت مهلة تحميل التحديث")
      } catch (_: Exception) {
        runCatching { tempFile.delete() }
        _state.value = AppUpdateState.Error("فشل تحميل التحديث")
      }
    }
  }

  fun cancelDownload() {
    downloadJob?.cancel()
    downloadJob = null
    if (_state.value is AppUpdateState.Downloading) {
      _state.value = AppUpdateState.Idle
    }
  }

  private fun onApkDownloaded(apkFile: File) {
    pendingInstallFile = apkFile

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
      _state.value = AppUpdateState.NeedsInstallPermission
      return
    }

    launchApkInstaller(apkFile)
  }

  fun openUnknownSourcesSettings() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val intent = Intent(
          Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
          Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
      }
    } catch (_: Exception) {
      _state.value = AppUpdateState.Error("تعذر فتح إعدادات التثبيت")
    }
  }

  fun retryPendingInstallIfPermitted() {
    val file = pendingInstallFile ?: return
    if (_state.value !is AppUpdateState.NeedsInstallPermission) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()) {
      launchApkInstaller(file)
    }
  }

  private fun launchApkInstaller(apkFile: File) {
    try {
      val contentUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile,
      )
      val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      context.startActivity(installIntent)
      pendingInstallFile = null
      _state.value = AppUpdateState.Idle
    } catch (_: Exception) {
      pendingInstallFile = null
      _state.value = AppUpdateState.Error("تعذر تشغيل مثبت التحديث")
    }
  }

  fun dismiss() {
    if (_state.value is AppUpdateState.Downloading) {
      cancelDownload()
      return
    }
    pendingInstallFile = null
    _state.value = AppUpdateState.Idle
  }

  private fun hasActiveNetwork(): Boolean {
    return try {
      val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
      val network = cm.activeNetwork ?: return false
      val caps = cm.getNetworkCapabilities(network) ?: return false
      caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } catch (_: Exception) {
      false
    }
  }

  fun release() {
    autoCheckJob?.cancel()
    checkJob?.cancel()
    downloadJob?.cancel()
  }
}
