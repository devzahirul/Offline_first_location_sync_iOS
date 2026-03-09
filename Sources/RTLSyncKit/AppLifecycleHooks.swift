import Foundation

#if canImport(UIKit) && os(iOS)
import UIKit
import RTLSWebSocket

/// Convenience hooks to trigger a flush on foreground/background transitions and (optionally) schedule BG processing.
///
/// This is intentionally small: many apps already have their own app-lifecycle wiring.
public final class RTLSAppLifecycleHooks: NSObject {
    private let client: LocationSyncClient
    private let backgroundProcessing: BackgroundProcessingConfiguration?
    private let webSocketClient: RealTimeLocationClient?

    private var started = false

    public init(
        client: LocationSyncClient,
        backgroundProcessing: BackgroundProcessingConfiguration? = nil,
        webSocketClient: RealTimeLocationClient? = nil
    ) {
        self.client = client
        self.backgroundProcessing = backgroundProcessing
        self.webSocketClient = webSocketClient
        super.init()
    }

    public func start() {
        guard !started else { return }
        started = true

        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(willEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
        center.addObserver(self, selector: #selector(didEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
        center.addObserver(self, selector: #selector(willTerminate), name: UIApplication.willTerminateNotification, object: nil)
    }

    public func stop() {
        guard started else { return }
        started = false
        NotificationCenter.default.removeObserver(self)
    }

    deinit {
        stop()
    }

    @objc private func willEnterForeground() {
        Task {
            await webSocketClient?.setBackgroundMode(false)
            await client.pullNow()
            await client.flushNow()
        }
    }

    @objc private func didEnterBackground() {
        Task { await webSocketClient?.setBackgroundMode(true) }
        // Schedule a background task so the system can run sync later (e.g. ~15 min),
        // even if the app is terminated after this. Location does NOT continue after terminate.
        if let backgroundProcessing {
            try? RTLSBackgroundSync.scheduleProcessingTask(configuration: backgroundProcessing)
        }
        // Best-effort quick drain before suspension.
        Task { await client.flushNow(maxBatches: 1) }
    }

    @objc private func willTerminate() {
        // Schedule again so we have a task queued for after terminate (sync only, no location).
        if let backgroundProcessing {
            try? RTLSBackgroundSync.scheduleProcessingTask(configuration: backgroundProcessing)
        }
        // Best-effort flush with very limited time.
        Task { await client.flushNow(maxBatches: 2) }
    }
}
#endif
