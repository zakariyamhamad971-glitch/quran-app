package com.example.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.example.R
import java.io.File

class CrashActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_crash)

    val stackTraceTv = findViewById<TextView>(R.id.crashStackTraceText)
    val copyBtn = findViewById<Button>(R.id.copyCrashButton)

    var trace = intent.getStringExtra("extra_stack_trace")
    if (trace.isNullOrBlank()) {
      runCatching {
        val crashFile = File(filesDir, "last_crash.txt")
        if (crashFile.exists()) {
          trace = crashFile.readText(Charsets.UTF_8)
        }
      }
    }

    stackTraceTv.text = trace?.takeIf { it.isNotBlank() } ?: "لا توجد تفاصيل متاحة للكراش."

    copyBtn.setOnClickListener {
      val textToCopy = stackTraceTv.text.toString()
      runCatching {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Crash StackTrace", textToCopy)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "تم نسخ تفاصيل الخطأ للحافظة", Toast.LENGTH_SHORT).show()
      }
    }
  }
}
