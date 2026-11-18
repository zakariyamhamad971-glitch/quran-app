package com.example.ui.components

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import com.example.data.quran.MushafEdition
import com.example.data.quran.SurahMeta
import java.util.Locale

internal object SurahHeaderOrnamentRenderer {

  private const val HEADER_LIGATURE = "header"
  internal const val SURAH_ICON_LIGATURE = "surah-icon"
  private const val GLYPH_UNITS_PER_EM = 1024f
  private const val GLYPH_WIDTH_UNITS = 8240f
  private const val GLYPH_HEIGHT_UNITS = 1016f
  internal const val HEADER_GLYPH_ASPECT_RATIO = GLYPH_WIDTH_UNITS / GLYPH_HEIGHT_UNITS

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

    // Full page width while preserving exact original 'header' glyph aspect ratio (8240 : 1016)
    val glyphW = width
    val glyphH = glyphW / HEADER_GLYPH_ASPECT_RATIO
    val glyphTop = top + (height - glyphH) * 0.5f
    val glyphRect = RectF(left, glyphTop, left + glyphW, glyphTop + glyphH)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeJoin = Paint.Join.ROUND
      strokeCap = Paint.Cap.ROUND
    }

    val isTajweed = edition == MushafEdition.TAJWEED_V4

    // Rich dark green backing for Tajweed, deep blue backing for Madinah
    val bgTopBottomColor = if (isTajweed) 0xFF0A4E2F.toInt() else 0xFF0E4F8F.toInt()
    val bgCenterColor = if (isTajweed) 0xFF05361F.toInt() else 0xFF083566.toInt()
    val circleTopColor = if (isTajweed) 0xFF0D643C.toInt() else 0xFF1261AD.toInt()
    val circleBottomColor = if (isTajweed) 0xFF073E24.toInt() else 0xFF093C70.toInt()
    val glyphGoldColor = if (effectiveDark) 0xFFEDC96D.toInt() else 0xFFE6BF5A.toInt()
    val panelWhiteColor = 0xFFFFFFFF.toInt()
    val panelInkColor = 0xFF111514.toInt()

    // 1. Colored frame background strictly inside the outer bounds of the glyph
    val bgInset = (glyphH * 0.015f).coerceAtLeast(0.5f)
    val bgRect = RectF(
      glyphRect.left + bgInset,
      glyphRect.top + bgInset,
      glyphRect.right - bgInset,
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

    // 2. Circle discs behind the left & right glyph medallions
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
    // Slight bleed stroke underneath the gold cartouche wall so zero green/blue hairlines show
    strokePaint.color = panelWhiteColor
    strokePaint.strokeWidth = (glyphH * 0.022f).coerceIn(dpFn(0.8f), dpFn(2.0f))
    canvas.drawPath(cartouchePath, strokePaint)

    // 4. Official QUL 'header' glyph drawn on top in bright gold
    if (quranCommon != null) {
      drawHeaderGlyphOnRect(
        canvas = canvas,
        glyphRect = glyphRect,
        color = glyphGoldColor,
        typeface = quranCommon,
      )
    } else {
      // Fallback border only if Typeface failed to load
      strokePaint.color = glyphGoldColor
      strokePaint.strokeWidth = (glyphH * 0.036f).coerceAtLeast(1.2f)
      canvas.drawRect(bgRect, strokePaint)
      canvas.drawPath(cartouchePath, strokePaint)
      canvas.drawCircle(leftCircleX, cy, glyphH * CIRCLE_RADIUS_H_RATIO, strokePaint)
      canvas.drawCircle(rightCircleX, cy, glyphH * CIRCLE_RADIUS_H_RATIO, strokePaint)
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

  private fun drawHeaderGlyphOnRect(
    canvas: Canvas,
    glyphRect: RectF,
    color: Int,
    typeface: Typeface,
  ) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      this.typeface = typeface
      this.color = color
      this.textLocale = Locale.US
      this.fontFeatureSettings = "liga"
    }
    val targetW = glyphRect.width()
    val bounds = Rect()

    var fontSizePx = (targetW * (GLYPH_UNITS_PER_EM / GLYPH_WIDTH_UNITS)).coerceAtLeast(8f)
    paint.textSize = fontSizePx
    paint.getTextBounds(HEADER_LIGATURE, 0, HEADER_LIGATURE.length, bounds)

    if (bounds.width() > 0) {
      fontSizePx *= targetW / bounds.width().toFloat()
      paint.textSize = fontSizePx
      paint.getTextBounds(HEADER_LIGATURE, 0, HEADER_LIGATURE.length, bounds)
    }

    val drawX = if (bounds.width() > 0) {
      glyphRect.left - bounds.left.toFloat()
    } else {
      glyphRect.left
    }
    val baseline = if (bounds.height() > 0) {
      glyphRect.centerY() - (bounds.top + bounds.bottom) * 0.5f
    } else {
      val fm = paint.fontMetrics
      glyphRect.centerY() - (fm.ascent + fm.descent) * 0.5f
    }
    canvas.drawText(HEADER_LIGATURE, drawX, baseline, paint)
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
    var labelPx = (radius * 0.54f).coerceAtLeast(7.5f)
    textPaint.textSize = labelPx
    textPaint.getTextBounds(topLabel, 0, topLabel.length, labelBounds)
    while ((textPaint.measureText(topLabel) > maxLabelW || labelBounds.height() > radius * 0.52f) && labelPx > 5.5f) {
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
    var numPx = (radius * 0.60f).coerceAtLeast(8f)
    textPaint.textSize = numPx
    textPaint.getTextBounds(numberText, 0, numberText.length, numBounds)
    while ((textPaint.measureText(numberText) > maxNumW || numBounds.height() > radius * 0.56f) && numPx > 6f) {
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
