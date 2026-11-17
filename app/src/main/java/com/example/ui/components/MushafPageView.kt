package com.example.ui.components

import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.prefs.BackgroundTheme
import com.example.data.quran.MushafEdition
import com.example.data.quran.MushafLine
import com.example.data.quran.MushafPageData
import com.example.data.quran.SurahMeta
import com.example.ui.theme.AmiriFontFamily
import com.example.ui.theme.QabasatGold
import com.example.ui.theme.QabasatNightGold
import com.example.viewmodel.PageLoadState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val lineFontFitCache = LruCache<String, Float>(512)
private val basmalahFontFitCache = LruCache<String, Float>(64)

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

@Composable
fun MushafPageFrame(
  pageNumber: Int,
  pageState: PageLoadState?,
  mushafEdition: MushafEdition,
  backgroundTheme: BackgroundTheme,
  surahsById: Map<Int, SurahMeta>,
  isBookmarked: Boolean,
  isDarkTheme: Boolean,
  activePlayingVerseKeyProvider: () -> String?,
  selectedVerseKeyProvider: () -> String?,
  onToggleBookmark: () -> Unit,
  onRetry: () -> Unit,
  onPageTapped: () -> Unit,
  onVerseLongPressed: (verseKey: String, pageNumber: Int, verseCodeV2Line: String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val effectiveDark = isDarkTheme || backgroundTheme == BackgroundTheme.BLACK
  val goldColor = if (effectiveDark) QabasatNightGold else QabasatGold

  // Pure White background -> Black ink; Pure Black background -> White ink (including Tajweed V4)
  val paperColor = if (effectiveDark) Color.Black else Color.White
  val pageInkColor = if (effectiveDark) Color.White else Color.Black
  val headerTint = if (effectiveDark) QabasatNightGold else MaterialTheme.colorScheme.primary
  val mutedInk = if (effectiveDark) Color.White.copy(alpha = 0.78f) else Color(0xFF424A47)
  val frameAccent = if (effectiveDark) QabasatNightGold.copy(alpha = 0.65f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
  val outerBorderColor = MaterialTheme.colorScheme.outline

  Surface(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 3.dp, vertical = 2.dp),
    shape = RoundedCornerShape(10.dp),
    color = paperColor,
    tonalElevation = if (effectiveDark) 0.dp else 2.dp,
    shadowElevation = if (effectiveDark) 0.dp else 2.dp,
  ) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .pointerInput(onPageTapped) {
            detectTapGestures(onTap = { onPageTapped() })
          }
          .drawWithCache {
            val outerStroke = Stroke(width = 1.4.dp.toPx())
            val innerStroke = Stroke(width = 0.75.dp.toPx())
            val outerMargin = 3.5.dp.toPx()
            val innerMargin = 6.5.dp.toPx()
            val outerTopLeft = Offset(outerMargin, outerMargin)
            val outerSize = Size(size.width - outerMargin * 2f, size.height - outerMargin * 2f)
            val outerCorner = CornerRadius(7.dp.toPx(), 7.dp.toPx())
            val innerTopLeft = Offset(innerMargin, innerMargin)
            val innerSize = Size(size.width - innerMargin * 2f, size.height - innerMargin * 2f)
            val innerCorner = CornerRadius(5.dp.toPx(), 5.dp.toPx())
            val outerGoldColor = goldColor.copy(alpha = 0.85f)
            val cornerRingColor = outerBorderColor.copy(alpha = 0.45f)
            val dotRadius = 2.2.dp.toPx()
            val ringRadius = 12.dp.toPx() * 0.24f
            val c1 = Offset(innerMargin, innerMargin)
            val c2 = Offset(size.width - innerMargin, innerMargin)
            val c3 = Offset(innerMargin, size.height - innerMargin)
            val c4 = Offset(size.width - innerMargin, size.height - innerMargin)

            onDrawBehind {
              drawRoundRect(
                color = outerGoldColor,
                topLeft = outerTopLeft,
                size = outerSize,
                cornerRadius = outerCorner,
                style = outerStroke,
              )
              drawRoundRect(
                color = frameAccent,
                topLeft = innerTopLeft,
                size = innerSize,
                cornerRadius = innerCorner,
                style = innerStroke,
              )
              drawCircle(color = goldColor, radius = dotRadius, center = c1)
              drawCircle(color = cornerRingColor, radius = ringRadius, center = c1, style = innerStroke)
              drawCircle(color = goldColor, radius = dotRadius, center = c2)
              drawCircle(color = cornerRingColor, radius = ringRadius, center = c2, style = innerStroke)
              drawCircle(color = goldColor, radius = dotRadius, center = c3)
              drawCircle(color = cornerRingColor, radius = ringRadius, center = c3, style = innerStroke)
              drawCircle(color = goldColor, radius = dotRadius, center = c4)
              drawCircle(color = cornerRingColor, radius = ringRadius, center = c4, style = innerStroke)
            }
          }
          .padding(horizontal = 9.dp, vertical = 7.dp),
      ) {
        when (pageState) {
          null, PageLoadState.Loading -> {
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.Center,
            ) {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(
                  color = headerTint,
                  strokeWidth = 2.5.dp,
                  modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                  text = "جاري تحميل الصفحة ${toArabicIndicDigits(pageNumber)}...",
                  style = MaterialTheme.typography.bodyMedium,
                  color = mutedInk,
                )
              }
            }
          }

          is PageLoadState.Error -> {
            Box(
              modifier = Modifier.fillMaxSize(),
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
            MushafPageContent(
              pageData = pageState.pageData,
              mushafEdition = mushafEdition,
              pageTypeface = pageState.pageTypeface,
              basmalahTypeface = pageState.basmalahTypeface,
              usesTagFallbackTajweed = pageState.usesTagFallbackTajweed,
              surahsById = surahsById,
              isBookmarked = isBookmarked,
              isDarkTheme = effectiveDark,
              goldColor = goldColor,
              pageInkColor = pageInkColor,
              headerTint = headerTint,
              mutedInk = mutedInk,
              activePlayingVerseKeyProvider = activePlayingVerseKeyProvider,
              selectedVerseKeyProvider = selectedVerseKeyProvider,
              onToggleBookmark = onToggleBookmark,
              onPageTapped = onPageTapped,
              onVerseLongPressed = onVerseLongPressed,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun MushafPageContent(
  pageData: MushafPageData,
  mushafEdition: MushafEdition,
  pageTypeface: Typeface?,
  basmalahTypeface: Typeface?,
  usesTagFallbackTajweed: Boolean,
  surahsById: Map<Int, SurahMeta>,
  isBookmarked: Boolean,
  isDarkTheme: Boolean,
  goldColor: Color,
  pageInkColor: Color,
  headerTint: Color,
  mutedInk: Color,
  activePlayingVerseKeyProvider: () -> String?,
  selectedVerseKeyProvider: () -> String?,
  onToggleBookmark: () -> Unit,
  onPageTapped: () -> Unit,
  onVerseLongPressed: (verseKey: String, pageNumber: Int, verseCodeV2Line: String) -> Unit,
) {
  val surahHeaderNames = remember(pageData.surahIds, surahsById) {
    pageData.surahIds.mapNotNull { surahsById[it]?.nameArabic }.joinToString(" • ")
  }

  // Pages 1 & 2 have 7 Quran lines (+ surah header) cleanly filling the page height;
  // Normal pages (3..604) always render all 15 equal-height lines edge to edge.
  val displayedLines = remember(pageData) {
    if (pageData.pageNumber in 1..2) {
      pageData.lines.dropLastWhile { it is MushafLine.EmptyLine }
    } else {
      pageData.lines
    }
  }

  // Hoist FontFamily + TextMeasurer + HapticFeedback ONCE per page instead of 15x per page.
  val pageQcfFontFamily = remember(pageTypeface) { pageTypeface?.let { FontFamily(it) } }
  val basmalahQcfFontFamily = remember(basmalahTypeface) { basmalahTypeface?.let { FontFamily(it) } }
  val textMeasurer = rememberTextMeasurer()
  val haptic = LocalHapticFeedback.current
  val density = LocalDensity.current

  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = if (surahHeaderNames.isNotBlank()) "سورة $surahHeaderNames" else "",
        style = MaterialTheme.typography.titleSmall.copy(
          fontFamily = AmiriFontFamily,
          fontWeight = FontWeight.Bold,
        ),
        color = headerTint,
        maxLines = 1,
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier
          .background(
            color = goldColor.copy(alpha = if (isDarkTheme) 0.14f else 0.11f),
            shape = RoundedCornerShape(10.dp),
          )
          .padding(horizontal = 8.dp, vertical = 1.dp),
      ) {
        Text(
          text = "الجزء ${toArabicIndicDigits(pageData.juzNumber)}",
          style = MaterialTheme.typography.labelMedium,
          color = mutedInk,
        )
        Text(
          text = "•",
          style = MaterialTheme.typography.labelSmall,
          color = goldColor,
        )
        Text(
          text = "الحزب ${toArabicIndicDigits(pageData.hizbNumber)}",
          style = MaterialTheme.typography.labelMedium,
          color = mutedInk,
        )
      }
    }

    // Single BoxWithConstraints measures the page lines container ONCE instead of 15 subcompositions per page.
    BoxWithConstraints(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .padding(vertical = 1.dp),
    ) {
      val lineCount = displayedLines.size.coerceAtLeast(1)
      val lineMaxWidthPx = constraints.maxWidth
      val lineMaxHeightPx = if (constraints.hasBoundedHeight) {
        (constraints.maxHeight / lineCount).coerceAtLeast(1)
      } else {
        Constraints.Infinity
      }

      Column(modifier = Modifier.fillMaxSize()) {
        for (line in displayedLines) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
            contentAlignment = Alignment.Center,
          ) {
            when (line) {
              is MushafLine.SurahHeaderLine -> {
                SurahHeaderBand(
                  surah = line.surah,
                  goldColor = goldColor,
                  textColor = headerTint,
                )
              }

              is MushafLine.BasmalahLine -> {
                BasmalahRow(
                  line = line,
                  qcfFont = basmalahQcfFontFamily,
                  inkColor = pageInkColor,
                  textMeasurer = textMeasurer,
                  maxWidthPx = lineMaxWidthPx,
                  maxHeightPx = lineMaxHeightPx,
                )
              }

              is MushafLine.AyahWordsLine -> {
                AyahLineRow(
                  pageNumber = pageData.pageNumber,
                  mushafEdition = mushafEdition,
                  line = line,
                  qcfFontFamily = pageQcfFontFamily,
                  usesTagFallbackTajweed = usesTagFallbackTajweed,
                  goldColor = goldColor,
                  baseTextColor = pageInkColor,
                  isDarkTheme = isDarkTheme,
                  textMeasurer = textMeasurer,
                  haptic = haptic,
                  density = density,
                  maxWidthPx = lineMaxWidthPx,
                  maxHeightPx = lineMaxHeightPx,
                  activePlayingVerseKeyProvider = activePlayingVerseKeyProvider,
                  selectedVerseKeyProvider = selectedVerseKeyProvider,
                  onPageTapped = onPageTapped,
                  onVerseLongPressed = onVerseLongPressed,
                )
              }

              is MushafLine.EmptyLine -> {
                Spacer(modifier = Modifier.fillMaxSize())
              }
            }
          }
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
        onClick = onToggleBookmark,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .size(30.dp)
          .testTag("bookmark_page_${pageData.pageNumber}"),
      ) {
        Icon(
          imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
          contentDescription = "علامة مرجعية للصفحة",
          tint = if (isBookmarked) goldColor else mutedInk,
          modifier = Modifier.size(18.dp),
        )
      }

      Box(
        modifier = Modifier
          .background(
            color = goldColor.copy(alpha = if (isDarkTheme) 0.18f else 0.14f),
            shape = RoundedCornerShape(16.dp),
          )
          .border(
            width = 0.8.dp,
            color = goldColor.copy(alpha = 0.55f),
            shape = RoundedCornerShape(16.dp),
          )
          .padding(horizontal = 14.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = toArabicIndicDigits(pageData.pageNumber),
          style = MaterialTheme.typography.titleSmall.copy(
            fontFamily = AmiriFontFamily,
            fontWeight = FontWeight.Bold,
          ),
          color = headerTint,
        )
      }

      Text(
        text = "اضغط مطولاً على الآية للتفسير",
        style = MaterialTheme.typography.labelSmall,
        color = mutedInk.copy(alpha = 0.85f),
      )
    }
  }
}

@Composable
private fun SurahHeaderBand(
  surah: SurahMeta,
  goldColor: Color,
  textColor: Color,
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 2.dp, vertical = 1.dp)
      .drawWithCache {
        val h = size.height
        val w = size.width
        val inset = 9.dp.toPx()
        val innerPad = 2.5.dp.toPx()
        val outerFill = goldColor.copy(alpha = 0.16f)
        val innerStrokeColor = goldColor.copy(alpha = 0.45f)
        val outerStroke = Stroke(width = 1.25.dp.toPx())
        val innerStroke = Stroke(width = 0.6.dp.toPx())
        val path = Path().apply {
          moveTo(inset, 0f)
          lineTo(w - inset, 0f)
          lineTo(w, h / 2f)
          lineTo(w - inset, h)
          lineTo(inset, h)
          lineTo(0f, h / 2f)
          close()
        }
        val innerPath = Path().apply {
          moveTo(inset + innerPad, innerPad)
          lineTo(w - inset - innerPad, innerPad)
          lineTo(w - innerPad, h / 2f)
          lineTo(w - inset - innerPad, h - innerPad)
          lineTo(inset + innerPad, h - innerPad)
          lineTo(innerPad, h / 2f)
          close()
        }
        onDrawBehind {
          drawPath(path = path, color = outerFill)
          drawPath(path = path, color = goldColor, style = outerStroke)
          drawPath(path = innerPath, color = innerStrokeColor, style = innerStroke)
        }
      }
      .padding(horizontal = 16.dp, vertical = 2.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "ترتيبها ${toArabicIndicDigits(surah.revelationOrder)}",
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
      )
      Text(
        text = "سورة ${surah.nameArabic}",
        style = MaterialTheme.typography.titleMedium.copy(
          fontFamily = AmiriFontFamily,
          fontWeight = FontWeight.Bold,
          fontSize = 17.sp,
        ),
        color = textColor,
        maxLines = 1,
      )
      Text(
        text = "${toArabicIndicDigits(surah.versesCount)} آية",
        style = MaterialTheme.typography.labelSmall,
        color = textColor,
      )
    }
  }
}

@Suppress("DEPRECATION")
@Composable
private fun BasmalahRow(
  line: MushafLine.BasmalahLine,
  qcfFont: FontFamily?,
  inkColor: Color,
  textMeasurer: TextMeasurer,
  maxWidthPx: Int,
  maxHeightPx: Int,
) {
  if (qcfFont == null || line.codeV2Glyphs.isBlank()) {
    Spacer(Modifier.fillMaxSize())
    return
  }

  val density = LocalDensity.current
  val maxSp = remember(maxHeightPx, density) {
    if (maxHeightPx > 0 && maxHeightPx != Constraints.Infinity) {
      with(density) { (maxHeightPx.toFloat() * 0.68f).toSp().value }.coerceIn(13f, 38f)
    } else {
      22f
    }
  }

  val fittedSp = remember(line.codeV2Glyphs, qcfFont, maxWidthPx, maxHeightPx, maxSp, density.density, density.fontScale) {
    if (maxWidthPx <= 0) {
      maxSp
    } else {
      val cacheKey = "${line.codeV2Glyphs}_${maxWidthPx}_${maxHeightPx}_${density.density}_${density.fontScale}"
      basmalahFontFitCache.get(cacheKey) ?: run {
        var lo = 10f
        var hi = maxSp
        val glyphStr = AnnotatedString(line.codeV2Glyphs)
        val widthConstraint = Constraints(maxWidth = maxWidthPx)

        fun fits(spSize: Float): Boolean {
          val measured = textMeasurer.measure(
            text = glyphStr,
            style = TextStyle(
              fontFamily = qcfFont,
              fontSize = spSize.sp,
              lineHeight = TextUnit.Unspecified,
              platformStyle = PlatformTextStyle(includeFontPadding = true),
              textDirection = TextDirection.Rtl,
            ),
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
            constraints = widthConstraint,
          )
          val fitsHeight = maxHeightPx <= 0 || maxHeightPx == Constraints.Infinity ||
            measured.size.height <= (maxHeightPx * 1.05f).toInt()
          return !measured.hasVisualOverflow && measured.size.width <= maxWidthPx && fitsHeight
        }

        val resolved = if (fits(hi)) {
          hi
        } else {
          repeat(7) {
            val mid = (lo + hi) * 0.5f
            if (fits(mid)) lo = mid else hi = mid
          }
          lo.coerceAtLeast(10f)
        }
        resolved.also { basmalahFontFitCache.put(cacheKey, it) }
      }
    }
  }

  val basmalahStyle = remember(qcfFont, fittedSp) {
    TextStyle(
      fontFamily = qcfFont,
      fontSize = fittedSp.sp,
      lineHeight = TextUnit.Unspecified,
      platformStyle = PlatformTextStyle(includeFontPadding = true),
      textAlign = TextAlign.Center,
      textDirection = TextDirection.Rtl,
    )
  }

  Box(
    modifier = Modifier.fillMaxWidth(),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = line.codeV2Glyphs,
      style = basmalahStyle,
      color = inkColor,
      maxLines = 1,
      softWrap = false,
      overflow = TextOverflow.Visible,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

private fun verseHighlightColors(
  isRecitedActive: Boolean,
  isDarkTheme: Boolean,
): Pair<Color, Color> {
  return if (isRecitedActive) {
    if (isDarkTheme) {
      Color(0xFFE0B45C).copy(alpha = 0.36f) to Color(0xFFFFD54F).copy(alpha = 0.72f)
    } else {
      Color(0xFFFFD54F).copy(alpha = 0.52f) to Color(0xFFC9971C).copy(alpha = 0.72f)
    }
  } else {
    if (isDarkTheme) {
      Color(0xFF4DB6AC).copy(alpha = 0.22f) to Color(0xFF4DB6AC).copy(alpha = 0.48f)
    } else {
      Color(0xFF12403C).copy(alpha = 0.12f) to Color(0xFF12403C).copy(alpha = 0.32f)
    }
  }
}

@Suppress("DEPRECATION")
@Composable
private fun AyahLineRow(
  pageNumber: Int,
  mushafEdition: MushafEdition,
  line: MushafLine.AyahWordsLine,
  qcfFontFamily: FontFamily?,
  usesTagFallbackTajweed: Boolean,
  goldColor: Color,
  baseTextColor: Color,
  isDarkTheme: Boolean,
  textMeasurer: TextMeasurer,
  haptic: HapticFeedback,
  density: androidx.compose.ui.unit.Density,
  maxWidthPx: Int,
  maxHeightPx: Int,
  activePlayingVerseKeyProvider: () -> String?,
  selectedVerseKeyProvider: () -> String?,
  onPageTapped: () -> Unit,
  onVerseLongPressed: (verseKey: String, pageNumber: Int, verseCodeV2Line: String) -> Unit,
) {
  if (!usesTagFallbackTajweed && qcfFontFamily == null) {
    Spacer(Modifier.fillMaxSize())
    return
  }

  val words = line.words
  if (words.isEmpty()) {
    Spacer(Modifier.fillMaxSize())
    return
  }

  val renderVectorTajweedMarkers = mushafEdition == MushafEdition.TAJWEED_V4

  val codeV2ByVerse = remember(line) {
    words.groupBy { it.verseKey }
      .mapValues { (_, ws) -> ws.joinToString(" ") { it.codeV2 } }
  }

  val wordTokens = remember(words, usesTagFallbackTajweed) {
    Array(words.size) { idx ->
      val w = words[idx]
      if (usesTagFallbackTajweed) w.textUthmani else w.codeV2
    }
  }

  val wordVerseKeys = remember(words) {
    Array(words.size) { idx -> words[idx].verseKey }
  }

  val lineVerseKeysSet = remember(words) {
    HashSet<String>(words.size).apply {
      for (idx in words.indices) add(words[idx].verseKey)
    }
  }

  val maxFontSp = remember(maxHeightPx, density) {
    if (maxHeightPx > 0 && maxHeightPx != Constraints.Infinity) {
      with(density) { (maxHeightPx.toFloat() * 0.72f).toSp().value }.coerceIn(12f, 40f)
    } else {
      24f
    }
  }

  val fittedFontSp = remember(
    pageNumber,
    mushafEdition,
    usesTagFallbackTajweed,
    line.lineNumber,
    qcfFontFamily,
    maxWidthPx,
    maxHeightPx,
    maxFontSp,
    density.density,
    density.fontScale,
  ) {
    if (maxWidthPx <= 0) {
      maxFontSp
    } else {
      val cacheKey = "${pageNumber}_${mushafEdition.name}_${usesTagFallbackTajweed}_${qcfFontFamily != null}_${line.lineNumber}_${maxWidthPx}_${maxHeightPx}_${density.density}_${density.fontScale}"
      lineFontFitCache.get(cacheKey) ?: run {
        val minSp = 8.5f
        var lo = minSp
        var hi = maxFontSp
        val annotatedTokens = Array(words.size) { idx -> AnnotatedString(wordTokens[idx]) }
        val unbounded = Constraints()

        fun fitsAt(candidateSp: Float): Boolean {
          val fontSizePx = with(density) { candidateSp.sp.toPx() }
          val minGapPx = (fontSizePx * 0.14f).coerceAtLeast(2f)
          val style = TextStyle(
            fontFamily = qcfFontFamily,
            fontSize = candidateSp.sp,
            lineHeight = TextUnit.Unspecified,
            platformStyle = PlatformTextStyle(includeFontPadding = true),
            textDirection = TextDirection.Rtl,
          )
          var sumW = 0f
          var maxH = 0f
          for (idx in words.indices) {
            val w = words[idx]
            if (renderVectorTajweedMarkers && w.isEndMarker) {
              sumW += fontSizePx * 1.32f
              val mh = fontSizePx * 1.18f
              if (mh > maxH) maxH = mh
            } else {
              val measured = textMeasurer.measure(
                text = annotatedTokens[idx],
                style = style,
                overflow = TextOverflow.Visible,
                softWrap = false,
                maxLines = 1,
                constraints = unbounded,
              )
              sumW += measured.size.width.toFloat()
              val mh = measured.size.height.toFloat()
              if (mh > maxH) maxH = mh
            }
          }
          val totalMinWidth = sumW + minGapPx * (words.size - 1).coerceAtLeast(0)
          val fitsWidth = totalMinWidth <= maxWidthPx.toFloat()
          val fitsHeight = maxHeightPx <= 0 || maxHeightPx == Constraints.Infinity ||
            maxH <= maxHeightPx.toFloat() * 1.05f
          return fitsWidth && fitsHeight
        }

        val resolved = if (fitsAt(hi)) {
          hi
        } else {
          repeat(8) {
            val mid = (lo + hi) * 0.5f
            if (fitsAt(mid)) {
              lo = mid
            } else {
              hi = mid
            }
          }
          lo.coerceAtLeast(minSp)
        }
        lineFontFitCache.put(cacheKey, resolved)
        resolved
      }
    }
  }

  // Allocate ONE shared TextStyle per line instead of 1 per word.
  val wordTextStyle = remember(qcfFontFamily, fittedFontSp) {
    TextStyle(
      fontFamily = qcfFontFamily,
      fontSize = fittedFontSp.sp,
      lineHeight = TextUnit.Unspecified,
      platformStyle = PlatformTextStyle(includeFontPadding = true),
      textDirection = TextDirection.Rtl,
    )
  }

  val markerWidthDp = remember(fittedFontSp, density) {
    with(density) { (fittedFontSp * 1.32f).sp.toDp() }
  }
  val markerHeightDp = remember(fittedFontSp, density) {
    with(density) { (fittedFontSp * 1.18f).sp.toDp() }
  }

  // Pre-allocated flat arrays eliminate WordPlacedBox object churn on relayout.
  val wordCount = words.size
  val wordLefts = remember(pageNumber, line.lineNumber, wordCount) { FloatArray(wordCount) }
  val wordTops = remember(pageNumber, line.lineNumber, wordCount) { FloatArray(wordCount) }
  val wordRights = remember(pageNumber, line.lineNumber, wordCount) { FloatArray(wordCount) { -1f } }
  val wordBottoms = remember(pageNumber, line.lineNumber, wordCount) { FloatArray(wordCount) }

  val defaultGapPx = remember(fittedFontSp, density) {
    with(density) { (fittedFontSp * 0.24f).sp.roundToPx() }.coerceAtLeast(4)
  }

  Layout(
    content = {
      words.forEachIndexed { idx, word ->
        if (renderVectorTajweedMarkers && word.isEndMarker) {
          Box(
            modifier = Modifier.size(width = markerWidthDp, height = markerHeightDp),
            contentAlignment = Alignment.Center,
          ) {
            TajweedAyahNumberRosette(
              arabicDigits = toArabicIndicDigits(word.ayahNumber),
              lineFontSp = fittedFontSp,
              goldColor = goldColor,
              inkColor = baseTextColor,
              isDarkTheme = isDarkTheme,
              isActiveProvider = { activePlayingVerseKeyProvider() == word.verseKey },
            )
          }
        } else {
          Text(
            text = wordTokens[idx],
            style = wordTextStyle,
            color = baseTextColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Visible,
          )
        }
      }
    },
    modifier = Modifier
      .fillMaxSize()
      .drawBehind {
        if (wordCount == 0 || wordRights[0] < 0f) return@drawBehind

        val activeKey = activePlayingVerseKeyProvider()?.takeIf { lineVerseKeysSet.contains(it) }
        val selectedKey = selectedVerseKeyProvider()?.takeIf { lineVerseKeysSet.contains(it) }
        if (activeKey == null && selectedKey == null) return@drawBehind

        val padX = 2.5.dp.toPx()
        val padY = 1.dp.toPx()
        val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())
        val stroke = Stroke(width = 1.dp.toPx())

        fun drawVerseHighlight(targetKey: String, isRecitedActive: Boolean) {
          val (fillColor, strokeColor) = verseHighlightColors(
            isRecitedActive = isRecitedActive,
            isDarkTheme = isDarkTheme,
          )
          var minLeft = Float.POSITIVE_INFINITY
          var minTop = Float.POSITIVE_INFINITY
          var maxRight = Float.NEGATIVE_INFINITY
          var maxBottom = Float.NEGATIVE_INFINITY
          var hasBox = false

          for (i in 0 until wordCount) {
            if (wordRights[i] >= 0f && wordVerseKeys[i] == targetKey) {
              val l = wordLefts[i]
              val t = wordTops[i]
              val r = wordRights[i]
              val b = wordBottoms[i]
              if (!hasBox) {
                minLeft = l
                minTop = t
                maxRight = r
                maxBottom = b
                hasBox = true
              } else {
                if (l < minLeft) minLeft = l
                if (t < minTop) minTop = t
                if (r > maxRight) maxRight = r
                if (b > maxBottom) maxBottom = b
              }
            } else if (hasBox) {
              val left = (minLeft - padX).coerceAtLeast(0f)
              val right = (maxRight + padX).coerceAtMost(size.width)
              val top = (minTop - padY).coerceAtLeast(0f)
              val bottom = (maxBottom + padY).coerceAtMost(size.height)
              val rectSize = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f))
              drawRoundRect(
                color = fillColor,
                topLeft = Offset(left, top),
                size = rectSize,
                cornerRadius = corner,
              )
              drawRoundRect(
                color = strokeColor,
                topLeft = Offset(left, top),
                size = rectSize,
                cornerRadius = corner,
                style = stroke,
              )
              hasBox = false
              minLeft = Float.POSITIVE_INFINITY
              minTop = Float.POSITIVE_INFINITY
              maxRight = Float.NEGATIVE_INFINITY
              maxBottom = Float.NEGATIVE_INFINITY
            }
          }
          if (hasBox) {
            val left = (minLeft - padX).coerceAtLeast(0f)
            val right = (maxRight + padX).coerceAtMost(size.width)
            val top = (minTop - padY).coerceAtLeast(0f)
            val bottom = (maxBottom + padY).coerceAtMost(size.height)
            val rectSize = Size((right - left).coerceAtLeast(1f), (bottom - top).coerceAtLeast(1f))
            drawRoundRect(
              color = fillColor,
              topLeft = Offset(left, top),
              size = rectSize,
              cornerRadius = corner,
            )
            drawRoundRect(
              color = strokeColor,
              topLeft = Offset(left, top),
              size = rectSize,
              cornerRadius = corner,
              style = stroke,
            )
          }
        }

        if (selectedKey != null && selectedKey != activeKey) {
          drawVerseHighlight(selectedKey, isRecitedActive = false)
        }
        if (activeKey != null) {
          drawVerseHighlight(activeKey, isRecitedActive = true)
        }
      }
      .pointerInput(pageNumber, line.lineNumber, codeV2ByVerse, onPageTapped, onVerseLongPressed) {
        detectTapGestures(
          onTap = { onPageTapped() },
          onLongPress = { tapOffset ->
            var hitIdx = -1
            var bestDist = Float.POSITIVE_INFINITY
            for (i in 0 until wordCount) {
              if (wordRights[i] < 0f) continue
              if (tapOffset.x in (wordLefts[i] - 8f)..(wordRights[i] + 8f) &&
                tapOffset.y in (wordTops[i] - 12f)..(wordBottoms[i] + 12f)
              ) {
                hitIdx = i
                break
              }
              val dist = abs(tapOffset.x - (wordLefts[i] + wordRights[i]) * 0.5f)
              if (dist < bestDist) {
                bestDist = dist
                hitIdx = i
              }
            }
            if (hitIdx >= 0) {
              val key = wordVerseKeys[hitIdx]
              haptic.performHapticFeedback(HapticFeedbackType.LongPress)
              onVerseLongPressed(
                key,
                pageNumber,
                codeV2ByVerse[key].orEmpty(),
              )
            }
          },
        )
      }
      .testTag("mushaf_page_${pageNumber}_line_${line.lineNumber}"),
  ) { measurables, incomingConstraints ->
    val childConstraints = Constraints()
    val placeables = Array(measurables.size) { idx -> measurables[idx].measure(childConstraints) }
    val n = placeables.size
    var sumW = 0
    var maxChildH = 0
    for (i in 0 until n) {
      val p = placeables[i]
      sumW += p.width
      if (p.height > maxChildH) maxChildH = p.height
    }

    val layoutW = if (incomingConstraints.hasBoundedWidth) {
      incomingConstraints.maxWidth
    } else {
      sumW
    }
    val layoutH = if (incomingConstraints.hasBoundedHeight) {
      incomingConstraints.maxHeight
    } else {
      maxChildH
    }

    // Justify edge-to-edge on full Mushaf lines (4+ words or >=45% line fill); center tiny stubs.
    val justifyEdgeToEdge = n >= 4 || (n >= 2 && sumW >= (layoutW * 0.45f).toInt())

    layout(layoutW, layoutH) {
      if (n == 0) return@layout

      if (justifyEdgeToEdge && n > 1 && layoutW >= sumW) {
        val totalGap = layoutW - sumW
        var rightCursor = layoutW
        var prevCumGap = 0
        for (i in 0 until n) {
          val p = placeables[i]
          val cumGap = if (i == 0) 0 else ((totalGap.toLong() * i) / (n - 1)).toInt()
          val gapStep = cumGap - prevCumGap
          prevCumGap = cumGap
          rightCursor -= gapStep
          val x = rightCursor - p.width
          val y = (layoutH - p.height) / 2
          rightCursor = x
          p.place(x = x, y = y)
          wordLefts[i] = x.toFloat()
          wordTops[i] = y.toFloat()
          wordRights[i] = (x + p.width).toFloat()
          wordBottoms[i] = (y + p.height).toFloat()
        }
      } else {
        val blockW = sumW + defaultGapPx * (n - 1).coerceAtLeast(0)
        var rightCursor = if (layoutW > blockW) {
          layoutW - (layoutW - blockW) / 2
        } else {
          layoutW
        }
        for (i in 0 until n) {
          val p = placeables[i]
          if (i > 0) rightCursor -= defaultGapPx
          val x = rightCursor - p.width
          val y = (layoutH - p.height) / 2
          rightCursor = x
          p.place(x = x, y = y)
          wordLefts[i] = x.toFloat()
          wordTops[i] = y.toFloat()
          wordRights[i] = (x + p.width).toFloat()
          wordBottoms[i] = (y + p.height).toFloat()
        }
      }
    }
  }
}

@Composable
private fun TajweedAyahNumberRosette(
  arabicDigits: String,
  lineFontSp: Float,
  goldColor: Color,
  inkColor: Color,
  isDarkTheme: Boolean,
  isActiveProvider: () -> Boolean,
) {
  val digitScale = when (arabicDigits.length) {
    1 -> 0.44f
    2 -> 0.39f
    else -> 0.33f
  }
  val numberFontSp = (lineFontSp * digitScale).coerceIn(7.5f, 18f)
  val numberStyle = remember(numberFontSp, inkColor) {
    TextStyle(
      fontFamily = AmiriFontFamily,
      fontWeight = FontWeight.Bold,
      fontSize = numberFontSp.sp,
      lineHeight = TextUnit.Unspecified,
      textAlign = TextAlign.Center,
      color = inkColor,
    )
  }

  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Spacer(
      modifier = Modifier
        .fillMaxSize()
        .drawWithCache {
          val cx = size.width / 2f
          val cy = size.height / 2f
          val minDim = minOf(size.width, size.height)
          val outerR = minDim * 0.46f
          val innerR = minDim * 0.37f
          val points = 16
          val starPath = Path()
          for (i in 0 until points) {
            val angle = (2.0 * PI * i) / points - PI / 2.0
            val r = if (i % 2 == 0) outerR else innerR
            val x = cx + (r * cos(angle)).toFloat()
            val y = cy + (r * sin(angle)).toFloat()
            if (i == 0) starPath.moveTo(x, y) else starPath.lineTo(x, y)
          }
          starPath.close()

          val activeFill = goldColor.copy(alpha = if (isDarkTheme) 0.44f else 0.36f)
          val inactiveFill = goldColor.copy(alpha = if (isDarkTheme) 0.22f else 0.16f)
          val activeStroke = Stroke(width = (minDim * 0.075f).coerceIn(0.9f, 2.2f))
          val inactiveStroke = Stroke(width = (minDim * 0.055f).coerceIn(0.9f, 2.2f))
          val innerColor = goldColor.copy(alpha = 0.55f)
          val innerRadius = minDim * 0.30f
          val innerStroke = Stroke(width = (minDim * 0.032f).coerceIn(0.6f, 1.1f))
          val center = Offset(cx, cy)

          onDrawBehind {
            val isActive = isActiveProvider()
            drawPath(path = starPath, color = if (isActive) activeFill else inactiveFill)
            drawPath(
              path = starPath,
              color = goldColor,
              style = if (isActive) activeStroke else inactiveStroke,
            )
            drawCircle(
              color = innerColor,
              radius = innerRadius,
              center = center,
              style = innerStroke,
            )
          }
        },
    )

    Text(
      text = arabicDigits,
      style = numberStyle,
      maxLines = 1,
      softWrap = false,
      overflow = TextOverflow.Visible,
    )
  }
}
