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
) {
  fun summaryLabelAr(): String = when (mode) {
    PrayerAlarmMode.SILENT -> "صامت"
    PrayerAlarmMode.SYSTEM_DEFAULT -> "نغمة النظام"
    PrayerAlarmMode.CUSTOM_URI -> customDisplayName?.takeIf { it.isNotBlank() } ?: "ملف مخصص"
  }
}

@Immutable
data class AdhanSettingsState(
  val alarmsEnabled: Boolean = true,
  val persistentCountdownEnabled: Boolean = true,
  val perPrayerConfig: Map<PrayerType, PrayerAlarmConfig> = defaultPerPrayerConfig(),
) {
  fun configFor(type: PrayerType): PrayerAlarmConfig =
    perPrayerConfig[type] ?: defaultConfigFor(type)

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
          defaults[type] = PrayerAlarmConfig(
            mode = if (mode == PrayerAlarmMode.CUSTOM_URI && uri == null) {
              PrayerAlarmMode.SYSTEM_DEFAULT
            } else {
              mode
            },
            customUri = uri,
            customDisplayName = name,
          )
        }
        defaults
      }.getOrDefault(defaultPerPrayerConfig())
    }
  }
}

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
