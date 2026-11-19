package com.example.update

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import java.io.FileInputStream
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
  data object PreparingInstall : AppUpdateState
  data class OpeningInstaller(val apkFile: File) : AppUpdateState
  data class NeedsInstallPermission(val autoLaunchPending: Boolean = true) : AppUpdateState
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
    private const val PREFS_NAME = "qabasat_update_prefs"
    private const val KEY_PENDING_APK_PATH = "pending_apk_path"
    private const val KEY_PENDING_BUILD = "pending_apk_build"
    private const val KEY_AWAITING_PERMISSION = "awaiting_install_permission"
    private const val MIN_APK_BYTES = 1024L

    private val BUILD_NUMBER_REGEX = Regex("""Build\s+(\d+)""", RegexOption.IGNORE_CASE)

    internal fun buildTargetApkFileName(remoteBuildNumber: Int, originalFileName: String): String {
      val clean = originalFileName.replace(Regex("""[^A-Za-z0-9._-]"""), "_").let {
        if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk"
      }
      return "build-${remoteBuildNumber}-$clean"
    }

    internal fun hasZipMagicHeader(file: File): Boolean {
      if (!file.exists() || !file.isFile || file.length() < 4L) return false
      return runCatching {
        FileInputStream(file).use { input ->
          val header = ByteArray(4)
          val read = input.read(header)
          read == 4 &&
            header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() &&
            header[3] == 0x04.toByte()
        }
      }.getOrDefault(false)
    }

    internal fun findCachedValidApk(
      targetFile: File,
      apkValidator: (File) -> Boolean,
    ): File? {
      return if (targetFile.exists() && apkValidator(targetFile)) targetFile else null
    }

    internal fun shouldResumePendingInstall(
      awaitingPermission: Boolean,
      pendingBuild: Int,
      currentBuild: Int,
      fileExists: Boolean,
    ): Boolean {
      return awaitingPermission && fileExists && pendingBuild > currentBuild
    }

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

  private val prefs by lazy {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
    if (hasPendingInstallAwaitingPermission()) return
    if (_state.value !is AppUpdateState.Idle) return

    autoCheckJob = scope.launch(Dispatchers.IO) {
      if (!hasActiveNetwork()) return@launch
      val result = fetchReleaseUpdateState(betaChannelEnabled)
      if (result is AppUpdateState.UpdateAvailable) {
        withContext(Dispatchers.Main) {
          if (_state.value is AppUpdateState.Idle && !hasPendingInstallAwaitingPermission()) {
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
    if (_state.value is AppUpdateState.Checking ||
      _state.value is AppUpdateState.Downloading ||
      _state.value is AppUpdateState.PreparingInstall ||
      _state.value is AppUpdateState.OpeningInstaller
    ) return

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
   * One-tap update flow:
   * 1. Reuses a previously downloaded & verified APK for the same build if already on disk.
   * 2. Otherwise downloads with live percentage, verifies APK integrity, then immediately opens installer
   *    (or opens Unknown Sources settings if permission is not granted yet and resumes automatically on return).
   */
  fun startDownloadAndInstall(update: AppUpdateState.UpdateAvailable) {
    if (_state.value is AppUpdateState.Downloading ||
      _state.value is AppUpdateState.PreparingInstall ||
      _state.value is AppUpdateState.OpeningInstaller
    ) return

    downloadJob?.cancel()
    val updatesDir = File(context.filesDir, "updates")
    val targetFileName = buildTargetApkFileName(update.remoteBuildNumber, update.fileName)
    val targetFile = File(updatesDir, targetFileName)
    val tempFile = File(updatesDir, "$targetFileName.part")

    _state.value = AppUpdateState.PreparingInstall

    downloadJob = scope.launch(Dispatchers.IO) {
      if (!updatesDir.exists()) updatesDir.mkdirs()

      // Fast path: if already downloaded from an earlier attempt, verify and launch installer without re-downloading
      val existingValid = findCachedValidApk(targetFile, ::isValidUpdateApk)
      if (existingValid != null) {
        withContext(Dispatchers.Main) {
          onVerifiedApkReady(existingValid, update.remoteBuildNumber)
        }
        return@launch
      }

      if (!hasActiveNetwork()) {
        _state.value = AppUpdateState.Error("لا يوجد اتصال بالإنترنت")
        return@launch
      }

      _state.value = AppUpdateState.Downloading(progressPercent = 0)

      try {
        if (targetFile.exists()) runCatching { targetFile.delete() }
        updatesDir.listFiles()?.forEach { old ->
          if (old.name != targetFile.name && old.name != tempFile.name) {
            runCatching { old.delete() }
          }
        }
        if (tempFile.exists()) runCatching { tempFile.delete() }

        val request = Request.Builder()
          .url(update.apkUrl)
          .header("Accept", "application/octet-stream")
          .header("User-Agent", "Qabasat-Android")
          .build()

        var downloaded = 0L
        var expectedTotalBytes = -1L

        QuranApiClient.okHttpClient.newCall(request).execute().use { response ->
          if (!response.isSuccessful) {
            throw IllegalStateException("HTTP ${response.code}")
          }
          val body = response.body ?: throw IllegalStateException("Empty APK body")
          expectedTotalBytes = body.contentLength()

          body.byteStream().use { input ->
            FileOutputStream(tempFile).use { output ->
              val buffer = ByteArray(16 * 1024)
              var lastEmitMs = 0L
              var lastPercent = -1

              while (true) {
                ensureActive()
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
                downloaded += read

                val now = SystemClock.elapsedRealtime()
                val pct = if (expectedTotalBytes > 0L) {
                  ((downloaded * 100L) / expectedTotalBytes).toInt().coerceIn(0, 99)
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
                    totalBytes = expectedTotalBytes,
                  )
                }
              }
              output.flush()
            }
          }
        }

        // Transition immediately out of download percentage UI before verifying & launching installer
        _state.value = AppUpdateState.PreparingInstall

        if (expectedTotalBytes > 0L && downloaded != expectedTotalBytes) {
          runCatching { tempFile.delete() }
          _state.value = AppUpdateState.Error("اكتمل تنزيل التحديث بشكل غير مكتمل، يرجى المحاولة مرة أخرى")
          return@launch
        }

        if (targetFile.exists()) runCatching { targetFile.delete() }
        if (!tempFile.renameTo(targetFile) || !isValidUpdateApk(targetFile)) {
          runCatching { tempFile.delete() }
          runCatching { targetFile.delete() }
          _state.value = AppUpdateState.Error("ملف التحديث تالف أو غير صالح للتثبيت")
          return@launch
        }

        withContext(Dispatchers.Main) {
          onVerifiedApkReady(targetFile, update.remoteBuildNumber)
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

  private fun isValidUpdateApk(apkFile: File): Boolean {
    if (!apkFile.exists() || !apkFile.isFile || apkFile.length() < MIN_APK_BYTES) return false
    if (!hasZipMagicHeader(apkFile)) return false

    return try {
      val pm = context.packageManager
      val pkgInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.PackageInfoFlags.of(0L))
      } else {
        @Suppress("DEPRECATION")
        pm.getPackageArchiveInfo(apkFile.absolutePath, 0)
      }
      pkgInfo != null && pkgInfo.packageName == context.packageName
    } catch (_: Exception) {
      false
    }
  }

  private fun onVerifiedApkReady(apkFile: File, remoteBuildNumber: Int) {
    pendingInstallFile = apkFile

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
      persistPendingInstall(apkFile, remoteBuildNumber, awaitingPermission = true)
      _state.value = AppUpdateState.NeedsInstallPermission(autoLaunchPending = true)
      return
    }

    persistPendingInstall(apkFile, remoteBuildNumber, awaitingPermission = false)
    _state.value = AppUpdateState.OpeningInstaller(apkFile)
  }

  fun cancelDownload() {
    downloadJob?.cancel()
    downloadJob = null
    if (_state.value is AppUpdateState.Downloading || _state.value is AppUpdateState.PreparingInstall) {
      _state.value = AppUpdateState.Idle
    }
  }

  fun openUnknownSourcesSettings(activity: Activity? = null, markLaunchedFromAuto: Boolean = false) {
    if (markLaunchedFromAuto && _state.value is AppUpdateState.NeedsInstallPermission) {
      _state.value = AppUpdateState.NeedsInstallPermission(autoLaunchPending = false)
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val intent = Intent(
          Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
          Uri.parse("package:${context.packageName}"),
        ).apply {
          if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        (activity ?: context).startActivity(intent)
      }
    } catch (_: Exception) {
      clearPendingInstall()
      _state.value = AppUpdateState.Error("تعذر فتح إعدادات التثبيت")
    }
  }

  /**
   * Called on Activity ON_RESUME (including after process restart caused by granting Unknown Sources permission).
   * Automatically resumes installation without requiring a second tap on Update.
   */
  fun resumePendingInstallIfNeeded() {
    val awaitingPermission = prefs.getBoolean(KEY_AWAITING_PERMISSION, false)
    val savedPath = prefs.getString(KEY_PENDING_APK_PATH, null)
    val savedBuild = prefs.getInt(KEY_PENDING_BUILD, 0)
    val savedFile = savedPath?.let { File(it) } ?: pendingInstallFile

    if (savedFile == null ||
      !shouldResumePendingInstall(
        awaitingPermission = awaitingPermission,
        pendingBuild = savedBuild,
        currentBuild = BuildConfig.BUILD_NUMBER,
        fileExists = savedFile.exists(),
      )
    ) {
      if (awaitingPermission) {
        clearPendingInstall()
      }
      return
    }

    val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
      context.packageManager.canRequestPackageInstalls()
    if (!permitted) {
      if (_state.value is AppUpdateState.Idle) {
        pendingInstallFile = savedFile
        _state.value = AppUpdateState.NeedsInstallPermission(autoLaunchPending = false)
      }
      return
    }

    if (!isValidUpdateApk(savedFile)) {
      runCatching { savedFile.delete() }
      clearPendingInstall()
      _state.value = AppUpdateState.Error("ملف التحديث تالف أو غير صالح للتثبيت")
      return
    }

    pendingInstallFile = savedFile
    persistPendingInstall(savedFile, savedBuild, awaitingPermission = false)
    _state.value = AppUpdateState.OpeningInstaller(savedFile)
  }

  /**
   * Launches Android's system package installer directly from the foreground Activity (when available)
   * using FileProvider so the confirmation sheet appears reliably on the first try.
   */
  fun launchApkInstallerFromUi(activity: Activity?, apkFile: File) {
    val hostContext: Context = activity ?: context
    try {
      if (!apkFile.exists() || !isValidUpdateApk(apkFile)) {
        clearPendingInstall()
        _state.value = AppUpdateState.Error("ملف التحديث غير صالح أو غير موجود")
        return
      }

      val contentUri = FileProvider.getUriForFile(
        hostContext,
        "${hostContext.packageName}.fileprovider",
        apkFile,
      )

      val viewIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(contentUri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        if (activity == null) {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      }

      val installIntent = if (viewIntent.resolveActivity(hostContext.packageManager) != null) {
        viewIntent
      } else {
        @Suppress("DEPRECATION")
        Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
          data = contentUri
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
          if (activity == null) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
        }
      }

      hostContext.startActivity(installIntent)
      clearPendingInstall()
      _state.value = AppUpdateState.Idle
    } catch (_: Exception) {
      clearPendingInstall()
      _state.value = AppUpdateState.Error("تعذر تشغيل مثبت التحديث")
    }
  }

  fun dismiss() {
    if (_state.value is AppUpdateState.Downloading || _state.value is AppUpdateState.PreparingInstall) {
      cancelDownload()
      return
    }
    clearPendingInstall()
    _state.value = AppUpdateState.Idle
  }

  private fun hasPendingInstallAwaitingPermission(): Boolean =
    prefs.getBoolean(KEY_AWAITING_PERMISSION, false)

  private fun persistPendingInstall(apkFile: File, remoteBuildNumber: Int, awaitingPermission: Boolean) {
    prefs.edit()
      .putString(KEY_PENDING_APK_PATH, apkFile.absolutePath)
      .putInt(KEY_PENDING_BUILD, remoteBuildNumber)
      .putBoolean(KEY_AWAITING_PERMISSION, awaitingPermission)
      .apply()
  }

  private fun clearPendingInstall() {
    pendingInstallFile = null
    prefs.edit()
      .remove(KEY_PENDING_APK_PATH)
      .remove(KEY_PENDING_BUILD)
      .putBoolean(KEY_AWAITING_PERMISSION, false)
      .apply()
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
