package com.rtls.rtls_flutter

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rtls.kmp.BatchingPolicy
import com.rtls.kmp.LocationRecordingDecider
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
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var pendingLocationIntent: PendingIntent? = null
    private var locationReceivedReceiver: BroadcastReceiver? = null

    companion object {
        private const val REQUEST_CODE_LOCATION = 0x2a02
    }

    private fun registerLifecycleCallbacksIfNeeded() {
        if (lifecycleCallbacks != null) return
        val app = (context as? Application) ?: return
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                scope.launch {
                    client?.setBackgroundMode(false)
                    client?.flushNow()
                }
            }
            override fun onActivityPaused(activity: Activity) {
                scope.launch {
                    client?.setBackgroundMode(true)
                    client?.flushNow()
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        lifecycleCallbacks = callbacks
        app.registerActivityLifecycleCallbacks(callbacks)
    }

    private fun unregisterLifecycleCallbacks() {
        val app = (context as? Application) ?: return
        lifecycleCallbacks?.let { app.unregisterActivityLifecycleCallbacks(it) }
        lifecycleCallbacks = null
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
        unregisterLifecycleCallbacks()
        unregisterLocationReceivedReceiver()
        removePendingIntentUpdates()
        client?.stopTracking()
        client = null
        context?.let { RtlsLocationForegroundService.stop(it) }
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
                val authStatus = if (granted) "authorizedAlways" else "denied"
                eventSink?.success(mapOf("type" to "authorizationChanged", "authorization" to authStatus))
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
                val maxAccuracy = call.argument<Number>("maxAcceptableAccuracyMeters")?.toFloat() ?: 100f
                locationRequestParams = when {
                    significantOnly -> LocationRequestParams(
                        intervalMillis = 60_000L,
                        minUpdateIntervalMillis = 60_000L,
                        minUpdateDistanceMeters = 500f,
                        maxUpdateDelayMillis = 120_000L,
                        useBalancedPowerAccuracy = true,
                        maxAcceptableAccuracyMeters = maxAccuracy
                    )
                    intervalSec != null && intervalSec > 0 -> {
                        val ms = (intervalSec * 1000).toLong().coerceAtLeast(5_000L)
                        LocationRequestParams(
                            intervalMillis = ms,
                            minUpdateIntervalMillis = ms,
                            minUpdateDistanceMeters = 0f,
                            maxUpdateDelayMillis = (ms * 3).coerceAtMost(60_000L),
                            maxAcceptableAccuracyMeters = maxAccuracy
                        )
                    }
                    distanceM != null && distanceM > 0 -> LocationRequestParams(
                        intervalMillis = 10_000L,
                        minUpdateIntervalMillis = 5_000L,
                        minUpdateDistanceMeters = distanceM.toFloat().coerceAtLeast(1f),
                        maxUpdateDelayMillis = 30_000L,
                        maxAcceptableAccuracyMeters = maxAccuracy
                    )
                    else -> LocationRequestParams(
                        maxUpdateDelayMillis = 30_000L,
                        useBalancedPowerAccuracy = true,
                        maxAcceptableAccuracyMeters = maxAccuracy
                    )
                }
                val decider = when {
                    significantOnly -> LocationRecordingDecider(
                        minDistanceMeters = 500.0,
                        maxAcceptableAccuracy = maxAccuracy.toDouble()
                    )
                    distanceM != null && distanceM > 0 -> LocationRecordingDecider(
                        minDistanceMeters = distanceM,
                        maxAcceptableAccuracy = maxAccuracy.toDouble()
                    )
                    intervalSec != null && intervalSec > 0 -> LocationRecordingDecider(
                        minTimeIntervalMs = (intervalSec * 1000).toLong(),
                        maxAcceptableAccuracy = maxAccuracy.toDouble()
                    )
                    else -> LocationRecordingDecider(
                        minDistanceMeters = 25.0,
                        maxAcceptableAccuracy = maxAccuracy.toDouble()
                    )
                }
                client = RTLSKmp.createLocationSyncClient(
                    context = ctx,
                    baseUrl = baseUrl,
                    userId = userId,
                    deviceId = deviceId,
                    accessToken = accessToken,
                    scope = scope,
                    batchingPolicy = batchingPolicy,
                    recordingDecider = decider
                )
                registerLifecycleCallbacksIfNeeded()
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
                savePendingIntentConfig(ctx, userId, deviceId)
                registerPendingIntentLocationUpdates(ctx, userId, deviceId)
                registerLocationReceivedReceiver()
                RtlsLocationForegroundService.start(ctx)
                val flow = RTLSKmp.createLocationFlow(ctx, userId, deviceId, locationRequestParams)
                c.startCollectingLocation(flow)
                result.success(null)
            }
            "stopTracking" -> {
                removePendingIntentUpdates()
                unregisterLocationReceivedReceiver()
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

    private fun savePendingIntentConfig(context: Context, userId: String, deviceId: String) {
        RtlsLocationBroadcastReceiver.getPrefs(context).edit()
            .putString(RtlsLocationBroadcastReceiver.KEY_USER_ID, userId)
            .putString(RtlsLocationBroadcastReceiver.KEY_DEVICE_ID, deviceId)
            .putFloat(RtlsLocationBroadcastReceiver.KEY_MAX_ACCURACY_METERS, locationRequestParams.maxAcceptableAccuracyMeters)
            .apply()
    }

    private fun buildLocationRequest(): LocationRequest {
        val p = locationRequestParams
        val priority = if (p.useBalancedPowerAccuracy)
            Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY
        return LocationRequest.Builder(priority, p.intervalMillis)
            .setMinUpdateIntervalMillis(p.minUpdateIntervalMillis)
            .setMinUpdateDistanceMeters(p.minUpdateDistanceMeters)
            .apply {
                if (p.maxUpdateDelayMillis > 0) {
                    setMaxUpdateDelayMillis(p.maxUpdateDelayMillis)
                }
            }
            .build()
    }

    private fun registerPendingIntentLocationUpdates(context: Context, userId: String, deviceId: String) {
        removePendingIntentUpdates()
        val intent = Intent(context, RtlsLocationBroadcastReceiver::class.java).apply {
            action = RtlsLocationBroadcastReceiver.ACTION_LOCATION_UPDATE
        }
        val pending = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            val client: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
            client.requestLocationUpdates(buildLocationRequest(), pending)
            pendingLocationIntent = pending
        } catch (_: SecurityException) { /* permission already checked in startTracking */ }
    }

    private fun removePendingIntentUpdates() {
        val ctx = context ?: return
        val pending = pendingLocationIntent ?: return
        try {
            LocationServices.getFusedLocationProviderClient(ctx).removeLocationUpdates(pending)
        } catch (_: Exception) { }
        pendingLocationIntent = null
    }

    private fun registerLocationReceivedReceiver() {
        val ctx = context ?: return
        unregisterLocationReceivedReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != RtlsLocationBroadcastReceiver.ACTION_LOCATION_RECEIVED) return
                scope.launch { client?.flushNow() }
                val id = intent.getStringExtra(RtlsLocationBroadcastReceiver.EXTRA_LAST_POINT_ID) ?: return
                val recordedAtMs = intent.getLongExtra(RtlsLocationBroadcastReceiver.EXTRA_LAST_RECORDED_AT, 0L)
                val lat = intent.getDoubleExtra(RtlsLocationBroadcastReceiver.EXTRA_LAST_LAT, 0.0)
                val lng = intent.getDoubleExtra(RtlsLocationBroadcastReceiver.EXTRA_LAST_LNG, 0.0)
                val acc = intent.getFloatExtra(RtlsLocationBroadcastReceiver.EXTRA_LAST_ACCURACY, -1f)
                val pointMap = mapOf(
                    "id" to id,
                    "userId" to (lastUserId ?: ""),
                    "deviceId" to (lastDeviceId ?: ""),
                    "recordedAtMs" to recordedAtMs,
                    "lat" to lat,
                    "lng" to lng,
                    "horizontalAccuracy" to if (acc >= 0) acc.toDouble() else null,
                    "verticalAccuracy" to null,
                    "altitude" to null,
                    "speed" to null,
                    "course" to null
                )
                scope.launch {
                    withContext(Dispatchers.Main) {
                        eventSink?.success(mapOf("type" to "recorded", "point" to pointMap))
                    }
                }
            }
        }
        locationReceivedReceiver = receiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, IntentFilter(RtlsLocationBroadcastReceiver.ACTION_LOCATION_RECEIVED), Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, IntentFilter(RtlsLocationBroadcastReceiver.ACTION_LOCATION_RECEIVED))
        }
    }

    private fun unregisterLocationReceivedReceiver() {
        val ctx = context ?: return
        locationReceivedReceiver?.let { ctx.unregisterReceiver(it) }
        locationReceivedReceiver = null
    }

    private fun startEventCollectionIfReady() {
        eventCollectionJob?.cancel()
        eventCollectionJob = null
        val c = client ?: return
        val sink = eventSink ?: return
        eventCollectionJob = scope.launch {
            c.events.collect { event ->
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
        emitAuthorizationStatusIfReady()
        startEventCollectionIfReady()
    }

    private fun emitAuthorizationStatusIfReady() {
        val ctx = context ?: return
        val sink = eventSink ?: return
        val authStatus = when {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED -> "denied"
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED -> "authorizedWhenInUse"
            else -> "authorizedAlways"
        }
        sink.success(mapOf("type" to "authorizationChanged", "authorization" to authStatus))
    }

    override fun onCancel(arguments: Any?) {
        eventSink = null
        eventCollectionJob?.cancel()
        eventCollectionJob = null
    }
}
