package com.example

import android.app.Application
import android.content.Intent
import com.example.ui.CrashActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class QabasatApplication : Application() {

  override fun onCreate() {
    val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
      runCatching {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTraceStr = sw.toString()

        val crashFile = File(filesDir, "last_crash.txt")
        crashFile.writeText(stackTraceStr, Charsets.UTF_8)

        val intent = Intent(this, CrashActivity::class.java).apply {
          putExtra("extra_stack_trace", stackTraceStr)
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
      }
      defaultHandler?.uncaughtException(thread, throwable) ?: run {
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(10)
      }
    }

    super.onCreate()
  }
}
