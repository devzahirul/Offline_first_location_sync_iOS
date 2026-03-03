package com.rtls.kmp

/**
 * Policy for when to flush pending location points to the server.
 * Matches iOS RTLSCore.BatchingPolicy.
 */
data class BatchingPolicy(
    val maxBatchSize: Int = 50,
    val flushIntervalSeconds: Long = 10L,
    val maxBatchAgeSeconds: Long = 60L
) {
    init {
        require(maxBatchSize >= 1) { "maxBatchSize must be >= 1" }
        require(flushIntervalSeconds >= 1) { "flushIntervalSeconds must be >= 1" }
        require(maxBatchAgeSeconds >= 0) { "maxBatchAgeSeconds must be >= 0" }
    }
}
