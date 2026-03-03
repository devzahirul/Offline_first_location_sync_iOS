// React Native native module bridging to RTLSyncKit (iOS).
// The host app must add the Swift package (this repo's Package.swift) in Xcode.

import Foundation
import React
import RTLSyncKit

private let eventRecorded = "rtls_recorded"
private let eventSyncEvent = "rtls_syncEvent"
private let eventError = "rtls_error"
private let eventAuthorizationChanged = "rtls_authorizationChanged"
private let eventTrackingStarted = "rtls_trackingStarted"
private let eventTrackingStopped = "rtls_trackingStopped"

private func locationPointToDict(_ point: LocationPoint) -> [String: Any] {
    var dict: [String: Any] = [
        "id": point.id.uuidString,
        "userId": point.userId,
        "deviceId": point.deviceId,
        "recordedAt": Int(point.recordedAt.timeIntervalSince1970 * 1000),
        "lat": point.coordinate.latitude,
        "lng": point.coordinate.longitude,
    ]
    if let v = point.horizontalAccuracy { dict["horizontalAccuracy"] = v }
    if let v = point.verticalAccuracy { dict["verticalAccuracy"] = v }
    if let v = point.altitude { dict["altitude"] = v }
    if let v = point.speed { dict["speed"] = v }
    if let v = point.course { dict["course"] = v }
    return dict
}

private func authStatusString(_ auth: LocationAuthorization) -> String {
    switch auth {
    case .notDetermined: return "notDetermined"
    case .restricted: return "restricted"
    case .denied: return "denied"
    case .authorizedWhenInUse: return "authorizedWhenInUse"
    case .authorizedAlways: return "authorizedAlways"
    }
}

@objc(RTLSyncModule)
class RTLSyncModule: RCTEventEmitter {

    private var client: LocationSyncClient?
    private var eventTask: Task<Void, Never>?

    override init() {
        super.init()
    }

    override static func requiresMainQueueSetup() -> Bool {
        return false
    }

    override func supportedEvents() -> [String]! {
        return [
            eventRecorded,
            eventSyncEvent,
            eventError,
            eventAuthorizationChanged,
            eventTrackingStarted,
            eventTrackingStopped,
        ]
    }

    private func startEventLoop(client: LocationSyncClient) {
        eventTask?.cancel()
        eventTask = Task { [weak self] in
            guard let self = self else { return }
            for await event in client.events {
                if Task.isCancelled { break }
                await self.emit(event)
            }
        }
    }

    private func emit(_ event: LocationSyncClientEvent) async {
        let (name, body): (String, [String: Any]?) = switch event {
        case .recorded(let point):
            (eventRecorded, locationPointToDict(point))
        case .syncEvent(let e):
            let payload: [String: Any] = switch e {
            case .didUpload(let accepted, let rejected):
                ["type": "uploadSucceeded", "accepted": accepted, "rejected": rejected]
            case .uploadFailed(let msg):
                ["type": "uploadFailed", "message": msg]
            }
            (eventSyncEvent, payload)
        case .error(let msg):
            (eventError, ["message": msg])
        case .authorizationChanged(let auth):
            (eventAuthorizationChanged, ["status": authStatusString(auth)])
        case .trackingStarted:
            (eventTrackingStarted, [:])
        case .trackingStopped:
            (eventTrackingStopped, [:])
        }
        await MainActor.run {
            self.sendEvent(withName: name, body: body ?? [:])
        }
    }

    @objc func configure(_ config: NSDictionary, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let baseURLString = config["baseURL"] as? String,
              let baseURL = URL(string: baseURLString),
              let userId = config["userId"] as? String,
              let deviceId = config["deviceId"] as? String,
              let accessToken = config["accessToken"] as? String else {
            reject("RTLSync", "configure requires baseURL, userId, deviceId, accessToken", nil)
            return
        }
        let token = accessToken
        let provider = AuthTokenProvider { token }
        let databaseURL: URL
        do {
            databaseURL = try LocationSyncClientConfiguration.defaultDatabaseURL(directoryName: "RTLSyncKitRN")
        } catch {
            reject("RTLSync", "Failed to get database URL: \(error)", error as NSError)
            return
        }
        let configuration = LocationSyncClientConfiguration(
            baseURL: baseURL,
            authTokenProvider: provider,
            userId: userId,
            deviceId: deviceId,
            databaseURL: databaseURL
        )
        Task { [weak self] in
            guard let self = self else { return }
            do {
                let c = try await LocationSyncClient(configuration: configuration)
                self.client = c
                self.startEventLoop(client: c)
                await MainActor.run { resolve(NSNull()) }
            } catch {
                await MainActor.run { reject("RTLSync", error.localizedDescription, error as NSError) }
            }
        }
    }

    @objc func startTracking(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let client = client else {
            reject("RTLSync", "Call configure first", nil)
            return
        }
        Task {
            await client.startTracking()
            await MainActor.run { resolve(NSNull()) }
        }
    }

    @objc func stopTracking(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let client = client else {
            reject("RTLSync", "Call configure first", nil)
            return
        }
        Task {
            await client.stopTracking()
            await MainActor.run { resolve(NSNull()) }
        }
    }

    @objc func requestAlwaysAuthorization(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let client = client else {
            reject("RTLSync", "Call configure first", nil)
            return
        }
        Task {
            await client.requestAlwaysAuthorization()
            await MainActor.run { resolve(NSNull()) }
        }
    }

    @objc func getStats(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let client = client else {
            reject("RTLSync", "Call configure first", nil)
            return
        }
        Task {
            let stats = await client.stats()
            let oldestMs: NSNumber? = stats.oldestPendingRecordedAt.map { NSNumber(value: Int($0.timeIntervalSince1970 * 1000)) }
            await MainActor.run {
                resolve([
                    "pendingCount": stats.pendingCount,
                    "oldestPendingRecordedAt": oldestMs ?? NSNull(),
                ])
            }
        }
    }

    @objc func flushNow(_ resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
        guard let client = client else {
            reject("RTLSync", "Call configure first", nil)
            return
        }
        Task {
            await client.flushNow()
            await MainActor.run { resolve(NSNull()) }
        }
    }
}
