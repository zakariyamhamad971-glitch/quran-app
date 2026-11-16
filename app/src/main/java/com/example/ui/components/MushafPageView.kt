package com.example.ui.components

import android.graphics.Typeface
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import kotlin.math.cos
import kotlin.math.sin

private fun toArabicIndicDigits(number: Int): String {
  val digits = charArrayOf('٠', '١', '٢', '٣', '٤', '٥', '٦', '٧', '٨', '٩')
  return number.toString().map { ch ->
    if (ch in '0'..'9') digits[ch - '0'] else ch
  }.joinToString("")
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
  isRecitationPlaying: Boolean,
  activePlayingVerseKey: String?,
  selectedVerseKey: String?,
  onToggleBookmark: () -> Unit,
  onRetry: () -> Unit,
  onVerseTapped: (verseKey: String, pageNumber: Int, verseCodeV2Line: String) -> Unit,
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
      .padding(horizontal = 8.dp, vertical = 4.dp),
    shape = RoundedCornerShape(12.dp),
    color = paperColor,
    tonalElevation = if (effectiveDark) 0.dp else 2.dp,
    shadowElevation = if (effectiveDark) 0.dp else 2.dp,
  ) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .drawBehind {
          drawRoundRect(
            color = goldColor.copy(alpha = 0.85f),
            topLeft = Offset(5.dp.toPx(), 5.dp.toPx()),
            size = Size(size.width - 10.dp.toPx(), size.height - 10.dp.toPx()),
            cornerRadius = CornerRadius(9.dp.toPx(), 9.dp.toPx()),
            style = Stroke(width = 1.6.dp.toPx()),
          )
          drawRoundRect(
            color = frameAccent,
            topLeft = Offset(9.dp.toPx(), 9.dp.toPx()),
            size = Size(size.width - 18.dp.toPx(), size.height - 18.dp.toPx()),
            cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
            style = Stroke(width = 0.85.dp.toPx()),
          )
          val cornerPad = 9.dp.toPx()
          val accentLen = 14.dp.toPx()
          listOf(
            Offset(cornerPad, cornerPad),
            Offset(size.width - cornerPad, cornerPad),
            Offset(cornerPad, size.height - cornerPad),
            Offset(size.width - cornerPad, size.height - cornerPad),
          ).forEach { pt ->
            drawCircle(color = goldColor, radius = 2.6.dp.toPx(), center = pt)
            drawCircle(
              color = outerBorderColor.copy(alpha = 0.45f),
              radius = accentLen * 0.26f,
              center = pt,
              style = Stroke(width = 0.8.dp.toPx()),
            )
          }
        }
        .padding(horizontal = 14.dp, vertical = 10.dp),
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
            isRecitationPlaying = isRecitationPlaying,
            activePlayingVerseKey = activePlayingVerseKey,
            selectedVerseKey = selectedVerseKey,
            onToggleBookmark = onToggleBookmark,
            onVerseTapped = onVerseTapped,
          )
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
  isRecitationPlaying: Boolean,
  activePlayingVerseKey: String?,
  selectedVerseKey: String?,
  onToggleBookmark: () -> Unit,
  onVerseTapped: (verseKey: String, pageNumber: Int, verseCodeV2Line: String) -> Unit,
) {
  val surahHeaderNames = remember(pageData.surahIds, surahsById) {
    pageData.surahIds.mapNotNull { surahsById[it]?.nameArabic }.joinToString(" • ")
  }

  Column(
    modifier = Modifier.fillMaxSize(),
    verticalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 6.dp, vertical = 1.dp),
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
          .background(
            color = goldColor.copy(alpha = if (isDarkTheme) 0.14f else 0.11f),
            shape = RoundedCornerShape(10.dp),
          )
          .padding(horizontal = 9.dp, vertical = 1.dp),
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

    Column(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .padding(vertical = 1.dp),
      verticalArrangement = Arrangement.SpaceEvenly,
    ) {
      for (line in pageData.lines) {
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
                basmalahTypeface = basmalahTypeface,
                inkColor = pageInkColor,
              )
            }

            is MushafLine.AyahWordsLine -> {
              AyahLineRow(
                pageNumber = pageData.pageNumber,
                mushafEdition = mushafEdition,
                line = line,
                pageTypeface = pageTypeface,
                usesTagFallbackTajweed = usesTagFallbackTajweed,
                goldColor = goldColor,
                baseTextColor = pageInkColor,
                isDarkTheme = isDarkTheme,
                isRecitationPlaying = isRecitationPlaying,
                activePlayingVerseKey = activePlayingVerseKey,
                selectedVerseKey = selectedVerseKey,
                onVerseTapped = onVerseTapped,
              )
            }

            is MushafLine.EmptyLine -> {
              Spacer(modifier = Modifier.fillMaxSize())
            }
          }
        }
      }
    }

    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(top = 1.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(
        onClick = onToggleBookmark,
        modifier = Modifier
          .minimumInteractiveComponentSize()
          .size(34.dp)
          .testTag("bookmark_page_${pageData.pageNumber}"),
      ) {
        Icon(
          imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
          contentDescription = "علامة مرجعية للصفحة",
          tint = if (isBookmarked) goldColor else mutedInk,
          modifier = Modifier.size(19.dp),
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
          .padding(horizontal = 16.dp, vertical = 2.dp),
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
        text = "اضغط على الآية للتفسير",
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
      .padding(horizontal = 4.dp, vertical = 1.dp)
      .drawBehind {
        val h = size.height
        val w = size.width
        val inset = 9.dp.toPx()
        val path = Path().apply {
          moveTo(inset, 0f)
          lineTo(w - inset, 0f)
          lineTo(w, h / 2f)
          lineTo(w - inset, h)
          lineTo(inset, h)
          lineTo(0f, h / 2f)
          close()
        }
        drawPath(path = path, color = goldColor.copy(alpha = 0.16f))
        drawPath(path = path, color = goldColor, style = Stroke(width = 1.25.dp.toPx()))
        val innerPad = 2.5.dp.toPx()
        val innerPath = Path().apply {
          moveTo(inset + innerPad, innerPad)
          lineTo(w - inset - innerPad, innerPad)
          lineTo(w - innerPad, h / 2f)
          lineTo(w - inset - innerPad, h - innerPad)
          lineTo(inset + innerPad, h - innerPad)
          lineTo(innerPad, h / 2f)
          close()
        }
        drawPath(path = innerPath, color = goldColor.copy(alpha = 0.45f), style = Stroke(width = 0.6.dp.toPx()))
      }
      .padding(horizontal = 18.dp, vertical = 2.dp),
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
          fontSize = 17.5.sp,
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
  basmalahTypeface: Typeface?,
  inkColor: Color,
) {
  if (basmalahTypeface == null || line.codeV2Glyphs.isBlank()) {
    Spacer(Modifier.fillMaxSize())
    return
  }
  val qcfFont = remember(basmalahTypeface) { FontFamily(basmalahTypeface) }
  val textMeasurer = rememberTextMeasurer()

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 2.dp),
    contentAlignment = Alignment.Center,
  ) {
    val density = LocalDensity.current
    val maxWidthPx = constraints.maxWidth
    val maxHeightPx = constraints.maxHeight
    val maxSp = remember(maxHeightPx, density) {
      if (maxHeightPx > 0 && maxHeightPx != Constraints.Infinity) {
        with(density) { (maxHeightPx.toFloat() * 0.56f).toSp().value }.coerceIn(13f, 23f)
      } else {
        19f
      }
    }

    val fittedSp = remember(line.codeV2Glyphs, qcfFont, maxWidthPx, maxHeightPx, maxSp) {
      if (maxWidthPx <= 0) {
        maxSp
      } else {
        var candidate = maxSp
        while (candidate > 11f) {
          val measured = textMeasurer.measure(
            text = AnnotatedString(line.codeV2Glyphs),
            style = TextStyle(
              fontFamily = qcfFont,
              fontSize = candidate.sp,
              lineHeight = TextUnit.Unspecified,
              platformStyle = PlatformTextStyle(includeFontPadding = true),
              textDirection = TextDirection.Rtl,
            ),
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = maxWidthPx),
          )
          val fitsHeight = maxHeightPx <= 0 || maxHeightPx == Constraints.Infinity ||
            measured.size.height <= (maxHeightPx * 1.05f).toInt()
          if (!measured.hasVisualOverflow && measured.size.width <= (maxWidthPx - 4) && fitsHeight) {
            break
          }
          candidate -= 0.5f
        }
        candidate.coerceAtLeast(11f)
      }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
      Text(
        text = line.codeV2Glyphs,
        style = TextStyle(
          fontFamily = qcfFont,
          fontSize = fittedSp.sp,
          lineHeight = TextUnit.Unspecified,
          platformStyle = PlatformTextStyle(includeFontPadding = true),
          textAlign = TextAlign.Center,
          textDirection = TextDirection.Rtl,
        ),
        color = inkColor,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        modifier = Modifier.fillMaxWidth(),
      )
    }
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
  pageTypeface: Typeface?,
  usesTagFallbackTajweed: Boolean,
  goldColor: Color,
  baseTextColor: Color,
  isDarkTheme: Boolean,
  isRecitationPlaying: Boolean,
  activePlayingVerseKey: String?,
  selectedVerseKey: String?,
  onVerseTapped: (verseKey: String, pageNumber: Int, verseCodeV2Line: String) -> Unit,
) {
  if (!usesTagFallbackTajweed && pageTypeface == null) {
    Spacer(Modifier.fillMaxSize())
    return
  }

  val renderVectorTajweedMarkers = mushafEdition == MushafEdition.TAJWEED_V4

  val qcfFontFamily = remember(pageTypeface) {
    pageTypeface?.let { FontFamily(it) }
  }

  val markerPlaceholder = remember {
    Placeholder(
      width = 1.36.em,
      height = 1.20.em,
      placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
    )
  }

  val linePayload = remember(
    line,
    renderVectorTajweedMarkers,
    usesTagFallbackTajweed,
    baseTextColor,
  ) {
    val placeholderRanges = mutableListOf<AnnotatedString.Range<Placeholder>>()
    val markers = mutableMapOf<String, AyahMarkerMeta>()
    val verseRangesBuilder = linkedMapOf<String, MutableList<IntRange>>()

    var openVerseKey: String? = null
    var openStart = 0

    val text = buildAnnotatedString {
      line.words.forEachIndexed { idx, word ->
        val tokenText = if (usesTagFallbackTajweed) word.textUthmani else word.codeV2
        if (openVerseKey != word.verseKey) {
          if (openVerseKey != null && length > openStart) {
            verseRangesBuilder.getOrPut(openVerseKey!!) { mutableListOf() }.add(openStart until length)
          }
          openVerseKey = word.verseKey
          openStart = length
        }

        pushStringAnnotation(tag = "verse", annotation = word.verseKey)
        if (renderVectorTajweedMarkers && word.isEndMarker) {
          val markerId = "ayah_end_${word.verseKey}_${word.id}"
          val arabicDigits = toArabicIndicDigits(word.ayahNumber)
          val startIdx = length
          appendInlineContent(markerId, "\uFD3E$arabicDigits\uFD3F")
          val endIdx = length
          placeholderRanges.add(AnnotatedString.Range(markerPlaceholder, startIdx, endIdx))
          markers[markerId] = AyahMarkerMeta(
            arabicDigits = arabicDigits,
            verseKey = word.verseKey,
          )
        } else {
          withStyle(SpanStyle(color = baseTextColor)) {
            append(tokenText)
          }
        }
        pop()

        if (idx < line.words.lastIndex) {
          val nextVerseSame = line.words[idx + 1].verseKey == word.verseKey
          if (!nextVerseSame && openVerseKey != null && length > openStart) {
            verseRangesBuilder.getOrPut(openVerseKey!!) { mutableListOf() }.add(openStart until length)
            openVerseKey = null
          }
          append(" ")
        }
      }
      if (openVerseKey != null && length > openStart) {
        verseRangesBuilder.getOrPut(openVerseKey!!) { mutableListOf() }.add(openStart until length)
      }
    }
    PreparedLine(
      annotated = text,
      placeholderRanges = placeholderRanges,
      markers = markers,
      verseCharRanges = verseRangesBuilder,
    )
  }

  val codeV2ByVerse = remember(line) {
    line.words.groupBy { it.verseKey }
      .mapValues { (_, ws) -> ws.joinToString(" ") { it.codeV2 } }
  }

  val textMeasurer = rememberTextMeasurer()

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 2.dp),
    contentAlignment = Alignment.Center,
  ) {
    val density = LocalDensity.current
    val maxWidthPx = constraints.maxWidth
    val maxHeightPx = constraints.maxHeight

    val maxFontSp = remember(maxHeightPx, density) {
      if (maxHeightPx > 0 && maxHeightPx != Constraints.Infinity) {
        with(density) { (maxHeightPx.toFloat() * 0.56f).toSp().value }.coerceIn(13f, 25f)
      } else {
        21f
      }
    }

    val fittedFontSp = remember(
      pageNumber,
      line.lineNumber,
      linePayload.annotated,
      qcfFontFamily,
      maxWidthPx,
      maxHeightPx,
      maxFontSp,
    ) {
      if (maxWidthPx <= 0) {
        maxFontSp
      } else {
        var candidate = maxFontSp
        while (candidate > 9.5f) {
          val measured = textMeasurer.measure(
            text = linePayload.annotated,
            style = TextStyle(
              fontFamily = qcfFontFamily,
              fontSize = candidate.sp,
              lineHeight = TextUnit.Unspecified,
              platformStyle = PlatformTextStyle(includeFontPadding = true),
              textDirection = TextDirection.Rtl,
            ),
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
            placeholders = linePayload.placeholderRanges,
            constraints = Constraints(maxWidth = maxWidthPx),
          )
          val fitsHeight = maxHeightPx <= 0 || maxHeightPx == Constraints.Infinity ||
            measured.size.height <= (maxHeightPx * 1.06f).toInt()
          if (!measured.hasVisualOverflow && measured.size.width <= (maxWidthPx - 6) && fitsHeight) {
            break
          }
          candidate -= 0.5f
        }
        candidate.coerceAtLeast(9.5f)
      }
    }

    val inlineContent = remember(
      linePayload.markers,
      markerPlaceholder,
      fittedFontSp,
      goldColor,
      baseTextColor,
      isDarkTheme,
      activePlayingVerseKey,
    ) {
      linePayload.markers.mapValues { (_, meta) ->
        InlineTextContent(markerPlaceholder) {
          TajweedAyahNumberRosette(
            arabicDigits = meta.arabicDigits,
            lineFontSp = fittedFontSp,
            goldColor = goldColor,
            inkColor = baseTextColor,
            isDarkTheme = isDarkTheme,
            isActive = meta.verseKey == activePlayingVerseKey,
          )
        }
      }
    }

    var layoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }

    val activeRangesOnLine = activePlayingVerseKey?.let { linePayload.verseCharRanges[it] }
    val selectedRangesOnLine = if (selectedVerseKey != null && selectedVerseKey != activePlayingVerseKey) {
      linePayload.verseCharRanges[selectedVerseKey]
    } else {
      null
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
      Text(
        text = linePayload.annotated,
        inlineContent = inlineContent,
        style = TextStyle(
          fontFamily = qcfFontFamily,
          fontSize = fittedFontSp.sp,
          lineHeight = TextUnit.Unspecified,
          platformStyle = PlatformTextStyle(includeFontPadding = true),
          textAlign = TextAlign.Center,
          textDirection = TextDirection.Rtl,
        ),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        onTextLayout = { layoutResult = it },
        modifier = Modifier
          .fillMaxWidth()
          .drawBehind {
            val lr = layoutResult ?: return@drawBehind
            val plain = lr.layoutInput.text.text
            if (plain.isEmpty()) return@drawBehind

            fun drawRanges(ranges: List<IntRange>, fillColor: Color, strokeColor: Color) {
              val padX = 2.5.dp.toPx()
              val padY = 1.dp.toPx()
              val corner = CornerRadius(6.dp.toPx(), 6.dp.toPx())
              for (range in ranges) {
                var minLeft = Float.POSITIVE_INFINITY
                var minTop = Float.POSITIVE_INFINITY
                var maxRight = Float.NEGATIVE_INFINITY
                var maxBottom = Float.NEGATIVE_INFINITY
                var i = range.first.coerceAtLeast(0)
                val end = range.last.coerceAtMost(plain.length - 1)
                while (i <= end) {
                  val cp = plain.codePointAt(i)
                  val charLen = Character.charCount(cp)
                  val box = runCatching { lr.getBoundingBox(i) }.getOrNull()
                  if (box != null && (box.width > 0f || box.height > 0f)) {
                    if (box.left < minLeft) minLeft = box.left
                    if (box.top < minTop) minTop = box.top
                    if (box.right > maxRight) maxRight = box.right
                    if (box.bottom > maxBottom) maxBottom = box.bottom
                  }
                  i += charLen
                }
                if (minLeft < maxRight && minTop < maxBottom) {
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
                    style = Stroke(width = 1.dp.toPx()),
                  )
                }
              }
            }

            if (!selectedRangesOnLine.isNullOrEmpty()) {
              val (fill, stroke) = verseHighlightColors(isRecitedActive = false, isDarkTheme = isDarkTheme)
              drawRanges(selectedRangesOnLine, fill, stroke)
            }
            if (!activeRangesOnLine.isNullOrEmpty()) {
              val (fill, stroke) = verseHighlightColors(isRecitedActive = true, isDarkTheme = isDarkTheme)
              drawRanges(activeRangesOnLine, fill, stroke)
            }
          }
          .pointerInput(linePayload.annotated) {
            detectTapGestures { tapOffset ->
              val lr = layoutResult ?: return@detectTapGestures
              val offset = lr.getOffsetForPosition(tapOffset)
              val hit = linePayload.annotated
                .getStringAnnotations(tag = "verse", start = offset, end = offset)
                .firstOrNull()
                ?: linePayload.annotated
                  .getStringAnnotations(
                    tag = "verse",
                    start = (offset - 1).coerceAtLeast(0),
                    end = (offset + 1).coerceAtMost(linePayload.annotated.length),
                  )
                  .firstOrNull()
              if (hit != null) {
                onVerseTapped(
                  hit.item,
                  pageNumber,
                  codeV2ByVerse[hit.item].orEmpty(),
                )
              }
            }
          }
          .testTag("mushaf_page_${pageNumber}_line_${line.lineNumber}"),
      )
    }
  }
}

private data class AyahMarkerMeta(
  val arabicDigits: String,
  val verseKey: String,
)

private data class PreparedLine(
  val annotated: AnnotatedString,
  val placeholderRanges: List<AnnotatedString.Range<Placeholder>>,
  val markers: Map<String, AyahMarkerMeta>,
  val verseCharRanges: Map<String, List<IntRange>>,
)

@Composable
private fun TajweedAyahNumberRosette(
  arabicDigits: String,
  lineFontSp: Float,
  goldColor: Color,
  inkColor: Color,
  isDarkTheme: Boolean,
  isActive: Boolean,
) {
  val digitScale = when (arabicDigits.length) {
    1 -> 0.44f
    2 -> 0.39f
    else -> 0.33f
  }
  val numberFontSp = (lineFontSp * digitScale).coerceIn(7.5f, 12.5f)
  val fillTint = when {
    isActive -> goldColor.copy(alpha = if (isDarkTheme) 0.44f else 0.36f)
    isDarkTheme -> goldColor.copy(alpha = 0.22f)
    else -> goldColor.copy(alpha = 0.16f)
  }

  Box(
    modifier = Modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    Canvas(modifier = Modifier.fillMaxSize()) {
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

      drawPath(path = starPath, color = fillTint)
      drawPath(
        path = starPath,
        color = goldColor,
        style = Stroke(width = (minDim * if (isActive) 0.075f else 0.055f).coerceIn(0.9f, 2.2f)),
      )
      drawCircle(
        color = goldColor.copy(alpha = 0.55f),
        radius = minDim * 0.30f,
        center = Offset(cx, cy),
        style = Stroke(width = (minDim * 0.032f).coerceIn(0.6f, 1.1f)),
      )
    }

    Text(
      text = arabicDigits,
      style = TextStyle(
        fontFamily = AmiriFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = numberFontSp.sp,
        lineHeight = TextUnit.Unspecified,
        textAlign = TextAlign.Center,
        color = inkColor,
      ),
      maxLines = 1,
      softWrap = false,
      overflow = TextOverflow.Visible,
    )
  }
}
