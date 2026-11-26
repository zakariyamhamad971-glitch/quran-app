package com.example.data.quran

import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import androidx.compose.runtime.Immutable
import com.example.BuildConfig
import com.example.ui.components.MushafPageBitmapRenderer
import com.example.ui.components.PageRenderSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Immutable
data class GlobalFontSizingProgress(
  val mushafId: String,
  val completedPages: Int,
  val totalPages: Int,
)

data class GlobalFontSizingResult(
  val introSp: Float,
  val bodySp: Float,
  val introLimitingPage: Int,
  val bodyLimitingPage: Int,
  val allPagesMeasured: Boolean,
  val measuredPagesCount: Int,
)

class GlobalMushafFontSizer(
  private val quranRepository: QuranRepository,
  private val fontManager: PageFontManager,
  private val renderer: MushafPageBitmapRenderer,
) {
  companion object {
    private const val LOG_TAG = "MushafFontSizing"

    fun buildCacheKey(
      edition: MushafEdition,
      fontVersion: String,
      layoutVersion: Int,
      safeWidthPx: Int,
      bodySlotHeightPx: Float,
      spec: PageRenderSpec,
    ): String {
      return buildString(96) {
        append(edition.name)
        append('|')
        append(fontVersion)
        append("|v")
        append(layoutVersion)
        append("|w")
        append(safeWidthPx)
        append("|sh")
        append(bodySlotHeightPx.roundToInt())
        append("|d")
        append(spec.density.toRawBits())
        append("|fs")
        append(spec.fontScale.toRawBits())
        append("|2p=")
        append(if (spec.isTwoPageMode) 1 else 0)
      }
    }
  }

  suspend fun computeGlobalFontSizes(
    spec: PageRenderSpec,
    usesTagFallbackTajweed: Boolean,
    fontVersion: String,
    pageRangeIntro: IntRange = 1..2,
    pageRangeBody: IntRange = 3..604,
    onProgress: suspend (completedPages: Int, totalPages: Int) -> Unit = { _, _ -> },
  ): GlobalFontSizingResult = withContext(Dispatchers.Default) {
    val metrics = renderer.computeTextBodyMetrics(spec)
    val edition = spec.edition
    val fontEdition = if (usesTagFallbackTajweed) MushafEdition.MADINAH_1421 else edition
    val renderVectorTajweedMarkers = edition == MushafEdition.TAJWEED_V4
    val goldInt = 0xFFC29038.toInt()
    val inkInt = Color.BLACK

    val basmalahTypeface = if (usesTagFallbackTajweed) {
      null
    } else {
      fontManager.loadPageFontForMeasurement(1, fontEdition, useWhiteInk = false)
    }
    val hasBasmalahTypeface = basmalahTypeface != null
    val lineTextPaint = renderer.createLineTextPaint(null, usesTagFallbackTajweed, inkInt = inkInt)
    val basmalahPaint = renderer.createBasmalahPaint(basmalahTypeface, inkInt = inkInt)
    val rosetteNumPaint = renderer.createRosetteNumPaint(inkInt = inkInt)
    val tokenBounds = Rect()

    val totalPages = (pageRangeIntro.count() + pageRangeBody.count()).coerceAtLeast(1)
    var processedCount = 0
    var measuredCount = 0
    var allMeasured = true

    var introSp = renderer.maxCandidateFontSp(metrics.introSlotHeightPx, metrics.density, metrics.fontScale)
    var introLimitingPage = 0

    for (page in pageRangeIntro) {
      currentCoroutineContext().ensureActive()
      val pageData = quranRepository.getPageForMeasurement(
        pageNumber = page,
        edition = edition,
        includeTajweedFallback = usesTagFallbackTajweed,
      ).getOrNull()
      val pageTypeface = if (usesTagFallbackTajweed) {
        null
      } else {
        fontManager.loadPageFontForMeasurement(page, fontEdition, useWhiteInk = false)
      }

      if (pageData != null && (usesTagFallbackTajweed || pageTypeface != null)) {
        measuredCount++
        lineTextPaint.typeface = renderer.resolveActiveTypeface(pageTypeface, usesTagFallbackTajweed)
        val displayedLines = renderer.displayedLinesForPage(pageData)
        val fits = renderer.doesPageFitFontSp(
          displayedLines = displayedLines,
          candidateSp = introSp,
          paint = lineTextPaint,
          basmalahPaint = basmalahPaint,
          hasBasmalahTypeface = hasBasmalahTypeface,
          rosetteNumPaint = rosetteNumPaint,
          renderVectorTajweedMarkers = renderVectorTajweedMarkers,
          usesTagFallbackTajweed = usesTagFallbackTajweed,
          safeWidthPx = metrics.safeWidthPx,
          slotHeightPx = metrics.introSlotHeightPx,
          density = metrics.density,
          fontScale = metrics.fontScale,
          tokenBounds = tokenBounds,
        )
        if (!fits || introLimitingPage == 0) {
          val nextSp = renderer.computeUniformPageFontSp(
            displayedLines = displayedLines,
            paint = lineTextPaint,
            basmalahPaint = basmalahPaint,
            hasBasmalahTypeface = hasBasmalahTypeface,
            rosetteNumPaint = rosetteNumPaint,
            renderVectorTajweedMarkers = renderVectorTajweedMarkers,
            usesTagFallbackTajweed = usesTagFallbackTajweed,
            goldInt = goldInt,
            inkInt = inkInt,
            effectiveDark = false,
            safeWidthPx = metrics.safeWidthPx,
            slotHeightPx = metrics.introSlotHeightPx,
            density = metrics.density,
            fontScale = metrics.fontScale,
            upperBoundSp = introSp,
            tokenBounds = tokenBounds,
          )
          if (introLimitingPage == 0 || nextSp < introSp - 0.0001f) {
            introSp = nextSp
            introLimitingPage = page
          }
        }
      } else {
        allMeasured = false
      }

      processedCount++
      onProgress(processedCount, totalPages)
    }

    var bodySp = renderer.maxCandidateFontSp(metrics.bodySlotHeightPx, metrics.density, metrics.fontScale)
    var bodyLimitingPage = 0

    for (page in pageRangeBody) {
      currentCoroutineContext().ensureActive()
      val pageData = quranRepository.getPageForMeasurement(
        pageNumber = page,
        edition = edition,
        includeTajweedFallback = usesTagFallbackTajweed,
      ).getOrNull()
      val pageTypeface = if (usesTagFallbackTajweed) {
        null
      } else {
        fontManager.loadPageFontForMeasurement(page, fontEdition, useWhiteInk = false)
      }

      if (pageData != null && (usesTagFallbackTajweed || pageTypeface != null)) {
        measuredCount++
        lineTextPaint.typeface = renderer.resolveActiveTypeface(pageTypeface, usesTagFallbackTajweed)
        val displayedLines = renderer.displayedLinesForPage(pageData)
        val fits = renderer.doesPageFitFontSp(
          displayedLines = displayedLines,
          candidateSp = bodySp,
          paint = lineTextPaint,
          basmalahPaint = basmalahPaint,
          hasBasmalahTypeface = hasBasmalahTypeface,
          rosetteNumPaint = rosetteNumPaint,
          renderVectorTajweedMarkers = renderVectorTajweedMarkers,
          usesTagFallbackTajweed = usesTagFallbackTajweed,
          safeWidthPx = metrics.safeWidthPx,
          slotHeightPx = metrics.bodySlotHeightPx,
          density = metrics.density,
          fontScale = metrics.fontScale,
          tokenBounds = tokenBounds,
        )
        if (!fits || bodyLimitingPage == 0) {
          val nextSp = renderer.computeUniformPageFontSp(
            displayedLines = displayedLines,
            paint = lineTextPaint,
            basmalahPaint = basmalahPaint,
            hasBasmalahTypeface = hasBasmalahTypeface,
            rosetteNumPaint = rosetteNumPaint,
            renderVectorTajweedMarkers = renderVectorTajweedMarkers,
            usesTagFallbackTajweed = usesTagFallbackTajweed,
            goldInt = goldInt,
            inkInt = inkInt,
            effectiveDark = false,
            safeWidthPx = metrics.safeWidthPx,
            slotHeightPx = metrics.bodySlotHeightPx,
            density = metrics.density,
            fontScale = metrics.fontScale,
            upperBoundSp = bodySp,
            tokenBounds = tokenBounds,
          )
          if (bodyLimitingPage == 0 || nextSp < bodySp - 0.0001f) {
            bodySp = nextSp
            bodyLimitingPage = page
          }
        }
      } else {
        allMeasured = false
      }

      processedCount++
      if (processedCount % 16 == 0 || processedCount == totalPages) {
        onProgress(processedCount, totalPages)
      }
    }

    if (BuildConfig.DEBUG) {
      val msg = "Global mushaf sizes edition=$edition fontVersion=$fontVersion " +
        "twoPage=${spec.isTwoPageMode} safeWidthPx=${metrics.safeWidthPx} " +
        "introSp=$introSp (maxPage=$introLimitingPage) " +
        "bodySp=$bodySp (maxPage=$bodyLimitingPage) " +
        "measured=$measuredCount/$totalPages allPagesMeasured=$allMeasured"
      runCatching { Log.d(LOG_TAG, msg) }
        .onFailure { println("$LOG_TAG: $msg") }
    }

    GlobalFontSizingResult(
      introSp = introSp,
      bodySp = bodySp,
      introLimitingPage = introLimitingPage,
      bodyLimitingPage = bodyLimitingPage,
      allPagesMeasured = allMeasured && measuredCount > 0,
      measuredPagesCount = measuredCount,
    )
  }
}
