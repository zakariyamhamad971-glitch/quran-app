package com.example.data.quran

import androidx.compose.runtime.Immutable
import com.squareup.moshi.JsonClass

enum class MushafEdition {
  MADINAH_1421,
  TAJWEED_V4,
}

@JsonClass(generateAdapter = true)
data class ChaptersResponse(
  val chapters: List<ChapterDto>,
)

@JsonClass(generateAdapter = true)
data class ChapterDto(
  val id: Int,
  val revelation_place: String,
  val revelation_order: Int,
  val bismillah_pre: Boolean,
  val name_simple: String,
  val name_arabic: String,
  val verses_count: Int,
  val pages: List<Int>,
)

@JsonClass(generateAdapter = true)
data class JuzsResponse(
  val juzs: List<JuzDto>,
)

@JsonClass(generateAdapter = true)
data class JuzDto(
  val id: Int,
  val juz_number: Int,
  val verse_mapping: Map<String, String>,
  val first_verse_id: Int,
  val last_verse_id: Int,
  val verses_count: Int,
)

@JsonClass(generateAdapter = true)
data class VersesByPageResponse(
  val verses: List<VerseDto>,
)

@JsonClass(generateAdapter = true)
data class VerseByKeyResponse(
  val verse: VerseDto,
)

@JsonClass(generateAdapter = true)
data class VerseDto(
  val id: Int,
  val verse_key: String,
  val page_number: Int,
  val juz_number: Int,
  val hizb_number: Int,
  val rub_el_hizb_number: Int? = null,
  val text_uthmani: String? = null,
  val words: List<WordDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class WordDto(
  val id: Int,
  val position: Int,
  val char_type_name: String,
  val page_number: Int,
  val line_number: Int,
  val code_v2: String? = null,
  val text_uthmani: String? = null,
)

@JsonClass(generateAdapter = true)
data class AlQuranCloudPageResponse(
  val code: Int,
  val status: String,
  val data: AlQuranCloudPageData? = null,
)

@JsonClass(generateAdapter = true)
data class AlQuranCloudPageData(
  val number: Int,
  val ayahs: List<AlQuranCloudAyah> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AlQuranCloudAyah(
  val number: Int,
  val text: String,
  val numberInSurah: Int,
  val surah: AlQuranCloudSurahRef? = null,
)

@JsonClass(generateAdapter = true)
data class AlQuranCloudSurahRef(
  val number: Int,
)

@Immutable
data class SurahMeta(
  val id: Int,
  val nameArabic: String,
  val revelationOrder: Int,
  val versesCount: Int,
  val bismillahPre: Boolean,
  val startPage: Int,
  val endPage: Int,
  val makki: Boolean,
)

@Immutable
data class JuzMeta(
  val juzNumber: Int,
  val startPage: Int,
  val firstSurahId: Int,
  val firstAyah: Int,
)

@Immutable
data class MushafWord(
  val id: Int,
  val verseKey: String,
  val surahId: Int,
  val ayahNumber: Int,
  val position: Int,
  val isEndMarker: Boolean,
  val codeV2: String,
  val textUthmani: String,
)

@Immutable
sealed interface MushafLine {
  val lineNumber: Int

  @Immutable
  data class SurahHeaderLine(
    override val lineNumber: Int,
    val surah: SurahMeta,
  ) : MushafLine

  @Immutable
  data class BasmalahLine(
    override val lineNumber: Int,
    val codeV2Glyphs: String,
    val textUthmani: String,
  ) : MushafLine

  @Immutable
  data class AyahWordsLine(
    override val lineNumber: Int,
    val words: List<MushafWord>,
    val isCentered: Boolean = false,
  ) : MushafLine

  @Immutable
  data class EmptyLine(
    override val lineNumber: Int,
  ) : MushafLine
}

@Immutable
data class MushafPageData(
  val pageNumber: Int,
  val juzNumber: Int,
  val hizbNumber: Int,
  val rubElHizbNumber: Int? = null,
  val surahIds: List<Int>,
  val lines: List<MushafLine>,
  val tajweedAyahTexts: Map<String, String> = emptyMap(),
)

@Immutable
data class PdfPageHeaderMeta(
  val pageNumber: Int,
  val surahIds: List<Int>,
  val juzNumber: Int?,
  val hizbNumber: Int?,
  val rubElHizbNumber: Int? = null,
)

@Immutable
data class TajweedRuleInfo(
  val id: String,
  val nameAr: String,
  val descriptionAr: String,
  val colorHex: String,
)

@Immutable
data class CreditSourceInfo(
  val id: String,
  val title: String,
  val organization: String,
  val url: String,
  val license: String,
  val usage: String,
)

@JsonClass(generateAdapter = true)
data class TranslationResourcesResponse(
  val translations: List<TranslationResourceDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TranslationResourceDto(
  val id: Int,
  val name: String,
  val author_name: String? = null,
  val slug: String? = null,
  val language_name: String? = null,
)

@JsonClass(generateAdapter = true)
data class TafsirResourcesResponse(
  val tafsirs: List<TafsirResourceDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TafsirResourceDto(
  val id: Int,
  val name: String,
  val author_name: String? = null,
  val slug: String? = null,
  val language_name: String? = null,
)

@JsonClass(generateAdapter = true)
data class VerseWithTranslationsResponse(
  val verse: VerseTranslationsDto,
)

@JsonClass(generateAdapter = true)
data class VerseTranslationsDto(
  val id: Int,
  val verse_key: String,
  val translations: List<TranslationItemDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TranslationItemDto(
  val id: Int? = null,
  val resource_id: Int,
  val text: String,
)

@JsonClass(generateAdapter = true)
data class TafsirByAyahResponse(
  val tafsir: TafsirContentDto? = null,
)

@JsonClass(generateAdapter = true)
data class TafsirContentDto(
  val resource_id: Int? = null,
  val resource_name: String? = null,
  val text: String? = null,
)

@Immutable
data class VerseTranslationEntry(
  val languageCode: String,
  val languageLabelAr: String,
  val translatorName: String,
  val text: String,
)

@Immutable
data class AyahSourceInsightEntry(
  val editionId: String,
  val sourceTitle: String,
  val languageLabelAr: String,
  val typeLabelAr: String,
  val isRtl: Boolean,
  val text: String,
)

@Immutable
data class AyahInsightSection(
  val verseKey: String,
  val surahId: Int,
  val ayahNumber: Int,
  val surahName: String,
  val exactAyahText: String,
  val codeV2Line: String,
  val sourceEntries: List<AyahSourceInsightEntry>,
)

@Immutable
data class VerseInsightsData(
  val verseKey: String,
  val surahId: Int,
  val ayahNumber: Int,
  val pageNumber: Int,
  val codeV2Line: String,
  val sections: List<AyahInsightSection> = emptyList(),
  val referenceSummary: String = "",
  val checkedEditionIds: Set<String> = emptySet(),
  val translations: List<VerseTranslationEntry> = emptyList(),
  val tafsirArabicTitle: String = "",
  val tafsirArabicText: String = "",
  val tafsirEnglishTitle: String = "",
  val tafsirEnglishText: String = "",
)

@JsonClass(generateAdapter = true)
data class QuranSearchResponse(
  val search: QuranSearchPayloadDto? = null,
)

@JsonClass(generateAdapter = true)
data class QuranSearchPayloadDto(
  val query: String = "",
  val total_results: Int = 0,
  val results: List<QuranSearchResultDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class QuranSearchResultDto(
  val verse_key: String,
  val verse_id: Int = 0,
  val text: String = "",
)

@Immutable
data class VerseSearchHit(
  val verseKey: String,
  val surahId: Int,
  val ayahNumber: Int,
  val pageNumber: Int,
  val snippetText: String,
)


