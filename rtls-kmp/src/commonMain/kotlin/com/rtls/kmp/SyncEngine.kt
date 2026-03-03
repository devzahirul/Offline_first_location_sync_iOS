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
import kotlinx.coroutines.sync.withLock

sealed class SyncEngineEvent {
    data class UploadSuccess(val accepted: Int, val rejected: Int) : SyncEngineEvent()
    data class UploadFailed(val message: String) : SyncEngineEvent()
}

/**
 * Sync engine: uploads pending points in batches when conditions are met.
 * Matches iOS RTLSSync.SyncEngine behaviour: timer + optional network gate,
 * conditional flush (batch size / max age), exponential backoff on failure,
 * optional retention pruning.
 */
class SyncEngine(
    private val store: LocationStore,
    private val api: LocationSyncAPI,
    private val batching: BatchingPolicy,
    private val retryPolicy: SyncRetryPolicy,
    private val retentionPolicy: RetentionPolicy,
    private val networkMonitor: NetworkMonitor?,
    private val scope: CoroutineScope
) {
    private val _events = MutableSharedFlow<SyncEngineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SyncEngineEvent> = _events.asSharedFlow()

    private var timerJob: Job? = null
    private var networkJob: Job? = null
    private var running = false
    private val flushMutex = Mutex()
    private var failureCount = 0
    private var nextAllowedFlushAtMs: Long? = null
    private var lastPruneAtMs: Long? = null

    fun start() {
        if (running) return
        running = true

        timerJob = scope.launch {
            while (isActive && running) {
                delay(batching.flushIntervalSeconds * 1000)
                if (running) {
                    flushIfNeeded(force = false, maxBatches = null)
                }
            }
        }

        // Match iOS: when network comes online, trigger conditional flush
        networkMonitor?.onlineFlow?.let { flow ->
            networkJob = scope.launch {
                flow.collectLatest { online ->
                    if (online && running) flushIfNeeded(force = false, maxBatches = null)
                }
            }
        }
    }

    fun stop() {
        running = false
        timerJob?.cancel()
        timerJob = null
        networkJob?.cancel()
        networkJob = null
    }

    fun notifyNewData() {
        scope.launch { flushIfNeeded(force = false, maxBatches = null) }
    }

    suspend fun flushNow(maxBatches: Int? = null) {
        flushIfNeeded(force = true, maxBatches = maxBatches)
    }

    private suspend fun flushIfNeeded(force: Boolean, maxBatches: Int?) {
        if (!running) return
        if (!flushMutex.tryLock()) return
        try {
            val now = System.currentTimeMillis()
            if (!force) {
                nextAllowedFlushAtMs?.let { next ->
                    if (now < next) return
                }
            }
            networkMonitor?.let { monitor ->
                if (!monitor.isOnline()) return
            }

            val should = try {
                shouldFlush(force)
            } catch (e: Exception) {
                _events.emit(SyncEngineEvent.UploadFailed(e.message ?: "Unknown error"))
                return
            }
            if (!should) return

            var batchesProcessed = 0
            while (running) {
                networkMonitor?.let { monitor ->
                    if (!monitor.isOnline()) return
                }
                if (maxBatches != null && batchesProcessed >= maxBatches) break

                val pending = store.fetchPendingPoints(batching.maxBatchSize)
                if (pending.isEmpty()) break

                try {
                    val batch = LocationUploadBatch(points = pending)
                    val result = api.upload(batch)
                    val sentAt = System.currentTimeMillis()

                    failureCount = 0
                    nextAllowedFlushAtMs = null

                    if (result.acceptedIds.isNotEmpty()) {
                        store.markSent(result.acceptedIds, sentAt)
                    }
                    if (result.rejected.isNotEmpty()) {
                        store.markFailed(
                            result.rejected.map { it.id },
                            result.rejected.firstOrNull()?.reason ?: "rejected"
                        )
                    }

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
        val count = store.pendingCount()
        if (count == 0) return false
        if (count >= batching.maxBatchSize) return true
        val oldest = store.oldestPendingRecordedAt() ?: return false
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
        lastPruneAtMs?.let { last ->
            if (nowMs - last < minIntervalMs) return
        }
        val prunable = store as? SentPointsPrunableLocationStore ?: run {
            lastPruneAtMs = nowMs
            return
        }
        try {
            val cutoff = nowMs - maxAgeMs
            prunable.pruneSentPoints(olderThanRecordedMs = cutoff)
        } catch (_: Exception) {
            // Non-fatal
        }
        lastPruneAtMs = nowMs
    }
}
