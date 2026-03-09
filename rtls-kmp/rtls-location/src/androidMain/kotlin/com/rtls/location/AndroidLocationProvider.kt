package com.rtls.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import com.google.android.gms.location.*
import com.rtls.core.LocationPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.resume

data class LocationRequestParams(
    val intervalMillis: Long = 10_000L,
    val minUpdateIntervalMillis: Long = 10_000L,
    val minUpdateDistanceMeters: Float = 10f,
    /** Enable HW batching: 30s default lets the GPS chip batch updates, saving CPU wakes. */
    val maxUpdateDelayMillis: Long = 30_000L,
    /** Default to balanced power: uses WiFi/cell + GPS only when needed. */
    val useBalancedPowerAccuracy: Boolean = true,
    val maxAcceptableAccuracyMeters: Float = 0f
)

class AndroidLocationProvider(private val context: Context) : LocationSource {

    private val fusedClient: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    override fun locationFlow(userId: String, deviceId: String): Flow<LocationPoint> =
        locationFlow(userId, deviceId, LocationRequestParams())

    @SuppressLint("MissingPermission")
    fun locationFlow(
        userId: String,
        deviceId: String,
        params: LocationRequestParams
    ): Flow<LocationPoint> = callbackFlow {
        try {
            val last = withContext(Dispatchers.IO) {
                suspendCancellableCoroutine<Location?> { cont ->
                    fusedClient.lastLocation
                        .addOnSuccessListener { cont.resume(it) }
                        .addOnFailureListener { cont.resume(null) }
                }
            }
            last?.let { trySend(it.toLocationPoint(userId, deviceId)) }
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val priority = if (params.useBalancedPowerAccuracy)
                Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY
            val request = LocationRequest.Builder(priority, params.intervalMillis)
                .setMinUpdateIntervalMillis(params.minUpdateIntervalMillis)
                .setMinUpdateDistanceMeters(params.minUpdateDistanceMeters)
                .apply {
                    if (params.maxUpdateDelayMillis > 0) setMaxUpdateDelayMillis(params.maxUpdateDelayMillis)
                }
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (loc in result.locations) {
                        if (params.maxAcceptableAccuracyMeters > 0f && loc.hasAccuracy() && loc.accuracy > params.maxAcceptableAccuracyMeters) continue
                        trySend(loc.toLocationPoint(userId, deviceId))
                    }
                }
            }
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            awaitClose { fusedClient.removeLocationUpdates(callback) }
        } else {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val provider = LocationManager.GPS_PROVIDER
            val listener = object : LocationListener {
                override fun onLocationChanged(loc: Location) {
                    if (params.maxAcceptableAccuracyMeters > 0f && loc.hasAccuracy() && loc.accuracy > params.maxAcceptableAccuracyMeters) return
                    trySend(loc.toLocationPoint(userId, deviceId))
                }
                @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {}
            }
            lm.requestLocationUpdates(provider, params.intervalMillis, params.minUpdateDistanceMeters, listener, Looper.getMainLooper())
            awaitClose { lm.removeUpdates(listener) }
        }
    }

    private fun Location.toLocationPoint(userId: String, deviceId: String) = LocationPoint(
        id = UUID.randomUUID().toString(),
        userId = userId,
        deviceId = deviceId,
        recordedAtMs = if (time > 0L) time else System.currentTimeMillis(),
        lat = latitude,
        lng = longitude,
        horizontalAccuracy = if (hasAccuracy()) accuracy.toDouble() else null,
        verticalAccuracy = null,
        altitude = if (hasAltitude()) altitude else null,
        speed = if (hasSpeed()) speed.toDouble().takeIf { it >= 0 } else null,
        course = if (hasBearing()) bearing.toDouble().takeIf { it >= 0 } else null
    )
}
