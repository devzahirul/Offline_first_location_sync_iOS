import Foundation
import RTLSCore

public enum SyncEngineEvent: Sendable, Equatable {
    case didUpload(accepted: Int, rejected: Int)
    case uploadFailed(String)
    case didPull(count: Int)
    case pullFailed(String)
}

public actor SyncEngine {
    private let store: LocationStore
    private let bidirectionalStore: (any BidirectionalLocationStore)?
    private let api: LocationSyncAPI
    private let pullAPI: (any LocationPullAPI)?
    private let mergeStrategy: (any LocationMergeStrategy)?
    private let batching: BatchingPolicy
    private let retryPolicy: SyncRetryPolicy
    private let retentionPolicy: RetentionPolicy
    private let network: NetworkMonitor
    private let pullInterval: TimeInterval?
    private let pullOnForeground: Bool

    private var timerTask: Task<Void, Never>?
    private var networkTask: Task<Void, Never>?
    private var pullTask: Task<Void, Never>?
    private var notifyDebounceTask: Task<Void, Never>?
    private var flushing = false

    private var failureCount = 0
    private var nextAllowedFlushAt: Date?
    private var lastPruneAt: Date?
    private var lastPullAt: Date?
    private var pendingInsertsSinceLastFlush = 0

    private let eventsStream: AsyncStream<SyncEngineEvent>
    private let continuation: AsyncStream<SyncEngineEvent>.Continuation
    public nonisolated var events: AsyncStream<SyncEngineEvent> { eventsStream }

    public init(
        store: LocationStore,
        api: LocationSyncAPI,
        batchingPolicy: BatchingPolicy,
        retryPolicy: SyncRetryPolicy = .default,
        retentionPolicy: RetentionPolicy = .recommended,
        network: NetworkMonitor = NetworkMonitor(),
        pullAPI: (any LocationPullAPI)? = nil,
        mergeStrategy: (any LocationMergeStrategy)? = nil,
        pullInterval: TimeInterval? = nil,
        pullOnForeground: Bool = true
    ) {
        self.store = store
        self.bidirectionalStore = store as? any BidirectionalLocationStore
        self.api = api
        self.pullAPI = pullAPI
        self.mergeStrategy = mergeStrategy
        self.batching = batchingPolicy
        self.retryPolicy = retryPolicy
        self.retentionPolicy = retentionPolicy
        self.network = network
        self.pullInterval = pullInterval
        self.pullOnForeground = pullOnForeground

        let (stream, continuation) = AsyncStream<SyncEngineEvent>.makeStream(bufferingPolicy: .bufferingNewest(64))
        self.eventsStream = stream
        self.continuation = continuation
    }

    public func start() {
        Task { await network.start() }

        timerTask?.cancel()
        timerTask = Task { [batching] in
            while !Task.isCancelled {
                let interval = max(1.0, batching.flushInterval)
                try? await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
                await self.flushIfNeeded(force: false)
            }
        }

        networkTask?.cancel()
        networkTask = Task {
            for await online in network.updates {
                if online {
                    await self.flushIfNeeded(force: false)
                }
            }
        }

        if pullAPI != nil, let bidir = bidirectionalStore {
            let interval = pullInterval ?? 60.0
            pullTask?.cancel()
            pullTask = Task { [pullAPI, mergeStrategy] in
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: UInt64(max(1.0, interval) * 1_000_000_000))
                    await self.runPullOnce(pullAPI: pullAPI!, store: bidir, mergeStrategy: mergeStrategy)
                }
            }
        }
    }

    public func stop() {
        timerTask?.cancel()
        timerTask = nil

        networkTask?.cancel()
        networkTask = nil

        pullTask?.cancel()
        pullTask = nil

        Task { await network.stop() }
    }

    public func notifyNewData() {
        pendingInsertsSinceLastFlush += 1
        if pendingInsertsSinceLastFlush >= batching.maxBatchSize {
            Task { await flushIfNeeded(force: false) }
            return
        }
        notifyDebounceTask?.cancel()
        notifyDebounceTask = Task {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            await flushIfNeeded(force: false)
        }
    }

    public func flushNow(maxBatches: Int? = nil) async {
        await flushIfNeeded(force: true, maxBatches: maxBatches)
    }

    /// Run one pull cycle (fetch from server, apply to local). No-op if pull or bidirectional store not configured.
    public func pullNow() async {
        guard let pullAPI, let bidir = bidirectionalStore else { return }
        await runPullOnce(pullAPI: pullAPI, store: bidir, mergeStrategy: mergeStrategy)
    }

    private func flushIfNeeded(force: Bool, maxBatches: Int? = nil) async {
        guard !flushing else { return }
        guard !Task.isCancelled else { return }

        if !force, let nextAllowedFlushAt, Date() < nextAllowedFlushAt {
            return
        }
        guard await network.isOnline() else { return }

        do {
            let should = try await shouldFlush(force: force)
            guard should else { return }
        } catch {
            continuation.yield(.uploadFailed(String(describing: error)))
            return
        }

        flushing = true
        defer { flushing = false }

        var batchesProcessed = 0
        while await network.isOnline() {
            if Task.isCancelled { break }
            if let maxBatches, batchesProcessed >= maxBatches { break }

            do {
                let pending = try await store.fetchPendingPoints(limit: batching.maxBatchSize)
                if pending.isEmpty { break }

                let result = try await api.upload(batch: LocationUploadBatch(points: pending))
                let now = Date()

                failureCount = 0
                nextAllowedFlushAt = nil

                if !result.acceptedIds.isEmpty {
                    try await store.markSent(pointIds: result.acceptedIds, sentAt: now)
                }

                if !result.rejected.isEmpty {
                    try await store.markFailed(
                        pointIds: result.rejected.map(\.id),
                        errorMessage: result.rejected.first?.reason ?? "rejected"
                    )
                }

                pendingInsertsSinceLastFlush = 0
                continuation.yield(.didUpload(accepted: result.acceptedIds.count, rejected: result.rejected.count))
                batchesProcessed += 1

                await maybePruneSentPoints(now: now)

                // If server didn't acknowledge any IDs, avoid an infinite loop.
                if result.acceptedIds.isEmpty, result.rejected.isEmpty {
                    break
                }
            } catch {
                scheduleBackoffAfterFailure()
                continuation.yield(.uploadFailed(String(describing: error)))
                break
            }
        }
    }

    private func shouldFlush(force: Bool) async throws -> Bool {
        if force { return true }

        let stats = try await store.pendingStats()
        if stats.count == 0 { return false }
        if stats.count >= batching.maxBatchSize { return true }

        if let oldest = stats.oldestRecordedAt {
            let age = Date().timeIntervalSince(oldest)
            if age >= batching.maxBatchAge { return true }
        }

        return false
    }

    private func scheduleBackoffAfterFailure() {
        failureCount = min(failureCount + 1, 30)

        let baseDelay = retryPolicy.delay(forAttempt: failureCount)
        let jitter = max(0.0, min(1.0, retryPolicy.jitterFraction))
        let factor = 1.0 + Double.random(in: -jitter...jitter)
        let delay = max(0.0, baseDelay * factor)

        nextAllowedFlushAt = Date().addingTimeInterval(delay)
    }

    private func runPullOnce(
        pullAPI: any LocationPullAPI,
        store: any BidirectionalLocationStore,
        mergeStrategy: (any LocationMergeStrategy)?
    ) async {
        do {
            let cursor = try await store.getLastPullCursor()
            let result = try await pullAPI.fetch(since: cursor)
            guard !result.items.isEmpty else {
                if let next = result.nextCursor {
                    try await store.setLastPullCursor(next)
                }
                return
            }
            try await store.applyServerChanges(
                result.items,
                mergeStrategy: mergeStrategy,
                serverTime: result.serverTime,
                lastSyncAt: lastPullAt
            )
            if let next = result.nextCursor {
                try await store.setLastPullCursor(next)
            }
            lastPullAt = Date()
            continuation.yield(.didPull(count: result.items.count))
        } catch {
            continuation.yield(.pullFailed(String(describing: error)))
        }
    }

    private func maybePruneSentPoints(now: Date) async {
        guard let maxAge = retentionPolicy.sentPointsMaxAge else { return }
        guard maxAge > 0 else { return }

        // Don't run a DELETE query on every upload; prune at most once per hour.
        let minInterval: TimeInterval = 60 * 60
        if let lastPruneAt, now.timeIntervalSince(lastPruneAt) < minInterval {
            return
        }

        guard let prunable = store as? any SentPointsPrunableLocationStore else {
            lastPruneAt = now
            return
        }

        do {
            let cutoff = now.addingTimeInterval(-maxAge)
            try await prunable.pruneSentPoints(olderThan: cutoff)
        } catch {
            // Non-fatal: retention cleanup shouldn't block sync.
        }

        lastPruneAt = now
    }
}
