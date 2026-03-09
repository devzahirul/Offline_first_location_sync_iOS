import Foundation
import SQLite3

enum SQLiteError: Error, CustomStringConvertible {
    case openDatabase(String)
    case prepare(String)
    case step(String)
    case bind(String)
    case exec(String)
    case transactionFailed(original: Error, rollback: Error)

    var description: String {
        switch self {
        case .openDatabase(let msg): return "SQLite open error: \(msg)"
        case .prepare(let msg): return "SQLite prepare error: \(msg)"
        case .step(let msg): return "SQLite step error: \(msg)"
        case .bind(let msg): return "SQLite bind error: \(msg)"
        case .exec(let msg): return "SQLite exec error: \(msg)"
        case .transactionFailed(let original, let rollback):
            return "SQLite transaction failed: \(original.localizedDescription). Rollback also failed: \(rollback.localizedDescription)"
        }
    }
}

final class SQLiteDatabase {
    private var db: OpaquePointer?

    init(url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)

        let flags = SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_FULLMUTEX
        let result = sqlite3_open_v2(url.path, &db, flags, nil)
        guard result == SQLITE_OK else {
            throw SQLiteError.openDatabase(Self.lastErrorMessage(from: db))
        }

        try exec("PRAGMA foreign_keys = ON;")
        try exec("PRAGMA journal_mode = WAL;")
        try exec("PRAGMA synchronous = NORMAL;")
    }

    deinit {
        sqlite3_close(db)
    }

    func exec(_ sql: String) throws {
        let result = sqlite3_exec(db, sql, nil, nil, nil)
        guard result == SQLITE_OK else {
            throw SQLiteError.exec(Self.lastErrorMessage(from: db))
        }
    }

    func prepare(_ sql: String) throws -> OpaquePointer? {
        var statement: OpaquePointer?
        let result = sqlite3_prepare_v2(db, sql, -1, &statement, nil)
        guard result == SQLITE_OK else {
            throw SQLiteError.prepare(Self.lastErrorMessage(from: db))
        }
        return statement
    }

    static func lastErrorMessage(from db: OpaquePointer?) -> String {
        guard let c = sqlite3_errmsg(db) else { return "Unknown sqlite error" }
        return String(cString: c)
    }

    func lastErrorMessage() -> String {
        Self.lastErrorMessage(from: db)
    }
}

