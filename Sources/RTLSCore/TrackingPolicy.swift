import Foundation

public enum TrackingPolicy: Equatable, Sendable {
    /// Record at most once per interval (best-effort; depends on CoreLocation delivery cadence).
    case time(interval: TimeInterval)
    /// Record when moved at least N meters from the last recorded point.
    case distance(meters: Double)
    /// Adapts tracking parameters based on motion state.
    /// Each sub-policy is applied when the corresponding motion state is detected.
    case adaptive(stationary: TrackingPolicy, walking: TrackingPolicy, driving: TrackingPolicy)
    /// Tracking paused — no GPS updates, near-zero battery usage.
    case paused

    public static let `default` = TrackingPolicy.distance(meters: 25)

    /// Default adaptive policy: stationary pauses GPS, walking uses 50m filter, driving uses 10m filter.
    public static let defaultAdaptive = TrackingPolicy.adaptive(
        stationary: .paused,
        walking: .distance(meters: 50),
        driving: .distance(meters: 10)
    )
}

/// Current motion state of the device, used by adaptive tracking policies.
public enum MotionState: Sendable, Equatable {
    case stationary
    case walking
    case running
    case driving
    case unknown
}

/// Provides a stream of motion state changes.
public protocol MotionStateProvider: Sendable {
    var motionStates: AsyncStream<MotionState> { get }
}

