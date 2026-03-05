import CoreLocation
import Foundation
import RTLSCore
import RTLSData
import RTLSPlatformiOS
import RTLSSync

public enum LocationSyncClientEvent: Sendable, Equatable {
    case authorizationChanged(LocationAuthorization)
    case trackingStarted
    case trackingStopped
    case recorded(LocationPoint)
    case syncEvent(SyncEngineEvent)
    case error(String)
}

public struct LocationSyncClientStats: Sendable, Equatable {
    public var pendingCount: Int
    public var oldestPendingRecordedAt: Date?

    public init(pendingCount: Int, oldestPendingRecordedAt: Date?) {
        self.pendingCount = pendingCount
        self.oldestPendingRecordedAt = oldestPendingRecordedAt
    }
}

public struct LocationSyncClientConfiguration: Sendable {
    public var baseURL: URL
    public var authTokenProvider: AuthTokenProvider
    public var userId: String
    public var deviceId: String
    public var trackingPolicy: TrackingPolicy
    public var batchingPolicy: BatchingPolicy
    public var retryPolicy: SyncRetryPolicy
    public var retentionPolicy: RetentionPolicy
    public var databaseURL: URL
    public var locationProviderConfiguration: LocationProvider.Configuration

    public init(
        baseURL: URL,
        authTokenProvider: AuthTokenProvider,
        userId: String,
        deviceId: String,
        trackingPolicy: TrackingPolicy = .default,
        batchingPolicy: BatchingPolicy = BatchingPolicy(),
        retryPolicy: SyncRetryPolicy = .default,
        retentionPolicy: RetentionPolicy = .recommended,
        databaseURL: URL,
        locationProviderConfiguration: LocationProvider.Configuration? = nil
    ) {
        self.baseURL = baseURL
        self.authTokenProvider = authTokenProvider
        self.userId = userId
        self.deviceId = deviceId
        self.trackingPolicy = trackingPolicy
        self.batchingPolicy = batchingPolicy
        self.retryPolicy = retryPolicy
        self.retentionPolicy = retentionPolicy
        self.databaseURL = databaseURL

        if let locationProviderConfiguration {
            self.locationProviderConfiguration = locationProviderConfiguration
        } else {
            // Default distance filter matches the chosen tracking policy.
            let distanceFilter: Double
            switch trackingPolicy {
            case .time:
                distanceFilter = kCLDistanceFilterNone
            case .distance(let meters):
                distanceFilter = meters
            }
            self.locationProviderConfiguration = LocationProvider.Configuration(distanceFilter: distanceFilter)
        }
    }

    public static func defaultDatabaseURL(directoryName: String = "RTLSyncKit") throws -> URL {
        let support = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        let dir = support.appendingPathComponent(directoryName, isDirectory: true)
        return dir.appendingPathComponent("rtlsync.sqlite", isDirectory: false)
    }
}

public actor LocationSyncClient {
    private let config: LocationSyncClientConfiguration

    private let store: SQLiteLocationStore
    private let api: URLSessionLocationSyncAPI
    private let syncEngine: SyncEngine
    private let provider: LocationProvider

    private var locationTask: Task<Void, Never>?
    private var syncEventsTask: Task<Void, Never>?

    private var isTracking = false
    private var recordingDecider: LocationRecordingDecider

    private let eventsStream: AsyncStream<LocationSyncClientEvent>
    private let continuation: AsyncStream<LocationSyncClientEvent>.Continuation
    public nonisolated var events: AsyncStream<LocationSyncClientEvent> { eventsStream }

    public init(configuration: LocationSyncClientConfiguration) async throws {
        self.config = configuration
        self.store = try await SQLiteLocationStore(databaseURL: configuration.databaseURL)
        self.api = URLSessionLocationSyncAPI(baseURL: configuration.baseURL, tokenProvider: configuration.authTokenProvider)
        self.syncEngine = SyncEngine(
            store: store,
            api: api,
            batchingPolicy: configuration.batchingPolicy,
            retryPolicy: configuration.retryPolicy,
            retentionPolicy: configuration.retentionPolicy
        )
        self.recordingDecider = LocationRecordingDecider(policy: configuration.trackingPolicy)

        let (stream, continuation) = AsyncStream<LocationSyncClientEvent>.makeStream(bufferingPolicy: .bufferingNewest(256))
        self.eventsStream = stream
        self.continuation = continuation

        self.provider = await MainActor.run {
            LocationProvider(configuration: configuration.locationProviderConfiguration)
        }

        await wireSyncEvents()
        await wireProviderEvents()
    }

    public func requestAlwaysAuthorization() async {
        await MainActor.run {
            provider.requestAlwaysAuthorization()
        }
    }

    public func requestWhenInUseAuthorization() async {
        await MainActor.run {
            provider.requestWhenInUseAuthorization()
        }
    }

    public func startTracking() async {
        guard !isTracking else { return }
        isTracking = true

        await syncEngine.start()

        await MainActor.run {
            provider.startUpdatingLocation()
        }

        continuation.yield(.trackingStarted)
    }

    public func stopTracking() async {
        guard isTracking else { return }
        isTracking = false

        await MainActor.run {
            provider.stopUpdatingLocation()
        }

        await syncEngine.stop()

        continuation.yield(.trackingStopped)
    }

    public func flushNow(maxBatches: Int? = nil) async {
        await syncEngine.flushNow(maxBatches: maxBatches)
    }

    /// Run one pull cycle (fetch from server, apply to local). No-op if engine is upload-only.
    public func pullNow() async {
        await syncEngine.pullNow()
    }

    public func stats() async -> LocationSyncClientStats {
        do {
            let count = try await store.pendingCount()
            let oldest = try await store.oldestPendingRecordedAt()
            return LocationSyncClientStats(pendingCount: count, oldestPendingRecordedAt: oldest)
        } catch {
            return LocationSyncClientStats(pendingCount: -1, oldestPendingRecordedAt: nil)
        }
    }

    /// Convenience for UI/debugging: load recent points for the current (userId, deviceId).
    /// Returned in chronological order (oldest -> newest).
    public func recentPoints(limit: Int) async -> [LocationPoint] {
        do {
            let points = try await store.fetchRecentPoints(userId: config.userId, deviceId: config.deviceId, limit: limit)
            return points.reversed()
        } catch {
            return []
        }
    }

    /// Convenience for UI/debugging: load pending points for the current (userId, deviceId).
    /// Returned in chronological order (oldest -> newest).
    public func pendingPoints(limit: Int) async -> [LocationPoint] {
        do {
            return try await store.fetchPendingPoints(userId: config.userId, deviceId: config.deviceId, limit: limit)
        } catch {
            return []
        }
    }

    public func subscribeToUserLocations(userId: String) async -> AsyncThrowingStream<LocationPoint, Error> {
        let subscriber = WebSocketLocationSubscriber(
            configuration: WebSocketLocationSubscriberConfiguration(
                baseURL: config.baseURL,
                tokenProvider: config.authTokenProvider
            )
        )
        return await subscriber.subscribe(userId: userId)
    }

    // MARK: - Wiring

    private func wireProviderEvents() async {
        locationTask?.cancel()
        locationTask = Task { @MainActor [provider] in
            for await event in provider.events {
                await self.handle(providerEvent: event)
            }
        }
    }

    private func wireSyncEvents() async {
        syncEventsTask?.cancel()
        syncEventsTask = Task { [syncEngine] in
            for await e in syncEngine.events {
                continuation.yield(.syncEvent(e))
            }
        }
    }

    private func handle(providerEvent: LocationProviderEvent) async {
        switch providerEvent {
        case .authorizationChanged(let auth):
            continuation.yield(.authorizationChanged(auth))

        case .didFail(let message):
            continuation.yield(.error(message))

        case .didUpdate(let sample):
            guard isTracking else { return }
            guard recordingDecider.shouldRecord(sampleAt: sample.recordedAt, coordinate: sample.coordinate, horizontalAccuracy: sample.horizontalAccuracy) else { return }

            let point = LocationPoint(
                userId: config.userId,
                deviceId: config.deviceId,
                recordedAt: sample.recordedAt,
                coordinate: sample.coordinate,
                horizontalAccuracy: sample.horizontalAccuracy,
                verticalAccuracy: sample.verticalAccuracy,
                altitude: sample.altitude,
                speed: sample.speed,
                course: sample.course
            )

            do {
                try await store.insert(points: [point])
                recordingDecider.markRecorded(sampleAt: sample.recordedAt, coordinate: sample.coordinate)

                continuation.yield(.recorded(point))
                await syncEngine.notifyNewData()
            } catch {
                continuation.yield(.error(String(describing: error)))
            }
        }
    }
}
