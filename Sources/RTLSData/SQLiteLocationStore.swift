import Foundation
import RTLSCore
import SQLite3
import os.log

public actor SQLiteLocationStore: LocationStore, SentPointsPrunableLocationStore, BidirectionalLocationStore {
    private nonisolated let SQLITE_TRANSIENT = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

    private enum SyncStatus: Int {
        case pending = 0
        case sent = 1
        case failed = 2
    }

    private let db: SQLiteDatabase

    public init(databaseURL: URL) async throws {
        self.db = try SQLiteDatabase(url: databaseURL)
        try migrateIfNeeded()
    }

    private func migrateIfNeeded() throws {
        try db.exec("""
        CREATE TABLE IF NOT EXISTS schema_version (
            version INTEGER NOT NULL
        );
        """)

        let current = try currentSchemaVersion()
        if current == 0 {
            try db.exec("""
            CREATE TABLE IF NOT EXISTS location_points (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                recorded_at_ms INTEGER NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                hacc REAL,
                vacc REAL,
                altitude REAL,
                speed REAL,
                course REAL,
                sync_status INTEGER NOT NULL,
                last_error TEXT,
                sent_at_ms INTEGER
            );
            """)
            try db.exec("""
            CREATE INDEX IF NOT EXISTS idx_location_points_status_time
            ON location_points(sync_status, recorded_at_ms);
            """)

            try setSchemaVersion(1)
        }
        if current < 2 {
            try db.exec("""
            CREATE TABLE IF NOT EXISTS sync_replica_location_points (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                recorded_at_ms INTEGER NOT NULL,
                lat REAL NOT NULL,
                lng REAL NOT NULL,
                hacc REAL,
                vacc REAL,
                altitude REAL,
                speed REAL,
                course REAL,
                updated_at_ms INTEGER NOT NULL
            );
            """)
            try db.exec("""
            CREATE TABLE IF NOT EXISTS sync_metadata (
                key TEXT PRIMARY KEY,
                value BLOB
            );
            """)
            try setSchemaVersion(2)
        }
    }

    private func currentSchemaVersion() throws -> Int {
        let stmt = try db.prepare("SELECT version FROM schema_version LIMIT 1;")
        defer { sqlite3_finalize(stmt) }

        if sqlite3_step(stmt) == SQLITE_ROW {
            return Int(sqlite3_column_int(stmt, 0))
        }
        return 0
    }

    private func setSchemaVersion(_ version: Int) throws {
        try db.exec("DELETE FROM schema_version;")
        let stmt = try db.prepare("INSERT INTO schema_version(version) VALUES (?);")
        defer { sqlite3_finalize(stmt) }

        guard sqlite3_bind_int(stmt, 1, Int32(version)) == SQLITE_OK else {
            throw SQLiteError.bind(db.lastErrorMessage())
        }
        guard sqlite3_step(stmt) == SQLITE_DONE else {
            throw SQLiteError.step(db.lastErrorMessage())
        }
    }

    public func insert(points: [LocationPoint]) async throws {
        guard !points.isEmpty else { return }

        try db.exec("BEGIN TRANSACTION;")
        do {
            let sql = """
            INSERT OR IGNORE INTO location_points(
                id, user_id, device_id, recorded_at_ms, lat, lng, hacc, vacc, altitude, speed, course,
                sync_status, last_error, sent_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL);
            """

            let stmt = try db.prepare(sql)
            defer { sqlite3_finalize(stmt) }

            for p in points {
                sqlite3_reset(stmt)
                sqlite3_clear_bindings(stmt)

                try bindText(stmt, index: 1, value: p.id.uuidString)
                try bindText(stmt, index: 2, value: p.userId)
                try bindText(stmt, index: 3, value: p.deviceId)
                try bindInt64(stmt, index: 4, value: Self.dateToMs(p.recordedAt))
                try bindDouble(stmt, index: 5, value: p.coordinate.latitude)
                try bindDouble(stmt, index: 6, value: p.coordinate.longitude)
                try bindNullableDouble(stmt, index: 7, value: p.horizontalAccuracy)
                try bindNullableDouble(stmt, index: 8, value: p.verticalAccuracy)
                try bindNullableDouble(stmt, index: 9, value: p.altitude)
                try bindNullableDouble(stmt, index: 10, value: p.speed)
                try bindNullableDouble(stmt, index: 11, value: p.course)
                try bindInt(stmt, index: 12, value: SyncStatus.pending.rawValue)

                guard sqlite3_step(stmt) == SQLITE_DONE else {
                    throw SQLiteError.step(db.lastErrorMessage())
                }
            }

            try db.exec("COMMIT;")
        } catch {
            // Attempt rollback and log any errors from both the original operation and rollback
            do {
                try db.exec("ROLLBACK;")
            } catch let rollbackError {
                // Log rollback failure - this indicates serious DB corruption
                os_log(.error, "RTLS: Transaction failed and rollback also failed: %{public}@. Original error: %{public}@",
                       rollbackError.localizedDescription, error.localizedDescription)
                // Throw composite error to preserve both errors
                throw SQLiteError.transactionFailed(original: error, rollback: rollbackError)
            }
            throw error
        }
    }

    public func fetchPendingPoints(limit: Int) async throws -> [LocationPoint] {
        guard limit > 0 else { return [] }

        let sql = """
        SELECT
            id, user_id, device_id, recorded_at_ms, lat, lng, hacc, vacc, altitude, speed, course
        FROM location_points
        WHERE sync_status = ?
        ORDER BY recorded_at_ms ASC
        LIMIT ?;
        """

        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }

        try bindInt(stmt, index: 1, value: SyncStatus.pending.rawValue)
        try bindInt(stmt, index: 2, value: limit)

        var points: [LocationPoint] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            points.append(decodePointRow(stmt))
        }
        return points
    }

    public func fetchPendingPoints(userId: String, deviceId: String, limit: Int) async throws -> [LocationPoint] {
        guard limit > 0 else { return [] }

        let sql = """
        SELECT
            id, user_id, device_id, recorded_at_ms, lat, lng, hacc, vacc, altitude, speed, course
        FROM location_points
        WHERE sync_status = ?
          AND user_id = ?
          AND device_id = ?
        ORDER BY recorded_at_ms ASC
        LIMIT ?;
        """

        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }

        try bindInt(stmt, index: 1, value: SyncStatus.pending.rawValue)
        try bindText(stmt, index: 2, value: userId)
        try bindText(stmt, index: 3, value: deviceId)
        try bindInt(stmt, index: 4, value: limit)

        var points: [LocationPoint] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            points.append(decodePointRow(stmt))
        }
        return points
    }

    public func fetchRecentPoints(userId: String, deviceId: String, limit: Int) async throws -> [LocationPoint] {
        guard limit > 0 else { return [] }

        let sql = """
        SELECT
            id, user_id, device_id, recorded_at_ms, lat, lng, hacc, vacc, altitude, speed, course
        FROM location_points
        WHERE user_id = ?
          AND device_id = ?
        ORDER BY recorded_at_ms DESC
        LIMIT ?;
        """

        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }

        try bindText(stmt, index: 1, value: userId)
        try bindText(stmt, index: 2, value: deviceId)
        try bindInt(stmt, index: 3, value: limit)

        var points: [LocationPoint] = []
        while sqlite3_step(stmt) == SQLITE_ROW {
            points.append(decodePointRow(stmt))
        }
        return points
    }

    public func pendingCount() async throws -> Int {
        let sql = "SELECT COUNT(1) FROM location_points WHERE sync_status = ?;"
        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }

        try bindInt(stmt, index: 1, value: SyncStatus.pending.rawValue)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return 0 }
        return Int(sqlite3_column_int(stmt, 0))
    }

    public func oldestPendingRecordedAt() async throws -> Date? {
        let sql = """
        SELECT recorded_at_ms
        FROM location_points
        WHERE sync_status = ?
        ORDER BY recorded_at_ms ASC
        LIMIT 1;
        """
        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }

        try bindInt(stmt, index: 1, value: SyncStatus.pending.rawValue)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return Self.msToDate(columnInt64(stmt, index: 0))
    }

    public func pendingStats() async throws -> PendingStats {
        let sql = "SELECT COUNT(1), MIN(recorded_at_ms) FROM location_points WHERE sync_status = ?;"
        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }

        try bindInt(stmt, index: 1, value: SyncStatus.pending.rawValue)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return PendingStats(count: 0, oldestRecordedAt: nil) }
        let count = Int(sqlite3_column_int(stmt, 0))
        let oldest: Date? = (sqlite3_column_type(stmt, 1) == SQLITE_NULL) ? nil : Self.msToDate(columnInt64(stmt, index: 1))
        return PendingStats(count: count, oldestRecordedAt: oldest)
    }

    public func markSent(pointIds: [UUID], sentAt: Date) async throws {
        try mark(pointIds: pointIds, status: .sent, sentAt: sentAt, errorMessage: nil)
    }

    public func markFailed(pointIds: [UUID], errorMessage: String) async throws {
        try mark(pointIds: pointIds, status: .failed, sentAt: nil, errorMessage: errorMessage)
    }

    // MARK: - BidirectionalLocationStore

    public func applyServerChanges(
        _ items: [LocationPoint],
        mergeStrategy: (any LocationMergeStrategy)?,
        serverTime: Date? = nil,
        lastSyncAt: Date? = nil
    ) async throws {
        guard !items.isEmpty else { return }
        let context = MergeContext(lastSyncAt: lastSyncAt, serverTime: serverTime)
        try db.exec("BEGIN TRANSACTION;")
        do {
            for serverItem in items {
                let local = try fetchLocalPoint(id: serverItem.id)
                if let local {
                    if local == serverItem { continue }
                    if let strategy = mergeStrategy {
                        switch strategy.resolve(local: local, server: serverItem, context: context) {
                        case .keepLocal: continue
                        case .keepServer: try upsertReplica(serverItem)
                        case .use(let resolved): try upsertReplica(resolved)
                        }
                    } else {
                        try upsertReplica(serverItem)
                    }
                } else {
                    try upsertReplica(serverItem)
                }
            }
            try db.exec("COMMIT;")
        } catch {
            try? db.exec("ROLLBACK;")
            throw error
        }
    }

    public func getLastPullCursor() async throws -> SyncCursor? {
        let sql = "SELECT value FROM sync_metadata WHERE key = 'pull_cursor' LIMIT 1;"
        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        guard let blob = sqlite3_column_blob(stmt, 0) else { return nil }
        let count = sqlite3_column_bytes(stmt, 0)
        return SyncCursor(value: Data(bytes: blob, count: Int(count)))
    }

    public func setLastPullCursor(_ cursor: SyncCursor?) async throws {
        let sql = "INSERT OR REPLACE INTO sync_metadata(key, value) VALUES ('pull_cursor', ?);"
        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }
        if let cursor {
            let bytes = [UInt8](cursor.value)
            guard sqlite3_bind_blob(stmt, 1, bytes, Int32(bytes.count), SQLITE_TRANSIENT) == SQLITE_OK else {
                throw SQLiteError.bind(db.lastErrorMessage())
            }
        } else {
            guard sqlite3_bind_null(stmt, 1) == SQLITE_OK else {
                throw SQLiteError.bind(db.lastErrorMessage())
            }
        }
        guard sqlite3_step(stmt) == SQLITE_DONE else {
            throw SQLiteError.step(db.lastErrorMessage())
        }
    }

    private func fetchLocalPoint(id: UUID) throws -> LocationPoint? {
        if let replica = try fetchReplicaPoint(id: id) { return replica }
        return try fetchPendingPoint(id: id)
    }

    private func fetchReplicaPoint(id: UUID) throws -> LocationPoint? {
        let sql = """
        SELECT id, user_id, device_id, recorded_at_ms, lat, lng, hacc, vacc, altitude, speed, course
        FROM sync_replica_location_points WHERE id = ?;
        """
        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }
        try bindText(stmt, index: 1, value: id.uuidString)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return decodePointRow(stmt)
    }

    private func fetchPendingPoint(id: UUID) throws -> LocationPoint? {
        let sql = """
        SELECT id, user_id, device_id, recorded_at_ms, lat, lng, hacc, vacc, altitude, speed, course
        FROM location_points WHERE id = ? AND sync_status = ?;
        """
        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }
        try bindText(stmt, index: 1, value: id.uuidString)
        try bindInt(stmt, index: 2, value: SyncStatus.pending.rawValue)
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return decodePointRow(stmt)
    }

    private func upsertReplica(_ point: LocationPoint) throws {
        let sql = """
        INSERT OR REPLACE INTO sync_replica_location_points(
            id, user_id, device_id, recorded_at_ms, lat, lng, hacc, vacc, altitude, speed, course, updated_at_ms
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
        """
        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }
        let nowMs = Self.dateToMs(Date())
        try bindText(stmt, index: 1, value: point.id.uuidString)
        try bindText(stmt, index: 2, value: point.userId)
        try bindText(stmt, index: 3, value: point.deviceId)
        try bindInt64(stmt, index: 4, value: Self.dateToMs(point.recordedAt))
        try bindDouble(stmt, index: 5, value: point.coordinate.latitude)
        try bindDouble(stmt, index: 6, value: point.coordinate.longitude)
        try bindNullableDouble(stmt, index: 7, value: point.horizontalAccuracy)
        try bindNullableDouble(stmt, index: 8, value: point.verticalAccuracy)
        try bindNullableDouble(stmt, index: 9, value: point.altitude)
        try bindNullableDouble(stmt, index: 10, value: point.speed)
        try bindNullableDouble(stmt, index: 11, value: point.course)
        try bindInt64(stmt, index: 12, value: nowMs)
        guard sqlite3_step(stmt) == SQLITE_DONE else {
            throw SQLiteError.step(db.lastErrorMessage())
        }
    }

    public func pruneSentPoints(olderThan cutoff: Date) async throws {
        let sql = """
        DELETE FROM location_points
        WHERE sync_status = ?
          AND sent_at_ms IS NOT NULL
          AND sent_at_ms < ?;
        """
        let stmt = try db.prepare(sql)
        defer { sqlite3_finalize(stmt) }

        try bindInt(stmt, index: 1, value: SyncStatus.sent.rawValue)
        try bindInt64(stmt, index: 2, value: Self.dateToMs(cutoff))

        guard sqlite3_step(stmt) == SQLITE_DONE else {
            throw SQLiteError.step(db.lastErrorMessage())
        }
    }

    private func mark(pointIds: [UUID], status: SyncStatus, sentAt: Date?, errorMessage: String?) throws {
        guard !pointIds.isEmpty else { return }

        let sentAtMs = sentAt.map(Self.dateToMs)
        let chunkSize = 999
        for chunk in stride(from: 0, to: pointIds.count, by: chunkSize).map({ Array(pointIds[$0..<min($0 + chunkSize, pointIds.count)]) }) {
            let placeholders = chunk.map { _ in "?" }.joined(separator: ",")
            let sql = """
            UPDATE location_points
            SET sync_status = ?, last_error = ?, sent_at_ms = ?
            WHERE id IN (\(placeholders));
            """
            let stmt = try db.prepare(sql)
            defer { sqlite3_finalize(stmt) }

            try bindInt(stmt, index: 1, value: status.rawValue)
            try bindNullableText(stmt, index: 2, value: errorMessage)
            try bindNullableInt64(stmt, index: 3, value: sentAtMs)
            for (offset, id) in chunk.enumerated() {
                try bindText(stmt, index: Int32(4 + offset), value: id.uuidString)
            }

            guard sqlite3_step(stmt) == SQLITE_DONE else {
                throw SQLiteError.step(db.lastErrorMessage())
            }
        }
    }

    // MARK: - Binding helpers

    private func decodePointRow(_ stmt: OpaquePointer?) -> LocationPoint {
        let id = UUID(uuidString: columnText(stmt, index: 0)) ?? UUID()
        let userId = columnText(stmt, index: 1)
        let deviceId = columnText(stmt, index: 2)
        let recordedAt = Self.msToDate(columnInt64(stmt, index: 3))
        let lat = sqlite3_column_double(stmt, 4)
        let lng = sqlite3_column_double(stmt, 5)

        let hacc = columnNullableDouble(stmt, index: 6)
        let vacc = columnNullableDouble(stmt, index: 7)
        let altitude = columnNullableDouble(stmt, index: 8)
        let speed = columnNullableDouble(stmt, index: 9)
        let course = columnNullableDouble(stmt, index: 10)

        return LocationPoint(
            id: id,
            userId: userId,
            deviceId: deviceId,
            recordedAt: recordedAt,
            coordinate: GeoCoordinate(latitude: lat, longitude: lng),
            horizontalAccuracy: hacc,
            verticalAccuracy: vacc,
            altitude: altitude,
            speed: speed,
            course: course
        )
    }

    private func bindText(_ stmt: OpaquePointer?, index: Int32, value: String) throws {
        let result = sqlite3_bind_text(stmt, index, value, -1, SQLITE_TRANSIENT)
        guard result == SQLITE_OK else { throw SQLiteError.bind(db.lastErrorMessage()) }
    }

    private func bindNullableText(_ stmt: OpaquePointer?, index: Int32, value: String?) throws {
        guard let value else {
            let result = sqlite3_bind_null(stmt, index)
            guard result == SQLITE_OK else { throw SQLiteError.bind(db.lastErrorMessage()) }
            return
        }
        try bindText(stmt, index: index, value: value)
    }

    private func bindInt(_ stmt: OpaquePointer?, index: Int32, value: Int) throws {
        let result = sqlite3_bind_int(stmt, index, Int32(value))
        guard result == SQLITE_OK else { throw SQLiteError.bind(db.lastErrorMessage()) }
    }

    private func bindInt64(_ stmt: OpaquePointer?, index: Int32, value: Int64) throws {
        let result = sqlite3_bind_int64(stmt, index, value)
        guard result == SQLITE_OK else { throw SQLiteError.bind(db.lastErrorMessage()) }
    }

    private func bindNullableInt64(_ stmt: OpaquePointer?, index: Int32, value: Int64?) throws {
        guard let value else {
            let result = sqlite3_bind_null(stmt, index)
            guard result == SQLITE_OK else { throw SQLiteError.bind(db.lastErrorMessage()) }
            return
        }
        try bindInt64(stmt, index: index, value: value)
    }

    private func bindDouble(_ stmt: OpaquePointer?, index: Int32, value: Double) throws {
        let result = sqlite3_bind_double(stmt, index, value)
        guard result == SQLITE_OK else { throw SQLiteError.bind(db.lastErrorMessage()) }
    }

    private func bindNullableDouble(_ stmt: OpaquePointer?, index: Int32, value: Double?) throws {
        guard let value else {
            let result = sqlite3_bind_null(stmt, index)
            guard result == SQLITE_OK else { throw SQLiteError.bind(db.lastErrorMessage()) }
            return
        }
        try bindDouble(stmt, index: index, value: value)
    }

    private func columnText(_ stmt: OpaquePointer?, index: Int32) -> String {
        guard let c = sqlite3_column_text(stmt, index) else { return "" }
        return String(cString: c)
    }

    private func columnInt64(_ stmt: OpaquePointer?, index: Int32) -> Int64 {
        sqlite3_column_int64(stmt, index)
    }

    private func columnNullableDouble(_ stmt: OpaquePointer?, index: Int32) -> Double? {
        if sqlite3_column_type(stmt, index) == SQLITE_NULL { return nil }
        return sqlite3_column_double(stmt, index)
    }

    private static func dateToMs(_ date: Date) -> Int64 {
        Int64(date.timeIntervalSince1970 * 1000.0)
    }

    private static func msToDate(_ ms: Int64) -> Date {
        Date(timeIntervalSince1970: TimeInterval(ms) / 1000.0)
    }
}
