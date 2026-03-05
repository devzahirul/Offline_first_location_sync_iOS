import Flutter
import Foundation
import RTLSyncKit

public class RtlsFlutterPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {

    private var client: LocationSyncClient?
    private var eventSink: FlutterEventSink?
    private var eventTask: Task<Void, Never>?
    private var lifecycleRegistered = false

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(name: "com.rtls.flutter/rtls", binaryMessenger: registrar.messenger())
        let instance = RtlsFlutterPlugin()
        registrar.addMethodCallDelegate(instance, channel: channel)
        let eventChannel = FlutterEventChannel(name: "com.rtls.flutter/rtls_events", binaryMessenger: registrar.messenger())
        eventChannel.setStreamHandler(instance)
    }

    private func registerLifecycleObserversIfNeeded() {
        guard !lifecycleRegistered else { return }
        lifecycleRegistered = true
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(appWillEnterForeground), name: UIApplication.willEnterForegroundNotification, object: nil)
        center.addObserver(self, selector: #selector(appDidEnterBackground), name: UIApplication.didEnterBackgroundNotification, object: nil)
    }

    @objc private func appWillEnterForeground() {
        guard let client = client else { return }
        Task { await client.flushNow() }
    }

    @objc private func appDidEnterBackground() {
        guard let client = client else { return }
        Task { await client.flushNow(maxBatches: 2) }
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "configure":
            guard let args = call.arguments as? [String: Any],
                  let baseURLStr = args["baseUrl"] as? String,
                  let baseURL = URL(string: baseURLStr),
                  let userId = args["userId"] as? String,
                  let deviceId = args["deviceId"] as? String,
                  let accessToken = args["accessToken"] as? String else {
                result(FlutterError(code: "INVALID", message: "baseUrl, userId, deviceId, accessToken required", details: nil))
                return
            }
            let token = accessToken
            let provider = AuthTokenProvider { token }
            let databaseURL: URL
            do {
                databaseURL = try LocationSyncClientConfiguration.defaultDatabaseURL(directoryName: "RTLSyncKitFlutter")
            } catch {
                result(FlutterError(code: "CONFIG", message: error.localizedDescription, details: nil))
                return
            }

            let trackingPolicy: TrackingPolicy
            let intervalSec = (args["locationIntervalSeconds"] as? NSNumber)?.doubleValue
            let distanceM = (args["locationDistanceMeters"] as? NSNumber)?.doubleValue
            let significantOnly = (args["useSignificantLocationOnly"] as? NSNumber)?.boolValue ?? false
            if significantOnly {
                trackingPolicy = .distance(meters: 500)
            } else if let sec = intervalSec, sec > 0 {
                trackingPolicy = .time(interval: sec)
            } else if let m = distanceM, m > 0 {
                trackingPolicy = .distance(meters: m)
            } else {
                trackingPolicy = .default
            }

            let batchMax = (args["batchMaxSize"] as? NSNumber)?.intValue ?? 50
            let flushSec = (args["flushIntervalSeconds"] as? NSNumber)?.doubleValue ?? 10
            let maxAgeSec = (args["maxBatchAgeSeconds"] as? NSNumber)?.doubleValue ?? 60
            let batchingPolicy = BatchingPolicy(
                maxBatchSize: max(1, batchMax),
                flushInterval: max(1, flushSec),
                maxBatchAge: max(1, maxAgeSec)
            )

            var locationConfig = LocationProvider.Configuration()
            locationConfig.allowsBackgroundLocationUpdates = true
            locationConfig.pausesLocationUpdatesAutomatically = !significantOnly
            locationConfig.showsBackgroundLocationIndicator = true
            locationConfig.useSignificantLocationChanges = significantOnly
            if significantOnly {
                locationConfig.desiredAccuracy = kCLLocationAccuracyHundredMeters
            }
            switch trackingPolicy {
            case .time:
                locationConfig.distanceFilter = kCLDistanceFilterNone
            case .distance(let meters):
                locationConfig.distanceFilter = meters
            }

            let config = LocationSyncClientConfiguration(
                baseURL: baseURL,
                authTokenProvider: provider,
                userId: userId,
                deviceId: deviceId,
                trackingPolicy: trackingPolicy,
                batchingPolicy: batchingPolicy,
                databaseURL: databaseURL,
                locationProviderConfiguration: locationConfig
            )
            Task {
                do {
                    if let old = await MainActor.run(body: { self.client }) {
                        await old.stopTracking()
                    }
                    let c = try await LocationSyncClient(configuration: config)
                    await MainActor.run {
                        self.client = c
                        self.registerLifecycleObserversIfNeeded()
                        self.startEventLoop(client: c)
                        result(nil)
                    }
                } catch {
                    await MainActor.run {
                        result(FlutterError(code: "CONFIG", message: error.localizedDescription, details: nil))
                    }
                }
            }
        case "startTracking":
            guard let client = client else {
                result(FlutterError(code: "NOT_CONFIGURED", message: "Call configure first", details: nil))
                return
            }
            Task {
                await client.startTracking()
                await MainActor.run { result(nil) }
            }
        case "stopTracking":
            Task {
                await client?.stopTracking()
                await MainActor.run { result(nil) }
            }
        case "requestAlwaysAuthorization":
            Task {
                await client?.requestAlwaysAuthorization()
                await MainActor.run { result(nil) }
            }
        case "getStats":
            guard let client = client else {
                result(["pendingCount": -1, "oldestPendingRecordedAtMs": NSNull()])
                return
            }
            Task {
                let stats = await client.stats()
                let oldest: Any = stats.oldestPendingRecordedAt.map { Int($0.timeIntervalSince1970 * 1000) } as Any? ?? NSNull()
                await MainActor.run {
                    result(["pendingCount": stats.pendingCount, "oldestPendingRecordedAtMs": oldest])
                }
            }
        case "flushNow":
            Task {
                await client?.flushNow()
                await MainActor.run { result(nil) }
            }
        default:
            result(FlutterMethodNotImplemented)
        }
    }

    private func startEventLoop(client: LocationSyncClient) {
        eventTask?.cancel()
        eventTask = Task { [weak self] in
            guard let self = self else { return }
            for await event in client.events {
                if Task.isCancelled { break }
                await MainActor.run {
                    self.eventSink?(self.eventToMap(event))
                }
            }
        }
    }

    private func eventToMap(_ event: LocationSyncClientEvent) -> [String: Any] {
        switch event {
        case .recorded(let point):
            return [
                "type": "recorded",
                "point": [
                    "id": point.id.uuidString,
                    "userId": point.userId,
                    "deviceId": point.deviceId,
                    "recordedAtMs": Int(point.recordedAt.timeIntervalSince1970 * 1000),
                    "lat": point.coordinate.latitude,
                    "lng": point.coordinate.longitude
                ]
            ]
        case .syncEvent(let e):
            switch e {
            case .didUpload(let accepted, let rejected):
                return ["type": "syncEvent", "event": "uploadSucceeded", "accepted": accepted, "rejected": rejected]
            case .uploadFailed(let msg):
                return ["type": "syncEvent", "event": "uploadFailed", "message": msg]
            }
        case .error(let msg):
            return ["type": "error", "message": msg]
        case .trackingStarted:
            return ["type": "trackingStarted"]
        case .trackingStopped:
            return ["type": "trackingStopped"]
        case .authorizationChanged(let auth):
            return ["type": "authorizationChanged", "authorization": String(describing: auth)]
        }
    }

    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        eventSink = events
        return nil
    }

    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        eventSink = nil
        return nil
    }
}
