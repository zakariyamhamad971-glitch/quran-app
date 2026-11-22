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
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.ReplacementSpan
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.data.pdf.PdfRenderedPageBitmap
import com.example.data.prefs.BackgroundTheme
import com.example.data.quran.MushafEdition
import com.example.data.quran.MushafLine
import com.example.data.quran.MushafPageData
import com.example.data.quran.PdfPageHeaderMeta
import com.example.data.quran.SurahMeta
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
  val layoutVersion: Int = com.example.data.quran.QuranRepository.LAYOUT_CACHE_VERSION,
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
  val safeBmpW = bmpWidthPx.coerceAtLeast(1).toFloat()
  val safeBmpH = bmpHeightPx.coerceAtLeast(1).toFloat()

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

  // 3. Surah banner sits directly below the label band
  val bannerGapPx = 2.5f * densityPxPerDp.coerceAtLeast(0.5f)
  val bannerIdealH = frameW / surahBannerAspectRatio.coerceAtLeast(1f)
  val bannerH = if (hasTopSurahBanner) minOf(bannerIdealH, midH * 0.16f).coerceAtLeast(1f) else 0f
  val bannerTop = if (hasTopSurahBanner) midTop + bannerGapPx else 0f
  val bannerLeft = frameLeft

  val textAvailTop = if (hasTopSurahBanner) {
    (bannerTop + bannerH + bannerGapPx).coerceAtMost(midBottom - 1f)
  } else {
    midTop
  }
  val textAvailH = (midBottom - textAvailTop).coerceAtLeast(1f)

  // 4. Text scaling: widest line fills ~97% of frame width (zoom capped at 2.4x, >= 1.0x)
  val widestLineW = (segmentation.widestLineWidthFraction * safeBmpW).coerceAtLeast(1f)
  val unzoomedScale = frameW / safeBmpW
  val fill97Scale = (0.97f * frameW) / widestLineW
  val targetScale = fill97Scale.coerceIn(unzoomedScale, unzoomedScale * 2.4f)

  val isFewLines = segmentation.isFewLines && segmentation.lines.isNotEmpty()

  if (isFewLines) {
    val lineCount = segmentation.lines.size
    val slotH = textAvailH / lineCount.toFloat()
    val lineMetrics = segmentation.lines.mapIndexed { idx, lineStrip ->
      val srcTop = (lineStrip.srcTopFraction * safeBmpH).roundToInt().coerceIn(0, bmpHeightPx - 1)
      val srcBot = (lineStrip.srcBottomFraction * safeBmpH).roundToInt().coerceIn(srcTop + 1, bmpHeightPx)
      val srcH = (srcBot - srcTop).coerceAtLeast(1)

      val srcLeft = (segmentation.textMinXFraction * safeBmpW).roundToInt().coerceIn(0, bmpWidthPx - 1)
      val srcRight = (segmentation.textMaxXFraction * safeBmpW).roundToInt().coerceIn(srcLeft + 1, bmpWidthPx)
      val srcW = (srcRight - srcLeft).coerceAtLeast(1)

      val lineScale = minOf(targetScale, (slotH * 0.94f) / srcH.toFloat()).coerceAtLeast(0.001f)
      val dstW = (srcW * lineScale).roundToInt().coerceIn(1, frameW.roundToInt())
      val dstH = (srcH * lineScale).roundToInt().coerceIn(1, slotH.roundToInt().coerceAtLeast(1))
      val dstLeft = (frameLeft + (frameW - dstW) * 0.5f).roundToInt()
      val slotTop = textAvailTop + idx * slotH
      val dstTop = (slotTop + (slotH - dstH) * 0.5f).roundToInt()

      PdfLineDestinationMetrics(
        srcLeftPx = srcLeft,
        srcTopPx = srcTop,
        srcWidthPx = srcW,
        srcHeightPx = srcH,
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
      textDstLeft = frameLeft.roundToInt(),
      textDstTop = textAvailTop.roundToInt(),
      textDstWidth = frameW.roundToInt(),
      textDstHeight = textAvailH.roundToInt(),
      bannerLeft = bannerLeft,
      bannerTop = bannerTop,
      bannerWidth = if (hasTopSurahBanner) frameW else 0f,
      bannerHeight = bannerH,
      appliedZoomRelativeToNatural = targetScale / unzoomedScale,
      isFewLines = true,
      lineLayouts = lineMetrics,
    )
  }

  // Normal full page: single text block scaled to fill frame width and height
  val srcTop = (segmentation.labelCutFraction * safeBmpH).roundToInt().coerceIn(0, bmpHeightPx - 1)
  val srcBot = (segmentation.legendCutFraction * safeBmpH).roundToInt().coerceIn(srcTop + 1, bmpHeightPx)
  val srcH = (srcBot - srcTop).coerceAtLeast(1)

  val srcLeft = (segmentation.textMinXFraction * safeBmpW).roundToInt().coerceIn(0, bmpWidthPx - 1)
  val srcRight = (segmentation.textMaxXFraction * safeBmpW).roundToInt().coerceIn(srcLeft + 1, bmpWidthPx)
  val srcW = (srcRight - srcLeft).coerceAtLeast(1)

  val maxScaleH = textAvailH / srcH.toFloat()
  val finalScale = minOf(targetScale, maxScaleH).coerceAtLeast(0.001f)
  val textDstW = (srcW * finalScale).roundToInt().coerceIn(1, frameW.roundToInt())
  val textDstH = (srcH * finalScale).roundToInt().coerceIn(1, textAvailH.roundToInt())
  val textDstLeft = (frameLeft + (frameW - textDstW) * 0.5f).roundToInt()
  val textDstTop = (textAvailTop + (textAvailH - textDstH) * 0.5f).roundToInt()

  return PdfPageLayoutMetrics(
    topBandDstLeft = topBandDstLeft,
    topBandDstTop = topBandDstTop,
    topBandDstWidth = topBandDstWidth,
    topBandDstHeight = topBandDstHeight,
    bottomBandDstLeft = bottomBandDstLeft,
    bottomBandDstTop = bottomBandDstTop,
    bottomBandDstWidth = bottomBandDstWidth,
    bottomBandDstHeight = bottomBandDstHeight,
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
)

class MushafPageBitmapRenderer(
  private val context: Context,
) {
  companion object {
    private const val MIN_CACHE_BYTES = 16 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 96 * 1024 * 1024
    private const val DEFAULT_CACHE_BYTES = 48 * 1024 * 1024

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

  private fun makeKey(pageNumber: Int, spec: PageRenderSpec): PageBitmapKey =
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
    )

  private fun ensureCacheCapacityFor(spec: PageRenderSpec) {
    val pageBytes = spec.widthPx.toLong() * spec.heightPx.toLong() * 4L
    val quarterHeap = (Runtime.getRuntime().maxMemory() / 4L).coerceAtMost(MAX_CACHE_BYTES.toLong())
    val desired = (pageBytes * 8L).coerceIn(MIN_CACHE_BYTES.toLong(), quarterHeap.coerceAtLeast(MIN_CACHE_BYTES.toLong())).toInt()
    if (desired != lastConfiguredCacheBytes) {
      synchronized(this) {
        if (desired != lastConfiguredCacheBytes) {
          bitmapCache.resize(desired)
          lastConfiguredCacheBytes = desired
        }
      }
    }
  }

  fun getCached(pageNumber: Int, spec: PageRenderSpec): RenderedMushafPage? {
    if (!spec.isValid) return null
    return bitmapCache.get(makeKey(pageNumber, spec))
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

    val padH = dp(9f)
    val padV = dp(7f)
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

  suspend fun renderPdfPageOrGet(
    pageNumber: Int,
    headerMeta: PdfPageHeaderMeta?,
    renderedPdf: PdfRenderedPageBitmap,
    topSurah: SurahMeta?,
    spec: PageRenderSpec,
    surahsById: Map<Int, SurahMeta>,
  ): RenderedMushafPage {
    val key = makeKey(pageNumber, spec)
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
  ): RenderedMushafPage {
    val key = makeKey(pageData.pageNumber, spec)
    bitmapCache.get(key)?.let { return it }

    return withContext(Dispatchers.Default) {
      bitmapCache.get(key)?.let { return@withContext it }
      ensureCacheCapacityFor(spec)
      ensureUiFontsLoaded()

      val rendered = renderPageToBitmap(
        pageData = pageData,
        spec = spec,
        pageTypeface = pageTypeface,
        basmalahTypeface = basmalahTypeface,
        usesTagFallbackTajweed = usesTagFallbackTajweed,
        surahsById = surahsById,
      )
      bitmapCache.put(key, rendered)
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
  )

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
    val frameAccentInt = if (effectiveDark) withAlpha(goldInt, 0.65f) else withAlpha(0xFF12403C.toInt(), 0.55f)
    val outlineCornerInt = if (effectiveDark) withAlpha(0xFF28322F.toInt(), 0.65f) else withAlpha(0xFFD9D5C9.toInt(), 0.65f)

    val amiriNormal = cachedAmiriNormal ?: Typeface.SERIF
    val amiriBold = cachedAmiriBold ?: amiriNormal
    val ibmNormal = cachedIbmNormal ?: Typeface.SANS_SERIF
    val ibmMedium = cachedIbmMedium ?: ibmNormal

    // 1. Paper surface (rounded card corners) + crisp double Mushaf frame
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    val cardCorner = dp(10f)
    fillPaint.color = paperInt
    canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), cardCorner, cardCorner, fillPaint)

    val outerMargin = dp(3.5f)
    val innerMargin = dp(6.5f)
    val outerCorner = dp(7f)
    val innerCorner = dp(5f)

    strokePaint.color = withAlpha(goldInt, 0.85f)
    strokePaint.strokeWidth = dp(1.4f)
    canvas.drawRoundRect(
      RectF(outerMargin, outerMargin, w - outerMargin, h - outerMargin),
      outerCorner,
      outerCorner,
      strokePaint,
    )

    strokePaint.color = frameAccentInt
    strokePaint.strokeWidth = dp(0.75f)
    canvas.drawRoundRect(
      RectF(innerMargin, innerMargin, w - innerMargin, h - innerMargin),
      innerCorner,
      innerCorner,
      strokePaint,
    )

    val dotRadius = dp(2.2f)
    val ringRadius = dp(12f) * 0.24f
    val cornerXs = floatArrayOf(innerMargin, w - innerMargin, innerMargin, w - innerMargin)
    val cornerYs = floatArrayOf(innerMargin, innerMargin, h - innerMargin, h - innerMargin)
    for (c in 0 until 4) {
      fillPaint.color = goldInt
      canvas.drawCircle(cornerXs[c], cornerYs[c], dotRadius, fillPaint)
      strokePaint.color = outlineCornerInt
      strokePaint.strokeWidth = dp(0.75f)
      canvas.drawCircle(cornerXs[c], cornerYs[c], ringRadius, strokePaint)
    }

    // 2. Inner content box matching horizontal = 9.dp, vertical = 7.dp
    val padH = dp(9f)
    val padV = dp(7f)
    val contentLeft = padH
    val contentTop = padV
    val contentRight = w - padH
    val contentBottom = h - padV
    val contentWidth = (contentRight - contentLeft).coerceAtLeast(1f)

    // 3. Top Header Row (Surah Name on RTL-right, Juz/Hizb Pill on RTL-left)
    val surahHeaderNames = surahIds.mapNotNull { surahsById[it]?.nameArabic }.joinToString(" • ")
    val headerTitle = if (surahHeaderNames.isNotBlank()) "سورة $surahHeaderNames" else ""
    val juzHizbText = if (juzNumber != null && hizbNumber != null && juzNumber > 0 && hizbNumber > 0) {
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
        var hintText = "اضغط مطولاً على الآية للتفسير"
        hintPaint.textSize = minOf(sp(6.8f), dp(8.5f))
        if (hintPaint.measureText(hintText) > maxHintW) {
          hintPaint.textSize = minOf(sp(6.2f), dp(7.6f))
        }
        if (hintPaint.measureText(hintText) > maxHintW) {
          hintText = "اضغط مطولاً للتفسير"
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
    )
  }

  private fun renderPageToBitmap(
    pageData: MushafPageData,
    spec: PageRenderSpec,
    pageTypeface: Typeface?,
    basmalahTypeface: Typeface?,
    usesTagFallbackTajweed: Boolean,
    surahsById: Map<Int, SurahMeta>,
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
    val bodyWidthInt = contentWidth.roundToInt().coerceAtLeast(1)

    val displayedLines = if (pageData.pageNumber in 1..2) {
      pageData.lines.dropLastWhile { it is MushafLine.EmptyLine }
    } else {
      pageData.lines
    }
    val lineCount = displayedLines.size.coerceAtLeast(1)
    val slotHeight = bodyHeight / lineCount.toFloat()

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

    val renderVectorTajweedMarkers = spec.edition == MushafEdition.TAJWEED_V4
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

    val isFramedIntroPage = pageData.pageNumber in 1..2
    val isTwoPageMode = spec.isTwoPageMode
    val uniformPageSp = computeUniformPageFontSp(
      displayedLines = displayedLines,
      paint = lineTextPaint,
      renderVectorTajweedMarkers = renderVectorTajweedMarkers,
      usesTagFallbackTajweed = usesTagFallbackTajweed,
      isFramedIntroPage = isFramedIntroPage,
      isTwoPageMode = isTwoPageMode,
      widthPx = bodyWidthInt,
      slotHeightPx = slotHeight,
      density = d,
      fontScale = fs,
    )

    for (slotIndex in displayedLines.indices) {
      val line = displayedLines[slotIndex]
      val slotTop = bodyTop + slotIndex * slotHeight

      when (line) {
        is MushafLine.EmptyLine -> Unit

        is MushafLine.SurahHeaderLine -> {
          SurahHeaderOrnamentRenderer.drawSurahHeaderBand(
            canvas = canvas,
            surah = line.surah,
            edition = spec.edition,
            effectiveDark = effectiveDark,
            left = bodyLeft,
            top = slotTop,
            width = contentWidth,
            height = slotHeight,
            amiriBold = amiriBold,
            ibmMedium = ibmMedium,
            surahNameV4 = surahNameV4,
            quranCommon = quranCommon,
            dpFn = ::dp,
          )
        }

        is MushafLine.BasmalahLine -> {
          if (basmalahTypeface != null && line.codeV2Glyphs.isNotBlank()) {
            drawBasmalahStaticLayout(
              canvas = canvas,
              glyphs = line.codeV2Glyphs,
              paint = basmalahPaint,
              left = bodyLeft,
              slotTop = slotTop,
              widthPx = bodyWidthInt,
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
            line = line,
            uniformPageSp = uniformPageSp,
            isFramedIntroPage = isFramedIntroPage,
            isTwoPageMode = isTwoPageMode,
            paint = lineTextPaint,
            rosetteNumPaint = rosetteNumPaint,
            renderVectorTajweedMarkers = renderVectorTajweedMarkers,
            usesTagFallbackTajweed = usesTagFallbackTajweed,
            goldInt = goldInt,
            inkInt = inkInt,
            effectiveDark = effectiveDark,
            left = bodyLeft,
            slotTop = slotTop,
            widthPx = bodyWidthInt,
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
    )
  }

  private fun drawBasmalahStaticLayout(
    canvas: Canvas,
    glyphs: String,
    paint: TextPaint,
    left: Float,
    slotTop: Float,
    widthPx: Int,
    slotHeightPx: Float,
    density: Float,
    fontScale: Float,
  ) {
    val maxSp = ((slotHeightPx * 0.68f) / (density * fontScale)).coerceIn(13f, 38f)
    var lo = 10f
    var hi = maxSp

    fun fits(candidateSp: Float): Boolean {
      paint.textSize = candidateSp * density * fontScale
      val w = paint.measureText(glyphs)
      val fm = paint.fontMetrics
      val h = fm.descent - fm.ascent
      return w <= widthPx.toFloat() && h <= slotHeightPx * 0.96f
    }

    val resolvedSp = if (fits(hi)) {
      hi
    } else {
      repeat(7) {
        val mid = (lo + hi) * 0.5f
        if (fits(mid)) lo = mid else hi = mid
      }
      lo.coerceAtLeast(10f)
    }

    paint.textSize = resolvedSp * density * fontScale
    val rtlText = "\u200F$glyphs\u200F"
    val layout = StaticLayout.Builder
      .obtain(rtlText, 0, rtlText.length, paint, widthPx)
      .setAlignment(Layout.Alignment.ALIGN_CENTER)
      .setTextDirection(TextDirectionHeuristics.RTL)
      .setIncludePad(true)
      .setMaxLines(1)
      .build()

    val offsetY = slotTop + (slotHeightPx - layout.height) * 0.5f
    canvas.save()
    canvas.translate(left, offsetY)
    layout.draw(canvas)
    canvas.restore()
  }

  private fun computeUniformPageFontSp(
    displayedLines: List<MushafLine>,
    paint: TextPaint,
    renderVectorTajweedMarkers: Boolean,
    usesTagFallbackTajweed: Boolean,
    isFramedIntroPage: Boolean,
    isTwoPageMode: Boolean,
    widthPx: Int,
    slotHeightPx: Float,
    density: Float,
    fontScale: Float,
  ): Float {
    val ayahLines = displayedLines.filterIsInstance<MushafLine.AyahWordsLine>().filter { it.words.isNotEmpty() }
    val maxSp = ((slotHeightPx * 1.45f) / (density * fontScale)).coerceIn(12f, 84f)
    val minSp = 8.5f
    if (ayahLines.isEmpty()) return maxSp.coerceAtMost(28f)

    val lineTokens = Array(ayahLines.size) { lineIdx ->
      val words = ayahLines[lineIdx].words
      Array(words.size) { wordIdx ->
        val w = words[wordIdx]
        if (usesTagFallbackTajweed) w.textUthmani else w.codeV2
      }
    }
    val tokenBounds = Rect()
    val effectiveWidthPx = (widthPx - 2).coerceAtLeast(1).toFloat()

    // For pages 1 & 2 (framed short Surahs), preserve original uniform longest-line fit untouched
    if (isFramedIntroPage) {
      fun allFramedLinesFitAt(candidateSp: Float): Boolean {
        val fontSizePx = candidateSp * density * fontScale
        paint.textSize = fontSizePx
        val minGapPx = (fontSizePx * 0.14f).coerceAtLeast(2.5f)
        val fm = paint.fontMetrics
        val layoutLineH = fm.bottom - fm.top
        val baselineInSlot = (slotHeightPx - layoutLineH) * 0.5f - fm.top

        var inkMinTop = Int.MAX_VALUE
        var inkMaxBottom = Int.MIN_VALUE
        var hasMeasuredInk = false
        var maxMarkerH = 0f

        for (lineIdx in ayahLines.indices) {
          val words = ayahLines[lineIdx].words
          val tokens = lineTokens[lineIdx]
          val n = words.size
          var sumW = 0f

          for (i in 0 until n) {
            val w = words[i]
            if (renderVectorTajweedMarkers && w.isEndMarker) {
              sumW += fontSizePx * 1.32f
              val mh = fontSizePx * 1.18f
              if (mh > maxMarkerH) maxMarkerH = mh
            } else {
              val tok = tokens[i]
              sumW += paint.measureText(tok)
              if (tok.isNotEmpty()) {
                paint.getTextBounds(tok, 0, tok.length, tokenBounds)
                if (tokenBounds.top < inkMinTop) inkMinTop = tokenBounds.top
                if (tokenBounds.bottom > inkMaxBottom) inkMaxBottom = tokenBounds.bottom
                hasMeasuredInk = true
              }
            }
          }

          val totalMinW = sumW + minGapPx * (n - 1).coerceAtLeast(0)
          if (totalMinW > widthPx.toFloat()) return false
        }

        if (maxMarkerH > slotHeightPx * 0.95f) return false
        if (hasMeasuredInk) {
          val inkH = (inkMaxBottom - inkMinTop).toFloat()
          if (inkH > slotHeightPx * 0.90f) return false
          val inkTopInSlot = baselineInSlot + inkMinTop.toFloat()
          val inkBottomInSlot = baselineInSlot + inkMaxBottom.toFloat()
          if (inkTopInSlot < slotHeightPx * 0.02f || inkBottomInSlot > slotHeightPx * 0.98f) {
            return false
          }
        } else {
          if ((fm.descent - fm.ascent) > slotHeightPx * 1.05f) return false
        }
        return true
      }

      if (allFramedLinesFitAt(maxSp)) return maxSp
      var lo = minSp
      var hi = maxSp
      repeat(12) {
        val mid = (lo + hi) * 0.5f
        if (allFramedLinesFitAt(mid)) lo = mid else hi = mid
      }
      return lo.coerceAtLeast(minSp)
    }

    // Single-page mode: restore clean uniform font size across all lines based on longest line fit
    if (!isTwoPageMode) {
      fun allSinglePageLinesFitAt(candidateSp: Float): Boolean {
        val fontSizePx = candidateSp * density * fontScale
        paint.textSize = fontSizePx
        val minGapPx = (fontSizePx * 0.14f).coerceAtLeast(2.5f)
        val fm = paint.fontMetrics
        val layoutLineH = fm.bottom - fm.top
        val baselineInSlot = (slotHeightPx - layoutLineH) * 0.5f - fm.top

        var inkMinTop = Int.MAX_VALUE
        var inkMaxBottom = Int.MIN_VALUE
        var hasMeasuredInk = false
        var maxMarkerH = 0f

        for (lineIdx in ayahLines.indices) {
          val words = ayahLines[lineIdx].words
          val tokens = lineTokens[lineIdx]
          val n = words.size
          var sumW = 0f

          for (i in 0 until n) {
            val w = words[i]
            if (renderVectorTajweedMarkers && w.isEndMarker) {
              sumW += fontSizePx * 1.32f
              val mh = fontSizePx * 1.18f
              if (mh > maxMarkerH) maxMarkerH = mh
            } else {
              val tok = tokens[i]
              sumW += paint.measureText(tok)
              if (tok.isNotEmpty()) {
                paint.getTextBounds(tok, 0, tok.length, tokenBounds)
                if (tokenBounds.top < inkMinTop) inkMinTop = tokenBounds.top
                if (tokenBounds.bottom > inkMaxBottom) inkMaxBottom = tokenBounds.bottom
                hasMeasuredInk = true
              }
            }
          }

          val totalMinW = sumW + minGapPx * (n - 1).coerceAtLeast(0)
          if (totalMinW > effectiveWidthPx) return false
        }

        if (maxMarkerH > slotHeightPx * 0.95f) return false
        if (hasMeasuredInk) {
          val inkH = (inkMaxBottom - inkMinTop).toFloat()
          if (inkH > slotHeightPx * 0.90f) return false
          val inkTopInSlot = baselineInSlot + inkMinTop.toFloat()
          val inkBottomInSlot = baselineInSlot + inkMaxBottom.toFloat()
          if (inkTopInSlot < slotHeightPx * 0.02f || inkBottomInSlot > slotHeightPx * 0.98f) {
            return false
          }
        } else {
          if ((fm.descent - fm.ascent) > slotHeightPx * 1.05f) return false
        }
        return true
      }

      if (allSinglePageLinesFitAt(maxSp)) return maxSp
      var lo = minSp
      var hi = maxSp
      repeat(12) {
        val mid = (lo + hi) * 0.5f
        if (allSinglePageLinesFitAt(mid)) lo = mid else hi = mid
      }
      return lo.coerceAtLeast(minSp)
    }

    // Two-page mode: sized with 25% font enlargement and up to 10% outlier shrink
    val bodyLineIndices = ayahLines.indices.filter { ayahLines[it].words.size >= 4 }
      .ifEmpty { ayahLines.indices.toList() }
    val isBodyLine = BooleanArray(ayahLines.size)
    for (idx in bodyLineIndices) isBodyLine[idx] = true

    fun twoPageUniformFitsAt(candidateSp: Float): Boolean {
      val effectiveCandidateSp = candidateSp * 1.25f
      val fontSizePx = effectiveCandidateSp * density * fontScale
      paint.textSize = fontSizePx
      val naturalGapPx = (fontSizePx * 0.16f).coerceAtLeast(3f)
      val minTouchGapPx = (fontSizePx * 0.025f).coerceAtLeast(1.5f)
      val fm = paint.fontMetrics
      val layoutLineH = fm.bottom - fm.top
      val baselineInSlot = (slotHeightPx - layoutLineH) * 0.5f - fm.top

      var inkMinTop = Int.MAX_VALUE
      var inkMaxBottom = Int.MIN_VALUE
      var hasMeasuredInk = false
      var maxMarkerH = 0f

      var bodyNaturalWidthSum = 0.0
      var bodyCount = 0
      var overflowLineIndices: MutableList<Int>? = null

      for (lineIdx in ayahLines.indices) {
        val words = ayahLines[lineIdx].words
        val tokens = lineTokens[lineIdx]
        val n = words.size
        var sumW = 0f

        for (i in 0 until n) {
          val w = words[i]
          if (renderVectorTajweedMarkers && w.isEndMarker) {
            sumW += fontSizePx * 1.32f
            val mh = fontSizePx * 1.18f
            if (mh > maxMarkerH) maxMarkerH = mh
          } else {
            val tok = tokens[i]
            sumW += paint.measureText(tok)
            if (tok.isNotEmpty()) {
              paint.getTextBounds(tok, 0, tok.length, tokenBounds)
              if (tokenBounds.top < inkMinTop) inkMinTop = tokenBounds.top
              if (tokenBounds.bottom > inkMaxBottom) inkMaxBottom = tokenBounds.bottom
              hasMeasuredInk = true
            }
          }
        }

        val gapSlots = (n - 1).coerceAtLeast(0)
        if (isBodyLine[lineIdx]) {
          bodyNaturalWidthSum += (sumW + naturalGapPx * gapSlots).toDouble()
          bodyCount++
        }

        val minTouchLineW = sumW + minTouchGapPx * gapSlots
        if (minTouchLineW > effectiveWidthPx) {
          if (overflowLineIndices == null) overflowLineIndices = ArrayList(4)
          overflowLineIndices.add(lineIdx)
        }
      }

      if (bodyCount > 0 && (bodyNaturalWidthSum / bodyCount) > effectiveWidthPx.toDouble()) {
        return false
      }

      if (maxMarkerH > slotHeightPx * 0.95f) return false
      if (hasMeasuredInk) {
        val inkH = (inkMaxBottom - inkMinTop).toFloat()
        if (inkH > slotHeightPx * 0.90f) return false
        val inkTopInSlot = baselineInSlot + inkMinTop.toFloat()
        val inkBottomInSlot = baselineInSlot + inkMaxBottom.toFloat()
        if (inkTopInSlot < slotHeightPx * 0.02f || inkBottomInSlot > slotHeightPx * 0.98f) {
          return false
        }
      } else {
        if ((fm.descent - fm.ascent) > slotHeightPx * 1.05f) return false
      }

      // In two-page mode, allow outlier lines to shrink up to 10% (0.90f)
      if (overflowLineIndices != null) {
        val shrunkFontSizePx = fontSizePx * 0.90f
        paint.textSize = shrunkFontSizePx
        val shrunkTouchGapPx = (shrunkFontSizePx * 0.025f).coerceAtLeast(1.5f)
        for (idx in overflowLineIndices) {
          val words = ayahLines[idx].words
          val tokens = lineTokens[idx]
          val n = words.size
          var shrunkSumW = 0f
          for (i in 0 until n) {
            shrunkSumW += if (renderVectorTajweedMarkers && words[i].isEndMarker) {
              shrunkFontSizePx * 1.32f
            } else {
              paint.measureText(tokens[i])
            }
          }
          val shrunkTotalW = shrunkSumW + shrunkTouchGapPx * (n - 1).coerceAtLeast(0)
          if (shrunkTotalW > effectiveWidthPx) {
            return false
          }
        }
      }

      return true
    }

    if (twoPageUniformFitsAt(maxSp)) return maxSp

    var lo = minSp
    var hi = maxSp
    repeat(12) {
      val mid = (lo + hi) * 0.5f
      if (twoPageUniformFitsAt(mid)) lo = mid else hi = mid
    }
    return lo.coerceAtLeast(minSp)
  }

  private fun drawAyahLineWithStaticLayout(
    canvas: Canvas,
    line: MushafLine.AyahWordsLine,
    uniformPageSp: Float,
    isFramedIntroPage: Boolean,
    isTwoPageMode: Boolean,
    paint: TextPaint,
    rosetteNumPaint: Paint,
    renderVectorTajweedMarkers: Boolean,
    usesTagFallbackTajweed: Boolean,
    goldInt: Int,
    inkInt: Int,
    effectiveDark: Boolean,
    left: Float,
    slotTop: Float,
    widthPx: Int,
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
  ): Int {
    val words = line.words
    val n = words.size
    val tokens = Array(n) { idx ->
      val w = words[idx]
      if (usesTagFallbackTajweed) w.textUthmani else w.codeV2
    }

    val effectiveWidthF = (widthPx - 2).coerceAtLeast(1).toFloat()
    val wordWidths = FloatArray(n)

    fun measureLineWidthsAt(candidateSp: Float): Pair<Float, Float> {
      val px = candidateSp * density * fontScale
      paint.textSize = px
      val mw = px * 1.32f
      var sum = 0f
      for (i in 0 until n) {
        val ww = if (renderVectorTajweedMarkers && words[i].isEndMarker) mw else paint.measureText(tokens[i])
        wordWidths[i] = ww
        sum += ww
      }
      return sum to px
    }

    val baseSp = if (isTwoPageMode) uniformPageSp * 1.25f else uniformPageSp
    var fittedSp = baseSp
    var (sumWordWidths, fontSizePx) = measureLineWidthsAt(fittedSp)

    val justifyEdgeToEdge: Boolean
    val gaps = IntArray((n - 1).coerceAtLeast(0))

    if (isFramedIntroPage) {
      val defaultGapPx = (fontSizePx * 0.20f).roundToInt().coerceAtLeast(3)
      val leftoverWidth = (widthPx.toFloat() - sumWordWidths).coerceAtLeast(0f)
      justifyEdgeToEdge = !line.isCentered && n >= 2
      if (justifyEdgeToEdge && widthPx.toFloat() >= sumWordWidths) {
        val totalGap = leftoverWidth.toDouble()
        var prevCum = 0
        for (i in 0 until n - 1) {
          val cum = ((totalGap * (i + 1)) / (n - 1)).roundToInt()
          gaps[i] = (cum - prevCum).coerceAtLeast(0)
          prevCum = cum
        }
      } else if (n > 1) {
        val maxNaturalGap = (leftoverWidth / (n - 1)).toInt().coerceAtLeast(1)
        val clampedGap = defaultGapPx.coerceAtMost(maxNaturalGap)
        for (i in 0 until n - 1) {
          gaps[i] = clampedGap
        }
      }
    } else if (!isTwoPageMode) {
      // Single-page mode: strictly uniform font size for ALL lines of the whole page (no line shrinks)
      // QUL centered=0 lines distributed evenly across full width; centered=1 lines centered with natural gap
      fittedSp = uniformPageSp
      val measured = measureLineWidthsAt(fittedSp)
      sumWordWidths = measured.first
      fontSizePx = measured.second

      val leftoverWidth = (effectiveWidthF - sumWordWidths).coerceAtLeast(0f)
      justifyEdgeToEdge = !line.isCentered && n >= 2
      if (justifyEdgeToEdge) {
        val totalGap = leftoverWidth.toDouble()
        var prevCum = 0
        for (i in 0 until n - 1) {
          val cum = ((totalGap * (i + 1)) / (n - 1)).roundToInt()
          gaps[i] = (cum - prevCum).coerceAtLeast(0)
          prevCum = cum
        }
      } else if (n > 1) {
        val defaultGapPx = (fontSizePx * 0.20f).roundToInt().coerceAtLeast(3)
        val maxNaturalGap = (leftoverWidth / (n - 1)).toInt().coerceAtLeast(1)
        val clampedGap = defaultGapPx.coerceAtMost(maxNaturalGap)
        for (i in 0 until n - 1) {
          gaps[i] = clampedGap
        }
      }
    } else {
      // Two-page mode: enlarged font with outlier shrink up to 10% and QUL centered/justify handling
      val gapSlots = (n - 1).coerceAtLeast(0)
      val minTouchGapAtUniform = (fontSizePx * 0.025f).coerceAtLeast(1.5f)
      val minLineWidthAtUniform = sumWordWidths + minTouchGapAtUniform * gapSlots

      if (minLineWidthAtUniform > effectiveWidthF && n > 0) {
        // Outlier line in two-page mode: shrink up to 10% (0.90) with minimal touch gap
        val minAllowedSp = baseSp * 0.90f
        var lo = minAllowedSp
        var hi = baseSp
        repeat(9) {
          val mid = (lo + hi) * 0.5f
          val (midSum, midPx) = measureLineWidthsAt(mid)
          val midTouchGap = (midPx * 0.025f).coerceAtLeast(1.5f)
          if (midSum + midTouchGap * gapSlots <= effectiveWidthF) {
            lo = mid
          } else {
            hi = mid
          }
        }
        fittedSp = lo
        val measured = measureLineWidthsAt(fittedSp)
        sumWordWidths = measured.first
        fontSizePx = measured.second

        val leftover = (effectiveWidthF - sumWordWidths).coerceAtLeast(0f)
        justifyEdgeToEdge = !line.isCentered && n >= 2
        if (justifyEdgeToEdge) {
          val totalGap = leftover.toDouble()
          var prevCum = 0
          for (i in 0 until n - 1) {
            val cum = ((totalGap * (i + 1)) / (n - 1)).roundToInt()
            gaps[i] = (cum - prevCum).coerceAtLeast(1)
            prevCum = cum
          }
        } else if (n > 1) {
          val naturalGapPx = (fontSizePx * 0.16f).roundToInt().coerceAtLeast(2)
          val maxNaturalGap = (leftover / (n - 1)).toInt().coerceAtLeast(1)
          val clampedGap = naturalGapPx.coerceAtMost(maxNaturalGap)
          for (i in 0 until n - 1) {
            gaps[i] = clampedGap
          }
        }
      } else {
        val leftover = (effectiveWidthF - sumWordWidths).coerceAtLeast(0f)
        justifyEdgeToEdge = !line.isCentered && n >= 2
        if (justifyEdgeToEdge) {
          val totalGap = leftover.toDouble()
          var prevCum = 0
          for (i in 0 until n - 1) {
            val cum = ((totalGap * (i + 1)) / (n - 1)).roundToInt()
            gaps[i] = (cum - prevCum).coerceAtLeast(1)
            prevCum = cum
          }
        } else if (n > 1) {
          val naturalGapPx = (fontSizePx * 0.16f).roundToInt().coerceAtLeast(3)
          val maxNaturalGap = (leftover / (n - 1)).toInt().coerceAtLeast(1)
          val clampedGap = naturalGapPx.coerceAtMost(maxNaturalGap)
          for (i in 0 until n - 1) {
            gaps[i] = clampedGap
          }
        }
      }
    }

    val markerWidthF = fontSizePx * 1.32f
    val markerHeightF = fontSizePx * 1.18f

    var sumGaps = 0
    for (g in gaps) sumGaps += g
    val totalLineWidthF = sumWordWidths + sumGaps.toFloat()

    // Build ONE SpannableStringBuilder and ONE StaticLayout for the entire Mushaf line
    val sb = SpannableStringBuilder()
    for (i in 0 until n) {
      val w = words[i]
      if (i > 0) {
        val gapStart = sb.length
        sb.append(' ')
        sb.setSpan(HorizontalGapSpan(gaps[i - 1]), gapStart, gapStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
      }
      sb.append('\u200F')
      if (renderVectorTajweedMarkers && w.isEndMarker) {
        val spanStart = sb.length
        sb.append('\uFFFC')
        val arabicNum = toArabicIndicDigits(w.ayahNumber)
        sb.setSpan(
          TajweedRosetteReplacementSpan(
            markerWidthPx = markerWidthF.roundToInt(),
            markerHeightPx = markerHeightF.roundToInt(),
            arabicDigits = arabicNum,
            lineFontSp = fittedSp,
            goldInt = goldInt,
            inkInt = inkInt,
            effectiveDark = effectiveDark,
            numPaint = rosetteNumPaint,
            density = density,
            fontScale = fontScale,
          ),
          spanStart,
          spanStart + 1,
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
      } else {
        sb.append(tokens[i])
      }
      sb.append('\u200F')
    }

    val staticWidth = maxOf(widthPx, ceil(totalLineWidthF).toInt() + 2)
    val layout = StaticLayout.Builder
      .obtain(sb, 0, sb.length, paint, staticWidth)
      .setAlignment(if (justifyEdgeToEdge) Layout.Alignment.ALIGN_NORMAL else Layout.Alignment.ALIGN_CENTER)
      .setTextDirection(TextDirectionHeuristics.RTL)
      .setIncludePad(true)
      .setMaxLines(1)
      .build()

    val layoutTopY = slotTop + (slotHeightPx - layout.height) * 0.5f
    val translateX = if (justifyEdgeToEdge) {
      left + widthPx.toFloat() - staticWidth.toFloat()
    } else {
      left + (widthPx.toFloat() - staticWidth.toFloat()) * 0.5f
    }

    canvas.save()
    canvas.translate(translateX, layoutTopY)
    layout.draw(canvas)
    canvas.restore()

    // Record exact word hit boxes and per-line merged verse highlight rects
    val codeV2ByVerse = HashMap<String, String>()
    for (i in 0 until n) {
      val vk = words[i].verseKey
      val existing = codeV2ByVerse[vk]
      codeV2ByVerse[vk] = if (existing == null) words[i].codeV2 else "$existing ${words[i].codeV2}"
    }

    val fm = paint.fontMetrics
    val textVisualH = (fm.descent - fm.ascent).coerceAtLeast(markerHeightF)
    val boxTop = (slotTop + (slotHeightPx - textVisualH) * 0.5f).coerceAtLeast(slotTop)
    val boxBottom = (boxTop + textVisualH).coerceAtMost(slotTop + slotHeightPx)

    var rightCursor = if (justifyEdgeToEdge) {
      left + widthPx.toFloat()
    } else {
      left + (widthPx.toFloat() + totalLineWidthF) * 0.5f
    }

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

    for (i in 0 until n) {
      if (i > 0) rightCursor -= gaps[i - 1].toFloat()
      val ww = wordWidths[i]
      val wordRight = rightCursor
      val wordLeft = wordRight - ww
      rightCursor = wordLeft

      val vk = words[i].verseKey
      if (cursor < hitLefts.size) {
        hitLefts[cursor] = wordLeft
        hitTops[cursor] = boxTop
        hitRights[cursor] = wordRight
        hitBottoms[cursor] = boxBottom
        hitVerseKeys[cursor] = vk
        hitCodeV2Lines[cursor] = codeV2ByVerse[vk].orEmpty()
        cursor++
      }

      if (renderVectorTajweedMarkers && words[i].isEndMarker) {
        val cx = (wordLeft + wordRight) * 0.5f
        val cy = slotTop + slotHeightPx * 0.5f
        val minDim = minOf(markerWidthF, markerHeightF)
        val slotArr = rosetteAccum.getOrPut(vk) { ArrayList(3) }
        slotArr.add(cx)
        slotArr.add(cy)
        slotArr.add(minDim)
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

  private class HorizontalGapSpan(
    private val widthPx: Int,
  ) : ReplacementSpan() {
    override fun getSize(
      paint: Paint,
      text: CharSequence?,
      start: Int,
      end: Int,
      fm: Paint.FontMetricsInt?,
    ): Int = widthPx

    override fun draw(
      canvas: Canvas,
      text: CharSequence?,
      start: Int,
      end: Int,
      x: Float,
      top: Int,
      y: Int,
      bottom: Int,
      paint: Paint,
    ) = Unit
  }

  private class TajweedRosetteReplacementSpan(
    private val markerWidthPx: Int,
    private val markerHeightPx: Int,
    private val arabicDigits: String,
    private val lineFontSp: Float,
    private val goldInt: Int,
    private val inkInt: Int,
    private val effectiveDark: Boolean,
    private val numPaint: Paint,
    private val density: Float,
    private val fontScale: Float,
  ) : ReplacementSpan() {
    private val rosetteFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.FILL
      color = withAlpha(goldInt, if (effectiveDark) 0.22f else 0.16f)
    }
    private val rosetteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = goldInt
    }
    private val innerRingStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      color = withAlpha(goldInt, 0.55f)
    }
    private val starPath = Path()

    override fun getSize(
      paint: Paint,
      text: CharSequence?,
      start: Int,
      end: Int,
      fm: Paint.FontMetricsInt?,
    ): Int = markerWidthPx

    override fun draw(
      canvas: Canvas,
      text: CharSequence?,
      start: Int,
      end: Int,
      x: Float,
      top: Int,
      y: Int,
      bottom: Int,
      paint: Paint,
    ) {
      val cx = x + markerWidthPx * 0.5f
      val cy = (top + bottom) * 0.5f
      val minDim = minOf(markerWidthPx.toFloat(), markerHeightPx.toFloat())
      val outerR = minDim * 0.46f
      val innerR = minDim * 0.37f
      val points = 16
      starPath.reset()
      for (i in 0 until points) {
        val angle = (2.0 * PI * i) / points - PI / 2.0
        val r = if (i % 2 == 0) outerR else innerR
        val px = cx + (r * cos(angle)).toFloat()
        val py = cy + (r * sin(angle)).toFloat()
        if (i == 0) starPath.moveTo(px, py) else starPath.lineTo(px, py)
      }
      starPath.close()

      rosetteStroke.strokeWidth = (minDim * 0.055f).coerceIn(0.9f, 2.2f)
      canvas.drawPath(starPath, rosetteFill)
      canvas.drawPath(starPath, rosetteStroke)

      innerRingStroke.strokeWidth = (minDim * 0.032f).coerceIn(0.6f, 1.1f)
      canvas.drawCircle(cx, cy, minDim * 0.30f, innerRingStroke)

      val digitScale = when (arabicDigits.length) {
        1 -> 0.44f
        2 -> 0.39f
        else -> 0.33f
      }
      val numSp = (lineFontSp * digitScale).coerceIn(7.5f, 18f)
      numPaint.textSize = numSp * density * fontScale
      numPaint.color = inkInt
      val fm = numPaint.fontMetrics
      val textBaseline = cy - (fm.ascent + fm.descent) * 0.5f
      canvas.drawText(arabicDigits, cx, textBaseline, numPaint)
    }
  }
}
