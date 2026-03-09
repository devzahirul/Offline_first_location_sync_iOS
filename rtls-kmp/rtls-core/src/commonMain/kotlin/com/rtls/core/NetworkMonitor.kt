package com.rtls.core

import kotlinx.coroutines.flow.Flow

enum class ConnectionType {
    WIFI, CELLULAR, UNKNOWN
}

interface NetworkMonitor {
    suspend fun isOnline(): Boolean
    val onlineFlow: Flow<Boolean>?
        get() = null
    suspend fun connectionType(): ConnectionType = ConnectionType.UNKNOWN
}
