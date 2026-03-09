package com.rtls.websocket

import com.rtls.core.AuthTokenProvider

data class WebSocketConfig(
    val baseUrl: String,
    val tokenProvider: AuthTokenProvider,
    val autoReconnect: Boolean = true,
    val reconnectBaseDelayMs: Long = 1_000L,
    val reconnectMaxDelayMs: Long = 30_000L,
    val pingIntervalMs: Long = 30_000L,
    /** Ping interval used in background mode. Longer intervals allow cellular radio dormancy. */
    val backgroundPingIntervalMs: Long = 120_000L,
    /** When true, fully disconnect WebSocket when entering background. */
    val disconnectInBackground: Boolean = false
)
