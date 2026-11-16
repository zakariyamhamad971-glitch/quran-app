package com.example

import com.example.data.quran.MushafLine
import com.example.data.quran.MushafPageBuilder
import com.example.data.quran.SurahMeta
import com.example.data.quran.TajweedTagParser
import com.example.data.quran.VerseDto
import com.example.data.quran.VersesByPageResponse
import com.example.data.quran.WordDto
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MushafLayoutUnitTest {

  @Test
  fun `buildPage preserves exact 15 lines and surah header plus basmalah slots`() {
    val surahs = mapOf(
      1 to SurahMeta(1, "S1", 5, 7, false, 1, 1, true),
      2 to SurahMeta(2, "S2", 87, 286, true, 2, 49, false),
    )
    val basmalahTokens = listOf(
      WordDto(1, 1, "word", 1, 2, "g1", "w1"),
      WordDto(2, 2, "word", 1, 2, "g2", "w2"),
      WordDto(3, 3, "word", 1, 2, "g3", "w3"),
      WordDto(4, 4, "word", 1, 2, "g4", "w4"),
      WordDto(5, 5, "end", 1, 2, "g5", "1"),
    )
    val page2Verses = VersesByPageResponse(
      verses = listOf(
        VerseDto(
          id = 8,
          verse_key = "2:1",
          page_number = 2,
          juz_number = 1,
          hizb_number = 1,
          words = listOf(
            WordDto(10, 1, "word", 2, 3, "a1", "t1"),
            WordDto(11, 2, "end", 2, 3, "a2", "1"),
          ),
        ),
      ),
    )

    val page = MushafPageBuilder.buildPage(
      pageNumber = 2,
      versesResponse = page2Verses,
      chaptersById = surahs,
      basmalahTokens = basmalahTokens,
    )

    assertEquals(15, page.lines.size)
    assertEquals((1..15).toList(), page.lines.map { it.lineNumber })
    assertTrue(page.lines[0] is MushafLine.SurahHeaderLine)
    assertTrue(page.lines[1] is MushafLine.BasmalahLine)
    assertTrue(page.lines[2] is MushafLine.AyahWordsLine)
    assertEquals("g1 g2 g3 g4", (page.lines[1] as MushafLine.BasmalahLine).codeV2Glyphs)
    assertTrue(page.lines[14] is MushafLine.EmptyLine)
  }

  @Test
  fun `tajweed tag parser preserves inner characters verbatim`() {
    val parsed = TajweedTagParser.parseToAnnotatedString("[h:1[A]][n[BC]]D", Color.Black)
    assertEquals("ABCD", parsed.text)
  }

  @Test
  fun `cpal patcher switches near black ink between black and white while preserving chromatic entries`() {
    // Minimal synthetic SFNT font with CPAL table containing 2 BGRA records:
    // Record 0: near-black (#111111FF)
    // Record 1: Tajweed red (#FF0008FF -> BGRA: 08, 00, FF, FF)
    val cpalOffset = 28
    val colorRecordsOffset = 12
    val cpalSize = 20
    val sfnt = ByteArray(cpalOffset + cpalSize)
    val buf = java.nio.ByteBuffer.wrap(sfnt).order(java.nio.ByteOrder.BIG_ENDIAN)
    buf.putShort(4, 1.toShort()) // numTables = 1
    // Table record for CPAL at byte 12
    sfnt[12] = 'C'.code.toByte()
    sfnt[13] = 'P'.code.toByte()
    sfnt[14] = 'A'.code.toByte()
    sfnt[15] = 'L'.code.toByte()
    buf.putInt(20, cpalOffset)
    buf.putInt(24, cpalSize)
    // CPAL header
    buf.putShort(cpalOffset + 6, 2.toShort()) // numColorRecords = 2
    buf.putInt(cpalOffset + 8, colorRecordsOffset)
    // Record 0 (near black BGRA)
    val rec0 = cpalOffset + colorRecordsOffset
    sfnt[rec0] = 0x11
    sfnt[rec0 + 1] = 0x11
    sfnt[rec0 + 2] = 0x11
    sfnt[rec0 + 3] = 0xFF.toByte()
    // Record 1 (chromatic Tajweed red BGRA)
    val rec1 = rec0 + 4
    sfnt[rec1] = 0x08
    sfnt[rec1 + 1] = 0x00
    sfnt[rec1 + 2] = 0xFF.toByte()
    sfnt[rec1 + 3] = 0xFF.toByte()

    val whiteInk = com.example.data.quran.CpalFontPatcher.patchCpalBlackToWhite(sfnt)
    assertEquals(0xFF, whiteInk[rec0].toInt() and 0xFF)
    assertEquals(0xFF, whiteInk[rec0 + 1].toInt() and 0xFF)
    assertEquals(0xFF, whiteInk[rec0 + 2].toInt() and 0xFF)
    assertEquals(0x08, whiteInk[rec1].toInt() and 0xFF)
    assertEquals(0xFF, whiteInk[rec1 + 2].toInt() and 0xFF)

    val blackInk = com.example.data.quran.CpalFontPatcher.patchCpalBlackToBlack(sfnt)
    assertEquals(0x00, blackInk[rec0].toInt() and 0xFF)
    assertEquals(0x00, blackInk[rec0 + 1].toInt() and 0xFF)
    assertEquals(0x00, blackInk[rec0 + 2].toInt() and 0xFF)
    assertEquals(0x08, blackInk[rec1].toInt() and 0xFF)
    assertEquals(0xFF, blackInk[rec1 + 2].toInt() and 0xFF)
  }

  @Test
  fun `background themes only contain white and black`() {
    assertEquals(
      listOf(com.example.data.prefs.BackgroundTheme.WHITE, com.example.data.prefs.BackgroundTheme.BLACK),
      com.example.data.prefs.BackgroundTheme.entries.toList(),
    )
  }

  @Test
  fun `parseTimingReadsJson handles top-level mp3quran array payload`() {
    val liveStyleArray = """
      [
        {"id": 123, "name": "مشاري العفاسي", "rewaya": "حفص عن عاصم", "folder_url": "https://cdn.mp3quran.net/audio/mishary-alafasy/r1/"},
        {"id": 288, "name": "مصطفى إسماعيل", "rewaya": "المصحف المجود", "folder_url": "https://cdn.mp3quran.net/audio/mustafa-ismail/r2/"}
      ]
    """.trimIndent()
    val parsed = com.example.data.audio.AudioRepository.parseTimingReadsJson(liveStyleArray)
    assertEquals(2, parsed.size)
    assertEquals(123, parsed[0].id)
    assertEquals("https://cdn.mp3quran.net/audio/mustafa-ismail/r2/", parsed[1].folder_url)
  }

  @Test
  fun `quran com chapter reciters dto parses nested style object and matches arabic names`() {
    val json = """
      {
        "reciters": [
          {
            "id": 1,
            "name": "Abdul Basit Abdul Samad",
            "style": {"name": "Mujawwad", "language_name": "english"},
            "qirat": {"name": "Hafs", "language_name": "english"},
            "translated_name": {"name": "AbdulBaset AbdulSamad [Mujawwad]", "language_name": "english"}
          },
          {
            "id": 2,
            "name": "Abdul Basit Abdul Samad",
            "style": {"name": "Murattal", "language_name": "english"},
            "qirat": {"name": "Hafs", "language_name": "english"},
            "translated_name": {"name": "Abdul Baset Abdul Samad", "language_name": "english"}
          },
          {
            "id": 7,
            "name": "Mishari Rashid al-`Afasy",
            "style": {"name": "Murattal", "language_name": "english"},
            "qirat": {"name": "Hafs", "language_name": "english"}
          },
          {
            "id": 1497,
            "name": "Yasser ad-Dussary",
            "style": {"name": "Murattal", "language_name": "english"},
            "qirat": {"name": "Hafs", "language_name": "english"}
          }
        ]
      }
    """.trimIndent()
    val adapter = com.example.data.quran.QuranApiClient.moshi.adapter(
      com.example.data.audio.QuranComChapterRecitersResponse::class.java,
    )
    val list = adapter.fromJson(json)!!.reciters
    assertEquals(
      2,
      com.example.data.audio.AudioRepository.findMatchingQuranComReciterIdFromList(
        "عبدالباسط عبدالصمد",
        list,
      ),
    )
    assertEquals(
      7,
      com.example.data.audio.AudioRepository.findMatchingQuranComReciterIdFromList(
        "مشاري العفاسي",
        list,
      ),
    )
    assertEquals(
      1497,
      com.example.data.audio.AudioRepository.findMatchingQuranComReciterIdFromList(
        "ياسر الدوسري",
        list,
      ),
    )
  }
}
