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
  companion object {
    private const val RELEASE_API_URL =
      "https://api.github.com/repos/zakariyamhamad971-glitch/quran-app/releases/tags/latest"
    private val BUILD_NUMBER_REGEX = Regex("""Build\s+(\d+)""", RegexOption.IGNORE_CASE)
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
  fun checkForUpdatesInBackground() {
    if (hasAutoChecked) return
    hasAutoChecked = true
    if (_state.value !is AppUpdateState.Idle) return

    autoCheckJob = scope.launch(Dispatchers.IO) {
      if (!hasActiveNetwork()) return@launch
      val result = fetchLatestReleaseInfo()
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
  fun checkForUpdates() {
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
