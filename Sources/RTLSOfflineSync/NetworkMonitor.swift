import Foundation
import Network

public enum ConnectionType: Sendable, Equatable {
    case wifi
    case cellular
    case unknown
}

public actor NetworkMonitor {
    private var monitor: NWPathMonitor?
    private let queue: DispatchQueue

    private var started = false
    private var online = false
    private var _connectionType: ConnectionType = .unknown

    private let statusStream: AsyncStream<Bool>
    private let continuation: AsyncStream<Bool>.Continuation

    public nonisolated var updates: AsyncStream<Bool> { statusStream }

    // Nonisolated access not available for actors; use connectionType() within actor-isolated contexts.

    public init() {
        self.monitor = nil
        self.queue = DispatchQueue(label: "RTLSyncKit.NetworkMonitor")

        let (stream, continuation) = AsyncStream<Bool>.makeStream(bufferingPolicy: .bufferingNewest(16))
        self.statusStream = stream
        self.continuation = continuation
    }

    public func start() {
        guard !started else { return }
        started = true

        let newMonitor = NWPathMonitor()
        newMonitor.pathUpdateHandler = { [weak self] path in
            let isOnline = path.status == .satisfied
            let connType: ConnectionType
            if path.usesInterfaceType(.wifi) {
                connType = .wifi
            } else if path.usesInterfaceType(.cellular) {
                connType = .cellular
            } else {
                connType = .unknown
            }
            Task { await self?.setOnline(isOnline, connectionType: connType) }
        }
        newMonitor.start(queue: queue)
        monitor = newMonitor
    }

    public func stop() {
        monitor?.cancel()
        monitor = nil
        started = false
    }

    public func isOnline() -> Bool {
        online
    }

    public func connectionType() -> ConnectionType {
        _connectionType
    }

    private func setOnline(_ value: Bool, connectionType: ConnectionType) {
        online = value
        _connectionType = connectionType
        continuation.yield(value)
    }
}
