package com.rtls.websocket

import com.rtls.core.LocationPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow

sealed class RealTimeEvent {
    data class Connected(val url: String) : RealTimeEvent()
    data object Disconnected : RealTimeEvent()
    data class Reconnecting(val attempt: Int, val delayMs: Long) : RealTimeEvent()
    data class LocationReceived(val point: LocationPoint) : RealTimeEvent()
    data class PushAcknowledged(val reqId: String, val status: String) : RealTimeEvent()
    data class BatchAcknowledged(val reqId: String, val acceptedIds: List<String>) : RealTimeEvent()
    data class Error(val message: String) : RealTimeEvent()
    data class Subscribed(val userId: String) : RealTimeEvent()
}

/**
 * High-level real-time location client over WebSocket.
 * Independently usable — no offline sync or local storage required.
 *
 * Features:
 * - Push locations in real-time (fire-and-forget or ack-based)
 * - Subscribe to other users' location updates
 * - Auto-reconnect with exponential backoff
 * - Heartbeat ping/pong
 */
class RealTimeLocationClient(
    private val config: WebSocketConfig,
    private val channel: RealTimeChannel,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _events = MutableSharedFlow<RealTimeEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<RealTimeEvent> = _events.asSharedFlow()

    private val _incomingLocations = MutableSharedFlow<LocationPoint>(extraBufferCapacity = 256)
    val incomingLocations: SharedFlow<LocationPoint> = _incomingLocations.asSharedFlow()

    private var messageJob: Job? = null
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    private var running = false
    private var isBackgroundMode = false
    private var reconnectAttempt = 0
    private val subscribedUserIds = mutableSetOf<String>()

    suspend fun connect() {
        running = true
        reconnectAttempt = 0
        doConnect()
    }

    suspend fun disconnect() {
        running = false
        reconnectJob?.cancel()
        pingJob?.cancel()
        messageJob?.cancel()
        try { channel.disconnect() } catch (_: Exception) {}
        _events.emit(RealTimeEvent.Disconnected)
    }

    suspend fun pushLocation(point: LocationPoint) {
        val msg = WsLocationPush(reqId = UUID.randomUUID().toString(), point = point)
        channel.send(json.encodeToString(msg))
    }

    suspend fun pushBatch(points: List<LocationPoint>) {
        val msg = WsLocationBatch(reqId = UUID.randomUUID().toString(), points = points)
        channel.send(json.encodeToString(msg))
    }

    suspend fun subscribe(userId: String) {
        subscribedUserIds.add(userId)
        if (channel.isConnected) {
            channel.send(json.encodeToString(WsSubscribe(userId = userId)))
        }
    }

    suspend fun unsubscribe(userId: String) {
        subscribedUserIds.remove(userId)
        if (channel.isConnected) {
            channel.send(json.encodeToString(WsUnsubscribe(userId = userId)))
        }
    }

    /**
     * Switch between foreground/background ping intervals.
     * In background mode, ping interval increases to reduce radio wake-ups.
     * If [WebSocketConfig.disconnectInBackground] is enabled, the WebSocket is fully disconnected.
     */
    suspend fun setBackgroundMode(enabled: Boolean) {
        if (isBackgroundMode == enabled) return
        isBackgroundMode = enabled

        if (enabled && config.disconnectInBackground) {
            disconnect()
            return
        }
        if (!enabled && config.disconnectInBackground && !running) {
            connect()
            return
        }
        // Restart ping loop with appropriate interval
        if (running && channel.isConnected) {
            startPingLoop()
        }
    }

    private suspend fun doConnect() {
        try {
            val wsUrl = buildWsUrl(config.baseUrl)
            val token = config.tokenProvider.accessToken()
            val headers = mutableMapOf<String, String>()
            if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"

            channel.connect(wsUrl, headers)

            val authMsg = WsAuthMessage(token = token)
            channel.send(json.encodeToString(authMsg))

            reconnectAttempt = 0
            _events.emit(RealTimeEvent.Connected(wsUrl))

            for (uid in subscribedUserIds) {
                channel.send(json.encodeToString(WsSubscribe(userId = uid)))
            }

            startMessageLoop()
            startPingLoop()
        } catch (e: Exception) {
            _events.emit(RealTimeEvent.Error(e.message ?: "Connection failed"))
            if (running && config.autoReconnect) scheduleReconnect()
        }
    }

    private fun startMessageLoop() {
        messageJob?.cancel()
        messageJob = scope.launch {
            try {
                channel.incomingMessages.collect { raw ->
                    handleServerMessage(raw)
                }
            } catch (e: Exception) {
                if (running) {
                    _events.emit(RealTimeEvent.Error(e.message ?: "Connection lost"))
                    _events.emit(RealTimeEvent.Disconnected)
                    if (config.autoReconnect) scheduleReconnect()
                }
            }
        }
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && channel.isConnected) {
                val interval = activePingIntervalMs
                delay(interval)
                try { channel.send(json.encodeToString(WsPing())) } catch (_: Exception) { break }
            }
        }
    }

    private val activePingIntervalMs: Long
        get() = if (!isBackgroundMode) {
            config.pingIntervalMs
        } else {
            // In background mode, use cellular interval by default for maximum battery savings
            // For full cellular-aware logic, pass NetworkMonitor and check connection type
            config.cellularBackgroundPingIntervalMs
        }

    private suspend fun handleServerMessage(raw: String) {
        try {
            val msg = json.decodeFromString<WsServerMessage>(raw)
            when (msg.type) {
                "auth.ok" -> {} // authenticated
                "location.ack" -> {
                    _events.emit(RealTimeEvent.PushAcknowledged(msg.reqId ?: "", msg.status ?: "accepted"))
                }
                "location.batch_ack" -> {
                    _events.emit(RealTimeEvent.BatchAcknowledged(msg.reqId ?: "", msg.acceptedIds ?: emptyList()))
                }
                "location.update", "location" -> {
                    msg.point?.let { point ->
                        _incomingLocations.emit(point)
                        _events.emit(RealTimeEvent.LocationReceived(point))
                    }
                }
                "subscribed" -> {
                    _events.emit(RealTimeEvent.Subscribed(msg.userId ?: ""))
                }
                "pong" -> {} // heartbeat response
                "error" -> {
                    _events.emit(RealTimeEvent.Error(msg.message ?: "Server error"))
                }
            }
        } catch (e: Exception) {
            _events.emit(RealTimeEvent.Error("Failed to parse server message: ${e.message}"))
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempt++
            val exp = 2.0.pow((reconnectAttempt - 1).coerceAtMost(10).toDouble())
            val delayMs = min(config.reconnectMaxDelayMs, (config.reconnectBaseDelayMs * exp).toLong())
            _events.emit(RealTimeEvent.Reconnecting(reconnectAttempt, delayMs))
            delay(delayMs)
            if (running) doConnect()
        }
    }

    private fun buildWsUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        val withPath = if (trimmed.endsWith("/v1/ws")) trimmed else "$trimmed/v1/ws"
        return withPath
            .replace("https://", "wss://")
            .replace("http://", "ws://")
            .let { if (!it.startsWith("ws")) "ws://$it" else it }
    }
}
