package com.example.data.pdf

import androidx.compose.runtime.Immutable

const val MUSHAF_BASE_URL = "https://raw.githubusercontent.com/zakariyamhamad971-glitch/quran-mushafs/main/"

@Immutable
data class PdfMushafEntry(
  val id: String,
  val order: Int,
  val file: String,
  val thumb: String,
  val titleAr: String,
  val riwayaAr: String,
  val pages: Int,
  val sizeMB: Double,
  val apiRiwayaIds: List<Int>,
)

@Immutable
data class PdfMushafTransferState(
  val mushafId: String,
  val isDownloadingOffline: Boolean = false,
  val isStreamingOpen: Boolean = false,
  val progressPercent: Int = 0,
  val downloadedBytes: Long = 0L,
  val totalBytes: Long = 0L,
  val errorMessage: String? = null,
) {
  val isBusy: Boolean get() = isDownloadingOffline || isStreamingOpen
}

enum class ColorSwatchStyle {
  FILLED,
  RING,
}

@Immutable
data class RiwayaColorGuideItem(
  val label: String,
  val colorHex: String,
  val style: ColorSwatchStyle = ColorSwatchStyle.FILLED,
)

@Immutable
data class RiwayaColorGuideSection(
  val id: String,
  val riwayaAr: String,
  val items: List<RiwayaColorGuideItem>,
)

@Immutable
data class ColorsGuideData(
  val version: Int = 1,
  val riwayat: List<RiwayaColorGuideSection> = emptyList(),
)

