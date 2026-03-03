package com.rtls.kmp

/**
 * Optional extension of LocationStore: deletes points that have been successfully
 * sent and are older than the provided cutoff (by recorded_at_ms).
 * Matches iOS RTLSCore.SentPointsPrunableLocationStore.
 */
interface SentPointsPrunableLocationStore : LocationStore {
    suspend fun pruneSentPoints(olderThanRecordedMs: Long)
}
