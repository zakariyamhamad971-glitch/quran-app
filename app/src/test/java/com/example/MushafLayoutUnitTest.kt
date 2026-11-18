package com.example

import androidx.compose.ui.graphics.Color
import com.example.data.integrity.Sha256Integrity
import com.example.data.quran.MushafEdition
import com.example.data.quran.MushafLine
import com.example.data.quran.MushafPageBuilder
import com.example.data.quran.QulLayoutLine
import com.example.data.quran.QulPageLayoutLoader
import com.example.data.quran.SurahMeta
import com.example.data.quran.TajweedTagParser
import com.example.data.quran.VerseDto
import com.example.data.quran.VersesByPageResponse
import com.example.data.quran.WordDto
import com.squareup.moshi.JsonReader
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MushafLayoutUnitTest {

  private fun locateQuranAsset(relativeName: String): File {
    val candidates = listOf(
      File("src/main/assets/quran/$relativeName"),
      File("app/src/main/assets/quran/$relativeName"),
    )
    return candidates.firstOrNull { it.exists() }
      ?: error("Cannot locate asset $relativeName from ${File(".").absolutePath}")
  }

  private fun assertCompleteQulLayout(fileName: String) {
    val file = locateQuranAsset(fileName)
    val pages = QulPageLayoutLoader.parseLayoutJson(file.readText())
    assertEquals("Expected 604 pages in $fileName", 604, pages.size)

    var expectedNextWordId = 1
    val seenSurahHeaders = mutableListOf<Int>()
    var basmallahCount = 0

    for (pageNum in 1..604) {
      val lines = pages[pageNum] ?: error("Missing page $pageNum in $fileName")
      val expectedLineCount = if (pageNum in 1..2) 8 else 15
      assertEquals("Page $pageNum line count mismatch in $fileName", expectedLineCount, lines.size)
      assertEquals((1..expectedLineCount).toList(), lines.map { it.line })

      for (line in lines) {
        when (line.type) {
          "ayah" -> {
            val first = line.first ?: error("Missing first on p$pageNum L${line.line} in $fileName")
            val last = line.last ?: error("Missing last on p$pageNum L${line.line} in $fileName")
            assertEquals(
              "Gap or duplicate word at p$pageNum L${line.line} in $fileName",
              expectedNextWordId,
              first,
            )
            assertTrue("Invalid range [$first..$last] on p$pageNum L${line.line}", last >= first)
            expectedNextWordId = last + 1
          }
          "surah_name" -> {
            val surah = line.surah ?: error("Missing surah on p$pageNum L${line.line} in $fileName")
            seenSurahHeaders += surah
          }
          "basmallah" -> basmallahCount++
          else -> error("Unexpected line type ${line.type} on p$pageNum L${line.line}")
        }
      }
    }

    // All 83,668 Quran word tokens (including 6,236 ayah-end markers across all 6,236 verses) covered contiguously
    assertEquals(83668, expectedNextWordId - 1)
    assertEquals((1..114).toList(), seenSurahHeaders)
    // All 114 surahs except Al-Fatihah (uses 1:1 as ayah) and At-Tawbah (9, no basmalah) = 112
    assertEquals(112, basmallahCount)
  }

  @Test
  fun `both QUL layouts cover all 604 pages 8 or 15 lines and all 83668 Quran tokens without gaps`() {
    assertCompleteQulLayout("layout_madinah_v2.json")
    assertCompleteQulLayout("layout_tajweed_v4.json")
  }

  @Test
  fun `manifest sha256 matches bundled QUL layout assets`() {
    val manifestMap = mutableMapOf<String, String>()
    JsonReader.of(Buffer().writeUtf8(locateQuranAsset("manifest.sha256.json").readText())).use { r ->
      r.beginObject()
      while (r.hasNext()) {
        if (r.nextName() == "files") {
          r.beginArray()
          while (r.hasNext()) {
            var path = ""
            var sha256 = ""
            r.beginObject()
            while (r.hasNext()) {
              when (r.nextName()) {
                "path" -> path = r.nextString()
                "sha256" -> sha256 = r.nextString().lowercase()
                else -> r.skipValue()
              }
            }
            r.endObject()
            manifestMap[path] = sha256
          }
          r.endArray()
        } else {
          r.skipValue()
        }
      }
      r.endObject()
    }
    for (name in listOf("credits.json", "layout_madinah_v2.json", "layout_tajweed_v4.json")) {
      val actual = Sha256Integrity.sha256Hex(locateQuranAsset(name))
      assertEquals("SHA-256 mismatch for $name", manifestMap[name], actual)
    }

    val fontCandidates = listOf(
      File("src/main/res/font/surah_name_v4.ttf"),
      File("app/src/main/res/font/surah_name_v4.ttf"),
    )
    val fontFile = fontCandidates.firstOrNull { it.exists() }
      ?: error("Missing res/font/surah_name_v4.ttf")
    val expectedFontSha = "026cfe8ac461531a7b1c8e4edd05ce3343f09e9c73447ff14c6bc93f3193d661"
    val actualFontSha = Sha256Integrity.sha256Hex(fontFile)
    assertEquals(expectedFontSha, actualFontSha)
    assertEquals(expectedFontSha, manifestMap["res/font/surah_name_v4.ttf"])
  }

  @Test
  fun `surah name ligature formatter maps surah 1 to 114 and credits registers QUL V4 font`() {
    assertEquals(
      "surah001",
      com.example.ui.components.SurahHeaderOrnamentRenderer.formatSurahLigature(1),
    )
    assertEquals(
      "surah017",
      com.example.ui.components.SurahHeaderOrnamentRenderer.formatSurahLigature(17),
    )
    assertEquals(
      "surah114",
      com.example.ui.components.SurahHeaderOrnamentRenderer.formatSurahLigature(114),
    )
    val creditsText = locateQuranAsset("credits.json").readText()
    assertTrue(creditsText.contains("QUL V4 Surah Name Font"))
    assertTrue(creditsText.contains("qul_surah_name_v4"))
  }

  @Test
  fun `buildPageFromQulLayout resolves boundary pages 597 598 604 across both editions`() {
    val basmalahTokens = listOf(
      WordDto(1, 1, "word", 1, 2, "b1", "بسم"),
      WordDto(2, 2, "word", 1, 2, "b2", "الله"),
      WordDto(3, 3, "word", 1, 2, "b3", "الرحمن"),
      WordDto(4, 4, "word", 1, 2, "b4", "الرحيم"),
      WordDto(5, 5, "end", 1, 2, "b5", "١"),
    )
    val surahs = (1..114).associateWith { id ->
      SurahMeta(id, "S$id", id, 10, id != 9, 1, 604, true)
    }

    for (assetName in listOf("layout_madinah_v2.json", "layout_tajweed_v4.json")) {
      val pages = QulPageLayoutLoader.parseLayoutJson(locateQuranAsset(assetName).readText())
      for (pageNum in listOf(1, 2, 597, 598, 599, 604)) {
        val qulLines = pages[pageNum]!!
        val ayahLines = qulLines.filter { it.type == "ayah" }
        val firstId = ayahLines.minOf { it.first!! }
        val lastId = ayahLines.maxOf { it.last!! }

        // Split words between simulated page N-1 response and page N response (with non-matching API IDs to exercise sequential mapping)
        val allSimulatedWords = (firstId..lastId).mapIndexed { idx, qulIdx ->
          WordDto(
            id = 100_000 + idx * 7, // intentionally non-sequential API DB ID
            position = idx + 1,
            char_type_name = if (qulIdx == lastId) "end" else "word",
            page_number = pageNum,
            line_number = 1, // ignored when QUL layout is authoritative
            code_v2 = "g$qulIdx",
            text_uthmani = "w$qulIdx",
          )
        }
        val splitPoint = (allSimulatedWords.size / 4).coerceAtLeast(1)
        val prevPageResp = VersesByPageResponse(
          verses = listOf(
            VerseDto(
              id = 6000,
              verse_key = "94:3",
              page_number = (pageNum - 1).coerceAtLeast(1),
              juz_number = 30,
              hizb_number = 60,
              words = allSimulatedWords.take(splitPoint),
            ),
          ),
        )
        val currPageResp = VersesByPageResponse(
          verses = listOf(
            VerseDto(
              id = 6001,
              verse_key = "95:1",
              page_number = pageNum,
              juz_number = 30,
              hizb_number = 60,
              words = allSimulatedWords.drop(splitPoint),
            ),
          ),
        )

        val built = MushafPageBuilder.buildPageFromQulLayout(
          pageNumber = pageNum,
          qulLines = qulLines,
          versesResponses = listOf(prevPageResp, currPageResp),
          chaptersById = surahs,
          basmalahTokens = basmalahTokens,
        )

        assertEquals(qulLines.size, built.lines.size)
        for (i in qulLines.indices) {
          val qLine = qulLines[i]
          val mLine = built.lines[i]
          when (qLine.type) {
            "surah_name" -> assertTrue(mLine is MushafLine.SurahHeaderLine)
            "basmallah" -> assertTrue(mLine is MushafLine.BasmalahLine)
            "ayah" -> {
              assertTrue("Expected AyahWordsLine at $assetName p$pageNum L${qLine.line}", mLine is MushafLine.AyahWordsLine)
              val ayahLine = mLine as MushafLine.AyahWordsLine
              assertEquals(qLine.centered, ayahLine.isCentered)
              assertEquals("g${qLine.first}", ayahLine.words.first().codeV2)
              assertEquals("g${qLine.last}", ayahLine.words.last().codeV2)
            }
          }
        }
      }
    }

    // Verify centered=1 vs centered=0 propagation on page 604
    val p604V2 = QulPageLayoutLoader.parseLayoutJson(locateQuranAsset("layout_madinah_v2.json").readText())[604]!!
    assertTrue(p604V2.first { it.line == 4 }.centered)
    assertFalse(p604V2.first { it.line == 3 }.centered)
  }

  @Test
  fun `buildPageFromQulLayout emits EmptyLine when page words are missing instead of guessing`() {
    val qulLines = listOf(
      QulLayoutLine(line = 1, type = "surah_name", centered = true, surah = 112),
      QulLayoutLine(line = 2, type = "ayah", centered = false, first = 1, last = 5),
    )
    val surahs = mapOf(112 to SurahMeta(112, "الإخلاص", 22, 4, true, 604, 604, true))
    val emptyResp = VersesByPageResponse(verses = emptyList())

    val built = MushafPageBuilder.buildPageFromQulLayout(
      pageNumber = 604,
      qulLines = qulLines,
      versesResponses = listOf(emptyResp),
      chaptersById = surahs,
      basmalahTokens = emptyList(),
    )

    assertEquals(2, built.lines.size)
    assertTrue(built.lines[0] is MushafLine.SurahHeaderLine)
    assertTrue(built.lines[1] is MushafLine.EmptyLine)
  }

  @Test
  fun `buildPage legacy fallback preserves 15 lines and surah header plus basmalah slots`() {
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
