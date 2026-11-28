package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LocationCity
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.prayer.AsrMadhab
import com.example.data.prayer.OfflineCityCatalog
import com.example.data.prayer.OfflineCityOption
import com.example.data.prayer.PrayerCalculationMethod
import com.example.data.prayer.PrayerFormatters
import com.example.data.prayer.PrayerHighLatitudeRule
import com.example.data.prayer.PrayerSettingsState
import com.example.data.prayer.PrayerType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualCityPickerSheet(
  currentCityName: String?,
  onUseCoarseLocation: () -> Unit,
  onSelectCity: (OfflineCityOption) -> Unit,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var query by remember { mutableStateOf("") }
  val filteredCities = remember(query) {
    val q = query.trim()
    if (q.isEmpty()) OfflineCityCatalog.cities
    else OfflineCityCatalog.cities.filter {
      it.arabicName.contains(q, ignoreCase = true) ||
        it.countryArabic.contains(q, ignoreCase = true)
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)
        .padding(bottom = 20.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.prayer_location_pick_city),
          style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
          color = MaterialTheme.colorScheme.onSurface,
        )
        OutlinedButton(
          onClick = {
            onUseCoarseLocation()
            onDismiss()
          },
          modifier = Modifier
            .heightIn(min = 48.dp)
            .testTag("city_picker_use_gps_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.MyLocation,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
          )
          Spacer(Modifier.width(6.dp))
          Text(text = "الموقع التلقائي", maxLines = 1)
        }
      }

      Spacer(Modifier.height(10.dp))

      OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        placeholder = { Text("ابحث باسم المدينة أو الدولة") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("city_picker_search_input"),
      )

      Spacer(Modifier.height(10.dp))

      LazyColumn(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(max = 420.dp),
      ) {
        items(filteredCities, key = { it.id }) { city ->
          val fullName = "${city.arabicName}، ${city.countryArabic}"
          val isSelected = currentCityName == fullName
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 52.dp)
              .clip(RoundedCornerShape(12.dp))
              .clickable {
                onSelectCity(city)
                onDismiss()
              }
              .padding(horizontal = 10.dp, vertical = 8.dp)
              .testTag("city_option_${city.id}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier.weight(1f),
            ) {
              Icon(
                imageVector = Icons.Filled.LocationCity,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
              )
              Spacer(Modifier.width(10.dp))
              Column {
                Text(
                  text = city.arabicName,
                  style = MaterialTheme.typography.titleSmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                  text = city.countryArabic,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
            if (isSelected) {
              Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "المدينة المختارة",
                tint = MaterialTheme.colorScheme.primary,
              )
            }
          }
          HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        }
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrayerSettingsSheet(
  settings: PrayerSettingsState,
  onSelectMethod: (PrayerCalculationMethod) -> Unit,
  onResetMethodToCountryDefault: () -> Unit,
  onSelectMadhab: (AsrMadhab) -> Unit,
  onSelectHighLatitudeRule: (PrayerHighLatitudeRule) -> Unit,
  onAdjustMinutes: (PrayerType, Int) -> Unit,
  onResetAdjustments: () -> Unit,
  onOpenAdhanSettings: (() -> Unit)? = null,
  onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 16.dp)
        .padding(bottom = 28.dp),
    ) {
      Text(
        text = stringResource(R.string.prayer_settings_title),
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
      )
      if (onOpenAdhanSettings != null) {
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
          onClick = {
            onDismiss()
            onOpenAdhanSettings()
          },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("prayer_settings_open_adhan_button"),
        ) {
          Text("إعدادات تنبيهات الأذان والإشعار الدائم")
        }
      }
      Spacer(Modifier.height(14.dp))

      PrayerCalculationSettingsContent(
        settings = settings,
        onSelectMethod = onSelectMethod,
        onResetMethodToCountryDefault = onResetMethodToCountryDefault,
        onSelectMadhab = onSelectMadhab,
        onSelectHighLatitudeRule = onSelectHighLatitudeRule,
        onAdjustMinutes = onAdjustMinutes,
        onResetAdjustments = onResetAdjustments,
      )
    }
  }
}

@Composable
fun PrayerCalculationSettingsContent(
  settings: PrayerSettingsState,
  onSelectMethod: (PrayerCalculationMethod) -> Unit,
  onResetMethodToCountryDefault: () -> Unit,
  onSelectMadhab: (AsrMadhab) -> Unit,
  onSelectHighLatitudeRule: (PrayerHighLatitudeRule) -> Unit,
  onAdjustMinutes: (PrayerType, Int) -> Unit,
  onResetAdjustments: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    // Calculation method section
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.prayer_method_title),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
      )
      if (settings.isMethodUserSelected) {
        TextButton(onClick = onResetMethodToCountryDefault) {
          Text("تلقائي حسب الدولة")
        }
      }
    }

    Spacer(Modifier.height(6.dp))
    PrayerCalculationMethod.entries.forEach { method ->
      val selected = settings.calculationMethod == method
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) {
          MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
          MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        },
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 3.dp)
          .clip(RoundedCornerShape(12.dp))
          .clickable { onSelectMethod(method) }
          .testTag("prayer_method_${method.name}"),
      ) {
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          RadioButton(
            selected = selected,
            onClick = { onSelectMethod(method) },
          )
          Spacer(Modifier.width(6.dp))
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = method.arabicLabel,
              style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            Text(
              text = method.subtitleAr,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(14.dp))

    // Asr juristic method
    Text(
      text = stringResource(R.string.prayer_madhab_title),
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    AsrMadhab.entries.forEach { madhab ->
      val selected = settings.asrMadhab == madhab
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .clip(RoundedCornerShape(10.dp))
          .clickable { onSelectMadhab(madhab) }
          .padding(horizontal = 6.dp)
          .testTag("prayer_madhab_${madhab.name}"),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RadioButton(
          selected = selected,
          onClick = { onSelectMadhab(madhab) },
        )
        Spacer(Modifier.width(6.dp))
        Text(
          text = madhab.arabicLabel,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(14.dp))

    // High-latitude rule
    Text(
      text = stringResource(R.string.prayer_high_lat_title),
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
      color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(6.dp))
    PrayerHighLatitudeRule.entries.forEach { rule ->
      val selected = settings.highLatitudeRule == rule
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 48.dp)
          .clip(RoundedCornerShape(10.dp))
          .clickable { onSelectHighLatitudeRule(rule) }
          .padding(horizontal = 6.dp)
          .testTag("prayer_high_lat_${rule.name}"),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RadioButton(
          selected = selected,
          onClick = { onSelectHighLatitudeRule(rule) },
        )
        Spacer(Modifier.width(6.dp))
        Text(
          text = rule.arabicLabel,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    Spacer(Modifier.height(14.dp))

    // Manual +/- minutes adjustment per prayer
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = stringResource(R.string.prayer_adjustments_title),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.primary,
      )
      TextButton(onClick = onResetAdjustments) {
        Text("إعادة تعيين")
      }
    }
    Spacer(Modifier.height(6.dp))

    PrayerType.entries.forEach { prayerType ->
      val offset = settings.adjustments.forPrayer(prayerType)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 50.dp)
          .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = prayerType.arabicName,
          style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
          color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
          FilledTonalIconButton(
            onClick = { onAdjustMinutes(prayerType, -1) },
            modifier = Modifier
              .size(48.dp)
              .testTag("prayer_adj_minus_${prayerType.key}"),
          ) {
            Icon(
              imageVector = Icons.Filled.Remove,
              contentDescription = "إنقاص دقيقة لـ ${prayerType.arabicName}",
            )
          }
          Text(
            text = "${PrayerFormatters.formatSignedMinutesArabic(offset)} د",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = if (offset == 0) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 10.dp),
          )
          FilledTonalIconButton(
            onClick = { onAdjustMinutes(prayerType, 1) },
            modifier = Modifier
              .size(48.dp)
              .testTag("prayer_adj_plus_${prayerType.key}"),
          ) {
            Icon(
              imageVector = Icons.Filled.Add,
              contentDescription = "زيادة دقيقة لـ ${prayerType.arabicName}",
            )
          }
        }
      }
    }
  }
}
