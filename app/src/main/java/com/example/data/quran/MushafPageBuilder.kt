package com.example.data.quran

object MushafPageBuilder {

  /**
   * Builds an exact 15-line page matching the Madinah 1421H Mushaf layout data from Quran.com API v4.
   * - Never alters, normalizes, or retypes Quran text or glyphs.
   * - Preserves exact word order (`position`) and exact `line_number` (1..15).
   * - Inserts Surah header band (1 line) and Basmalah (1 line, using 1:1 API data) strictly
   *   in the line slots defined by the 15-line layout.
   */
  fun buildPage(
    pageNumber: Int,
    versesResponse: VersesByPageResponse,
    chaptersById: Map<Int, SurahMeta>,
    basmalahTokens: List<WordDto>,
    tajweedAyahTexts: Map<String, String> = emptyMap(),
  ): MushafPageData {
    val lineWordsMap = (1..15).associateWith { mutableListOf<MushafWord>() }
    val surahFirstAyahLineOnThisPage = mutableMapOf<Int, Int>()
    val surahIdsOnPage = linkedSetOf<Int>()

    var juzNumber = 1
    var hizbNumber = 1

    val sortedVerses = versesResponse.verses.sortedBy { it.id }
    if (sortedVerses.isNotEmpty()) {
      juzNumber = sortedVerses.first().juz_number
      hizbNumber = sortedVerses.first().hizb_number
    }

    for (verse in sortedVerses) {
      val parts = verse.verse_key.split(":")
      val surahId = parts.getOrNull(0)?.toIntOrNull() ?: continue
      val ayahNumber = parts.getOrNull(1)?.toIntOrNull() ?: continue
      surahIdsOnPage.add(surahId)

      val sortedWords = verse.words.sortedBy { it.position }
      for (word in sortedWords) {
        if (word.page_number != pageNumber) continue
        val lineNo = word.line_number
        if (lineNo !in 1..15) continue

        if (ayahNumber == 1 && word.position == 1) {
          val existing = surahFirstAyahLineOnThisPage[surahId]
          if (existing == null || lineNo < existing) {
            surahFirstAyahLineOnThisPage[surahId] = lineNo
          }
        }

        val isEnd = word.char_type_name == "end"
        lineWordsMap[lineNo]?.add(
          MushafWord(
            id = word.id,
            verseKey = verse.verse_key,
            surahId = surahId,
            ayahNumber = ayahNumber,
            position = word.position,
            isEndMarker = isEnd,
            codeV2 = word.code_v2.orEmpty(),
            textUthmani = word.text_uthmani.orEmpty(),
          ),
        )
      }
    }

    val headerLineMap = mutableMapOf<Int, SurahMeta>()
    val basmalahLineSet = mutableSetOf<Int>()

    // Basmalah glyphs/text strictly from API 1:1 words (excluding the end-ayah marker)
    val basmalahOnlyWords = basmalahTokens
      .filter { it.char_type_name != "end" }
      .sortedBy { it.position }
    val basmalahCodeV2 = basmalahOnlyWords.joinToString(" ") { it.code_v2.orEmpty() }.trim()
    val basmalahUthmani = basmalahOnlyWords.joinToString(" ") { it.text_uthmani.orEmpty() }.trim()

    // Place Surah header and Basmalah lines immediately above ayah 1 of starting surahs
    for ((surahId, firstWordLine) in surahFirstAyahLineOnThisPage) {
      val surah = chaptersById[surahId] ?: continue
      if (surahId == 1) {
        // Surah Al-Fatihah: 1:1 is itself the Basmalah verse on line 2; line 1 is Surah Header
        if (firstWordLine >= 2 && lineWordsMap[firstWordLine - 1].orEmpty().isEmpty()) {
          headerLineMap[firstWordLine - 1] = surah
        }
      } else if (!surah.bismillahPre) {
        // Surah At-Tawbah (9): no Basmalah, header sits 1 line above ayah 1
        if (firstWordLine >= 2 && lineWordsMap[firstWordLine - 1].orEmpty().isEmpty()) {
          headerLineMap[firstWordLine - 1] = surah
        }
      } else {
        // Standard Surah with Basmalah: Basmalah at L-1, Surah Header at L-2
        if (firstWordLine >= 2 && lineWordsMap[firstWordLine - 1].orEmpty().isEmpty()) {
          basmalahLineSet.add(firstWordLine - 1)
        }
        if (firstWordLine >= 3 && lineWordsMap[firstWordLine - 2].orEmpty().isEmpty()) {
          headerLineMap[firstWordLine - 2] = surah
        }
      }
    }

    // Handle edge case where a Surah header/Basmalah sits at the bottom of page N (lines 14/15)
    // while ayah 1 begins on line 1 or 2 of page N+1
    val chaptersStartingOnThisPage = chaptersById.values.filter { it.startPage == pageNumber }
    for (surah in chaptersStartingOnThisPage) {
      if (surahFirstAyahLineOnThisPage.containsKey(surah.id)) continue
      // Find lowest empty lines at the bottom of this page
      val trailingEmpty = (15 downTo 1).takeWhile { ln ->
        lineWordsMap[ln].orEmpty().isEmpty() && !headerLineMap.containsKey(ln) && !basmalahLineSet.contains(ln)
      }.sorted()
      if (surah.bismillahPre && trailingEmpty.size >= 2) {
        headerLineMap[trailingEmpty[0]] = surah
        basmalahLineSet.add(trailingEmpty[1])
      } else if (trailingEmpty.isNotEmpty()) {
        headerLineMap[trailingEmpty.last()] = surah
      }
    }

    val lines = (1..15).map { ln ->
      val header = headerLineMap[ln]
      when {
        header != null -> MushafLine.SurahHeaderLine(ln, header)
        basmalahLineSet.contains(ln) -> MushafLine.BasmalahLine(ln, basmalahCodeV2, basmalahUthmani)
        else -> {
          val words = lineWordsMap[ln].orEmpty()
          if (words.isEmpty()) {
            MushafLine.EmptyLine(ln)
          } else {
            MushafLine.AyahWordsLine(ln, words)
          }
        }
      }
    }

    return MushafPageData(
      pageNumber = pageNumber,
      juzNumber = juzNumber,
      hizbNumber = hizbNumber,
      surahIds = surahIdsOnPage.toList(),
      lines = lines,
      tajweedAyahTexts = tajweedAyahTexts,
    )
  }
}
