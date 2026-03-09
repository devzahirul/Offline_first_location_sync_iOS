package com.rtls.websocket

import com.rtls.core.AuthTokenProvider

data class WebSocketConfig(
    val baseUrl: String,
    val tokenProvider: AuthTokenProvider,
    val autoReconnect: Boolean = true,
    val reconnectBaseDelayMs: Long = 1_000L,
    val reconnectMaxDelayMs: Long = 30_000L,
    val pingIntervalMs: Long = 30_000L,
    /** Ping interval used in background mode on WiFi. Longer intervals allow cellular radio dormancy. */
    val backgroundPingIntervalMs: Long = 120_000L,
    /** Ping interval used in background mode on cellular networks. Defaults to 5 minutes
     *  to allow cellular radio to enter low-power state between pings. */
    val cellularBackgroundPingIntervalMs: Long = 300_000L,
    /** When true, fully disconnect WebSocket when entering background. */
    val disconnectInBackground: Boolean = false
) {
    init {
        // Enforce HTTPS for security - location data and auth tokens must not be transmitted in cleartext
        require(
            baseUrl.startsWith("https://") || baseUrl.startsWith("wss://"),
            "RTLS requires HTTPS/WSS for security. baseUrl must start with 'https://' or 'wss://' (was: $baseUrl). " +
            "In development, you may use a self-signed certificate, but never transmit " +
            "location data or auth tokens over plaintext HTTP."
        )
    }
}
