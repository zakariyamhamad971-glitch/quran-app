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
import java.util.Locale
import com.example.data.quran.MushafEdition
import com.example.data.quran.SurahMeta
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

internal object SurahHeaderOrnamentRenderer {

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
    dpFn: (Float) -> Float,
  ) {
    if (width <= 8f || height <= 8f) return

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      style = Paint.Style.STROKE
      strokeJoin = Paint.Join.ROUND
      strokeCap = Paint.Cap.ROUND
    }

    val outerRect = RectF(left, top, left + width, top + height)
    val outerFrameStroke = (height * 0.026f).coerceIn(dpFn(0.9f), dpFn(1.8f))
    val gapBetweenFrames = (height * 0.045f).coerceIn(dpFn(1.5f), dpFn(3.0f))
    val innerRect = RectF(
      outerRect.left + gapBetweenFrames,
      outerRect.top + gapBetweenFrames,
      outerRect.right - gapBetweenFrames,
      outerRect.bottom - gapBetweenFrames,
    )

    val frameDarkStroke = if (effectiveDark) 0xFFD9C38A.toInt() else 0xFF141816.toInt()
    val panelWhite = if (effectiveDark) 0xFFF7F4EA.toInt() else 0xFFFFFFFF.toInt()
    val panelInk = 0xFF121514.toInt()

    // Crisp cream/paper frame mat between double outer border
    fillPaint.color = if (effectiveDark) 0xFF181D1B.toInt() else 0xFFFAF8F2.toInt()
    canvas.drawRect(outerRect, fillPaint)

    when (edition) {
      MushafEdition.TAJWEED_V4 -> drawTajweedOrnament(
        canvas = canvas,
        surah = surah,
        innerRect = innerRect,
        frameDarkStroke = frameDarkStroke,
        panelWhite = panelWhite,
        panelInk = panelInk,
        amiriBold = amiriBold,
        ibmMedium = ibmMedium,
        surahNameV4 = surahNameV4,
        fillPaint = fillPaint,
        strokePaint = strokePaint,
        dpFn = dpFn,
      )

      MushafEdition.MADINAH_1421 -> drawMadinahOrnament(
        canvas = canvas,
        surah = surah,
        innerRect = innerRect,
        frameDarkStroke = frameDarkStroke,
        panelWhite = panelWhite,
        panelInk = panelInk,
        amiriBold = amiriBold,
        surahNameV4 = surahNameV4,
        fillPaint = fillPaint,
        strokePaint = strokePaint,
        dpFn = dpFn,
      )
    }

    // Crisp outer and inner border lines
    strokePaint.shader = null
    strokePaint.color = frameDarkStroke
    strokePaint.strokeWidth = outerFrameStroke
    canvas.drawRect(outerRect, strokePaint)
    canvas.drawRect(innerRect, strokePaint)
  }

  private fun drawTajweedOrnament(
    canvas: Canvas,
    surah: SurahMeta,
    innerRect: RectF,
    frameDarkStroke: Int,
    panelWhite: Int,
    panelInk: Int,
    amiriBold: Typeface,
    ibmMedium: Typeface,
    surahNameV4: Typeface?,
    fillPaint: Paint,
    strokePaint: Paint,
    dpFn: (Float) -> Float,
  ) {
    val w = innerRect.width()
    val h = innerRect.height()
    val cx = innerRect.centerX()
    val cy = innerRect.centerY()

    val emeraldDeep = 0xFF006D37.toInt()
    val emeraldMid = 0xFF008F48.toInt()
    val emeraldBright = 0xFF17A85B.toInt()
    val goldBase = 0xFFD2A138.toInt()
    val goldBright = 0xFFE6BF5A.toInt()
    val darkOutline = 0xFF101914.toInt()

    // 1. Clipped repeating emerald & gold botanical-geometric wings
    canvas.save()
    canvas.clipRect(innerRect)

    fillPaint.shader = LinearGradient(
      innerRect.left,
      innerRect.top,
      innerRect.left,
      innerRect.bottom,
      intArrayOf(emeraldMid, emeraldDeep, emeraldMid),
      floatArrayOf(0f, 0.5f, 1f),
      Shader.TileMode.CLAMP,
    )
    canvas.drawRect(innerRect, fillPaint)
    fillPaint.shader = null

    val tileW = (h * 0.92f).coerceAtLeast(18f)
    val strokeThin = (h * 0.020f).coerceIn(dpFn(0.75f), dpFn(1.4f))
    val numTilesHalf = ((w * 0.5f) / tileW).toInt() + 2

    for (k in 0 until numTilesHalf) {
      val rxLeft = cx + k * tileW
      drawTajweedArabesqueTile(
        canvas = canvas,
        tileLeft = rxLeft,
        tileTop = innerRect.top,
        tileW = tileW,
        tileH = h,
        emeraldBright = emeraldBright,
        emeraldDeep = emeraldDeep,
        goldBase = goldBase,
        goldBright = goldBright,
        outline = darkOutline,
        fillPaint = fillPaint,
        strokePaint = strokePaint,
        strokeThin = strokeThin,
      )
      val lxRight = cx - (k + 1) * tileW
      canvas.save()
      canvas.scale(-1f, 1f, lxRight + tileW * 0.5f, cy)
      drawTajweedArabesqueTile(
        canvas = canvas,
        tileLeft = lxRight,
        tileTop = innerRect.top,
        tileW = tileW,
        tileH = h,
        emeraldBright = emeraldBright,
        emeraldDeep = emeraldDeep,
        goldBase = goldBase,
        goldBright = goldBright,
        outline = darkOutline,
        fillPaint = fillPaint,
        strokePaint = strokePaint,
        strokeThin = strokeThin,
      )
      canvas.restore()
    }
    canvas.restore()

    // Inner dark green top/bottom rail bars for clean visual grounding
    val railH = (h * 0.055f).coerceAtLeast(1.2f)
    fillPaint.color = goldBase
    canvas.drawRect(innerRect.left, innerRect.top, innerRect.right, innerRect.top + railH, fillPaint)
    canvas.drawRect(innerRect.left, innerRect.bottom - railH, innerRect.right, innerRect.bottom, fillPaint)
    strokePaint.color = darkOutline
    strokePaint.strokeWidth = (h * 0.018f).coerceAtLeast(0.8f)
    canvas.drawLine(innerRect.left, innerRect.top + railH, innerRect.right, innerRect.top + railH, strokePaint)
    canvas.drawLine(innerRect.left, innerRect.bottom - railH, innerRect.right, innerRect.bottom - railH, strokePaint)

    // 2. Geometry for flanking medallions + central scalloped white cartouche
    val circleRadius = min(h * 0.435f, w * 0.084f)
    val medallionCenterOffset = (w * 0.292f).coerceIn(circleRadius * 2.5f, w * 0.34f)
    val rightCircleX = cx + medallionCenterOffset
    val leftCircleX = cx - medallionCenterOffset

    val cartoucheHalfW = (medallionCenterOffset - circleRadius * 1.18f).coerceAtLeast(w * 0.16f)
    val cartoucheRect = RectF(
      cx - cartoucheHalfW,
      innerRect.top + h * 0.06f,
      cx + cartoucheHalfW,
      innerRect.bottom - h * 0.06f,
    )

    // Draw scalloped cartouche with emerald/gold ribbon frame
    val outerCartouchePath = buildScallopedCartouchePath(cartoucheRect, lobeRatio = 0.20f)
    val borderInset = (h * 0.075f).coerceIn(dpFn(2f), dpFn(4.5f))
    val innerCartoucheRect = RectF(
      cartoucheRect.left + borderInset,
      cartoucheRect.top + borderInset,
      cartoucheRect.right - borderInset,
      cartoucheRect.bottom - borderInset,
    )
    val innerCartouchePath = buildScallopedCartouchePath(innerCartoucheRect, lobeRatio = 0.20f)

    // Outer white cushion halo
    strokePaint.color = panelWhite
    strokePaint.strokeWidth = borderInset * 0.9f
    canvas.drawPath(outerCartouchePath, strokePaint)

    // Emerald band fill + white center panel
    fillPaint.color = emeraldMid
    canvas.drawPath(outerCartouchePath, fillPaint)
    fillPaint.color = panelWhite
    canvas.drawPath(innerCartouchePath, fillPaint)

    // Gold hairline inside the emerald collar + crisp dark borders
    strokePaint.color = darkOutline
    strokePaint.strokeWidth = (h * 0.022f).coerceAtLeast(0.9f)
    canvas.drawPath(outerCartouchePath, strokePaint)
    canvas.drawPath(innerCartouchePath, strokePaint)

    val midGoldRect = RectF(
      cartoucheRect.left + borderInset * 0.5f,
      cartoucheRect.top + borderInset * 0.5f,
      cartoucheRect.right - borderInset * 0.5f,
      cartoucheRect.bottom - borderInset * 0.5f,
    )
    strokePaint.color = goldBright
    strokePaint.strokeWidth = (h * 0.016f).coerceAtLeast(0.7f)
    canvas.drawPath(buildScallopedCartouchePath(midGoldRect, lobeRatio = 0.20f), strokePaint)

    // 3. Two circular medallions: Right = ترتيبها + surah.id, Left = آياتها + surah.versesCount
    drawTajweedMedallion(
      canvas = canvas,
      cx = rightCircleX,
      cy = cy,
      radius = circleRadius,
      topLabel = "ترتيبها",
      numberText = toArabicIndicDigits(surah.id),
      emeraldDeep = emeraldDeep,
      emeraldMid = emeraldMid,
      goldBase = goldBase,
      goldBright = goldBright,
      darkOutline = darkOutline,
      panelWhite = panelWhite,
      ibmMedium = ibmMedium,
      fillPaint = fillPaint,
      strokePaint = strokePaint,
    )

    drawTajweedMedallion(
      canvas = canvas,
      cx = leftCircleX,
      cy = cy,
      radius = circleRadius,
      topLabel = "آياتها",
      numberText = toArabicIndicDigits(surah.versesCount),
      emeraldDeep = emeraldDeep,
      emeraldMid = emeraldMid,
      goldBase = goldBase,
      goldBright = goldBright,
      darkOutline = darkOutline,
      panelWhite = panelWhite,
      ibmMedium = ibmMedium,
      fillPaint = fillPaint,
      strokePaint = strokePaint,
    )

    // 4. Surah Title centered in the white cartouche (QUL V4 ligature surah001..surah114)
    if (surahNameV4 != null) {
      drawFittedSurahLigatureTitle(
        canvas = canvas,
        ligatureText = formatSurahLigature(surah.id),
        box = innerCartoucheRect,
        color = panelInk,
        typeface = surahNameV4,
      )
    } else {
      val titleText = if (surah.nameArabic.startsWith("سورة")) {
        surah.nameArabic
      } else {
        "سورة ${surah.nameArabic}"
      }
      drawFittedCenteredTitle(
        canvas = canvas,
        text = titleText,
        box = innerCartoucheRect,
        color = panelInk,
        typeface = amiriBold,
      )
    }
  }

  private fun drawTajweedArabesqueTile(
    canvas: Canvas,
    tileLeft: Float,
    tileTop: Float,
    tileW: Float,
    tileH: Float,
    emeraldBright: Int,
    emeraldDeep: Int,
    goldBase: Int,
    goldBright: Int,
    outline: Int,
    fillPaint: Paint,
    strokePaint: Paint,
    strokeThin: Float,
  ) {
    val midX = tileLeft + tileW * 0.5f
    val midY = tileTop + tileH * 0.5f

    // Geometric gold diamond petal backing
    val diamond = Path().apply {
      moveTo(midX, tileTop + tileH * 0.08f)
      quadTo(tileLeft + tileW * 0.72f, tileTop + tileH * 0.30f, tileLeft + tileW * 0.96f, midY)
      quadTo(tileLeft + tileW * 0.72f, tileTop + tileH * 0.70f, midX, tileTop + tileH * 0.92f)
      quadTo(tileLeft + tileW * 0.28f, tileTop + tileH * 0.70f, tileLeft + tileW * 0.04f, midY)
      quadTo(tileLeft + tileW * 0.28f, tileTop + tileH * 0.30f, midX, tileTop + tileH * 0.08f)
      close()
    }
    fillPaint.color = goldBase
    canvas.drawPath(diamond, fillPaint)
    strokePaint.color = outline
    strokePaint.strokeWidth = strokeThin
    canvas.drawPath(diamond, strokePaint)

    // Inner bright emerald quatrefoil leaf
    val innerLeaf = Path().apply {
      moveTo(midX, tileTop + tileH * 0.20f)
      cubicTo(
        tileLeft + tileW * 0.76f, tileTop + tileH * 0.20f,
        tileLeft + tileW * 0.84f, tileTop + tileH * 0.50f,
        midX, tileTop + tileH * 0.80f,
      )
      cubicTo(
        tileLeft + tileW * 0.16f, tileTop + tileH * 0.50f,
        tileLeft + tileW * 0.24f, tileTop + tileH * 0.20f,
        midX, tileTop + tileH * 0.20f,
      )
      close()
    }
    fillPaint.color = emeraldBright
    canvas.drawPath(innerLeaf, fillPaint)
    canvas.drawPath(innerLeaf, strokePaint)

    // Corner golden trefoil accents
    val cornerR = tileH * 0.14f
    fillPaint.color = goldBright
    canvas.drawCircle(tileLeft, tileTop + tileH * 0.14f, cornerR * 0.55f, fillPaint)
    canvas.drawCircle(tileLeft, tileTop + tileH * 0.86f, cornerR * 0.55f, fillPaint)

    // Central dark-green & gold rosette core
    fillPaint.color = emeraldDeep
    canvas.drawCircle(midX, midY, tileH * 0.14f, fillPaint)
    canvas.drawCircle(midX, midY, tileH * 0.14f, strokePaint)
    fillPaint.color = goldBright
    canvas.drawCircle(midX, midY, tileH * 0.065f, fillPaint)
  }

  private fun drawTajweedMedallion(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    radius: Float,
    topLabel: String,
    numberText: String,
    emeraldDeep: Int,
    emeraldMid: Int,
    goldBase: Int,
    goldBright: Int,
    darkOutline: Int,
    panelWhite: Int,
    ibmMedium: Typeface,
    fillPaint: Paint,
    strokePaint: Paint,
  ) {
    // Subtle scalloped gold rosette collar around the medallion
    val scallops = 12
    val rosettePath = Path()
    for (i in 0 until scallops) {
      val a1 = (i.toFloat() / scallops) * 2f * PI.toFloat()
      val aMid = ((i + 0.5f) / scallops) * 2f * PI.toFloat()
      val a2 = ((i + 1f) / scallops) * 2f * PI.toFloat()
      val rBase = radius * 1.03f
      val rPeak = radius * 1.16f
      val x1 = cx + cos(a1) * rBase
      val y1 = cy + sin(a1) * rBase
      val xm = cx + cos(aMid) * rPeak
      val ym = cy + sin(aMid) * rPeak
      val x2 = cx + cos(a2) * rBase
      val y2 = cy + sin(a2) * rBase
      if (i == 0) rosettePath.moveTo(x1, y1)
      rosettePath.quadTo(xm, ym, x2, y2)
    }
    rosettePath.close()

    fillPaint.color = goldBright
    canvas.drawPath(rosettePath, fillPaint)
    strokePaint.color = darkOutline
    strokePaint.strokeWidth = (radius * 0.06f).coerceAtLeast(0.8f)
    canvas.drawPath(rosettePath, strokePaint)

    // Crisp white separator ring + enlarged emerald interior disc for clear text readability
    fillPaint.color = panelWhite
    canvas.drawCircle(cx, cy, radius * 0.96f, fillPaint)
    canvas.drawCircle(cx, cy, radius * 0.96f, strokePaint)

    fillPaint.shader = LinearGradient(
      cx,
      cy - radius * 0.89f,
      cx,
      cy + radius * 0.89f,
      intArrayOf(emeraldMid, emeraldDeep),
      null,
      Shader.TileMode.CLAMP,
    )
    canvas.drawCircle(cx, cy, radius * 0.88f, fillPaint)
    fillPaint.shader = null

    strokePaint.color = goldBase
    strokePaint.strokeWidth = (radius * 0.055f).coerceAtLeast(0.75f)
    canvas.drawCircle(cx, cy, radius * 0.88f, strokePaint)

    // Two clear, enlarged centered white text lines inside the medallion
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      typeface = ibmMedium
      color = 0xFFFFFFFF.toInt()
      textAlign = Paint.Align.CENTER
    }
    val labelBounds = Rect()
    val maxLabelW = radius * 1.54f
    var labelPx = (radius * 0.56f).coerceAtLeast(8.5f)
    textPaint.textSize = labelPx
    textPaint.getTextBounds(topLabel, 0, topLabel.length, labelBounds)
    while ((textPaint.measureText(topLabel) > maxLabelW || labelBounds.height() > radius * 0.56f) && labelPx > 6f) {
      labelPx *= 0.93f
      textPaint.textSize = labelPx
      textPaint.getTextBounds(topLabel, 0, topLabel.length, labelBounds)
    }
    val labelBaseline = if (labelBounds.height() > 0) {
      cy - radius * 0.24f - (labelBounds.top + labelBounds.bottom) * 0.5f
    } else {
      val labelFm = textPaint.fontMetrics
      cy - radius * 0.22f - (labelFm.ascent + labelFm.descent) * 0.5f
    }
    canvas.drawText(topLabel, cx, labelBaseline, textPaint)

    val numBounds = Rect()
    val maxNumW = radius * 1.46f
    var numPx = (radius * 0.62f).coerceAtLeast(9f)
    textPaint.textSize = numPx
    textPaint.getTextBounds(numberText, 0, numberText.length, numBounds)
    while ((textPaint.measureText(numberText) > maxNumW || numBounds.height() > radius * 0.58f) && numPx > 6.5f) {
      numPx *= 0.93f
      textPaint.textSize = numPx
      textPaint.getTextBounds(numberText, 0, numberText.length, numBounds)
    }
    val numBaseline = if (numBounds.height() > 0) {
      cy + radius * 0.31f - (numBounds.top + numBounds.bottom) * 0.5f
    } else {
      val numFm = textPaint.fontMetrics
      cy + radius * 0.30f - (numFm.ascent + numFm.descent) * 0.5f
    }
    canvas.drawText(numberText, cx, numBaseline, textPaint)
  }

  private fun drawMadinahOrnament(
    canvas: Canvas,
    surah: SurahMeta,
    innerRect: RectF,
    frameDarkStroke: Int,
    panelWhite: Int,
    panelInk: Int,
    amiriBold: Typeface,
    surahNameV4: Typeface?,
    fillPaint: Paint,
    strokePaint: Paint,
    dpFn: (Float) -> Float,
  ) {
    val w = innerRect.width()
    val h = innerRect.height()
    val cx = innerRect.centerX()
    val cy = innerRect.centerY()

    val skyTurquoise = 0xFF43BAE8.toInt()
    val deepAzure = 0xFF1477B0.toInt()
    val darkNavy = 0xFF0E3E62.toInt()
    val rosePetal = 0xFFEE87B5.toInt()
    val roseLight = 0xFFFAD3E5.toInt()
    val goldAccent = 0xFFE5BA52.toInt()

    // 1. Clipped repeating turquoise & floral arabesque wings
    canvas.save()
    canvas.clipRect(innerRect)

    fillPaint.shader = LinearGradient(
      innerRect.left,
      innerRect.top,
      innerRect.left,
      innerRect.bottom,
      intArrayOf(skyTurquoise, deepAzure, skyTurquoise),
      floatArrayOf(0f, 0.5f, 1f),
      Shader.TileMode.CLAMP,
    )
    canvas.drawRect(innerRect, fillPaint)
    fillPaint.shader = null

    val tileW = (h * 0.96f).coerceAtLeast(20f)
    val strokeThin = (h * 0.020f).coerceIn(dpFn(0.75f), dpFn(1.35f))
    val numTilesHalf = ((w * 0.5f) / tileW).toInt() + 2

    for (k in 0 until numTilesHalf) {
      val rxLeft = cx + k * tileW
      drawMadinahFloralTile(
        canvas = canvas,
        tileLeft = rxLeft,
        tileTop = innerRect.top,
        tileW = tileW,
        tileH = h,
        skyTurquoise = skyTurquoise,
        darkNavy = darkNavy,
        rosePetal = rosePetal,
        roseLight = roseLight,
        goldAccent = goldAccent,
        fillPaint = fillPaint,
        strokePaint = strokePaint,
        strokeThin = strokeThin,
      )
      val lxRight = cx - (k + 1) * tileW
      canvas.save()
      canvas.scale(-1f, 1f, lxRight + tileW * 0.5f, cy)
      drawMadinahFloralTile(
        canvas = canvas,
        tileLeft = lxRight,
        tileTop = innerRect.top,
        tileW = tileW,
        tileH = h,
        skyTurquoise = skyTurquoise,
        darkNavy = darkNavy,
        rosePetal = rosePetal,
        roseLight = roseLight,
        goldAccent = goldAccent,
        fillPaint = fillPaint,
        strokePaint = strokePaint,
        strokeThin = strokeThin,
      )
      canvas.restore()
    }
    canvas.restore()

    // Thin horizontal gold & navy trim strips inside the top & bottom edges
    val railH = (h * 0.05f).coerceAtLeast(1.2f)
    fillPaint.color = goldAccent
    canvas.drawRect(innerRect.left, innerRect.top, innerRect.right, innerRect.top + railH, fillPaint)
    canvas.drawRect(innerRect.left, innerRect.bottom - railH, innerRect.right, innerRect.bottom, fillPaint)
    strokePaint.color = darkNavy
    strokePaint.strokeWidth = (h * 0.018f).coerceAtLeast(0.8f)
    canvas.drawLine(innerRect.left, innerRect.top + railH, innerRect.right, innerRect.top + railH, strokePaint)
    canvas.drawLine(innerRect.left, innerRect.bottom - railH, innerRect.right, innerRect.bottom - railH, strokePaint)

    // 2. Center scalloped white cartouche (NO circles on sides for Madinah)
    val cartoucheHalfW = (w * 0.245f).coerceAtLeast(h * 1.8f).coerceAtMost(w * 0.34f)
    val cartoucheRect = RectF(
      cx - cartoucheHalfW,
      innerRect.top + h * 0.06f,
      cx + cartoucheHalfW,
      innerRect.bottom - h * 0.06f,
    )
    val outerCartouchePath = buildScallopedCartouchePath(cartoucheRect, lobeRatio = 0.22f)
    val borderInset = (h * 0.070f).coerceIn(dpFn(1.8f), dpFn(4.0f))
    val innerCartoucheRect = RectF(
      cartoucheRect.left + borderInset,
      cartoucheRect.top + borderInset,
      cartoucheRect.right - borderInset,
      cartoucheRect.bottom - borderInset,
    )
    val innerCartouchePath = buildScallopedCartouchePath(innerCartoucheRect, lobeRatio = 0.22f)

    // Crisp outer azure/turquoise border band around white cartouche
    fillPaint.color = deepAzure
    canvas.drawPath(outerCartouchePath, fillPaint)
    fillPaint.color = panelWhite
    canvas.drawPath(innerCartouchePath, fillPaint)

    strokePaint.color = frameDarkStroke
    strokePaint.strokeWidth = (h * 0.022f).coerceAtLeast(0.9f)
    canvas.drawPath(outerCartouchePath, strokePaint)
    canvas.drawPath(innerCartouchePath, strokePaint)

    val midGoldRect = RectF(
      cartoucheRect.left + borderInset * 0.5f,
      cartoucheRect.top + borderInset * 0.5f,
      cartoucheRect.right - borderInset * 0.5f,
      cartoucheRect.bottom - borderInset * 0.5f,
    )
    strokePaint.color = goldAccent
    strokePaint.strokeWidth = (h * 0.016f).coerceAtLeast(0.7f)
    canvas.drawPath(buildScallopedCartouchePath(midGoldRect, lobeRatio = 0.22f), strokePaint)

    // 3. Surah name only centered inside white cartouche (QUL V4 ligature surah001..surah114)
    if (surahNameV4 != null) {
      drawFittedSurahLigatureTitle(
        canvas = canvas,
        ligatureText = formatSurahLigature(surah.id),
        box = innerCartoucheRect,
        color = panelInk,
        typeface = surahNameV4,
      )
    } else {
      val titleText = if (surah.nameArabic.startsWith("سورة")) {
        surah.nameArabic
      } else {
        "سورة ${surah.nameArabic}"
      }
      drawFittedCenteredTitle(
        canvas = canvas,
        text = titleText,
        box = innerCartoucheRect,
        color = panelInk,
        typeface = amiriBold,
      )
    }
  }

  private fun drawMadinahFloralTile(
    canvas: Canvas,
    tileLeft: Float,
    tileTop: Float,
    tileW: Float,
    tileH: Float,
    skyTurquoise: Int,
    darkNavy: Int,
    rosePetal: Int,
    roseLight: Int,
    goldAccent: Int,
    fillPaint: Paint,
    strokePaint: Paint,
    strokeThin: Float,
  ) {
    val midX = tileLeft + tileW * 0.5f
    val midY = tileTop + tileH * 0.5f

    // Flowing arabesque vine ribbons
    val upperScroll = Path().apply {
      moveTo(tileLeft, midY)
      cubicTo(
        tileLeft + tileW * 0.24f, tileTop + tileH * 0.14f,
        tileLeft + tileW * 0.76f, tileTop + tileH * 0.14f,
        tileLeft + tileW, midY,
      )
    }
    val lowerScroll = Path().apply {
      moveTo(tileLeft, midY)
      cubicTo(
        tileLeft + tileW * 0.24f, tileTop + tileH * 0.86f,
        tileLeft + tileW * 0.76f, tileTop + tileH * 0.86f,
        tileLeft + tileW, midY,
      )
    }
    strokePaint.color = darkNavy
    strokePaint.strokeWidth = strokeThin * 1.35f
    canvas.drawPath(upperScroll, strokePaint)
    canvas.drawPath(lowerScroll, strokePaint)

    strokePaint.color = skyTurquoise
    strokePaint.strokeWidth = strokeThin * 0.7f
    canvas.drawPath(upperScroll, strokePaint)
    canvas.drawPath(lowerScroll, strokePaint)

    // Golden corner leaves flanking the floral rosette
    val leafTop = Path().apply {
      moveTo(midX, tileTop + tileH * 0.11f)
      quadTo(tileLeft + tileW * 0.65f, tileTop + tileH * 0.18f, midX, tileTop + tileH * 0.28f)
      quadTo(tileLeft + tileW * 0.35f, tileTop + tileH * 0.18f, midX, tileTop + tileH * 0.11f)
      close()
    }
    val leafBottom = Path().apply {
      moveTo(midX, tileTop + tileH * 0.89f)
      quadTo(tileLeft + tileW * 0.65f, tileTop + tileH * 0.82f, midX, tileTop + tileH * 0.72f)
      quadTo(tileLeft + tileW * 0.35f, tileTop + tileH * 0.82f, midX, tileTop + tileH * 0.89f)
      close()
    }
    fillPaint.color = goldAccent
    canvas.drawPath(leafTop, fillPaint)
    canvas.drawPath(leafBottom, fillPaint)
    strokePaint.color = darkNavy
    strokePaint.strokeWidth = strokeThin * 0.85f
    canvas.drawPath(leafTop, strokePaint)
    canvas.drawPath(leafBottom, strokePaint)

    // Central 6-petal pink/rose flower rosette
    val petalDist = tileH * 0.155f
    val petalR = tileH * 0.095f
    for (p in 0 until 6) {
      val angle = (p * 60.0 * PI / 180.0).toFloat()
      val px = midX + cos(angle) * petalDist
      val py = midY + sin(angle) * petalDist
      fillPaint.color = if (p % 2 == 0) rosePetal else roseLight
      canvas.drawCircle(px, py, petalR, fillPaint)
      canvas.drawCircle(px, py, petalR, strokePaint)
    }
    fillPaint.color = goldAccent
    canvas.drawCircle(midX, midY, tileH * 0.085f, fillPaint)
    canvas.drawCircle(midX, midY, tileH * 0.085f, strokePaint)
  }

  private fun buildScallopedCartouchePath(rect: RectF, lobeRatio: Float): Path {
    val l = rect.left
    val t = rect.top
    val r = rect.right
    val b = rect.bottom
    val h = rect.height()
    val midY = rect.centerY()
    val shoulder = (h * lobeRatio).coerceAtMost(rect.width() * 0.18f)
    val tipIndent = shoulder * 0.62f

    return Path().apply {
      // Top straight edge
      moveTo(l + shoulder, t)
      lineTo(r - shoulder, t)
      // Right ogee/scalloped end: top corner -> waist -> tip -> bottom waist -> bottom corner
      cubicTo(
        r - shoulder * 0.45f, t,
        r - tipIndent, t + h * 0.28f,
        r, midY,
      )
      cubicTo(
        r - tipIndent, b - h * 0.28f,
        r - shoulder * 0.45f, b,
        r - shoulder, b,
      )
      // Bottom straight edge
      lineTo(l + shoulder, b)
      // Left ogee/scalloped end
      cubicTo(
        l + shoulder * 0.45f, b,
        l + tipIndent, b - h * 0.28f,
        l, midY,
      )
      cubicTo(
        l + tipIndent, t + h * 0.28f,
        l + shoulder * 0.45f, t,
        l + shoulder, t,
      )
      close()
    }
  }

  private fun drawFittedSurahLigatureTitle(
    canvas: Canvas,
    ligatureText: String,
    box: RectF,
    color: Int,
    typeface: Typeface,
  ) {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
      this.typeface = typeface
      this.color = color
      this.textLocale = Locale.US
      this.fontFeatureSettings = "liga"
    }
    val targetInkH = (box.height() * 0.70f).coerceAtLeast(8f)
    val maxAvailW = (box.width() * 0.80f).coerceAtLeast(10f)
    val maxInkH = (box.height() * 0.78f).coerceAtLeast(8f)
    val bounds = Rect()

    var fontSizePx = targetInkH.coerceIn(10f, 120f)
    paint.textSize = fontSizePx
    paint.getTextBounds(ligatureText, 0, ligatureText.length, bounds)

    if (bounds.height() > 0) {
      val scaled = fontSizePx * (targetInkH / bounds.height().toFloat())
      fontSizePx = scaled.coerceIn(8f, box.height() * 1.6f)
      paint.textSize = fontSizePx
      paint.getTextBounds(ligatureText, 0, ligatureText.length, bounds)
    }

    var measuredW = maxOf(paint.measureText(ligatureText), bounds.width().toFloat())
    var inkH = bounds.height().toFloat()
    if (measuredW > maxAvailW && measuredW > 0f) {
      fontSizePx *= (maxAvailW / measuredW)
      paint.textSize = fontSizePx
      paint.getTextBounds(ligatureText, 0, ligatureText.length, bounds)
      measuredW = maxOf(paint.measureText(ligatureText), bounds.width().toFloat())
      inkH = bounds.height().toFloat()
    }
    while ((measuredW > maxAvailW || inkH > maxInkH) && fontSizePx > 7f) {
      fontSizePx *= 0.94f
      paint.textSize = fontSizePx
      paint.getTextBounds(ligatureText, 0, ligatureText.length, bounds)
      measuredW = maxOf(paint.measureText(ligatureText), bounds.width().toFloat())
      inkH = bounds.height().toFloat()
    }

    val drawX = if (bounds.width() > 0) {
      box.centerX() - (bounds.left + bounds.right) * 0.5f
    } else {
      box.centerX() - paint.measureText(ligatureText) * 0.5f
    }
    val baseline = if (bounds.height() > 0) {
      box.centerY() - (bounds.top + bounds.bottom) * 0.5f
    } else {
      val fm = paint.fontMetrics
      box.centerY() - (fm.ascent + fm.descent) * 0.5f
    }
    canvas.drawText(ligatureText, drawX, baseline, paint)
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
    val availW = (box.width() * 0.78f).coerceAtLeast(10f)
    val availH = (box.height() * 0.74f).coerceAtLeast(8f)

    var fontSizePx = (box.height() * 0.56f).coerceIn(10f, 96f)
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
