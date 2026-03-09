import Foundation

public struct LocationRecordingDecider: Sendable, Equatable {
    public var policy: TrackingPolicy
    /// Reject samples with horizontal accuracy worse than this (meters). 0 = no filter.
    public var maxAcceptableAccuracy: Double
    public private(set) var lastRecordedAt: Date?
    public private(set) var lastRecordedCoordinate: GeoCoordinate?

    public init(
        policy: TrackingPolicy,
        maxAcceptableAccuracy: Double = 100,
        lastRecordedAt: Date? = nil,
        lastRecordedCoordinate: GeoCoordinate? = nil
    ) {
        self.policy = policy
        self.maxAcceptableAccuracy = maxAcceptableAccuracy
        self.lastRecordedAt = lastRecordedAt
        self.lastRecordedCoordinate = lastRecordedCoordinate
    }

    public func shouldRecord(sampleAt: Date, coordinate: GeoCoordinate, horizontalAccuracy: Double? = nil) -> Bool {
        if maxAcceptableAccuracy > 0, let acc = horizontalAccuracy, acc > maxAcceptableAccuracy {
            return false
        }

        switch policy {
        case .time(let interval):
            guard interval > 0 else { return true }
            guard let lastRecordedAt else { return true }
            return sampleAt.timeIntervalSince(lastRecordedAt) >= interval

        case .distance(let meters):
            guard meters > 0 else { return true }
            guard let lastRecordedCoordinate else { return true }
            return coordinate.distance(to: lastRecordedCoordinate) >= meters

        case .adaptive:
            // Adaptive is resolved by the orchestrator into a concrete sub-policy.
            // If called directly, default to distance(25).
            guard let lastRecordedCoordinate else { return true }
            return coordinate.distance(to: lastRecordedCoordinate) >= 25.0

        case .paused:
            return false
        }
    }

    public mutating func markRecorded(sampleAt: Date, coordinate: GeoCoordinate) {
        lastRecordedAt = sampleAt
        lastRecordedCoordinate = coordinate
    }
}
