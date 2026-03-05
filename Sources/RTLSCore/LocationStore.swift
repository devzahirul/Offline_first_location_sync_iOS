import Foundation

public struct PendingStats: Sendable, Equatable {
    public var count: Int
    public var oldestRecordedAt: Date?

    public init(count: Int, oldestRecordedAt: Date?) {
        self.count = count
        self.oldestRecordedAt = oldestRecordedAt
    }
}

public protocol LocationStore: Sendable {
    func insert(points: [LocationPoint]) async throws

    func fetchPendingPoints(limit: Int) async throws -> [LocationPoint]
    func pendingCount() async throws -> Int
    func oldestPendingRecordedAt() async throws -> Date?

    /// Single query returning both count and oldest timestamp — avoids 2 round-trips in hot path.
    func pendingStats() async throws -> PendingStats

    func markSent(pointIds: [UUID], sentAt: Date) async throws
    func markFailed(pointIds: [UUID], errorMessage: String) async throws
}

