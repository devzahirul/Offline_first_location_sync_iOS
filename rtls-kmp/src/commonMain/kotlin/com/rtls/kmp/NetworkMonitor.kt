package com.rtls.kmp

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction for network availability. When present, SyncEngine only attempts
 * upload when isOnline() is true. Matches iOS RTLSSync.NetworkMonitor concept.
 * Optional [onlineFlow]: when it emits true, SyncEngine runs flushIfNeeded(force = false)
 * to match iOS "flush when network comes online" behaviour.
 */
interface NetworkMonitor {
    suspend fun isOnline(): Boolean

    /**
     * Optional stream of online state. When this emits `true`, SyncEngine triggers
     * a conditional flush (same as iOS networkTask). Default null = no "on online" trigger.
     */
    val onlineFlow: Flow<Boolean>?
        get() = null
}
