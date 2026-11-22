package com.example.data.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
  val segmentation: PdfPageSegmentation,
)

data class PdfPageCacheKey(
  val filePath: String,
  val fileLength: Long,
  val lastModified: Long,
  val pageIndex: Int,
  val widthPx: Int,
)

data class InkRowInfo(
  val hasInk: Boolean,
  val minX: Int,
  val maxX: Int,
)

data class InkCluster(
  val startY: Int,
  val endY: Int,
  val minX: Int,
  val maxX: Int,
)

class PdfRendererBridge {
  companion object {
    private const val TAG = "PdfRendererBridge"
    const val CROPPED_PAGE_WIDTH_PT = 472f
    const val CROPPED_PAGE_HEIGHT_PT = 688f
    const val MAX_RENDER_WIDTH_PX = 2400
    const val NON_WHITE_GRAY_THRESHOLD = 235

    // Useful area inside original 595x842 page: x 62..534, y 74..762 (origin bottom-left)
    // In top-left coords: top = (842 - 762) = 80, bottom = (842 - 74) = 768.
    const val UNCROPPED_ORIGIN_LEFT = 62f
    const val UNCROPPED_ORIGIN_RIGHT = 534f
    const val UNCROPPED_ORIGIN_TOP = 80f
    const val UNCROPPED_ORIGIN_BOTTOM = 768f
    const val FULL_PAGE_WIDTH_PT = 595f
    const val FULL_PAGE_HEIGHT_PT = 842f

    private const val MIN_CACHE_BYTES = 32 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 128 * 1024 * 1024

    private val totalColdRenders = AtomicInteger(0)
    private val totalColdTimeMs = AtomicLong(0)
    private val totalCachedHits = AtomicInteger(0)

    fun getPerformanceStats(): String {
      val coldCount = totalColdRenders.get()
      val coldTime = totalColdTimeMs.get()
      val avgCold = if (coldCount > 0) coldTime / coldCount else 0L
      val hits = totalCachedHits.get()
      return "Cold: $coldCount renders, avg ${avgCold}ms. Cached hits: $hits"
    }

    fun computeCropScalePlan(
      pageWidthPt: Int,
      pageHeightPt: Int,
      onScreenWidthPx: Int,
      cropLeftPt: Float = 0f,
      cropRightPt: Float = 0f,
    ): PdfCropScalePlan {
      val pw = pageWidthPt.coerceAtLeast(1).toFloat()
      val ph = pageHeightPt.coerceAtLeast(1).toFloat()
      val targetCroppedW = (onScreenWidthPx.coerceAtLeast(32) * 2).coerceIn(64, MAX_RENDER_WIDTH_PX)
      val isUncroppedA4 = pageWidthPt >= 560 && pageHeightPt >= 800

      if (isUncroppedA4) {
        val usefulLeft = (UNCROPPED_ORIGIN_LEFT + cropLeftPt).coerceIn(0f, pw - 30f)
        val usefulRight = (UNCROPPED_ORIGIN_RIGHT - cropRightPt).coerceIn(usefulLeft + 30f, pw)
        val usefulTop = UNCROPPED_ORIGIN_TOP.coerceIn(0f, ph - 30f)
        val usefulBottom = UNCROPPED_ORIGIN_BOTTOM.coerceIn(usefulTop + 30f, ph)

        val usefulWidthFrac = (usefulRight - usefulLeft) / pw
        val fullW = (targetCroppedW.toFloat() / usefulWidthFrac).roundToInt().coerceAtLeast(64)
        val scale = fullW.toFloat() / pw
        val fullH = (ph * scale).roundToInt().coerceAtLeast(64)

        val cropX = (scale * usefulLeft).roundToInt().coerceIn(0, fullW - 2)
        val cropY = (scale * usefulTop).roundToInt().coerceIn(0, fullH - 2)
        val cropRightPx = (scale * usefulRight).roundToInt().coerceIn(cropX + 1, fullW)
        val cropBottomPx = (scale * usefulBottom).roundToInt().coerceIn(cropY + 1, fullH)

        return PdfCropScalePlan(
          pageWidthPt = pageWidthPt,
          pageHeightPt = pageHeightPt,
          isUncroppedA4 = true,
          fullRenderWidthPx = fullW,
          fullRenderHeightPx = fullH,
          renderScale = scale,
          cropLeftPx = cropX,
          cropTopPx = cropY,
          croppedWidthPx = (cropRightPx - cropX).coerceAtLeast(1),
          croppedHeightPx = (cropBottomPx - cropY).coerceAtLeast(1),
        )
      }

      // Page is already cropped to text box; apply custom crop margins if requested
      if (cropLeftPt > 0f || cropRightPt > 0f) {
        val usefulW = (pw - cropLeftPt - cropRightPt).coerceAtLeast(30f)
        val scale = targetCroppedW.toFloat() / usefulW
        val fullW = (pw * scale).roundToInt().coerceAtLeast(64)
        val fullH = (ph * scale).roundToInt().coerceAtLeast(64)
        val cropX = (scale * cropLeftPt).roundToInt().coerceIn(0, fullW - 2)
        val cropRightPx = (scale * (pw - cropRightPt)).roundToInt().coerceIn(cropX + 1, fullW)
        return PdfCropScalePlan(
          pageWidthPt = pageWidthPt,
          pageHeightPt = pageHeightPt,
          isUncroppedA4 = true,
          fullRenderWidthPx = fullW,
          fullRenderHeightPx = fullH,
          renderScale = scale,
          cropLeftPx = cropX,
          cropTopPx = 0,
          croppedWidthPx = (cropRightPx - cropX).coerceAtLeast(1),
          croppedHeightPx = fullH,
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

    /**
     * Segments the page by finding genuine blank-row gaps, ensuring cuts NEVER slice through ink.
     * Guaranteed:
     * - Label band is only cut in the exact middle of the blank gap below the top label cluster.
     * - Legend band is only cut in the exact middle of the blank gap above the bottom legend cluster.
     * - Text block is bounded cleanly between label cut and legend cut.
     * - Few-lines pages (3..11 lines) are split into individual line strips at blank-row gaps (>=2px).
     */
    fun computePageSegmentation(
      width: Int,
      height: Int,
      readRow: (y: Int, rowBuffer: IntArray) -> Unit,
    ): PdfPageSegmentation {
      val safeW = width.coerceAtLeast(1)
      val safeH = height.coerceAtLeast(1)
      val rowBuffer = IntArray(safeW)

      // Step 1: Scan per-row ink info using standard luminance < 235
      val rows = Array(safeH) { y ->
        readRow(y, rowBuffer)
        var rowMinX = safeW
        var rowMaxX = -1
        var hasInk = false
        for (x in 0 until safeW) {
          val px = rowBuffer[x]
          val r = (px ushr 16) and 0xFF
          val g = (px ushr 8) and 0xFF
          val b = px and 0xFF
          val lum = (r * 299 + g * 587 + b * 114) / 1000
          if (lum < NON_WHITE_GRAY_THRESHOLD) {
            hasInk = true
            if (x < rowMinX) rowMinX = x
            if (x > rowMaxX) rowMaxX = x
          }
        }
        InkRowInfo(hasInk = hasInk, minX = rowMinX, maxX = rowMaxX)
      }

      // Step 2: Group contiguous ink rows into raw clusters
      val rawClusters = ArrayList<InkCluster>()
      var currentStart = -1
      var currentMinX = safeW
      var currentMaxX = -1
      for (y in 0 until safeH) {
        val info = rows[y]
        if (info.hasInk) {
          if (currentStart < 0) {
            currentStart = y
            currentMinX = info.minX
            currentMaxX = info.maxX
          } else {
            if (info.minX < currentMinX) currentMinX = info.minX
            if (info.maxX > currentMaxX) currentMaxX = info.maxX
          }
        } else {
          if (currentStart >= 0) {
            rawClusters += InkCluster(currentStart, y - 1, currentMinX, currentMaxX)
            currentStart = -1
            currentMinX = safeW
            currentMaxX = -1
          }
        }
      }
      if (currentStart >= 0) {
        rawClusters += InkCluster(currentStart, safeH - 1, currentMinX, currentMaxX)
      }

      if (rawClusters.isEmpty()) {
        return PdfPageSegmentation(
          hasLabelBand = false,
          labelCutFraction = 0f,
          hasLegendBand = false,
          legendCutFraction = 1f,
          textMinXFraction = 0f,
          textMaxXFraction = 1f,
          textMinYFraction = 0f,
          textMaxYFraction = 1f,
          widestLineWidthFraction = 1f,
          isFewLines = false,
          lines = emptyList(),
        )
      }

      // Step 3: Merge clusters separated by <= 3 px (and <= 5 px in legend area > 78% height)
      val mergedClusters = ArrayList<InkCluster>()
      for (c in rawClusters) {
        if (mergedClusters.isEmpty()) {
          mergedClusters += c
        } else {
          val last = mergedClusters.last()
          val gap = c.startY - last.endY - 1
          val threshold = if (last.startY >= (0.78f * safeH).toInt() || c.startY >= (0.78f * safeH).toInt()) 5 else 3
          if (gap <= threshold) {
            mergedClusters[mergedClusters.lastIndex] = InkCluster(
              startY = last.startY,
              endY = c.endY,
              minX = minOf(last.minX, c.minX),
              maxX = maxOf(last.maxX, c.maxX),
            )
          } else {
            mergedClusters += c
          }
        }
      }

      // Step 4: Legend band detection (last cluster below 80% height, height >= 25px, gap above >= 3px)
      var hasLegendBand = false
      var legendCutRow = safeH
      var activeClusters = mergedClusters

      if (mergedClusters.size >= 2) {
        val last = mergedClusters.last()
        val prev = mergedClusters[mergedClusters.size - 2]
        val clusterH = last.endY - last.startY + 1
        val gapAbove = last.startY - prev.endY - 1
        if (last.startY >= (0.80f * safeH).toInt() && clusterH >= 25 && gapAbove >= 3) {
          hasLegendBand = true
          legendCutRow = (prev.endY + last.startY) / 2
          activeClusters = ArrayList(mergedClusters.dropLast(1))
        }
      }

      // Step 5: Label band detection (first cluster ending <= 6% height, gap below >= 3px)
      var hasLabelBand = false
      var labelCutRow = 0
      if (activeClusters.size >= 2) {
        val first = activeClusters.first()
        val next = activeClusters[1]
        val gapBelow = next.startY - first.endY - 1
        if (first.endY <= (0.06f * safeH).toInt() && gapBelow >= 3) {
          hasLabelBand = true
          labelCutRow = (first.endY + next.startY) / 2
          activeClusters = ArrayList(activeClusters.drop(1))
        }
      }

      // Step 6: Text block between labelCutRow and legendCutRow
      var textMinX = safeW
      var textMaxX = -1
      var textMinY = legendCutRow
      var textMaxY = -1
      var maxLineWidth = 0

      for (y in labelCutRow until legendCutRow) {
        val r = rows[y]
        if (r.hasInk) {
          if (r.minX < textMinX) textMinX = r.minX
          if (r.maxX > textMaxX) textMaxX = r.maxX
          if (y < textMinY) textMinY = y
          if (y > textMaxY) textMaxY = y
          val lineW = r.maxX - r.minX + 1
          if (lineW > maxLineWidth) maxLineWidth = lineW
        }
      }

      if (textMaxX < textMinX) {
        textMinX = 0
        textMaxX = safeW - 1
        textMinY = labelCutRow
        textMaxY = (legendCutRow - 1).coerceAtLeast(labelCutRow)
        maxLineWidth = safeW
      }

      // Step 7: Detect individual text lines (splitting at blank gaps >= 2px)
      val lineRuns = ArrayList<Pair<Int, Int>>()
      var lineStart = -1
      for (y in labelCutRow until legendCutRow) {
        if (rows[y].hasInk) {
          if (lineStart < 0) lineStart = y
        } else {
          if (lineStart >= 0) {
            lineRuns += (lineStart to y - 1)
            lineStart = -1
          }
        }
      }
      if (lineStart >= 0) {
        lineRuns += (lineStart to (legendCutRow - 1).coerceAtLeast(lineStart))
      }

      // Merge runs with gap <= 1 blank px, keeping genuine blank gaps >= 2px as line dividers
      val linesList = ArrayList<Pair<Int, Int>>()
      for (run in lineRuns) {
        if (linesList.isEmpty()) {
          linesList += run
        } else {
          val prev = linesList.last()
          val gap = run.first - prev.second - 1
          if (gap <= 1) {
            linesList[linesList.lastIndex] = (prev.first to run.second)
          } else {
            linesList += run
          }
        }
      }

      val isFewLines = linesList.size in 3..11
      val lineStrips = if (isFewLines) {
        linesList.mapIndexed { idx, lineRange ->
          val topCut = if (idx == 0) labelCutRow else (linesList[idx - 1].second + lineRange.first) / 2
          val botCut = if (idx == linesList.lastIndex) legendCutRow else (lineRange.second + linesList[idx + 1].first) / 2
          var lMinX = safeW
          var lMaxX = -1
          for (y in lineRange.first..lineRange.second) {
            val r = rows[y]
            if (r.hasInk) {
              if (r.minX < lMinX) lMinX = r.minX
              if (r.maxX > lMaxX) lMaxX = r.maxX
            }
          }
          if (lMaxX < lMinX) {
            lMinX = textMinX
            lMaxX = textMaxX
          }
          PdfLineStripFraction(
            srcTopFraction = topCut.toFloat() / safeH.toFloat(),
            srcBottomFraction = botCut.toFloat() / safeH.toFloat(),
            minXFraction = lMinX.toFloat() / safeW.toFloat(),
            maxXFraction = lMaxX.toFloat() / safeW.toFloat(),
          )
        }
      } else {
        emptyList()
      }

      return PdfPageSegmentation(
        hasLabelBand = hasLabelBand,
        labelCutFraction = labelCutRow.toFloat() / safeH.toFloat(),
        hasLegendBand = hasLegendBand,
        legendCutFraction = legendCutRow.toFloat() / safeH.toFloat(),
        textMinXFraction = textMinX.toFloat() / safeW.toFloat(),
        textMaxXFraction = textMaxX.toFloat() / safeW.toFloat(),
        textMinYFraction = textMinY.toFloat() / safeH.toFloat(),
        textMaxYFraction = textMaxY.toFloat() / safeH.toFloat(),
        widestLineWidthFraction = (maxLineWidth.toFloat() / safeW.toFloat()).coerceIn(0.1f, 1f),
        isFewLines = isFewLines,
        lines = lineStrips,
      )
    }
  }

  private val mutex = Mutex()
  private val loggedFirstPages = HashSet<String>()
  private val segmentationCache = ConcurrentHashMap<String, PdfPageSegmentation>()

  // High-res bitmap cache sized to ~1/8 of max memory (32MB - 128MB)
  private val cacheLimitBytes = (Runtime.getRuntime().maxMemory() / 8L)
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
    val hit = pageBitmapCache.get(makeCacheKey(pdfFile, pageNumber, onScreenWidthPx))
    if (hit != null) {
      totalCachedHits.incrementAndGet()
    }
    return hit
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
    cropLeftPt: Float = 0f,
    cropRightPt: Float = 0f,
  ): PdfRenderedPageBitmap = withContext(Dispatchers.Default) {
    val cacheKey = makeCacheKey(pdfFile, pageNumber, onScreenWidthPx)
    pageBitmapCache.get(cacheKey)?.let {
      totalCachedHits.incrementAndGet()
      return@withContext it
    }

    val t0 = SystemClock.elapsedRealtime()

    val rendered = mutex.withLock {
      pageBitmapCache.get(cacheKey)?.let {
        totalCachedHits.incrementAndGet()
        return@withLock it
      }

      val renderer = ensureOpenLocked(mushafId, pdfFile)
      val pageIndex = pageNumber - 1
      if (pageIndex !in 0 until renderer.pageCount) {
        throw IllegalStateException("الصفحة $pageNumber غير متوفرة في هذا المصحف")
      }

      val segKey = "${pdfFile.absolutePath}|${pdfFile.length()}|$pageIndex"

      renderer.openPage(pageIndex).use { page ->
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
          cropLeftPt = cropLeftPt,
          cropRightPt = cropRightPt,
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

        // Retrieve or compute segmentation for this page
        val segmentation = segmentationCache.getOrPut(segKey) {
          computePageSegmentation(
            width = croppedBmp.width,
            height = croppedBmp.height,
          ) { y, rowBuffer ->
            croppedBmp.getPixels(rowBuffer, 0, croppedBmp.width, 0, y, croppedBmp.width, 1)
          }
        }

        val topBandEndY = (segmentation.labelCutFraction * croppedBmp.height).roundToInt()
        val bottomBandStartY = (segmentation.legendCutFraction * croppedBmp.height).roundToInt()
        val textBounds = PdfPixelRect(
          left = (segmentation.textMinXFraction * croppedBmp.width).roundToInt(),
          top = (segmentation.textMinYFraction * croppedBmp.height).roundToInt(),
          right = (segmentation.textMaxXFraction * croppedBmp.width).roundToInt(),
          bottom = (segmentation.textMaxYFraction * croppedBmp.height).roundToInt(),
        )

        val pageResult = PdfRenderedPageBitmap(
          bitmap = croppedBmp,
          croppedWidthPx = croppedBmp.width,
          croppedHeightPx = croppedBmp.height,
          topBandEndY = topBandEndY,
          bottomBandStartY = bottomBandStartY,
          textBounds = textBounds,
          segmentation = segmentation,
        )
        pageBitmapCache.put(cacheKey, pageResult)
        pageResult
      }
    }

    val elapsed = SystemClock.elapsedRealtime() - t0
    totalColdRenders.incrementAndGet()
    totalColdTimeMs.addAndGet(elapsed)
    if (BuildConfig.DEBUG) {
      Log.d("PdfMushaf", "Page $pageNumber rendered in ${elapsed}ms (cold=true, stats: ${getPerformanceStats()})")
    }

    rendered
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
      segmentationCache.keys.removeIf { it.contains("/$mushafId.pdf") }
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
    segmentationCache.clear()
  }
}
