package com.example.ui.components

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
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
import androidx.compose.ui.zIndex
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

@Immutable
data class PageSelectionRootRect(
  val left: Float,
  val top: Float,
  val right: Float,
  val bottom: Float,
)

private fun findHitWordIndex(
  boxes: WordHitBoxes,
  tx: Float,
  ty: Float,
): Int {
  for (i in 0 until boxes.count) {
    val l = boxes.lefts[i]
    val t = boxes.tops[i]
    val r = boxes.rights[i]
    val b = boxes.bottoms[i]
    if (tx in (l - 8f)..(r + 8f) && ty in (t - 8f)..(b + 8f)) {
      return i
    }
  }
  // Narrow inter-word horizontal gap tolerance strictly on the same text line
  var bestIdx = -1
  var bestHorizDist = 18f
  for (i in 0 until boxes.count) {
    val l = boxes.lefts[i]
    val t = boxes.tops[i]
    val r = boxes.rights[i]
    val b = boxes.bottoms[i]
    if (ty in (t - 6f)..(b + 6f)) {
      val dx = when {
        tx < l -> l - tx
        tx > r -> tx - r
        else -> 0f
      }
      if (dx <= bestHorizDist) {
        bestHorizDist = dx
        bestIdx = i
      }
    }
  }
  return bestIdx
}

private fun findNearestWordIndexForDrag(
  boxes: WordHitBoxes,
  tx: Float,
  ty: Float,
): Int {
  val exact = findHitWordIndex(boxes, tx, ty)
  if (exact >= 0) return exact
  if (boxes.count <= 0) return -1
  var bestIdx = -1
  var bestDistSq = Float.POSITIVE_INFINITY
  for (i in 0 until boxes.count) {
    val l = boxes.lefts[i]
    val t = boxes.tops[i]
    val r = boxes.rights[i]
    val b = boxes.bottoms[i]
    val dx = when {
      tx < l -> l - tx
      tx > r -> tx - r
      else -> 0f
    }
    val dy = when {
      ty < t -> t - ty
      ty > b -> ty - b
      else -> 0f
    }
    val score = dx * dx + (dy * 1.8f) * (dy * 1.8f)
    if (score < bestDistSq) {
      bestDistSq = score
      bestIdx = i
    }
  }
  return bestIdx
}

private fun parseVerseSortPair(vk: String): Pair<Int, Int>? {
  val p = vk.split(":")
  val s = p.getOrNull(0)?.toIntOrNull() ?: return null
  val a = p.getOrNull(1)?.toIntOrNull() ?: return null
  return s to a
}

@Composable
fun MushafPageFrame(
  pageNumber: Int,
  pageState: PageLoadState?,
  backgroundTheme: BackgroundTheme,
  isBookmarked: Boolean,
  isDarkTheme: Boolean,
  activePlayingVerseKeyProvider: () -> String?,
  selectedVerseKeysProvider: () -> Set<String>,
  onToggleBookmark: () -> Unit,
  onRetry: () -> Unit,
  onNonWordTapped: () -> Unit,
  onWordTapped: (verseKey: String, pageNumber: Int, verseCodeV2Line: String) -> Unit,
  onWordLongPressed: (verseKey: String, pageNumber: Int, verseCodeV2Line: String) -> Unit,
  onSelectionHandleDragged: (isStart: Boolean, targetVerseKey: String, pageNumber: Int, verseCodeV2Line: String) -> Unit = { _, _, _, _ -> },
  onSelectionBoundsChanged: (pageNumber: Int, rectInRoot: PageSelectionRootRect?) -> Unit = { _, _ -> },
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
    if (effectiveDark) Color(0xFF42A5F5).copy(alpha = 0.30f) else Color(0xFF2196F3).copy(alpha = 0.22f)
  }
  val selectedStrokeColor = remember(effectiveDark) {
    if (effectiveDark) Color(0xFF64B5F6).copy(alpha = 0.65f) else Color(0xFF1976D2).copy(alpha = 0.52f)
  }
  val reusableRosettePath = remember { Path() }

  DisposableEffect(pageNumber) {
    onDispose {
      onSelectionBoundsChanged(pageNumber, null)
    }
  }

  CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    Box(
      modifier = modifier.fillMaxSize(),
    ) {
      when (pageState) {
        null, PageLoadState.Loading -> {
          // Light placeholder with flat paper surface and border while background bitmap renders
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(paperColor, RoundedCornerShape(4.dp))
              .border(1.dp, goldColor.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
              .pointerInput(onNonWordTapped) {
                detectTapGestures(onTap = { onNonWordTapped() })
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
              .background(paperColor, RoundedCornerShape(4.dp))
              .border(1.dp, goldColor.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
              .pointerInput(onNonWordTapped) {
                detectTapGestures(onTap = { onNonWordTapped() })
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

            var canvasCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            val currentSelectedKeys = selectedVerseKeysProvider()

            fun publishBounds(coords: LayoutCoordinates?) {
              if (coords == null || !coords.isAttached || isPdfMode || pdfContent != null || currentSelectedKeys.isEmpty()) {
                onSelectionBoundsChanged(pageNumber, null)
                return
              }
              val viewW = coords.size.width.toFloat()
              val viewH = coords.size.height.toFloat()
              if (viewW <= 1f || viewH <= 1f) {
                onSelectionBoundsChanged(pageNumber, null)
                return
              }
              val scaleX = viewW / rendered.widthPx.toFloat().coerceAtLeast(1f)
              val scaleY = viewH / rendered.heightPx.toFloat().coerceAtLeast(1f)
              var minL = Float.POSITIVE_INFINITY
              var minT = Float.POSITIVE_INFINITY
              var maxR = Float.NEGATIVE_INFINITY
              var maxB = Float.NEGATIVE_INFINITY
              var foundAny = false
              for (vk in currentSelectedKeys) {
                val rects = rendered.verseHighlightRects[vk] ?: continue
                var i = 0
                while (i + 3 < rects.size) {
                  val l = rects[i] * scaleX
                  val t = rects[i + 1] * scaleY
                  val r = rects[i + 2] * scaleX
                  val b = rects[i + 3] * scaleY
                  if (l < minL) minL = l
                  if (t < minT) minT = t
                  if (r > maxR) maxR = r
                  if (b > maxB) maxB = b
                  foundAny = true
                  i += 4
                }
              }
              if (!foundAny) {
                onSelectionBoundsChanged(pageNumber, null)
              } else {
                val topLeftRoot = coords.localToRoot(Offset(minL, minT))
                val bottomRightRoot = coords.localToRoot(Offset(maxR, maxB))
                onSelectionBoundsChanged(
                  pageNumber,
                  PageSelectionRootRect(
                    left = topLeftRoot.x,
                    top = topLeftRoot.y,
                    right = bottomRightRoot.x,
                    bottom = bottomRightRoot.y,
                  ),
                )
              }
            }

            LaunchedEffect(currentSelectedKeys, rendered, isPdfMode, pdfContent, canvasCoords) {
              publishBounds(canvasCoords)
            }

            Canvas(
              modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { coords ->
                  canvasCoords = coords
                  publishBounds(coords)
                }
                .pointerInput(rendered, isPdfMode, onNonWordTapped, onWordTapped, onWordLongPressed) {
                  if (isPdfMode) {
                    awaitPointerEventScope {
                      while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.pressed }) {
                          onNonWordTapped()
                          break
                        }
                      }
                    }
                  } else {
                    awaitEachGesture {
                      val down = awaitFirstDown(requireUnconsumed = false)
                      val downPos = down.position
                      val pointerId = down.id
                      val slop = viewConfiguration.touchSlop
                      var movedBeyondSlop = false
                      var upChange: PointerInputChange? = null

                      try {
                        withTimeout(1500L) {
                          while (true) {
                            val event = awaitPointerEvent()
                            val ch = event.changes.firstOrNull { it.id == pointerId }
                            if (ch == null || !ch.pressed) {
                              upChange = ch
                              break
                            }
                            if ((ch.position - downPos).getDistance() > slop) {
                              movedBeyondSlop = true
                              break
                            }
                          }
                        }

                        if (!movedBeyondSlop) {
                          upChange?.consume()
                          val viewW = size.width.toFloat().coerceAtLeast(1f)
                          val viewH = size.height.toFloat().coerceAtLeast(1f)
                          val sx = rendered.widthPx.toFloat() / viewW
                          val sy = rendered.heightPx.toFloat() / viewH
                          val tx = downPos.x * sx
                          val ty = downPos.y * sy
                          val boxes = rendered.wordHitBoxes
                          val hitIdx = findHitWordIndex(boxes, tx, ty)
                          if (hitIdx >= 0) {
                            onWordTapped(
                              boxes.verseKeys[hitIdx],
                              rendered.pageNumber,
                              boxes.codeV2Lines[hitIdx],
                            )
                          } else {
                            onNonWordTapped()
                          }
                        }
                      } catch (_: androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException) {
                        val viewW = size.width.toFloat().coerceAtLeast(1f)
                        val viewH = size.height.toFloat().coerceAtLeast(1f)
                        val sx = rendered.widthPx.toFloat() / viewW
                        val sy = rendered.heightPx.toFloat() / viewH
                        val tx = downPos.x * sx
                        val ty = downPos.y * sy
                        val boxes = rendered.wordHitBoxes
                        val hitIdx = findHitWordIndex(boxes, tx, ty)
                        if (hitIdx >= 0) {
                          haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                          onWordLongPressed(
                            boxes.verseKeys[hitIdx],
                            rendered.pageNumber,
                            boxes.codeV2Lines[hitIdx],
                          )
                        }
                        while (true) {
                          val event = awaitPointerEvent()
                          event.changes.forEach { it.consume() }
                          if (event.changes.all { !it.pressed }) break
                        }
                      }
                    }
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
              val selectedKeys = selectedVerseKeysProvider()
              if (activeKey == null && selectedKeys.isEmpty()) return@Canvas

              val scaleX = size.width / rendered.widthPx.toFloat().coerceAtLeast(1f)
              val scaleY = size.height / rendered.heightPx.toFloat().coerceAtLeast(1f)
              val padX = 2.5.dp.toPx()
              val padY = 1.dp.toPx()
              val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())
              val highlightStroke = Stroke(width = 1.dp.toPx())

              for (selectedKey in selectedKeys) {
                if (selectedKey == activeKey) continue
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

            if (!isPdfMode && pdfContent == null && currentSelectedKeys.isNotEmpty()) {
              val sortedSelected = remember(currentSelectedKeys) {
                currentSelectedKeys.sortedWith(
                  compareBy<String>(
                    { parseVerseSortPair(it)?.first ?: Int.MAX_VALUE },
                    { parseVerseSortPair(it)?.second ?: Int.MAX_VALUE },
                  ),
                )
              }
              val scaleX = containerW / rendered.widthPx.toFloat().coerceAtLeast(1f)
              val scaleY = containerH / rendered.heightPx.toFloat().coerceAtLeast(1f)
              val startKey = sortedSelected.firstOrNull()
              val endKey = sortedSelected.lastOrNull()
              val startRects = startKey?.let { rendered.verseHighlightRects[it] }
              val endRects = endKey?.let { rendered.verseHighlightRects[it] }

              if (startRects != null && startRects.size >= 4) {
                val anchorX = (startRects[2] * scaleX).coerceIn(0f, containerW)
                val anchorY = (startRects[3] * scaleY).coerceIn(0f, containerH)
                SelectionDragHandle(
                  isStart = true,
                  anchorX = anchorX,
                  anchorY = anchorY,
                  containerW = containerW,
                  containerH = containerH,
                  rendered = rendered,
                  handleColor = if (effectiveDark) Color(0xFF64B5F6) else Color(0xFF1976D2),
                  onHandleDragged = onSelectionHandleDragged,
                )
              }
              if (endRects != null && endRects.size >= 4) {
                val n = endRects.size
                val anchorX = (endRects[n - 4] * scaleX).coerceIn(0f, containerW)
                val anchorY = (endRects[n - 1] * scaleY).coerceIn(0f, containerH)
                SelectionDragHandle(
                  isStart = false,
                  anchorX = anchorX,
                  anchorY = anchorY,
                  containerW = containerW,
                  containerH = containerH,
                  rendered = rendered,
                  handleColor = if (effectiveDark) Color(0xFF64B5F6) else Color(0xFF1976D2),
                  onHandleDragged = onSelectionHandleDragged,
                )
              }
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
private fun SelectionDragHandle(
  isStart: Boolean,
  anchorX: Float,
  anchorY: Float,
  containerW: Float,
  containerH: Float,
  rendered: RenderedMushafPage,
  handleColor: Color,
  onHandleDragged: (isStart: Boolean, targetVerseKey: String, pageNumber: Int, verseCodeV2Line: String) -> Unit,
) {
  val density = LocalDensity.current
  val touchSizeDp = 48.dp
  val touchSizePx = with(density) { touchSizeDp.toPx() }
  val halfTouchPx = touchSizePx * 0.5f
  val pinOffsetPx = with(density) { 4.dp.toPx() }
  val latestAnchorX by rememberUpdatedState(anchorX)
  val latestAnchorY by rememberUpdatedState(anchorY)
  val latestOnDragged by rememberUpdatedState(onHandleDragged)

  val boxX = (anchorX - halfTouchPx).roundToInt()
  val boxY = (anchorY - pinOffsetPx).roundToInt()

  Box(
    contentAlignment = Alignment.TopCenter,
    modifier = Modifier
      .zIndex(5f)
      .absoluteOffset { IntOffset(boxX, boxY) }
      .size(touchSizeDp)
      .pointerInput(rendered, isStart, containerW, containerH) {
        var curX = 0f
        var curY = 0f
        detectDragGestures(
          onDragStart = {
            curX = latestAnchorX
            curY = latestAnchorY
          },
          onDrag = { change, dragAmount ->
            change.consume()
            curX = (curX + dragAmount.x).coerceIn(0f, containerW)
            curY = (curY + dragAmount.y).coerceIn(0f, containerH)
            val sx = rendered.widthPx.toFloat() / containerW
            val sy = rendered.heightPx.toFloat() / containerH
            val hitIdx = findNearestWordIndexForDrag(rendered.wordHitBoxes, curX * sx, curY * sy)
            if (hitIdx >= 0) {
              val boxes = rendered.wordHitBoxes
              latestOnDragged(
                isStart,
                boxes.verseKeys[hitIdx],
                rendered.pageNumber,
                boxes.codeV2Lines[hitIdx],
              )
            }
          },
        )
      }
      .testTag(if (isStart) "selection_handle_start" else "selection_handle_end"),
  ) {
    Box(
      modifier = Modifier
        .padding(top = 4.dp)
        .size(20.dp)
        .clip(CircleShape)
        .background(handleColor)
        .border(2.dp, Color.White, CircleShape),
    )
  }
}

private object PdfBannerFontCache {
  @Volatile var amiriBold: Typeface? = null
  @Volatile var ibmMedium: Typeface? = null
  @Volatile var surahNameV4: Typeface? = null
  @Volatile var surahNameV4Loaded: Boolean = false
  @Volatile var quranCommon: Typeface? = null
  @Volatile var quranCommonLoaded: Boolean = false

  fun getAmiriBold(context: android.content.Context): Typeface {
    amiriBold?.let { return it }
    return synchronized(this) {
      amiriBold ?: run {
        val base = runCatching { ResourcesCompat.getFont(context, R.font.amiri) }.getOrNull() ?: Typeface.SERIF
        Typeface.create(base, Typeface.BOLD).also { amiriBold = it }
      }
    }
  }

  fun getIbmMedium(context: android.content.Context): Typeface {
    ibmMedium?.let { return it }
    return synchronized(this) {
      ibmMedium ?: run {
        val base = runCatching { ResourcesCompat.getFont(context, R.font.ibm_plex_sans_arabic) }.getOrNull()
          ?: Typeface.SANS_SERIF
        Typeface.create(base, Typeface.BOLD).also { ibmMedium = it }
      }
    }
  }

  fun getSurahNameV4(context: android.content.Context): Typeface? {
    if (surahNameV4Loaded) return surahNameV4
    return synchronized(this) {
      if (!surahNameV4Loaded) {
        surahNameV4 = runCatching { ResourcesCompat.getFont(context, R.font.surah_name_v4) }.getOrNull()
        surahNameV4Loaded = true
      }
      surahNameV4
    }
  }

  fun getQuranCommon(context: android.content.Context): Typeface? {
    if (quranCommonLoaded) return quranCommon
    return synchronized(this) {
      if (!quranCommonLoaded) {
        quranCommon = runCatching { ResourcesCompat.getFont(context, R.font.quran_common) }.getOrNull()
        quranCommonLoaded = true
      }
      quranCommon
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
  val amiriBold = remember(context) { PdfBannerFontCache.getAmiriBold(context) }
  val ibmMedium = remember(context) { PdfBannerFontCache.getIbmMedium(context) }
  val surahNameV4 = remember(context) { PdfBannerFontCache.getSurahNameV4(context) }
  val quranCommon = remember(context) { PdfBannerFontCache.getQuranCommon(context) }

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
