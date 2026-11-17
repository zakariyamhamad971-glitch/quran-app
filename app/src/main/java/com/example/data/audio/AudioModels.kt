package com.example.data.audio

import androidx.compose.runtime.Immutable
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Mp3QuranRecitersResponse(
  val reciters: List<Mp3QuranReciterDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class Mp3QuranReciterDto(
  val id: Int,
  val name: String,
  val moshaf: List<Mp3QuranMoshafDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class Mp3QuranMoshafDto(
  val id: Int,
  val name: String,
  val server: String,
  val surah_total: Int = 0,
  val surah_list: String = "",
)

@JsonClass(generateAdapter = true)
data class Mp3QuranSuwarResponse(
  val suwar: List<Mp3QuranSurahDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class Mp3QuranSurahDto(
  val id: Int,
  val name: String,
  val start_page: Int = 0,
  val end_page: Int = 0,
  val makkia: Int = 0,
)

@JsonClass(generateAdapter = true)
data class AyahTimingDto(
  val ayah: Int,
  val start_time: Long,
  val end_time: Long,
)

@JsonClass(generateAdapter = true)
data class TimingReadsResponse(
  val reads: List<TimingReadDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class TimingReadDto(
  val id: Int,
  val name: String? = null,
  val rewaya: String? = null,
  val folder_url: String? = null,
)

enum class ReciterMatchStatus {
  VERIFIED,
  MISSING,
  AMBIGUOUS,
}

@Immutable
data class VerifiedReciter(
  val orderIndex: Int,
  val requestedName: String,
  val reciterId: Int?,
  val displayName: String,
  val moshafId: Int?,
  val moshafName: String?,
  val serverUrl: String?,
  val availableSurahs: Set<Int>,
  val status: ReciterMatchStatus,
  val statusNote: String,
  val isPrimaryOrdered: Boolean = true,
) {
  val key: String get() = "${reciterId ?: -orderIndex}_${moshafId ?: 0}"
  val isPlayable: Boolean get() = status == ReciterMatchStatus.VERIFIED && !serverUrl.isNullOrBlank()
}

data class SurahUrlAudit(
  val surahId: Int,
  val url: String,
  val isReachable: Boolean,
  val contentType: String?,
  val contentLengthBytes: Long,
)

@JsonClass(generateAdapter = true)
data class QuranComChapterRecitersResponse(
  val reciters: List<QuranComReciterDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class QuranComNamedFieldDto(
  val name: String? = null,
  val language_name: String? = null,
)

@JsonClass(generateAdapter = true)
data class QuranComReciterDto(
  val id: Int,
  val name: String = "",
  val arabic_name: String? = null,
  val style: QuranComNamedFieldDto? = null,
  val qirat: QuranComNamedFieldDto? = null,
  val translated_name: QuranComNamedFieldDto? = null,
)

@JsonClass(generateAdapter = true)
data class QuranComChapterAudioResponse(
  val audio_file: QuranComAudioFileDto? = null,
)

@JsonClass(generateAdapter = true)
data class QuranComAudioFileDto(
  val id: Int = 0,
  val chapter_id: Int = 0,
  val audio_url: String? = null,
  val file_size: Double? = null,
  val timestamps: List<QuranComTimestampDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class QuranComTimestampDto(
  val verse_key: String = "",
  val timestamp_from: Long = 0L,
  val timestamp_to: Long = 0L,
)

@JsonClass(generateAdapter = true)
data class AlQuranCloudEditionsResponse(
  val code: Int = 0,
  val data: List<AlQuranCloudAudioEditionDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AlQuranCloudAudioEditionDto(
  val identifier: String = "",
  val language: String = "",
  val name: String = "",
  val englishName: String = "",
  val format: String = "",
  val type: String = "",
)

@JsonClass(generateAdapter = true)
data class AlQuranCloudSurahAudioResponse(
  val code: Int = 0,
  val data: AlQuranCloudSurahAudioData? = null,
)

@JsonClass(generateAdapter = true)
data class AlQuranCloudSurahAudioData(
  val number: Int = 0,
  val ayahs: List<AlQuranCloudAyahAudioDto> = emptyList(),
)

@JsonClass(generateAdapter = true)
data class AlQuranCloudAyahAudioDto(
  val numberInSurah: Int = 0,
  val audio: String = "",
)

@Immutable
data class RecitationRange(
  val startSurah: Int,
  val startAyah: Int,
  val endSurah: Int,
  val endAyah: Int,
  val loop: Boolean = false,
)

data class ResolvedPlaybackSource(
  val streamUrl: String,
  val ayahTimings: List<AyahTimingDto>,
  val timingSourceLabel: String,
)

data class VerseAudioSegment(
  val ayahNumber: Int,
  val audioUrl: String,
)

sealed interface ResolvedRecitationAudio {
  data class SingleTimedTrack(
    val streamUrl: String,
    val ayahTimings: List<AyahTimingDto>,
    val timingSourceLabel: String,
    val matchesSelectedMoshafUrl: Boolean,
  ) : ResolvedRecitationAudio

  data class VerseByVersePlaylist(
    val segments: List<VerseAudioSegment>,
    val timingSourceLabel: String,
  ) : ResolvedRecitationAudio

  data class UntimedSingleTrack(
    val streamUrl: String,
  ) : ResolvedRecitationAudio
}


