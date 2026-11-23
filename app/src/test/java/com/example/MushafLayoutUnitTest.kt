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

    val surahFontCandidates = listOf(
      File("src/main/res/font/surah_name_v4.ttf"),
      File("app/src/main/res/font/surah_name_v4.ttf"),
    )
    val surahFontFile = surahFontCandidates.firstOrNull { it.exists() }
      ?: error("Missing res/font/surah_name_v4.ttf")
    val expectedSurahFontSha = "026cfe8ac461531a7b1c8e4edd05ce3343f09e9c73447ff14c6bc93f3193d661"
    val actualSurahFontSha = Sha256Integrity.sha256Hex(surahFontFile)
    assertEquals(expectedSurahFontSha, actualSurahFontSha)
    assertEquals(expectedSurahFontSha, manifestMap["res/font/surah_name_v4.ttf"])

    val commonFontCandidates = listOf(
      File("src/main/res/font/quran_common.ttf"),
      File("app/src/main/res/font/quran_common.ttf"),
    )
    val commonFontFile = commonFontCandidates.firstOrNull { it.exists() }
      ?: error("Missing res/font/quran_common.ttf")
    val expectedCommonFontSha = "b2e2730fab97d87f87b95d66ad42ecfc03b94284ad598ac3fd1c670d9354dda2"
    val actualCommonFontSha = Sha256Integrity.sha256Hex(commonFontFile)
    assertEquals(expectedCommonFontSha, actualCommonFontSha)
    assertEquals(expectedCommonFontSha, manifestMap["res/font/quran_common.ttf"])
  }

  @Test
  fun `surah name ligature formatter maps surah 1 to 114 and credits registers QUL fonts`() {
    assertEquals(
      "surah-icon",
      com.example.ui.components.SurahHeaderOrnamentRenderer.SURAH_ICON_LIGATURE,
    )
    assertEquals(
      "surah-icon" to "surah001",
      com.example.ui.components.SurahHeaderOrnamentRenderer.formatSurahHeaderTokens(1),
    )
    assertEquals(
      "surah-icon" to "surah017",
      com.example.ui.components.SurahHeaderOrnamentRenderer.formatSurahHeaderTokens(17),
    )
    assertEquals(
      "surah-icon" to "surah114",
      com.example.ui.components.SurahHeaderOrnamentRenderer.formatSurahHeaderTokens(114),
    )
    assertEquals(
      "سورة الفاتحة",
      com.example.ui.components.SurahHeaderOrnamentRenderer.formatFallbackSurahTitle("سورة الفاتحة"),
    )
    assertEquals(
      "سورة الإخلاص",
      com.example.ui.components.SurahHeaderOrnamentRenderer.formatFallbackSurahTitle("الإخلاص"),
    )
    assertEquals(
      8240f / 1016f,
      com.example.ui.components.SurahHeaderOrnamentRenderer.HEADER_GLYPH_ASPECT_RATIO,
      0.0001f,
    )
    val creditsText = locateQuranAsset("credits.json").readText()
    assertTrue(creditsText.contains("QUL V4 Surah Name Font"))
    assertTrue(creditsText.contains("qul_surah_name_v4"))
    assertTrue(creditsText.contains("QUL quran-common font"))
    assertTrue(creditsText.contains("qul_quran_common"))
  }

  @Test
  fun `all 114 surah ligatures plus surah-icon fit within 65 percent of white cartouche panel`() {
    val fontFile = listOf(
      File("src/main/res/font/surah_name_v4.ttf"),
      File("app/src/main/res/font/surah_name_v4.ttf"),
    ).firstOrNull { it.exists() } ?: error("Missing res/font/surah_name_v4.ttf")

    val bytes = fontFile.readBytes()
    val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
    val numTables = buf.getShort(4).toInt() and 0xFFFF
    val tables = HashMap<String, Pair<Int, Int>>()
    for (i in 0 until numTables) {
      val off = 12 + i * 16
      val tag = String(bytes, off, 4, Charsets.ISO_8859_1)
      tables[tag] = buf.getInt(off + 8) to buf.getInt(off + 12)
    }

    fun u16(o: Int): Int = buf.getShort(o).toInt() and 0xFFFF
    fun s16(o: Int): Int = buf.getShort(o).toInt()
    fun u32(o: Int): Int = buf.getInt(o)

    val cmapOff = tables.getValue("cmap").first
    val numSub = u16(cmapOff + 2)
    val gidToChar = HashMap<Int, Char>()
    for (i in 0 until numSub) {
      val subOff = cmapOff + u32(cmapOff + 4 + i * 8 + 4)
      if (u16(subOff) == 4) {
        val segCount = u16(subOff + 6) / 2
        val endBase = subOff + 14
        val startBase = endBase + segCount * 2 + 2
        val deltaBase = startBase + segCount * 2
        val rangeBase = deltaBase + segCount * 2
        for (j in 0 until segCount) {
          val e = u16(endBase + j * 2)
          if (e == 0xFFFF) continue
          val s = u16(startBase + j * 2)
          val d = s16(deltaBase + j * 2)
          val ro = u16(rangeBase + j * 2)
          for (cp in s..e) {
            val gid = if (ro == 0) {
              (cp + d) and 0xFFFF
            } else {
              val raw = u16(rangeBase + j * 2 + ro + (cp - s) * 2)
              if (raw == 0) 0 else (raw + d) and 0xFFFF
            }
            if (cp in 32..126 && gid != 0) gidToChar[gid] = cp.toChar()
          }
        }
        break
      }
    }

    fun parseCoverage(covOff: Int): List<Int> {
      val fmt = u16(covOff)
      val out = mutableListOf<Int>()
      if (fmt == 1) {
        val cnt = u16(covOff + 2)
        for (k in 0 until cnt) out += u16(covOff + 4 + k * 2)
      } else if (fmt == 2) {
        val cnt = u16(covOff + 2)
        for (k in 0 until cnt) {
          val s = u16(covOff + 4 + k * 6)
          val e = u16(covOff + 6 + k * 6)
          for (g in s..e) out += g
        }
      }
      return out
    }

    val gsubOff = tables.getValue("GSUB").first
    val lookupListOff = gsubOff + u16(gsubOff + 8)
    val numLookups = u16(lookupListOff)
    val ligatures = HashMap<String, Int>()
    for (li in 0 until numLookups) {
      val lOff = lookupListOff + u16(lookupListOff + 2 + li * 2)
      if (u16(lOff) != 4) continue
      val subCnt = u16(lOff + 4)
      for (si in 0 until subCnt) {
        val stOff = lOff + u16(lOff + 6 + si * 2)
        val firstGids = parseCoverage(stOff + u16(stOff + 2))
        val lsCnt = u16(stOff + 4)
        for (idx in 0 until lsCnt) {
          val lsOff = stOff + u16(stOff + 6 + idx * 2)
          val ligCnt = u16(lsOff)
          for (gi in 0 until ligCnt) {
            val ligOff = lsOff + u16(lsOff + 2 + gi * 2)
            val lgid = u16(ligOff)
            val compCnt = u16(ligOff + 2)
            val sb = StringBuilder()
            sb.append(gidToChar[firstGids[idx]] ?: '?')
            for (c in 0 until compCnt - 1) {
              sb.append(gidToChar[u16(ligOff + 4 + c * 2)] ?: '?')
            }
            ligatures[sb.toString()] = lgid
          }
        }
      }
    }

    val headOff = tables.getValue("head").first
    val upem = u16(headOff + 18).toFloat()
    val hmtxOff = tables.getValue("hmtx").first

    val iconGid = ligatures[com.example.ui.components.SurahHeaderOrnamentRenderer.SURAH_ICON_LIGATURE]
      ?: error("Missing surah-icon ligature in surah_name_v4.ttf")
    val iconAdv = u16(hmtxOff + iconGid * 4).toFloat()

    // White cartouche proportions inside 8240x1016 'header' glyph
    val panelW = (com.example.ui.components.SurahHeaderOrnamentRenderer.CARTOUCHE_RIGHT_NX -
      com.example.ui.components.SurahHeaderOrnamentRenderer.CARTOUCHE_LEFT_NX) * 8240f
    val panelH = (com.example.ui.components.SurahHeaderOrnamentRenderer.CARTOUCHE_BOTTOM_NY -
      com.example.ui.components.SurahHeaderOrnamentRenderer.CARTOUCHE_TOP_NY) * 1016f

    val seenNameGids = HashSet<Int>()
    for (surahId in 1..114) {
      val (iconToken, nameToken) = com.example.ui.components.SurahHeaderOrnamentRenderer.formatSurahHeaderTokens(surahId)
      assertEquals("surah-icon", iconToken)
      val nameGid = ligatures[nameToken] ?: error("Missing ligature $nameToken in surah_name_v4.ttf")
      assertFalse("Name ligature $nameToken must not duplicate surah-icon GID", nameGid == iconGid)
      assertTrue("Duplicate GID for $nameToken", seenNameGids.add(nameGid))

      val nameAdv = u16(hmtxOff + nameGid * 4).toFloat()
      var fontSize = panelH * 0.55f
      var combinedW = ((iconAdv + nameAdv) / upem) * fontSize + fontSize * 0.14f
      val maxAllowedW = panelW * 0.65f
      if (combinedW > maxAllowedW) {
        fontSize *= (maxAllowedW / combinedW)
        combinedW = ((iconAdv + nameAdv) / upem) * fontSize + fontSize * 0.14f
      }
      assertTrue("Combined width overflow for surah $surahId", combinedW <= maxAllowedW + 0.01f)
      val sideMargin = (panelW - combinedW) * 0.5f
      assertTrue("Insufficient margin inside panel for surah $surahId", sideMargin >= panelW * 0.17f)
    }
    assertEquals(114, seenNameGids.size)
  }

  @Test
  fun `surah header glyph rect fits strictly inside slot bounds on pages 1 2 595 596 604 without clipping neighbors`() {
    val scenarios = listOf(
      Triple(342f, 44f, "portrait phone 15-line"),
      Triple(560f, 38f, "landscape tablet two-page 15-line"),
      Triple(620f, 46f, "landscape tablet single-page 15-line"),
      Triple(560f, 82f, "framed intro p1-p2"),
    )
    for ((slotW, slotH, label) in scenarios) {
      val slotTop = 120f
      val rect = com.example.ui.components.SurahHeaderOrnamentRenderer.computeHeaderGlyphBounds(
        left = 18f,
        top = slotTop,
        width = slotW,
        height = slotH,
        vertPadPx = 3f,
      )
      assertTrue("$label top overflow: ${rect.top} < $slotTop", rect.top >= slotTop)
      assertTrue("$label bottom overflow: ${rect.bottom} > ${slotTop + slotH}", rect.bottom <= slotTop + slotH)
      assertTrue("$label width overflow: ${rect.width} > $slotW", rect.width <= slotW + 0.01f)
      val aspect = rect.width / rect.height
      assertEquals(
        "$label distorted aspect ratio",
        com.example.ui.components.SurahHeaderOrnamentRenderer.HEADER_GLYPH_ASPECT_RATIO,
        aspect,
        0.01f,
      )
    }

    // Verify QUL surah_name line indices on p1, p2, p595, p596, p604 never clash across both editions
    for (assetName in listOf("layout_madinah_v2.json", "layout_tajweed_v4.json")) {
      val pages = QulPageLayoutLoader.parseLayoutJson(locateQuranAsset(assetName).readText())
      for (pageNum in listOf(1, 2, 595, 596, 604)) {
        val lines = pages[pageNum]!!
        val lineCount = lines.size.toFloat()
        val slotH = 1200f / lineCount
        val slotW = 700f
        lines.forEachIndexed { idx, qLine ->
          if (qLine.type == "surah_name") {
            val sTop = idx * slotH
            val rect = com.example.ui.components.SurahHeaderOrnamentRenderer.computeHeaderGlyphBounds(
              left = 20f,
              top = sTop,
              width = slotW,
              height = slotH,
              vertPadPx = 3f,
            )
            assertTrue("$assetName p$pageNum L${qLine.line} bleeds above slot", rect.top >= sTop)
            assertTrue("$assetName p$pageNum L${qLine.line} bleeds below slot", rect.bottom <= sTop + slotH)
          }
        }
      }
    }
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

  @Test
  fun `beta channel preference defaults to false`() {
    val defaultState = com.example.data.prefs.PersistedReaderState()
    assertFalse(defaultState.betaChannelEnabled)
  }

  @Test
  fun `pickHigherBuildCandidate selects higher build number between latest and beta`() {
    val latest50 = com.example.update.AppUpdateManager.ReleaseCandidate(
      remoteBuildNumber = 50,
      apkUrl = "https://example.com/latest.apk",
      fileName = "quran-app.apk",
    )
    val beta55 = com.example.update.AppUpdateManager.ReleaseCandidate(
      remoteBuildNumber = 55,
      apkUrl = "https://example.com/beta.apk",
      fileName = "quran-app.apk",
    )
    val beta48 = com.example.update.AppUpdateManager.ReleaseCandidate(
      remoteBuildNumber = 48,
      apkUrl = "https://example.com/beta48.apk",
      fileName = "quran-app.apk",
    )

    assertEquals(
      beta55,
      com.example.update.AppUpdateManager.pickHigherBuildCandidate(latest = latest50, beta = beta55),
    )
    assertEquals(
      latest50,
      com.example.update.AppUpdateManager.pickHigherBuildCandidate(latest = latest50, beta = beta48),
    )
    assertEquals(
      latest50,
      com.example.update.AppUpdateManager.pickHigherBuildCandidate(latest = latest50, beta = null),
    )
    assertEquals(
      beta55,
      com.example.update.AppUpdateManager.pickHigherBuildCandidate(latest = null, beta = beta55),
    )
    assertEquals(
      null,
      com.example.update.AppUpdateManager.pickHigherBuildCandidate(latest = null, beta = null),
    )
  }

  @Test
  fun `evaluateCandidateAgainstBuild compares candidate build number with current BuildConfig BUILD_NUMBER`() {
    val currentBuild = BuildConfig.BUILD_NUMBER
    val newer = com.example.update.AppUpdateManager.ReleaseCandidate(
      remoteBuildNumber = currentBuild + 3,
      apkUrl = "https://example.com/new.apk",
      fileName = "quran-app.apk",
    )
    val equal = com.example.update.AppUpdateManager.ReleaseCandidate(
      remoteBuildNumber = currentBuild,
      apkUrl = "https://example.com/equal.apk",
      fileName = "quran-app.apk",
    )

    val available = com.example.update.AppUpdateManager.evaluateCandidateAgainstBuild(newer, currentBuild)
    assertTrue(available is com.example.update.AppUpdateState.UpdateAvailable)
    assertEquals(
      currentBuild + 3,
      (available as com.example.update.AppUpdateState.UpdateAvailable).remoteBuildNumber,
    )

    val upToDate = com.example.update.AppUpdateManager.evaluateCandidateAgainstBuild(equal, currentBuild)
    assertEquals(com.example.update.AppUpdateState.UpToDate, upToDate)
  }

  @Test
  fun `beta channel unlocked preference defaults to false`() {
    val defaultState = com.example.data.prefs.PersistedReaderState()
    assertFalse(defaultState.betaChannelUnlocked)
    assertFalse(defaultState.betaChannelEnabled)
  }

  @Test
  fun `beta unlock requires 10 consecutive taps within less than 2 seconds and resets on timeout`() {
    var state = com.example.ui.components.BetaUnlockTapTracker.TapState()
    var t = 1_000L

    // 9 fast taps do not unlock yet
    repeat(9) {
      t += 300L
      state = com.example.ui.components.BetaUnlockTapTracker.onTap(state, t)
      assertFalse(state.unlocked)
    }
    assertEquals(9, state.count)

    // Waiting >= 2000ms resets counter back to 1 on next tap
    t += 2_000L
    state = com.example.ui.components.BetaUnlockTapTracker.onTap(state, t)
    assertFalse(state.unlocked)
    assertEquals(1, state.count)

    // 8 more fast taps (total 9 consecutive) still locked
    repeat(8) {
      t += 500L
      state = com.example.ui.components.BetaUnlockTapTracker.onTap(state, t)
      assertFalse(state.unlocked)
    }
    assertEquals(9, state.count)

    // 10th consecutive fast tap (< 2000ms) unlocks
    t += 1_999L
    state = com.example.ui.components.BetaUnlockTapTracker.onTap(state, t)
    assertTrue(state.unlocked)
  }

  @Test
  fun `app update manager builds per-build apk name and detects valid zip header and cached apk`() {
    val fileName = com.example.update.AppUpdateManager.buildTargetApkFileName(
      remoteBuildNumber = 42,
      originalFileName = "qabasat release.apk",
    )
    assertEquals("build-42-qabasat_release.apk", fileName)

    val tempDir = createTempDir(prefix = "qabasat_update_test_")
    try {
      val validZipFile = File(tempDir, fileName).apply {
        writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00))
      }
      val corruptFile = File(tempDir, "corrupt.apk").apply {
        writeBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))
      }
      val missingFile = File(tempDir, "missing.apk")

      assertTrue(com.example.update.AppUpdateManager.hasZipMagicHeader(validZipFile))
      assertFalse(com.example.update.AppUpdateManager.hasZipMagicHeader(corruptFile))
      assertFalse(com.example.update.AppUpdateManager.hasZipMagicHeader(missingFile))

      val reused = com.example.update.AppUpdateManager.findCachedValidApk(
        targetFile = validZipFile,
        apkValidator = { f -> com.example.update.AppUpdateManager.hasZipMagicHeader(f) },
      )
      assertEquals(validZipFile, reused)

      val rejected = com.example.update.AppUpdateManager.findCachedValidApk(
        targetFile = corruptFile,
        apkValidator = { f -> com.example.update.AppUpdateManager.hasZipMagicHeader(f) },
      )
      assertEquals(null, rejected)
    } finally {
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `shouldResumePendingInstall resumes only when awaiting permission on newer existing build`() {
    assertTrue(
      com.example.update.AppUpdateManager.shouldResumePendingInstall(
        awaitingPermission = true,
        pendingBuild = 25,
        currentBuild = 24,
        fileExists = true,
      ),
    )
    assertFalse(
      com.example.update.AppUpdateManager.shouldResumePendingInstall(
        awaitingPermission = false,
        pendingBuild = 25,
        currentBuild = 24,
        fileExists = true,
      ),
    )
    assertFalse(
      com.example.update.AppUpdateManager.shouldResumePendingInstall(
        awaitingPermission = true,
        pendingBuild = 24,
        currentBuild = 24,
        fileExists = true,
      ),
    )
    assertFalse(
      com.example.update.AppUpdateManager.shouldResumePendingInstall(
        awaitingPermission = true,
        pendingBuild = 25,
        currentBuild = 24,
        fileExists = false,
      ),
    )
  }

  @Test
  fun `pdf manifest parser reads all fields and sorts strictly by order ascending`() {
    val manifestJson = """
      [
        {
          "id": "warsh_azraq",
          "order": 3,
          "file": "pdfs/warsh.pdf",
          "thumb": "thumbs/warsh.jpg",
          "titleAr": "مصحف ورش عن نافع",
          "riwayaAr": "ورش عن نافع",
          "pages": 604,
          "sizeMB": 94.5,
          "apiRiwayaIds": [2, 10]
        },
        {
          "id": "hafs_madinah",
          "order": 1,
          "file": "/pdfs/hafs.pdf",
          "thumb": "/thumbs/hafs.jpg",
          "titleAr": "مصحف المدينة",
          "riwayaAr": "حفص عن عاصم",
          "pages": 604,
          "sizeMB": 80.2,
          "apiRiwayaIds": [1]
        },
        {
          "id": "khalaf_hamza",
          "order": 2,
          "file": "pdfs/khalaf.pdf",
          "thumb": "thumbs/khalaf.jpg",
          "titleAr": "خلف عن حمزة",
          "riwayaAr": "خلف عن حمزة",
          "pages": 604,
          "sizeMB": 88.0,
          "apiRiwayaIds": []
        }
      ]
    """.trimIndent()

    val entries = com.example.data.pdf.PdfManifestParser.parseManifestJson(manifestJson)
    assertEquals(3, entries.size)
    assertEquals(listOf("hafs_madinah", "khalaf_hamza", "warsh_azraq"), entries.map { it.id })
    assertEquals(listOf(1, 2, 3), entries.map { it.order })
    assertEquals("pdfs/hafs.pdf", entries[0].file)
    assertEquals("thumbs/hafs.jpg", entries[0].thumb)
    assertEquals(emptyList<Int>(), entries[1].apiRiwayaIds)
    assertEquals(listOf(2, 10), entries[2].apiRiwayaIds)
    assertEquals(94.5, entries[2].sizeMB, 0.001)
  }

  @Test
  fun `mp3quran riwayat client parser groups reciters by riwaya order filters ayah timing and builds urls`() {
    val riwayatJson = """
      {
        "riwayat": [
          {"id": 1, "name": "حفص عن عاصم"},
          {"id": 2, "name": "ورش عن نافع"}
        ]
      }
    """.trimIndent()
    val riwayat = com.example.data.audio.Mp3QuranRiwayatParser.parseRiwayatJson(riwayatJson)
    assertEquals(2, riwayat.size)
    assertEquals(1, riwayat[0].id)
    assertEquals("ورش عن نافع", riwayat[1].name)

    val readsJson = """
      [
        {"id": 101, "name": "قارئ بتوقيت", "rewaya": "ورش"},
        {"id": 0, "name": "غير صالح"}
      ]
    """.trimIndent()
    val timingReads = com.example.data.audio.Mp3QuranRiwayatParser.parseTimingReadsJson(readsJson)
    assertEquals(1, timingReads.size)
    assertEquals(101, timingReads[0].id)

    val rawRecitersR2 = """
      {
        "reciters": [
          {
            "id": 20,
            "name": "قارئ ورش الأول",
            "moshaf": [
              {
                "id": 101,
                "name": "ورش - المرتل",
                "server": "http://server6.mp3quran.net/warsh/",
                "surah_list": "1,2,114"
              }
            ]
          },
          {
            "id": 21,
            "name": "قارئ ورش الثاني",
            "moshaf": [
              {
                "id": 202,
                "name": "ورش - بدون توقيت",
                "server": "https://server7.mp3quran.net/w2",
                "surah_list": "1,18"
              }
            ]
          }
        ]
      }
    """.trimIndent()
    val rawRecitersR10 = """
      {
        "reciters": [
          {
            "id": 30,
            "name": "قارئ الأزرق",
            "moshaf": [
              {
                "id": 303,
                "name": "طريق الأزرق",
                "server": "https://server9.mp3quran.net/azr/",
                "surah_list": "1,2"
              }
            ]
          }
        ]
      }
    """.trimIndent()

    val parsed2 = com.example.data.audio.Mp3QuranRiwayatParser.parseRecitersForRiwayaJson(rawRecitersR2, 2, "ورش عن نافع")
    val parsed10 = com.example.data.audio.Mp3QuranRiwayatParser.parseRecitersForRiwayaJson(rawRecitersR10, 10, "ورش من طريق الأزرق")
    val grouped = com.example.data.audio.Mp3QuranRiwayatParser.buildGroupedVerifiedReciters(
      orderedRiwayaIds = listOf(10, 2),
      entriesByRiwayaId = mapOf(2 to parsed2, 10 to parsed10),
      timingReadIds = timingReads.map { it.id }.toSet(),
    )

    assertEquals(3, grouped.size)
    // Group order respects apiRiwayaIds = [10, 2] and preserves API order inside group
    assertEquals(listOf(303, 101, 202), grouped.map { it.moshafId })
    assertFalse(grouped[0].hasAyahTiming)
    assertTrue(grouped[1].hasAyahTiming)
    assertFalse(grouped[2].hasAyahTiming)
    assertTrue(grouped[0].isPdfModeReciter)
    assertEquals(
      "https://server6.mp3quran.net/warsh/001.mp3",
      com.example.data.audio.Mp3QuranRiwayatParser.buildSurahAudioUrl(grouped[1].serverUrl.orEmpty(), 1),
    )
    assertEquals(
      "https://server7.mp3quran.net/w2/018.mp3",
      com.example.data.audio.Mp3QuranRiwayatParser.buildSurahAudioUrl(grouped[2].serverUrl.orEmpty(), 18),
    )

    // Ayah timing ignores ayah <= 0 (basmalah/0 markers) and null times
    val timingJson = """
      [
        {"ayah": 0, "start_time": 0, "end_time": 2100},
        {"ayah": 1, "start_time": 2100, "end_time": 6300},
        {"ayah": 2, "start_time": null, "end_time": 9000},
        {"ayah": 3, "start_time": 9000, "end_time": 13500}
      ]
    """.trimIndent()
    val timings = com.example.data.audio.Mp3QuranRiwayatParser.parseAyahTimingsJson(timingJson)
    assertEquals(2, timings.size)
    assertEquals(1, timings[0].ayah)
    assertEquals(3, timings[1].ayah)
  }

  @Test
  fun `colors guide parser reads filled and ring styles verbatim without merging`() {
    val json = """
      {
        "version": 1,
        "riwayat": [
          {
            "id": 7,
            "riwayaAr": "ورش عن نافع",
            "items": [
              { "label": "حرف مد لازم", "color": "#1A2B3C", "style": "filled" },
              { "label": "إمالة كبرى", "color": "#FF5500", "style": "ring" },
              { "label": "صلة ميم الجمع", "color": "#008844", "style": "RING" }
            ]
          },
          {
            "id": 3,
            "riwayaAr": "قالون عن نافع",
            "items": [
              { "label": "مد منفصل", "color": "#1A2B3C", "style": "filled" },
              { "label": "غنة", "color": "#169200", "style": "filled" }
            ]
          }
        ]
      }
    """.trimIndent()

    val parsed = com.example.data.pdf.ColorsGuideParser.parseColorsGuideJson(json)
    assertEquals(1, parsed.version)
    assertEquals(2, parsed.riwayat.size)

    val warsh = parsed.riwayat[0]
    assertEquals("7", warsh.id)
    assertEquals("ورش عن نافع", warsh.riwayaAr)
    assertEquals(3, warsh.items.size)
    assertEquals("حرف مد لازم", warsh.items[0].label)
    assertEquals("#1A2B3C", warsh.items[0].colorHex)
    assertEquals(com.example.data.pdf.ColorSwatchStyle.FILLED, warsh.items[0].style)
    assertEquals("إمالة كبرى", warsh.items[1].label)
    assertEquals("#FF5500", warsh.items[1].colorHex)
    assertEquals(com.example.data.pdf.ColorSwatchStyle.RING, warsh.items[1].style)
    assertEquals(com.example.data.pdf.ColorSwatchStyle.RING, warsh.items[2].style)

    val qaloon = parsed.riwayat[1]
    assertEquals("3", qaloon.id)
    assertEquals("قالون عن نافع", qaloon.riwayaAr)
    assertEquals(2, qaloon.items.size)
    // Same hex color in another riwaya keeps its own riwaya-specific label verbatim
    assertEquals("مد منفصل", qaloon.items[0].label)
    assertEquals("#1A2B3C", qaloon.items[0].colorHex)
  }

  @Test
  fun `colors guide sections order by manifest order matching riwayat id with manifest entry id`() {
    val guideJson = """
      {
        "version": 1,
        "riwayat": [
          { "id": 10, "riwayaAr": "رواية عاشرة", "items": [{ "label": "بند ١", "color": "#111111", "style": "filled" }] },
          { "id": 2, "riwayaAr": "رواية ثانية", "items": [{ "label": "بند ٢", "color": "#222222", "style": "ring" }] },
          { "id": 5, "riwayaAr": "رواية خامسة", "items": [{ "label": "بند ٣", "color": "#333333", "style": "filled" }] },
          { "id": 99, "riwayaAr": "غير مدرجة", "items": [{ "label": "بند ٤", "color": "#444444", "style": "filled" }] }
        ]
      }
    """.trimIndent()
    val manifestJson = """
      [
        { "id": 5, "order": 1, "file": "pdfs/5.pdf", "thumb": "thumbs/5.jpg", "titleAr": "5", "riwayaAr": "رواية خامسة", "pages": 604, "sizeMB": 50.0, "apiRiwayaIds": [] },
        { "id": 10, "order": 3, "file": "pdfs/10.pdf", "thumb": "thumbs/10.jpg", "titleAr": "10", "riwayaAr": "رواية عاشرة", "pages": 604, "sizeMB": 50.0, "apiRiwayaIds": [] },
        { "id": 2, "order": 2, "file": "pdfs/2.pdf", "thumb": "thumbs/2.jpg", "titleAr": "2", "riwayaAr": "رواية ثانية", "pages": 604, "sizeMB": 50.0, "apiRiwayaIds": [] }
      ]
    """.trimIndent()

    val guide = com.example.data.pdf.ColorsGuideParser.parseColorsGuideJson(guideJson)
    val manifest = com.example.data.pdf.PdfManifestParser.parseManifestJson(manifestJson)
    val ordered = com.example.data.pdf.ColorsGuideParser.orderSectionsByManifest(guide.riwayat, manifest)

    assertEquals(listOf("5", "2", "10", "99"), ordered.map { it.id })
    assertEquals(com.example.data.pdf.ColorSwatchStyle.RING, ordered[1].items[0].style)
  }

  @Test
  fun `pdf manifest parser ignores extra cropLeftPt and cropRightPt fields`() {
    val json = """
      [
        {
          "id": "with_crop",
          "order": 1,
          "file": "pdfs/1.pdf",
          "thumb": "thumbs/1.jpg",
          "titleAr": "مصحف ١",
          "riwayaAr": "حفص",
          "pages": 604,
          "sizeMB": 85.0,
          "cropLeftPt": 14.5,
          "cropRightPt": 456.0
        }
      ]
    """.trimIndent()

    val entries = com.example.data.pdf.PdfManifestParser.parseManifestJson(json)
    assertEquals(1, entries.size)
    assertEquals("with_crop", entries[0].id)
    assertEquals(85.0, entries[0].sizeMB, 0.001)
  }

  @Test
  fun `pdf renderer computeCropScalePlan renders at 2x screen width capped at 2400 and crops 595x842 by exact fractions`() {
    // 1) Cropped 472x688 for 1000px screen body -> 2000px wide
    val plan2x = com.example.data.pdf.PdfRendererBridge.computeCropScalePlan(
      pageWidthPt = 472,
      pageHeightPt = 688,
      onScreenWidthPx = 1000,
    )
    assertFalse(plan2x.isUncroppedA4)
    assertEquals(2000, plan2x.croppedWidthPx)
    assertEquals(2000, plan2x.fullRenderWidthPx)
    assertTrue(plan2x.croppedHeightPx > 2900)

    // 2) Tablet width 1400px -> 2x would be 2800px, capped at 2400px
    val planCapped = com.example.data.pdf.PdfRendererBridge.computeCropScalePlan(
      pageWidthPt = 472,
      pageHeightPt = 688,
      onScreenWidthPx = 1400,
    )
    assertEquals(2400, planCapped.croppedWidthPx)

    // 3) Uncropped 595x842 crops to [62..534, 80..768] fractions while keeping cropped width >= 2x screen width
    val uncroppedPlan = com.example.data.pdf.PdfRendererBridge.computeCropScalePlan(
      pageWidthPt = 595,
      pageHeightPt = 842,
      onScreenWidthPx = 900,
    )
    assertTrue(uncroppedPlan.isUncroppedA4)
    assertEquals(1800, uncroppedPlan.croppedWidthPx)
    assertTrue(uncroppedPlan.fullRenderWidthPx > 2200)
    assertEquals(
      com.example.data.pdf.PdfRendererBridge.CROPPED_PAGE_WIDTH_PT /
        com.example.data.pdf.PdfRendererBridge.CROPPED_PAGE_HEIGHT_PT,
      uncroppedPlan.croppedWidthPx.toFloat() / uncroppedPlan.croppedHeightPx.toFloat(),
      0.01f,
    )
  }

  @Test
  fun `pdf segmentation finds bands and avoids cutting ink`() {
    val bmpW = 200
    val bmpH = 688
    val darkPx = 0xFF202020.toInt()

    val segmentation = com.example.data.pdf.PdfRendererBridge.computePageSegmentation(
      width = bmpW,
      height = bmpH,
    ) { y, buf ->
      java.util.Arrays.fill(buf, 0xFFFFFFFF.toInt())
      // Top label band ink at y=10..18
      if (y in 10..18) {
        for (x in 20..180) buf[x] = darkPx
      }
      // Bottom legend band ink at y=650..675
      if (y in 650..675) {
        for (x in 20..180) buf[x] = darkPx
      }
      // Middle text ink at y=100..500
      if (y in 100..500) {
        for (x in 30..170) buf[x] = darkPx
      }
    }

    assertTrue(segmentation.hasLabelBand)
    assertTrue(segmentation.hasLegendBand)
    assertTrue(segmentation.labelCutFraction > 0f)
    assertTrue(segmentation.legendCutFraction < 1f)
  }

  @Test
  fun `computePdfPageLayout pins bands and reserves surah banner with segmentation`() {
    val bmpW = 2000
    val bmpH = 2915

    val segmentation = com.example.data.pdf.PdfPageSegmentation(
      hasLabelBand = true,
      labelCutFraction = 0.05f,
      hasLegendBand = true,
      legendCutFraction = 0.92f,
      textMinXFraction = 0.1f,
      textMaxXFraction = 0.9f,
      textMinYFraction = 0.08f,
      textMaxYFraction = 0.90f,
      widestLineWidthFraction = 0.8f,
      isFewLines = false,
      lines = emptyList(),
    )

    val layout = com.example.ui.components.computePdfPageLayout(
      frameLeft = 20f,
      frameTop = 40f,
      frameRight = 1020f,
      frameBottom = 1840f,
      bmpWidthPx = bmpW,
      bmpHeightPx = bmpH,
      segmentation = segmentation,
      hasTopSurahBanner = true,
      densityPxPerDp = 3f,
    )

    assertEquals(20, layout.topBandDstLeft)
    assertEquals(40, layout.topBandDstTop)
    assertEquals(1000, layout.topBandDstWidth)
    assertEquals(1840, layout.bottomBandDstTop + layout.bottomBandDstHeight)
    assertTrue(layout.bannerHeight > 0f)
    assertTrue(layout.bannerTop >= layout.topBandDstTop + layout.topBandDstHeight)
  }

  @Test
  fun `pdf manifest stale file size detector flags differences over 5 percent`() {
    val oneHundredMbBytes = (100.0 * 1024.0 * 1024.0).toLong()
    assertFalse(com.example.data.pdf.PdfManifestParser.isStaleFileSize(oneHundredMbBytes, 100.0))
    assertFalse(
      com.example.data.pdf.PdfManifestParser.isStaleFileSize((oneHundredMbBytes * 0.96).toLong(), 100.0),
    )
    assertFalse(
      com.example.data.pdf.PdfManifestParser.isStaleFileSize((oneHundredMbBytes * 1.04).toLong(), 100.0),
    )
    assertTrue(
      com.example.data.pdf.PdfManifestParser.isStaleFileSize((oneHundredMbBytes * 0.94).toLong(), 100.0),
    )
    assertTrue(
      com.example.data.pdf.PdfManifestParser.isStaleFileSize((oneHundredMbBytes * 1.06).toLong(), 100.0),
    )
    assertTrue(com.example.data.pdf.PdfManifestParser.isStaleFileSize(0L, 100.0))
  }

  @Test
  fun `pdf page cache key distinguishes file pageIndex and widthPx`() {
    val k1 = com.example.data.pdf.PdfPageCacheKey(
      filePath = "/data/warsh.pdf",
      fileLength = 1000L,
      lastModified = 10L,
      pageIndex = 4,
      widthPx = 1000,
    )
    assertFalse(k1 == k1.copy(fileLength = 2000L))
    assertFalse(k1 == k1.copy(pageIndex = 5))
    assertFalse(k1 == k1.copy(widthPx = 600))
  }

  private fun applyColorMatrix(matrixValues: FloatArray, r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
    val rf = r.toFloat()
    val gf = g.toFloat()
    val bf = b.toFloat()
    val outR = (matrixValues[0] * rf + matrixValues[1] * gf + matrixValues[2] * bf + matrixValues[4]).toInt().coerceIn(0, 255)
    val outG = (matrixValues[5] * rf + matrixValues[6] * gf + matrixValues[7] * bf + matrixValues[9]).toInt().coerceIn(0, 255)
    val outB = (matrixValues[10] * rf + matrixValues[11] * gf + matrixValues[12] * bf + matrixValues[14]).toInt().coerceIn(0, 255)
    return Triple(outR, outG, outB)
  }

  @Test
  fun `pdf dark mode color matrix maps white to black black to white and preserves tajweed hues`() {
    val m = com.example.ui.components.createPdfDarkModeColorMatrix().values

    // White (#FFFFFF) -> Black (#000000), matching Madani dark background
    val (wr, wg, wb) = applyColorMatrix(m, 255, 255, 255)
    assertEquals(0, wr)
    assertEquals(0, wg)
    assertEquals(0, wb)

    // Black (#000000) -> White (#FFFFFF)
    val (kr, kg, kb) = applyColorMatrix(m, 0, 0, 0)
    assertEquals(255, kr)
    assertEquals(255, kg)
    assertEquals(255, kb)

    // Tajweed red stays predominantly red
    val (rr, rg, rb) = applyColorMatrix(m, 200, 30, 30)
    assertTrue("Expected red channel dominant, got ($rr, $rg, $rb)", rr > rg + 50 && rr > rb + 50)

    // Tajweed blue stays predominantly blue
    val (br, bg, bb) = applyColorMatrix(m, 20, 90, 210)
    assertTrue("Expected blue channel dominant, got ($br, $bg, $bb)", bb > bg + 40 && bb > br + 80)

    // Tajweed green stays predominantly green
    val (gr, gg, gb) = applyColorMatrix(m, 20, 150, 50)
    assertTrue("Expected green channel dominant, got ($gr, $gg, $gb)", gg > gr + 40 && gg > gb + 40)

    // Tajweed orange keeps warm R > G > B ordering
    val (or, og, ob) = applyColorMatrix(m, 230, 120, 20)
    assertTrue("Expected orange ordering R > G > B, got ($or, $og, $ob)", or > og && og > ob)
  }

  @Test
  fun `quran word search normalizer strips diacritics marks tatweel and wasla only`() {
    val norm = com.example.data.quran.QuranWordSearchNormalizer
    // Alef wasla ٱ maps to plain alef ا; harakat and shadda stripped
    assertEquals("الله", norm.normalizeKey("ٱللَّهِ"))
    // Dagger alef ٰ, tatweel ـ, maddah ٓ stripped
    assertEquals("الرحمن", norm.normalizeKey("ٱلرَّحْمَـٰنِ"))
    assertEquals("أيها", norm.normalizeKey("أَيُّهَآ"))
    // Strict letter distinction: never merge hamza forms, alef variants, yaa/alef maqsura, or taa marbuta/haa
    assertFalse(norm.normalizeKey("إن") == norm.normalizeKey("أن"))
    assertFalse(norm.normalizeKey("إن") == norm.normalizeKey("ان"))
    assertFalse(norm.normalizeKey("آمن") == norm.normalizeKey("أمن"))
    assertFalse(norm.normalizeKey("على") == norm.normalizeKey("علي"))
    assertFalse(norm.normalizeKey("جنة") == norm.normalizeKey("جنه"))
    assertFalse(norm.normalizeKey("مؤمن") == norm.normalizeKey("مئمن"))
    assertFalse(norm.normalizeKey("يا") == norm.normalizeKey("يَـٰٓأَيُّهَا"))
  }

  @Test
  fun `quran word search index handles exact whole word substring ordering and original highlights`() {
    val verses = listOf(
      VerseDto(
        id = 1,
        verse_key = "112:1",
        page_number = 604,
        juz_number = 30,
        hizb_number = 60,
        text_uthmani = "قُلْ هُوَ ٱللَّهُ أَحَدٌ",
      ),
      VerseDto(
        id = 2,
        verse_key = "1:1",
        page_number = 1,
        juz_number = 1,
        hizb_number = 1,
        text_uthmani = "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ",
      ),
      VerseDto(
        id = 3,
        verse_key = "2:153",
        page_number = 23,
        juz_number = 2,
        hizb_number = 3,
        text_uthmani = "يَـٰٓأَيُّهَا ٱلَّذِينَ ءَامَنُوا۟ ۞ إِنَّ ٱللَّهَ مَعَ ٱلصَّـٰبِرِينَ",
      ),
      VerseDto(
        id = 4,
        verse_key = "2:6",
        page_number = 2,
        juz_number = 1,
        hizb_number = 1,
        text_uthmani = "إِنَّ ٱلَّذِينَ كَفَرُوا۟ وَٱللَّهِ أَن لَّا يُؤْمِنُونَ",
      ),
      VerseDto(
        id = 5,
        verse_key = "18:19",
        page_number = 295,
        juz_number = 15,
        hizb_number = 30,
        text_uthmani = "فَلْيَنظُرْ أَيُّهَآ أَزْكَىٰ طَعَامًا",
      ),
    )
    val surahsById = mapOf(
      1 to SurahMeta(1, "الفاتحة", 5, 7, false, 1, 1, true),
      2 to SurahMeta(2, "البقرة", 87, 286, true, 2, 49, false),
      18 to SurahMeta(18, "الكهف", 69, 110, true, 293, 304, true),
      112 to SurahMeta(112, "الإخلاص", 22, 4, true, 604, 604, true),
    )

    val index = com.example.data.quran.QuranWordSearchIndex.buildFromVerses(verses)

    // 1) Whole-word "الله": matches 1:1, 2:153, 112:1 (NOT وَٱللَّهِ in 2:6), sorted in mushaf order
    val allahWhole = index.search("الله", isSubstring = false, surahsById = surahsById)
    assertEquals(3, allahWhole.totalOccurrences)
    assertEquals(3, allahWhole.surahCount)
    assertEquals(listOf(1, 2, 112), allahWhole.groups.map { it.surahId })
    val hit1 = allahWhole.groups[0].hits.single()
    val highlighted1 = hit1.highlightRanges.map { hit1.originalText.substring(it.first, it.last + 1) }
    assertEquals(listOf("ٱللَّهِ"), highlighted1)

    // 2) Substring mode "الله": also finds وَٱللَّهِ in 2:6 and highlights exact substring on original text
    val allahSub = index.search("الله", isSubstring = true, surahsById = surahsById)
    assertEquals(4, allahSub.totalOccurrences)
    val baqarahHits = allahSub.groups.first { it.surahId == 2 }.hits
    assertEquals(listOf("2:6", "2:153"), baqarahHits.map { it.verseKey })
    val waAllahHit = baqarahHits[0]
    val subHighlighted = waAllahHit.highlightRanges.map {
      waAllahHit.originalText.substring(it.first, it.last + 1)
    }
    assertEquals(listOf("ٱللَّهِ"), subHighlighted)

    // 3) Strict hamza distinction: "إن" vs "أن"
    val innaRes = index.search("إن", isSubstring = false, surahsById = surahsById)
    val annaRes = index.search("أن", isSubstring = false, surahsById = surahsById)
    assertEquals(2, innaRes.totalOccurrences)
    assertEquals(1, annaRes.totalOccurrences)
    assertEquals(listOf("2:6", "2:153"), innaRes.groups.single().hits.map { it.verseKey })
    assertEquals(listOf("2:6"), annaRes.groups.single().hits.map { it.verseKey })

    // 4) "يا" must NOT match "يَـٰٓأَيُّهَا", and "أيها" whole-word matches only 18:19 (while substring matches 2:153 + 18:19)
    assertEquals(0, index.search("يا", isSubstring = false, surahsById = surahsById).totalOccurrences)
    assertEquals(0, index.search("يا", isSubstring = true, surahsById = surahsById).totalOccurrences)
    val ayyuhaWhole = index.search("أيها", isSubstring = false, surahsById = surahsById)
    assertEquals(1, ayyuhaWhole.totalOccurrences)
    assertEquals("18:19", ayyuhaWhole.groups.single().hits.single().verseKey)
    val ayyuhaSub = index.search("أيها", isSubstring = true, surahsById = surahsById)
    assertEquals(2, ayyuhaSub.totalOccurrences)
  }

  @Test
  fun `computeCropScalePlan handles cropLeftPt and cropRightPt as x-bounds without OOM on 472x688 and 595x842`() {
    val plan472 = com.example.data.pdf.PdfRendererBridge.computeCropScalePlan(
      pageWidthPt = 472,
      pageHeightPt = 688,
      onScreenWidthPx = 1080,
      cropLeftPt = 14.5f,
      cropRightPt = 456.0f,
    )
    assertTrue(plan472.needsCrop)
    assertTrue(plan472.fullRenderWidthPx <= com.example.data.pdf.PdfRendererBridge.MAX_RENDER_WIDTH_PX)
    assertTrue(plan472.fullRenderHeightPx <= com.example.data.pdf.PdfRendererBridge.MAX_RENDER_HEIGHT_PX)
    assertTrue("Expected high-res cropped width >= 1800, got ${plan472.croppedWidthPx}", plan472.croppedWidthPx >= 1800)
    assertTrue(plan472.cropLeftPx > 0)
    assertTrue(plan472.cropLeftPx + plan472.croppedWidthPx <= plan472.fullRenderWidthPx)

    val plan595 = com.example.data.pdf.PdfRendererBridge.computeCropScalePlan(
      pageWidthPt = 595,
      pageHeightPt = 842,
      onScreenWidthPx = 1080,
      cropLeftPt = 14.5f,
      cropRightPt = 456.0f,
    )
    assertTrue(plan595.isUncroppedA4)
    assertTrue(plan595.needsCrop)
    assertTrue(plan595.fullRenderWidthPx <= com.example.data.pdf.PdfRendererBridge.MAX_RENDER_WIDTH_PX)
    assertTrue(plan595.fullRenderHeightPx <= com.example.data.pdf.PdfRendererBridge.MAX_RENDER_HEIGHT_PX)
    assertTrue("Expected high-res cropped width >= 1700, got ${plan595.croppedWidthPx}", plan595.croppedWidthPx >= 1700)
  }

  @Test
  fun `computePdfPageLayout enforces 2 percent top margin top-aligned surah banner equal few-line gaps and no vertical centering`() {
    val frameLeft = 20f
    val frameTop = 40f
    val frameRight = 1020f
    val frameBottom = 1840f
    val frameH = frameBottom - frameTop // 1800f
    val bmpW = 1000
    val bmpH = 1458

    // Case 1: Few-line surah-start page (e.g., Al-Fatiha / 7 lines) with label & legend bands
    val fewLineStrips = (0 until 7).map { idx ->
      val topF = 0.25f + idx * 0.055f
      com.example.data.pdf.PdfLineStripFraction(
        srcTopFraction = topF,
        srcBottomFraction = topF + 0.045f,
        minXFraction = 0.12f,
        maxXFraction = 0.88f,
      )
    }
    val fewSeg = com.example.data.pdf.PdfPageSegmentation(
      hasLabelBand = true,
      labelCutFraction = 0.04f,
      hasLegendBand = true,
      legendCutFraction = 0.92f,
      textMinXFraction = 0.12f,
      textMaxXFraction = 0.88f,
      textMinYFraction = 0.25f,
      textMaxYFraction = 0.63f,
      widestLineWidthFraction = 0.76f,
      isFewLines = true,
      lines = fewLineStrips,
    )

    val fewLayout = com.example.ui.components.computePdfPageLayout(
      frameLeft = frameLeft,
      frameTop = frameTop,
      frameRight = frameRight,
      frameBottom = frameBottom,
      bmpWidthPx = bmpW,
      bmpHeightPx = bmpH,
      segmentation = fewSeg,
      hasTopSurahBanner = true,
      densityPxPerDp = 3f,
    )

    val labelBottom = fewLayout.topBandDstTop + fewLayout.topBandDstHeight
    val expectedTopMargin = frameH * 0.02f
    // Banner starts right under label band + ~2% margin (never floating mid-page)
    assertEquals(labelBottom + expectedTopMargin, fewLayout.bannerTop, 2f)
    assertTrue(fewLayout.isFewLines)
    assertEquals(7, fewLayout.lineLayouts.size)
    // First line sits cleanly under banner, no huge blank above; last line ends near legend top
    val firstLine = fewLayout.lineLayouts.first()
    val lastLine = fewLayout.lineLayouts.last()
    assertTrue(firstLine.dstTopPx >= (fewLayout.bannerTop + fewLayout.bannerHeight).toInt())
    assertTrue(firstLine.dstTopPx <= (fewLayout.bannerTop + fewLayout.bannerHeight + 12f).toInt())
    val lastLineBottom = lastLine.dstTopPx + lastLine.dstHeightPx
    assertTrue(
      "Last line bottom ($lastLineBottom) should absorb bottom whitespace near legend (${fewLayout.bottomBandDstTop})",
      kotlin.math.abs(fewLayout.bottomBandDstTop - lastLineBottom) <= 8,
    )
    // Equal gaps between consecutive lines
    val gaps = (0 until fewLayout.lineLayouts.size - 1).map { i ->
      val a = fewLayout.lineLayouts[i]
      val b = fewLayout.lineLayouts[i + 1]
      b.dstTopPx - (a.dstTopPx + a.dstHeightPx)
    }
    assertTrue(gaps.all { it >= 0 })
    assertTrue("Expected equal gaps, got $gaps", gaps.max() - gaps.min() <= 2)

    // Case 2: Normal/single-block page without label/legend must start ~2% from frameTop (never centered)
    val shortBlockSeg = com.example.data.pdf.PdfPageSegmentation(
      hasLabelBand = false,
      labelCutFraction = 0f,
      hasLegendBand = false,
      legendCutFraction = 1f,
      textMinXFraction = 0.1f,
      textMaxXFraction = 0.9f,
      textMinYFraction = 0.2f,
      textMaxYFraction = 0.5f,
      widestLineWidthFraction = 0.8f,
      isFewLines = false,
      lines = emptyList(),
    )
    val shortLayout = com.example.ui.components.computePdfPageLayout(
      frameLeft = frameLeft,
      frameTop = frameTop,
      frameRight = frameRight,
      frameBottom = frameBottom,
      bmpWidthPx = bmpW,
      bmpHeightPx = bmpH,
      segmentation = shortBlockSeg,
      hasTopSurahBanner = false,
      densityPxPerDp = 3f,
    )
    assertEquals((frameTop + frameH * 0.02f).toInt(), shortLayout.textDstTop)
  }

  @Test
  fun `pdf segmentation detects few-line gaps cleanly without cutting ink`() {
    val bmpW = 472
    val bmpH = 688
    val darkPx = 0xFF111111.toInt()
    val seg = com.example.data.pdf.PdfRendererBridge.computePageSegmentation(bmpW, bmpH) { y, buf ->
      java.util.Arrays.fill(buf, 0xFFFFFFFF.toInt())
      // Label band: y=8..16
      if (y in 8..16) for (x in 150..320) buf[x] = darkPx
      // 4 sparse lines separated by 30px gaps
      val lineStarts = listOf(100, 150, 200, 250)
      for (s in lineStarts) {
        if (y in s..(s + 18)) for (x in 50..420) buf[x] = darkPx
      }
      // 2-row tall legend band at y=630..675 with a 4px internal blank gap at 651..654 (must merge via <=5px threshold!)
      if (y in 630..650 || y in 655..675) for (x in 30..440) buf[x] = darkPx
    }

    assertTrue(seg.hasLabelBand)
    assertTrue(seg.hasLegendBand)
    assertTrue("Legend cut must sit in blank gap above row 630, got ${seg.legendCutFraction * bmpH}", seg.legendCutFraction * bmpH < 630f)
    assertTrue("Label cut must sit in blank gap below row 16, got ${seg.labelCutFraction * bmpH}", seg.labelCutFraction * bmpH in 17f..99f)
    assertTrue(seg.isFewLines)
    assertEquals(4, seg.lines.size)
  }

  @Test
  fun `buildRiwayaGroupsAndAssignReciters maps reciters strictly by apiRiwayaIds and puts unmapped in other`() {
    fun reciter(keyId: Int, name: String, riwayaId: Int?, riwayaName: String, isPdf: Boolean) =
      com.example.data.audio.VerifiedReciter(
        orderIndex = keyId,
        requestedName = name,
        reciterId = keyId,
        displayName = name,
        moshafId = keyId * 10,
        moshafName = riwayaName,
        serverUrl = "https://example.invalid/$keyId/",
        availableSurahs = setOf(1, 2),
        status = com.example.data.audio.ReciterMatchStatus.VERIFIED,
        statusNote = "",
        riwayaId = riwayaId,
        riwayaName = riwayaName,
        isPdfModeReciter = isPdf,
      )

    val manifest = listOf(
      com.example.data.pdf.PdfMushafEntry("warsh_id", 2, "p2.pdf", "t2.jpg", "ورش", "ورش عن نافع", 604, 80.0, listOf(2, 10)),
      com.example.data.pdf.PdfMushafEntry("qaloon_id", 1, "p1.pdf", "t1.jpg", "قالون", "قالون عن نافع", 604, 80.0, listOf(3)),
      com.example.data.pdf.PdfMushafEntry("empty_id", 3, "p3.pdf", "t3.jpg", "البزي", "البزي عن ابن كثير", 604, 80.0, listOf(99)),
    )

    val hafsR = reciter(1, "العفاسي", null, "", isPdf = false)
    // Intentionally misleading riwayaName to verify apiRiwayaIds drives assignment rather than keywords
    val warshAzraqR = reciter(2, "قارئ الأزرق", 10, "بدون وصف مطابق", isPdf = true)
    val qaloonR = reciter(3, "قارئ قالون", 3, "قالون", isPdf = true)
    val unmappedR = reciter(4, "قارئ إضافي", 777, "ورش مزيف بالاسم فقط", isPdf = true)

    val (groups, byGroup) = com.example.ui.components.buildRiwayaGroupsAndAssignReciters(
      allReciters = listOf(hafsR, warshAzraqR, qaloonR, unmappedR),
      pdfMushafs = manifest,
    )

    assertEquals(
      listOf("hafs", "pdf_qaloon_id", "pdf_warsh_id", "pdf_empty_id", "other"),
      groups.map { it.id },
    )
    assertEquals(listOf(hafsR), byGroup["hafs"])
    assertEquals(listOf(qaloonR), byGroup["pdf_qaloon_id"])
    assertEquals(listOf(warshAzraqR), byGroup["pdf_warsh_id"])
    assertTrue(byGroup["pdf_empty_id"].orEmpty().isEmpty())
    assertEquals(listOf(unmappedR), byGroup["other"])
  }
}
