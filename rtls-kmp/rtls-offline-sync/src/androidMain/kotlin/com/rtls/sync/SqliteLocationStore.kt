package com.rtls.sync

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.rtls.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SqliteLocationStore(private val context: Context) : LocationStore, SentPointsPrunableLocationStore, BidirectionalLocationStore {

    private val helper by lazy { RtlsDbHelper(context) }
    private val pendingWhere = "sent_at IS NULL AND failed_at IS NULL"

    override suspend fun insert(points: List<LocationPoint>) = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val sql = """
                INSERT OR IGNORE INTO location_points(
                    id, user_id, device_id, recorded_at_ms, lat, lng,
                    horizontal_accuracy, vertical_accuracy, altitude, speed, course
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            val stmt = db.compileStatement(sql)
            for (p in points) {
                stmt.clearBindings()
                stmt.bindString(1, p.id)
                stmt.bindString(2, p.userId)
                stmt.bindString(3, p.deviceId)
                stmt.bindLong(4, p.recordedAtMs)
                stmt.bindDouble(5, p.lat)
                stmt.bindDouble(6, p.lng)
                if (p.horizontalAccuracy != null) stmt.bindDouble(7, p.horizontalAccuracy) else stmt.bindNull(7)
                if (p.verticalAccuracy != null) stmt.bindDouble(8, p.verticalAccuracy) else stmt.bindNull(8)
                if (p.altitude != null) stmt.bindDouble(9, p.altitude) else stmt.bindNull(9)
                if (p.speed != null) stmt.bindDouble(10, p.speed) else stmt.bindNull(10)
                if (p.course != null) stmt.bindDouble(11, p.course) else stmt.bindNull(11)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun fetchPendingPoints(limit: Int): List<LocationPoint> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, user_id, device_id, recorded_at_ms, lat, lng, horizontal_accuracy, vertical_accuracy, altitude, speed, course FROM location_points WHERE $pendingWhere ORDER BY recorded_at_ms ASC LIMIT ?",
            arrayOf(limit.toString())
        )
        val results = mutableListOf<LocationPoint>()
        cursor.use {
            while (it.moveToNext()) results.add(cursorToPoint(it))
        }
        results
    }

    override suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(1) FROM location_points WHERE $pendingWhere", null)
        cursor.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    override suspend fun oldestPendingRecordedAt(): Long? = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val cursor = db.rawQuery("SELECT MIN(recorded_at_ms) FROM location_points WHERE $pendingWhere", null)
        cursor.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    }

    override suspend fun pendingStats(): PendingStats = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(1), MIN(recorded_at_ms) FROM location_points WHERE $pendingWhere", null)
        cursor.use {
            if (it.moveToFirst()) {
                PendingStats(it.getInt(0), if (!it.isNull(1)) it.getLong(1) else null)
            } else PendingStats(0, null)
        }
    }

    override suspend fun markSent(pointIds: List<String>, sentAtMs: Long) = withContext(Dispatchers.IO) {
        if (pointIds.isEmpty()) return@withContext
        val db = helper.writableDatabase
        for (chunk in pointIds.chunked(999)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.execSQL(
                "UPDATE location_points SET sent_at = ?, failed_at = NULL WHERE id IN ($placeholders)",
                arrayOf(sentAtMs.toString()) + chunk.toTypedArray()
            )
        }
    }

    /**
     * Atomic operation: mark points as sent AND prune old sent points in a single transaction.
     * Prevents data loss if crash occurs between separate markSent + pruneSentPoints calls.
     */
    suspend fun markSentAndPrune(
        pointIds: List<String>,
        sentAtMs: Long,
        olderThanRecordedMs: Long?
    ) = withContext(Dispatchers.IO) {
        if (pointIds.isEmpty()) return@withContext
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            // Mark sent
            for (chunk in pointIds.chunked(999)) {
                val placeholders = chunk.joinToString(",") { "?" }
                db.execSQL(
                    "UPDATE location_points SET sent_at = ?, failed_at = NULL WHERE id IN ($placeholders)",
                    arrayOf(sentAtMs.toString()) + chunk.toTypedArray()
                )
            }
            // Prune old sent points
            if (olderThanRecordedMs != null) {
                db.execSQL(
                    "DELETE FROM location_points WHERE sent_at IS NOT NULL AND recorded_at_ms < ?",
                    arrayOf(olderThanRecordedMs.toString())
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun markFailed(pointIds: List<String>, errorMessage: String) = withContext(Dispatchers.IO) {
        if (pointIds.isEmpty()) return@withContext
        val db = helper.writableDatabase
        for (chunk in pointIds.chunked(999)) {
            val placeholders = chunk.joinToString(",") { "?" }
            db.execSQL(
                "UPDATE location_points SET failed_at = ?, error_message = ? WHERE id IN ($placeholders)",
                arrayOf(System.currentTimeMillis().toString(), errorMessage) + chunk.toTypedArray()
            )
        }
    }

    override suspend fun pruneSentPoints(olderThanRecordedMs: Long) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.execSQL(
            "DELETE FROM location_points WHERE sent_at IS NOT NULL AND recorded_at_ms < ?",
            arrayOf(olderThanRecordedMs.toString())
        )
    }

    override suspend fun applyServerChanges(
        items: List<LocationPoint>,
        mergeStrategy: LocationMergeStrategy?,
        serverTimeMs: Long?,
        lastSyncAtMs: Long?
    ) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val db = helper.writableDatabase
        val context = MergeContext(lastSyncAtMs = lastSyncAtMs, serverTimeMs = serverTimeMs)
        db.beginTransaction()
        try {
            for (serverItem in items) {
                val localCursor = db.rawQuery(
                    "SELECT id, user_id, device_id, recorded_at_ms, lat, lng, horizontal_accuracy, vertical_accuracy, altitude, speed, course FROM sync_replica_location_points WHERE id = ?",
                    arrayOf(serverItem.id)
                )
                val local = localCursor.use { if (it.moveToFirst()) cursorToPoint(it) else null }

                val resolved = if (local != null && mergeStrategy != null) {
                    when (val result = mergeStrategy.resolve(local, serverItem, context)) {
                        is LocationMergeResult.KeepLocal -> continue
                        is LocationMergeResult.KeepServer -> serverItem
                        is LocationMergeResult.Use -> result.point
                    }
                } else serverItem

                val stmt = db.compileStatement("""
                    INSERT OR REPLACE INTO sync_replica_location_points(
                        id, user_id, device_id, recorded_at_ms, lat, lng,
                        horizontal_accuracy, vertical_accuracy, altitude, speed, course, updated_at_ms
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent())
                stmt.clearBindings()
                stmt.bindString(1, resolved.id)
                stmt.bindString(2, resolved.userId)
                stmt.bindString(3, resolved.deviceId)
                stmt.bindLong(4, resolved.recordedAtMs)
                stmt.bindDouble(5, resolved.lat)
                stmt.bindDouble(6, resolved.lng)
                if (resolved.horizontalAccuracy != null) stmt.bindDouble(7, resolved.horizontalAccuracy) else stmt.bindNull(7)
                if (resolved.verticalAccuracy != null) stmt.bindDouble(8, resolved.verticalAccuracy) else stmt.bindNull(8)
                if (resolved.altitude != null) stmt.bindDouble(9, resolved.altitude) else stmt.bindNull(9)
                if (resolved.speed != null) stmt.bindDouble(10, resolved.speed) else stmt.bindNull(10)
                if (resolved.course != null) stmt.bindDouble(11, resolved.course) else stmt.bindNull(11)
                stmt.bindLong(12, System.currentTimeMillis())
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun getLastPullCursor(): SyncCursor? = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val cursor = db.rawQuery("SELECT value FROM sync_metadata WHERE key = 'pull_cursor' LIMIT 1", null)
        cursor.use {
            if (it.moveToFirst() && !it.isNull(0)) SyncCursor(it.getString(0)) else null
        }
    }

    override suspend fun setLastPullCursor(cursor: SyncCursor?) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        if (cursor != null) {
            db.execSQL("INSERT OR REPLACE INTO sync_metadata(key, value) VALUES ('pull_cursor', ?)", arrayOf(cursor.stringValue()))
        } else {
            db.execSQL("DELETE FROM sync_metadata WHERE key = 'pull_cursor'")
        }
    }

    private fun cursorToPoint(c: android.database.Cursor): LocationPoint {
        return LocationPoint(
            id = c.getString(0),
            userId = c.getString(1),
            deviceId = c.getString(2),
            recordedAtMs = c.getLong(3),
            lat = c.getDouble(4),
            lng = c.getDouble(5),
            horizontalAccuracy = if (!c.isNull(6)) c.getDouble(6) else null,
            verticalAccuracy = if (!c.isNull(7)) c.getDouble(7) else null,
            altitude = if (!c.isNull(8)) c.getDouble(8) else null,
            speed = if (!c.isNull(9)) c.getDouble(9) else null,
            course = if (!c.isNull(10)) c.getDouble(10) else null
        )
    }

    private class RtlsDbHelper(context: Context) : SQLiteOpenHelper(context, "rtls_locations.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("PRAGMA journal_mode = WAL")
            db.execSQL("PRAGMA synchronous = NORMAL")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS location_points (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    recorded_at_ms INTEGER NOT NULL,
                    lat REAL NOT NULL,
                    lng REAL NOT NULL,
                    horizontal_accuracy REAL,
                    vertical_accuracy REAL,
                    altitude REAL,
                    speed REAL,
                    course REAL,
                    sent_at INTEGER,
                    failed_at INTEGER,
                    error_message TEXT
                )
            """.trimIndent())
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_lp_pending ON location_points(sent_at, failed_at, recorded_at_ms)")
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS sync_replica_location_points (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    recorded_at_ms INTEGER NOT NULL,
                    lat REAL NOT NULL,
                    lng REAL NOT NULL,
                    horizontal_accuracy REAL,
                    vertical_accuracy REAL,
                    altitude REAL,
                    speed REAL,
                    course REAL,
                    updated_at_ms INTEGER NOT NULL
                )
            """.trimIndent())
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS sync_metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
            """.trimIndent())
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_replica_location_points (
                        id TEXT PRIMARY KEY, user_id TEXT NOT NULL, device_id TEXT NOT NULL,
                        recorded_at_ms INTEGER NOT NULL, lat REAL NOT NULL, lng REAL NOT NULL,
                        horizontal_accuracy REAL, vertical_accuracy REAL, altitude REAL,
                        speed REAL, course REAL, updated_at_ms INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_metadata (key TEXT PRIMARY KEY, value TEXT)")
            }
        }

        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            db.execSQL("PRAGMA journal_mode = WAL")
            db.execSQL("PRAGMA synchronous = NORMAL")
        }
    }
}
