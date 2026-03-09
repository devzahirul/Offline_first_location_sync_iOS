package com.rtls.location

import com.rtls.core.LocationPoint
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.*

/**
 * Thread-safe location recording decider with mutex-protected state.
 * Prevents race conditions when accessed from multiple coroutines.
 */
class LocationRecordingDecider(
    private val minTimeIntervalMs: Long = 0L,
    private val minDistanceMeters: Double = 0.0,
    private val maxAcceptableAccuracy: Double = 0.0
) {
    private val stateMutex = Mutex()
    private var lastRecordedState: RecordedState? = null

    private data class RecordedState(
        val timestampMs: Long,
        val lat: Double,
        val lng: Double
    )

    /**
     * Thread-safe check if a point should be recorded.
     */
    suspend fun shouldRecord(point: LocationPoint): Boolean = stateMutex.withLock {
        if (maxAcceptableAccuracy > 0.0) {
            val acc = point.horizontalAccuracy
            if (acc != null && acc > maxAcceptableAccuracy) return@withLock false
        }
        val state = lastRecordedState
        if (state == null) return@withLock true
        if (minTimeIntervalMs > 0 && point.recordedAtMs - state.timestampMs < minTimeIntervalMs) return@withLock false
        if (minDistanceMeters > 0.0) {
            val dist = haversineMeters(state.lat, state.lng, point.lat, point.lng)
            if (dist < minDistanceMeters) return@withLock false
        }
        true
    }

    /**
     * Thread-safe mark a point as recorded.
     */
    suspend fun markRecorded(point: LocationPoint) = stateMutex.withLock {
        lastRecordedState = RecordedState(point.recordedAtMs, point.lat, point.lng)
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0
        fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLng / 2).pow(2)
            return EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
        }
    }
}
