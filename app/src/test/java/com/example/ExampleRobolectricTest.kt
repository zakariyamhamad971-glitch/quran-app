package com.example

import android.content.Context
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.example.data.prefs.BackgroundTheme
import com.example.data.quran.MushafEdition
import com.example.data.quran.MushafLine
import com.example.data.quran.MushafPageBuilder
import com.example.data.quran.SurahMeta
import com.example.data.quran.VerseDto
import com.example.data.quran.VersesByPageResponse
import com.example.data.quran.WordDto
import com.example.ui.components.MushafPageBitmapRenderer
import com.example.ui.components.PageRenderSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
}
