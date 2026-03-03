package com.rtls.reactnative

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.rtls.kmp.BatchingPolicy
import com.rtls.kmp.LocationPoint
import com.rtls.kmp.LocationRequestParams
import com.rtls.kmp.LocationSyncClient
import com.rtls.kmp.LocationSyncClientEvent
import com.rtls.kmp.RTLSKmp
import com.rtls.kmp.SyncEngineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EVENT_RECORDED = "rtls_recorded"
private const val EVENT_SYNC_EVENT = "rtls_syncEvent"
private const val EVENT_ERROR = "rtls_error"
private const val EVENT_AUTHORIZATION_CHANGED = "rtls_authorizationChanged"
private const val EVENT_TRACKING_STARTED = "rtls_trackingStarted"
private const val EVENT_TRACKING_STOPPED = "rtls_trackingStopped"

class RTLSyncModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "RTLSyncModule"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var client: LocationSyncClient? = null
    private var lastUserId: String? = null
    private var lastDeviceId: String? = null
    private var eventsJob: kotlinx.coroutines.Job? = null
    private var locationRequestParams: LocationRequestParams = LocationRequestParams()

    private fun emit(name: String, params: WritableMap?) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            ?.emit(name, params ?: Arguments.createMap())
    }

    private fun pointToMap(point: LocationPoint): WritableMap {
        val map = Arguments.createMap()
        map.putString("id", point.id)
        map.putString("userId", point.userId)
        map.putString("deviceId", point.deviceId)
        map.putDouble("recordedAt", point.recordedAtMs.toDouble())
        map.putDouble("lat", point.lat)
        map.putDouble("lng", point.lng)
        point.horizontalAccuracy?.let { map.putDouble("horizontalAccuracy", it) }
        point.verticalAccuracy?.let { map.putDouble("verticalAccuracy", it) }
        point.altitude?.let { map.putDouble("altitude", it) }
        point.speed?.let { map.putDouble("speed", it) }
        point.course?.let { map.putDouble("course", it) }
        return map
    }

    private fun startEventCollection(c: LocationSyncClient) {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            c.events.collectLatest { event ->
                when (event) {
                    is LocationSyncClientEvent.Recorded -> {
                        emit(EVENT_RECORDED, pointToMap(event.point))
                    }
                    is LocationSyncClientEvent.SyncEvent -> {
                        val payload = Arguments.createMap()
                        when (val e = event.event) {
                            is SyncEngineEvent.UploadSuccess -> {
                                payload.putString("type", "uploadSucceeded")
                                payload.putInt("accepted", e.accepted)
                                payload.putInt("rejected", e.rejected)
                            }
                            is SyncEngineEvent.UploadFailed -> {
                                payload.putString("type", "uploadFailed")
                                payload.putString("message", e.message)
                            }
                            else -> payload.putString("type", "unknown")
                        }
                        emit(EVENT_SYNC_EVENT, payload)
                    }
                    is LocationSyncClientEvent.Error -> {
                        val payload = Arguments.createMap()
                        payload.putString("message", event.message)
                        emit(EVENT_ERROR, payload)
                    }
                    LocationSyncClientEvent.TrackingStarted -> emit(EVENT_TRACKING_STARTED, Arguments.createMap())
                    LocationSyncClientEvent.TrackingStopped -> emit(EVENT_TRACKING_STOPPED, Arguments.createMap())
                }
            }
        }
    }

    @ReactMethod
    fun configure(config: com.facebook.react.bridge.ReadableMap, promise: Promise) {
        try {
            val baseURL = config.getString("baseURL") ?: run {
                promise.reject("RTLSync", "configure requires baseURL", null)
                return
            }
            val userId = config.getString("userId") ?: run {
                promise.reject("RTLSync", "configure requires userId", null)
                return
            }
            val deviceId = config.getString("deviceId") ?: run {
                promise.reject("RTLSync", "configure requires deviceId", null)
                return
            }
            val accessToken = config.getString("accessToken") ?: run {
                promise.reject("RTLSync", "configure requires accessToken", null)
                return
            }

            val batchMaxSize = if (config.hasKey("batchMaxSize")) config.getInt("batchMaxSize") else null
            val flushIntervalSec = if (config.hasKey("flushIntervalSeconds")) config.getDouble("flushIntervalSeconds") else null
            val maxBatchAgeSec = if (config.hasKey("maxBatchAgeSeconds")) config.getDouble("maxBatchAgeSeconds") else null
            val batchingPolicy = BatchingPolicy(
                maxBatchSize = batchMaxSize?.coerceAtLeast(1) ?: 50,
                flushIntervalSeconds = (flushIntervalSec?.toLong()?.coerceAtLeast(1)) ?: 10L,
                maxBatchAgeSeconds = (maxBatchAgeSec?.toLong()?.coerceAtLeast(0)) ?: 60L
            )

            val intervalSec = if (config.hasKey("locationIntervalSeconds")) config.getDouble("locationIntervalSeconds") else null
            val distanceM = if (config.hasKey("locationDistanceMeters")) config.getDouble("locationDistanceMeters") else null
            val significantOnly = if (config.hasKey("useSignificantLocationOnly")) config.getBoolean("useSignificantLocationOnly") else false
            locationRequestParams = when {
                significantOnly -> LocationRequestParams(
                    intervalMillis = 60_000L,
                    minUpdateIntervalMillis = 60_000L,
                    minUpdateDistanceMeters = 500f
                )
                intervalSec != null && intervalSec > 0 -> {
                    val ms = (intervalSec * 1000).toLong().coerceAtLeast(5_000L)
                    LocationRequestParams(intervalMillis = ms, minUpdateIntervalMillis = ms, minUpdateDistanceMeters = 0f)
                }
                distanceM != null && distanceM > 0 -> LocationRequestParams(
                    intervalMillis = 10_000L,
                    minUpdateIntervalMillis = 5_000L,
                    minUpdateDistanceMeters = distanceM.toFloat().coerceAtLeast(1f)
                )
                else -> LocationRequestParams()
            }

            val ctx = reactApplicationContext
            client?.stopTracking()
            lastUserId = userId
            lastDeviceId = deviceId
            val c = RTLSKmp.createLocationSyncClient(
                context = ctx,
                baseUrl = baseURL,
                userId = userId,
                deviceId = deviceId,
                accessToken = accessToken,
                scope = scope,
                batchingPolicy = batchingPolicy
            )
            client = c
            startEventCollection(c)
            promise.resolve(null)
        } catch (e: Exception) {
            Log.e("RTLSyncModule", "configure failed", e)
            promise.reject("RTLSync", e.message, e)
        }
    }

    @ReactMethod
    fun startTracking(promise: Promise) {
        val ctx = reactApplicationContext
        val c = client
        if (c == null) {
            promise.reject("RTLSync", "Call configure first", null)
            return
        }
        val userId = lastUserId ?: run {
            promise.reject("RTLSync", "Configure first", null)
            return
        }
        val deviceId = lastDeviceId ?: run {
            promise.reject("RTLSync", "Configure first", null)
            return
        }
        if (!hasLocationPermission(ctx)) {
            promise.reject("LOCATION_PERMISSION_DENIED", "Location permission not granted. Call requestAlwaysAuthorization() first.", null)
            return
        }
        try {
            val flow = RTLSKmp.createLocationFlow(ctx, userId, deviceId, locationRequestParams)
            c.startCollectingLocation(flow)
            promise.resolve(null)
        } catch (e: Exception) {
            Log.e("RTLSyncModule", "startTracking failed", e)
            promise.reject("RTLSync", e.message, e)
        }
    }

    @ReactMethod
    fun stopTracking(promise: Promise) {
        try {
            client?.stopTracking()
            promise.resolve(null)
        } catch (e: Exception) {
            promise.reject("RTLSync", e.message, e)
        }
    }

    @ReactMethod
    fun requestAlwaysAuthorization(promise: Promise) {
        val activity = currentActivity
        if (activity == null) {
            promise.reject("NO_ACTIVITY", "Activity not available to request permission", null)
            return
        }
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), REQUEST_CODE_LOCATION)
        promise.resolve(null)
    }

    @ReactMethod
    fun getStats(promise: Promise) {
        val c = client
        if (c == null) {
            promise.reject("RTLSync", "Call configure first", null)
            return
        }
        scope.launch {
            try {
                val stats = c.stats()
                withContext(Dispatchers.Main) {
                    val map = Arguments.createMap()
                    map.putInt("pendingCount", stats.pendingCount)
                    if (stats.oldestPendingRecordedAtMs != null) {
                        map.putDouble("oldestPendingRecordedAt", stats.oldestPendingRecordedAtMs.toDouble())
                    } else {
                        map.putNull("oldestPendingRecordedAt")
                    }
                    promise.resolve(map)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("RTLSync", e.message, e)
                }
            }
        }
    }

    @ReactMethod
    fun flushNow(promise: Promise) {
        val c = client
        if (c == null) {
            promise.reject("RTLSync", "Call configure first", null)
            return
        }
        scope.launch {
            try {
                c.flushNow()
                withContext(Dispatchers.Main) { promise.resolve(null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("RTLSync", e.message, e)
                }
            }
        }
    }

    override fun invalidate() {
        eventsJob?.cancel()
        client?.stopTracking()
        client = null
        scope.cancel()
        super.invalidate()
    }

    private fun hasLocationPermission(ctx: android.content.Context): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val REQUEST_CODE_LOCATION = 0x2a03
    }
}
