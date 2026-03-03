package com.rtls.kmp

import kotlin.math.min
import kotlin.math.pow

/**
 * Exponential backoff policy for upload retries.
 * Matches iOS RTLSCore.SyncRetryPolicy.
 */
data class SyncRetryPolicy(
    val baseDelayMs: Long = 2_000L,
    val maxDelayMs: Long = 120_000L,
    val jitterFraction: Double = 0.2
) {
    init {
        require(jitterFraction in 0.0..1.0) { "jitterFraction must be in 0..1" }
    }

    /**
     * Delay in milliseconds for the given attempt (1-based).
     * Exponential: baseDelay * 2^(attempt-1), capped at maxDelay, with jitter.
     */
    fun delayForAttempt(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val exp = 2.0.pow((attempt - 1).toDouble())
        val base = min(maxDelayMs.toDouble(), baseDelayMs * exp)
        val jitter = base * jitterFraction
        val offset = (kotlin.random.Random.nextDouble() * 2 - 1) * jitter
        return (base + offset).toLong().coerceAtLeast(0L)
    }

    companion object {
        val Default = SyncRetryPolicy()
    }
}
