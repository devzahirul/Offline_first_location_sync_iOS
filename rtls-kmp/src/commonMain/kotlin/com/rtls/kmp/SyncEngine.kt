package com.rtls.kmp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

sealed class SyncEngineEvent {
    data class UploadSuccess(val accepted: Int, val rejected: Int) : SyncEngineEvent()
    data class UploadFailed(val message: String) : SyncEngineEvent()
    data class PullSuccess(val count: Int) : SyncEngineEvent()
    data class PullFailed(val message: String) : SyncEngineEvent()
}

/**
 * Sync engine: uploads pending points in batches when conditions are met.
 * Demand-driven timer: only runs when pending data exists, stops when drained.
 * Supports cellular-aware flush intervals via BatchingPolicy.cellularFlushIntervalSeconds.
 */
class SyncEngine(
    private val store: LocationStore,
    private val api: LocationSyncAPI,
    private val batching: BatchingPolicy,
    private val retryPolicy: SyncRetryPolicy,
    private val retentionPolicy: RetentionPolicy,
    private val networkMonitor: NetworkMonitor?,
    private val scope: CoroutineScope,
    private val pullAPI: LocationPullAPI? = null,
    private val mergeStrategy: LocationMergeStrategy? = null,
    private val pullIntervalSeconds: Long? = null,
    private val pullOnForeground: Boolean = true
) {
    private val _events = MutableSharedFlow<SyncEngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SyncEngineEvent> = _events.asSharedFlow()

    private val bidirectionalStore: BidirectionalLocationStore? = store as? BidirectionalLocationStore

    private var timerJob: Job? = null
    private var networkJob: Job? = null
    private var pullJob: Job? = null
    private var notifyDebounceJob: Job? = null
    private var running = false
    private val flushMutex = Mutex()
    private var failureCount = 0
    private var nextAllowedFlushAtMs: Long? = null
    private var lastPruneAtMs: Long? = null
    private var lastPullAtMs: Long? = null
    private var pendingInsertsSinceLastFlush = 0
    private var hasPendingData = false

    fun start() {
        if (running) return
        running = true

        // Do NOT start timerJob here — it is demand-driven via ensureTimerRunning()

        networkMonitor?.onlineFlow?.let { flow ->
            networkJob = scope.launch {
                flow.collectLatest { online ->
                    if (online && running) {
                        val stats = try { store.pendingStats() } catch (_: Exception) { null }
                        if (stats != null && stats.count > 0) {
                            hasPendingData = true
                            ensureTimerRunning()
                        }
                        flushIfNeeded(force = false, maxBatches = null)
                    }
                }
            }
        }

        if (pullAPI != null && bidirectionalStore != null) {
            val intervalMs = (pullIntervalSeconds ?: 60L).coerceAtLeast(1L) * 1000
            pullJob = scope.launch {
                while (isActive && running) {
                    delay(intervalMs)
                    if (running) runPullOnce()
                }
            }
        }
    }

    fun stop() {
        running = false
        timerJob?.cancel(); timerJob = null
        networkJob?.cancel(); networkJob = null
        pullJob?.cancel(); pullJob = null
    }

    fun notifyNewData() {
        hasPendingData = true
        ensureTimerRunning()

        pendingInsertsSinceLastFlush++
        if (pendingInsertsSinceLastFlush >= batching.maxBatchSize) {
            scope.launch { flushIfNeeded(force = false, maxBatches = null) }
            return
        }
        notifyDebounceJob?.cancel()
        notifyDebounceJob = scope.launch {
            delay(2_000L)
            flushIfNeeded(force = false, maxBatches = null)
        }
    }

    private fun ensureTimerRunning() {
        if (!hasPendingData || timerJob != null) return
        timerJob = scope.launch {
            while (isActive && running) {
                val interval = batching.flushIntervalSeconds.coerceAtLeast(1L) * 1000
                delay(interval)
                if (running) flushIfNeeded(force = false, maxBatches = null)
            }
        }
    }

    private fun stopTimerIfDrained() {
        if (hasPendingData) return
        timerJob?.cancel()
        timerJob = null
    }

    suspend fun flushNow(maxBatches: Int? = null) {
        flushIfNeeded(force = true, maxBatches = maxBatches)
    }

    suspend fun pullNow() {
        if (pullAPI == null || bidirectionalStore == null) return
        runPullOnce()
    }

    private suspend fun runPullOnce() {
        val api = pullAPI ?: return
        val bidir = bidirectionalStore ?: return
        try {
            val cursor = bidir.getLastPullCursor()
            val result = api.fetch(cursor)
            if (result.items.isEmpty()) {
                result.nextCursor?.let { bidir.setLastPullCursor(it) }
                return
            }
            bidir.applyServerChanges(result.items, mergeStrategy, result.serverTimeMs, lastPullAtMs)
            result.nextCursor?.let { bidir.setLastPullCursor(it) }
            lastPullAtMs = System.currentTimeMillis()
            _events.emit(SyncEngineEvent.PullSuccess(result.items.size))
        } catch (e: Exception) {
            _events.emit(SyncEngineEvent.PullFailed(e.message ?: "Unknown error"))
        }
    }

    private suspend fun flushIfNeeded(force: Boolean, maxBatches: Int?) {
        if (!running) return
        if (!flushMutex.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            if (!force) {
                nextAllowedFlushAtMs?.let { next -> if (now < next) return }
            }
            networkMonitor?.let { if (!it.isOnline()) return }

            val should = try { shouldFlush(force) } catch (e: Exception) {
                _events.emit(SyncEngineEvent.UploadFailed(e.message ?: "Unknown error"))
                return
            }
            if (!should) return

            var batchesProcessed = 0
            while (running) {
                networkMonitor?.let { if (!it.isOnline()) return }
                if (maxBatches != null && batchesProcessed >= maxBatches) break

                val pending = store.fetchPendingPoints(batching.maxBatchSize)
                if (pending.isEmpty()) {
                    hasPendingData = false
                    stopTimerIfDrained()
                    break
                }

                try {
                    val batch = LocationUploadBatch(points = pending)
                    val result = api.upload(batch)
                    val sentAt = System.currentTimeMillis()
                    failureCount = 0
                    nextAllowedFlushAtMs = null
                    if (result.acceptedIds.isNotEmpty()) store.markSent(result.acceptedIds, sentAt)
                    if (result.rejected.isNotEmpty()) {
                        store.markFailed(result.rejected.map { it.id }, result.rejected.firstOrNull()?.reason ?: "rejected")
                    }
                    pendingInsertsSinceLastFlush = 0
                    _events.emit(SyncEngineEvent.UploadSuccess(result.acceptedIds.size, result.rejected.size))
                    batchesProcessed++
                    maybePruneSentPoints(sentAt)
                    if (result.acceptedIds.isEmpty() && result.rejected.isEmpty()) break
                } catch (e: Exception) {
                    scheduleBackoffAfterFailure()
                    _events.emit(SyncEngineEvent.UploadFailed(e.message ?: "Unknown error"))
                    break
                }
            }
        } finally {
            flushMutex.unlock()
        }
    }

    private suspend fun shouldFlush(force: Boolean): Boolean {
        if (force) return true
        val stats = store.pendingStats()
        if (stats.count == 0) {
            hasPendingData = false
            stopTimerIfDrained()
            return false
        }
        if (stats.count >= batching.maxBatchSize) return true
        val oldest = stats.oldestRecordedAtMs ?: return false
        val ageSeconds = (System.currentTimeMillis() - oldest) / 1000
        return ageSeconds >= batching.maxBatchAgeSeconds
    }

    private fun scheduleBackoffAfterFailure() {
        failureCount = (failureCount + 1).coerceAtMost(30)
        val delayMs = retryPolicy.delayForAttempt(failureCount)
        nextAllowedFlushAtMs = System.currentTimeMillis() + delayMs
    }

    private suspend fun maybePruneSentPoints(nowMs: Long) {
        val maxAgeMs = retentionPolicy.sentPointsMaxAgeMs ?: return
        if (maxAgeMs <= 0) return
        val minIntervalMs = 60 * 60 * 1000L
        lastPruneAtMs?.let { last -> if (nowMs - last < minIntervalMs) return }
        val prunable = store as? SentPointsPrunableLocationStore ?: run {
            lastPruneAtMs = nowMs
            return
        }
        try {
            prunable.pruneSentPoints(olderThanRecordedMs = nowMs - maxAgeMs)
        } catch (_: Exception) {}
        lastPruneAtMs = nowMs
    }
}
