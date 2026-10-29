package com.ramish.sadiq.ui.guardtab

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import com.ramish.sadiq.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

data class OneShotLocationResult(
    val latitude: Double,
    val longitude: Double,
    val placeName: String = "Unknown"
)

/**
 * One location fix for "Use my location once".
 *
 * The app has no internet permission and no Play Services dependency, so the fix comes from the
 * platform [LocationManager]: the last known reading when there is one, otherwise one live
 * `getCurrentLocation` call that is cancelled with the caller. The place name is a best-effort
 * reverse geocode; when it fails the coordinates are still applied.
 *
 * Only `ACCESS_COARSE_LOCATION` is declared, so a provider that needs fine accuracy answers with a
 * `SecurityException`; that provider is skipped and the next one is tried.
 */
@Singleton
class OneShotLocation @Inject constructor(
    @ApplicationContext private val context: Context
) {

    @SuppressLint("MissingPermission")
    suspend fun requestLocation(): OneShotLocationResult? {
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider ->
                runCatching { locationManager.isProviderEnabled(provider) }.getOrDefault(false)
            }

        if (providers.isEmpty()) {
            // CONTRACT-GAP: no route to the OS location settings page from this screen yet.
            return null
        }

        val cached = providers
            .mapNotNull { provider ->
                runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }

        val location = cached ?: withTimeoutOrNull(TIMEOUT_MS) {
            providers.firstNotNullOfOrNull { provider -> currentLocation(locationManager, provider) }
        }

        return location?.let { fix ->
            OneShotLocationResult(
                latitude = fix.latitude,
                longitude = fix.longitude,
                placeName = placeName(fix)
            )
        }
    }

    /** One live fix from [provider], cancelled with the coroutine. */
    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(
        locationManager: LocationManager,
        provider: String
    ): Location? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            locationManager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                if (continuation.isActive) continuation.resume(location)
            }
        } catch (_: SecurityException) {
            if (continuation.isActive) continuation.resume(null)
        } catch (_: IllegalArgumentException) {
            // The provider vanished between the enabled check and the request.
            if (continuation.isActive) continuation.resume(null)
        }
    }

    /** A short, human place name for [location], or the generic label when geocoding fails. */
    private suspend fun placeName(location: Location): String = withContext(Dispatchers.IO) {
        val resolved = runCatching {
            @Suppress("DEPRECATION")
            Geocoder(context, Locale.getDefault())
                .getFromLocation(location.latitude, location.longitude, 1)
                ?.firstOrNull()
                ?.let { address ->
                    address.locality ?: address.subAdminArea ?: address.adminArea
                }
        }.getOrNull()

        resolved ?: context.getString(R.string.prayer_settings_place_unknown)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
