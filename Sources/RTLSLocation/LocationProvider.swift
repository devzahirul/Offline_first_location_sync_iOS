import CoreLocation
import Foundation
import RTLSCore

public enum LocationAuthorization: Equatable, Sendable {
    case notDetermined
    case restricted
    case denied
    case authorizedWhenInUse
    case authorizedAlways

    init(_ status: CLAuthorizationStatus) {
        switch status {
        case .notDetermined: self = .notDetermined
        case .restricted: self = .restricted
        case .denied: self = .denied
        case .authorizedWhenInUse: self = .authorizedWhenInUse
        case .authorizedAlways: self = .authorizedAlways
        @unknown default: self = .notDetermined
        }
    }
}

public struct RawLocationSample: Equatable, Sendable {
    public var recordedAt: Date
    public var coordinate: GeoCoordinate
    public var horizontalAccuracy: Double?
    public var verticalAccuracy: Double?
    public var altitude: Double?
    public var speed: Double?
    public var course: Double?

    public init(
        recordedAt: Date,
        coordinate: GeoCoordinate,
        horizontalAccuracy: Double? = nil,
        verticalAccuracy: Double? = nil,
        altitude: Double? = nil,
        speed: Double? = nil,
        course: Double? = nil
    ) {
        self.recordedAt = recordedAt
        self.coordinate = coordinate
        self.horizontalAccuracy = horizontalAccuracy
        self.verticalAccuracy = verticalAccuracy
        self.altitude = altitude
        self.speed = speed
        self.course = course
    }
}

public enum LocationProviderEvent: Sendable, Equatable {
    case authorizationChanged(LocationAuthorization)
    case didUpdate(RawLocationSample)
    case didFail(String)
}

@MainActor
public final class LocationProvider: NSObject {
    public struct Configuration: Sendable, Equatable {
        public var desiredAccuracy: CLLocationAccuracy
        public var activityType: CLActivityType
        public var distanceFilter: CLLocationDistance
        public var pausesLocationUpdatesAutomatically: Bool
        public var allowsBackgroundLocationUpdates: Bool
        public var showsBackgroundLocationIndicator: Bool
        /// When true, use significant-change location service instead of continuous updates.
        /// The system can relaunch the app when a significant change occurs, even after the app was terminated.
        /// Updates are less frequent (~500m or more) but very battery efficient.
        public var useSignificantLocationChanges: Bool

        public init(
            desiredAccuracy: CLLocationAccuracy = kCLLocationAccuracyNearestTenMeters,
            activityType: CLActivityType = .other,
            distanceFilter: CLLocationDistance = 10.0,
            pausesLocationUpdatesAutomatically: Bool = true,
            allowsBackgroundLocationUpdates: Bool = true,
            showsBackgroundLocationIndicator: Bool = false,
            useSignificantLocationChanges: Bool = false
        ) {
            self.desiredAccuracy = desiredAccuracy
            self.activityType = activityType
            self.distanceFilter = distanceFilter
            self.pausesLocationUpdatesAutomatically = pausesLocationUpdatesAutomatically
            self.allowsBackgroundLocationUpdates = allowsBackgroundLocationUpdates
            self.showsBackgroundLocationIndicator = showsBackgroundLocationIndicator
            self.useSignificantLocationChanges = useSignificantLocationChanges
        }
    }

    private let manager: CLLocationManager
    private var configuration: Configuration

    private let eventsStream: AsyncStream<LocationProviderEvent>
    private let continuation: AsyncStream<LocationProviderEvent>.Continuation

    public var events: AsyncStream<LocationProviderEvent> { eventsStream }

    public init(
        configuration: Configuration = Configuration(),
        manager: CLLocationManager = CLLocationManager()
    ) {
        self.manager = manager
        self.configuration = configuration

        let (stream, continuation) = AsyncStream<LocationProviderEvent>.makeStream(bufferingPolicy: .bufferingNewest(256))
        self.eventsStream = stream
        self.continuation = continuation

        super.init()

        manager.delegate = self
        manager.desiredAccuracy = configuration.desiredAccuracy
        manager.activityType = configuration.activityType
        manager.distanceFilter = configuration.distanceFilter
        manager.pausesLocationUpdatesAutomatically = configuration.pausesLocationUpdatesAutomatically
        // `allowsBackgroundLocationUpdates = true` will throw an Objective-C exception at runtime
        // if the host app has not enabled Background Modes -> Location updates (UIBackgroundModes includes "location").
        //
        // Swift cannot catch NSException, so we must guard it.
        if configuration.allowsBackgroundLocationUpdates && Self.isLocationBackgroundModeEnabled() {
            manager.allowsBackgroundLocationUpdates = true
        } else {
            manager.allowsBackgroundLocationUpdates = false
            if configuration.allowsBackgroundLocationUpdates && !Self.isLocationBackgroundModeEnabled() {
                continuation.yield(
                    .didFail(
                        "Background location updates requested, but UIBackgroundModes is missing \"location\". Enable Background Modes -> Location updates in Xcode or set UIBackgroundModes=[\"location\", ...] in Info.plist."
                    )
                )
            }
        }
        #if os(iOS)
        manager.showsBackgroundLocationIndicator = configuration.showsBackgroundLocationIndicator
        #endif

        continuation.yield(.authorizationChanged(LocationAuthorization(manager.authorizationStatus)))
    }

    public func requestWhenInUseAuthorization() {
        manager.requestWhenInUseAuthorization()
    }

    public func requestAlwaysAuthorization() {
        manager.requestAlwaysAuthorization()
    }

    public func startUpdatingLocation() {
        if configuration.useSignificantLocationChanges {
            manager.startMonitoringSignificantLocationChanges()
        } else {
            manager.startUpdatingLocation()
        }
    }

    public func stopUpdatingLocation() {
        if configuration.useSignificantLocationChanges {
            manager.stopMonitoringSignificantLocationChanges()
        } else {
            manager.stopUpdatingLocation()
        }
    }

    /// Reconfigure the location manager without stop/restart cycle.
    /// Useful for adapting accuracy/distance filter based on motion state or power policy.
    public func reconfigure(_ newConfig: Configuration) {
        let wasSignificant = configuration.useSignificantLocationChanges
        let isSignificant = newConfig.useSignificantLocationChanges

        configuration = newConfig
        manager.desiredAccuracy = newConfig.desiredAccuracy
        manager.activityType = newConfig.activityType
        manager.distanceFilter = newConfig.distanceFilter
        manager.pausesLocationUpdatesAutomatically = newConfig.pausesLocationUpdatesAutomatically

        // Switch between significant-change and continuous if needed
        if wasSignificant != isSignificant {
            if wasSignificant {
                manager.stopMonitoringSignificantLocationChanges()
                manager.startUpdatingLocation()
            } else {
                manager.stopUpdatingLocation()
                manager.startMonitoringSignificantLocationChanges()
            }
        }
    }

    // MARK: - Private

    private static func isLocationBackgroundModeEnabled() -> Bool {
        // We only trust the correct plist type for this key (Array of String).
        // If the key is missing or the type is wrong, iOS will not treat the app as backgroundable for location updates.
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "UIBackgroundModes") else { return false }
        guard let modes = raw as? [String] else { return false }
        return modes.contains("location")
    }
}

extension LocationProvider: @preconcurrency CLLocationManagerDelegate {
    public func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        continuation.yield(.authorizationChanged(LocationAuthorization(manager.authorizationStatus)))
    }

    public func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        continuation.yield(.didFail(String(describing: error)))
    }

    public func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard !locations.isEmpty else { return }

        for loc in locations {
            let sample = RawLocationSample(
                recordedAt: loc.timestamp,
                coordinate: GeoCoordinate(latitude: loc.coordinate.latitude, longitude: loc.coordinate.longitude),
                horizontalAccuracy: loc.horizontalAccuracy >= 0 ? loc.horizontalAccuracy : nil,
                verticalAccuracy: loc.verticalAccuracy >= 0 ? loc.verticalAccuracy : nil,
                altitude: loc.altitude,
                speed: loc.speed >= 0 ? loc.speed : nil,
                course: loc.course >= 0 ? loc.course : nil
            )
            continuation.yield(.didUpdate(sample))
        }
    }
}
