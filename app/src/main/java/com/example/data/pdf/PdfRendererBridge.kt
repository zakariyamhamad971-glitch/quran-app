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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

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
  val needsCrop: Boolean = isUncroppedA4,
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
  val isTwoPageMode: Boolean = false,
  val scaleMultiplierBits: Int = 1f.toRawBits(),
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
    const val MAX_SINGLE_PAGE_WIDTH_PX = 1600
    const val MAX_TWO_PAGE_WIDTH_PX = 1100
    const val MAX_RENDER_WIDTH_PX = MAX_SINGLE_PAGE_WIDTH_PX
    const val MAX_RENDER_HEIGHT_PX = 2500
    const val MAX_BITMAP_PIXELS = 2_500_000L
    const val PREVIEW_SCALE_FACTOR = 0.4f
    const val ANALYSIS_TARGET_WIDTH_PX = 236
    const val NON_WHITE_GRAY_THRESHOLD = 235

    // Useful area inside original 595x842 page: x 62..534, y 74..762 (origin bottom-left)
    // In top-left coords: top = (842 - 762) = 80, bottom = (842 - 74) = 768.
    const val UNCROPPED_ORIGIN_LEFT = 62f
    const val UNCROPPED_ORIGIN_RIGHT = 534f
    const val UNCROPPED_ORIGIN_TOP = 80f
    const val UNCROPPED_ORIGIN_BOTTOM = 768f
    const val FULL_PAGE_WIDTH_PT = 595f
    const val FULL_PAGE_HEIGHT_PT = 842f

    private const val MIN_CACHE_BYTES = 16 * 1024 * 1024
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
      isTwoPageMode: Boolean = false,
      textBlockZoom: Float = 1f,
      scaleMultiplier: Float = 1f,
    ): PdfCropScalePlan {
      val pw = pageWidthPt.coerceAtLeast(30).toFloat()
      val ph = pageHeightPt.coerceAtLeast(30).toFloat()
      val isUncroppedA4 = pageWidthPt >= 560 && pageHeightPt >= 800

      val baseLeft = if (isUncroppedA4) UNCROPPED_ORIGIN_LEFT.coerceIn(0f, pw - 30f) else 0f
      val baseRight = if (isUncroppedA4) UNCROPPED_ORIGIN_RIGHT.coerceIn(baseLeft + 30f, pw) else pw
      val baseTop = if (isUncroppedA4) UNCROPPED_ORIGIN_TOP.coerceIn(0f, ph - 30f) else 0f
      val baseBottom = if (isUncroppedA4) UNCROPPED_ORIGIN_BOTTOM.coerceIn(baseTop + 30f, ph) else ph
      val baseW = (baseRight - baseLeft).coerceAtLeast(30f)
      val usefulH = (baseBottom - baseTop).coerceAtLeast(30f)

      // Manifest cropLeftPt/cropRightPt may represent X coordinates inside 472pt (e.g. 14.5..456.0)
      // or small margin widths from the edges. Distinguish safely and never shrink usefulW below 50% of baseW.
      val relLeft = if (cropLeftPt > 0f && cropLeftPt < baseW * 0.45f) cropLeftPt else 0f
      val relRight = when {
        cropRightPt <= 0f -> baseW
        cropRightPt > relLeft + baseW * 0.5f && cropRightPt <= baseW + 8f ->
          cropRightPt.coerceAtMost(baseW)
        cropRightPt < baseW * 0.45f ->
          (baseW - cropRightPt).coerceAtLeast(relLeft + baseW * 0.5f)
        else -> baseW
      }

      val usefulLeft = (baseLeft + relLeft).coerceIn(0f, pw - 30f)
      val usefulRight = (baseLeft + relRight).coerceIn(usefulLeft + 30f, pw)
      val usefulW = (usefulRight - usefulLeft).coerceAtLeast(30f)

      val maxModeWidthPx = if (isTwoPageMode) MAX_TWO_PAGE_WIDTH_PX else MAX_SINGLE_PAGE_WIDTH_PX
      val clampedZoom = textBlockZoom.coerceIn(1f, 2.4f)
      val clampedMult = scaleMultiplier.coerceIn(0.15f, 1f)
      val fullTargetCroppedW = (onScreenWidthPx.coerceAtLeast(32).toFloat() * clampedZoom)
        .roundToInt()
        .coerceIn(64, maxModeWidthPx)
      val targetCroppedW = (fullTargetCroppedW.toFloat() * clampedMult).roundToInt().coerceAtLeast(64)

      var scale = (targetCroppedW.toFloat() / usefulW).coerceAtLeast(0.05f)
      val maxWidthScale = maxModeWidthPx.toFloat() / usefulW
      if (scale > maxWidthScale) {
        scale = maxWidthScale
      }
      val maxHeightScale = MAX_RENDER_HEIGHT_PX.toFloat() / usefulH
      if (scale > maxHeightScale) {
        scale = maxHeightScale
      }
      val projectedPixels = (usefulW * scale) * (usefulH * scale)
      val safeMaxPixels = MAX_BITMAP_PIXELS.toFloat() * 0.992f
      if (projectedPixels > safeMaxPixels && projectedPixels > 1f) {
        scale *= sqrt(safeMaxPixels / projectedPixels)
      }
      scale = scale.coerceAtLeast(0.05f)

      var cropX = (scale * usefulLeft).roundToInt().coerceAtLeast(0)
      var cropY = (scale * baseTop).roundToInt().coerceAtLeast(0)
      var croppedW = (scale * usefulW).roundToInt().coerceIn(64, maxModeWidthPx)
      var croppedH = (scale * usefulH).roundToInt().coerceIn(64, MAX_RENDER_HEIGHT_PX)

      if (croppedW.toLong() * croppedH.toLong() > MAX_BITMAP_PIXELS) {
        val fixFactor = sqrt((MAX_BITMAP_PIXELS.toFloat() * 0.992f) / (croppedW.toFloat() * croppedH.toFloat()))
        scale = (scale * fixFactor).coerceAtLeast(0.05f)
        cropX = (scale * usefulLeft).roundToInt().coerceAtLeast(0)
        cropY = (scale * baseTop).roundToInt().coerceAtLeast(0)
        croppedW = (scale * usefulW).roundToInt().coerceIn(64, maxModeWidthPx)
        croppedH = (scale * usefulH).roundToInt().coerceIn(64, MAX_RENDER_HEIGHT_PX)
      }

      val fullW = (pw * scale).roundToInt().coerceAtLeast(cropX + croppedW)
      val fullH = (ph * scale).roundToInt().coerceAtLeast(cropY + croppedH)

      val hasHorizontalSubCrop = relLeft > 0.25f || relRight < (baseW - 0.25f)
      if (!isUncroppedA4 && !hasHorizontalSubCrop) {
        return PdfCropScalePlan(
          pageWidthPt = pageWidthPt,
          pageHeightPt = pageHeightPt,
          isUncroppedA4 = false,
          fullRenderWidthPx = croppedW,
          fullRenderHeightPx = croppedH,
          renderScale = scale,
          cropLeftPx = 0,
          cropTopPx = 0,
          croppedWidthPx = croppedW,
          croppedHeightPx = croppedH,
          needsCrop = false,
        )
      }

      return PdfCropScalePlan(
        pageWidthPt = pageWidthPt,
        pageHeightPt = pageHeightPt,
        isUncroppedA4 = isUncroppedA4,
        fullRenderWidthPx = fullW,
        fullRenderHeightPx = fullH,
        renderScale = scale,
        cropLeftPx = cropX,
        cropTopPx = cropY,
        croppedWidthPx = croppedW,
        croppedHeightPx = croppedH,
        needsCrop = isUncroppedA4 || cropX > 0 || cropY > 0 || croppedW < fullW || croppedH < fullH,
      )
    }

    /**
     * Segments the page by finding genuine blank-row gaps, ensuring cuts NEVER slice through ink.
     * Supports both native ~688px unit-test canvases and fast ~236px analysis bitmaps (~344px tall)
     * by scaling row-height/gap thresholds proportionally when height < 550.
     */
    fun computePageSegmentation(
      width: Int,
      height: Int,
      readRow: (y: Int, rowBuffer: IntArray) -> Unit,
    ): PdfPageSegmentation {
      val safeW = width.coerceAtLeast(1)
      val safeH = height.coerceAtLeast(1)
      val rowBuffer = IntArray(safeW)

      val threshScale = if (safeH < 550) (safeH.toFloat() / CROPPED_PAGE_HEIGHT_PT).coerceIn(0.35f, 1f) else 1f
      val normalMergeGap = if (threshScale < 0.99f) (3f * threshScale).roundToInt().coerceAtLeast(1) else 3
      val legendMergeGap = if (threshScale < 0.99f) (5f * threshScale).roundToInt().coerceAtLeast(2) else 5
      val minLegendHeight = if (threshScale < 0.99f) (25f * threshScale).roundToInt().coerceAtLeast(10) else 25
      val minBandGap = if (threshScale < 0.99f) (3f * threshScale).roundToInt().coerceAtLeast(2) else 3
      val edgeGuard = if (threshScale < 0.99f) (2f * threshScale).roundToInt().coerceAtLeast(1) else 2

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

      // Step 3: Merge closely adjacent ink clusters (tolerance slightly wider in legend area)
      val mergedClusters = ArrayList<InkCluster>()
      val legendZoneY = (0.78f * safeH).toInt()
      for (c in rawClusters) {
        if (mergedClusters.isEmpty()) {
          mergedClusters += c
        } else {
          val last = mergedClusters.last()
          val gap = c.startY - last.endY - 1
          val threshold = if (last.startY >= legendZoneY || c.startY >= legendZoneY) legendMergeGap else normalMergeGap
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

      // Step 4: Legend band detection (last cluster below 80% height)
      var hasLegendBand = false
      var legendCutRow = safeH
      var activeClusters = mergedClusters

      if (mergedClusters.size >= 2) {
        val last = mergedClusters.last()
        val prev = mergedClusters[mergedClusters.size - 2]
        val clusterH = last.endY - last.startY + 1
        val gapAbove = last.startY - prev.endY - 1
        if (last.startY >= (0.80f * safeH).toInt() && clusterH >= minLegendHeight && gapAbove >= minBandGap) {
          hasLegendBand = true
          legendCutRow = (prev.endY + last.startY) / 2
          activeClusters = ArrayList(mergedClusters.dropLast(1))
        }
      }

      // Step 5: Label band detection (first cluster ending <= 6% height)
      var hasLabelBand = false
      var labelCutRow = 0
      if (activeClusters.size >= 2) {
        val first = activeClusters.first()
        val next = activeClusters[1]
        val gapBelow = next.startY - first.endY - 1
        if (first.endY <= (0.06f * safeH).toInt() && gapBelow >= minBandGap) {
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

      // Step 7: Detect individual text lines
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
          val topCut = if (idx == 0) {
            (lineRange.first - edgeGuard).coerceAtLeast(labelCutRow)
          } else {
            (linesList[idx - 1].second + lineRange.first) / 2
          }
          val botCut = if (idx == linesList.lastIndex) {
            (lineRange.second + edgeGuard + 1).coerceAtMost(legendCutRow)
          } else {
            (lineRange.second + linesList[idx + 1].first) / 2
          }
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

    /**
     * Analyzes ink rows from a bitmap. When fed a fast ~236px analysis bitmap directly,
     * reads rows in O(width * height) without intermediate bucketing overhead.
     */
    fun computePageSegmentationFromBitmap(bmp: Bitmap): PdfPageSegmentation {
      val bmpW = bmp.width.coerceAtLeast(1)
      val bmpH = bmp.height.coerceAtLeast(1)
      if (bmpW <= 520) {
        val rowBuf = IntArray(bmpW)
        return computePageSegmentation(bmpW, bmpH) { y, out ->
          bmp.getPixels(rowBuf, 0, bmpW, 0, y, bmpW, 1)
          System.arraycopy(rowBuf, 0, out, 0, bmpW)
        }
      }

      val analysisW = ANALYSIS_TARGET_WIDTH_PX
      val analysisH = ((ANALYSIS_TARGET_WIDTH_PX.toFloat() * bmpH.toFloat()) / bmpW.toFloat())
        .roundToInt()
        .coerceIn(100, 1200)

      val bucketHasInk = BooleanArray(analysisH)
      val bucketMinX = IntArray(analysisH) { analysisW }
      val bucketMaxX = IntArray(analysisH) { -1 }

      val chunkRows = 64
      val chunkBuf = IntArray(bmpW * chunkRows)
      var yStart = 0
      while (yStart < bmpH) {
        val rowsThisChunk = minOf(chunkRows, bmpH - yStart)
        bmp.getPixels(chunkBuf, 0, bmpW, 0, yStart, bmpW, rowsThisChunk)
        for (dy in 0 until rowsThisChunk) {
          val y = yStart + dy
          val ay = ((y.toLong() * analysisH.toLong()) / bmpH.toLong()).toInt().coerceIn(0, analysisH - 1)
          val rowOffset = dy * bmpW
          var rowMinSrcX = bmpW
          var rowMaxSrcX = -1
          for (x in 0 until bmpW) {
            val px = chunkBuf[rowOffset + x]
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            if (lum < NON_WHITE_GRAY_THRESHOLD) {
              if (x < rowMinSrcX) rowMinSrcX = x
              if (x > rowMaxSrcX) rowMaxSrcX = x
            }
          }
          if (rowMaxSrcX >= rowMinSrcX) {
            val axMin = ((rowMinSrcX.toLong() * analysisW.toLong()) / bmpW.toLong()).toInt().coerceIn(0, analysisW - 1)
            val axMax = ((rowMaxSrcX.toLong() * analysisW.toLong()) / bmpW.toLong()).toInt().coerceIn(axMin, analysisW - 1)
            bucketHasInk[ay] = true
            if (axMin < bucketMinX[ay]) bucketMinX[ay] = axMin
            if (axMax > bucketMaxX[ay]) bucketMaxX[ay] = axMax
          }
        }
        yStart += rowsThisChunk
      }

      return computePageSegmentation(analysisW, analysisH) { ay, out ->
        java.util.Arrays.fill(out, Color.WHITE)
        if (bucketHasInk[ay]) {
          out[bucketMinX[ay]] = Color.BLACK
          out[bucketMaxX[ay]] = Color.BLACK
        }
      }
    }
  }

  private val mutex = Mutex()
  private val visibleWaitingCount = AtomicInteger(0)
  private val renderExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "QabasatPdfRenderer").apply { isDaemon = true }
  }
  private val renderDispatcher = renderExecutor.asCoroutineDispatcher()
  private val loggedFirstPages = HashSet<String>()
  private val segmentationCache = ConcurrentHashMap<String, PdfPageSegmentation>()

  // High-res bitmap cache sized to ~1/8 of max heap
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
    isTwoPageMode: Boolean = false,
    scaleMultiplier: Float = 1f,
  ): PdfPageCacheKey = PdfPageCacheKey(
    filePath = pdfFile.absolutePath,
    fileLength = pdfFile.length(),
    lastModified = pdfFile.lastModified(),
    pageIndex = pageNumber - 1,
    widthPx = onScreenWidthPx,
    isTwoPageMode = isTwoPageMode,
    scaleMultiplierBits = scaleMultiplier.toRawBits(),
  )

  fun getCached(
    pdfFile: File,
    pageNumber: Int,
    onScreenWidthPx: Int,
    isTwoPageMode: Boolean = false,
    scaleMultiplier: Float = 1f,
  ): PdfRenderedPageBitmap? {
    if (onScreenWidthPx <= 0 || pageNumber < 1) return null
    val hit = pageBitmapCache.get(
      makeCacheKey(
        pdfFile = pdfFile,
        pageNumber = pageNumber,
        onScreenWidthPx = onScreenWidthPx,
        isTwoPageMode = isTwoPageMode,
        scaleMultiplier = scaleMultiplier,
      ),
    )
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
      throw IllegalStateException("تعذر فتح المصحف", e)
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
    isTwoPageMode: Boolean = false,
    scaleMultiplier: Float = 1f,
    isVisiblePriority: Boolean = true,
  ): PdfRenderedPageBitmap {
    val cacheKey = makeCacheKey(
      pdfFile = pdfFile,
      pageNumber = pageNumber,
      onScreenWidthPx = onScreenWidthPx,
      isTwoPageMode = isTwoPageMode,
      scaleMultiplier = scaleMultiplier,
    )
    pageBitmapCache.get(cacheKey)?.let { cached ->
      totalCachedHits.incrementAndGet()
      if (BuildConfig.DEBUG) {
        val mode = if (isTwoPageMode) "two_page" else "single"
        val stage = if (scaleMultiplier < 0.99f) "preview_${scaleMultiplier}x" else "full"
        Log.d(
          "PdfMushaf",
          "Page $pageNumber ($mode/$stage) cache HIT ${cached.croppedWidthPx}x${cached.croppedHeightPx}",
        )
      }
      return cached
    }

    if (isVisiblePriority) {
      visibleWaitingCount.incrementAndGet()
    }
    var visibleCountReleased = !isVisiblePriority
    try {
      if (!isVisiblePriority) {
        while (visibleWaitingCount.get() > 0) {
          coroutineContext.ensureActive()
          delay(12L)
        }
      }

      val t0 = SystemClock.elapsedRealtime()
      val rendered = withContext(renderDispatcher) {
        coroutineContext.ensureActive()
        mutex.withLock {
          if (!visibleCountReleased) {
            visibleWaitingCount.decrementAndGet()
            visibleCountReleased = true
          }

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
                "First opened mushaf page: mushaf=$mushafId page=$pageNumber page.width=${page.width} page.height=${page.height} screenW=$onScreenWidthPx twoPage=$isTwoPageMode",
              )
            }

            // Compute or reuse page ink segmentation on a fast ~236px wide bitmap (~80K px)
            val segmentation = segmentationCache[segKey] ?: run {
              val analysisPlan = computeCropScalePlan(
                pageWidthPt = page.width,
                pageHeightPt = page.height,
                onScreenWidthPx = ANALYSIS_TARGET_WIDTH_PX,
                cropLeftPt = cropLeftPt,
                cropRightPt = cropRightPt,
                isTwoPageMode = false,
                textBlockZoom = 1f,
                scaleMultiplier = 1f,
              )
              val analysisBmp = Bitmap.createBitmap(
                analysisPlan.croppedWidthPx,
                analysisPlan.croppedHeightPx,
                Bitmap.Config.ARGB_8888,
              )
              try {
                Canvas(analysisBmp).drawColor(Color.WHITE)
                val analysisMatrix = Matrix().apply {
                  setScale(analysisPlan.renderScale, analysisPlan.renderScale)
                  postTranslate(-analysisPlan.cropLeftPx.toFloat(), -analysisPlan.cropTopPx.toFloat())
                }
                page.render(analysisBmp, null, analysisMatrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                computePageSegmentationFromBitmap(analysisBmp).also { seg ->
                  segmentationCache[segKey] = seg
                }
              } finally {
                analysisBmp.recycle()
              }
            }

            val textBlockZoom = (0.97f / segmentation.widestLineWidthFraction.coerceAtLeast(0.1f))
              .coerceIn(1f, 2.4f)
            val plan = computeCropScalePlan(
              pageWidthPt = page.width,
              pageHeightPt = page.height,
              onScreenWidthPx = onScreenWidthPx,
              cropLeftPt = cropLeftPt,
              cropRightPt = cropRightPt,
              isTwoPageMode = isTwoPageMode,
              textBlockZoom = textBlockZoom,
              scaleMultiplier = scaleMultiplier,
            )

            // Render directly into cropped destination dimensions with Matrix translation (1 allocation)
            val croppedBmp = Bitmap.createBitmap(
              plan.croppedWidthPx,
              plan.croppedHeightPx,
              Bitmap.Config.ARGB_8888,
            )
            Canvas(croppedBmp).drawColor(Color.WHITE)

            val matrix = Matrix().apply {
              setScale(plan.renderScale, plan.renderScale)
              postTranslate(-plan.cropLeftPx.toFloat(), -plan.cropTopPx.toFloat())
            }
            page.render(croppedBmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

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
      }

      val elapsed = SystemClock.elapsedRealtime() - t0
      totalColdRenders.incrementAndGet()
      totalColdTimeMs.addAndGet(elapsed)
      if (BuildConfig.DEBUG) {
        val mode = if (isTwoPageMode) "two_page" else "single"
        val stage = if (scaleMultiplier < 0.99f) "preview_${scaleMultiplier}x" else "full"
        val pxCount = rendered.croppedWidthPx.toLong() * rendered.croppedHeightPx.toLong()
        Log.d(
          "PdfMushaf",
          "Page $pageNumber ($mode/$stage) COLD rendered in ${elapsed}ms -> ${rendered.croppedWidthPx}x${rendered.croppedHeightPx} (${pxCount}px) | ${getPerformanceStats()}",
        )
      }

      return rendered
    } finally {
      if (!visibleCountReleased) {
        visibleWaitingCount.decrementAndGet()
      }
    }
  }

  suspend fun closeIfMatches(mushafId: String) {
    withContext(renderDispatcher) {
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
  }

  fun closeBlocking() {
    pageBitmapCache.evictAll()
    segmentationCache.clear()
    runCatching {
      renderExecutor.execute {
        closeCurrentLocked()
      }
      renderExecutor.shutdown()
    }
  }
}
