package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.util.LruCache
import com.example.data.quran.MushafEdition
import com.example.data.quran.SurahMeta
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

internal object SurahHeaderOrnamentRenderer {

  private const val HEADER_LIGATURE = "header"
  internal const val SURAH_ICON_LIGATURE = "surah-icon"
  private const val GLYPH_UNITS_PER_EM = 1024f
  private const val GLYPH_WIDTH_UNITS = 8240f
  private const val GLYPH_HEIGHT_UNITS = 1016f
  internal const val HEADER_GLYPH_ASPECT_RATIO = GLYPH_WIDTH_UNITS / GLYPH_HEIGHT_UNITS

  // Wing / medallion seam fractions from gid=40 ('header') contours
  private const val WING_CAP_NX = 0.014f
  private const val WING_LEFT_END_NX = 0.160f
  private const val WING_RIGHT_START_NX = 0.840f

  // Exact normalized circle centers & radii extracted from glyph 40 ('header') contours C40 & C31
  private const val LEFT_CIRCLE_NX = 0.20619f
  private const val RIGHT_CIRCLE_NX = 0.79381f
  private const val CIRCLE_NY = 0.50000f
  private const val CIRCLE_RADIUS_H_RATIO = 0.292f

  // Exact bounds of contour C35 (inner white panel of 'header')
  internal const val CARTOUCHE_LEFT_NX = 0.25485f
  internal const val CARTOUCHE_RIGHT_NX = 0.74515f
  internal const val CARTOUCHE_TOP_NY = 0.12500f
  internal const val CARTOUCHE_BOTTOM_NY = 0.87500f

  // Exact TrueType spline points of contour C35 (inner center white cartouche cutout of 'header')
  // Format: float triples [nx, ny, onCurve (1f or 0f)] normalized to glyph bbox (0..8240, 828..-188)
  private val cartoucheContourPoints = floatArrayOf(
    0.74515f, 0.49803f, 1f,
    0.74515f, 0.41437f, 0f,
    0.73859f, 0.33071f, 1f,
    0.73192f, 0.24705f, 0f,
    0.72039f, 0.21949f, 1f,
    0.71711f, 0.21555f, 0f,
    0.71129f, 0.20669f, 1f,
    0.70995f, 0.20472f, 0f,
    0.70583f, 0.16437f, 1f,
    0.70158f, 0.12500f, 0f,
    0.69794f, 0.12500f, 1f,
    0.30206f, 0.12500f, 1f,
    0.30012f, 0.12500f, 0f,
    0.29709f, 0.13878f, 1f,
    0.29393f, 0.15354f, 0f,
    0.29272f, 0.17224f, 1f,
    0.29066f, 0.20374f, 0f,
    0.28871f, 0.20669f, 1f,
    0.28544f, 0.21063f, 0f,
    0.27961f, 0.21949f, 1f,
    0.26796f, 0.24705f, 0f,
    0.26141f, 0.33071f, 1f,
    0.25485f, 0.41437f, 0f,
    0.25485f, 0.49114f, 1f,
    0.25485f, 0.56890f, 0f,
    0.25898f, 0.63681f, 1f,
    0.26299f, 0.70571f, 0f,
    0.27100f, 0.74606f, 1f,
    0.27900f, 0.78642f, 0f,
    0.28434f, 0.79035f, 1f,
    0.28981f, 0.79429f, 0f,
    0.29163f, 0.79823f, 1f,
    0.29211f, 0.79921f, 0f,
    0.29539f, 0.83760f, 1f,
    0.29867f, 0.87500f, 0f,
    0.30085f, 0.87500f, 1f,
    0.69915f, 0.87500f, 1f,
    0.70146f, 0.87500f, 0f,
    0.70473f, 0.83760f, 1f,
    0.70801f, 0.79921f, 0f,
    0.70837f, 0.79823f, 1f,
    0.71117f, 0.79035f, 0f,
    0.71578f, 0.78543f, 1f,
    0.72039f, 0.77953f, 0f,
    0.72160f, 0.77657f, 1f,
    0.72621f, 0.76673f, 0f,
    0.73277f, 0.72244f, 1f,
    0.73932f, 0.67717f, 0f,
    0.74090f, 0.63976f, 1f,
    0.74405f, 0.56693f, 1f,
    0.74515f, 0.53543f, 0f,
  )

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

  internal fun formatSurahLigature(surahId: Int): String =
    "surah${surahId.coerceIn(1, 114).toString().padStart(3, '0')}"

  internal fun formatSurahHeaderTokens(surahId: Int): Pair<String, String> =
    SURAH_ICON_LIGATURE to formatSurahLigature(surahId)

  internal fun formatFallbackSurahTitle(nameArabic: String): String {
    val cleaned = nameArabic.trim().removePrefix("سورة").trim()
    return if (cleaned.isNotEmpty()) "سورة $cleaned" else nameArabic.trim()
  }

  internal data class HeaderGlyphBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
  ) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
  }

  internal fun computeHeaderGlyphBounds(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    vertPadPx: Float = 0f,
  ): HeaderGlyphBox {
    val safeVertPad = vertPadPx.coerceIn(0f, height * 0.12f)
    val maxGlyphH = (height - safeVertPad).coerceAtLeast(1f)
    val glyphH = minOf(maxGlyphH, width / HEADER_GLYPH_ASPECT_RATIO).coerceAtLeast(1f)
    val glyphW = (glyphH * HEADER_GLYPH_ASPECT_RATIO).coerceAtMost(width)
    val glyphLeft = left + (width - glyphW) * 0.5f
    val glyphTop = top + (height - glyphH) * 0.5f
    return HeaderGlyphBox(glyphLeft, glyphTop, glyphLeft + glyphW, glyphTop + glyphH)
  }

  fun drawSurahHeaderBand(
    canvas: Canvas,
    surah: SurahMeta,
    edition: MushafEdition,
    effectiveDark: Boolean,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    amiriBold: Typeface,
    ibmMedium: Typeface,
    surahNameV4: Typeface? = null,
    quranCommon: Typeface? = null,
    dpFn: (Float) -> Float,
  ) {
    if (width <= 8f || height <= 8f) return

    // Strictly fit ornamental glyph inside the surah_name slot bounds while preserving original 'header' aspect ratio (8240 : 1016)
    val vertPad = dpFn(1.5f).coerceAtMost(height * 0.08f)
    val box = computeHeaderGlyphBounds(left, top, width, height, vertPadPx = vertPad)
    val glyphRect = RectF(box.left, box.top, box.right, box.bottom)
    val glyphW = box.width
    val glyphH = box.height

    canvas.save()
    canvas.clipRect(left, top, left + width, top + height)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeJoin = Paint.Join.ROUND
      strokeCap = Paint.Cap.ROUND
    }

    // Unified shared palette for both Madinah and Tajweed mushafs (identical appearance)
    val bgTopBottomColor = 0xFF0B6A35.toInt()
    val bgCenterColor = 0xFF064C24.toInt()
    val circleTopColor = 0xFF0E8040.toInt()
    val circleBottomColor = 0xFF07572A.toInt()
    val glyphGoldColor = if (effectiveDark) 0xFFF3CF68.toInt() else 0xFFEEC658.toInt()
    val glyphOutlineColor = 0xFF02200E.toInt()
    val panelWhiteColor = 0xFFFFFFFF.toInt()
    val panelInkColor = 0xFF111514.toInt()

    // 1. Emerald frame background spanning edge-to-edge across the entire [left, left + width] between inner gold lines
    val bgInset = (glyphH * 0.012f).coerceAtLeast(0.5f)
    val bgRect = RectF(
      left,
      glyphRect.top + bgInset,
      left + width,
      glyphRect.bottom - bgInset,
    )
    fillPaint.shader = LinearGradient(
      bgRect.left,
      bgRect.top,
      bgRect.left,
      bgRect.bottom,
      intArrayOf(bgTopBottomColor, bgCenterColor, bgTopBottomColor),
      floatArrayOf(0f, 0.5f, 1f),
      Shader.TileMode.CLAMP,
    )
    canvas.drawRect(bgRect, fillPaint)
    fillPaint.shader = null

    // 2. Circle discs behind the left & right glyph medallions (centered in exact undistorted center coordinates)
    val cy = glyphRect.top + glyphH * CIRCLE_NY
    val leftCircleX = glyphRect.left + glyphW * LEFT_CIRCLE_NX
    val rightCircleX = glyphRect.left + glyphW * RIGHT_CIRCLE_NX
    val circleFillRadius = glyphH * (CIRCLE_RADIUS_H_RATIO + 0.012f)
    for (cxCircle in floatArrayOf(leftCircleX, rightCircleX)) {
      fillPaint.shader = LinearGradient(
        cxCircle,
        cy - circleFillRadius,
        cxCircle,
        cy + circleFillRadius,
        intArrayOf(circleTopColor, circleBottomColor),
        null,
        Shader.TileMode.CLAMP,
      )
      canvas.drawCircle(cxCircle, cy, circleFillRadius, fillPaint)
      fillPaint.shader = null
    }

    // 3. Center panel white fill fitted cleanly to C35 cartouche contours of 'header'
    val cartouchePath = buildCartouchePathFromC35(glyphRect)
    fillPaint.color = panelWhiteColor
    canvas.drawPath(cartouchePath, fillPaint)
    // Slight bleed stroke underneath the gold cartouche wall so zero background hairlines show
    strokePaint.color = panelWhiteColor
    strokePaint.strokeWidth = (glyphH * 0.022f).coerceIn(dpFn(0.8f), dpFn(2.0f))
    canvas.drawPath(cartouchePath, strokePaint)

    // 4. Official QUL 'header' glyph drawn on top with crisp dark outline + bright gold,
    // tiling the authentic arabesque wing pattern seamlessly edge-to-edge across [left, left + width].
    if (quranCommon != null) {
      drawEdgeToEdgeHeaderGlyph(
        canvas = canvas,
        bandLeft = left,
        bandWidth = width,
        glyphRect = glyphRect,
        goldColor = glyphGoldColor,
        outlineColor = glyphOutlineColor,
        typeface = quranCommon,
        dpFn = dpFn,
      )
    } else {
      // Ornate geometric fallback when Typeface is unavailable
      drawFallbackOrnateBanner(
        canvas = canvas,
        bandLeft = left,
        bandWidth = width,
        bgRect = bgRect,
        glyphRect = glyphRect,
        cartouchePath = cartouchePath,
        leftCircleX = leftCircleX,
        rightCircleX = rightCircleX,
        cy = cy,
        glyphGoldColor = glyphGoldColor,
        outlineColor = glyphOutlineColor,
        dpFn = dpFn,
      )
    }

    // 5. Text inside the two glyph circles: Right = "ترتيبها" + order, Left = "آياتها" + verses count
    val textRadius = glyphH * CIRCLE_RADIUS_H_RATIO
    drawCircleLabelAndNumber(
      canvas = canvas,
      cx = rightCircleX,
      cy = cy,
      radius = textRadius,
      topLabel = "ترتيبها",
      numberText = toArabicIndicDigits(surah.id),
      ibmMedium = ibmMedium,
    )
    drawCircleLabelAndNumber(
      canvas = canvas,
      cx = leftCircleX,
      cy = cy,
      radius = textRadius,
      topLabel = "آياتها",
      numberText = toArabicIndicDigits(surah.versesCount),
      ibmMedium = ibmMedium,
    )

    // 6. Surah line inside the white cartouche (C35 full bounds):
    // 'surah-icon' ("سورة") on the right + gap + 'surahNNN' on the left, same font & size,
    // ~55% of white panel height, total width <= 65% of white panel width, evenly centered.
    val whitePanelBox = RectF(
      glyphRect.left + glyphW * CARTOUCHE_LEFT_NX,
      glyphRect.top + glyphH * CARTOUCHE_TOP_NY,
      glyphRect.left + glyphW * CARTOUCHE_RIGHT_NX,
      glyphRect.top + glyphH * CARTOUCHE_BOTTOM_NY,
    )
    if (surahNameV4 != null) {
      val (iconLigature, nameLigature) = formatSurahHeaderTokens(surah.id)
      drawFittedSurahWordAndName(
        canvas = canvas,
        iconLigature = iconLigature,
        nameLigature = nameLigature,
        whitePanelBox = whitePanelBox,
        color = panelInkColor,
        typeface = surahNameV4,
      )
    } else {
      drawFittedCenteredTitle(
        canvas = canvas,
        text = formatFallbackSurahTitle(surah.nameArabic),
        box = whitePanelBox,
        color = panelInkColor,
        typeface = amiriBold,
      )
    }

    canvas.restore()
  }

  private fun buildCartouchePathFromC35(glyphRect: RectF): Path {
    val count = cartoucheContourPoints.size / 3
    fun mapX(idx: Int): Float = glyphRect.left + cartoucheContourPoints[idx * 3] * glyphRect.width()
    fun mapY(idx: Int): Float = glyphRect.top + cartoucheContourPoints[idx * 3 + 1] * glyphRect.height()
    fun isOn(idx: Int): Boolean = cartoucheContourPoints[idx * 3 + 2] > 0.5f

    val path = Path()
    // Index 0 is an on-curve point in C35
    path.moveTo(mapX(0), mapY(0))

    var i = 1
    while (i < count) {
      val x = mapX(i)
      val y = mapY(i)
      if (isOn(i)) {
        path.lineTo(x, y)
        i++
      } else {
        val nextIdx = (i + 1) % count
        val nx = mapX(nextIdx)
        val ny = mapY(nextIdx)
        if (isOn(nextIdx)) {
          path.quadTo(x, y, nx, ny)
          i += 2
        } else {
          // TrueType implied on-curve midpoint between two consecutive off-curve control points
          path.quadTo(x, y, (x + nx) * 0.5f, (y + ny) * 0.5f)
          i++
        }
      }
    }
    path.close()
    return path
  }

  private data class HeaderBitmapKey(
    val widthPx: Int,
    val heightPx: Int,
    val goldColor: Int,
    val outlineColor: Int,
    val typefaceId: Int,
  )

  private val headerGlyphBitmapCache = LruCache<HeaderBitmapKey, Bitmap>(6)

  private fun getOrRenderHeaderGlyphBitmap(
    targetW: Int,
    targetH: Int,
    goldColor: Int,
    outlineColor: Int,
    typeface: Typeface,
  ): Bitmap {
    val safeW = targetW.coerceAtLeast(8)
    val safeH = targetH.coerceAtLeast(4)
    val key = HeaderBitmapKey(
      widthPx = safeW,
      heightPx = safeH,
      goldColor = goldColor,
      outlineColor = outlineColor,
      typefaceId = System.identityHashCode(typeface),
    )
    headerGlyphBitmapCache.get(key)?.let { return it }

    val bmp = Bitmap.createBitmap(safeW, safeH, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      this.typeface = typeface
      this.textLocale = Locale.US
      this.fontFeatureSettings = "liga"
    }
    val bounds = Rect()
    var fontSizePx = (safeW * (GLYPH_UNITS_PER_EM / GLYPH_WIDTH_UNITS)).coerceAtLeast(8f)
    paint.textSize = fontSizePx
    paint.getTextBounds(HEADER_LIGATURE, 0, HEADER_LIGATURE.length, bounds)

    if (bounds.width() > 0) {
      fontSizePx *= safeW.toFloat() / bounds.width().toFloat()
      paint.textSize = fontSizePx
      paint.getTextBounds(HEADER_LIGATURE, 0, HEADER_LIGATURE.length, bounds)
    }

    val drawX = if (bounds.width() > 0) -bounds.left.toFloat() else 0f
    val baseline = if (bounds.height() > 0) {
      safeH * 0.5f - (bounds.top + bounds.bottom) * 0.5f
    } else {
      val fm = paint.fontMetrics
      safeH * 0.5f - (fm.ascent + fm.descent) * 0.5f
    }

    // Dark-forest outline underlay makes every arabesque stem & leaf sharp and prominent
    paint.style = Paint.Style.STROKE
    paint.strokeJoin = Paint.Join.ROUND
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeWidth = (safeH * 0.015f).coerceIn(0.8f, 2.2f)
    paint.color = outlineColor
    c.drawText(HEADER_LIGATURE, drawX, baseline, paint)

    paint.style = Paint.Style.FILL
    paint.color = goldColor
    c.drawText(HEADER_LIGATURE, drawX, baseline, paint)

    synchronized(headerGlyphBitmapCache) {
      headerGlyphBitmapCache.put(key, bmp)
    }
    return bmp
  }

  private fun drawEdgeToEdgeHeaderGlyph(
    canvas: Canvas,
    bandLeft: Float,
    bandWidth: Float,
    glyphRect: RectF,
    goldColor: Int,
    outlineColor: Int,
    typeface: Typeface,
    dpFn: (Float) -> Float,
  ) {
    val bmpW = ceil(glyphRect.width().toDouble()).toInt().coerceAtLeast(16)
    val bmpH = ceil(glyphRect.height().toDouble()).toInt().coerceAtLeast(8)
    val bmp = getOrRenderHeaderGlyphBitmap(
      targetW = bmpW,
      targetH = bmpH,
      goldColor = goldColor,
      outlineColor = outlineColor,
      typeface = typeface,
    )
    val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    val bandRight = bandLeft + bandWidth
    if (bandWidth <= glyphRect.width() + 2f) {
      canvas.drawBitmap(
        bmp,
        Rect(0, 0, bmpW, bmpH),
        RectF(bandLeft, glyphRect.top, bandRight, glyphRect.bottom),
        bmpPaint,
      )
      return
    }

    val centerSrcL = (bmpW * WING_LEFT_END_NX).roundToInt().coerceIn(1, bmpW - 2)
    val centerSrcR = (bmpW * WING_RIGHT_START_NX).roundToInt().coerceIn(centerSrcL + 1, bmpW - 1)
    val leftCapSrcEnd = (bmpW * WING_CAP_NX).roundToInt().coerceIn(1, centerSrcL - 1)
    val rightCapSrcStart = (bmpW * (1f - WING_CAP_NX)).roundToInt().coerceIn(centerSrcR + 1, bmpW - 1)

    val centerDstL = glyphRect.left + glyphRect.width() * WING_LEFT_END_NX
    val centerDstR = glyphRect.left + glyphRect.width() * WING_RIGHT_START_NX
    val capDstW = (glyphRect.width() * WING_CAP_NX).coerceAtLeast(dpFn(2.0f))

    // 1. Center medallions & white cartouche at exact 1:1 natural aspect ratio
    canvas.drawBitmap(
      bmp,
      Rect(centerSrcL, 0, centerSrcR, bmpH),
      RectF(centerDstL, glyphRect.top, centerDstR, glyphRect.bottom),
      bmpPaint,
    )

    // 2. Left floral arabesque wing tiled & alternatingly mirrored seamlessly from centerDstL to bandLeft + capDstW
    val leftWingFillStart = (bandLeft + capDstW).coerceAtMost(centerDstL)
    if (centerDstL > leftWingFillStart + 0.5f) {
      val srcLeftWing = Rect(leftCapSrcEnd, 0, centerSrcL, bmpH)
      val tileW = (glyphRect.width() * (WING_LEFT_END_NX - WING_CAP_NX)).coerceAtLeast(dpFn(12f))
      canvas.save()
      canvas.clipRect(leftWingFillStart, glyphRect.top, centerDstL + 0.5f, glyphRect.bottom)
      var curRight = centerDstL
      var tileIdx = 0
      while (curRight > leftWingFillStart) {
        val curLeft = curRight - tileW
        val dst = RectF(curLeft, glyphRect.top, curRight + 0.5f, glyphRect.bottom)
        if (tileIdx % 2 == 0) {
          canvas.drawBitmap(bmp, srcLeftWing, dst, bmpPaint)
        } else {
          canvas.save()
          canvas.scale(-1f, 1f, (curLeft + curRight) * 0.5f, glyphRect.centerY())
          canvas.drawBitmap(bmp, srcLeftWing, dst, bmpPaint)
          canvas.restore()
        }
        curRight = curLeft
        tileIdx++
      }
      canvas.restore()
    }

    // 3. Right floral arabesque wing tiled & alternatingly mirrored seamlessly from centerDstR to bandRight - capDstW
    val rightWingFillEnd = (bandRight - capDstW).coerceAtLeast(centerDstR)
    if (rightWingFillEnd > centerDstR + 0.5f) {
      val srcRightWing = Rect(centerSrcR, 0, rightCapSrcStart, bmpH)
      val tileW = (glyphRect.width() * (1f - WING_CAP_NX - WING_RIGHT_START_NX)).coerceAtLeast(dpFn(12f))
      canvas.save()
      canvas.clipRect(centerDstR - 0.5f, glyphRect.top, rightWingFillEnd, glyphRect.bottom)
      var curLeft = centerDstR
      var tileIdx = 0
      while (curLeft < rightWingFillEnd) {
        val curRight = curLeft + tileW
        val dst = RectF(curLeft - 0.5f, glyphRect.top, curRight, glyphRect.bottom)
        if (tileIdx % 2 == 0) {
          canvas.drawBitmap(bmp, srcRightWing, dst, bmpPaint)
        } else {
          canvas.save()
          canvas.scale(-1f, 1f, (curLeft + curRight) * 0.5f, glyphRect.centerY())
          canvas.drawBitmap(bmp, srcRightWing, dst, bmpPaint)
          canvas.restore()
        }
        curLeft = curRight
        tileIdx++
      }
      canvas.restore()
    }

    // 4. Left & right outer ornamental border end-caps flush against the inner gold frame lines
    canvas.drawBitmap(
      bmp,
      Rect(0, 0, leftCapSrcEnd, bmpH),
      RectF(bandLeft, glyphRect.top, (bandLeft + capDstW).coerceAtMost(centerDstL), glyphRect.bottom),
      bmpPaint,
    )
    canvas.drawBitmap(
      bmp,
      Rect(rightCapSrcStart, 0, bmpW, bmpH),
      RectF((bandRight - capDstW).coerceAtLeast(centerDstR), glyphRect.top, bandRight, glyphRect.bottom),
      bmpPaint,
    )
  }

  private fun drawFallbackOrnateBanner(
    canvas: Canvas,
    bandLeft: Float,
    bandWidth: Float,
    bgRect: RectF,
    glyphRect: RectF,
    cartouchePath: Path,
    leftCircleX: Float,
    rightCircleX: Float,
    cy: Float,
    glyphGoldColor: Int,
    outlineColor: Int,
    dpFn: (Float) -> Float,
  ) {
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeJoin = Paint.Join.ROUND
      strokeCap = Paint.Cap.ROUND
    }
    val glyphH = glyphRect.height()
    val strokeW = (glyphH * 0.032f).coerceAtLeast(dpFn(1.1f))
    val radius = glyphH * CIRCLE_RADIUS_H_RATIO

    // Ornate diamond + scroll lattice along both left & right wings so fallback wings are never plain
    val wingPadY = glyphH * 0.14f
    val diamondH = (glyphH - wingPadY * 2f) * 0.42f
    val stepW = (diamondH * 1.45f).coerceAtLeast(dpFn(12f))
    val leftEnd = leftCircleX - radius
    val rightStart = rightCircleX + radius

    fun drawWingLattice(startX: Float, endX: Float) {
      if (endX <= startX + dpFn(4f)) return
      canvas.save()
      canvas.clipRect(startX, bgRect.top, endX, bgRect.bottom)
      var cx = startX + stepW * 0.5f
      val diamondPath = Path()
      while (cx - stepW * 0.5f < endX) {
        diamondPath.reset()
        diamondPath.moveTo(cx, cy - diamondH)
        diamondPath.lineTo(cx + stepW * 0.44f, cy)
        diamondPath.lineTo(cx, cy + diamondH)
        diamondPath.lineTo(cx - stepW * 0.44f, cy)
        diamondPath.close()
        strokePaint.color = outlineColor
        strokePaint.strokeWidth = strokeW * 1.45f
        canvas.drawPath(diamondPath, strokePaint)
        strokePaint.color = glyphGoldColor
        strokePaint.strokeWidth = strokeW * 0.72f
        canvas.drawPath(diamondPath, strokePaint)
        canvas.drawCircle(cx, cy, diamondH * 0.24f, strokePaint)
        cx += stepW
      }
      canvas.restore()
    }

    drawWingLattice(bandLeft, leftEnd)
    drawWingLattice(rightStart, bandLeft + bandWidth)

    strokePaint.color = glyphGoldColor
    strokePaint.strokeWidth = strokeW
    canvas.drawRect(bgRect, strokePaint)
    canvas.drawPath(cartouchePath, strokePaint)
    canvas.drawCircle(leftCircleX, cy, radius, strokePaint)
    canvas.drawCircle(rightCircleX, cy, radius, strokePaint)
  }

  private fun drawCircleLabelAndNumber(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    radius: Float,
    topLabel: String,
    numberText: String,
    ibmMedium: Typeface,
  ) {
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = ibmMedium
      color = 0xFFFFFFFF.toInt()
      textAlign = Paint.Align.CENTER
    }

    val labelBounds = Rect()
    val maxLabelW = radius * 1.48f
    var labelPx = (radius * 0.54f).coerceAtLeast(4f)
    textPaint.textSize = labelPx
    textPaint.getTextBounds(topLabel, 0, topLabel.length, labelBounds)
    while ((textPaint.measureText(topLabel) > maxLabelW || labelBounds.height() > radius * 0.52f) && labelPx > 3.5f) {
      labelPx *= 0.93f
      textPaint.textSize = labelPx
      textPaint.getTextBounds(topLabel, 0, topLabel.length, labelBounds)
    }
    val labelBaseline = if (labelBounds.height() > 0) {
      cy - radius * 0.25f - (labelBounds.top + labelBounds.bottom) * 0.5f
    } else {
      val fm = textPaint.fontMetrics
      cy - radius * 0.24f - (fm.ascent + fm.descent) * 0.5f
    }
    canvas.drawText(topLabel, cx, labelBaseline, textPaint)

    val numBounds = Rect()
    val maxNumW = radius * 1.40f
    var numPx = (radius * 0.60f).coerceAtLeast(4.5f)
    textPaint.textSize = numPx
    textPaint.getTextBounds(numberText, 0, numberText.length, numBounds)
    while ((textPaint.measureText(numberText) > maxNumW || numBounds.height() > radius * 0.56f) && numPx > 3.5f) {
      numPx *= 0.93f
      textPaint.textSize = numPx
      textPaint.getTextBounds(numberText, 0, numberText.length, numBounds)
    }
    val numBaseline = if (numBounds.height() > 0) {
      cy + radius * 0.31f - (numBounds.top + numBounds.bottom) * 0.5f
    } else {
      val fm = textPaint.fontMetrics
      cy + radius * 0.30f - (fm.ascent + fm.descent) * 0.5f
    }
    canvas.drawText(numberText, cx, numBaseline, textPaint)
  }

  private fun drawFittedSurahWordAndName(
    canvas: Canvas,
    iconLigature: String,
    nameLigature: String,
    whitePanelBox: RectF,
    color: Int,
    typeface: Typeface,
  ) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      this.typeface = typeface
      this.color = color
      this.textLocale = Locale.US
      this.fontFeatureSettings = "liga"
    }

    val targetLineH = (whitePanelBox.height() * 0.55f).coerceAtLeast(7f)
    val maxCombinedW = (whitePanelBox.width() * 0.65f).coerceAtLeast(12f)
    val maxLineH = (whitePanelBox.height() * 0.62f).coerceAtLeast(7f)

    val iconBounds = Rect()
    val nameBounds = Rect()
    var iconW = 0f
    var nameW = 0f
    var gapPx = 0f
    var totalW = 0f
    var unionH = 0f

    fun measureBothAt(sizePx: Float) {
      paint.textSize = sizePx
      paint.getTextBounds(iconLigature, 0, iconLigature.length, iconBounds)
      paint.getTextBounds(nameLigature, 0, nameLigature.length, nameBounds)

      iconW = if (iconBounds.width() > 0) {
        iconBounds.width().toFloat()
      } else {
        paint.measureText(iconLigature)
      }
      nameW = if (nameBounds.width() > 0) {
        nameBounds.width().toFloat()
      } else {
        paint.measureText(nameLigature)
      }
      // Note: space in surah_name_v4.ttf has 0 advanceWidth, so add explicit proportional visual gap
      gapPx = sizePx * 0.14f
      totalW = nameW + gapPx + iconW

      val topY = minOf(iconBounds.top, nameBounds.top)
      val botY = maxOf(iconBounds.bottom, nameBounds.bottom)
      unionH = if (botY > topY) {
        (botY - topY).toFloat()
      } else {
        val fm = paint.fontMetrics
        fm.descent - fm.ascent
      }
    }

    var fontSizePx = targetLineH.coerceIn(8f, 120f)
    measureBothAt(fontSizePx)

    if (unionH > 0f) {
      fontSizePx = (fontSizePx * (targetLineH / unionH)).coerceIn(7f, whitePanelBox.height() * 1.4f)
      measureBothAt(fontSizePx)
    }

    if (totalW > maxCombinedW && totalW > 0f) {
      fontSizePx = (fontSizePx * (maxCombinedW / totalW)).coerceAtLeast(6f)
      measureBothAt(fontSizePx)
    }

    while ((totalW > maxCombinedW || unionH > maxLineH) && fontSizePx > 6f) {
      fontSizePx *= 0.94f
      measureBothAt(fontSizePx)
    }

    // Center [nameLigature] (left) + [gap] + [iconLigature "سورة"] (right) horizontally in whitePanelBox
    val startX = whitePanelBox.centerX() - totalW * 0.5f
    val nameDrawX = if (nameBounds.width() > 0) {
      startX - nameBounds.left.toFloat()
    } else {
      startX
    }
    val iconStartX = startX + nameW + gapPx
    val iconDrawX = if (iconBounds.width() > 0) {
      iconStartX - iconBounds.left.toFloat()
    } else {
      iconStartX
    }

    val unionTop = minOf(iconBounds.top, nameBounds.top)
    val unionBottom = maxOf(iconBounds.bottom, nameBounds.bottom)
    val baseline = if (unionBottom > unionTop) {
      whitePanelBox.centerY() - (unionTop + unionBottom) * 0.5f
    } else {
      val fm = paint.fontMetrics
      whitePanelBox.centerY() - (fm.ascent + fm.descent) * 0.5f
    }

    // RTL Arabic reading order on single horizontal line: "سورة" on right, surah name on left
    canvas.drawText(nameLigature, nameDrawX, baseline, paint)
    canvas.drawText(iconLigature, iconDrawX, baseline, paint)
  }

  private fun drawFittedCenteredTitle(
    canvas: Canvas,
    text: String,
    box: RectF,
    color: Int,
    typeface: Typeface,
  ) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      this.typeface = typeface
      this.color = color
    }
    val availW = (box.width() * 0.65f).coerceAtLeast(10f)
    val availH = (box.height() * 0.58f).coerceAtLeast(8f)

    var fontSizePx = (box.height() * 0.55f).coerceIn(10f, 96f)
    paint.textSize = fontSizePx
    var measuredW = paint.measureText(text)
    var fm = paint.fontMetrics
    var textH = fm.descent - fm.ascent

    while ((measuredW > availW || textH > availH) && fontSizePx > 7f) {
      fontSizePx *= 0.92f
      paint.textSize = fontSizePx
      measuredW = paint.measureText(text)
      fm = paint.fontMetrics
      textH = fm.descent - fm.ascent
    }

    val baseline = box.centerY() - (fm.ascent + fm.descent) * 0.5f
    val drawX = box.centerX() - measuredW * 0.5f
    canvas.drawText(text, drawX, baseline, paint)
  }
}
