package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Calm Islamic geometric arabesque and mosque silhouette background drawn purely with Compose vector
 * paths and gradients. Adapts seamlessly to light and dark themes with no external photos or assets.
 */
@Composable
fun IslamicAmbientBackground(
  isDarkTheme: Boolean,
  modifier: Modifier = Modifier,
) {
  val topBg = if (isDarkTheme) Color(0xFF061614) else Color(0xFFF6F4ED)
  val midBg = if (isDarkTheme) Color(0xFF0A1E1B) else Color(0xFFFBF9F3)
  val bottomBg = if (isDarkTheme) Color(0xFF040D0C) else Color(0xFFEEE9DA)

  val patternLineColor = if (isDarkTheme) {
    Color(0xFFE0B45C).copy(alpha = 0.065f)
  } else {
    Color(0xFF12403C).copy(alpha = 0.055f)
  }
  val patternAccentColor = if (isDarkTheme) {
    Color(0xFF2B887E).copy(alpha = 0.08f)
  } else {
    Color(0xFFC29038).copy(alpha = 0.065f)
  }
  val mosqueNearColor = if (isDarkTheme) {
    Color(0xFF143833).copy(alpha = 0.42f)
  } else {
    Color(0xFF12403C).copy(alpha = 0.075f)
  }
  val mosqueFarColor = if (isDarkTheme) {
    Color(0xFF0E2925).copy(alpha = 0.34f)
  } else {
    Color(0xFFC29038).copy(alpha = 0.065f)
  }

  Canvas(modifier = modifier.fillMaxSize()) {
    // 1. Soft atmospheric gradient wash
    drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(topBg, midBg, bottomBg),
      ),
    )

    // Soft radial top glow
    drawCircle(
      brush = Brush.radialGradient(
        colors = listOf(
          if (isDarkTheme) Color(0xFF1B4B44).copy(alpha = 0.22f) else Color(0xFFC29038).copy(alpha = 0.10f),
          Color.Transparent,
        ),
        center = Offset(size.width * 0.5f, size.height * 0.14f),
        radius = size.maxDimension * 0.55f,
      ),
      center = Offset(size.width * 0.5f, size.height * 0.14f),
      radius = size.maxDimension * 0.55f,
    )

    // 2. Subtle geometric 8-pointed star arabesque lattice
    drawArabesqueLattice(
      lineColor = patternLineColor,
      accentColor = patternAccentColor,
    )

    // 3. Calm layered mosque silhouette along the bottom horizon
    drawMosqueSilhouette(
      farColor = mosqueFarColor,
      nearColor = mosqueNearColor,
    )
  }
}

private fun DrawScope.drawArabesqueLattice(
  lineColor: Color,
  accentColor: Color,
) {
  val step = (size.minDimension / 5.5f).coerceIn(110f, 180f)
  val strokeWidth = 1.2f
  val cols = (size.width / step).toInt() + 2
  val rows = ((size.height * 0.78f) / step).toInt() + 2

  for (r in 0 until rows) {
    for (c in 0 until cols) {
      val cx = c * step + if (r % 2 == 1) step * 0.5f else 0f
      val cy = r * step
      val outerR = step * 0.34f
      val innerR = step * 0.17f

      drawEightPointedStar(
        center = Offset(cx, cy),
        outerRadius = outerR,
        innerRadius = innerR,
        color = lineColor,
        strokeWidth = strokeWidth,
      )
      drawCircle(
        color = accentColor,
        radius = step * 0.065f,
        center = Offset(cx, cy),
        style = Stroke(width = strokeWidth),
      )
    }
  }
}

private fun DrawScope.drawEightPointedStar(
  center: Offset,
  outerRadius: Float,
  innerRadius: Float,
  color: Color,
  strokeWidth: Float,
) {
  val path = Path()
  val totalPoints = 16
  for (i in 0 until totalPoints) {
    val radius = if (i % 2 == 0) outerRadius else innerRadius
    val angle = (i * (2.0 * PI / totalPoints)) - PI / 2.0
    val x = center.x + (radius * cos(angle)).toFloat()
    val y = center.y + (radius * sin(angle)).toFloat()
    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
  }
  path.close()
  drawPath(path = path, color = color, style = Stroke(width = strokeWidth))
}

private fun DrawScope.drawMosqueSilhouette(
  farColor: Color,
  nearColor: Color,
) {
  val w = size.width
  val h = size.height
  val baseY = h
  val horizonH = (h * 0.14f).coerceIn(72f, 145f)

  // Far gentle architectural hills + side domes
  val farPath = Path().apply {
    moveTo(0f, baseY)
    lineTo(0f, baseY - horizonH * 0.42f)
    lineTo(w * 0.16f, baseY - horizonH * 0.42f)
    // Left secondary dome
    quadraticTo(w * 0.22f, baseY - horizonH * 0.85f, w * 0.28f, baseY - horizonH * 0.42f)
    lineTo(w * 0.72f, baseY - horizonH * 0.42f)
    // Right secondary dome
    quadraticTo(w * 0.78f, baseY - horizonH * 0.85f, w * 0.84f, baseY - horizonH * 0.42f)
    lineTo(w, baseY - horizonH * 0.42f)
    lineTo(w, baseY)
    close()
  }
  drawPath(path = farPath, color = farColor)

  // Foreground central dome & twin minarets
  val cx = w * 0.5f
  val domeHalfW = (w * 0.15f).coerceIn(58f, 120f)
  val domeBaseY = baseY - horizonH * 0.34f
  val domePeakY = baseY - horizonH * 1.15f
  val minaretOffset = domeHalfW * 1.55f
  val minaretW = (w * 0.024f).coerceIn(10f, 18f)
  val minaretTopY = baseY - horizonH * 1.48f

  val nearPath = Path().apply {
    moveTo(0f, baseY)
    lineTo(0f, baseY - horizonH * 0.24f)
    lineTo(cx - minaretOffset - minaretW * 0.5f, baseY - horizonH * 0.24f)

    // Left Minaret
    lineTo(cx - minaretOffset - minaretW * 0.4f, minaretTopY + minaretW)
    quadraticTo(cx - minaretOffset, minaretTopY - minaretW * 0.9f, cx - minaretOffset + minaretW * 0.4f, minaretTopY + minaretW)
    lineTo(cx - minaretOffset + minaretW * 0.5f, domeBaseY)

    // Left shoulder to central dome
    lineTo(cx - domeHalfW, domeBaseY)
    cubicTo(
      cx - domeHalfW * 0.92f, domeBaseY - (domeBaseY - domePeakY) * 0.65f,
      cx - domeHalfW * 0.28f, domePeakY + (domeBaseY - domePeakY) * 0.08f,
      cx, domePeakY,
    )
    cubicTo(
      cx + domeHalfW * 0.28f, domePeakY + (domeBaseY - domePeakY) * 0.08f,
      cx + domeHalfW * 0.92f, domeBaseY - (domeBaseY - domePeakY) * 0.65f,
      cx + domeHalfW, domeBaseY,
    )

    // Right Minaret
    lineTo(cx + minaretOffset - minaretW * 0.5f, domeBaseY)
    lineTo(cx + minaretOffset - minaretW * 0.4f, minaretTopY + minaretW)
    quadraticTo(cx + minaretOffset, minaretTopY - minaretW * 0.9f, cx + minaretOffset + minaretW * 0.4f, minaretTopY + minaretW)
    lineTo(cx + minaretOffset + minaretW * 0.5f, baseY - horizonH * 0.24f)

    lineTo(w, baseY - horizonH * 0.24f)
    lineTo(w, baseY)
    close()
  }
  drawPath(path = nearPath, color = nearColor)
}
