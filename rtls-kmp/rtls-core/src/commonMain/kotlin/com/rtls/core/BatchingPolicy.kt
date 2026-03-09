package com.rtls.core

data class BatchingPolicy(
    val maxBatchSize: Int = 50,
    val flushIntervalSeconds: Long = 10L,
    val maxBatchAgeSeconds: Long = 60L,
    /** When non-null and on cellular, use this flush interval instead of [flushIntervalSeconds]. */
    val cellularFlushIntervalSeconds: Long? = null
) {
    init {
        require(maxBatchSize >= 1) { "maxBatchSize must be >= 1" }
        require(flushIntervalSeconds >= 1) { "flushIntervalSeconds must be >= 1" }
        require(maxBatchAgeSeconds >= 0) { "maxBatchAgeSeconds must be >= 0" }
    }
}
