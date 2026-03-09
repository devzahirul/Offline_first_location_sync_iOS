import Foundation
import CoreMotion
import RTLSCore

/// Provides motion state changes using CMMotionActivityManager.
/// Requires NSMotionUsageDescription in Info.plist.
/// Applies 30-second hysteresis to prevent rapid state flapping.
public final class CMMotionStateProvider: MotionStateProvider, @unchecked Sendable {
    private let activityManager = CMMotionActivityManager()
    private let operationQueue = OperationQueue()

    private let stream: AsyncStream<MotionState>
    private let continuation: AsyncStream<MotionState>.Continuation

    private var lastEmittedState: MotionState = .unknown
    private var candidateState: MotionState = .unknown
    private var candidateSince: Date?
    private let hysteresisInterval: TimeInterval

    public var motionStates: AsyncStream<MotionState> { stream }

    public init(hysteresisInterval: TimeInterval = 30.0) {
        self.hysteresisInterval = hysteresisInterval

        let (s, c) = AsyncStream<MotionState>.makeStream(bufferingPolicy: .bufferingNewest(16))
        self.stream = s
        self.continuation = c

        operationQueue.maxConcurrentOperationCount = 1
        operationQueue.qualityOfService = .utility
    }

    public func start() {
        guard CMMotionActivityManager.isActivityAvailable() else { return }

        activityManager.startActivityUpdates(to: operationQueue) { [weak self] activity in
            guard let self, let activity else { return }
            let state = Self.mapActivity(activity)
            self.processState(state)
        }
    }

    public func stop() {
        activityManager.stopActivityUpdates()
        continuation.finish()
    }

    private func processState(_ newState: MotionState) {
        if newState == lastEmittedState {
            candidateState = newState
            candidateSince = nil
            return
        }

        if newState == candidateState {
            if let since = candidateSince, Date().timeIntervalSince(since) >= hysteresisInterval {
                lastEmittedState = newState
                candidateSince = nil
                continuation.yield(newState)
            }
        } else {
            candidateState = newState
            candidateSince = Date()
        }
    }

    private static func mapActivity(_ activity: CMMotionActivity) -> MotionState {
        if activity.automotive { return .driving }
        if activity.running { return .running }
        if activity.walking { return .walking }
        if activity.stationary { return .stationary }
        return .unknown
    }
}
