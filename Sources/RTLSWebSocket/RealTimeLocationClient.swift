import Foundation
import RTLSCore

/// High-level real-time location client over WebSocket.
/// Independently usable — no offline sync or local storage required.
///
/// Features:
/// - Push locations in real-time
/// - Subscribe to other users' location updates
/// - Auto-reconnect with exponential backoff
/// - Heartbeat ping/pong
public actor RealTimeLocationClient {
    public struct Configuration: Sendable {
        public var baseURL: URL
        public var tokenProvider: AuthTokenProvider
        public var autoReconnect: Bool
        public var reconnectBaseDelay: TimeInterval
        public var reconnectMaxDelay: TimeInterval
        public var pingInterval: TimeInterval
        /// Ping interval used in background mode. Longer intervals allow cellular radio dormancy.
        public var backgroundPingInterval: TimeInterval
        /// When true, fully disconnect WebSocket when entering background.
        public var disconnectInBackground: Bool

        public init(
            baseURL: URL,
            tokenProvider: AuthTokenProvider,
            autoReconnect: Bool = true,
            reconnectBaseDelay: TimeInterval = 1.0,
            reconnectMaxDelay: TimeInterval = 30.0,
            pingInterval: TimeInterval = 30.0,
            backgroundPingInterval: TimeInterval = 120.0,
            disconnectInBackground: Bool = false
        ) {
            self.baseURL = baseURL
            self.tokenProvider = tokenProvider
            self.autoReconnect = autoReconnect
            self.reconnectBaseDelay = reconnectBaseDelay
            self.reconnectMaxDelay = reconnectMaxDelay
            self.pingInterval = pingInterval
            self.backgroundPingInterval = backgroundPingInterval
            self.disconnectInBackground = disconnectInBackground
        }
    }

    public enum Event: Sendable {
        case connected
        case disconnected
        case reconnecting(attempt: Int, delay: TimeInterval)
        case locationReceived(LocationPoint)
        case pushAcknowledged(reqId: String, status: String)
        case batchAcknowledged(reqId: String, acceptedIds: [String])
        case subscribed(userId: String)
        case error(String)
    }

    private let config: Configuration
    private let session: URLSession

    private var webSocketTask: URLSessionWebSocketTask?
    private var pingTask: Task<Void, Never>?
    private var receiveTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var running = false
    private var isBackgroundMode = false
    private var reconnectAttempt = 0
    private var subscribedUserIds: Set<String> = []

    private let eventsContinuation: AsyncStream<Event>.Continuation
    public nonisolated let events: AsyncStream<Event>

    private let locationsContinuation: AsyncStream<LocationPoint>.Continuation
    public nonisolated let incomingLocations: AsyncStream<LocationPoint>

    public init(
        configuration: Configuration,
        session: URLSession = .shared
    ) {
        self.config = configuration
        self.session = session

        let (evStream, evCont) = AsyncStream<Event>.makeStream(bufferingPolicy: .bufferingNewest(256))
        self.events = evStream
        self.eventsContinuation = evCont

        let (locStream, locCont) = AsyncStream<LocationPoint>.makeStream(bufferingPolicy: .bufferingNewest(256))
        self.incomingLocations = locStream
        self.locationsContinuation = locCont
    }

    public func connect() async {
        running = true
        reconnectAttempt = 0
        await doConnect()
    }

    public func disconnect() async {
        running = false
        reconnectTask?.cancel()
        pingTask?.cancel()
        receiveTask?.cancel()
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        eventsContinuation.yield(.disconnected)
    }

    public func pushLocation(_ point: LocationPoint) async throws {
        let msg: [String: Any] = [
            "type": "location.push",
            "reqId": UUID().uuidString,
            "point": try encodedPoint(point)
        ]
        try await sendJSON(msg)
    }

    public func pushBatch(_ points: [LocationPoint]) async throws {
        let msg: [String: Any] = [
            "type": "location.batch",
            "reqId": UUID().uuidString,
            "points": try points.map { try encodedPoint($0) }
        ]
        try await sendJSON(msg)
    }

    public func subscribe(userId: String) async {
        subscribedUserIds.insert(userId)
        let msg: [String: Any] = ["type": "subscribe", "userId": userId]
        try? await sendJSON(msg)
    }

    public func unsubscribe(userId: String) async {
        subscribedUserIds.remove(userId)
        let msg: [String: Any] = ["type": "unsubscribe", "userId": userId]
        try? await sendJSON(msg)
    }

    /// Switch between foreground/background ping intervals.
    /// In background mode, ping interval increases to reduce radio wake-ups.
    /// If `disconnectInBackground` is enabled, the WebSocket is fully disconnected.
    public func setBackgroundMode(_ enabled: Bool) async {
        guard isBackgroundMode != enabled else { return }
        isBackgroundMode = enabled

        if enabled && config.disconnectInBackground {
            await disconnect()
            return
        }

        if !enabled && config.disconnectInBackground && !running {
            await connect()
            return
        }

        // Restart ping loop with appropriate interval
        if running {
            startPingLoop()
        }
    }

    // MARK: - Private

    private func doConnect() async {
        do {
            let token = try await config.tokenProvider.accessToken()
            let wsURL = makeWebSocketURL(from: config.baseURL)
            var request = URLRequest(url: wsURL)
            if !token.isEmpty {
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            }

            let ws = session.webSocketTask(with: request)
            self.webSocketTask = ws
            ws.resume()

            let authMsg: [String: Any] = ["type": "auth", "token": token]
            try await sendJSON(authMsg)

            reconnectAttempt = 0
            eventsContinuation.yield(.connected)

            for uid in subscribedUserIds {
                let subMsg: [String: Any] = ["type": "subscribe", "userId": uid]
                try? await sendJSON(subMsg)
            }

            startReceiveLoop()
            startPingLoop()
        } catch {
            eventsContinuation.yield(.error(error.localizedDescription))
            if running && config.autoReconnect {
                scheduleReconnect()
            }
        }
    }

    private func startReceiveLoop() {
        receiveTask?.cancel()
        receiveTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let ws = await self?.webSocketTask else { break }
                do {
                    let message = try await ws.receive()
                    let data: Data
                    switch message {
                    case .data(let d): data = d
                    case .string(let s): data = Data(s.utf8)
                    @unknown default: continue
                    }
                    await self?.handleServerMessage(data)
                } catch {
                    if await (self?.running ?? false) {
                        await self?.eventsContinuation.yield(.error(error.localizedDescription))
                        await self?.eventsContinuation.yield(.disconnected)
                        if await (self?.config.autoReconnect ?? false) {
                            await self?.scheduleReconnect()
                        }
                    }
                    break
                }
            }
        }
    }

    private func startPingLoop() {
        pingTask?.cancel()
        pingTask = Task { [weak self] in
            while !Task.isCancelled {
                let interval = await self?.activePingInterval ?? 30.0
                try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
                let msg: [String: Any] = ["type": "ping"]
                try? await self?.sendJSON(msg)
            }
        }
    }

    private var activePingInterval: TimeInterval {
        isBackgroundMode ? config.backgroundPingInterval : config.pingInterval
    }

    private func handleServerMessage(_ data: Data) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let type = json["type"] as? String else { return }

        switch type {
        case "auth.ok":
            break
        case "location.ack":
            let reqId = json["reqId"] as? String ?? ""
            let status = json["status"] as? String ?? "accepted"
            eventsContinuation.yield(.pushAcknowledged(reqId: reqId, status: status))
        case "location.batch_ack":
            let reqId = json["reqId"] as? String ?? ""
            let ids = json["acceptedIds"] as? [String] ?? []
            eventsContinuation.yield(.batchAcknowledged(reqId: reqId, acceptedIds: ids))
        case "location.update", "location":
            if let pointData = json["point"],
               let pointJSON = try? JSONSerialization.data(withJSONObject: pointData),
               let point = try? RTLSJSON.decoder().decode(LocationPoint.self, from: pointJSON) {
                locationsContinuation.yield(point)
                eventsContinuation.yield(.locationReceived(point))
            }
        case "subscribed":
            eventsContinuation.yield(.subscribed(userId: json["userId"] as? String ?? ""))
        case "pong":
            break
        case "error":
            eventsContinuation.yield(.error(json["message"] as? String ?? "Server error"))
        default:
            break
        }
    }

    private func scheduleReconnect() {
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            guard let self else { return }
            let attempt = await self.incrementReconnectAttempt()
            let exp = pow(2.0, Double(min(attempt - 1, 10)))
            let delay = min(await self.config.reconnectMaxDelay, await self.config.reconnectBaseDelay * exp)
            await self.eventsContinuation.yield(.reconnecting(attempt: attempt, delay: delay))
            try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))
            if await self.running { await self.doConnect() }
        }
    }

    private func incrementReconnectAttempt() -> Int {
        reconnectAttempt += 1
        return reconnectAttempt
    }

    private func sendJSON(_ obj: [String: Any]) async throws {
        let data = try JSONSerialization.data(withJSONObject: obj)
        let string = String(data: data, encoding: .utf8) ?? ""
        try await webSocketTask?.send(.string(string))
    }

    private func encodedPoint(_ point: LocationPoint) throws -> [String: Any] {
        let data = try RTLSJSON.encoder().encode(point)
        guard let dict = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw NSError(domain: "RTLSWebSocket", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to encode point"])
        }
        return dict
    }
}

private func makeWebSocketURL(from baseURL: URL) -> URL {
    let httpURL = baseURL
        .appendingPathComponent("v1")
        .appendingPathComponent("ws")
    guard var components = URLComponents(url: httpURL, resolvingAgainstBaseURL: false) else {
        return httpURL
    }
    if components.scheme == "https" { components.scheme = "wss" }
    if components.scheme == "http" { components.scheme = "ws" }
    return components.url ?? httpURL
}
