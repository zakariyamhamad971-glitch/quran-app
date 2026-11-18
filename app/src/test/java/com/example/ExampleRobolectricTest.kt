package com.example

import android.content.Context
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.example.data.integrity.VerifiedDiskCache
import com.example.data.prefs.BackgroundTheme
import com.example.data.prefs.ReaderPreferences
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
  fun `normal page sizes uniformly from average lines, caps long shrink within 8 percent, justifies medium lines, and centers sparse lines`() = runBlocking {
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
    // Line 6: slightly shorter line (7 words -> should justify edge-to-edge cleanly)
    val mediumShortWords = (1..7).map { idx ->
      WordDto(200 + idx, 6, if (idx == 7) "end" else "word", 3, 6, "كلمة", "قصير$idx")
    }
    // Line 7: sparse 2-word stub (huge remaining gap -> must remain centered, not edge-to-edge stretched)
    val sparseWords = (1..2).map { idx ->
      WordDto(300 + idx, 7, if (idx == 2) "end" else "word", 3, 7, "كلمة", "نهاية$idx")
    }

    val page3 = MushafPageBuilder.buildPage(
      pageNumber = 3,
      versesResponse = VersesByPageResponse(
        verses = listOf(VerseDto(1, "2:1", 3, 1, 1, words = longWords)) +
          avgVerseWords +
          listOf(
            VerseDto(6, "2:6", 3, 1, 1, words = mediumShortWords),
            VerseDto(7, "2:7", 3, 1, 1, words = sparseWords),
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
    val rectSparse = rendered.verseHighlightRects["2:7"]!!

    // Long line never overflows frame horizontally and shrinks at most 8% from uniform height
    assertTrue(rectLong[0] >= 0f && rectLong[2] <= spec.widthPx.toFloat())
    val longH = rectLong[3] - rectLong[1]
    val avgH = rectAvg[3] - rectAvg[1]
    assertTrue(longH >= avgH * 0.90f)

    // Medium-short line (7 words) justifies cleanly across majority of width
    val medShortW = rectMedShort[2] - rectMedShort[0]
    assertTrue(medShortW > spec.widthPx * 0.70f)

    // Sparse 2-word line stays centered with reasonable gaps, NOT stretched edge-to-edge
    val sparseW = rectSparse[2] - rectSparse[0]
    assertTrue(sparseW < spec.widthPx * 0.55f)
    val sparseCenter = (rectSparse[0] + rectSparse[2]) * 0.5f
    assertEquals(spec.widthPx * 0.5f, sparseCenter, 25f)
  }

  @Test
  fun `offline setup manager tracks resumable progress in verified cache and setup flag persists`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = ReaderPreferences(context)
    assertFalse(prefs.stateFlow.first().mushafSetupCompleted)

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

    prefs.setMushafSetupCompleted(true)
    assertTrue(prefs.stateFlow.first().mushafSetupCompleted)
  }
}
