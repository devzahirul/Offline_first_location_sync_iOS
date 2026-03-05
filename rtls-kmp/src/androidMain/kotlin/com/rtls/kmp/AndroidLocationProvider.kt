package com.rtls.kmp

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import java.util.UUID

data class LocationRequestParams(
    val intervalMillis: Long = 10_000L,
    val minUpdateIntervalMillis: Long = 10_000L,
    val minUpdateDistanceMeters: Float = 10f,
    /** Hardware-level batching: buffer updates for up to this long before waking the CPU.
     *  0 = deliver immediately (no batching). Higher = less CPU wake-ups = major power saving.
     *  Recommended: 2-5x intervalMillis (e.g. 30_000-60_000 for 10s interval). */
    val maxUpdateDelayMillis: Long = 0L,
    /** Use balanced power accuracy (WiFi/cell) instead of high accuracy (GPS).
     *  Sufficient for most sync use cases; saves significant battery. */
    val useBalancedPowerAccuracy: Boolean = false,
    /** Reject locations with horizontal accuracy worse than this (meters). 0 = no filter. */
    val maxAcceptableAccuracyMeters: Float = 0f
)

class AndroidLocationProvider(private val context: Context) {

    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    fun locationFlow(
        userId: String,
        deviceId: String,
        params: LocationRequestParams = LocationRequestParams()
    ): Flow<LocationPoint> = callbackFlow {
        val useLocationManagerFallback = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
        if (useLocationManagerFallback) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager != null) {
                val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                    LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
                try {
                    val last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (last != null) trySend(last.toLocationPoint(userId, deviceId))

                    val listener = LocationListener { loc -> trySend(loc.toLocationPoint(userId, deviceId)) }
                    withContext(Dispatchers.Main) {
                        locationManager.requestLocationUpdates(
                            provider, params.intervalMillis, params.minUpdateDistanceMeters,
                            listener, Looper.getMainLooper()
                        )
                        if (provider == LocationManager.GPS_PROVIDER && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                            locationManager.requestLocationUpdates(
                                LocationManager.NETWORK_PROVIDER, params.intervalMillis, params.minUpdateDistanceMeters,
                                listener, Looper.getMainLooper()
                            )
                        }
                    }
                    awaitClose { locationManager.removeUpdates(listener) }
                } catch (e: SecurityException) {
                    close(IllegalStateException("Location permission not granted.", e))
                }
            } else {
                close(IllegalStateException("LocationManager not available"))
            }
            return@callbackFlow
        }

        val last = suspendCancellableCoroutine<Location?> { cont ->
            client.lastLocation.addOnCompleteListener { task -> cont.resume(task.result) }
        }
        last?.let { trySend(it.toLocationPoint(userId, deviceId)) }

        val priority = if (params.useBalancedPowerAccuracy)
            Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY
        val request = LocationRequest.Builder(priority, params.intervalMillis)
            .setMinUpdateIntervalMillis(params.minUpdateIntervalMillis)
            .setMinUpdateDistanceMeters(params.minUpdateDistanceMeters)
            .apply {
                if (params.maxUpdateDelayMillis > 0) {
                    setMaxUpdateDelayMillis(params.maxUpdateDelayMillis)
                }
            }
            .build()

        val maxAcc = params.maxAcceptableAccuracyMeters
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val locations = result.locations
                if (!locations.isNullOrEmpty()) {
                    for (loc in locations) {
                        if (maxAcc > 0f && loc.hasAccuracy() && loc.accuracy > maxAcc) continue
                        trySend(loc.toLocationPoint(userId, deviceId))
                    }
                } else {
                    result.lastLocation?.let { loc ->
                        if (maxAcc <= 0f || !loc.hasAccuracy() || loc.accuracy <= maxAcc) {
                            trySend(loc.toLocationPoint(userId, deviceId))
                        }
                    }
                }
            }
        }

        var useFused = true
        try {
            withContext(Dispatchers.Main) {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            close(IllegalStateException("Location permission not granted.", e))
            return@callbackFlow
        } catch (_: Exception) {
            useFused = false
        }

        if (!useFused) {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager == null) {
                close(IllegalStateException("LocationManager not available"))
                return@callbackFlow
            }
            val provider = if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                LocationManager.GPS_PROVIDER else LocationManager.NETWORK_PROVIDER
            val listener = LocationListener { loc -> trySend(loc.toLocationPoint(userId, deviceId)) }
            try {
                withContext(Dispatchers.Main) {
                    locationManager.requestLocationUpdates(
                        provider, params.intervalMillis, params.minUpdateDistanceMeters,
                        listener, Looper.getMainLooper()
                    )
                }
                awaitClose { locationManager.removeUpdates(listener) }
            } catch (e: SecurityException) {
                close(IllegalStateException("Location permission not granted.", e))
            }
            return@callbackFlow
        }

        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun Location.toLocationPoint(userId: String, deviceId: String): LocationPoint =
        LocationPoint(
            id = UUID.randomUUID().toString(),
            userId = userId,
            deviceId = deviceId,
            recordedAtMs = if (time > 0L) time else System.currentTimeMillis(),
            lat = latitude,
            lng = longitude,
            horizontalAccuracy = if (hasAccuracy()) accuracy.toDouble() else null,
            verticalAccuracy = null,
            altitude = if (hasAltitude()) altitude.toDouble() else null,
            speed = if (hasSpeed()) speed.toDouble().takeIf { it >= 0 } else null,
            course = if (hasBearing()) bearing.toDouble().takeIf { it >= 0 } else null
        )
}
