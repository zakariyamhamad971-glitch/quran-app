package com.example

import android.content.Context
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.example.data.integrity.VerifiedDiskCache
import com.example.data.prefs.BackgroundTheme
import com.example.data.prefs.ReaderPreferences
import com.example.data.quran.GlobalMushafFontSizer
import com.example.data.quran.MushafEdition
import com.example.data.quran.MushafLine
import com.example.data.quran.MushafOfflineSetupManager
import com.example.data.quran.MushafPageBuilder
import com.example.data.quran.PageFontManager
import com.example.data.quran.QuranRepository
import com.example.data.quran.SurahMeta
import com.example.data.quran.VerseDto
import com.example.data.quran.VersesByPageResponse
import com.example.data.quran.WordDto
import com.example.ui.components.MushafPageBitmapRenderer
import com.example.ui.components.PageRenderSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("قبسات", appName)
  }

  @Test
  fun `page bitmap renderer pre-renders static layout bitmap and caches by spec`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val renderer = MushafPageBitmapRenderer(context)
    val surahs = mapOf(
      1 to SurahMeta(1, "الفاتحة", 5, 7, false, 1, 1, false),
    )
    val page1 = MushafPageBuilder.buildPage(
      pageNumber = 1,
      versesResponse = VersesByPageResponse(
        verses = listOf(
          VerseDto(
            id = 1,
            verse_key = "1:1",
            page_number = 1,
            juz_number = 1,
            hizb_number = 1,
            words = listOf(
              WordDto(1, 2, "word", 1, 1, "بِسْمِ", "بِسْمِ"),
              WordDto(2, 2, "word", 1, 1, "اللَّهِ", "اللَّهِ"),
              WordDto(3, 2, "end", 1, 1, "١", "1"),
            ),
          ),
        ),
      ),
      chaptersById = surahs,
      basmalahTokens = emptyList(),
    )

    val spec = PageRenderSpec(
      widthPx = 620,
      heightPx = 1000,
      density = 2.0f,
      fontScale = 1.0f,
      edition = MushafEdition.TAJWEED_V4,
      isDarkTheme = false,
      backgroundTheme = BackgroundTheme.WHITE,
    )

    assertNull(renderer.getCached(1, spec))

    val rendered = renderer.renderOrGet(
      pageData = page1,
      spec = spec,
      pageTypeface = Typeface.SERIF,
      basmalahTypeface = Typeface.SERIF,
      usesTagFallbackTajweed = false,
      surahsById = surahs,
    )

    assertEquals(620, rendered.imageBitmap.width)
    assertEquals(1000, rendered.imageBitmap.height)
    assertTrue(rendered.byteCount > 0)
    assertEquals(3, rendered.wordHitBoxes.count)
    assertTrue(rendered.verseHighlightRects.containsKey("1:1"))
    assertNotNull(rendered.rosetteSlotsByVerse["1:1"])

    val cached = renderer.getCached(1, spec)
    assertSame(rendered, cached)

    val darkSpec = spec.copy(isDarkTheme = true, backgroundTheme = BackgroundTheme.BLACK)
    assertNull(renderer.getCached(1, darkSpec))
  }

  @Test
  fun `auto update check stays silent offline while manual check reports error`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val manager = com.example.update.AppUpdateManager(context, this)

    manager.checkForUpdatesInBackground()
    kotlinx.coroutines.delay(50)
    assertEquals(com.example.update.AppUpdateState.Idle, manager.state.value)

    manager.checkForUpdates()
    kotlinx.coroutines.delay(50)
    assertTrue(manager.state.value is com.example.update.AppUpdateState.Error)
    manager.release()
  }

  @Test
  fun `page bitmap renderer uses uniform font size across long and short lines and justifies shorter lines`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val renderer = MushafPageBitmapRenderer(context)
    val surahs = mapOf(
      2 to SurahMeta(2, "البقرة", 2, 286, false, 2, 5, true),
    )
    // Line 2 is long (10 words), Line 3 is shorter (5 words) — both must share one uniform page font size
    // and line 3 must justify edge-to-edge across the page width.
    val longLineWords = (1..10).map { idx ->
      WordDto(idx, 2, if (idx == 10) "end" else "word", 2, 1, "كلمة", "كلمة$idx")
    }
    val shortLineWords = (11..15).map { idx ->
      WordDto(idx, 3, if (idx == 15) "end" else "word", 2, 2, "قصير", "قصير$idx")
    }
    val page2 = MushafPageBuilder.buildPage(
      pageNumber = 2,
      versesResponse = VersesByPageResponse(
        verses = listOf(
          VerseDto(2, "2:1", 2, 1, 1, words = longLineWords),
          VerseDto(3, "2:2", 2, 1, 1, words = shortLineWords),
        ),
      ),
      chaptersById = surahs,
      basmalahTokens = emptyList(),
    )

    val spec = PageRenderSpec(
      widthPx = 720,
      heightPx = 1200,
      density = 2.0f,
      fontScale = 1.0f,
      edition = MushafEdition.MADINAH_1421,
      isDarkTheme = false,
      backgroundTheme = BackgroundTheme.WHITE,
    )

    val rendered = renderer.renderOrGet(
      pageData = page2,
      spec = spec,
      pageTypeface = Typeface.SERIF,
      basmalahTypeface = Typeface.SERIF,
      usesTagFallbackTajweed = false,
      surahsById = surahs,
    )

    assertEquals(15, rendered.wordHitBoxes.count)
    val rectLong = rendered.verseHighlightRects["2:1"]!!
    val rectShort = rendered.verseHighlightRects["2:2"]!!
    val longHeight = rectLong[3] - rectLong[1]
    val shortHeight = rectShort[3] - rectShort[1]
    val shortWidth = rectShort[2] - rectShort[0]
    // Uniform font size yields matching vertical text box heights across long and short lines.
    assertEquals(longHeight, shortHeight, 1.0f)
    // Shorter line of 5 words justifies across the majority of the inner line width.
    assertTrue(shortWidth > spec.widthPx * 0.65f)
  }

  @Test
  fun `single page mode uses strictly uniform font size across all lines and justifies lines`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val renderer = MushafPageBitmapRenderer(context)
    val surahs = mapOf(
      2 to SurahMeta(2, "البقرة", 2, 286, false, 2, 5, true),
    )

    // Line 1: longer line (11 words)
    val longWords = (1..11).map { idx ->
      WordDto(idx, 1, if (idx == 11) "end" else "word", 3, 1, "كلمة", "كلمة$idx")
    }
    // Lines 2..5: average body lines (8 words each)
    val avgVerseWords = (2..5).mapIndexed { lineOffset, vId ->
      val lineNo = lineOffset + 2
      VerseDto(
        id = vId,
        verse_key = "2:$vId",
        page_number = 3,
        juz_number = 1,
        hizb_number = 1,
        words = (1..8).map { idx ->
          WordDto(vId * 20 + idx, lineNo, if (idx == 8) "end" else "word", 3, lineNo, "كلمة", "متوسط$idx")
        },
      )
    }
    // Line 6: slightly shorter line (7 words)
    val mediumShortWords = (1..7).map { idx ->
      WordDto(200 + idx, 6, if (idx == 7) "end" else "word", 3, 6, "كلمة", "قصير$idx")
    }
    // Line 7: 3-word line
    val shortWords = (1..3).map { idx ->
      WordDto(300 + idx, 7, if (idx == 3) "end" else "word", 3, 7, "كلمة", "نهاية$idx")
    }

    val page3 = MushafPageBuilder.buildPage(
      pageNumber = 3,
      versesResponse = VersesByPageResponse(
        verses = listOf(VerseDto(1, "2:1", 3, 1, 1, words = longWords)) +
          avgVerseWords +
          listOf(
            VerseDto(6, "2:6", 3, 1, 1, words = mediumShortWords),
            VerseDto(7, "2:7", 3, 1, 1, words = shortWords),
          ),
      ),
      chaptersById = surahs,
      basmalahTokens = emptyList(),
    )

    val spec = PageRenderSpec(
      widthPx = 720,
      heightPx = 1200,
      density = 2.0f,
      fontScale = 1.0f,
      edition = MushafEdition.MADINAH_1421,
      isDarkTheme = false,
      backgroundTheme = BackgroundTheme.WHITE,
      isTwoPageMode = false,
    )

    val rendered = renderer.renderOrGet(
      pageData = page3,
      spec = spec,
      pageTypeface = Typeface.SERIF,
      basmalahTypeface = Typeface.SERIF,
      usesTagFallbackTajweed = false,
      surahsById = surahs,
    )

    val rectLong = rendered.verseHighlightRects["2:1"]!!
    val rectAvg = rendered.verseHighlightRects["2:2"]!!
    val rectMedShort = rendered.verseHighlightRects["2:6"]!!
    val rectShort = rendered.verseHighlightRects["2:7"]!!

    // Long line never overflows frame horizontally
    assertTrue(rectLong[0] >= 0f && rectLong[2] <= spec.widthPx.toFloat())
    val longH = rectLong[3] - rectLong[1]
    val avgH = rectAvg[3] - rectAvg[1]
    // All lines in single-page mode share the exact same uniform font size and vertical text height
    assertEquals(longH, avgH, 1.0f)

    // Lines justify across the width
    val medShortW = rectMedShort[2] - rectMedShort[0]
    assertTrue(medShortW > spec.widthPx * 0.70f)
    val shortW = rectShort[2] - rectShort[0]
    assertTrue(shortW > spec.widthPx * 0.70f)
  }

  @Test
  fun `offline setup manager tracks resumable progress in verified cache and setup flag persists`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = ReaderPreferences(context)
    assertTrue(prefs.stateFlow.first().downloadedMushafIds.isEmpty())

    val diskCache = VerifiedDiskCache(context)
    val fontManager = PageFontManager(diskCache)
    val repo = QuranRepository(context, diskCache, fontManager)
    val manager = MushafOfflineSetupManager(
      diskCache = diskCache,
      quranRepository = repo,
      fontManager = fontManager,
      scope = this,
      includeTajweedFallback = false,
    )

    // Pre-populate verified cache items for both editions + chapters + basmalah + juzs
    diskCache.writeVerifiedBytes(
      "api/chapters_ar.json",
      """{"chapters":[{"id":1,"name_simple":"Al-Fatihah","name_arabic":"الفاتحة","revelation_order":5,"verses_count":7,"bismillah_pre":false,"pages":[1,1],"revelation_place":"makkah"}]}""".encodeToByteArray(),
    )
    diskCache.writeVerifiedBytes(
      "api/juzs_resolved.json",
      """{"juzs":[{"juzNumber":1,"startPage":1,"firstSurahId":1,"firstAyah":1}]}""".encodeToByteArray(),
    )
    diskCache.writeVerifiedBytes(
      "api/verse_1_1.json",
      """{"verse":{"id":1,"verse_key":"1:1","page_number":1,"juz_number":1,"hizb_number":1,"words":[]}}""".encodeToByteArray(),
    )
    diskCache.writeVerifiedBytes(
      "api/page_1.json",
      """{"verses":[{"id":1,"verse_key":"1:1","page_number":1,"juz_number":1,"hizb_number":1,"words":[]}]}""".encodeToByteArray(),
    )
    diskCache.writeVerifiedBytes("fonts/qcf_v2/p1.ttf", byteArrayOf(0x00, 0x01, 0x00, 0x00) + ByteArray(300))
    diskCache.writeVerifiedBytes("fonts/qcf_v4_raw/p1.ttf", byteArrayOf(0x00, 0x01, 0x00, 0x00) + ByteArray(300))

    val scan = manager.inspectCachedProgress()
    assertEquals(1211, scan.madinahProgress.totalFiles)
    assertEquals(5, scan.madinahProgress.completedFiles)
    assertEquals(1206, scan.madinahProgress.remainingFiles)
    assertEquals(604, scan.tajweedProgress.totalFiles)
    assertEquals(1, scan.tajweedProgress.completedFiles)
    assertEquals(603, scan.tajweedProgress.remainingFiles)

    // Corrupt a cached file without updating .sha256 — SHA-256 verification must reject and queue it for re-download
    val corruptedFont = java.io.File(context.filesDir, "qabasat_verified_cache/fonts/qcf_v4_raw/p1.ttf")
    corruptedFont.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
    val rescan = manager.inspectCachedProgress()
    assertEquals(0, rescan.tajweedProgress.completedFiles)
    assertEquals(604, rescan.tajweedProgress.remainingFiles)

    // Offline index & page metadata load cleanly from qabasat_verified_cache
    val surahs = repo.getSurahs().getOrThrow()
    assertEquals(1, surahs.size)
    assertEquals("الفاتحة", surahs.first().nameArabic)
    assertEquals(1, repo.getJuzs().getOrThrow().size)
    assertTrue(repo.getPage(1, includeTajweedFallback = false).isSuccess)

    prefs.addDownloadedMushaf("madinah_1421")
    assertTrue(prefs.stateFlow.first().downloadedMushafIds.contains("madinah_1421"))
  }

  @Test
  fun `layout cache version is bumped and renderer enforces uniform non-clipping layout in portrait and two-page modes`() = runBlocking {
    assertTrue(QuranRepository.LAYOUT_CACHE_VERSION >= 5)

    val context = ApplicationProvider.getApplicationContext<Context>()
    val renderer = MushafPageBitmapRenderer(context)
    val surahs = mapOf(
      1 to SurahMeta(1, "الفاتحة", 5, 7, false, 1, 1, false),
      2 to SurahMeta(2, "البقرة", 87, 286, false, 2, 49, true),
      3 to SurahMeta(3, "آل عمران", 89, 200, false, 50, 76, false),
      97 to SurahMeta(97, "القدر", 25, 5, false, 598, 598, true),
      98 to SurahMeta(98, "البينة", 100, 8, false, 598, 598, false),
    )

    val portraitSpec = PageRenderSpec(
      widthPx = 620,
      heightPx = 1000,
      density = 2.0f,
      fontScale = 1.0f,
      edition = MushafEdition.MADINAH_1421,
      isDarkTheme = false,
      backgroundTheme = BackgroundTheme.WHITE,
      isTwoPageMode = false,
    )
    val landscapeTwoPageSpec = PageRenderSpec(
      widthPx = 780,
      heightPx = 520,
      density = 2.0f,
      fontScale = 1.0f,
      edition = MushafEdition.TAJWEED_V4,
      isDarkTheme = false,
      backgroundTheme = BackgroundTheme.WHITE,
      isTwoPageMode = true,
    )

    // Test page 2 (framed intro with centered=1 lines, basmalah, and long/short lines)
    val page2CenteredShort = (1..3).map { idx ->
      WordDto(idx, 8, if (idx == 3) "end" else "word", 2, 8, "مُفْلِحُونَ", "مفلح$idx")
    }
    val page2LongLine = (1..10).map { idx ->
      WordDto(100 + idx, 5, if (idx == 10) "end" else "word", 2, 5, "ٱلْمُتَّقِينَ", "متق$idx")
    }
    val qulPage2 = listOf(
      com.example.data.quran.QulLayoutLine(1, "surah_name", surah = 2, centered = true),
      com.example.data.quran.QulLayoutLine(2, "basmallah", centered = true),
      com.example.data.quran.QulLayoutLine(3, "ayah", first = 1, last = 8, centered = true),
      com.example.data.quran.QulLayoutLine(4, "ayah", first = 9, last = 15, centered = true),
      com.example.data.quran.QulLayoutLine(5, "ayah", first = 16, last = 25, centered = false),
      com.example.data.quran.QulLayoutLine(6, "ayah", first = 26, last = 33, centered = true),
      com.example.data.quran.QulLayoutLine(7, "ayah", first = 34, last = 39, centered = true),
      com.example.data.quran.QulLayoutLine(8, "ayah", first = 40, last = 42, centered = true),
    )
    fun makeRange(startId: Int, count: Int, lineNo: Int, verseKey: String, pageNo: Int = 2): VerseDto = VerseDto(
      id = startId,
      verse_key = verseKey,
      page_number = pageNo,
      juz_number = 1,
      hizb_number = 1,
      words = (0 until count).map { idx ->
        WordDto(
          id = startId + idx,
          position = idx + 1,
          char_type_name = if (idx == count - 1) "end" else "word",
          line_number = lineNo,
          page_number = pageNo,
          code_v2 = "كَلِمَة",
          text_uthmani = "ٱلْمُتَّقِينَ",
        )
      },
    )
    val page2Data = MushafPageBuilder.buildPageFromQulLayout(
      pageNumber = 2,
      qulLines = qulPage2,
      versesResponses = listOf(
        VersesByPageResponse(
          verses = listOf(
            makeRange(1, 8, 3, "2:1"),
            makeRange(9, 7, 4, "2:2"),
            VerseDto(16, "2:3", 2, 1, 1, words = page2LongLine),
            makeRange(26, 8, 6, "2:4"),
            makeRange(34, 6, 7, "2:5"),
            VerseDto(40, "2:6", 2, 1, 1, words = page2CenteredShort),
          ),
        ),
      ),
      chaptersById = surahs,
      basmalahTokens = listOf(
        WordDto(1, 1, "word", 1, 1, "بِسْمِ", "بِسْمِ"),
        WordDto(2, 2, "word", 1, 1, "اللَّهِ", "اللَّهِ"),
        WordDto(3, 3, "word", 1, 1, "الرَّحْمَٰنِ", "الرَّحْمَٰنِ"),
        WordDto(4, 4, "word", 1, 1, "الرَّحِيمِ", "الرَّحِيمِ"),
      ),
    )

    for (spec in listOf(portraitSpec, landscapeTwoPageSpec, portraitSpec.copy(edition = MushafEdition.TAJWEED_V4))) {
      val rendered = renderer.renderOrGet(
        pageData = page2Data,
        spec = spec,
        pageTypeface = Typeface.SERIF,
        basmalahTypeface = Typeface.SERIF,
        usesTagFallbackTajweed = false,
        surahsById = surahs,
      )
      val boxes = rendered.wordHitBoxes
      assertTrue(boxes.count > 0)
      for (i in 0 until boxes.count) {
        assertTrue(
          "Word box $i left=${boxes.lefts[i]} right=${boxes.rights[i]} overflowed width=${spec.widthPx}",
          boxes.lefts[i] >= 0f && boxes.rights[i] <= spec.widthPx.toFloat(),
        )
      }
      // Uniform height across all ayah lines (never per-line glyph shrink in two-page or portrait)
      val rectLong = rendered.verseHighlightRects["2:3"]!!
      val rectCenteredShort = rendered.verseHighlightRects["2:6"]!!
      assertEquals(rectLong[3] - rectLong[1], rectCenteredShort[3] - rectCenteredShort[1], 1.5f)
      // On page 2, line 5 (2:3, centered=false) justifies edge-to-edge while line 8 (2:6, centered=true) stays centered at natural width
      val longJustifiedW = rectLong[2] - rectLong[0]
      val centeredShortW = rectCenteredShort[2] - rectCenteredShort[0]
      assertTrue(
        "Page 2 centered=false line ($longJustifiedW) should fill width (specWidth=${spec.widthPx})",
        longJustifiedW > spec.widthPx * 0.70f,
      )
      assertTrue(
        "Page 2 centered=true line ($centeredShortW) should stay centered at natural width (< justified $longJustifiedW)",
        centeredShortW < longJustifiedW * 0.85f,
      )
    }

    // On pages 3-604, lines flagged centered=true stay centered at their natural width
    val page3WithCentered = MushafPageBuilder.buildPageFromQulLayout(
      pageNumber = 3,
      qulLines = listOf(
        com.example.data.quran.QulLayoutLine(1, "ayah", first = 1, last = 8, centered = false),
        com.example.data.quran.QulLayoutLine(2, "ayah", first = 9, last = 11, centered = true),
      ),
      versesResponses = listOf(
        VersesByPageResponse(
          verses = listOf(
            makeRange(1, 8, 1, "2:10", pageNo = 3),
            makeRange(9, 3, 2, "2:11", pageNo = 3),
          ),
        ),
      ),
      chaptersById = surahs,
      basmalahTokens = emptyList(),
    )
    val renderedPage3 = renderer.renderOrGet(
      pageData = page3WithCentered,
      spec = portraitSpec,
      pageTypeface = Typeface.SERIF,
      basmalahTypeface = Typeface.SERIF,
      usesTagFallbackTajweed = false,
      surahsById = surahs,
    )
    val justifiedBodyRect = renderedPage3.verseHighlightRects["2:10"]!!
    val centeredBodyRect = renderedPage3.verseHighlightRects["2:11"]!!
    val justifiedBodyW = justifiedBodyRect[2] - justifiedBodyRect[0]
    val centeredBodyW = centeredBodyRect[2] - centeredBodyRect[0]
    assertTrue(
      "Body page centered short line ($centeredBodyW) should stay narrower than justified line ($justifiedBodyW)",
      centeredBodyW < justifiedBodyW * 0.85f,
    )

    // Verify proportional vertical line spacing on page 2 vs standard 15-line page (lines 3..8 pitch < stretched full-body/8)
    val portraitPage2 = renderer.renderOrGet(
      pageData = page2Data,
      spec = portraitSpec,
      pageTypeface = Typeface.SERIF,
      basmalahTypeface = Typeface.SERIF,
      usesTagFallbackTajweed = false,
      surahsById = surahs,
    )
    val line3Top = portraitPage2.verseHighlightRects["2:1"]!![1]
    val line8Top = portraitPage2.verseHighlightRects["2:6"]!![1]
    val avgPitchPage2 = (line8Top - line3Top) / 5f
    // Unstretched pitch must be substantially less than bodyHeight / 8 (~115px)
    assertTrue("Page 2 vertical line pitch ($avgPitchPage2) should stay compact", avgPitchPage2 < portraitSpec.heightPx / 10f)

    // Verify re-render happens when font instance changes after loading
    val altFont = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    val rerendered = renderer.renderOrGet(
      pageData = page2Data,
      spec = portraitSpec,
      pageTypeface = altFont,
      basmalahTypeface = altFont,
      usesTagFallbackTajweed = false,
      surahsById = surahs,
    )
    assertFalse(rerendered === portraitPage2)

    // Verify target debug pages 1, 2, 7, 8, 50, 598 build cleanly from bundled QUL JSON and stay within bounds
    val targetPages = listOf(1, 2, 7, 8, 50, 598)
    for (edition in listOf(MushafEdition.MADINAH_1421, MushafEdition.TAJWEED_V4)) {
      for (pageNum in targetPages) {
        val qulLines = com.example.data.quran.QulPageLayoutLoader.getPageLines(context, edition, pageNum)
        var syntheticWordId = 1
        val verses = qulLines.filter { it.type == "ayah" && it.first != null && it.last != null }.mapIndexed { idx, ql ->
          val count = ql.last!! - ql.first!! + 1
          val words = (0 until count).map { wi ->
            val wid = ql.first!! + wi
            WordDto(
              id = wid,
              position = wi + 1,
              char_type_name = if (wi == count - 1 && idx % 2 == 0) "end" else "word",
              line_number = ql.line,
              page_number = pageNum,
              code_v2 = "كَلِمَة",
              text_uthmani = "ٱلرَّحْمَٰنِ",
            )
          }
          syntheticWordId += count
          VerseDto(idx + 1, "${ql.surah ?: 2}:${idx + 1}", pageNum, 1, 1, words = words)
        }
        val built = MushafPageBuilder.buildPageFromQulLayout(
          pageNumber = pageNum,
          qulLines = qulLines,
          versesResponses = listOf(VersesByPageResponse(verses)),
          chaptersById = surahs,
          basmalahTokens = listOf(
            WordDto(1, 1, "word", 1, 1, "بِسْمِ", "بِسْمِ"),
            WordDto(2, 2, "word", 1, 1, "اللَّهِ", "اللَّهِ"),
            WordDto(3, 3, "word", 1, 1, "الرَّحْمَٰنِ", "الرَّحْمَٰنِ"),
            WordDto(4, 4, "word", 1, 1, "الرَّحِيمِ", "الرَّحِيمِ"),
          ),
        )
        for (spec in listOf(portraitSpec.copy(edition = edition), landscapeTwoPageSpec.copy(edition = edition))) {
          val rendered = renderer.renderOrGet(
            pageData = built,
            spec = spec,
            pageTypeface = Typeface.SERIF,
            basmalahTypeface = Typeface.SERIF,
            usesTagFallbackTajweed = false,
            surahsById = surahs,
          )
          for (i in 0 until rendered.wordHitBoxes.count) {
            assertTrue(
              "Page $pageNum ($edition twoPage=${spec.isTwoPageMode}) box $i clipped: [${rendered.wordHitBoxes.lefts[i]}, ${rendered.wordHitBoxes.rights[i]}] width=${spec.widthPx}",
              rendered.wordHitBoxes.lefts[i] >= 0f && rendered.wordHitBoxes.rights[i] <= spec.widthPx.toFloat(),
            )
          }
        }
      }
    }
  }

  @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  @Test
  fun `global mushaf font sizer computes unified intro and body sizes and persists cache in preferences`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = ReaderPreferences(context)
    val diskCache = VerifiedDiskCache(context)
    val fontManager = PageFontManager(diskCache)
    val repo = QuranRepository(context, diskCache, fontManager)
    val renderer = MushafPageBitmapRenderer(context)
    val sizer = GlobalMushafFontSizer(repo, fontManager, renderer)

    val surahsJson = """{"chapters":[""" +
      """{"id":1,"name_simple":"Al-Fatihah","name_arabic":"الفاتحة","revelation_order":5,"verses_count":7,"bismillah_pre":false,"pages":[1,1],"revelation_place":"makkah"},""" +
      """{"id":2,"name_simple":"Al-Baqarah","name_arabic":"البقرة","revelation_order":87,"verses_count":286,"bismillah_pre":true,"pages":[2,49],"revelation_place":"madinah"}""" +
      """]}"""
    diskCache.writeVerifiedBytes("api/chapters_ar.json", surahsJson.encodeToByteArray())
    diskCache.writeVerifiedBytes(
      "api/verse_1_1.json",
      ("""{"verse":{"id":1,"verse_key":"1:1","page_number":1,"juz_number":1,"hizb_number":1,"words":[""" +
        """{"id":1,"position":1,"char_type_name":"word","line_number":2,"page_number":1,"code_v2":"بِسْمِ","text_uthmani":"بِسْمِ"},""" +
        """{"id":2,"position":2,"char_type_name":"word","line_number":2,"page_number":1,"code_v2":"اللَّهِ","text_uthmani":"اللَّهِ"},""" +
        """{"id":3,"position":3,"char_type_name":"word","line_number":2,"page_number":1,"code_v2":"الرَّحْمَٰنِ","text_uthmani":"الرَّحْمَٰنِ"},""" +
        """{"id":4,"position":4,"char_type_name":"word","line_number":2,"page_number":1,"code_v2":"الرَّحِيمِ","text_uthmani":"الرَّحِيمِ"}""" +
        """]}}""").encodeToByteArray(),
    )

    // Craft verified cached pages 1..4 matching the exact QUL token counts in layout_madinah_v2.json
    // so MushafPageBuilder resolves every line cleanly; make page 2 wider than page 1 and page 4 wider than page 3.
    suspend fun writeQulMatchedPageJson(pageNum: Int, verseKey: String, wideLineNo: Int?, tokenTextNormal: String, tokenTextWide: String) {
      val qulAyahLines = com.example.data.quran.QulPageLayoutLoader
        .getPageLines(context, MushafEdition.MADINAH_1421, pageNum)
        .filter { it.type == "ayah" && it.first != null && it.last != null }
      val firstQulId = qulAyahLines.minOf { it.first!! }
      val lastQulId = qulAyahLines.maxOf { it.last!! }
      val lineByWordId = HashMap<Int, Int>()
      for (ql in qulAyahLines) {
        for (wid in ql.first!!..ql.last!!) {
          lineByWordId[wid] = ql.line
        }
      }
      val wordsJson = (firstQulId..lastQulId).mapIndexed { idx, wid ->
        val lineNo = lineByWordId[wid] ?: qulAyahLines.first().line
        val txt = if (wideLineNo != null && lineNo == wideLineNo) tokenTextWide else tokenTextNormal
        val pos = idx + 1
        val type = if (wid == lastQulId) "end" else "word"
        """{"id":$wid,"position":$pos,"char_type_name":"$type","line_number":$lineNo,"page_number":$pageNum,"code_v2":"$txt","text_uthmani":"$txt"}"""
      }.joinToString(",")
      val json = """{"verses":[{"id":$pageNum,"verse_key":"$verseKey","page_number":$pageNum,"juz_number":1,"hizb_number":1,"words":[$wordsJson]}]}"""
      diskCache.writeVerifiedBytes("api/page_$pageNum.json", json.encodeToByteArray())
    }

    writeQulMatchedPageJson(pageNum = 1, verseKey = "1:1", wideLineNo = null, tokenTextNormal = "ا", tokenTextWide = "ا")
    writeQulMatchedPageJson(pageNum = 2, verseKey = "2:1", wideLineNo = 5, tokenTextNormal = "بِسْمِ", tokenTextWide = "ٱلْمُتَّقِينَٱلْمُتَّقِينَ")
    writeQulMatchedPageJson(pageNum = 3, verseKey = "2:6", wideLineNo = null, tokenTextNormal = "ا", tokenTextWide = "ا")
    writeQulMatchedPageJson(pageNum = 4, verseKey = "2:17", wideLineNo = 2, tokenTextNormal = "كَلِمَة", tokenTextWide = "ٱلْمُتَّقِينَٱلْمُتَّقِينَ")

    val spec = PageRenderSpec(
      widthPx = 620,
      heightPx = 1000,
      density = 2.0f,
      fontScale = 1.0f,
      edition = MushafEdition.MADINAH_1421,
      isDarkTheme = false,
      backgroundTheme = BackgroundTheme.WHITE,
      isTwoPageMode = false,
    )
    val metrics = renderer.computeTextBodyMetrics(spec)
    val fontVersion = fontManager.fontVersionFor(MushafEdition.MADINAH_1421, usesTagFallback = true)
    val cacheKey = GlobalMushafFontSizer.buildCacheKey(
      edition = spec.edition,
      fontVersion = fontVersion,
      layoutVersion = QuranRepository.LAYOUT_CACHE_VERSION,
      safeWidthPx = metrics.safeWidthPx,
      bodySlotHeightPx = metrics.bodySlotHeightPx,
      spec = spec,
    )

    var lastProgressTotal = 0
    val sizingResult = sizer.computeGlobalFontSizes(
      spec = spec,
      usesTagFallbackTajweed = true,
      fontVersion = fontVersion,
      pageRangeIntro = 1..2,
      pageRangeBody = 3..4,
      onProgress = { _, total -> lastProgressTotal = total },
    )

    assertEquals("sizingResult=$sizingResult", 4, sizingResult.measuredPagesCount)
    assertTrue("sizingResult=$sizingResult", sizingResult.allPagesMeasured)
    assertEquals("sizingResult=$sizingResult", 4, lastProgressTotal)
    assertEquals("sizingResult=$sizingResult", 2, sizingResult.introLimitingPage)
    assertEquals("sizingResult=$sizingResult", 4, sizingResult.bodyLimitingPage)
    assertTrue("sizingResult=$sizingResult", sizingResult.introSp > sizingResult.bodySp)

    prefs.saveGlobalFontSizes(cacheKey, sizingResult.introSp, sizingResult.bodySp)
    val persisted = prefs.stateFlow.first().globalFontSizes[cacheKey]
    assertNotNull(persisted)
    assertEquals(sizingResult.introSp, persisted!!.introSp, 0.001f)
    assertEquals(sizingResult.bodySp, persisted.bodySp, 0.001f)

    // Render Page 1 & Page 2 with globalIntroFontSp -> same line height & full-width fill
    val globalSpec = spec.copy(
      globalIntroFontSp = sizingResult.introSp,
      globalBodyFontSp = sizingResult.bodySp,
    )
    val surahs = repo.getSurahs().getOrThrow().associateBy { it.id }
    val p1Data = repo.getPageForMeasurement(1, MushafEdition.MADINAH_1421, includeTajweedFallback = true).getOrThrow()
    val p2Data = repo.getPageForMeasurement(2, MushafEdition.MADINAH_1421, includeTajweedFallback = true).getOrThrow()
    val p3Data = repo.getPageForMeasurement(3, MushafEdition.MADINAH_1421, includeTajweedFallback = true).getOrThrow()
    val p4Data = repo.getPageForMeasurement(4, MushafEdition.MADINAH_1421, includeTajweedFallback = true).getOrThrow()

    val r1 = renderer.renderOrGet(p1Data, globalSpec, Typeface.SERIF, Typeface.SERIF, true, surahs)
    val r2 = renderer.renderOrGet(p2Data, globalSpec, Typeface.SERIF, Typeface.SERIF, true, surahs)
    val r3 = renderer.renderOrGet(p3Data, globalSpec, Typeface.SERIF, Typeface.SERIF, true, surahs)
    val r4 = renderer.renderOrGet(p4Data, globalSpec, Typeface.SERIF, Typeface.SERIF, true, surahs)

    // Compare individual line heights from wordHitBoxes (page 1 & 2 span multiple lines of a single synthetic verse)
    val h1Line = r1.wordHitBoxes.let { it.bottoms[0] - it.tops[0] }
    val h2Line = r2.wordHitBoxes.let { it.bottoms[0] - it.tops[0] }
    val h3Line = r3.wordHitBoxes.let { it.bottoms[0] - it.tops[0] }
    val h4Line = r4.wordHitBoxes.let { it.bottoms[0] - it.tops[0] }

    // Pages 1 & 2 share identical intro font height; Pages 3 & 4 share identical smaller body font height
    assertEquals(h1Line, h2Line, 1.0f)
    assertEquals(h3Line, h4Line, 1.0f)
    assertTrue(h1Line > h3Line)

    // In Madinah layout, centered=1 lines on Page 1 & Page 2 stay centered at natural width (not stretched)
    val r1Line2Rect = r1.verseHighlightRects["1:1"]!!
    val w1 = r1Line2Rect[2] - r1Line2Rect[0]
    val c1 = (r1Line2Rect[0] + r1Line2Rect[2]) * 0.5f
    assertTrue("Page 1 centered short line width $w1 should stay at natural width", w1 < spec.widthPx * 0.60f)
    assertTrue("Page 1 centered short line center $c1 should align with page center", kotlin.math.abs(c1 - spec.widthPx * 0.5f) < spec.widthPx * 0.08f)

    prefs.clearGlobalFontSizes()
    assertTrue(prefs.stateFlow.first().globalFontSizes.isEmpty())
  }

  @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  @Test
  fun `madinah page 1 lines are centered at natural width and tajweed page 2 line 5 is justified`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val renderer = MushafPageBitmapRenderer(context)
    val surahs = mapOf(
      1 to SurahMeta(1, "الفاتحة", 5, 7, false, 1, 1, false),
      2 to SurahMeta(2, "البقرة", 87, 286, false, 2, 49, true),
    )
    val basmalahTokens = listOf(
      WordDto(1, 1, "word", 1, 1, "بِسْمِ", "بِسْمِ"),
      WordDto(2, 2, "word", 1, 1, "اللَّهِ", "اللَّهِ"),
      WordDto(3, 3, "word", 1, 1, "الرَّحْمَٰنِ", "الرَّحْمَٰنِ"),
      WordDto(4, 4, "word", 1, 1, "الرَّحِيمِ", "الرَّحِيمِ"),
    )

    fun buildSyntheticPage(
      pageNum: Int,
      edition: MushafEdition,
      surahId: Int,
      tokenText: String,
    ): com.example.data.quran.MushafPageData {
      val qulLines = com.example.data.quran.QulPageLayoutLoader.getPageLines(context, edition, pageNum)
      val verses = qulLines
        .filter { it.type == "ayah" && it.first != null && it.last != null }
        .map { ql ->
          val count = ql.last!! - ql.first!! + 1
          val words = (0 until count).map { idx ->
            val wid = ql.first!! + idx
            WordDto(
              id = wid,
              position = idx + 1,
              char_type_name = if (idx == count - 1) "end" else "word",
              line_number = ql.line,
              page_number = pageNum,
              code_v2 = tokenText,
              text_uthmani = tokenText,
            )
          }
          VerseDto(
            id = ql.line,
            verse_key = "$surahId:${ql.line}",
            page_number = pageNum,
            juz_number = 1,
            hizb_number = 1,
            words = words,
          )
        }
      return MushafPageBuilder.buildPageFromQulLayout(
        pageNumber = pageNum,
        qulLines = qulLines,
        versesResponses = listOf(VersesByPageResponse(verses)),
        chaptersById = surahs,
        basmalahTokens = basmalahTokens,
      )
    }

    val spec = PageRenderSpec(
      widthPx = 620,
      heightPx = 1000,
      density = 2.0f,
      fontScale = 1.0f,
      edition = MushafEdition.MADINAH_1421,
      isDarkTheme = false,
      backgroundTheme = BackgroundTheme.WHITE,
      globalIntroFontSp = 18f,
    )

    // 1) Madinah Page 1: every ayah line has centered=1 -> centered at natural width, never stretched
    val madinahPage1 = buildSyntheticPage(1, MushafEdition.MADINAH_1421, 1, "قُلْ")
    val madinahAyahLines = madinahPage1.lines.filterIsInstance<MushafLine.AyahWordsLine>()
    assertEquals(7, madinahAyahLines.size)
    assertTrue(madinahAyahLines.all { it.isCentered })

    val renderedMadinahP1 = renderer.renderOrGet(
      pageData = madinahPage1,
      spec = spec,
      pageTypeface = Typeface.SERIF,
      basmalahTypeface = Typeface.SERIF,
      usesTagFallbackTajweed = true,
      surahsById = surahs,
    )
    for (line in madinahAyahLines) {
      val rect = renderedMadinahP1.verseHighlightRects["1:${line.lineNumber}"]!!
      val lineWidth = rect[2] - rect[0]
      val lineCenter = (rect[0] + rect[2]) * 0.5f
      assertTrue(
        "Madinah page 1 line ${line.lineNumber} width ($lineWidth) should stay at natural width (< 70% of ${spec.widthPx})",
        lineWidth < spec.widthPx * 0.70f,
      )
      assertTrue(
        "Madinah page 1 line ${line.lineNumber} center ($lineCenter) should be centered in ${spec.widthPx}",
        kotlin.math.abs(lineCenter - spec.widthPx * 0.5f) < spec.widthPx * 0.08f,
      )
    }

    // 2) Tajweed Page 2: line 5 has centered=0 -> justified edge-to-edge; centered lines stay centered at natural width
    val tajweedPage2 = buildSyntheticPage(2, MushafEdition.TAJWEED_V4, 2, "قُلْ")
    val tajweedLine5 = tajweedPage2.lines.filterIsInstance<MushafLine.AyahWordsLine>().first { it.lineNumber == 5 }
    val tajweedLine3 = tajweedPage2.lines.filterIsInstance<MushafLine.AyahWordsLine>().first { it.lineNumber == 3 }
    val tajweedLine8 = tajweedPage2.lines.filterIsInstance<MushafLine.AyahWordsLine>().first { it.lineNumber == 8 }
    assertFalse("Tajweed page 2 line 5 must have centered=false", tajweedLine5.isCentered)
    assertTrue("Tajweed page 2 line 3 must have centered=true", tajweedLine3.isCentered)
    assertTrue("Tajweed page 2 line 8 must have centered=true", tajweedLine8.isCentered)

    val tajweedSpec = spec.copy(edition = MushafEdition.TAJWEED_V4)
    val renderedTajweedP2 = renderer.renderOrGet(
      pageData = tajweedPage2,
      spec = tajweedSpec,
      pageTypeface = Typeface.SERIF,
      basmalahTypeface = Typeface.SERIF,
      usesTagFallbackTajweed = true,
      surahsById = surahs,
    )
    val line5Rect = renderedTajweedP2.verseHighlightRects["2:5"]!!
    val line3Rect = renderedTajweedP2.verseHighlightRects["2:3"]!!
    val line8Rect = renderedTajweedP2.verseHighlightRects["2:8"]!!
    val line5Width = line5Rect[2] - line5Rect[0]
    val line3Width = line3Rect[2] - line3Rect[0]
    val line8Width = line8Rect[2] - line8Rect[0]

    assertTrue(
      "Tajweed page 2 line 5 ($line5Width) must justify across content width (specWidth=${tajweedSpec.widthPx})",
      line5Width > tajweedSpec.widthPx * 0.78f,
    )
    // Line 3 has the same 8-word count as line 5, so natural centered width must be substantially narrower than justified line 5
    assertTrue(
      "Tajweed page 2 centered line 3 ($line3Width) should stay narrower than justified line 5 ($line5Width)",
      line3Width < line5Width * 0.80f,
    )
    assertTrue(
      "Tajweed page 2 centered line 8 ($line8Width) should stay narrower than justified line 5 ($line5Width)",
      line8Width < line5Width * 0.65f,
    )
    val line8Center = (line8Rect[0] + line8Rect[2]) * 0.5f
    assertTrue(
      "Tajweed page 2 line 8 center ($line8Center) should be centered in ${tajweedSpec.widthPx}",
      kotlin.math.abs(line8Center - tajweedSpec.widthPx * 0.5f) < tajweedSpec.widthPx * 0.08f,
    )
  }
}
