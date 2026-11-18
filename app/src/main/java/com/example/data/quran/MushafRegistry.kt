package com.example.data.quran

import androidx.compose.runtime.Immutable

@Immutable
data class MushafItem(
  val id: String,
  val title: String,
  val subtitle: String,
  val sizeMb: Float,
  val edition: MushafEdition,
)

object MushafRegistry {
  val allMushafs = listOf(
    MushafItem(
      id = "madinah_1421",
      title = "مصحف المدينة ١٤٢١",
      subtitle = "الرسم العثماني KFGQPC - حفص عن عاصم",
      sizeMb = 52.5f,
      edition = MushafEdition.MADINAH_1421,
    ),
    MushafItem(
      id = "tajweed_v4",
      title = "مصحف التجويد الملون",
      subtitle = "أحكام التجويد ملونة مع الرسم العثماني",
      sizeMb = 64.0f,
      edition = MushafEdition.TAJWEED_V4,
    ),
  )

  fun getById(id: String?): MushafItem =
    allMushafs.firstOrNull { it.id == id } ?: allMushafs.first()

  fun getByEdition(edition: MushafEdition): MushafItem =
    allMushafs.firstOrNull { it.edition == edition } ?: allMushafs.first()
}
