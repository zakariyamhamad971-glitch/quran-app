package com.example.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.quran.QuranApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
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
  data class Downloading(val progressPercent: Int?) : AppUpdateState
  data object NeedsInstallPermission : AppUpdateState
  data class Error(val message: String) : AppUpdateState
}

class AppUpdateManager(
  private val context: Context,
  private val scope: CoroutineScope,
) {
  companion object {
    private const val RELEASE_API_URL =
      "https://api.github.com/repos/zakariyamhamad971-glitch/quran-app/releases/tags/latest"
    private val BUILD_NUMBER_REGEX = Regex("""Build\s+(\d+)""", RegexOption.IGNORE_CASE)
  }

  private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
  val state: StateFlow<AppUpdateState> = _state.asStateFlow()

  private var checkJob: Job? = null
  private var downloadMonitorJob: Job? = null
  private var downloadReceiver: BroadcastReceiver? = null
  private var activeDownloadId: Long = -1L
  private var expectedApkFile: File? = null
  private var pendingInstallFile: File? = null

  fun checkForUpdates() {
    if (_state.value is AppUpdateState.Checking || _state.value is AppUpdateState.Downloading) return
    checkJob?.cancel()
    checkJob = scope.launch {
      _state.value = AppUpdateState.Checking
      if (!hasActiveNetwork()) {
        _state.value = AppUpdateState.Error("لا يوجد اتصال بالإنترنت")
        return@launch
      }

      val result = withContext(Dispatchers.IO) {
        fetchLatestReleaseInfo()
      }
      _state.value = result
    }
  }

  private fun fetchLatestReleaseInfo(): AppUpdateState {
    return try {
      val request = Request.Builder()
        .url(RELEASE_API_URL)
        .header("Accept", "application/vnd.github+json")
        .header("User-Agent", "Qabasat-Android")
        .build()

      QuranApiClient.okHttpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          return AppUpdateState.Error("تعذر التحقق من التحديث (${response.code})")
        }
        val rawJson = response.body?.string()
          ?: return AppUpdateState.Error("تعذر قراءة بيانات التحديث")

        val json = JSONObject(rawJson)
        val bodyText = json.optString("body", "")
        val match = BUILD_NUMBER_REGEX.find(bodyText)
        val remoteBuild = match?.groupValues?.getOrNull(1)?.toIntOrNull()
          ?: return AppUpdateState.Error("تعذر معرفة رقم الإصدار الجديد")

        if (remoteBuild <= BuildConfig.BUILD_NUMBER) {
          return AppUpdateState.UpToDate
        }

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

        if (apkUrl.isNullOrBlank()) {
          AppUpdateState.Error("ملف التحديث غير متاح حاليًا")
        } else {
          AppUpdateState.UpdateAvailable(
            remoteBuildNumber = remoteBuild,
            apkUrl = apkUrl,
            fileName = apkName,
          )
        }
      }
    } catch (_: UnknownHostException) {
      AppUpdateState.Error("لا يوجد اتصال بالإنترنت")
    } catch (_: ConnectException) {
      AppUpdateState.Error("لا يوجد اتصال بالإنترنت")
    } catch (_: SocketTimeoutException) {
      AppUpdateState.Error("انتهت مهلة الاتصال بالخادم")
    } catch (_: Exception) {
      AppUpdateState.Error("تعذر التحقق من التحديث")
    }
  }

  fun startDownloadAndInstall(update: AppUpdateState.UpdateAvailable) {
    if (!hasActiveNetwork()) {
      _state.value = AppUpdateState.Error("لا يوجد اتصال بالإنترنت")
      return
    }

    val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
    if (dm == null) {
      _state.value = AppUpdateState.Error("فشل تحميل التحديث")
      return
    }

    cleanupDownloadMonitor()
    val safeName = update.fileName.replace(Regex("""[^A-Za-z0-9._-]"""), "_").let {
      if (it.endsWith(".apk", ignoreCase = true)) it else "$it.apk"
    }
    val targetDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    if (targetDir == null) {
      _state.value = AppUpdateState.Error("فشل تحميل التحديث")
      return
    }
    if (!targetDir.exists()) targetDir.mkdirs()
    val targetFile = File(targetDir, safeName)
    if (targetFile.exists()) {
      targetFile.delete()
    }
    expectedApkFile = targetFile

    val downloadId = try {
      val req = DownloadManager.Request(Uri.parse(update.apkUrl))
        .setTitle("تحديث قبسات")
        .setDescription("جاري تحميل التحديث...")
        .setMimeType("application/vnd.android.package-archive")
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setAllowedOverMetered(true)
        .setAllowedOverRoaming(true)
        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, safeName)
      dm.enqueue(req)
    } catch (_: Exception) {
      -1L
    }

    if (downloadId <= 0L) {
      _state.value = AppUpdateState.Error("فشل تحميل التحديث")
      return
    }

    activeDownloadId = downloadId
    _state.value = AppUpdateState.Downloading(progressPercent = 0)
    registerCompletionReceiver(dm, downloadId)
    startDownloadPolling(dm, downloadId)
  }

  private fun registerCompletionReceiver(dm: DownloadManager, downloadId: Long) {
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(ctx: Context?, intent: Intent?) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (completedId == downloadId) {
          handleDownloadFinishedCheck(dm, downloadId)
        }
      }
    }
    downloadReceiver = receiver
    runCatching {
      ContextCompat.registerReceiver(
        context,
        receiver,
        IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
        ContextCompat.RECEIVER_EXPORTED,
      )
    }
  }

  private fun startDownloadPolling(dm: DownloadManager, downloadId: Long) {
    downloadMonitorJob?.cancel()
    downloadMonitorJob = scope.launch(Dispatchers.IO) {
      while (isActive && activeDownloadId == downloadId) {
        val statusResult = queryDownloadStatus(dm, downloadId)
        when (statusResult) {
          is DownloadPollStatus.InProgress -> {
            withContext(Dispatchers.Main) {
              if (_state.value is AppUpdateState.Downloading) {
                _state.value = AppUpdateState.Downloading(statusResult.percent)
              }
            }
          }
          is DownloadPollStatus.Completed -> {
            withContext(Dispatchers.Main) {
              onApkDownloaded(statusResult.file)
            }
            break
          }
          is DownloadPollStatus.Failed -> {
            withContext(Dispatchers.Main) {
              cleanupDownloadMonitor()
              _state.value = AppUpdateState.Error("فشل تحميل التحديث")
            }
            break
          }
          null -> Unit
        }
        delay(600L)
      }
    }
  }

  private fun handleDownloadFinishedCheck(dm: DownloadManager, downloadId: Long) {
    scope.launch(Dispatchers.IO) {
      val statusResult = queryDownloadStatus(dm, downloadId)
      withContext(Dispatchers.Main) {
        when (statusResult) {
          is DownloadPollStatus.Completed -> onApkDownloaded(statusResult.file)
          is DownloadPollStatus.Failed -> {
            cleanupDownloadMonitor()
            _state.value = AppUpdateState.Error("فشل تحميل التحديث")
          }
          else -> Unit
        }
      }
    }
  }

  private sealed interface DownloadPollStatus {
    data class InProgress(val percent: Int?) : DownloadPollStatus
    data class Completed(val file: File) : DownloadPollStatus
    data object Failed : DownloadPollStatus
  }

  private fun queryDownloadStatus(dm: DownloadManager, downloadId: Long): DownloadPollStatus? {
    return try {
      dm.query(DownloadManager.Query().setFilterById(downloadId)).use { cursor ->
        if (cursor == null || !cursor.moveToFirst()) return null
        val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val totalCol = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val downloadedCol = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val uriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)

        when (if (statusCol >= 0) cursor.getInt(statusCol) else -1) {
          DownloadManager.STATUS_SUCCESSFUL -> {
            val localUriStr = if (uriCol >= 0) cursor.getString(uriCol) else null
            val resolvedFile = localUriStr?.let { Uri.parse(it).path }?.let(::File)
              ?: expectedApkFile
            if (resolvedFile != null && resolvedFile.exists()) {
              DownloadPollStatus.Completed(resolvedFile)
            } else {
              DownloadPollStatus.Failed
            }
          }
          DownloadManager.STATUS_FAILED -> DownloadPollStatus.Failed
          DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> {
            val total = if (totalCol >= 0) cursor.getLong(totalCol) else -1L
            val downloaded = if (downloadedCol >= 0) cursor.getLong(downloadedCol) else 0L
            val pct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else null
            DownloadPollStatus.InProgress(pct)
          }
          else -> null
        }
      }
    } catch (_: Exception) {
      DownloadPollStatus.Failed
    }
  }

  private fun onApkDownloaded(apkFile: File) {
    if (activeDownloadId == -1L) return
    cleanupDownloadMonitor()
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
    if (_state.value !is AppUpdateState.Downloading) {
      pendingInstallFile = null
    }
    _state.value = AppUpdateState.Idle
  }

  private fun cleanupDownloadMonitor() {
    activeDownloadId = -1L
    downloadMonitorJob?.cancel()
    downloadMonitorJob = null
    downloadReceiver?.let { r ->
      runCatching { context.unregisterReceiver(r) }
    }
    downloadReceiver = null
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
    checkJob?.cancel()
    cleanupDownloadMonitor()
  }
}
