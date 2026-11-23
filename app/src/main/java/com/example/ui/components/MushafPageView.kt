package com.example.ui.components

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import com.example.R
import com.example.data.prefs.BackgroundTheme
import com.example.data.quran.MushafEdition
import com.example.data.quran.SurahMeta
import com.example.ui.theme.QabasatGold
import com.example.ui.theme.QabasatNightGold
import com.example.viewmodel.PageLoadState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val arabicIndicDigits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')

private fun toArabicIndicDigits(number: Int): String {
  val raw = number.toString()
  return buildString(raw.length) {
    for (i in raw.indices) {
      val ch = raw[i]
      append(if (ch in '0'..'9') arabicIndicDigits[ch - '0'] else ch)
    }
  }
}

/**
 * Equivalent to CSS `filter: invert(1) hue-rotate(180deg)`:
 * RGB inversion (`scale -1, translate 255`) followed by W3C `hue-rotate(180deg)`.
 * Maps pure white (#FFFFFF) -> pure black (#000000), black -> white, and preserves Tajweed hues.
 */
internal fun createPdfDarkModeColorMatrix(): ColorMatrix {
  val invert = ColorMatrix(
    floatArrayOf(
      -1f, 0f, 0f, 0f, 255f,
      0f, -1f, 0f, 0f, 255f,
      0f, 0f, -1f, 0f, 255f,
      0f, 0f, 0f, 1f, 0f,
    ),
  )
  val hueRotate180 = ColorMatrix(
    floatArrayOf(
      -0.574f, 1.430f, 0.144f, 0f, 0f,
      0.426f, 0.430f, 0.144f, 0f, 0f,
      0.426f, 1.430f, -0.856f, 0f, 0f,
      0f, 0f, 0f, 1f, 0f,
    ),
  )
  hueRotate180.timesAssign(invert)
  return hueRotate180
}

private fun DrawScope.drawHighlightRects(
  coords: FloatArray,
  scaleX: Float,
  scaleY: Float,
  padX: Float,
  padY: Float,
  corner: CornerRadius,
  stroke: Stroke,
  fillColor: Color,
  strokeColor: Color,
) {
  var i = 0
  while (i + 3 < coords.size) {
    val left = (coords[i] * scaleX - padX).coerceAtLeast(0f)
    val top = (coords[i + 1] * scaleY - padY).coerceAtLeast(0f)
    val right = (coords[i + 2] * scaleX + padX).coerceAtMost(size.width)
    val bottom = (coords[i + 3] * scaleY + padY).coerceAtMost(size.height)
    val rectSize = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f))
    val topLeft = Offset(left, top)
    drawRoundRect(
      color = fillColor,
      topLeft = topLeft,
      size = rectSize,
      cornerRadius = corner,
    )
    drawRoundRect(
      color = strokeColor,
      topLeft = topLeft,
      size = rectSize,
      cornerRadius = corner,
      style = stroke,
    )
    i += 4
  }
}

private fun DrawScope.drawActiveRosetteHighlight(
  slots: FloatArray,
  scaleX: Float,
  scaleY: Float,
  goldColor: Color,
  isDarkTheme: Boolean,
  reusablePath: Path,
) {
  val scaleAvg = (scaleX + scaleY) * 0.5f
  val fillColor = goldColor.copy(alpha = if (isDarkTheme) 0.28f else 0.22f)
  var i = 0
  while (i + 2 < slots.size) {
    val cx = slots[i] * scaleX
    val cy = slots[i + 1] * scaleY
    val minDim = slots[i + 2] * scaleAvg
    val outerR = minDim * 0.46f
    val innerR = minDim * 0.37f
    reusablePath.reset()
    for (p in 0 until 16) {
      val angle = (2.0 * PI * p) / 16.0 - PI / 2.0
      val r = if (p % 2 == 0) outerR else innerR
      val px = cx + (r * cos(angle)).toFloat()
      val py = cy + (r * sin(angle)).toFloat()
      if (p == 0) reusablePath.moveTo(px, py) else reusablePath.lineTo(px, py)
    }
    reusablePath.close()
    drawPath(path = reusablePath, color = fillColor)
    drawPath(
      path = reusablePath,
      color = goldColor,
      style = Stroke(width = (minDim * 0.075f).coerceIn(1.0f, 2.4f)),
    )
    i += 3
  }
}

@Composable
fun MushafPageFrame(
  pageNumber: Int,
  pageState: PageLoadState?,
  backgroundTheme: BackgroundTheme,
  isBookmarked: Boolean,
  isDarkTheme: Boolean,
  activePlayingVerseKeyProvider: () -> String?,
  selectedVerseKeyProvider: () -> String?,
  onToggleBookmark: () -> Unit,
  onRetry: () -> Unit,
  onPageTapped: () -> Unit,
  onVerseLongPressed: (verseKey: String, pageNumber: Int, verseCodeV2Line: String) -> Unit,
  modifier: Modifier = Modifier,
  isPdfMode: Boolean = false,
) {
  val effectiveDark = isDarkTheme || backgroundTheme == BackgroundTheme.BLACK
  val pdfColorFilter = remember(isPdfMode, effectiveDark) {
    if (isPdfMode && effectiveDark) {
      ColorFilter.colorMatrix(createPdfDarkModeColorMatrix())
    } else {
      null
    }
  }
  val goldColor = if (effectiveDark) QabasatNightGold else QabasatGold
  val paperColor = if (effectiveDark) Color.Black else Color.White
  val headerTint = if (effectiveDark) QabasatNightGold else Color(0xFF12403C)
  val mutedInk = if (effectiveDark) Color.White.copy(alpha = 0.78f) else Color(0xFF424A47)
  val haptic = LocalHapticFeedback.current

  val activeFillColor = remember(effectiveDark) {
    if (effectiveDark) Color(0xFFE0B45C).copy(alpha = 0.36f) else Color(0xFFFFD54F).copy(alpha = 0.52f)
  }
  val activeStrokeColor = remember(effectiveDark) {
    if (effectiveDark) Color(0xFFFFD54F).copy(alpha = 0.72f) else Color(0xFFC9971C).copy(alpha = 0.72f)
  }
  val selectedFillColor = remember(effectiveDark) {
    if (effectiveDark) Color(0xFF4DB6AC).copy(alpha = 0.22f) else Color(0xFF12403C).copy(alpha = 0.12f)
  }
  val selectedStrokeColor = remember(effectiveDark) {
    if (effectiveDark) Color(0xFF4DB6AC).copy(alpha = 0.48f) else Color(0xFF12403C).copy(alpha = 0.32f)
  }
  val reusableRosettePath = remember { Path() }

  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Box(
      modifier = modifier
        .fillMaxSize()
        .padding(horizontal = 3.dp, vertical = 2.dp),
    ) {
      when (pageState) {
        null, PageLoadState.Loading -> {
          // Light placeholder with flat paper surface and border while background bitmap renders
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(paperColor, RoundedCornerShape(10.dp))
              .border(1.dp, goldColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
              .pointerInput(onPageTapped) {
                detectTapGestures(onTap = { onPageTapped() })
              },
            contentAlignment = Alignment.Center,
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
              CircularProgressIndicator(
                color = headerTint,
                strokeWidth = 2.dp,
                modifier = Modifier.size(26.dp),
              )
              Spacer(Modifier.height(8.dp))
              Text(
                text = "صفحة ${toArabicIndicDigits(pageNumber)}",
                style = MaterialTheme.typography.bodyMedium,
                color = mutedInk,
              )
            }
          }
        }

        is PageLoadState.Error -> {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(paperColor, RoundedCornerShape(10.dp))
              .border(1.dp, goldColor.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
              .pointerInput(onPageTapped) {
                detectTapGestures(onTap = { onPageTapped() })
              },
            contentAlignment = Alignment.Center,
          ) {
            Column(
              horizontalAlignment = Alignment.CenterHorizontally,
              modifier = Modifier.padding(16.dp),
            ) {
              Text(
                text = pageState.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
              )
              Spacer(Modifier.height(12.dp))
              Button(
                onClick = onRetry,
                modifier = Modifier.testTag("retry_page_$pageNumber"),
              ) {
                Text("إعادة المحاولة")
              }
            }
          }
        }

        is PageLoadState.Ready -> {
          val rendered = pageState.renderedPage
          val pdfContent = rendered.pdfContent
          val density = LocalDensity.current
          BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val containerW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val containerH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
            val pdfLayout = remember(pdfContent, containerW, containerH, rendered.widthPx, rendered.heightPx, density.density) {
              if (pdfContent == null) {
                null
              } else {
                val sx = containerW / rendered.widthPx.toFloat().coerceAtLeast(1f)
                val sy = containerH / rendered.heightPx.toFloat().coerceAtLeast(1f)
                computePdfPageLayout(
                  frameLeft = pdfContent.bodyLeftPx * sx,
                  frameTop = pdfContent.bodyTopPx * sy,
                  frameRight = pdfContent.bodyRightPx * sx,
                  frameBottom = pdfContent.bodyBottomPx * sy,
                  bmpWidthPx = pdfContent.bmpWidthPx,
                  bmpHeightPx = pdfContent.bmpHeightPx,
                  segmentation = pdfContent.segmentation,
                  hasTopSurahBanner = pdfContent.topSurah != null,
                  densityPxPerDp = density.density,
                )
              }
            }

            Canvas(
              modifier = Modifier
                .fillMaxSize()
                .pointerInput(rendered, isPdfMode, onPageTapped, onVerseLongPressed) {
                  if (isPdfMode) {
                    detectTapGestures(onTap = { onPageTapped() })
                  } else {
                    detectTapGestures(
                      onTap = { onPageTapped() },
                      onLongPress = { tapOffset ->
                        val viewW = size.width.toFloat().coerceAtLeast(1f)
                        val viewH = size.height.toFloat().coerceAtLeast(1f)
                        val sx = rendered.widthPx.toFloat() / viewW
                        val sy = rendered.heightPx.toFloat() / viewH
                        val tx = tapOffset.x * sx
                        val ty = tapOffset.y * sy
                        val boxes = rendered.wordHitBoxes
                        var hitIdx = -1
                        var bestDistSq = Float.POSITIVE_INFINITY
                        for (i in 0 until boxes.count) {
                          val l = boxes.lefts[i]
                          val t = boxes.tops[i]
                          val r = boxes.rights[i]
                          val b = boxes.bottoms[i]
                          if (tx in (l - 10f)..(r + 10f) && ty in (t - 12f)..(b + 12f)) {
                            hitIdx = i
                            break
                          }
                          val cx = (l + r) * 0.5f
                          val cy = (t + b) * 0.5f
                          val dx = tx - cx
                          val dy = ty - cy
                          val dSq = dx * dx + dy * dy * 4f
                          if (dSq < bestDistSq) {
                            bestDistSq = dSq
                            hitIdx = i
                          }
                        }
                        if (hitIdx >= 0) {
                          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                          onVerseLongPressed(
                            boxes.verseKeys[hitIdx],
                            rendered.pageNumber,
                            boxes.codeV2Lines[hitIdx],
                          )
                        }
                      },
                    )
                  }
                }
                .testTag("mushaf_page_canvas_$pageNumber"),
            ) {
              val dstW = size.width.roundToInt().coerceAtLeast(1)
              val dstH = size.height.roundToInt().coerceAtLeast(1)
              drawImage(
                image = rendered.imageBitmap,
                dstSize = IntSize(dstW, dstH),
                filterQuality = FilterQuality.High,
              )

              if (pdfContent != null && pdfLayout != null) {
                // 1. Pinned top band (riwaya label)
                if (pdfLayout.topBandDstHeight > 0) {
                  val labelSrcH = (pdfContent.segmentation.labelCutFraction * pdfContent.bmpHeightPx)
                    .roundToInt()
                    .coerceIn(1, pdfContent.bmpHeightPx)
                  drawImage(
                    image = pdfContent.pdfBitmap,
                    srcOffset = IntOffset(0, 0),
                    srcSize = IntSize(pdfContent.bmpWidthPx, labelSrcH),
                    dstOffset = IntOffset(pdfLayout.topBandDstLeft, pdfLayout.topBandDstTop),
                    dstSize = IntSize(pdfLayout.topBandDstWidth, pdfLayout.topBandDstHeight),
                    filterQuality = FilterQuality.High,
                    colorFilter = pdfColorFilter,
                  )
                }

                // 2. Quran text block: either evenly spread line strips (3..11 lines) or scaled single block
                if (pdfLayout.isFewLines && pdfLayout.lineLayouts.isNotEmpty()) {
                  val maxBmpX = (pdfContent.bmpWidthPx - 1).coerceAtLeast(0)
                  val maxBmpY = (pdfContent.bmpHeightPx - 1).coerceAtLeast(0)
                  for (lineMetrics in pdfLayout.lineLayouts) {
                    val safeLeft = lineMetrics.srcLeftPx.coerceIn(0, maxBmpX)
                    val safeTop = lineMetrics.srcTopPx.coerceIn(0, maxBmpY)
                    val safeW = lineMetrics.srcWidthPx.coerceIn(1, (pdfContent.bmpWidthPx - safeLeft).coerceAtLeast(1))
                    val safeH = lineMetrics.srcHeightPx.coerceIn(1, (pdfContent.bmpHeightPx - safeTop).coerceAtLeast(1))
                    drawImage(
                      image = pdfContent.pdfBitmap,
                      srcOffset = IntOffset(safeLeft, safeTop),
                      srcSize = IntSize(safeW, safeH),
                      dstOffset = IntOffset(lineMetrics.dstLeftPx, lineMetrics.dstTopPx),
                      dstSize = IntSize(lineMetrics.dstWidthPx, lineMetrics.dstHeightPx),
                      filterQuality = FilterQuality.High,
                      colorFilter = pdfColorFilter,
                    )
                  }
                } else {
                  val maxBmpX = (pdfContent.bmpWidthPx - 1).coerceAtLeast(0)
                  val maxBmpY = (pdfContent.bmpHeightPx - 1).coerceAtLeast(0)
                  val srcLeft = pdfLayout.textSrcLeftPx.coerceIn(0, maxBmpX)
                  val srcTop = pdfLayout.textSrcTopPx.coerceIn(0, maxBmpY)
                  val textSrcW = pdfLayout.textSrcWidthPx.coerceIn(1, (pdfContent.bmpWidthPx - srcLeft).coerceAtLeast(1))
                  val textSrcH = pdfLayout.textSrcHeightPx.coerceIn(1, (pdfContent.bmpHeightPx - srcTop).coerceAtLeast(1))
                  drawImage(
                    image = pdfContent.pdfBitmap,
                    srcOffset = IntOffset(srcLeft, srcTop),
                    srcSize = IntSize(textSrcW, textSrcH),
                    dstOffset = IntOffset(pdfLayout.textDstLeft, pdfLayout.textDstTop),
                    dstSize = IntSize(pdfLayout.textDstWidth, pdfLayout.textDstHeight),
                    filterQuality = FilterQuality.High,
                    colorFilter = pdfColorFilter,
                  )
                }

                // 3. Pinned bottom legend band (complete in one piece, drawn once)
                if (pdfLayout.bottomBandDstHeight > 0) {
                  val maxBmpY = (pdfContent.bmpHeightPx - 1).coerceAtLeast(0)
                  val legendSrcY = (pdfContent.segmentation.legendCutFraction * pdfContent.bmpHeightPx)
                    .roundToInt().coerceIn(0, maxBmpY)
                  val botSrcH = (pdfContent.bmpHeightPx - legendSrcY).coerceAtLeast(1)
                  drawImage(
                    image = pdfContent.pdfBitmap,
                    srcOffset = IntOffset(0, legendSrcY),
                    srcSize = IntSize(pdfContent.bmpWidthPx, botSrcH),
                    dstOffset = IntOffset(pdfLayout.bottomBandDstLeft, pdfLayout.bottomBandDstTop),
                    dstSize = IntSize(pdfLayout.bottomBandDstWidth, pdfLayout.bottomBandDstHeight),
                    filterQuality = FilterQuality.High,
                    colorFilter = pdfColorFilter,
                  )
                }
              }

              if (isPdfMode || pdfContent != null) return@Canvas
              val activeKey = activePlayingVerseKeyProvider()
              val selectedKey = selectedVerseKeyProvider()
              if (activeKey == null && selectedKey == null) return@Canvas

              val scaleX = size.width / rendered.widthPx.toFloat().coerceAtLeast(1f)
              val scaleY = size.height / rendered.heightPx.toFloat().coerceAtLeast(1f)
              val padX = 2.5.dp.toPx()
              val padY = 1.dp.toPx()
              val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())
              val highlightStroke = Stroke(width = 1.dp.toPx())

              if (selectedKey != null && selectedKey != activeKey) {
                rendered.verseHighlightRects[selectedKey]?.let { rects ->
                  drawHighlightRects(
                    coords = rects,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    padX = padX,
                    padY = padY,
                    corner = corner,
                    stroke = highlightStroke,
                    fillColor = selectedFillColor,
                    strokeColor = selectedStrokeColor,
                  )
                }
              }

              if (activeKey != null) {
                rendered.verseHighlightRects[activeKey]?.let { rects ->
                  drawHighlightRects(
                    coords = rects,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    padX = padX,
                    padY = padY,
                    corner = corner,
                    stroke = highlightStroke,
                    fillColor = activeFillColor,
                    strokeColor = activeStrokeColor,
                  )
                }
                rendered.rosetteSlotsByVerse[activeKey]?.let { slots ->
                  drawActiveRosetteHighlight(
                    slots = slots,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    goldColor = goldColor,
                    isDarkTheme = effectiveDark,
                    reusablePath = reusableRosettePath,
                  )
                }
              }
            }

            val topSurah = pdfContent?.topSurah
            if (topSurah != null && pdfLayout != null && pdfLayout.bannerWidth > 4f && pdfLayout.bannerHeight > 4f) {
              PdfTopSurahBanner(
                surah = topSurah,
                effectiveDark = effectiveDark,
                modifier = Modifier
                  .offset {
                    IntOffset(pdfLayout.bannerLeft.roundToInt(), pdfLayout.bannerTop.roundToInt())
                  }
                  .size(
                    width = with(density) { pdfLayout.bannerWidth.toDp() },
                    height = with(density) { pdfLayout.bannerHeight.toDp() },
                  )
                  .testTag("pdf_top_surah_banner_$pageNumber"),
              )
            }

            // Compact interactive bookmark toggle aligned strictly within the slim footer row
            Box(
              contentAlignment = Alignment.Center,
              modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 7.dp)
                .size(width = 32.dp, height = 18.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable(
                  role = Role.Button,
                  onClick = onToggleBookmark,
                )
                .testTag("bookmark_page_$pageNumber"),
            ) {
              Icon(
                imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                contentDescription = "علامة مرجعية للصفحة",
                tint = if (isBookmarked) goldColor else mutedInk,
                modifier = Modifier.size(13.dp),
              )
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PdfTopSurahBanner(
  surah: SurahMeta,
  effectiveDark: Boolean,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val amiriBold = remember(context) {
    val base = runCatching { ResourcesCompat.getFont(context, R.font.amiri) }.getOrNull() ?: Typeface.SERIF
    Typeface.create(base, Typeface.BOLD)
  }
  val ibmMedium = remember(context) {
    val base = runCatching { ResourcesCompat.getFont(context, R.font.ibm_plex_sans_arabic) }.getOrNull()
      ?: Typeface.SANS_SERIF
    Typeface.create(base, Typeface.BOLD)
  }
  val surahNameV4 = remember(context) {
    runCatching { ResourcesCompat.getFont(context, R.font.surah_name_v4) }.getOrNull()
  }
  val quranCommon = remember(context) {
    runCatching { ResourcesCompat.getFont(context, R.font.quran_common) }.getOrNull()
  }

  Canvas(modifier = modifier) {
    drawIntoCanvas { canvas ->
      SurahHeaderOrnamentRenderer.drawSurahHeaderBand(
        canvas = canvas.nativeCanvas,
        surah = surah,
        edition = MushafEdition.TAJWEED_V4,
        effectiveDark = effectiveDark,
        left = 0f,
        top = 0f,
        width = size.width,
        height = size.height,
        amiriBold = amiriBold,
        ibmMedium = ibmMedium,
        surahNameV4 = surahNameV4,
        quranCommon = quranCommon,
        dpFn = { dpVal -> dpVal * density },
      )
    }
  }
}
