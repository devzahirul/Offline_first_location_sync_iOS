import Foundation

public enum PowerAction: Sendable, Equatable {
    case normal
    case reducedTracking
    case wifiOnlySync
    case pauseTracking
}

public struct PowerPolicy: Sendable, Equatable {
    /// Battery level (0.0–1.0) below which low-battery actions kick in.
    public var lowBatteryThreshold: Double
    /// Battery level (0.0–1.0) below which critical actions kick in.
    public var criticalBatteryThreshold: Double
    /// Action to take when battery falls below lowBatteryThreshold.
    public var lowBatteryAction: PowerAction
    /// Action to take when battery falls below criticalBatteryThreshold.
    public var criticalBatteryAction: PowerAction

    public init(
        lowBatteryThreshold: Double = 0.15,
        criticalBatteryThreshold: Double = 0.05,
        lowBatteryAction: PowerAction = .reducedTracking,
        criticalBatteryAction: PowerAction = .pauseTracking
    ) {
        self.lowBatteryThreshold = lowBatteryThreshold
        self.criticalBatteryThreshold = criticalBatteryThreshold
        self.lowBatteryAction = lowBatteryAction
        self.criticalBatteryAction = criticalBatteryAction
    }

    /// Determine the appropriate action for the current battery level.
    public func action(forBatteryLevel level: Double) -> PowerAction {
        if level <= criticalBatteryThreshold { return criticalBatteryAction }
        if level <= lowBatteryThreshold { return lowBatteryAction }
        return .normal
    }
}
