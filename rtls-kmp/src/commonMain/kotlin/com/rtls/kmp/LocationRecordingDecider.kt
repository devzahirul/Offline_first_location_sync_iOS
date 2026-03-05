package com.rtls.kmp

import kotlin.math.*

/**
 * Matches iOS RTLSCore.LocationRecordingDecider: filters raw location updates
 * so only meaningful changes get inserted into SQLite and trigger sync.
 * Without this, every FusedLocationProvider fix (potentially every second) hits the DB.
 */
class LocationRecordingDecider(
    private val minTimeIntervalMs: Long = 0L,
    private val minDistanceMeters: Double = 0.0,
    private val maxAcceptableAccuracy: Double = 0.0
) {
    private var lastRecordedAtMs: Long? = null
    private var lastLat: Double? = null
    private var lastLng: Double? = null

    fun shouldRecord(point: LocationPoint): Boolean {
        if (maxAcceptableAccuracy > 0.0) {
            val acc = point.horizontalAccuracy
            if (acc != null && acc > maxAcceptableAccuracy) return false
        }

        val prevTime = lastRecordedAtMs
        val prevLat = lastLat
        val prevLng = lastLng

        if (prevTime == null || prevLat == null || prevLng == null) return true

        if (minTimeIntervalMs > 0 && point.recordedAtMs - prevTime < minTimeIntervalMs) {
            return false
        }

        if (minDistanceMeters > 0.0) {
            val dist = haversineMeters(prevLat, prevLng, point.lat, point.lng)
            if (dist < minDistanceMeters) return false
        }

        return true
    }

    fun markRecorded(point: LocationPoint) {
        lastRecordedAtMs = point.recordedAtMs
        lastLat = point.lat
        lastLng = point.lng
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
