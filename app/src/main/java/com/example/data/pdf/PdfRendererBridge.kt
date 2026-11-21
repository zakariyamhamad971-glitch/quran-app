package com.example.data.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PdfRendererBridge {
  private val mutex = Mutex()

  private var openMushafId: String? = null
  private var openFilePath: String? = null
  private var openFileLength: Long = -1L
  private var openPfd: ParcelFileDescriptor? = null
  private var openRenderer: PdfRenderer? = null

  private fun closeCurrentLocked() {
    runCatching { openRenderer?.close() }
    runCatching { openPfd?.close() }
    openRenderer = null
    openPfd = null
    openMushafId = null
    openFilePath = null
    openFileLength = -1L
  }

  private fun ensureOpenLocked(mushafId: String, file: File): PdfRenderer {
    val path = file.absolutePath
    val len = file.length()
    val existing = openRenderer
    if (existing != null && openMushafId == mushafId && openFilePath == path && openFileLength == len) {
      return existing
    }
    closeCurrentLocked()
    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val renderer = try {
      PdfRenderer(pfd)
    } catch (e: Exception) {
      runCatching { pfd.close() }
      throw IllegalStateException("تعذر فتح ملف المصحف PDF", e)
    }
    openPfd = pfd
    openRenderer = renderer
    openMushafId = mushafId
    openFilePath = path
    openFileLength = len
    return renderer
  }

  suspend fun renderPage(
    mushafId: String,
    pdfFile: File,
    pageNumber: Int,
    targetWidthPx: Int,
    targetHeightPx: Int,
  ): Bitmap = withContext(Dispatchers.Default) {
    val w = targetWidthPx.coerceAtLeast(32)
    val h = targetHeightPx.coerceAtLeast(32)
    mutex.withLock {
      val renderer = ensureOpenLocked(mushafId, pdfFile)
      val pageIndex = pageNumber - 1
      if (pageIndex !in 0 until renderer.pageCount) {
        throw IllegalStateException("الصفحة $pageNumber غير متوفرة في هذا المصحف")
      }
      renderer.openPage(pageIndex).use { page ->
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        val pw = page.width.toFloat().coerceAtLeast(1f)
        val ph = page.height.toFloat().coerceAtLeast(1f)
        // Edge-to-edge fill inside frame body while preserving aspect ratio (no clipped riwaya header/color legend)
        val scale = minOf(w.toFloat() / pw, h.toFloat() / ph)
        val drawW = pw * scale
        val drawH = ph * scale
        val dx = (w.toFloat() - drawW) * 0.5f
        val dy = (h.toFloat() - drawH) * 0.5f
        val matrix = Matrix().apply {
          postScale(scale, scale)
          postTranslate(dx, dy)
        }
        page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        bmp
      }
    }
  }

  suspend fun closeIfMatches(mushafId: String) {
    mutex.withLock {
      if (openMushafId == mushafId) {
        closeCurrentLocked()
      }
    }
  }

  fun closeBlocking() {
    runCatching { openRenderer?.close() }
    runCatching { openPfd?.close() }
    openRenderer = null
    openPfd = null
    openMushafId = null
    openFilePath = null
    openFileLength = -1L
  }
}
