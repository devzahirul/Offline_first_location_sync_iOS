package com.rtls.client

import com.rtls.core.*
import com.rtls.location.LocationRecordingDecider
import com.rtls.sync.*
import com.rtls.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

sealed class RTLSClientEvent {
    data class Recorded(val point: LocationPoint) : RTLSClientEvent()
    data class SyncEvent(val event: SyncEngineEvent) : RTLSClientEvent()
    data class WebSocketEvent(val event: RealTimeEvent) : RTLSClientEvent()
    data class Error(val message: String) : RTLSClientEvent()
    data object TrackingStarted : RTLSClientEvent()
    data object TrackingStopped : RTLSClientEvent()
}

data class RTLSClientStats(
    val pendingCount: Int,
    val oldestPendingRecordedAtMs: Long?,
    val webSocketConnected: Boolean
)

/**
 * Unified facade that wires together any combination of:
 * - Offline sync (batch upload + bidirectional pull)
 * - WebSocket (real-time push + subscribe)
 * - Location (background GPS collection)
 *
 * Each capability is optional. Use individual packages directly for more control.
 */
class RTLSClient(
    private val store: LocationStore?,
    private val syncEngine: SyncEngine?,
    private val webSocketClient: RealTimeLocationClient?,
    private val userId: String,
    private val deviceId: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val recordingDecider: LocationRecordingDecider? = null
) {
    private val _events = MutableSharedFlow<RTLSClientEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RTLSClientEvent> = _events.asSharedFlow()

    val incomingLocations: SharedFlow<LocationPoint>?
        get() = webSocketClient?.incomingLocations

    private var locationJob: Job? = null
    private var syncEventsJob: Job? = null
    private var wsEventsJob: Job? = null
    private var isTracking = false

    fun startCollectingLocation(locationFlow: Flow<LocationPoint>) {
        if (isTracking) return
        isTracking = true
        syncEngine?.start()
        scope.launch { _events.emit(RTLSClientEvent.TrackingStarted) }

        locationJob = scope.launch {
            locationFlow
                .catch { e -> _events.emit(RTLSClientEvent.Error(e.message ?: "Location error")) }
                .collect { point ->
                    if (recordingDecider != null && !recordingDecider.shouldRecord(point)) return@collect
                    try {
                        store?.insert(listOf(point))
                        recordingDecider?.markRecorded(point)
                        _events.emit(RTLSClientEvent.Recorded(point))
                        syncEngine?.notifyNewData()

                        if (webSocketClient != null) {
                            try { webSocketClient.pushLocation(point) } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        _events.emit(RTLSClientEvent.Error(e.message ?: "Insert error"))
                    }
                }
        }

        syncEventsJob = syncEngine?.let { engine ->
            scope.launch {
                engine.events.collect { e -> _events.emit(RTLSClientEvent.SyncEvent(e)) }
            }
        }

        wsEventsJob = webSocketClient?.let { ws ->
            scope.launch {
                ws.events.collect { e -> _events.emit(RTLSClientEvent.WebSocketEvent(e)) }
            }
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        locationJob?.cancel(); locationJob = null
        syncEventsJob?.cancel(); syncEventsJob = null
        wsEventsJob?.cancel(); wsEventsJob = null
        syncEngine?.stop()
        scope.launch { _events.emit(RTLSClientEvent.TrackingStopped) }
    }

    suspend fun connectWebSocket() { webSocketClient?.connect() }
    suspend fun disconnectWebSocket() { webSocketClient?.disconnect() }
    suspend fun subscribeToUser(userId: String) { webSocketClient?.subscribe(userId) }
    suspend fun unsubscribeFromUser(userId: String) { webSocketClient?.unsubscribe(userId) }

    /** Switch WebSocket between foreground/background ping intervals. */
    suspend fun setBackgroundMode(enabled: Boolean) { webSocketClient?.setBackgroundMode(enabled) }

    suspend fun flushNow() { syncEngine?.flushNow() }
    suspend fun pullNow() { syncEngine?.pullNow() }

    suspend fun stats(): RTLSClientStats {
        val pending = store?.pendingCount() ?: 0
        val oldest = store?.oldestPendingRecordedAt()
        return RTLSClientStats(pending, oldest, webSocketClient?.events?.replayCache?.any { it is RealTimeEvent.Connected } == true)
    }
}
