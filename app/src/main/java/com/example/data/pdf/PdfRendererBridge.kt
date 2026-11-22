package com.example.data.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

data class PdfPixelRect(
  val left: Int,
  val top: Int,
  val right: Int,
  val bottom: Int,
) {
  val width: Int get() = (right - left).coerceAtLeast(1)
  val height: Int get() = (bottom - top).coerceAtLeast(1)
}

data class PdfCropScalePlan(
  val pageWidthPt: Int,
  val pageHeightPt: Int,
  val isUncroppedA4: Boolean,
  val fullRenderWidthPx: Int,
  val fullRenderHeightPx: Int,
  val renderScale: Float,
  val cropLeftPx: Int,
  val cropTopPx: Int,
  val croppedWidthPx: Int,
  val croppedHeightPx: Int,
)

data class PdfRenderedPageBitmap(
  val bitmap: Bitmap,
  val croppedWidthPx: Int,
  val croppedHeightPx: Int,
  val topBandEndY: Int,
  val bottomBandStartY: Int,
  val textBounds: PdfPixelRect,
)

data class PdfPageCacheKey(
  val filePath: String,
  val fileLength: Long,
  val lastModified: Long,
  val pageIndex: Int,
  val widthPx: Int,
)

class PdfRendererBridge {
  companion object {
    private const val TAG = "PdfRendererBridge"
    const val CROPPED_PAGE_WIDTH_PT = 472f
    const val CROPPED_PAGE_HEIGHT_PT = 688f
    const val TOP_BAND_END_PT = 30f
    const val BOTTOM_BAND_START_PT = 640f
    const val MAX_RENDER_WIDTH_PX = 2400
    const val NON_WHITE_GRAY_THRESHOLD = 235
    const val TEXT_BOX_PADDING_RATIO = 0.02f

    // Useful area inside original 595x842 page: x 62..534, y 74..762 (bottom-left origin)
    const val UNCROPPED_FRAC_LEFT = 62f / 595f
    const val UNCROPPED_FRAC_RIGHT = 534f / 595f
    const val UNCROPPED_FRAC_TOP = (842f - 762f) / 842f
    const val UNCROPPED_FRAC_BOTTOM = (842f - 74f) / 842f

    private const val MIN_CACHE_BYTES = 32 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 128 * 1024 * 1024

    fun computeCropScalePlan(
      pageWidthPt: Int,
      pageHeightPt: Int,
      onScreenWidthPx: Int,
    ): PdfCropScalePlan {
      val pw = pageWidthPt.coerceAtLeast(1).toFloat()
      val ph = pageHeightPt.coerceAtLeast(1).toFloat()
      val targetCroppedW = (onScreenWidthPx.coerceAtLeast(32) * 2).coerceIn(64, MAX_RENDER_WIDTH_PX)
      val isUncroppedA4 = pageWidthPt >= 560 && pageHeightPt >= 800

      if (isUncroppedA4) {
        val usefulWidthFraction = UNCROPPED_FRAC_RIGHT - UNCROPPED_FRAC_LEFT
        val fullW = (targetCroppedW.toFloat() / usefulWidthFraction).roundToInt().coerceAtLeast(64)
        val scale = fullW.toFloat() / pw
        val fullH = (ph * scale).roundToInt().coerceAtLeast(64)
        val cropX = (fullW * UNCROPPED_FRAC_LEFT).roundToInt().coerceIn(0, fullW - 2)
        val cropY = (fullH * UNCROPPED_FRAC_TOP).roundToInt().coerceIn(0, fullH - 2)
        val cropRight = (fullW * UNCROPPED_FRAC_RIGHT).roundToInt().coerceIn(cropX + 1, fullW)
        val cropBottom = (fullH * UNCROPPED_FRAC_BOTTOM).roundToInt().coerceIn(cropY + 1, fullH)
        return PdfCropScalePlan(
          pageWidthPt = pageWidthPt,
          pageHeightPt = pageHeightPt,
          isUncroppedA4 = true,
          fullRenderWidthPx = fullW,
          fullRenderHeightPx = fullH,
          renderScale = scale,
          cropLeftPx = cropX,
          cropTopPx = cropY,
          croppedWidthPx = (cropRight - cropX).coerceAtLeast(1),
          croppedHeightPx = (cropBottom - cropY).coerceAtLeast(1),
        )
      }

      val scale = targetCroppedW.toFloat() / pw
      val fullH = (ph * scale).roundToInt().coerceAtLeast(64)
      return PdfCropScalePlan(
        pageWidthPt = pageWidthPt,
        pageHeightPt = pageHeightPt,
        isUncroppedA4 = false,
        fullRenderWidthPx = targetCroppedW,
        fullRenderHeightPx = fullH,
        renderScale = scale,
        cropLeftPx = 0,
        cropTopPx = 0,
        croppedWidthPx = targetCroppedW,
        croppedHeightPx = fullH,
      )
    }

    fun computeBandBoundariesPx(bmpHeightPx: Int): Pair<Int, Int> {
      val safeH = bmpHeightPx.coerceAtLeast(8)
      val topBandEndY = ((TOP_BAND_END_PT / CROPPED_PAGE_HEIGHT_PT) * safeH)
        .roundToInt()
        .coerceIn(1, safeH - 3)
      val bottomBandStartY = ((BOTTOM_BAND_START_PT / CROPPED_PAGE_HEIGHT_PT) * safeH)
        .roundToInt()
        .coerceIn(topBandEndY + 2, safeH - 1)
      return topBandEndY to bottomBandStartY
    }

    fun computePaddedTextBounds(
      minX: Int,
      minY: Int,
      maxX: Int,
      maxY: Int,
      bmpWidthPx: Int,
      topBandEndY: Int,
      bottomBandStartY: Int,
    ): PdfPixelRect {
      if (maxX < minX || maxY < minY) {
        return PdfPixelRect(0, topBandEndY, bmpWidthPx.coerceAtLeast(1), bottomBandStartY.coerceAtLeast(topBandEndY + 1))
      }
      val boxW = (maxX - minX + 1).toFloat()
      val boxH = (maxY - minY + 1).toFloat()
      val padX = (boxW * TEXT_BOX_PADDING_RATIO).roundToInt()
      val padY = (boxH * TEXT_BOX_PADDING_RATIO).roundToInt()
      val left = (minX - padX).coerceIn(0, (bmpWidthPx - 1).coerceAtLeast(0))
      val top = (minY - padY).coerceIn(topBandEndY, (bottomBandStartY - 1).coerceAtLeast(topBandEndY))
      val right = (maxX + 1 + padX).coerceIn(left + 1, bmpWidthPx.coerceAtLeast(left + 1))
      val bottom = (maxY + 1 + padY).coerceIn(top + 1, bottomBandStartY.coerceAtLeast(top + 1))
      return PdfPixelRect(left = left, top = top, right = right, bottom = bottom)
    }

    fun scanTextBoundsInMiddleBand(
      bmpWidthPx: Int,
      bmpHeightPx: Int,
      readRow: (y: Int, rowBuffer: IntArray) -> Unit,
    ): Triple<Int, Int, PdfPixelRect> {
      val (topBandEndY, bottomBandStartY) = computeBandBoundariesPx(bmpHeightPx)
      val rowBuffer = IntArray(bmpWidthPx)
      var minX = bmpWidthPx
      var minY = bottomBandStartY
      var maxX = -1
      var maxY = -1

      for (y in topBandEndY until bottomBandStartY) {
        readRow(y, rowBuffer)
        for (x in 0 until bmpWidthPx) {
          val px = rowBuffer[x]
          val r = (px ushr 16) and 0xFF
          val g = (px ushr 8) and 0xFF
          val b = px and 0xFF
          val gray = (r * 299 + g * 587 + b * 114) / 1000
          if (gray < NON_WHITE_GRAY_THRESHOLD) {
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
          }
        }
      }

      val bounds = computePaddedTextBounds(
        minX = minX,
        minY = minY,
        maxX = maxX,
        maxY = maxY,
        bmpWidthPx = bmpWidthPx,
        topBandEndY = topBandEndY,
        bottomBandStartY = bottomBandStartY,
      )
      return Triple(topBandEndY, bottomBandStartY, bounds)
    }
  }

  private val mutex = Mutex()
  private val loggedFirstPages = HashSet<String>()

  private val cacheLimitBytes = (Runtime.getRuntime().maxMemory() / 4L)
    .coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong())
    .toInt()

  private val pageBitmapCache = object : LruCache<PdfPageCacheKey, PdfRenderedPageBitmap>(cacheLimitBytes) {
    override fun sizeOf(key: PdfPageCacheKey, value: PdfRenderedPageBitmap): Int =
      value.bitmap.allocationByteCount.coerceAtLeast(1)
  }

  private var openMushafId: String? = null
  private var openFilePath: String? = null
  private var openFileLength: Long = -1L
  private var openPfd: ParcelFileDescriptor? = null
  private var openRenderer: PdfRenderer? = null

  private fun makeCacheKey(
    pdfFile: File,
    pageNumber: Int,
    onScreenWidthPx: Int,
  ): PdfPageCacheKey = PdfPageCacheKey(
    filePath = pdfFile.absolutePath,
    fileLength = pdfFile.length(),
    lastModified = pdfFile.lastModified(),
    pageIndex = pageNumber - 1,
    widthPx = onScreenWidthPx,
  )

  fun getCached(
    pdfFile: File,
    pageNumber: Int,
    onScreenWidthPx: Int,
  ): PdfRenderedPageBitmap? {
    if (onScreenWidthPx <= 0 || pageNumber < 1) return null
    return pageBitmapCache.get(makeCacheKey(pdfFile, pageNumber, onScreenWidthPx))
  }

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
    onScreenWidthPx: Int,
  ): PdfRenderedPageBitmap = withContext(Dispatchers.Default) {
    val cacheKey = makeCacheKey(pdfFile, pageNumber, onScreenWidthPx)
    pageBitmapCache.get(cacheKey)?.let { return@withContext it }

    mutex.withLock {
      pageBitmapCache.get(cacheKey)?.let { return@withLock it }

      val renderer = ensureOpenLocked(mushafId, pdfFile)
      val pageIndex = pageNumber - 1
      if (pageIndex !in 0 until renderer.pageCount) {
        throw IllegalStateException("الصفحة $pageNumber غير متوفرة في هذا المصحف")
      }
      val rendered = renderer.openPage(pageIndex).use { page ->
        val logKey = "$mushafId|${pdfFile.absolutePath}|${pdfFile.length()}"
        if (loggedFirstPages.add(logKey)) {
          Log.d(
            TAG,
            "First opened PDF page: mushaf=$mushafId page=$pageNumber page.width=${page.width} page.height=${page.height} screenW=$onScreenWidthPx",
          )
        }

        val plan = computeCropScalePlan(
          pageWidthPt = page.width,
          pageHeightPt = page.height,
          onScreenWidthPx = onScreenWidthPx,
        )

        val fullBmp = Bitmap.createBitmap(
          plan.fullRenderWidthPx,
          plan.fullRenderHeightPx,
          Bitmap.Config.ARGB_8888,
        )
        Canvas(fullBmp).drawColor(Color.WHITE)

        val matrix = Matrix().apply {
          setScale(plan.renderScale, plan.renderScale)
        }
        page.render(fullBmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

        val croppedBmp = if (!plan.isUncroppedA4) {
          fullBmp
        } else {
          try {
            Bitmap.createBitmap(
              fullBmp,
              plan.cropLeftPx,
              plan.cropTopPx,
              plan.croppedWidthPx,
              plan.croppedHeightPx,
            )
          } finally {
            fullBmp.recycle()
          }
        }

        val (topBandEndY, bottomBandStartY, textBounds) = scanTextBoundsInMiddleBand(
          bmpWidthPx = croppedBmp.width,
          bmpHeightPx = croppedBmp.height,
        ) { y, rowBuffer ->
          croppedBmp.getPixels(rowBuffer, 0, croppedBmp.width, 0, y, croppedBmp.width, 1)
        }

        PdfRenderedPageBitmap(
          bitmap = croppedBmp,
          croppedWidthPx = croppedBmp.width,
          croppedHeightPx = croppedBmp.height,
          topBandEndY = topBandEndY,
          bottomBandStartY = bottomBandStartY,
          textBounds = textBounds,
        )
      }

      pageBitmapCache.put(cacheKey, rendered)
      rendered
    }
  }

  suspend fun closeIfMatches(mushafId: String) {
    mutex.withLock {
      val prevPath = if (openMushafId == mushafId) openFilePath else null
      if (openMushafId == mushafId) {
        closeCurrentLocked()
      }
      val snapshot = pageBitmapCache.snapshot()
      for (key in snapshot.keys) {
        if ((prevPath != null && key.filePath == prevPath) || key.filePath.contains("/$mushafId.pdf")) {
          pageBitmapCache.remove(key)
        }
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
    pageBitmapCache.evictAll()
  }
}
