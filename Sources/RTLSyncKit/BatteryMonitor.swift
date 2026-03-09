import Foundation

#if canImport(UIKit) && os(iOS)
import UIKit
import RTLSCore

/// Monitors device battery level and emits changes as an AsyncStream.
public final class BatteryMonitor: @unchecked Sendable {
    private let stream: AsyncStream<Float>
    private let continuation: AsyncStream<Float>.Continuation
    private var observerToken: NSObjectProtocol?

    /// Stream of battery level values (0.0–1.0). Emits on each system notification.
    public var levels: AsyncStream<Float> { stream }

    public init() {
        let (s, c) = AsyncStream<Float>.makeStream(bufferingPolicy: .bufferingNewest(8))
        self.stream = s
        self.continuation = c
    }

    @MainActor
    public func start() {
        UIDevice.current.isBatteryMonitoringEnabled = true
        continuation.yield(UIDevice.current.batteryLevel)

        observerToken = NotificationCenter.default.addObserver(
            forName: UIDevice.batteryLevelDidChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            self?.continuation.yield(UIDevice.current.batteryLevel)
        }
    }

    public func stop() {
        if let token = observerToken {
            NotificationCenter.default.removeObserver(token)
            observerToken = nil
        }
        continuation.finish()
    }

    deinit {
        stop()
    }
}
#endif
