package com.rtls.core

/**
 * Extension of LocationStore that supports pruning of sent location points.
 * Implementations should provide atomic markSentAndPrune to prevent data loss on crash.
 */
interface SentPointsPrunableLocationStore : LocationStore {
    suspend fun pruneSentPoints(olderThanRecordedMs: Long)

    /**
     * Atomic operation: mark points as sent AND prune old sent points in a single transaction.
     * Default implementation calls separate methods (non-atomic), implementations should override.
     */
    suspend fun markSentAndPrune(
        pointIds: List<String>,
        sentAtMs: Long,
        olderThanRecordedMs: Long?
    ) {
        // Default non-atomic implementation for backwards compatibility
        markSent(pointIds, sentAtMs)
        if (olderThanRecordedMs != null) {
            pruneSentPoints(olderThanRecordedMs)
        }
    }
}
