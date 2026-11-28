package com.example

import android.net.Uri
import com.example.data.audio.AudioRepository
import com.example.data.audio.Mp3QuranMoshafInfo
import com.example.data.audio.Mp3QuranRiwayaReciterEntry
import com.example.data.audio.Mp3QuranRiwayatParser
import com.example.data.prayer.AdhanAlarmScheduler
import com.example.data.prayer.AdhanSettingsState
import com.example.data.prayer.DailyPrayerTimesRecord
import com.example.data.prayer.PrayerAlarmConfig
import com.example.data.prayer.PrayerAlarmMode
import com.example.data.prayer.PrayerType
import com.example.data.quran.InsightRegistryProvider
import com.example.data.quran.InsightSourceType
import com.example.data.quran.TafsirRegistryRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AdhanAndFeaturesUnitTest {

  @Test
  fun adhanSettings_defaultsSunriseSilent_andRoundTripsConfigJson() {
    val defaults = AdhanSettingsState()
    assertTrue(defaults.alarmsEnabled)
    assertTrue(defaults.persistentCountdownEnabled)
    assertEquals(PrayerAlarmMode.SYSTEM_DEFAULT, defaults.configFor(PrayerType.FAJR).mode)
    assertEquals(PrayerAlarmMode.SYSTEM_DEFAULT, defaults.configFor(PrayerType.DHUHR).mode)
    assertEquals(PrayerAlarmMode.SILENT, defaults.configFor(PrayerType.SUNRISE).mode)

    val custom = defaults
      .withPrayerConfig(
        PrayerType.FAJR,
        PrayerAlarmConfig(
          mode = PrayerAlarmMode.CUSTOM_URI,
          customUri = "content://com.example.docs/adhan_fajr.mp3",
          customDisplayName = "أذان الفجر",
        ),
      )
      .withPrayerConfig(
        PrayerType.ASR,
        PrayerAlarmConfig(mode = PrayerAlarmMode.SILENT),
      )

    val encoded = AdhanSettingsState.encodeConfigsJson(custom.perPrayerConfig)
    val decoded = AdhanSettingsState.decodeConfigsJson(encoded)

    assertEquals(PrayerAlarmMode.CUSTOM_URI, decoded[PrayerType.FAJR]?.mode)
    assertEquals("content://com.example.docs/adhan_fajr.mp3", decoded[PrayerType.FAJR]?.customUri)
    assertEquals("أذان الفجر", decoded[PrayerType.FAJR]?.customDisplayName)
    assertEquals(PrayerAlarmMode.SILENT, decoded[PrayerType.ASR]?.mode)
    assertEquals(PrayerAlarmMode.SYSTEM_DEFAULT, decoded[PrayerType.MAGHRIB]?.mode)
  }

  @Test
  fun collectAlarmsForWindow_filtersPastSilentAndBeyond48h() {
    val nowMs = 1_000_000_000L
    val hourMs = 3_600_000L
    val day0 = DailyPrayerTimesRecord(
      dateKey = "2026-11-27",
      year = 2026,
      month = 11,
      day = 27,
      fajrEpochMs = nowMs - 2 * hourMs, // past
      sunriseEpochMs = nowMs + 1 * hourMs, // future but silent by default
      dhuhrEpochMs = nowMs + 4 * hourMs,
      asrEpochMs = nowMs + 7 * hourMs,
      maghribEpochMs = nowMs + 10 * hourMs,
      ishaEpochMs = nowMs + 12 * hourMs,
    )
    val day2OutOfHorizon = DailyPrayerTimesRecord(
      dateKey = "2026-11-29",
      year = 2026,
      month = 11,
      day = 29,
      fajrEpochMs = nowMs + 47 * hourMs, // inside 48h
      sunriseEpochMs = nowMs + 48 * hourMs + 1_000L,
      dhuhrEpochMs = nowMs + 52 * hourMs, // outside 48h
      asrEpochMs = nowMs + 55 * hourMs,
      maghribEpochMs = nowMs + 58 * hourMs,
      ishaEpochMs = nowMs + 60 * hourMs,
    )

    val settings = AdhanSettingsState()
    val scheduled = AdhanAlarmScheduler.collectAlarmsForWindow(
      nowMs = nowMs,
      windowMs = 48L * hourMs,
      dailyRecords = listOf(day0, day2OutOfHorizon),
      adhanSettings = settings,
    )

    // Expected: dhuhr, asr, maghrib, isha from day0 + fajr from day2 = 5 alarms
    assertEquals(5, scheduled.size)
    assertEquals(
      listOf(PrayerType.DHUHR, PrayerType.ASR, PrayerType.MAGHRIB, PrayerType.ISHA, PrayerType.FAJR),
      scheduled.map { it.prayerType },
    )
    assertTrue(scheduled.zipWithNext().all { (a, b) -> a.triggerEpochMs < b.triggerEpochMs })

    // When master alarms toggle is off, nothing should be scheduled
    val disabled = AdhanAlarmScheduler.collectAlarmsForWindow(
      nowMs = nowMs,
      windowMs = 48L * hourMs,
      dailyRecords = listOf(day0, day2OutOfHorizon),
      adhanSettings = settings.copy(alarmsEnabled = false),
    )
    assertTrue(disabled.isEmpty())
  }

  @Test
  fun reciterSearchNormalization_matchesSingleLetterHamzaAndDiacritics() {
    assertTrue(AudioRepository.matchesReciterSearchQuery("مشاري راشد العفاسي", "م"))
    assertTrue(AudioRepository.matchesReciterSearchQuery("مشاري راشد العفاسي", "عفا"))
    assertTrue(AudioRepository.matchesReciterSearchQuery("أحمد بن علي العجمي", "احمد"))
    assertTrue(AudioRepository.matchesReciterSearchQuery("إدريس أبكر", "ادريس ابكر"))
    assertTrue(AudioRepository.matchesReciterSearchQuery("مَـحْـمُـود خَلِيل الحُصَرِي", "محمود خليل"))
    assertTrue(AudioRepository.matchesReciterSearchQuery("خالد القحطاني", "   "))
    assertFalse(AudioRepository.matchesReciterSearchQuery("مشاري راشد العفاسي", "سعد"))
  }

  @Test
  fun buildGroupedVerifiedReciters_preservesAllStylesWithRawMoshafNames() {
    val entriesByRiwaya = mapOf(
      1 to listOf(
        Mp3QuranRiwayaReciterEntry(
          reciterId = 112,
          reciterName = "محمود خليل الحصري",
          riwayaId = 1,
          riwayaName = "حفص عن عاصم",
          moshafList = listOf(
            Mp3QuranMoshafInfo(1121, "المصحف المجود - حفص عن عاصم", "https://server.test/1/", (1..114).toSet()),
            Mp3QuranMoshafInfo(1122, "حفص عن عاصم - مرتل", "https://server.test/2/", (1..114).toSet()),
            Mp3QuranMoshafInfo(1123, "المصحف المعلم - حفص عن عاصم", "https://server.test/3/", (1..114).toSet()),
          ),
        ),
      ),
    )

    val reciters = Mp3QuranRiwayatParser.buildGroupedVerifiedReciters(
      orderedRiwayaIds = listOf(1),
      entriesByRiwayaId = entriesByRiwaya,
      timingReadIds = setOf(1122),
    )

    assertEquals(1, reciters.size)
    val husary = reciters.first()
    assertEquals(112, husary.reciterId)
    assertEquals(3, husary.availableStyles.size)
    assertEquals("حفص عن عاصم - مرتل", husary.moshafName)
    assertEquals(
      setOf("حفص عن عاصم - مرتل", "المصحف المجود - حفص عن عاصم", "المصحف المعلم - حفص عن عاصم"),
      husary.availableStyles.map { it.moshafName }.toSet(),
    )
  }

  @Test
  fun tafsirRegistry_parsesEditionsAndMarksIrab() = runTest {
    val context = RuntimeEnvironment.getApplication()
    val repo = TafsirRegistryRepository(context, this)
    val provider = InsightRegistryProvider(
      id = "spa5k_tafsir_api",
      nameAr = "مكتبة التفاسير",
      language = "multi",
      type = InsightSourceType.TAFSIR,
      editionsUrl = "https://cdn.jsdelivr.net/gh/spa5k/tafsir_api@main/tafsir/editions.json",
      urlPattern = "https://cdn.jsdelivr.net/gh/spa5k/tafsir_api@main/tafsir/{edition}/{surah}.json",
      version = "1.0.0",
      license = "Public",
      attribution = "spa5k/tafsir_api",
    )

    val editionsJson = """
      [
        {
          "slug": "ar-tafsir-muyassar",
          "name": "التفسير الميسر",
          "author_name": "مجمع الملك فهد",
          "language_name": "arabic",
          "source": "quran.com"
        },
        {
          "slug": "ar-irab-sample",
          "name": "إعراب القرآن",
          "author_name": "Test",
          "language_name": "arabic",
          "source": "irab"
        },
        {
          "slug": "en-tafisr-ibn-kathir",
          "name": "Tafsir Ibn Kathir",
          "author_name": "Hafiz Ibn Kathir",
          "language_name": "english",
          "source": "quran.com"
        }
      ]
    """.trimIndent()

    val parsed = repo.parseEditionsJson(editionsJson, provider)
    assertEquals(3, parsed.size)
    assertEquals(
      listOf("ar-tafsir-muyassar", "ar-irab-sample", "en-tafisr-ibn-kathir"),
      parsed.map { it.slug },
    )
    assertEquals(InsightSourceType.IRAB, parsed[1].type)
    assertEquals(InsightSourceType.IRAB.labelAr, "إعراب")

    val surahPayload = """
      {
        "ayahs": [
          {"ayah": 1, "surah": 1, "text": "بسم الله تفسير"},
          {"ayah": 2, "surah": 1, "text": "الحمد لله تفسير"}
        ]
      }
    """.trimIndent()
    val ayahs = repo.parseSurahTafsirPayload(surahPayload)
    assertEquals("بسم الله تفسير", ayahs[1])
    assertEquals("الحمد لله تفسير", ayahs[2])
  }

  @Test
  fun adhanSoundRegistry_loadsVerifiedCatalogOrOfflineFallbackAndAttributions() = runTest {
    val context = RuntimeEnvironment.getApplication()
    val repo = com.example.data.prayer.AdhanSoundRepository(context)
    val snapshot = repo.loadRegistry()
    assertTrue(snapshot.sounds.isNotEmpty() || !snapshot.loadError.isNullOrBlank())
    val credits = repo.loadAdhanCredits()
    assertTrue(credits.any { it.id == "batoulapps_adhan2" && it.license.contains("MIT") })
    assertTrue(credits.any { it.id == "adhan_sounds_registry" })
    // Unreadable custom URI safely resolves or returns null/fallback without throwing
    val fallback = repo.resolvePlayableUri(
      PrayerAlarmConfig(
        mode = PrayerAlarmMode.CUSTOM_URI,
        customUri = Uri.parse("content://nonexistent/audio.mp3").toString(),
      ),
    )
    // In Robolectric, default ringtone URI may or may not be populated, just assert no crash
    assertTrue(fallback == null || fallback.toString().isNotEmpty())
  }
}
