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
  fun `riwayat debug repository parses real api json handles missing fields filters ignoring diacritics and formats url`() {
    val riwayatJson = """
      {
        "riwayat": [
          {"id": 1, "name": "حفص عن عاصم"},
          {"id": 2}
        ]
      }
    """.trimIndent()
    val riwayat = com.example.debug.RiwayatApiDebugRepository.parseRiwayatJson(riwayatJson)
    assertEquals(2, riwayat.size)
    assertEquals("1 - حفص عن عاصم", riwayat[0].displayLabel)
    assertEquals("غير موجود", riwayat[1].name)

    val recitersJson = """
      {
        "reciters": [
          {
            "id": 10,
            "name": "مِشَارِي رَاشِد العَفَاسِي",
            "moshaf": [
              {
                "id": 101,
                "name": "المصحف المرتل",
                "server": "https://server8.mp3quran.net/afs/",
                "surah_list": "1,2,3,4,5,6,7,8,9,10,11,12"
              }
            ]
          },
          {
            "id": 11,
            "name": "قارئ ناقص الحقول",
            "moshaf": [
              {
                "id": 102
              }
            ]
          }
        ]
      }
    """.trimIndent()
    val parsedReciters = com.example.debug.RiwayatApiDebugRepository.parseRecitersJson(
      rawJson = recitersJson,
      riwayaId = "1",
      riwayaName = "حفص عن عاصم",
    )
    assertEquals(2, parsedReciters.size)
    assertEquals(
      listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"),
      parsedReciters[0].moshafList[0].first10Surahs,
    )
    assertEquals("غير موجود", parsedReciters[1].moshafList[0].name)
    assertEquals("غير موجود", parsedReciters[1].moshafList[0].server)
    assertEquals(emptyList<String>(), parsedReciters[1].moshafList[0].first10Surahs)

    // Filter ignores Arabic diacritics
    val filtered = com.example.debug.RiwayatApiDebugRepository.filterReciters(
      all = parsedReciters,
      selectedRiwayaId = "1",
      searchQuery = "مشاري راشد",
    )
    assertEquals(1, filtered.size)
    assertEquals("10", filtered[0].reciterId)

    // Exact URL construction: server + padded 3-digit surah + .mp3
    assertEquals(
      "https://server8.mp3quran.net/afs/001.mp3",
      com.example.debug.RiwayatApiDebugRepository.buildMoshafAudioUrl(
        server = parsedReciters[0].moshafList[0].server,
        surahNumber = 1,
      ),
    )

    val clipboard = com.example.debug.RiwayatApiDebugRepository.formatFilteredResultsForClipboard(
      filtered = filtered,
      selectedRiwayaLabel = "1 - حفص عن عاصم",
    )
    assertTrue(clipboard.contains("مِشَارِي رَاشِد العَفَاسِي"))
    assertTrue(clipboard.contains("https://server8.mp3quran.net/afs/"))
    assertTrue(clipboard.contains("1,2,3,4,5,6,7,8,9,10"))
  }

  @Test
  fun `riwayat debug repository parses timing reads soar surah1 counts reciters and formats clipboard timing labels`() {
    val readsJson = """
      [
        {"id": 101, "name": "مشاري العفاسي"},
        {"id": 999, "name": "قارئ آخر", "rewaya": "ورش"}
      ]
    """.trimIndent()
    val reads = com.example.debug.RiwayatApiDebugRepository.parseTimingReadsJson(readsJson)
    assertEquals(2, reads.size)
    assertEquals("101", reads[0].id)
    assertTrue(reads[0].formatRawLine().contains("name: مشاري العفاسي"))

    val soarJson = """[{"id": 1, "name": "الفاتحة"}, {"id": 2, "name": "البقرة"}]"""
    assertEquals(2, com.example.debug.RiwayatApiDebugRepository.parseTimingSoarCount(soarJson))

    val surah1Json = """
      [
        {"ayah": 1, "start_time": 0, "end_time": 4200},
        {"ayah": 2, "start_time": 4200, "end_time": 9100},
        {"ayah": 3, "start_time": 9100},
        {"ayah": 4, "start_time": 12000, "end_time": 16000}
      ]
    """.trimIndent()
    val (totalAyahs, first3) = com.example.debug.RiwayatApiDebugRepository.parseAyahTimingsJson(surah1Json)
    assertEquals(4, totalAyahs)
    assertEquals(3, first3.size)
    assertEquals("ayah=1, start_time=0, end_time=4200", first3[0].formatLine())
    assertEquals("غير موجود", first3[2].endTime)

    val reciters = listOf(
      com.example.debug.DebugReciterEntry(
        uniqueKey = "1_10_0",
        reciterId = "10",
        reciterName = "قارئ بتوقيت",
        riwayaId = "1",
        riwayaName = "حفص",
        moshafList = listOf(
          com.example.debug.DebugMoshaf(
            id = "101",
            name = "المرتل",
            server = "https://s/",
            rawSurahList = "1,2",
            first10Surahs = listOf("1", "2"),
            firstSurahNumber = 1,
            rawFieldNames = listOf("id", "name", "server", "surah_list"),
          ),
        ),
      ),
      com.example.debug.DebugReciterEntry(
        uniqueKey = "1_11_1",
        reciterId = "11",
        reciterName = "قارئ بلا توقيت",
        riwayaId = "1",
        riwayaName = "حفص",
        moshafList = listOf(
          com.example.debug.DebugMoshaf(
            id = "202",
            name = "المرتل",
            server = "https://s2/",
            rawSurahList = "1",
            first10Surahs = listOf("1"),
            firstSurahNumber = 1,
            rawFieldNames = listOf("id", "name", "server", "surah_list"),
          ),
        ),
      ),
    )
    val timingIds = setOf("101")
    val (withTiming, totalInRiwaya) = com.example.debug.RiwayatApiDebugRepository.countRecitersWithTimingForRiwaya(
      all = reciters,
      selectedRiwayaId = "1",
      timingReadIds = timingIds,
    )
    assertEquals(1, withTiming)
    assertEquals(2, totalInRiwaya)
    assertEquals(
      "له توقيت",
      com.example.debug.RiwayatApiDebugRepository.timingLabelForMoshaf(reciters[0].moshafList[0], timingIds, true),
    )
    assertEquals(
      "ليس له توقيت",
      com.example.debug.RiwayatApiDebugRepository.timingLabelForMoshaf(reciters[1].moshafList[0], timingIds, true),
    )

    val clipboardWithTiming = com.example.debug.RiwayatApiDebugRepository.formatFilteredResultsForClipboard(
      filtered = reciters,
      selectedRiwayaLabel = "1 - حفص",
      timingReadIds = timingIds,
      timingReadsLoaded = true,
    )
    assertTrue(clipboardWithTiming.contains("قرّاء لهم توقيت: 1 من 2"))
    assertTrue(clipboardWithTiming.contains("id: 101 | له توقيت"))
    assertTrue(clipboardWithTiming.contains("id: 202 | ليس له توقيت"))
  }
}
