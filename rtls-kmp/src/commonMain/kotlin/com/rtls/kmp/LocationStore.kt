package com.rtls.kmp

data class PendingStats(val count: Int, val oldestRecordedAtMs: Long?)

interface LocationStore {
    suspend fun insert(points: List<LocationPoint>)
    suspend fun fetchPendingPoints(limit: Int): List<LocationPoint>
    suspend fun pendingCount(): Int
    suspend fun oldestPendingRecordedAt(): Long?
    /** Single query returning both count and oldest timestamp — avoids 2 round-trips in hot path. */
    suspend fun pendingStats(): PendingStats = PendingStats(pendingCount(), oldestPendingRecordedAt())
    suspend fun markSent(pointIds: List<String>, sentAtMs: Long)
    suspend fun markFailed(pointIds: List<String>, errorMessage: String)
}
