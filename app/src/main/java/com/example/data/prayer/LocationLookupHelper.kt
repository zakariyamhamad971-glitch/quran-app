package com.example.data.prayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.resume

sealed interface LocationLookupResult {
  data class Success(val location: SavedPrayerLocation) : LocationLookupResult
  data object PermissionDenied : LocationLookupResult
  data object Unavailable : LocationLookupResult
}

class LocationLookupHelper(private val context: Context) {

  fun hasCoarseLocationPermission(): Boolean {
    return hasLocationPermission()
  }

  fun hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
      context,
      Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
  }

  @SuppressLint("MissingPermission")
  suspend fun fetchCoarseLocation(): LocationLookupResult = withContext(Dispatchers.IO) {
    if (!hasLocationPermission()) {
      return@withContext LocationLookupResult.PermissionDenied
    }

    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
      ?: return@withContext LocationLookupResult.Unavailable

    val location = requestFreshCoarseLocation(lm) ?: pickBestLastKnownLocation(lm)
      ?: return@withContext LocationLookupResult.Unavailable

    val timeZoneId = TimeZone.getDefault().id
    val geocoded = reverseGeocodeOfflineSafe(location.latitude, location.longitude)
    val countryCode = geocoded?.countryCode?.takeIf { it.isNotBlank() }
      ?: inferCountryCodeOffline(location.latitude, location.longitude, timeZoneId)

    val displayName = geocoded?.displayName?.takeIf { it.isNotBlank() }.orEmpty()

    LocationLookupResult.Success(
      SavedPrayerLocation(
        latitude = location.latitude,
        longitude = location.longitude,
        displayName = displayName,
        countryCode = countryCode,
        timeZoneId = timeZoneId,
        isManual = false,
      ),
    )
  }

  @SuppressLint("MissingPermission")
  private suspend fun requestFreshCoarseLocation(lm: LocationManager): Location? {
    val providers = buildList {
      if (runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
        add(LocationManager.GPS_PROVIDER)
      }
      if (runCatching { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
        add(LocationManager.NETWORK_PROVIDER)
      }
      if (runCatching { lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) }.getOrDefault(false)) {
        add(LocationManager.PASSIVE_PROVIDER)
      }
    }
    for (provider in providers) {
      val loc = withTimeoutOrNull(4500L) {
        suspendCancellableCoroutine<Location?> { cont ->
          val signal = CancellationSignal()
          cont.invokeOnCancellation { signal.cancel() }
          runCatching {
            LocationManagerCompat.getCurrentLocation(
              lm,
              provider,
              signal,
              ContextCompat.getMainExecutor(context),
            ) { result ->
              if (cont.isActive) cont.resume(result)
            }
          }.onFailure {
            if (cont.isActive) cont.resume(null)
          }
        }
      }
      if (loc != null) return loc
    }
    return null
  }

  @SuppressLint("MissingPermission")
  private fun pickBestLastKnownLocation(lm: LocationManager): Location? {
    val candidates = listOf(
      LocationManager.GPS_PROVIDER,
      LocationManager.NETWORK_PROVIDER,
      LocationManager.PASSIVE_PROVIDER,
    )
    return candidates
      .mapNotNull { provider -> runCatching { lm.getLastKnownLocation(provider) }.getOrNull() }
      .maxByOrNull { it.time }
  }

  private data class ReverseGeocodedInfo(
    val displayName: String,
    val countryCode: String?,
  )

  private suspend fun reverseGeocodeOfflineSafe(
    latitude: Double,
    longitude: Double,
  ): ReverseGeocodedInfo? {
    if (!Geocoder.isPresent()) return null
    return withTimeoutOrNull(2000L) {
      val geocoder = Geocoder(context, Locale.forLanguageTag("ar"))
      val address: Address? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        suspendCancellableCoroutine { cont ->
          runCatching {
            geocoder.getFromLocation(latitude, longitude, 1) { list ->
              if (cont.isActive) cont.resume(list.firstOrNull())
            }
          }.onFailure {
            if (cont.isActive) cont.resume(null)
          }
        }
      } else {
        @Suppress("DEPRECATION")
        runCatching {
          geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
        }.getOrNull()
      }
      address?.let { addr ->
        val city = addr.locality ?: addr.subAdminArea ?: addr.adminArea
        val country = addr.countryName
        val label = listOfNotNull(
          city?.takeIf { it.isNotBlank() },
          country?.takeIf { it.isNotBlank() },
        ).distinct().joinToString("، ")
        if (label.isNotBlank() || !addr.countryCode.isNullOrBlank()) {
          ReverseGeocodedInfo(
            displayName = label,
            countryCode = addr.countryCode,
          )
        } else {
          null
        }
      }
    }
  }

  private fun inferCountryCodeOffline(
    latitude: Double,
    longitude: Double,
    timeZoneId: String,
  ): String? {
    if (timeZoneId == "Africa/Cairo") return "EG"
    if (latitude in 22.0..31.7 && longitude in 24.7..36.9) return "EG"
    val localeCountry = Locale.getDefault().country
    return localeCountry.takeIf { it.length == 2 }
  }
}
