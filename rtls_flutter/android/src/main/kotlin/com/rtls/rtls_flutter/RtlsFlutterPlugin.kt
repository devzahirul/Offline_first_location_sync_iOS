package com.rtls.rtls_flutter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.PluginRegistry
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rtls.kmp.BatchingPolicy
import com.rtls.kmp.LocationRequestParams
import com.rtls.kmp.LocationSyncClientEvent
import com.rtls.kmp.RTLSKmp
import androidx.core.app.ActivityCompat

class RtlsFlutterPlugin : FlutterPlugin, MethodChannel.MethodCallHandler, EventChannel.StreamHandler, ActivityAware {

    private var channel: MethodChannel? = null
    private var eventChannel: EventChannel? = null
    private var eventSink: EventChannel.EventSink? = null
    private var client: com.rtls.kmp.LocationSyncClient? = null
    private var context: Context? = null
    private var activityBinding: ActivityPluginBinding? = null
    private var lastUserId: String? = null
    private var lastDeviceId: String? = null
    private var locationRequestParams: LocationRequestParams = LocationRequestParams()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var eventCollectionJob: Job? = null

    companion object {
        private const val REQUEST_CODE_LOCATION = 0x2a02
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        channel = MethodChannel(binding.binaryMessenger, "com.rtls.flutter/rtls")
        channel!!.setMethodCallHandler(this)
        eventChannel = EventChannel(binding.binaryMessenger, "com.rtls.flutter/rtls_events")
        eventChannel!!.setStreamHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        channel = null
        eventChannel?.setStreamHandler(null)
        eventChannel = null
        client?.stopTracking()
        client = null
        context = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activityBinding = binding
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activityBinding = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activityBinding = binding
    }

    override fun onDetachedFromActivity() {
        activityBinding = null
    }

    private fun hasLocationPermission(ctx: Context): Boolean {
        return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestLocationPermissions(result: Result) {
        val activity = activityBinding?.activity ?: run {
            result.error("NO_ACTIVITY", "Activity not available to request permission", null)
            return
        }
        val ctx = context ?: run {
            result.error("NO_CONTEXT", "Context not available", null)
            return
        }
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        val binding = activityBinding!!
        val listener = object : PluginRegistry.RequestPermissionsResultListener {
            override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean {
                if (requestCode != REQUEST_CODE_LOCATION) return false
                binding.removeRequestPermissionsResultListener(this)
                val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                result.success(granted)
                return true
            }
        }
        binding.addRequestPermissionsResultListener(listener)
        ActivityCompat.requestPermissions(activity, permissions.toTypedArray(), REQUEST_CODE_LOCATION)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "configure" -> {
                val baseUrl = call.argument<String>("baseUrl") ?: ""
                val userId = call.argument<String>("userId") ?: ""
                val deviceId = call.argument<String>("deviceId") ?: ""
                val accessToken = call.argument<String>("accessToken") ?: ""
                val intervalSec = call.argument<Number>("locationIntervalSeconds")?.toDouble()
                val distanceM = call.argument<Number>("locationDistanceMeters")?.toDouble()
                val significantOnly = call.argument<Boolean>("useSignificantLocationOnly") ?: false
                val batchMaxSize = call.argument<Number>("batchMaxSize")?.toInt()
                val flushIntervalSec = call.argument<Number>("flushIntervalSeconds")?.toDouble()
                val maxBatchAgeSec = call.argument<Number>("maxBatchAgeSeconds")?.toDouble()
                val batchingPolicy = BatchingPolicy(
                    maxBatchSize = batchMaxSize?.coerceAtLeast(1) ?: 50,
                    flushIntervalSeconds = (flushIntervalSec?.toLong()?.coerceAtLeast(1)) ?: 10L,
                    maxBatchAgeSeconds = (maxBatchAgeSec?.toLong()?.coerceAtLeast(0)) ?: 60L
                )
                val ctx = context ?: run {
                    result.error("NO_CONTEXT", "Context not available", null)
                    return
                }
                client?.stopTracking()
                lastUserId = userId
                lastDeviceId = deviceId
                locationRequestParams = when {
                    significantOnly -> LocationRequestParams(
                        intervalMillis = 60_000L,
                        minUpdateIntervalMillis = 60_000L,
                        minUpdateDistanceMeters = 500f
                    )
                    intervalSec != null && intervalSec > 0 -> {
                        val ms = (intervalSec * 1000).toLong().coerceAtLeast(5_000L)
                        LocationRequestParams(
                            intervalMillis = ms,
                            minUpdateIntervalMillis = ms,
                            minUpdateDistanceMeters = 0f
                        )
                    }
                    distanceM != null && distanceM > 0 -> LocationRequestParams(
                        intervalMillis = 10_000L,
                        minUpdateIntervalMillis = 5_000L,
                        minUpdateDistanceMeters = distanceM.toFloat().coerceAtLeast(1f)
                    )
                    else -> LocationRequestParams()
                }
                client = RTLSKmp.createLocationSyncClient(
                    context = ctx,
                    baseUrl = baseUrl,
                    userId = userId,
                    deviceId = deviceId,
                    accessToken = accessToken,
                    scope = scope,
                    batchingPolicy = batchingPolicy
                )
                startEventCollectionIfReady()
                result.success(null)
            }
            "startTracking" -> {
                val ctx = context ?: run {
                    result.error("NO_CONTEXT", "Context not available", null)
                    return
                }
                if (!hasLocationPermission(ctx)) {
                    result.error(
                        "LOCATION_PERMISSION_DENIED",
                        "Location permission not granted. Call requestAlwaysAuthorization() first and grant permission.",
                        null
                    )
                    return
                }
                val c = client
                if (c == null) {
                    result.error("NOT_CONFIGURED", "Call configure first", null)
                    return
                }
                val userId = lastUserId ?: return result.error("INVALID", "Configure first", null)
                val deviceId = lastDeviceId ?: return result.error("INVALID", "Configure first", null)
                RtlsLocationForegroundService.start(ctx)
                val flow = RTLSKmp.createLocationFlow(ctx, userId, deviceId, locationRequestParams)
                c.startCollectingLocation(flow)
                result.success(null)
            }
            "stopTracking" -> {
                context?.let { RtlsLocationForegroundService.stop(it) }
                client?.stopTracking()
                result.success(null)
            }
            "requestAlwaysAuthorization" -> requestLocationPermissions(result)
            "getStats" -> {
                scope.launch {
                    val stats = try {
                        client?.stats()
                    } catch (e: Exception) {
                        null
                    }
                    withContext(Dispatchers.Main) {
                        if (stats != null) {
                            result.success(mapOf(
                                "pendingCount" to stats.pendingCount,
                                "oldestPendingRecordedAtMs" to (stats.oldestPendingRecordedAtMs ?: 0)
                            ))
                        } else {
                            result.success(mapOf("pendingCount" to -1, "oldestPendingRecordedAtMs" to null))
                        }
                    }
                }
            }
            "flushNow" -> {
                scope.launch {
                    try {
                        client?.flushNow()
                        withContext(Dispatchers.Main) { result.success(null) }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { result.error("FLUSH_FAILED", e.message, null) }
                    }
                }
            }
            else -> result.notImplemented()
        }
    }

    private fun startEventCollectionIfReady() {
        eventCollectionJob?.cancel()
        eventCollectionJob = null
        val c = client ?: return
        val sink = eventSink ?: return
        eventCollectionJob = scope.launch {
            c.events.collectLatest { event ->
                val map: Map<String, Any?> = when (event) {
                    is LocationSyncClientEvent.Recorded -> mapOf(
                        "type" to "recorded",
                        "point" to mapOf(
                            "id" to event.point.id,
                            "userId" to event.point.userId,
                            "deviceId" to event.point.deviceId,
                            "recordedAtMs" to event.point.recordedAtMs,
                            "lat" to event.point.lat,
                            "lng" to event.point.lng,
                            "horizontalAccuracy" to event.point.horizontalAccuracy,
                            "verticalAccuracy" to event.point.verticalAccuracy,
                            "altitude" to event.point.altitude,
                            "speed" to event.point.speed,
                            "course" to event.point.course
                        )
                    )
                    is LocationSyncClientEvent.SyncEvent -> when (val e = event.event) {
                        is com.rtls.kmp.SyncEngineEvent.UploadSuccess -> mapOf(
                            "type" to "syncEvent",
                            "event" to "uploadSucceeded",
                            "accepted" to e.accepted,
                            "rejected" to e.rejected
                        )
                        is com.rtls.kmp.SyncEngineEvent.UploadFailed -> mapOf(
                            "type" to "syncEvent",
                            "event" to "uploadFailed",
                            "message" to e.message
                        )
                        else -> mapOf("type" to "syncEvent", "event" to "unknown")
                    }
                    is LocationSyncClientEvent.Error -> mapOf("type" to "error", "message" to event.message)
                    LocationSyncClientEvent.TrackingStarted -> mapOf("type" to "trackingStarted")
                    LocationSyncClientEvent.TrackingStopped -> mapOf("type" to "trackingStopped")
                }
                withContext(Dispatchers.Main) { eventSink?.success(map) }
            }
        }
    }

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        eventSink = events
        startEventCollectionIfReady()
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        eventCollectionJob?.cancel()
        eventCollectionJob = null
    }
}
