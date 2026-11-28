package com.example.data.prayer

import androidx.compose.runtime.Immutable
import org.json.JSONObject

enum class PrayerAlarmMode(val arabicLabel: String, val subtitleAr: String) {
  SILENT(
    arabicLabel = "صامت",
    subtitleAr = "إيقاف التنبيه الصوتي لهذه الصلاة",
  ),
  SYSTEM_DEFAULT(
    arabicLabel = "نغمة النظام الافتراضية",
    subtitleAr = "استخدام صوت المنبه الافتراضي للجهاز",
  ),
  MUEZZIN(
    arabicLabel = "مؤذن معتمد",
    subtitleAr = "اختيار مؤذن من مكتبة المؤذنين (بث وتنزيل)",
  ),
  CUSTOM_URI(
    arabicLabel = "ملف صوتي من الجهاز",
    subtitleAr = "اختيار ملف أذان أو تنبيه محفوظ على جهازك",
  ),
}

@Immutable
data class PrayerAlarmConfig(
  val mode: PrayerAlarmMode = PrayerAlarmMode.SYSTEM_DEFAULT,
  val customUri: String? = null,
  val customDisplayName: String? = null,
  val muezzinId: String? = null,
  val muezzinGroupId: String? = null,
  val recordingFileName: String? = null,
  val recordingSizeBytes: Long = 0L,
) {
  fun summaryLabelAr(): String = when (mode) {
    PrayerAlarmMode.SILENT -> "صامت"
    PrayerAlarmMode.SYSTEM_DEFAULT -> "نغمة النظام"
    PrayerAlarmMode.MUEZZIN -> customDisplayName?.takeIf { it.isNotBlank() } ?: "مؤذن معتمد"
    PrayerAlarmMode.CUSTOM_URI -> customDisplayName?.takeIf { it.isNotBlank() } ?: "ملف مخصص"
  }
}

@Immutable
data class IqamaConfig(
  val enabled: Boolean = false,
  val delayMinutes: Int = 15,
  val customUri: String? = null,
  val customDisplayName: String? = null,
)

@Immutable
data class AdhanSettingsState(
  val alarmsEnabled: Boolean = true,
  val persistentCountdownEnabled: Boolean = true,
  val preAdhanMinutes: Int = 0,
  val adhanVolumePercent: Int = 100,
  val vibrateOnAdhan: Boolean = true,
  val shortAlertOnly: Boolean = false,
  val backgroundImagePath: String? = null,
  val backgroundLabel: String? = null,
  val perPrayerConfig: Map<PrayerType, PrayerAlarmConfig> = defaultPerPrayerConfig(),
  val iqamaConfigs: Map<PrayerType, IqamaConfig> = defaultIqamaConfigs(),
) {
  fun configFor(type: PrayerType): PrayerAlarmConfig =
    perPrayerConfig[type] ?: defaultConfigFor(type)

  fun iqamaFor(type: PrayerType): IqamaConfig =
    if (type == PrayerType.SUNRISE) IqamaConfig(enabled = false, delayMinutes = 0)
    else iqamaConfigs[type] ?: defaultIqamaFor(type)

  fun withIqamaConfig(type: PrayerType, config: IqamaConfig): AdhanSettingsState {
    if (type == PrayerType.SUNRISE) return this
    val updated = iqamaConfigs.toMutableMap()
    updated[type] = config.copy(delayMinutes = config.delayMinutes.coerceIn(1, 90))
    return copy(iqamaConfigs = updated)
  }

  fun isAudibleFor(type: PrayerType): Boolean {
    if (!alarmsEnabled) return false
    return configFor(type).mode != PrayerAlarmMode.SILENT
  }

  fun withPrayerConfig(type: PrayerType, config: PrayerAlarmConfig): AdhanSettingsState {
    val updated = perPrayerConfig.toMutableMap()
    updated[type] = config
    return copy(perPrayerConfig = updated)
  }

  fun withAllPrayersConfig(config: PrayerAlarmConfig, includeSunrise: Boolean = false): AdhanSettingsState {
    val updated = perPrayerConfig.toMutableMap()
    for (type in PrayerType.entries) {
      if (type == PrayerType.SUNRISE && !includeSunrise) continue
      updated[type] = config
    }
    return copy(perPrayerConfig = updated)
  }

  companion object {
    fun defaultConfigFor(type: PrayerType): PrayerAlarmConfig =
      if (type == PrayerType.SUNRISE) {
        PrayerAlarmConfig(mode = PrayerAlarmMode.SILENT)
      } else {
        PrayerAlarmConfig(mode = PrayerAlarmMode.SYSTEM_DEFAULT)
      }

    fun defaultPerPrayerConfig(): Map<PrayerType, PrayerAlarmConfig> =
      PrayerType.entries.associateWith { defaultConfigFor(it) }

    fun encodeConfigsJson(configs: Map<PrayerType, PrayerAlarmConfig>): String {
      val root = JSONObject()
      for (type in PrayerType.entries) {
        val cfg = configs[type] ?: defaultConfigFor(type)
        root.put(
          type.key,
          JSONObject().apply {
            put("mode", cfg.mode.name)
            if (!cfg.customUri.isNullOrBlank()) put("uri", cfg.customUri)
            if (!cfg.customDisplayName.isNullOrBlank()) put("name", cfg.customDisplayName)
            if (!cfg.muezzinId.isNullOrBlank()) put("muezzin_id", cfg.muezzinId)
            if (!cfg.muezzinGroupId.isNullOrBlank()) put("muezzin_group_id", cfg.muezzinGroupId)
            if (!cfg.recordingFileName.isNullOrBlank()) put("recording_file", cfg.recordingFileName)
            if (cfg.recordingSizeBytes > 0L) put("recording_size", cfg.recordingSizeBytes)
          },
        )
      }
      return root.toString()
    }

    fun decodeConfigsJson(raw: String?): Map<PrayerType, PrayerAlarmConfig> {
      val defaults = defaultPerPrayerConfig().toMutableMap()
      if (raw.isNullOrBlank()) return defaults
      return runCatching {
        val root = JSONObject(raw)
        for (type in PrayerType.entries) {
          val obj = root.optJSONObject(type.key) ?: continue
          val mode = obj.optString("mode", "").let { name ->
            runCatching { PrayerAlarmMode.valueOf(name) }.getOrNull()
          } ?: defaultConfigFor(type).mode
          val uri = obj.optString("uri", "").takeIf { it.isNotBlank() }
          val name = obj.optString("name", "").takeIf { it.isNotBlank() }
          val muezzinId = obj.optString("muezzin_id", "").takeIf { it.isNotBlank() }
          val muezzinGroupId = obj.optString("muezzin_group_id", "").takeIf { it.isNotBlank() }
          val recordingFile = obj.optString("recording_file", "").takeIf { it.isNotBlank() }
          val recordingSize = obj.optLong("recording_size", 0L).coerceAtLeast(0L)
          defaults[type] = PrayerAlarmConfig(
            mode = if (mode == PrayerAlarmMode.CUSTOM_URI && uri == null) {
              PrayerAlarmMode.SYSTEM_DEFAULT
            } else {
              mode
            },
            customUri = uri,
            customDisplayName = name,
            muezzinId = muezzinId,
            muezzinGroupId = muezzinGroupId,
            recordingFileName = recordingFile,
            recordingSizeBytes = recordingSize,
          )
        }
        defaults
      }.getOrDefault(defaultPerPrayerConfig())
    }

    fun defaultIqamaDelayMinutes(type: PrayerType): Int = when (type) {
      PrayerType.FAJR -> 20
      PrayerType.DHUHR -> 15
      PrayerType.ASR -> 15
      PrayerType.MAGHRIB -> 10
      PrayerType.ISHA -> 15
      PrayerType.SUNRISE -> 0
    }

    fun defaultIqamaFor(type: PrayerType): IqamaConfig = IqamaConfig(
      enabled = false,
      delayMinutes = defaultIqamaDelayMinutes(type),
    )

    fun defaultIqamaConfigs(): Map<PrayerType, IqamaConfig> =
      PrayerType.entries.filter { it != PrayerType.SUNRISE }.associateWith { defaultIqamaFor(it) }

    fun encodeIqamaConfigsJson(configs: Map<PrayerType, IqamaConfig>): String {
      val root = JSONObject()
      for (type in PrayerType.entries) {
        if (type == PrayerType.SUNRISE) continue
        val cfg = configs[type] ?: defaultIqamaFor(type)
        root.put(
          type.key,
          JSONObject().apply {
            put("enabled", cfg.enabled)
            put("delay", cfg.delayMinutes.coerceIn(1, 90))
            if (!cfg.customUri.isNullOrBlank()) put("uri", cfg.customUri)
            if (!cfg.customDisplayName.isNullOrBlank()) put("name", cfg.customDisplayName)
          },
        )
      }
      return root.toString()
    }

    fun decodeIqamaConfigsJson(raw: String?): Map<PrayerType, IqamaConfig> {
      val defaults = defaultIqamaConfigs().toMutableMap()
      if (raw.isNullOrBlank()) return defaults
      return runCatching {
        val root = JSONObject(raw)
        for (type in PrayerType.entries) {
          if (type == PrayerType.SUNRISE) continue
          val obj = root.optJSONObject(type.key) ?: continue
          val enabled = obj.optBoolean("enabled", false)
          val delay = obj.optInt("delay", defaultIqamaDelayMinutes(type)).coerceIn(1, 90)
          val uri = obj.optString("uri", "").takeIf { it.isNotBlank() }
          val name = obj.optString("name", "").takeIf { it.isNotBlank() }
          defaults[type] = IqamaConfig(
            enabled = enabled,
            delayMinutes = delay,
            customUri = uri,
            customDisplayName = name,
          )
        }
        defaults
      }.getOrDefault(defaultIqamaConfigs())
    }
  }
}

@Immutable
data class AdhanRecordingItem(
  val id: String,
  val fileName: String,
  val arabicTitle: String,
  val recordingNumber: Int?,
  val isFajr: Boolean,
  val isHq: Boolean,
  val countryAr: String?,
  val placeDetailAr: String?,
  val sizeBytes: Long,
  val primaryUrl: String,
  val fallbackUrl: String,
)

@Immutable
data class MuezzinGroup(
  val id: String,
  val arabicName: String,
  val englishName: String,
  val countryAr: String,
  val placeDetailAr: String?,
  val hasFajr: Boolean,
  val recordings: List<AdhanRecordingItem>,
)

@Immutable
data class AdhanSoundRegistryEntry(
  val id: String,
  val nameAr: String,
  val muezzinAr: String,
  val sourceType: String,
  val license: String,
  val attribution: String,
  val audioUrl: String? = null,
)

@Immutable
data class ScheduledPrayerAlarm(
  val requestCode: Int,
  val dateKey: String,
  val prayerType: PrayerType,
  val triggerEpochMs: Long,
  val config: PrayerAlarmConfig,
)

@Immutable
data class ScheduledIqamaAlarm(
  val requestCode: Int,
  val dateKey: String,
  val prayerType: PrayerType,
  val triggerEpochMs: Long,
  val config: IqamaConfig,
)
