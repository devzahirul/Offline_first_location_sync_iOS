import Foundation

/// Extension of LocationStore that supports pruning of sent location points.
/// Implementations should provide atomic markSentAndPrune to prevent data loss on crash.
public protocol SentPointsPrunableLocationStore: LocationStore, Sendable {
    /// Deletes points that have been successfully sent and are older than the provided cutoff date.
    func pruneSentPoints(olderThan cutoff: Date) async throws

    /// Atomic operation: mark points as sent AND prune old sent points in a single transaction.
    /// Default implementation calls separate methods (non-atomic), implementations should override.
    func markSentAndPrune(pointIds: [UUID], sentAt: Date, olderThanRecordedAt: Date?) async throws
}

/// Default non-atomic implementation for backwards compatibility
public extension SentPointsPrunableLocationStore {
    func markSentAndPrune(pointIds: [UUID], sentAt: Date, olderThanRecordedAt: Date?) async throws {
        try await markSent(pointIds: pointIds, sentAt: sentAt)
        if let olderThan = olderThanRecordedAt {
            try await pruneSentPoints(olderThan: olderThan)
        }
    }
}
