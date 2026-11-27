package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import com.example.BuildConfig
import com.example.R
import com.example.data.pdf.PdfRenderedPageBitmap
import com.example.data.prefs.BackgroundTheme
import com.example.data.quran.MushafEdition
import com.example.data.quran.MushafLine
import com.example.data.quran.MushafPageData
import com.example.data.quran.MushafWord
import com.example.data.quran.PdfPageHeaderMeta
import com.example.data.quran.SurahMeta
import com.example.data.quran.TajweedTagParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@Immutable
data class PageRenderSpec(
  val widthPx: Int = 0,
  val heightPx: Int = 0,
  val density: Float = 0f,
  val fontScale: Float = 1f,
  val edition: MushafEdition = MushafEdition.MADINAH_1421,
  val isDarkTheme: Boolean = false,
  val backgroundTheme: BackgroundTheme = BackgroundTheme.WHITE,
  val isTwoPageMode: Boolean = false,
  val pdfMushafId: String? = null,
  val pdfFileKey: String? = null,
  val globalIntroFontSp: Float? = null,
  val globalBodyFontSp: Float? = null,
) {
  val isValid: Boolean
    get() = widthPx > 32 && heightPx > 32 && density > 0f

  val effectiveDark: Boolean
    get() = isDarkTheme || backgroundTheme == BackgroundTheme.BLACK

  val isPdfMode: Boolean
    get() = !pdfMushafId.isNullOrBlank()
}

data class PageBitmapKey(
  val pageNumber: Int,
  val widthPx: Int,
  val heightPx: Int,
  val densityBits: Int,
  val fontScaleBits: Int,
  val edition: MushafEdition,
  val effectiveDark: Boolean,
  val isTwoPageMode: Boolean,
  val pdfMushafId: String? = null,
  val pdfFileKey: String? = null,
  val globalIntroFontSpBits: Int = 0,
  val globalBodyFontSpBits: Int = 0,
  val layoutVersion: Int = com.example.data.quran.QuranRepository.LAYOUT_CACHE_VERSION,
  val isPreview: Boolean = false,
  val usesTagFallbackTajweed: Boolean = false,
)

data class TextBodyLayoutMetrics(
  val contentWidth: Float,
  val safeWidthPx: Int,
  val introSlotHeightPx: Float,
  val bodySlotHeightPx: Float,
  val density: Float,
  val fontScale: Float,
)

@Immutable
class WordHitBoxes(
  val count: Int,
  val lefts: FloatArray,
  val tops: FloatArray,
  val rights: FloatArray,
  val bottoms: FloatArray,
  val verseKeys: Array<String>,
  val codeV2Lines: Array<String>,
)

@Immutable
data class PdfLineDestinationMetrics(
  val srcLeftPx: Int,
  val srcTopPx: Int,
  val srcWidthPx: Int,
  val srcHeightPx: Int,
  val dstLeftPx: Int,
  val dstTopPx: Int,
  val dstWidthPx: Int,
  val dstHeightPx: Int,
)

@Immutable
data class PdfPageOverlayData(
  val pdfBitmap: ImageBitmap,
  val bmpWidthPx: Int,
  val bmpHeightPx: Int,
  val topBandEndY: Int,
  val bottomBandStartY: Int,
  val textSrcLeft: Int,
  val textSrcTop: Int,
  val textSrcRight: Int,
  val textSrcBottom: Int,
  val bodyLeftPx: Float,
  val bodyTopPx: Float,
  val bodyRightPx: Float,
  val bodyBottomPx: Float,
  val topSurah: SurahMeta?,
  val segmentation: com.example.data.pdf.PdfPageSegmentation,
)

data class PdfPageLayoutMetrics(
  val topBandDstLeft: Int,
  val topBandDstTop: Int,
  val topBandDstWidth: Int,
  val topBandDstHeight: Int,
  val bottomBandDstLeft: Int,
  val bottomBandDstTop: Int,
  val bottomBandDstWidth: Int,
  val bottomBandDstHeight: Int,
  val textSrcLeftPx: Int,
  val textSrcTopPx: Int,
  val textSrcWidthPx: Int,
  val textSrcHeightPx: Int,
  val textDstLeft: Int,
  val textDstTop: Int,
  val textDstWidth: Int,
  val textDstHeight: Int,
  val bannerLeft: Float,
  val bannerTop: Float,
  val bannerWidth: Float,
  val bannerHeight: Float,
  val appliedZoomRelativeToNatural: Float,
  val isFewLines: Boolean = false,
  val lineLayouts: List<PdfLineDestinationMetrics> = emptyList(),
)

fun computePdfPageLayout(
  frameLeft: Float,
  frameTop: Float,
  frameRight: Float,
  frameBottom: Float,
  bmpWidthPx: Int,
  bmpHeightPx: Int,
  segmentation: com.example.data.pdf.PdfPageSegmentation,
  hasTopSurahBanner: Boolean,
  densityPxPerDp: Float,
  surahBannerAspectRatio: Float = SurahHeaderOrnamentRenderer.HEADER_GLYPH_ASPECT_RATIO,
): PdfPageLayoutMetrics {
  val frameW = (frameRight - frameLeft).coerceAtLeast(1f)
  val frameH = (frameBottom - frameTop).coerceAtLeast(1f)
  val safeBmpWInt = bmpWidthPx.coerceAtLeast(2)
  val safeBmpHInt = bmpHeightPx.coerceAtLeast(2)
  val safeBmpW = safeBmpWInt.toFloat()
  val safeBmpH = safeBmpHInt.toFloat()

  // 1. Label band pinned at top at natural scale (fit to width)
  val topBandDstLeft = frameLeft.roundToInt()
  val topBandDstTop = frameTop.roundToInt()
  val topBandDstWidth = frameW.roundToInt().coerceAtLeast(1)
  val topBandDstHeight = if (segmentation.hasLabelBand && segmentation.labelCutFraction > 0f) {
    val naturalLabelH = frameW * (segmentation.labelCutFraction * safeBmpH / safeBmpW)
    minOf(naturalLabelH, frameH * 0.12f).roundToInt().coerceAtLeast(1)
  } else {
    0
  }

  // 2. Legend band pinned at bottom at natural scale (fit to width), complete and in one piece
  val bottomBandDstLeft = frameLeft.roundToInt()
  val bottomBandDstWidth = frameW.roundToInt().coerceAtLeast(1)
  val bottomBandDstHeight = if (segmentation.hasLegendBand && segmentation.legendCutFraction < 1f) {
    val naturalLegendH = frameW * ((1f - segmentation.legendCutFraction) * safeBmpH / safeBmpW)
    minOf(naturalLegendH, frameH * 0.18f).roundToInt().coerceAtLeast(1)
  } else {
    0
  }
  val bottomBandDstTop = (frameBottom.roundToInt() - bottomBandDstHeight)
    .coerceAtLeast(topBandDstTop + topBandDstHeight)

  val midTop = (topBandDstTop + topBandDstHeight).toFloat()
  val midBottom = bottomBandDstTop.toFloat()
  val midH = (midBottom - midTop).coerceAtLeast(1f)

  // Empty space rule: content starts right under the label band (or top of frame when absent)
  // with only a fixed ~2% top margin.
  val topContentMarginPx = (frameH * 0.02f).coerceAtLeast(2f)
  val contentStartTop = (midTop + topContentMarginPx).coerceAtMost(midBottom - 1f)

  // 3. Surah banner sits at contentStartTop directly below the label band + 2% margin
  val bannerGapPx = (2.5f * densityPxPerDp).coerceAtLeast(2f)
  val bannerIdealH = frameW / surahBannerAspectRatio.coerceAtLeast(1f)
  val bannerH = if (hasTopSurahBanner) minOf(bannerIdealH, midH * 0.16f).coerceAtLeast(1f) else 0f
  val bannerTop = if (hasTopSurahBanner) contentStartTop else 0f
  val bannerLeft = frameLeft

  val textAvailTop = if (hasTopSurahBanner) {
    (bannerTop + bannerH + bannerGapPx).coerceAtMost(midBottom - 1f)
  } else {
    contentStartTop
  }
  val textAvailH = (midBottom - textAvailTop).coerceAtLeast(1f)

  // 4. Text scaling: widest line fills ~97% of frame width (zoom capped at 2.4x, >= 1.0x)
  val widestLineW = (segmentation.widestLineWidthFraction * safeBmpW).coerceAtLeast(1f)
  val unzoomedScale = frameW / safeBmpW
  val fill97Scale = (0.97f * frameW) / widestLineW
  val targetScale = fill97Scale.coerceIn(unzoomedScale, unzoomedScale * 2.4f)

  val srcLeft = (segmentation.textMinXFraction * safeBmpW).roundToInt().coerceIn(0, safeBmpWInt - 1)
  val srcRight = (segmentation.textMaxXFraction * safeBmpW).roundToInt().coerceIn(srcLeft + 1, safeBmpWInt)
  val blockSrcW = (srcRight - srcLeft).coerceAtLeast(1)

  val labelCutPx = (segmentation.labelCutFraction * safeBmpH).roundToInt().coerceIn(0, safeBmpHInt - 1)
  val legendCutPx = (segmentation.legendCutFraction * safeBmpH).roundToInt().coerceIn(labelCutPx + 1, safeBmpHInt)

  val isFewLines = segmentation.isFewLines && segmentation.lines.isNotEmpty()

  if (isFewLines) {
    val lineCount = segmentation.lines.size
    data class RawStrip(val srcTop: Int, val srcH: Int)
    val rawStrips = segmentation.lines.map { strip ->
      val sTop = (strip.srcTopFraction * safeBmpH).roundToInt().coerceIn(labelCutPx, safeBmpHInt - 1)
      val sBot = (strip.srcBottomFraction * safeBmpH).roundToInt().coerceIn(sTop + 1, legendCutPx)
      RawStrip(srcTop = sTop, srcH = (sBot - sTop).coerceAtLeast(1))
    }

    val rawTotalH = rawStrips.sumOf { it.srcH.toDouble() }.toFloat().coerceAtLeast(1f)
    val maxScaleToFitWidth = (frameW / blockSrcW.toFloat()).coerceAtLeast(0.001f)
    val desiredLineScale = minOf(targetScale, maxScaleToFitWidth)
    val maxScaleToFitHeight = if (lineCount > 1) {
      (textAvailH * 0.94f) / rawTotalH
    } else {
      textAvailH / rawTotalH
    }
    val lineScale = minOf(desiredLineScale, maxScaleToFitHeight).coerceAtLeast(0.001f)

    val dstW = (blockSrcW * lineScale).roundToInt().coerceIn(1, frameW.roundToInt())
    val dstLeft = (frameLeft + (frameW - dstW) * 0.5f).roundToInt()
    val dstHeights = rawStrips.map { (it.srcH * lineScale).roundToInt().coerceAtLeast(1) }
    val totalLinesDstH = dstHeights.sum().toFloat()
    val equalGap = if (lineCount > 1) {
      ((textAvailH - totalLinesDstH) / (lineCount - 1).toFloat()).coerceAtLeast(0f)
    } else {
      0f
    }

    var cursorY = textAvailTop
    val lineMetrics = rawStrips.mapIndexed { idx, raw ->
      val dstH = dstHeights[idx]
      val dstTop = cursorY.roundToInt()
      cursorY += dstH + equalGap
      PdfLineDestinationMetrics(
        srcLeftPx = srcLeft,
        srcTopPx = raw.srcTop,
        srcWidthPx = blockSrcW,
        srcHeightPx = raw.srcH,
        dstLeftPx = dstLeft,
        dstTopPx = dstTop,
        dstWidthPx = dstW,
        dstHeightPx = dstH,
      )
    }

    return PdfPageLayoutMetrics(
      topBandDstLeft = topBandDstLeft,
      topBandDstTop = topBandDstTop,
      topBandDstWidth = topBandDstWidth,
      topBandDstHeight = topBandDstHeight,
      bottomBandDstLeft = bottomBandDstLeft,
      bottomBandDstTop = bottomBandDstTop,
      bottomBandDstWidth = bottomBandDstWidth,
      bottomBandDstHeight = bottomBandDstHeight,
      textSrcLeftPx = srcLeft,
      textSrcTopPx = labelCutPx,
      textSrcWidthPx = blockSrcW,
      textSrcHeightPx = (legendCutPx - labelCutPx).coerceAtLeast(1),
      textDstLeft = frameLeft.roundToInt(),
      textDstTop = textAvailTop.roundToInt(),
      textDstWidth = frameW.roundToInt(),
      textDstHeight = textAvailH.roundToInt(),
      bannerLeft = bannerLeft,
      bannerTop = bannerTop,
      bannerWidth = if (hasTopSurahBanner) frameW else 0f,
      bannerHeight = bannerH,
      appliedZoomRelativeToNatural = lineScale / unzoomedScale,
      isFewLines = true,
      lineLayouts = lineMetrics,
    )
  }

  // Normal full page or top-aligned fallback: tighten vertical crop strictly to ink bounds inside [labelCutPx, legendCutPx]
  val guardPx = (safeBmpH * 0.005f).roundToInt().coerceIn(2, 12)
  val inkMinY = (segmentation.textMinYFraction * safeBmpH).roundToInt()
  val inkMaxY = (segmentation.textMaxYFraction * safeBmpH).roundToInt()
  val srcTop = if (inkMaxY >= inkMinY) {
    (inkMinY - guardPx).coerceIn(labelCutPx, (legendCutPx - 1).coerceAtLeast(labelCutPx))
  } else {
    labelCutPx
  }
  val srcBot = if (inkMaxY >= inkMinY) {
    (inkMaxY + guardPx + 1).coerceIn(srcTop + 1, legendCutPx)
  } else {
    legendCutPx
  }
  val srcH = (srcBot - srcTop).coerceAtLeast(1)

  val maxScaleH = textAvailH / srcH.toFloat()
  val maxScaleW = frameW / blockSrcW.toFloat()
  val finalScale = minOf(targetScale, maxScaleH, maxScaleW).coerceAtLeast(0.001f)
  val textDstW = (blockSrcW * finalScale).roundToInt().coerceIn(1, frameW.roundToInt())
  val textDstH = (srcH * finalScale).roundToInt().coerceIn(1, textAvailH.roundToInt())
  val textDstLeft = (frameLeft + (frameW - textDstW) * 0.5f).roundToInt()
  // Always top-aligned under label band / surah banner (never vertically centered with blank gap above)
  val textDstTop = textAvailTop.roundToInt()

  return PdfPageLayoutMetrics(
    topBandDstLeft = topBandDstLeft,
    topBandDstTop = topBandDstTop,
    topBandDstWidth = topBandDstWidth,
    topBandDstHeight = topBandDstHeight,
    bottomBandDstLeft = bottomBandDstLeft,
    bottomBandDstTop = bottomBandDstTop,
    bottomBandDstWidth = bottomBandDstWidth,
    bottomBandDstHeight = bottomBandDstHeight,
    textSrcLeftPx = srcLeft,
    textSrcTopPx = srcTop,
    textSrcWidthPx = blockSrcW,
    textSrcHeightPx = srcH,
    textDstLeft = textDstLeft,
    textDstTop = textDstTop,
    textDstWidth = textDstW,
    textDstHeight = textDstH,
    bannerLeft = bannerLeft,
    bannerTop = bannerTop,
    bannerWidth = if (hasTopSurahBanner) frameW else 0f,
    bannerHeight = bannerH,
    appliedZoomRelativeToNatural = finalScale / unzoomedScale,
    isFewLines = false,
    lineLayouts = emptyList(),
  )
}

@Immutable
data class RenderedMushafPage(
  val pageNumber: Int,
  val widthPx: Int,
  val heightPx: Int,
  val byteCount: Int,
  val imageBitmap: ImageBitmap,
  val verseHighlightRects: Map<String, FloatArray>,
  val rosetteSlotsByVerse: Map<String, FloatArray>,
  val wordHitBoxes: WordHitBoxes,
  val pdfContent: PdfPageOverlayData? = null,
  val isPreview: Boolean = false,
  internal val pageFontIdentity: Int = 0,
  internal val basmalahFontIdentity: Int = 0,
  internal val usesTagFallbackTajweed: Boolean = false,
  internal val endMarkerFontIdentity: Int = 0,
)

class MushafPageBitmapRenderer(
  private val context: Context,
) {
  companion object {
    private const val MIN_CACHE_BYTES = 16 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 96 * 1024 * 1024
    private const val DEFAULT_CACHE_BYTES = 48 * 1024 * 1024
    private const val OVERFLOW_LOG_TAG = "MushafLineOverflow"
    private val DEBUG_OVERFLOW_PAGES = setOf(1, 2, 7, 8, 50, 598)
    private const val BODY_PAD_H_DP = 5.5f
    private const val BODY_PAD_V_DP = 4.5f

    private fun computeSafetyMarginPx(contentWidth: Float, density: Float): Float =
      maxOf(1.5f * density, contentWidth * 0.004f)

    private val arabicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

    private fun toArabicIndicDigits(number: Int): String {
      val raw = number.toString()
      return buildString(raw.length) {
        for (i in raw.indices) {
          val ch = raw[i]
          append(if (ch in '0'..'9') arabicDigits[ch - '0'] else ch)
        }
      }
    }

    private fun withAlpha(argb: Int, alpha: Float): Int {
      val a = (alpha.coerceIn(0f, 1f) * 255f).roundToInt().coerceIn(0, 255)
      return (a shl 24) or (argb and 0x00FFFFFF)
    }
  }

  private val bitmapCache = object : LruCache<PageBitmapKey, RenderedMushafPage>(DEFAULT_CACHE_BYTES) {
    override fun sizeOf(key: PageBitmapKey, value: RenderedMushafPage): Int =
      value.byteCount.coerceAtLeast(1)
  }

  @Volatile
  private var lastConfiguredCacheBytes: Int = DEFAULT_CACHE_BYTES

  @Volatile
  private var cachedAmiriNormal: Typeface? = null
  @Volatile
  private var cachedAmiriBold: Typeface? = null
  @Volatile
  private var cachedIbmNormal: Typeface? = null
  @Volatile
  private var cachedIbmMedium: Typeface? = null
  @Volatile
  private var cachedSurahNameV4: Typeface? = null
  @Volatile
  private var surahNameV4Attempted: Boolean = false
  @Volatile
  private var cachedQuranCommon: Typeface? = null
  @Volatile
  private var quranCommonAttempted: Boolean = false

  private fun ensureUiFontsLoaded() {
    if (cachedAmiriNormal != null && cachedIbmNormal != null && surahNameV4Attempted && quranCommonAttempted) return
    synchronized(this) {
      if (cachedAmiriNormal == null) {
        val amiri = runCatching { ResourcesCompat.getFont(context, R.font.amiri) }.getOrNull()
          ?: Typeface.SERIF
        cachedAmiriNormal = amiri
        cachedAmiriBold = Typeface.create(amiri, Typeface.BOLD)
      }
      if (cachedIbmNormal == null) {
        val ibm = runCatching { ResourcesCompat.getFont(context, R.font.ibm_plex_sans_arabic) }.getOrNull()
          ?: Typeface.SANS_SERIF
        cachedIbmNormal = ibm
        cachedIbmMedium = Typeface.create(ibm, Typeface.BOLD)
      }
      if (!surahNameV4Attempted) {
        cachedSurahNameV4 = runCatching { ResourcesCompat.getFont(context, R.font.surah_name_v4) }.getOrNull()
        surahNameV4Attempted = true
      }
      if (!quranCommonAttempted) {
        cachedQuranCommon = runCatching { ResourcesCompat.getFont(context, R.font.quran_common) }.getOrNull()
        quranCommonAttempted = true
      }
    }
  }

  private fun makeKey(
    pageNumber: Int,
    spec: PageRenderSpec,
    isPreview: Boolean = false,
    usesTagFallbackTajweed: Boolean = false,
  ): PageBitmapKey =
    PageBitmapKey(
      pageNumber = pageNumber,
      widthPx = spec.widthPx,
      heightPx = spec.heightPx,
      densityBits = spec.density.toRawBits(),
      fontScaleBits = spec.fontScale.toRawBits(),
      edition = spec.edition,
      effectiveDark = spec.effectiveDark,
      isTwoPageMode = spec.isTwoPageMode,
      pdfMushafId = spec.pdfMushafId,
      pdfFileKey = if (spec.isPdfMode) spec.pdfFileKey else null,
      globalIntroFontSpBits = if (spec.isPdfMode) 0 else (spec.globalIntroFontSp?.toRawBits() ?: 0),
      globalBodyFontSpBits = if (spec.isPdfMode) 0 else (spec.globalBodyFontSp?.toRawBits() ?: 0),
      isPreview = isPreview,
      usesTagFallbackTajweed = usesTagFallbackTajweed,
    )

  private fun ensureCacheCapacityFor(spec: PageRenderSpec) {
    val pageBytes = spec.widthPx.toLong() * spec.heightPx.toLong() * 4L
    val eighthHeap = (Runtime.getRuntime().maxMemory() / 8L).coerceAtMost(MAX_CACHE_BYTES.toLong())
    val desired = (pageBytes * 8L).coerceIn(MIN_CACHE_BYTES.toLong(), eighthHeap.coerceAtLeast(MIN_CACHE_BYTES.toLong())).toInt()
    if (desired != lastConfiguredCacheBytes) {
      synchronized(this) {
        if (desired != lastConfiguredCacheBytes) {
          bitmapCache.resize(desired)
          lastConfiguredCacheBytes = desired
        }
      }
    }
  }

  fun getCached(pageNumber: Int, spec: PageRenderSpec, isPreview: Boolean = false): RenderedMushafPage? {
    if (!spec.isValid) return null
    return bitmapCache.get(makeKey(pageNumber, spec, isPreview = isPreview))
  }

  fun clearAll() {
    bitmapCache.evictAll()
  }

  fun computePdfBodySizePx(spec: PageRenderSpec, pageNumber: Int = 1): Pair<Int, Int> {
    ensureUiFontsLoaded()
    val w = spec.widthPx.coerceAtLeast(1)
    val h = spec.heightPx.coerceAtLeast(1)
    val d = spec.density
    val fs = spec.fontScale
    fun dp(v: Float): Float = v * d
    fun sp(v: Float): Float = v * d * fs

    val padH = dp(BODY_PAD_H_DP)
    val padV = dp(BODY_PAD_V_DP)
    val contentTop = padV
    val contentBottom = h - padV
    val contentWidth = (w - padH * 2f).coerceAtLeast(1f)

    val amiriNormal = cachedAmiriNormal ?: Typeface.SERIF
    val amiriBold = cachedAmiriBold ?: amiriNormal
    val titleFm = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = amiriNormal
      textSize = sp(13f * 0.60f)
    }.fontMetrics
    val pillFm = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = amiriNormal
      textSize = sp(11f * 0.60f)
    }.fontMetrics
    val topHeaderHeight = maxOf(
      (titleFm.descent - titleFm.ascent) + dp(2f),
      (pillFm.descent - pillFm.ascent) + dp(4f),
    )

    val footerBadgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = amiriBold
      textSize = minOf(sp(8.2f), dp(10.5f))
    }
    val pageNumText = toArabicIndicDigits(pageNumber)
    val pageNumBounds = Rect()
    footerBadgePaint.getTextBounds(pageNumText, 0, pageNumText.length, pageNumBounds)
    val footerBadgeFm = footerBadgePaint.fontMetrics
    val pageNumInkH = if (pageNumBounds.height() > 0) {
      pageNumBounds.height().toFloat()
    } else {
      (footerBadgeFm.descent - footerBadgeFm.ascent) * 0.65f
    }
    val badgeH = maxOf(dp(13f), pageNumInkH + dp(3.5f) * 2f)
    val footerHeight = maxOf(dp(18f), badgeH + dp(2f))
    val footerTop = contentBottom - footerHeight

    val contentLeft = padH
    val contentRight = w - padH
    val bodyTop = contentTop + topHeaderHeight + dp(1f)
    val bodyBottom = footerTop - dp(2f)
    val bodyLeftPx = ceil(contentLeft.toDouble()).toInt().coerceIn(0, w - 1)
    val bodyRightPx = kotlin.math.floor(contentRight.toDouble()).toInt().coerceIn(bodyLeftPx + 1, w)
    val bodyTopPx = ceil(bodyTop.toDouble()).toInt().coerceIn(0, h - 1)
    val bodyBottomPx = kotlin.math.floor(bodyBottom.toDouble()).toInt().coerceIn(bodyTopPx + 1, h)
    val bodyWidthPx = (bodyRightPx - bodyLeftPx).coerceAtLeast(1)
    val bodyHeightPx = (bodyBottomPx - bodyTopPx).coerceAtLeast(1)
    return bodyWidthPx to bodyHeightPx
  }

  fun computeTextBodyMetrics(spec: PageRenderSpec): TextBodyLayoutMetrics {
    val (_, bodyHeightInt) = computePdfBodySizePx(spec, pageNumber = 3)
    val bodyHeight = bodyHeightInt.toFloat().coerceAtLeast(1f)
    val d = spec.density
    val w = spec.widthPx.coerceAtLeast(1)
    val contentWidth = (w - (BODY_PAD_H_DP * d) * 2f).coerceAtLeast(1f)
    val safetyMarginPx = computeSafetyMarginPx(contentWidth, d)
    val safeContentWidth = (contentWidth - safetyMarginPx * 2f).coerceAtLeast(1f)
    val safeWidthPx = safeContentWidth.roundToInt().coerceAtLeast(1)
    val bodySlotHeightPx = bodyHeight / 15f
    val introSlotHeightPx = minOf(bodyHeight / 8f, bodySlotHeightPx * 1.24f)
    return TextBodyLayoutMetrics(
      contentWidth = contentWidth,
      safeWidthPx = safeWidthPx,
      introSlotHeightPx = introSlotHeightPx,
      bodySlotHeightPx = bodySlotHeightPx,
      density = d,
      fontScale = spec.fontScale,
    )
  }

  internal fun createLineTextPaint(
    pageTypeface: Typeface?,
    usesTagFallbackTajweed: Boolean,
    inkInt: Int = android.graphics.Color.BLACK,
  ): TextPaint {
    ensureUiFontsLoaded()
    val amiriNormal = cachedAmiriNormal ?: Typeface.SERIF
    val activeTypeface = when {
      usesTagFallbackTajweed -> amiriNormal
      pageTypeface != null -> pageTypeface
      else -> amiriNormal
    }
    return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = activeTypeface
      color = inkInt
    }
  }

  internal fun resolveActiveTypeface(
    pageTypeface: Typeface?,
    usesTagFallbackTajweed: Boolean,
  ): Typeface {
    ensureUiFontsLoaded()
    val amiriNormal = cachedAmiriNormal ?: Typeface.SERIF
    return when {
      usesTagFallbackTajweed -> amiriNormal
      pageTypeface != null -> pageTypeface
      else -> amiriNormal
    }
  }

  internal fun createBasmalahPaint(
    basmalahTypeface: Typeface?,
    inkInt: Int = android.graphics.Color.BLACK,
  ): TextPaint {
    ensureUiFontsLoaded()
    val amiriNormal = cachedAmiriNormal ?: Typeface.SERIF
    return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = basmalahTypeface ?: amiriNormal
      color = inkInt
    }
  }

  internal fun createRosetteNumPaint(
    inkInt: Int = android.graphics.Color.BLACK,
  ): Paint {
    ensureUiFontsLoaded()
    val amiriBold = cachedAmiriBold ?: cachedAmiriNormal ?: Typeface.SERIF
    return Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = amiriBold
      color = inkInt
      textAlign = Paint.Align.CENTER
    }
  }

  internal fun maxCandidateFontSp(
    slotHeightPx: Float,
    density: Float,
    fontScale: Float,
  ): Float = ((slotHeightPx * 1.45f) / (density * fontScale)).coerceIn(8f, 84f)

  internal fun displayedLinesForPage(pageData: MushafPageData): List<MushafLine> =
    if (pageData.pageNumber in 1..2) {
      pageData.lines.dropLastWhile { it is MushafLine.EmptyLine }
    } else {
      pageData.lines
    }

  suspend fun renderPdfPageOrGet(
    pageNumber: Int,
    headerMeta: PdfPageHeaderMeta?,
    renderedPdf: PdfRenderedPageBitmap,
    topSurah: SurahMeta?,
    spec: PageRenderSpec,
    surahsById: Map<Int, SurahMeta>,
    isPreview: Boolean = false,
  ): RenderedMushafPage {
    val key = makeKey(pageNumber, spec, isPreview = isPreview)
    bitmapCache.get(key)?.let { cached ->
      if (cached.pdfContent?.topSurah?.id == topSurah?.id) {
        return cached
      }
    }

    return withContext(Dispatchers.Default) {
      bitmapCache.get(key)?.let { cached ->
        if (cached.pdfContent?.topSurah?.id == topSurah?.id) {
          return@withContext cached
        }
      }
      ensureCacheCapacityFor(spec)
      ensureUiFontsLoaded()

      val rendered = renderPdfPageToBitmap(
        pageNumber = pageNumber,
        headerMeta = headerMeta,
        renderedPdf = renderedPdf,
        topSurah = topSurah,
        spec = spec,
        surahsById = surahsById,
        isPreview = isPreview,
      )
      bitmapCache.put(key, rendered)
      rendered
    }
  }

  suspend fun renderOrGet(
    pageData: MushafPageData,
    spec: PageRenderSpec,
    pageTypeface: Typeface?,
    basmalahTypeface: Typeface?,
    usesTagFallbackTajweed: Boolean,
    surahsById: Map<Int, SurahMeta>,
    endMarkerTypeface: Typeface? = null,
  ): RenderedMushafPage {
    val key = makeKey(
      pageNumber = pageData.pageNumber,
      spec = spec,
      usesTagFallbackTajweed = usesTagFallbackTajweed,
    )
    val pageFontId = System.identityHashCode(pageTypeface)
    val basmalahFontId = System.identityHashCode(basmalahTypeface)
    val endMarkerFontId = System.identityHashCode(endMarkerTypeface)
    bitmapCache.get(key)?.let { cached ->
      if (
        cached.pageFontIdentity == pageFontId &&
        cached.basmalahFontIdentity == basmalahFontId &&
        cached.usesTagFallbackTajweed == usesTagFallbackTajweed &&
        cached.endMarkerFontIdentity == endMarkerFontId
      ) {
        return cached
      }
    }

    return withContext(Dispatchers.Default) {
      bitmapCache.get(key)?.let { cached ->
        if (
          cached.pageFontIdentity == pageFontId &&
          cached.basmalahFontIdentity == basmalahFontId &&
          cached.usesTagFallbackTajweed == usesTagFallbackTajweed &&
          cached.endMarkerFontIdentity == endMarkerFontId
        ) {
          return@withContext cached
        }
      }
      ensureCacheCapacityFor(spec)
      ensureUiFontsLoaded()

      val rendered = renderPageToBitmap(
        pageData = pageData,
        spec = spec,
        pageTypeface = pageTypeface,
        basmalahTypeface = basmalahTypeface,
        usesTagFallbackTajweed = usesTagFallbackTajweed,
        surahsById = surahsById,
        endMarkerTypeface = endMarkerTypeface,
      )
      val hasBasmalahLine = pageData.lines.any { it is MushafLine.BasmalahLine }
      val allRequiredFontsLoaded = usesTagFallbackTajweed ||
        (pageTypeface != null && (!hasBasmalahLine || basmalahTypeface != null))
      if (allRequiredFontsLoaded) {
        bitmapCache.put(key, rendered)
      }
      rendered
    }
  }

  private data class CardChromeMetrics(
    val w: Int,
    val h: Int,
    val density: Float,
    val fontScale: Float,
    val effectiveDark: Boolean,
    val goldInt: Int,
    val inkInt: Int,
    val innerFrameLeft: Float,
    val innerFrameRight: Float,
    val bodyLeft: Float,
    val bodyTop: Float,
    val bodyRight: Float,
    val bodyBottom: Float,
    val contentWidth: Float,
    val amiriNormal: Typeface,
    val amiriBold: Typeface,
    val ibmNormal: Typeface,
    val ibmMedium: Typeface,
    val surahNameV4: Typeface?,
    val quranCommon: Typeface?,
  ) {
    val innerFrameWidth: Float
      get() = (innerFrameRight - innerFrameLeft).coerceAtLeast(1f)
  }

  private fun drawCardChromeAndComputeBody(
    canvas: Canvas,
    pageNumber: Int,
    surahIds: List<Int>,
    juzNumber: Int?,
    hizbNumber: Int?,
    spec: PageRenderSpec,
    surahsById: Map<Int, SurahMeta>,
    isPdfMode: Boolean,
  ): CardChromeMetrics {
    val w = spec.widthPx.coerceAtLeast(1)
    val h = spec.heightPx.coerceAtLeast(1)
    val d = spec.density
    val fs = spec.fontScale

    fun dp(v: Float): Float = v * d
    fun sp(v: Float): Float = v * d * fs

    val effectiveDark = spec.effectiveDark
    val goldInt = if (effectiveDark) 0xFFE0B45C.toInt() else 0xFFC29038.toInt()
    val paperInt = if (effectiveDark) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    val inkInt = if (effectiveDark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
    val headerTintInt = if (effectiveDark) 0xFFE0B45C.toInt() else 0xFF12403C.toInt()
    val mutedInkInt = if (effectiveDark) withAlpha(0xFFFFFFFF.toInt(), 0.78f) else 0xFF424A47.toInt()
    val frameAccentInt = if (effectiveDark) withAlpha(goldInt, 0.72f) else withAlpha(goldInt, 0.78f)
    val outlineCornerInt = if (effectiveDark) withAlpha(0xFF28322F.toInt(), 0.65f) else withAlpha(0xFFD9D5C9.toInt(), 0.65f)

    val amiriNormal = cachedAmiriNormal ?: Typeface.SERIF
    val amiriBold = cachedAmiriBold ?: amiriNormal
    val ibmNormal = cachedIbmNormal ?: Typeface.SANS_SERIF
    val ibmMedium = cachedIbmMedium ?: ibmNormal

    // 1. Paper surface + full-bleed double gold Mushaf frame running edge-to-edge with zero outer gap
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    val outerStrokeW = dp(1.5f)
    val innerStrokeW = dp(0.75f)
    val outerMargin = outerStrokeW * 0.5f
    val innerMargin = dp(4.0f)
    val cardCorner = dp(4f)
    val outerCorner = dp(3.5f)
    val innerCorner = dp(2.0f)

    fillPaint.color = paperInt
    canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), cardCorner, cardCorner, fillPaint)

    strokePaint.color = withAlpha(goldInt, 0.90f)
    strokePaint.strokeWidth = outerStrokeW
    canvas.drawRoundRect(
      RectF(outerMargin, outerMargin, w - outerMargin, h - outerMargin),
      outerCorner,
      outerCorner,
      strokePaint,
    )

    strokePaint.color = frameAccentInt
    strokePaint.strokeWidth = innerStrokeW
    canvas.drawRoundRect(
      RectF(innerMargin, innerMargin, w - innerMargin, h - innerMargin),
      innerCorner,
      innerCorner,
      strokePaint,
    )

    val dotRadius = dp(2.0f)
    val ringRadius = dp(10.5f) * 0.24f
    val cornerXs = floatArrayOf(innerMargin, w - innerMargin, innerMargin, w - innerMargin)
    val cornerYs = floatArrayOf(innerMargin, innerMargin, h - innerMargin, h - innerMargin)
    for (c in 0 until 4) {
      fillPaint.color = goldInt
      canvas.drawCircle(cornerXs[c], cornerYs[c], dotRadius, fillPaint)
      strokePaint.color = outlineCornerInt
      strokePaint.strokeWidth = dp(0.75f)
      canvas.drawCircle(cornerXs[c], cornerYs[c], ringRadius, strokePaint)
    }

    val innerFrameLeft = innerMargin + innerStrokeW * 0.5f
    val innerFrameRight = w - innerMargin - innerStrokeW * 0.5f

    // 2. Inner content box cleanly inside the inner gold frame
    val padH = dp(BODY_PAD_H_DP)
    val padV = dp(BODY_PAD_V_DP)
    val contentLeft = padH
    val contentTop = padV
    val contentRight = w - padH
    val contentBottom = h - padV
    val contentWidth = (contentRight - contentLeft).coerceAtLeast(1f)

    // 3. Top Header Row (Surah Name on RTL-right, Juz/Hizb Pill on RTL-left)
    val surahHeaderNames = surahIds.mapNotNull { surahsById[it]?.nameArabic }.joinToString(" • ")
    val headerTitle = if (surahHeaderNames.isNotBlank()) "سورة $surahHeaderNames" else ""
    val juzHizbText = if (pageNumber in 1..604) {
      com.example.data.quran.QuranQuartersCatalog.getHeaderQuarterText(pageNumber)
    } else if (juzNumber != null && hizbNumber != null && juzNumber > 0 && hizbNumber > 0) {
      "الجزء ${toArabicIndicDigits(juzNumber)} • الحزب ${toArabicIndicDigits(hizbNumber)}"
    } else {
      ""
    }

    val baseTitleSp = 13f * 0.60f // 40% smaller: 7.8sp
    val basePillSp = 11f * 0.60f  // 40% smaller: 6.6sp
    val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = amiriNormal
      textSize = sp(baseTitleSp)
      color = headerTintInt
    }
    val pillPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = amiriNormal
      textSize = sp(basePillSp)
      color = mutedInkInt
    }

    val pillLeft = contentLeft + dp(4f)
    val titleRight = contentRight - dp(4f)
    val availableHeaderW = (titleRight - pillLeft).coerceAtLeast(1f)
    val minSafeGap = dp(8f)
    var pillPadH = dp(6f)
    val pillPadV = dp(1f)

    var pillTextW = if (juzHizbText.isNotEmpty()) pillPaint.measureText(juzHizbText) else 0f
    var headerTitleW = if (headerTitle.isNotEmpty()) titlePaint.measureText(headerTitle) else 0f
    var pillW = if (juzHizbText.isNotEmpty()) pillTextW + pillPadH * 2f else 0f

    if (headerTitle.isNotEmpty() && juzHizbText.isNotEmpty() && (pillW + minSafeGap + headerTitleW) > availableHeaderW) {
      val minReadablePx = dp(4.5f)
      val scale = ((availableHeaderW - minSafeGap - pillPadH * 2f) / (pillTextW + headerTitleW))
        .coerceIn(0.45f, 0.96f)
      titlePaint.textSize = (sp(baseTitleSp) * scale).coerceAtLeast(minReadablePx)
      pillPaint.textSize = (sp(basePillSp) * scale).coerceAtLeast(minReadablePx * 0.85f)
      pillPadH = (dp(6f) * scale).coerceAtLeast(dp(3.5f))
      pillTextW = pillPaint.measureText(juzHizbText)
      headerTitleW = titlePaint.measureText(headerTitle)
      pillW = pillTextW + pillPadH * 2f

      while ((pillW + minSafeGap + headerTitleW) > availableHeaderW && titlePaint.textSize > minReadablePx) {
        titlePaint.textSize = (titlePaint.textSize * 0.94f).coerceAtLeast(minReadablePx)
        pillPaint.textSize = (pillPaint.textSize * 0.94f).coerceAtLeast(minReadablePx * 0.85f)
        pillTextW = pillPaint.measureText(juzHizbText)
        headerTitleW = titlePaint.measureText(headerTitle)
        pillW = pillTextW + pillPadH * 2f
      }
    }

    val titleFm = titlePaint.fontMetrics
    val pillFm = pillPaint.fontMetrics
    val topHeaderHeight = maxOf(
      (titleFm.descent - titleFm.ascent) + dp(2f),
      (pillFm.descent - pillFm.ascent) + dp(4f),
    )

    if (juzHizbText.isNotEmpty()) {
      val pillTextH = pillFm.descent - pillFm.ascent
      val pillH = pillTextH + pillPadV * 2f
      val pillTop = contentTop + (topHeaderHeight - pillH) * 0.5f
      fillPaint.color = withAlpha(goldInt, if (effectiveDark) 0.14f else 0.11f)
      canvas.drawRoundRect(
        RectF(pillLeft, pillTop, pillLeft + pillW, pillTop + pillH),
        dp(10f),
        dp(10f),
        fillPaint,
      )
      canvas.drawText(
        juzHizbText,
        pillLeft + pillPadH,
        pillTop + pillPadV - pillFm.ascent,
        pillPaint,
      )
    }

    if (headerTitle.isNotEmpty()) {
      val reservedPill = if (juzHizbText.isNotEmpty()) pillW + minSafeGap else 0f
      val maxAllowedTitleW = (availableHeaderW - reservedPill).coerceAtLeast(0f)
      val drawTitle = if (headerTitleW <= maxAllowedTitleW) {
        headerTitle
      } else {
        android.text.TextUtils.ellipsize(
          headerTitle,
          titlePaint,
          maxAllowedTitleW,
          android.text.TextUtils.TruncateAt.END,
        ).toString()
      }
      val drawTitleW = titlePaint.measureText(drawTitle)
      val titleBaseline = contentTop + (topHeaderHeight - (titleFm.descent - titleFm.ascent)) * 0.5f - titleFm.ascent
      canvas.drawText(drawTitle, titleRight - drawTitleW, titleBaseline, titlePaint)
    }

    // 4. Compact Bottom Footer Row (~0.5 cm total bottom band: scaled Page Number Pill + RTL-left hint (text mode only) + clear RTL-right Bookmark corner)
    val footerBadgePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = amiriBold
      textSize = minOf(sp(8.2f), dp(10.5f))
      color = headerTintInt
    }
    val pageNumText = toArabicIndicDigits(pageNumber)
    val pageNumBounds = Rect()
    footerBadgePaint.getTextBounds(pageNumText, 0, pageNumText.length, pageNumBounds)
    val footerBadgeFm = footerBadgePaint.fontMetrics
    val pageNumW = maxOf(footerBadgePaint.measureText(pageNumText), pageNumBounds.width().toFloat())
    val pageNumInkH = if (pageNumBounds.height() > 0) {
      pageNumBounds.height().toFloat()
    } else {
      (footerBadgeFm.descent - footerBadgeFm.ascent) * 0.65f
    }
    val badgePadH = dp(8.5f)
    val badgePadV = dp(3.5f)
    val badgeW = pageNumW + badgePadH * 2f
    val badgeH = maxOf(dp(13f), pageNumInkH + badgePadV * 2f)

    val footerHeight = maxOf(dp(18f), badgeH + dp(2f))
    val footerTop = contentBottom - footerHeight

    val badgeLeft = contentLeft + (contentWidth - badgeW) * 0.5f
    val badgeTop = footerTop + (footerHeight - badgeH) * 0.5f
    val badgeCorner = dp(8f)
    fillPaint.color = withAlpha(goldInt, if (effectiveDark) 0.18f else 0.14f)
    canvas.drawRoundRect(
      RectF(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH),
      badgeCorner,
      badgeCorner,
      fillPaint,
    )
    strokePaint.color = withAlpha(goldInt, 0.55f)
    strokePaint.strokeWidth = dp(0.75f)
    canvas.drawRoundRect(
      RectF(badgeLeft, badgeTop, badgeLeft + badgeW, badgeTop + badgeH),
      badgeCorner,
      badgeCorner,
      strokePaint,
    )
    val badgeTextX = if (pageNumBounds.width() > 0) {
      badgeLeft + badgeW * 0.5f - (pageNumBounds.left + pageNumBounds.right) * 0.5f
    } else {
      badgeLeft + badgePadH
    }
    val badgeTextY = if (pageNumBounds.height() > 0) {
      badgeTop + badgeH * 0.5f - (pageNumBounds.top + pageNumBounds.bottom) * 0.5f
    } else {
      badgeTop + badgeH * 0.5f - (footerBadgeFm.ascent + footerBadgeFm.descent) * 0.5f
    }
    canvas.drawText(pageNumText, badgeTextX, badgeTextY, footerBadgePaint)

    if (!isPdfMode) {
      val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ibmMedium
        textSize = minOf(sp(6.8f), dp(8.5f))
        color = withAlpha(mutedInkInt, 0.85f)
      }
      val safeGapFromBadge = dp(12f)
      val maxHintW = (badgeLeft - contentLeft - safeGapFromBadge).coerceAtLeast(0f)
      if (maxHintW > dp(34f)) {
        var hintText = "اضغط على الآية للتحديد"
        hintPaint.textSize = minOf(sp(6.8f), dp(8.5f))
        if (hintPaint.measureText(hintText) > maxHintW) {
          hintPaint.textSize = minOf(sp(6.2f), dp(7.6f))
        }
        if (hintPaint.measureText(hintText) > maxHintW) {
          hintText = "اضغط للتحديد"
          hintPaint.textSize = minOf(sp(6.2f), dp(7.6f))
        }
        if (hintPaint.measureText(hintText) > maxHintW) {
          hintPaint.textSize = minOf(sp(5.6f), dp(6.8f))
        }
        if (hintPaint.measureText(hintText) <= maxHintW) {
          val hintBounds = Rect()
          hintPaint.getTextBounds(hintText, 0, hintText.length, hintBounds)
          val hintBaseline = if (hintBounds.height() > 0) {
            footerTop + footerHeight * 0.5f - (hintBounds.top + hintBounds.bottom) * 0.5f
          } else {
            val hintFm = hintPaint.fontMetrics
            footerTop + footerHeight * 0.5f - (hintFm.ascent + hintFm.descent) * 0.5f
          }
          canvas.drawText(hintText, contentLeft, hintBaseline, hintPaint)
        }
      }
    }

    val bodyTop = contentTop + topHeaderHeight + dp(1f)
    val bodyBottom = footerTop - dp(2f)
    return CardChromeMetrics(
      w = w,
      h = h,
      density = d,
      fontScale = fs,
      effectiveDark = effectiveDark,
      goldInt = goldInt,
      inkInt = inkInt,
      innerFrameLeft = innerFrameLeft,
      innerFrameRight = innerFrameRight,
      bodyLeft = contentLeft,
      bodyTop = bodyTop,
      bodyRight = contentRight,
      bodyBottom = bodyBottom,
      contentWidth = contentWidth,
      amiriNormal = amiriNormal,
      amiriBold = amiriBold,
      ibmNormal = ibmNormal,
      ibmMedium = ibmMedium,
      surahNameV4 = cachedSurahNameV4,
      quranCommon = cachedQuranCommon,
    )
  }

  private fun renderPdfPageToBitmap(
    pageNumber: Int,
    headerMeta: PdfPageHeaderMeta?,
    renderedPdf: PdfRenderedPageBitmap,
    topSurah: SurahMeta?,
    spec: PageRenderSpec,
    surahsById: Map<Int, SurahMeta>,
    isPreview: Boolean = false,
  ): RenderedMushafPage {
    val w = spec.widthPx.coerceAtLeast(1)
    val h = spec.heightPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val chrome = drawCardChromeAndComputeBody(
      canvas = canvas,
      pageNumber = pageNumber,
      surahIds = headerMeta?.surahIds.orEmpty(),
      juzNumber = headerMeta?.juzNumber,
      hizbNumber = headerMeta?.hizbNumber,
      spec = spec,
      surahsById = surahsById,
      isPdfMode = true,
    )

    val bodyLeftPx = ceil(chrome.bodyLeft.toDouble()).toInt().coerceIn(0, w - 1).toFloat()
    val bodyTopPx = ceil(chrome.bodyTop.toDouble()).toInt().coerceIn(0, h - 1).toFloat()
    val bodyRightPx = kotlin.math.floor(chrome.bodyRight.toDouble()).toInt().coerceIn(bodyLeftPx.toInt() + 1, w).toFloat()
    val bodyBottomPx = kotlin.math.floor(chrome.bodyBottom.toDouble()).toInt().coerceIn(bodyTopPx.toInt() + 1, h).toFloat()

    return RenderedMushafPage(
      pageNumber = pageNumber,
      widthPx = w,
      heightPx = h,
      byteCount = bitmap.allocationByteCount,
      imageBitmap = bitmap.asImageBitmap(),
      verseHighlightRects = emptyMap(),
      rosetteSlotsByVerse = emptyMap(),
      wordHitBoxes = WordHitBoxes(
        count = 0,
        lefts = FloatArray(0),
        tops = FloatArray(0),
        rights = FloatArray(0),
        bottoms = FloatArray(0),
        verseKeys = emptyArray(),
        codeV2Lines = emptyArray(),
      ),
      pdfContent = PdfPageOverlayData(
        pdfBitmap = renderedPdf.bitmap.asImageBitmap(),
        bmpWidthPx = renderedPdf.croppedWidthPx,
        bmpHeightPx = renderedPdf.croppedHeightPx,
        topBandEndY = renderedPdf.topBandEndY,
        bottomBandStartY = renderedPdf.bottomBandStartY,
        textSrcLeft = renderedPdf.textBounds.left,
        textSrcTop = renderedPdf.textBounds.top,
        textSrcRight = renderedPdf.textBounds.right,
        textSrcBottom = renderedPdf.textBounds.bottom,
        bodyLeftPx = bodyLeftPx,
        bodyTopPx = bodyTopPx,
        bodyRightPx = bodyRightPx,
        bodyBottomPx = bodyBottomPx,
        topSurah = topSurah,
        segmentation = renderedPdf.segmentation,
      ),
      isPreview = isPreview,
    )
  }

  private fun renderPageToBitmap(
    pageData: MushafPageData,
    spec: PageRenderSpec,
    pageTypeface: Typeface?,
    basmalahTypeface: Typeface?,
    usesTagFallbackTajweed: Boolean,
    surahsById: Map<Int, SurahMeta>,
    endMarkerTypeface: Typeface? = null,
  ): RenderedMushafPage {
    val w = spec.widthPx.coerceAtLeast(1)
    val h = spec.heightPx.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val chrome = drawCardChromeAndComputeBody(
      canvas = canvas,
      pageNumber = pageData.pageNumber,
      surahIds = pageData.surahIds,
      juzNumber = pageData.juzNumber,
      hizbNumber = pageData.hizbNumber,
      spec = spec,
      surahsById = surahsById,
      isPdfMode = false,
    )

    val d = chrome.density
    val fs = chrome.fontScale
    fun dp(v: Float): Float = v * d

    val effectiveDark = chrome.effectiveDark
    val goldInt = chrome.goldInt
    val inkInt = chrome.inkInt
    val amiriNormal = chrome.amiriNormal
    val amiriBold = chrome.amiriBold
    val ibmMedium = chrome.ibmMedium
    val surahNameV4 = chrome.surahNameV4
    val quranCommon = chrome.quranCommon

    val bodyTop = chrome.bodyTop
    val bodyBottom = chrome.bodyBottom
    val bodyHeight = (bodyBottom - bodyTop).coerceAtLeast(1f)
    val bodyLeft = chrome.bodyLeft
    val contentWidth = chrome.contentWidth
    val safetyMarginPx = computeSafetyMarginPx(contentWidth, d)
    val safeLeft = bodyLeft + safetyMarginPx
    val safeContentWidth = (contentWidth - safetyMarginPx * 2f).coerceAtLeast(1f)
    val safeWidthInt = safeContentWidth.roundToInt().coerceAtLeast(1)

    val isFramedIntroPage = pageData.pageNumber in 1..2
    val displayedLines = displayedLinesForPage(pageData)
    val lineCount = displayedLines.size.coerceAtLeast(1)
    val standardSlotHeight = bodyHeight / 15f
    val slotHeight = if (isFramedIntroPage && lineCount < 15) {
      // Keep vertical pitch proportional to page height and similar to printed mushaf instead of stretching 8 lines over full height
      minOf(bodyHeight / lineCount.toFloat(), standardSlotHeight * 1.24f)
    } else {
      bodyHeight / lineCount.toFloat()
    }
    val blockHeight = slotHeight * lineCount.toFloat()
    val linesStartTop = bodyTop + ((bodyHeight - blockHeight) * 0.5f).coerceAtLeast(0f)

    // Pre-size hit box storage
    val totalWordsEstimate = displayedLines.sumOf { if (it is MushafLine.AyahWordsLine) it.words.size else 0 }
    val hitLefts = FloatArray(totalWordsEstimate)
    val hitTops = FloatArray(totalWordsEstimate)
    val hitRights = FloatArray(totalWordsEstimate)
    val hitBottoms = FloatArray(totalWordsEstimate)
    val hitVerseKeys = Array(totalWordsEstimate) { "" }
    val hitCodeV2Lines = Array(totalWordsEstimate) { "" }
    var hitCursor = 0

    // Group highlight segments per verse across each line
    val verseRectAccum = HashMap<String, MutableList<Float>>()
    val rosetteAccum = HashMap<String, MutableList<Float>>()

    val endMarkerPaint = if (spec.edition == MushafEdition.TAJWEED_V4 && endMarkerTypeface != null) {
      TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = endMarkerTypeface
        color = inkInt
      }
    } else {
      null
    }
    val renderVectorTajweedMarkers = spec.edition == MushafEdition.TAJWEED_V4 && endMarkerPaint == null
    val activeTypeface = when {
      usesTagFallbackTajweed -> amiriNormal
      pageTypeface != null -> pageTypeface
      else -> amiriNormal
    }

    val lineTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = activeTypeface
      color = inkInt
    }
    val basmalahPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = basmalahTypeface ?: amiriNormal
      color = inkInt
    }
    val rosetteNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = amiriBold
      color = inkInt
      textAlign = Paint.Align.CENTER
    }

    val providedGlobalSp = if (isFramedIntroPage) spec.globalIntroFontSp else spec.globalBodyFontSp
    val uniformPageSp = if (providedGlobalSp != null && providedGlobalSp > 0f) {
      providedGlobalSp
    } else {
      computeUniformPageFontSp(
        displayedLines = displayedLines,
        paint = lineTextPaint,
        basmalahPaint = basmalahPaint,
        hasBasmalahTypeface = basmalahTypeface != null,
        rosetteNumPaint = rosetteNumPaint,
        renderVectorTajweedMarkers = renderVectorTajweedMarkers,
        usesTagFallbackTajweed = usesTagFallbackTajweed,
        goldInt = goldInt,
        inkInt = inkInt,
        effectiveDark = effectiveDark,
        safeWidthPx = safeWidthInt,
        slotHeightPx = slotHeight,
        density = d,
        fontScale = fs,
        endMarkerPaint = endMarkerPaint,
      )
    }

    val parsedAyahTokensByVerse: Map<String, List<TajweedTagParser.TaggedToken>> =
      if (usesTagFallbackTajweed && pageData.tajweedAyahTexts.isNotEmpty()) {
        buildMap(pageData.tajweedAyahTexts.size) {
          for ((vk, rawText) in pageData.tajweedAyahTexts) {
            put(vk, TajweedTagParser.parseAyahToTaggedTokens(rawText, effectiveDark = effectiveDark))
          }
        }
      } else {
        emptyMap()
      }

    for (slotIndex in displayedLines.indices) {
      val line = displayedLines[slotIndex]
      val slotTop = linesStartTop + slotIndex * slotHeight

      when (line) {
        is MushafLine.EmptyLine -> Unit

        is MushafLine.SurahHeaderLine -> {
          SurahHeaderOrnamentRenderer.drawSurahHeaderBand(
            canvas = canvas,
            surah = line.surah,
            edition = spec.edition,
            effectiveDark = effectiveDark,
            left = chrome.innerFrameLeft,
            top = slotTop,
            width = chrome.innerFrameWidth,
            height = slotHeight,
            amiriBold = amiriBold,
            ibmMedium = ibmMedium,
            surahNameV4 = surahNameV4,
            quranCommon = quranCommon,
            dpFn = ::dp,
          )
        }

        is MushafLine.BasmalahLine -> {
          val basmalahText = if (basmalahTypeface != null && line.codeV2Glyphs.isNotBlank()) {
            line.codeV2Glyphs
          } else if (usesTagFallbackTajweed && line.textUthmani.isNotBlank()) {
            line.textUthmani
          } else {
            ""
          }
          if (basmalahText.isNotBlank()) {
            drawBasmalahStaticLayout(
              canvas = canvas,
              pageNumber = pageData.pageNumber,
              lineNumber = line.lineNumber,
              spec = spec,
              glyphs = basmalahText,
              uniformPageSp = uniformPageSp,
              paint = basmalahPaint,
              usesTagFallbackTajweed = usesTagFallbackTajweed,
              safeLeft = safeLeft,
              slotTop = slotTop,
              safeWidthPx = safeWidthInt,
              contentWidthPx = contentWidth,
              slotHeightPx = slotHeight,
              density = d,
              fontScale = fs,
            )
          }
        }

        is MushafLine.AyahWordsLine -> {
          if (line.words.isEmpty() || (!usesTagFallbackTajweed && pageTypeface == null)) {
            continue
          }
          hitCursor = drawAyahLineWithStaticLayout(
            canvas = canvas,
            pageNumber = pageData.pageNumber,
            spec = spec,
            line = line,
            uniformPageSp = uniformPageSp,
            paint = lineTextPaint,
            rosetteNumPaint = rosetteNumPaint,
            renderVectorTajweedMarkers = renderVectorTajweedMarkers,
            usesTagFallbackTajweed = usesTagFallbackTajweed,
            goldInt = goldInt,
            inkInt = inkInt,
            effectiveDark = effectiveDark,
            safeLeft = safeLeft,
            slotTop = slotTop,
            safeWidthPx = safeWidthInt,
            contentWidthPx = contentWidth,
            slotHeightPx = slotHeight,
            density = d,
            fontScale = fs,
            hitLefts = hitLefts,
            hitTops = hitTops,
            hitRights = hitRights,
            hitBottoms = hitBottoms,
            hitVerseKeys = hitVerseKeys,
            hitCodeV2Lines = hitCodeV2Lines,
            startCursor = hitCursor,
            verseRectAccum = verseRectAccum,
            rosetteAccum = rosetteAccum,
            endMarkerPaint = endMarkerPaint,
            parsedAyahTokensByVerse = parsedAyahTokensByVerse,
          )
        }
      }
    }

    val verseHighlightRects = HashMap<String, FloatArray>(verseRectAccum.size)
    for ((k, list) in verseRectAccum) {
      verseHighlightRects[k] = list.toFloatArray()
    }
    val rosetteSlotsByVerse = HashMap<String, FloatArray>(rosetteAccum.size)
    for ((k, list) in rosetteAccum) {
      rosetteSlotsByVerse[k] = list.toFloatArray()
    }

    return RenderedMushafPage(
      pageNumber = pageData.pageNumber,
      widthPx = w,
      heightPx = h,
      byteCount = bitmap.allocationByteCount,
      imageBitmap = bitmap.asImageBitmap(),
      verseHighlightRects = verseHighlightRects,
      rosetteSlotsByVerse = rosetteSlotsByVerse,
      wordHitBoxes = WordHitBoxes(
        count = hitCursor,
        lefts = hitLefts,
        tops = hitTops,
        rights = hitRights,
        bottoms = hitBottoms,
        verseKeys = hitVerseKeys,
        codeV2Lines = hitCodeV2Lines,
      ),
      pageFontIdentity = System.identityHashCode(pageTypeface),
      basmalahFontIdentity = System.identityHashCode(basmalahTypeface),
      usesTagFallbackTajweed = usesTagFallbackTajweed,
      endMarkerFontIdentity = System.identityHashCode(endMarkerTypeface),
    )
  }

  private fun measureTokenHorizontalOverhangs(
    paint: TextPaint,
    token: String,
    advanceWidth: Float,
    boundsRect: Rect,
  ): Pair<Float, Float> {
    if (token.isEmpty()) return 0f to 0f
    paint.getTextBounds(token, 0, token.length, boundsRect)
    val leftOverhang = (-boundsRect.left.toFloat()).coerceAtLeast(0f)
    val rightOverhang = (boundsRect.right.toFloat() - advanceWidth).coerceAtLeast(0f)
    return leftOverhang to rightOverhang
  }

  private data class MeasuredWordBox(
    val widthPx: Float,
    val drawOffsetX: Float,
    val leftOverhangPx: Float,
    val rightOverhangPx: Float,
  )

  private fun measureWordBox(
    paint: TextPaint,
    token: String,
    boundsRect: Rect,
  ): MeasuredWordBox {
    if (token.isEmpty()) return MeasuredWordBox(0f, 0f, 0f, 0f)
    val adv = paint.measureText(token)
    paint.getTextBounds(token, 0, token.length, boundsRect)
    val bboxW = (boundsRect.right - boundsRect.left).toFloat().coerceAtLeast(0f)
    // Guard against any Android shaper returning 0/collapsed advance for Presentation Forms-A ligatures (U+FC5E..U+FC63)
    return if (adv > 1f && (bboxW <= 1f || adv >= bboxW * 0.25f)) {
      val leftOver = (-boundsRect.left.toFloat()).coerceAtLeast(0f)
      val rightOver = (boundsRect.right.toFloat() - adv).coerceAtLeast(0f)
      MeasuredWordBox(
        widthPx = adv,
        drawOffsetX = 0f,
        leftOverhangPx = leftOver,
        rightOverhangPx = rightOver,
      )
    } else {
      val safeW = maxOf(adv, bboxW)
      MeasuredWordBox(
        widthPx = safeW,
        drawOffsetX = if (bboxW > 1f) -boundsRect.left.toFloat() else 0f,
        leftOverhangPx = 0f,
        rightOverhangPx = 0f,
      )
    }
  }

  private fun measureStaticLayoutLineSpanWidth(layout: StaticLayout): Float {
    if (layout.lineCount <= 0) return 0f
    val span = layout.getLineRight(0) - layout.getLineLeft(0)
    return maxOf(layout.getLineWidth(0), span).coerceAtLeast(0f)
  }

  private fun logDebugOverflowIfNeeded(
    pageNumber: Int,
    lineNumber: Int,
    spec: PageRenderSpec,
    fontSp: Float,
    visualWidthPx: Float,
    contentWidthPx: Float,
    safeWidthPx: Int,
    lineCount: Int,
  ) {
    if (!BuildConfig.DEBUG || pageNumber !in DEBUG_OVERFLOW_PAGES) return
    if (visualWidthPx > contentWidthPx || lineCount > 1) {
      val msg = "Overflow page=$pageNumber line=$lineNumber edition=${spec.edition} " +
        "twoPage=${spec.isTwoPageMode} fontSp=$fontSp visualW=$visualWidthPx " +
        "contentW=$contentWidthPx safeW=$safeWidthPx layoutLines=$lineCount"
      runCatching { Log.w(OVERFLOW_LOG_TAG, msg) }
        .onFailure { System.err.println("$OVERFLOW_LOG_TAG: $msg") }
    }
  }

  private fun drawBasmalahStaticLayout(
    canvas: Canvas,
    pageNumber: Int,
    lineNumber: Int,
    spec: PageRenderSpec,
    glyphs: String,
    uniformPageSp: Float,
    paint: TextPaint,
    usesTagFallbackTajweed: Boolean,
    safeLeft: Float,
    slotTop: Float,
    safeWidthPx: Int,
    contentWidthPx: Float,
    slotHeightPx: Float,
    density: Float,
    fontScale: Float,
  ) {
    val boundsRect = Rect()
    val rtlText = "\u200F$glyphs\u200F"
    val minSp = 6f
    val maxAllowedSp = uniformPageSp.coerceAtLeast(minSp)

    fun measureBasmalahVisual(candidateSp: Float): Pair<Boolean, Float> {
      val px = candidateSp * density * fontScale
      paint.textSize = px
      val adv = paint.measureText(glyphs)
      val (leftOverhang, rightOverhang) = measureTokenHorizontalOverhangs(paint, glyphs, adv, boundsRect)
      val fm = paint.fontMetrics
      val verticalFits = if (usesTagFallbackTajweed) {
        (fm.descent - fm.ascent) <= slotHeightPx * 0.96f
      } else {
        px <= slotHeightPx * 0.66f && boundsRect.height().toFloat() <= slotHeightPx * 0.92f
      }
      if (!verticalFits) return false to Float.MAX_VALUE
      val probeW = maxOf(safeWidthPx * 2, ceil(adv).toInt() + 128)
      val probeLayout = StaticLayout.Builder
        .obtain(rtlText, 0, rtlText.length, paint, probeW)
        .setAlignment(Layout.Alignment.ALIGN_CENTER)
        .setTextDirection(TextDirectionHeuristics.RTL)
        .setIncludePad(true)
        .setMaxLines(2)
        .build()
      val spanW = measureStaticLayoutLineSpanWidth(probeLayout)
      val visualW = spanW + leftOverhang + rightOverhang
      val fits = probeLayout.lineCount == 1 && visualW <= safeWidthPx.toFloat()
      return fits to visualW
    }

    val resolvedSp = if (measureBasmalahVisual(maxAllowedSp).first) {
      maxAllowedSp
    } else {
      var lo = minSp
      var hi = maxAllowedSp
      repeat(12) {
        val mid = (lo + hi) * 0.5f
        if (measureBasmalahVisual(mid).first) lo = mid else hi = mid
      }
      lo.coerceAtLeast(minSp)
    }

    paint.textSize = resolvedSp * density * fontScale
    val adv = paint.measureText(glyphs)
    val (leftOverhang, rightOverhang) = measureTokenHorizontalOverhangs(paint, glyphs, adv, boundsRect)
    val layoutWidth = maxOf(safeWidthPx, ceil(adv).toInt() + 64)
    val layout = StaticLayout.Builder
      .obtain(rtlText, 0, rtlText.length, paint, layoutWidth)
      .setAlignment(Layout.Alignment.ALIGN_CENTER)
      .setTextDirection(TextDirectionHeuristics.RTL)
      .setIncludePad(true)
      .setMaxLines(2)
      .build()

    val spanW = measureStaticLayoutLineSpanWidth(layout)
    val visualW = spanW + leftOverhang + rightOverhang
    logDebugOverflowIfNeeded(
      pageNumber = pageNumber,
      lineNumber = lineNumber,
      spec = spec,
      fontSp = resolvedSp,
      visualWidthPx = visualW,
      contentWidthPx = contentWidthPx,
      safeWidthPx = safeWidthPx,
      lineCount = layout.lineCount,
    )

    val lineLeft = if (layout.lineCount > 0) layout.getLineLeft(0) else 0f
    val lineRight = if (layout.lineCount > 0) layout.getLineRight(0) else spanW
    val lineCenter = (lineLeft + lineRight) * 0.5f
    val targetCenter = safeLeft + safeWidthPx.toFloat() * 0.5f
    val minTx = (safeLeft + leftOverhang) - lineLeft
    val maxTx = (safeLeft + safeWidthPx.toFloat() - rightOverhang) - lineRight
    val translateX = if (minTx <= maxTx) {
      (targetCenter - lineCenter).coerceIn(minTx, maxTx)
    } else {
      (minTx + maxTx) * 0.5f
    }
    val offsetY = slotTop + (slotHeightPx - layout.height) * 0.5f

    canvas.save()
    canvas.translate(translateX, offsetY)
    layout.draw(canvas)
    canvas.restore()
  }

  private data class ExactLayoutCheckResult(
    val fits: Boolean,
    val anyWrapped: Boolean,
    val worstRatio: Float,
  )

  private fun allLinesFitFast(
    candidateSp: Float,
    ayahLines: List<MushafLine.AyahWordsLine>,
    lineTokens: Array<Array<String>>,
    basmalahLines: List<MushafLine.BasmalahLine>,
    paint: TextPaint,
    basmalahPaint: TextPaint,
    hasBasmalahTypeface: Boolean,
    renderVectorTajweedMarkers: Boolean,
    usesTagFallbackTajweed: Boolean,
    safeWidthF: Float,
    slotHeightPx: Float,
    density: Float,
    fontScale: Float,
    tokenBounds: Rect,
    endMarkerPaint: TextPaint? = null,
  ): Boolean {
    val fontSizePx = candidateSp * density * fontScale
    if (!usesTagFallbackTajweed && fontSizePx > slotHeightPx * 0.66f) return false
    paint.textSize = fontSizePx
    endMarkerPaint?.textSize = fontSizePx
    val justifiedMinGapPx = (fontSizePx * 0.065f).roundToInt().coerceAtLeast(1)
    val centeredGapPx = (fontSizePx * 0.11f).roundToInt().coerceAtLeast(2)
    val fm = paint.fontMetrics

    var inkMinTop = Int.MAX_VALUE
    var inkMaxBottom = Int.MIN_VALUE
    var hasMeasuredInk = false
    var maxMarkerH = 0f

    for (lineIdx in ayahLines.indices) {
      val ayahLine = ayahLines[lineIdx]
      val words = ayahLine.words
      val tokens = lineTokens[lineIdx]
      val n = words.size
      var sumW = 0f
      var rightOverhang = 0f
      var leftOverhang = 0f

      for (i in 0 until n) {
        val w = words[i]
        if (w.isEndMarker && endMarkerPaint != null && w.codeV2.isNotBlank()) {
          val tok = w.codeV2
          val box = measureWordBox(endMarkerPaint, tok, tokenBounds)
          sumW += box.widthPx
          if (i == 0) rightOverhang = box.rightOverhangPx
          if (i == n - 1) leftOverhang = box.leftOverhangPx
        } else if (renderVectorTajweedMarkers && w.isEndMarker) {
          val mw = (fontSizePx * 1.05f).roundToInt().coerceAtLeast(1).toFloat()
          sumW += mw
          if (mw > maxMarkerH) maxMarkerH = mw
        } else {
          val tok = tokens[i]
          val box = measureWordBox(paint, tok, tokenBounds)
          sumW += box.widthPx
          if (tok.isNotEmpty()) {
            if (tokenBounds.top < inkMinTop) inkMinTop = tokenBounds.top
            if (tokenBounds.bottom > inkMaxBottom) inkMaxBottom = tokenBounds.bottom
            hasMeasuredInk = true
            if (i == 0) {
              rightOverhang = box.rightOverhangPx
            }
            if (i == n - 1) {
              leftOverhang = box.leftOverhangPx
            }
          }
        }
      }

      val lineGapPx = if (ayahLine.isCentered) centeredGapPx else justifiedMinGapPx
      val totalNaturalW = sumW + lineGapPx * (n - 1).coerceAtLeast(0) + leftOverhang + rightOverhang
      if (totalNaturalW > safeWidthF) return false
    }

    if (maxMarkerH > slotHeightPx * 0.95f) return false
    if (usesTagFallbackTajweed) {
      if (hasMeasuredInk) {
        if ((inkMaxBottom - inkMinTop).toFloat() > slotHeightPx * 0.92f) return false
      } else if ((fm.descent - fm.ascent) > slotHeightPx * 1.05f) {
        return false
      }
    }

    for (bLine in basmalahLines) {
      val glyphs = if (hasBasmalahTypeface && bLine.codeV2Glyphs.isNotBlank()) {
        bLine.codeV2Glyphs
      } else if (usesTagFallbackTajweed && bLine.textUthmani.isNotBlank()) {
        bLine.textUthmani
      } else {
        ""
      }
      if (glyphs.isNotBlank()) {
        basmalahPaint.textSize = fontSizePx
        val adv = basmalahPaint.measureText(glyphs)
        val (lOver, rOver) = measureTokenHorizontalOverhangs(basmalahPaint, glyphs, adv, tokenBounds)
        if (adv + lOver + rOver > safeWidthF) return false
        if (usesTagFallbackTajweed) {
          val bFm = basmalahPaint.fontMetrics
          if ((bFm.descent - bFm.ascent) > slotHeightPx * 0.96f) return false
        } else {
          if (tokenBounds.height().toFloat() > slotHeightPx * 0.92f) return false
        }
      }
    }

    return true
  }

  private fun checkExactStaticLayoutFit(
    candidateSp: Float,
    ayahLines: List<MushafLine.AyahWordsLine>,
    lineTokens: Array<Array<String>>,
    basmalahLines: List<MushafLine.BasmalahLine>,
    paint: TextPaint,
    basmalahPaint: TextPaint,
    hasBasmalahTypeface: Boolean,
    rosetteNumPaint: Paint,
    renderVectorTajweedMarkers: Boolean,
    usesTagFallbackTajweed: Boolean,
    goldInt: Int,
    inkInt: Int,
    effectiveDark: Boolean,
    safeWidthPx: Int,
    safeWidthF: Float,
    density: Float,
    fontScale: Float,
    tokenBounds: Rect,
    endMarkerPaint: TextPaint? = null,
  ): ExactLayoutCheckResult {
    val fontSizePx = candidateSp * density * fontScale
    paint.textSize = fontSizePx
    endMarkerPaint?.textSize = fontSizePx
    val justifiedMinGapPx = (fontSizePx * 0.065f).roundToInt().coerceAtLeast(1)
    val centeredGapPx = (fontSizePx * 0.11f).roundToInt().coerceAtLeast(2)
    var worstRatio = 1.0f
    var anyWrapped = false

    for (lineIdx in ayahLines.indices) {
      val ayahLine = ayahLines[lineIdx]
      val words = ayahLine.words
      val tokens = lineTokens[lineIdx]
      val n = words.size
      val lineGapPx = if (ayahLine.isCentered) centeredGapPx else justifiedMinGapPx
      var estW = lineGapPx * (n - 1).coerceAtLeast(0).toFloat()
      var rightOverhang = 0f
      var leftOverhang = 0f
      for (i in 0 until n) {
        if (words[i].isEndMarker && endMarkerPaint != null && words[i].codeV2.isNotBlank()) {
          val box = measureWordBox(endMarkerPaint, words[i].codeV2, tokenBounds)
          estW += box.widthPx
          if (i == 0) rightOverhang = box.rightOverhangPx
          if (i == n - 1) leftOverhang = box.leftOverhangPx
        } else if (renderVectorTajweedMarkers && words[i].isEndMarker) {
          estW += (fontSizePx * 1.05f).roundToInt().coerceAtLeast(1).toFloat()
        } else {
          val box = measureWordBox(paint, tokens[i], tokenBounds)
          estW += box.widthPx
          if (i == 0) rightOverhang = box.rightOverhangPx
          if (i == n - 1) leftOverhang = box.leftOverhangPx
        }
      }
      val actualVisualW = estW + leftOverhang + rightOverhang
      val ratio = actualVisualW / safeWidthF
      if (ratio > worstRatio) worstRatio = ratio
    }

    for (bLine in basmalahLines) {
      val glyphs = if (hasBasmalahTypeface && bLine.codeV2Glyphs.isNotBlank()) {
        bLine.codeV2Glyphs
      } else if (usesTagFallbackTajweed && bLine.textUthmani.isNotBlank()) {
        bLine.textUthmani
      } else {
        ""
      }
      if (glyphs.isNotBlank()) {
        basmalahPaint.textSize = fontSizePx
        val adv = basmalahPaint.measureText(glyphs)
        val (lOver, rOver) = measureTokenHorizontalOverhangs(basmalahPaint, glyphs, adv, tokenBounds)
        val rtlText = "\u200F$glyphs\u200F"
        val probeW = maxOf(safeWidthPx * 2, ceil(adv).toInt() + 128)
        val probeLayout = StaticLayout.Builder
          .obtain(rtlText, 0, rtlText.length, basmalahPaint, probeW)
          .setAlignment(Layout.Alignment.ALIGN_CENTER)
          .setTextDirection(TextDirectionHeuristics.RTL)
          .setIncludePad(true)
          .setMaxLines(2)
          .build()
        if (probeLayout.lineCount > 1) anyWrapped = true
        val actualVisualW = measureStaticLayoutLineSpanWidth(probeLayout) + lOver + rOver
        val ratio = actualVisualW / safeWidthF
        if (ratio > worstRatio) worstRatio = ratio
      }
    }

    return ExactLayoutCheckResult(
      fits = !anyWrapped && worstRatio <= 1.0f,
      anyWrapped = anyWrapped,
      worstRatio = worstRatio,
    )
  }

  internal fun doesPageFitFontSp(
    displayedLines: List<MushafLine>,
    candidateSp: Float,
    paint: TextPaint,
    basmalahPaint: TextPaint,
    hasBasmalahTypeface: Boolean,
    rosetteNumPaint: Paint,
    renderVectorTajweedMarkers: Boolean,
    usesTagFallbackTajweed: Boolean,
    safeWidthPx: Int,
    slotHeightPx: Float,
    density: Float,
    fontScale: Float,
    tokenBounds: Rect = Rect(),
    endMarkerPaint: TextPaint? = null,
  ): Boolean {
    val ayahLines = displayedLines.filterIsInstance<MushafLine.AyahWordsLine>().filter { it.words.isNotEmpty() }
    val basmalahLines = displayedLines.filterIsInstance<MushafLine.BasmalahLine>()
    if (ayahLines.isEmpty() && basmalahLines.isEmpty()) return true
    val lineTokens = Array(ayahLines.size) { lineIdx ->
      val words = ayahLines[lineIdx].words
      Array(words.size) { wordIdx ->
        val w = words[wordIdx]
        if (usesTagFallbackTajweed) w.textUthmani else w.codeV2
      }
    }
    val safeWidthF = safeWidthPx.coerceAtLeast(1).toFloat()
    if (!allLinesFitFast(
        candidateSp = candidateSp,
        ayahLines = ayahLines,
        lineTokens = lineTokens,
        basmalahLines = basmalahLines,
        paint = paint,
        basmalahPaint = basmalahPaint,
        hasBasmalahTypeface = hasBasmalahTypeface,
        renderVectorTajweedMarkers = renderVectorTajweedMarkers,
        usesTagFallbackTajweed = usesTagFallbackTajweed,
        safeWidthF = safeWidthF,
        slotHeightPx = slotHeightPx,
        density = density,
        fontScale = fontScale,
        tokenBounds = tokenBounds,
        endMarkerPaint = endMarkerPaint,
      )
    ) {
      return false
    }
    return checkExactStaticLayoutFit(
      candidateSp = candidateSp,
      ayahLines = ayahLines,
      lineTokens = lineTokens,
      basmalahLines = basmalahLines,
      paint = paint,
      basmalahPaint = basmalahPaint,
      hasBasmalahTypeface = hasBasmalahTypeface,
      rosetteNumPaint = rosetteNumPaint,
      renderVectorTajweedMarkers = renderVectorTajweedMarkers,
      usesTagFallbackTajweed = usesTagFallbackTajweed,
      goldInt = 0xFFC29038.toInt(),
      inkInt = android.graphics.Color.BLACK,
      effectiveDark = false,
      safeWidthPx = safeWidthPx,
      safeWidthF = safeWidthF,
      density = density,
      fontScale = fontScale,
      tokenBounds = tokenBounds,
      endMarkerPaint = endMarkerPaint,
    ).fits
  }

  internal fun computeUniformPageFontSp(
    displayedLines: List<MushafLine>,
    paint: TextPaint,
    basmalahPaint: TextPaint,
    hasBasmalahTypeface: Boolean,
    rosetteNumPaint: Paint,
    renderVectorTajweedMarkers: Boolean,
    usesTagFallbackTajweed: Boolean,
    goldInt: Int,
    inkInt: Int,
    effectiveDark: Boolean,
    safeWidthPx: Int,
    slotHeightPx: Float,
    density: Float,
    fontScale: Float,
    upperBoundSp: Float? = null,
    tokenBounds: Rect = Rect(),
    endMarkerPaint: TextPaint? = null,
  ): Float {
    val ayahLines = displayedLines.filterIsInstance<MushafLine.AyahWordsLine>().filter { it.words.isNotEmpty() }
    val basmalahLines = displayedLines.filterIsInstance<MushafLine.BasmalahLine>()
    val minSp = 6f
    val rawMaxSp = maxCandidateFontSp(slotHeightPx, density, fontScale)
    val maxSp = if (upperBoundSp != null) minOf(rawMaxSp, upperBoundSp).coerceAtLeast(minSp) else rawMaxSp
    if (ayahLines.isEmpty()) return maxSp.coerceAtMost(28f)

    val lineTokens = Array(ayahLines.size) { lineIdx ->
      val words = ayahLines[lineIdx].words
      Array(words.size) { wordIdx ->
        val w = words[wordIdx]
        if (usesTagFallbackTajweed) w.textUthmani else w.codeV2
      }
    }
    val safeWidthF = safeWidthPx.coerceAtLeast(1).toFloat()

    var resolvedSp = if (
      allLinesFitFast(
        candidateSp = maxSp,
        ayahLines = ayahLines,
        lineTokens = lineTokens,
        basmalahLines = basmalahLines,
        paint = paint,
        basmalahPaint = basmalahPaint,
        hasBasmalahTypeface = hasBasmalahTypeface,
        renderVectorTajweedMarkers = renderVectorTajweedMarkers,
        usesTagFallbackTajweed = usesTagFallbackTajweed,
        safeWidthF = safeWidthF,
        slotHeightPx = slotHeightPx,
        density = density,
        fontScale = fontScale,
        tokenBounds = tokenBounds,
        endMarkerPaint = endMarkerPaint,
      )
    ) {
      maxSp
    } else {
      var lo = minSp
      var hi = maxSp
      repeat(14) {
        val mid = (lo + hi) * 0.5f
        if (
          allLinesFitFast(
            candidateSp = mid,
            ayahLines = ayahLines,
            lineTokens = lineTokens,
            basmalahLines = basmalahLines,
            paint = paint,
            basmalahPaint = basmalahPaint,
            hasBasmalahTypeface = hasBasmalahTypeface,
            renderVectorTajweedMarkers = renderVectorTajweedMarkers,
            usesTagFallbackTajweed = usesTagFallbackTajweed,
            safeWidthF = safeWidthF,
            slotHeightPx = slotHeightPx,
            density = density,
            fontScale = fontScale,
            tokenBounds = tokenBounds,
            endMarkerPaint = endMarkerPaint,
          )
        ) {
          lo = mid
        } else {
          hi = mid
        }
      }
      lo.coerceAtLeast(minSp)
    }

    // Exact StaticLayout verification pass with real loaded Typeface so no shaped line/marker/diacritic ever overflows
    repeat(8) {
      val exact = checkExactStaticLayoutFit(
        candidateSp = resolvedSp,
        ayahLines = ayahLines,
        lineTokens = lineTokens,
        basmalahLines = basmalahLines,
        paint = paint,
        basmalahPaint = basmalahPaint,
        hasBasmalahTypeface = hasBasmalahTypeface,
        rosetteNumPaint = rosetteNumPaint,
        renderVectorTajweedMarkers = renderVectorTajweedMarkers,
        usesTagFallbackTajweed = usesTagFallbackTajweed,
        goldInt = goldInt,
        inkInt = inkInt,
        effectiveDark = effectiveDark,
        safeWidthPx = safeWidthPx,
        safeWidthF = safeWidthF,
        density = density,
        fontScale = fontScale,
        tokenBounds = tokenBounds,
        endMarkerPaint = endMarkerPaint,
      )
      if (exact.fits) {
        return resolvedSp
      }
      if (resolvedSp <= minSp) {
        return minSp
      }
      val nextSp = if (exact.anyWrapped) {
        resolvedSp * 0.94f
      } else {
        (resolvedSp * (0.992f / exact.worstRatio)).coerceAtMost(resolvedSp - 0.1f)
      }
      resolvedSp = nextSp.coerceAtLeast(minSp)
    }

    return resolvedSp.coerceAtLeast(minSp)
  }

  private fun drawAyahLineWithStaticLayout(
    canvas: Canvas,
    pageNumber: Int,
    spec: PageRenderSpec,
    line: MushafLine.AyahWordsLine,
    uniformPageSp: Float,
    paint: TextPaint,
    rosetteNumPaint: Paint,
    renderVectorTajweedMarkers: Boolean,
    usesTagFallbackTajweed: Boolean,
    goldInt: Int,
    inkInt: Int,
    effectiveDark: Boolean,
    safeLeft: Float,
    slotTop: Float,
    safeWidthPx: Int,
    contentWidthPx: Float,
    slotHeightPx: Float,
    density: Float,
    fontScale: Float,
    hitLefts: FloatArray,
    hitTops: FloatArray,
    hitRights: FloatArray,
    hitBottoms: FloatArray,
    hitVerseKeys: Array<String>,
    hitCodeV2Lines: Array<String>,
    startCursor: Int,
    verseRectAccum: HashMap<String, MutableList<Float>>,
    rosetteAccum: HashMap<String, MutableList<Float>>,
    endMarkerPaint: TextPaint? = null,
    parsedAyahTokensByVerse: Map<String, List<TajweedTagParser.TaggedToken>> = emptyMap(),
  ): Int {
    val words = line.words
    val n = words.size
    val tokens = Array(n) { idx ->
      val w = words[idx]
      if (usesTagFallbackTajweed) w.textUthmani else w.codeV2
    }

    val safeWidthF = safeWidthPx.coerceAtLeast(1).toFloat()
    val fittedSp = uniformPageSp
    val baseFontSizePx = fittedSp * density * fontScale
    val isTajweedEdition = spec.edition == MushafEdition.TAJWEED_V4

    val wordWidths = FloatArray(n)
    val wordDrawOffsets = FloatArray(n)
    val boundsRect = Rect()
    var markerWidthF = (baseFontSizePx * 1.05f).roundToInt().coerceAtLeast(1).toFloat()
    var markerHeightF = markerWidthF
    var sumWordWidths = 0f
    var rightOverhang = 0f
    var leftOverhang = 0f

    fun measureLineAtFontSize(targetFontSizePx: Float) {
      paint.textSize = targetFontSizePx
      paint.textAlign = Paint.Align.LEFT
      endMarkerPaint?.textSize = targetFontSizePx
      endMarkerPaint?.textAlign = Paint.Align.LEFT
      markerWidthF = (targetFontSizePx * 1.05f).roundToInt().coerceAtLeast(1).toFloat()
      markerHeightF = markerWidthF
      sumWordWidths = 0f
      rightOverhang = 0f
      leftOverhang = 0f
      for (i in 0 until n) {
        val w = words[i]
        val isTajweedEndMarker = isTajweedEdition && w.isEndMarker
        if (isTajweedEndMarker && endMarkerPaint != null && w.codeV2.isNotBlank()) {
          val box = measureWordBox(endMarkerPaint, w.codeV2, boundsRect)
          wordWidths[i] = box.widthPx
          wordDrawOffsets[i] = box.drawOffsetX
          if (i == 0) rightOverhang = box.rightOverhangPx
          if (i == n - 1) leftOverhang = box.leftOverhangPx
        } else if (renderVectorTajweedMarkers && w.isEndMarker) {
          wordWidths[i] = markerWidthF
          wordDrawOffsets[i] = 0f
        } else {
          val box = measureWordBox(paint, tokens[i], boundsRect)
          wordWidths[i] = box.widthPx
          wordDrawOffsets[i] = box.drawOffsetX
          if (i == 0) rightOverhang = box.rightOverhangPx
          if (i == n - 1) leftOverhang = box.leftOverhangPx
        }
        sumWordWidths += wordWidths[i]
      }
    }

    var lineFontSizePx = baseFontSizePx
    measureLineAtFontSize(lineFontSizePx)

    // Overflow-only guard: when TAJWEED_V4 shares MADINAH_1421's exact global font size,
    // a handful of Tajweed lines contain an extra word or slightly wider glyph; scale ONLY
    // that overflowing line down so no word ever clips past the inner frame.
    val minRequiredInterWordPx = if (n > 1) (n - 1).toFloat() else 0f
    if (sumWordWidths + leftOverhang + rightOverhang + minRequiredInterWordPx > safeWidthF && n >= 1) {
      var lo = (baseFontSizePx * 0.50f).coerceAtLeast(1f)
      var hi = baseFontSizePx
      repeat(8) {
        val mid = (lo + hi) * 0.5f
        measureLineAtFontSize(mid)
        val minGapMid = (mid * 0.04f).roundToInt().coerceAtLeast(1) * (n - 1).coerceAtLeast(0)
        if (sumWordWidths + leftOverhang + rightOverhang + minGapMid <= safeWidthF) {
          lo = mid
        } else {
          hi = mid
        }
      }
      lineFontSizePx = lo
      measureLineAtFontSize(lineFontSizePx)
    }
    val lineFontSp = lineFontSizePx / (density * fontScale).coerceAtLeast(0.01f)

    val naturalGapPx = (lineFontSizePx * 0.11f).roundToInt().coerceAtLeast(2)
    val gaps = IntArray((n - 1).coerceAtLeast(0)) { naturalGapPx }

    // Follow the layout's own `centered` flag on all pages:
    // centered = 1 stays centered at its natural width with natural word spacing;
    // centered = 0 justifies by widening the gaps between words only.
    val justifyEdgeToEdge = !line.isCentered && n >= 2
    if (justifyEdgeToEdge) {
      val availableAdvanceW = (safeWidthF - leftOverhang - rightOverhang)
        .coerceAtLeast(sumWordWidths)
      val totalGap = (availableAdvanceW - sumWordWidths).coerceAtLeast(0f).toDouble()
      var prevCum = 0
      for (i in 0 until n - 1) {
        val cum = ((totalGap * (i + 1)) / (n - 1)).roundToInt()
        gaps[i] = (cum - prevCum).coerceAtLeast(0)
        prevCum = cum
      }
    } else if (n > 1) {
      val availableForNaturalGaps = (safeWidthF - leftOverhang - rightOverhang - sumWordWidths)
        .coerceAtLeast(0f)
      val maxSafeGap = (availableForNaturalGaps / (n - 1)).toInt().coerceAtLeast(0)
      val clampedGap = naturalGapPx.coerceAtMost(maxSafeGap)
      for (i in 0 until n - 1) {
        gaps[i] = clampedGap
      }
    }

    var sumGaps = 0
    for (g in gaps) sumGaps += g
    var totalAdvanceW = sumWordWidths + sumGaps.toFloat()
    var actualVisualW = totalAdvanceW + leftOverhang + rightOverhang

    if (actualVisualW > safeWidthF + 0.5f && n >= 2 && sumGaps > 0) {
      val trimTotal = ceil((actualVisualW - safeWidthF).toDouble()).toInt().coerceAtMost(sumGaps)
      val newTotalGap = (sumGaps - trimTotal).coerceAtLeast(0)
      var prevCum = 0
      for (i in 0 until n - 1) {
        val cum = ((newTotalGap.toDouble() * (i + 1)) / (n - 1)).roundToInt()
        gaps[i] = (cum - prevCum).coerceAtLeast(0)
        prevCum = cum
      }
      sumGaps = gaps.sum()
      totalAdvanceW = sumWordWidths + sumGaps.toFloat()
      actualVisualW = totalAdvanceW + leftOverhang + rightOverhang
    }

    logDebugOverflowIfNeeded(
      pageNumber = pageNumber,
      lineNumber = line.lineNumber,
      spec = spec,
      fontSp = lineFontSp,
      visualWidthPx = actualVisualW,
      contentWidthPx = contentWidthPx,
      safeWidthPx = safeWidthPx,
      lineCount = 1,
    )

    val startRightX = if (justifyEdgeToEdge) {
      safeLeft + safeWidthF - rightOverhang
    } else {
      val targetCenter = safeLeft + safeWidthF * 0.5f
      val visualCenterOffsetFromRight = (rightOverhang - totalAdvanceW - leftOverhang) * 0.5f
      val idealRightX = targetCenter - visualCenterOffsetFromRight
      val minRightX = safeLeft + leftOverhang + totalAdvanceW
      val maxRightX = safeLeft + safeWidthF - rightOverhang
      if (minRightX <= maxRightX) {
        idealRightX.coerceIn(minRightX, maxRightX)
      } else {
        (minRightX + maxRightX) * 0.5f
      }
    }

    val fm = paint.fontMetrics
    val layoutLineH = fm.bottom - fm.top
    val baselineY = slotTop + (slotHeightPx - layoutLineH) * 0.5f - fm.top
    val textVisualH = (fm.descent - fm.ascent).coerceAtLeast(markerHeightF)
    val boxTop = (slotTop + (slotHeightPx - textVisualH) * 0.5f).coerceAtLeast(slotTop)
    val boxBottom = (boxTop + textVisualH).coerceAtMost(slotTop + slotHeightPx)

    // Record exact word hit boxes and per-line merged verse highlight rects
    val codeV2ByVerse = HashMap<String, String>()
    for (i in 0 until n) {
      val vk = words[i].verseKey
      val existing = codeV2ByVerse[vk]
      codeV2ByVerse[vk] = if (existing == null) words[i].codeV2 else "$existing ${words[i].codeV2}"
    }

    var rightCursor = startRightX
    var cursor = startCursor
    var currentSegmentVerse: String? = null
    var segMinL = 0f
    var segMinT = 0f
    var segMaxR = 0f
    var segMaxB = 0f

    fun flushHighlightSegment() {
      val vk = currentSegmentVerse ?: return
      val list = verseRectAccum.getOrPut(vk) { ArrayList(4) }
      list.add(segMinL)
      list.add(segMinT)
      list.add(segMaxR)
      list.add(segMaxB)
      currentSegmentVerse = null
    }

    val rosetteCy = slotTop + slotHeightPx * 0.5f
    for (i in 0 until n) {
      if (i > 0) rightCursor -= gaps[i - 1].toFloat()
      val ww = wordWidths[i]
      val wordRight = rightCursor
      val wordLeft = wordRight - ww
      rightCursor = wordLeft

      val w = words[i]
      val isTajweedEndMarker = isTajweedEdition && w.isEndMarker
      if (isTajweedEndMarker && endMarkerPaint != null && w.codeV2.isNotBlank()) {
        canvas.drawText(w.codeV2, wordLeft + wordDrawOffsets[i], baselineY, endMarkerPaint)
        val cx = (wordLeft + wordRight) * 0.5f
        val slotDim = minOf(ww, textVisualH)
        val slotArr = rosetteAccum.getOrPut(w.verseKey) { ArrayList(3) }
        slotArr.add(cx)
        slotArr.add(rosetteCy)
        slotArr.add(slotDim)
      } else if (renderVectorTajweedMarkers && w.isEndMarker) {
        val cx = (wordLeft + wordRight) * 0.5f
        drawMadinahStyleFallbackMarker(
          canvas = canvas,
          cx = cx,
          cy = rosetteCy,
          markerWidthF = markerWidthF,
          markerHeightF = markerHeightF,
          arabicDigits = toArabicIndicDigits(w.ayahNumber),
          lineFontSp = lineFontSp,
          inkInt = inkInt,
          numPaint = rosetteNumPaint,
          density = density,
          fontScale = fontScale,
        )
        val minDim = minOf(markerWidthF, markerHeightF)
        val slotArr = rosetteAccum.getOrPut(w.verseKey) { ArrayList(3) }
        slotArr.add(cx)
        slotArr.add(rosetteCy)
        slotArr.add(minDim)
      } else {
        val tok = tokens[i]
        if (tok.isNotEmpty()) {
          val coloredWord = if (usesTagFallbackTajweed) {
            TajweedTagParser.buildWordSpannable(
              wordTextUthmani = tok,
              wordPosition1Based = w.position,
              ayahTokens = parsedAyahTokensByVerse[w.verseKey],
            )
          } else {
            tok
          }
          if (coloredWord is android.text.Spannable) {
            val layoutW = ceil(ww).toInt().coerceAtLeast(1) + 64
            val wordLayout = StaticLayout.Builder
              .obtain(coloredWord, 0, coloredWord.length, paint, layoutW)
              .setAlignment(Layout.Alignment.ALIGN_NORMAL)
              .setTextDirection(TextDirectionHeuristics.RTL)
              .setIncludePad(false)
              .build()
            val drawLeftInLayout = if (wordLayout.lineCount > 0) wordLayout.getLineLeft(0) else 0f
            val layoutBaseline = if (wordLayout.lineCount > 0) wordLayout.getLineBaseline(0).toFloat() else baselineY
            canvas.save()
            canvas.translate(wordLeft + wordDrawOffsets[i] - drawLeftInLayout, baselineY - layoutBaseline)
            wordLayout.draw(canvas)
            canvas.restore()
          } else {
            canvas.drawText(tok, wordLeft + wordDrawOffsets[i], baselineY, paint)
          }
        }
      }

      val vk = w.verseKey
      if (cursor < hitLefts.size) {
        hitLefts[cursor] = wordLeft
        hitTops[cursor] = boxTop
        hitRights[cursor] = wordRight
        hitBottoms[cursor] = boxBottom
        hitVerseKeys[cursor] = vk
        hitCodeV2Lines[cursor] = codeV2ByVerse[vk].orEmpty()
        cursor++
      }

      if (currentSegmentVerse == vk) {
        if (wordLeft < segMinL) segMinL = wordLeft
        if (boxTop < segMinT) segMinT = boxTop
        if (wordRight > segMaxR) segMaxR = wordRight
        if (boxBottom > segMaxB) segMaxB = boxBottom
      } else {
        flushHighlightSegment()
        currentSegmentVerse = vk
        segMinL = wordLeft
        segMinT = boxTop
        segMaxR = wordRight
        segMaxB = boxBottom
      }
    }
    flushHighlightSegment()

    return cursor
  }

  private fun drawMadinahStyleFallbackMarker(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    markerWidthF: Float,
    markerHeightF: Float,
    arabicDigits: String,
    lineFontSp: Float,
    inkInt: Int,
    numPaint: Paint,
    density: Float,
    fontScale: Float,
  ) {
    val outerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = inkInt
    }
    val innerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = withAlpha(inkInt, 0.78f)
    }
    val minDim = minOf(markerWidthF, markerHeightF)
    val outerR = minDim * 0.45f
    val innerR = minDim * 0.34f
    val scallopCount = 16
    val scallopPath = Path()
    for (i in 0 until scallopCount) {
      val angle = (2.0 * PI * i) / scallopCount - PI / 2.0
      val r = if (i % 2 == 0) outerR else minDim * 0.40f
      val px = cx + (r * cos(angle)).toFloat()
      val py = cy + (r * sin(angle)).toFloat()
      if (i == 0) scallopPath.moveTo(px, py) else scallopPath.lineTo(px, py)
    }
    scallopPath.close()

    outerStroke.strokeWidth = (minDim * 0.048f).coerceIn(0.8f, 1.8f)
    canvas.drawPath(scallopPath, outerStroke)

    innerStroke.strokeWidth = (minDim * 0.036f).coerceIn(0.6f, 1.3f)
    canvas.drawCircle(cx, cy, innerR, innerStroke)

    val digitScale = when (arabicDigits.length) {
      1 -> 0.44f
      2 -> 0.38f
      else -> 0.32f
    }
    val numSp = (lineFontSp * digitScale).coerceIn(7.0f, 17f)
    numPaint.textSize = numSp * density * fontScale
    numPaint.color = inkInt
    val fm = numPaint.fontMetrics
    val textBaseline = cy - (fm.ascent + fm.descent) * 0.5f
    canvas.drawText(arabicDigits, cx, textBaseline, numPaint)
  }
}
