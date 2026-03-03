package com.rtls.kmp

/**
 * Controls local data cleanup so the on-device database doesn't grow forever.
 * Pending (unsent) points are never deleted by this policy.
 * Matches iOS RTLSCore.RetentionPolicy.
 */
data class RetentionPolicy(
    val sentPointsMaxAgeMs: Long? = null
) {
    companion object {
        /** Keep sent points forever (no pruning). */
        val KeepForever = RetentionPolicy(sentPointsMaxAgeMs = null)

        /** Pragmatic default: keep local sent points for 7 days, then prune. */
        val Recommended = RetentionPolicy(sentPointsMaxAgeMs = 7L * 24 * 60 * 60 * 1000)
    }
}
