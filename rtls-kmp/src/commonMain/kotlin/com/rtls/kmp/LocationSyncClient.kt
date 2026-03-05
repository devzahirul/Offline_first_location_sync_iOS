package com.rtls.kmp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed class LocationSyncClientEvent {
    data class Recorded(val point: LocationPoint) : LocationSyncClientEvent()
    data class SyncEvent(val event: SyncEngineEvent) : LocationSyncClientEvent()
    data class Error(val message: String) : LocationSyncClientEvent()
    object TrackingStarted : LocationSyncClientEvent()
    object TrackingStopped : LocationSyncClientEvent()
}

data class LocationSyncClientStats(
    val pendingCount: Int,
    val oldestPendingRecordedAtMs: Long?
)

class LocationSyncClient(
    private val store: LocationStore,
    private val syncEngine: SyncEngine,
    private val userId: String,
    private val deviceId: String,
    private val scope: CoroutineScope,
    private val recordingDecider: LocationRecordingDecider? = null
) {
    private val _events = MutableSharedFlow<LocationSyncClientEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<LocationSyncClientEvent> = _events.asSharedFlow()

    private var locationJob: Job? = null
    private var syncEventsJob: Job? = null
    private var isTracking = false

    fun startCollectingLocation(locationFlow: kotlinx.coroutines.flow.Flow<LocationPoint>) {
        if (isTracking) return
        isTracking = true
        syncEngine.start()
        scope.launch { _events.emit(LocationSyncClientEvent.TrackingStarted) }
        locationJob = scope.launch {
            locationFlow
                .catch { e -> _events.emit(LocationSyncClientEvent.Error(e.message ?: "Location error")) }
                .collect { point ->
                    if (recordingDecider != null && !recordingDecider.shouldRecord(point)) return@collect
                    try {
                        store.insert(listOf(point))
                        recordingDecider?.markRecorded(point)
                        _events.emit(LocationSyncClientEvent.Recorded(point))
                        syncEngine.notifyNewData()
                    } catch (e: Exception) {
                        _events.emit(LocationSyncClientEvent.Error(e.message ?: "Insert error"))
                    }
                }
        }
        syncEventsJob = scope.launch {
            syncEngine.events.collect { e ->
                _events.emit(LocationSyncClientEvent.SyncEvent(e))
            }
        }
    }

    fun stopTracking() {
        if (!isTracking) return
        isTracking = false
        locationJob?.cancel()
        locationJob = null
        syncEventsJob?.cancel()
        syncEventsJob = null
        syncEngine.stop()
        scope.launch { _events.emit(LocationSyncClientEvent.TrackingStopped) }
    }

    suspend fun stats(): LocationSyncClientStats {
        val count = store.pendingCount()
        val oldest = store.oldestPendingRecordedAt()
        return LocationSyncClientStats(pendingCount = count, oldestPendingRecordedAtMs = oldest)
    }

    /**
     * Run one pull cycle (fetch from server, apply to local). No-op if engine is upload-only.
     */
    suspend fun pullNow() {
        syncEngine.pullNow()
    }

    suspend fun flushNow() {
        syncEngine.flushNow()
    }
}
