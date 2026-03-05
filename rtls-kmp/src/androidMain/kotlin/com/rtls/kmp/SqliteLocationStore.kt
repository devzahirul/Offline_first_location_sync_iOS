package com.rtls.kmp

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SQLite-backed LocationStore using SQLiteOpenHelper so the platform handles
 * DB lifecycle and threading. Only considers points with sent_at IS NULL AND failed_at IS NULL
 * as pending (failed points are not re-uploaded indefinitely).
 */
class SqliteLocationStore(private val context: Context) : LocationStore, SentPointsPrunableLocationStore, BidirectionalLocationStore {

    private val helper by lazy { RtlsDbHelper(context) }

    private val pendingWhere = "sent_at IS NULL AND failed_at IS NULL"

    override suspend fun insert(points: List<LocationPoint>) = withContext(Dispatchers.IO) {
        if (points.isEmpty()) return@withContext
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            points.forEach { p ->
                db.execSQL(
                    """INSERT OR IGNORE INTO location_points
                        (id, user_id, device_id, recorded_at, lat, lng, horizontal_accuracy, vertical_accuracy, altitude, speed, course, sent_at, failed_at, error_message)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL)""",
                    arrayOf(
                        p.id, p.userId, p.deviceId, p.recordedAtMs, p.lat, p.lng,
                        p.horizontalAccuracy, p.verticalAccuracy, p.altitude, p.speed, p.course
                    )
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun fetchPendingPoints(limit: Int): List<LocationPoint> = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val c = db.rawQuery(
            "SELECT id, user_id, device_id, recorded_at, lat, lng, horizontal_accuracy, vertical_accuracy, altitude, speed, course FROM location_points WHERE $pendingWhere ORDER BY recorded_at ASC LIMIT ?",
            arrayOf(limit.toString())
        )
        val list = mutableListOf<LocationPoint>()
        while (c.moveToNext()) {
            list.add(
                LocationPoint(
                    id = c.getString(0),
                    userId = c.getString(1),
                    deviceId = c.getString(2),
                    recordedAtMs = c.getLong(3),
                    lat = c.getDouble(4),
                    lng = c.getDouble(5),
                    horizontalAccuracy = if (c.isNull(6)) null else c.getDouble(6),
                    verticalAccuracy = if (c.isNull(7)) null else c.getDouble(7),
                    altitude = if (c.isNull(8)) null else c.getDouble(8),
                    speed = if (c.isNull(9)) null else c.getDouble(9),
                    course = if (c.isNull(10)) null else c.getDouble(10)
                )
            )
        }
        c.close()
        list
    }

    override suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val c = db.rawQuery("SELECT COUNT(*) FROM location_points WHERE $pendingWhere", null)
        c.moveToFirst()
        val n = c.getInt(0)
        c.close()
        n
    }

    override suspend fun oldestPendingRecordedAt(): Long? = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val c = db.rawQuery("SELECT MIN(recorded_at) FROM location_points WHERE $pendingWhere", null)
        val result = if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        c.close()
        result
    }

    override suspend fun pendingStats(): PendingStats = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val c = db.rawQuery(
            "SELECT COUNT(*), MIN(recorded_at) FROM location_points WHERE $pendingWhere", null
        )
        val stats = if (c.moveToFirst()) {
            PendingStats(c.getInt(0), if (c.isNull(1)) null else c.getLong(1))
        } else {
            PendingStats(0, null)
        }
        c.close()
        stats
    }

    override suspend fun markSent(pointIds: List<String>, sentAtMs: Long) = withContext(Dispatchers.IO) {
        if (pointIds.isEmpty()) return@withContext
        val db = helper.writableDatabase
        for (chunk in pointIds.chunked(999)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = arrayOf(sentAtMs.toString()) + chunk.toTypedArray()
            db.execSQL("UPDATE location_points SET sent_at = ? WHERE id IN ($placeholders)", args)
        }
    }

    override suspend fun markFailed(pointIds: List<String>, errorMessage: String) = withContext(Dispatchers.IO) {
        if (pointIds.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        val db = helper.writableDatabase
        for (chunk in pointIds.chunked(999)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val args = arrayOf(now.toString(), errorMessage) + chunk.toTypedArray()
            db.execSQL("UPDATE location_points SET failed_at = ?, error_message = ? WHERE id IN ($placeholders)", args)
        }
    }

    override suspend fun pruneSentPoints(olderThanRecordedMs: Long) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.execSQL(
            "DELETE FROM location_points WHERE sent_at IS NOT NULL AND recorded_at < ?",
            arrayOf(olderThanRecordedMs)
        )
    }

    override suspend fun applyServerChanges(
        items: List<LocationPoint>,
        mergeStrategy: LocationMergeStrategy?,
        serverTimeMs: Long?,
        lastSyncAtMs: Long?
    ) = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext
        val context = MergeContext(lastSyncAtMs = lastSyncAtMs, serverTimeMs = serverTimeMs)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for (serverItem in items) {
                val local = fetchLocalPoint(db, serverItem.id)
                if (local != null) {
                    if (local == serverItem) continue
                    when (val result = mergeStrategy?.resolve(local, serverItem, context) ?: LocationMergeResult.KeepServer) {
                        is LocationMergeResult.KeepLocal -> { /* no write */ }
                        is LocationMergeResult.KeepServer -> upsertReplica(db, serverItem)
                        is LocationMergeResult.Use -> upsertReplica(db, result.point)
                    }
                } else {
                    upsertReplica(db, serverItem)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override suspend fun getLastPullCursor(): SyncCursor? = withContext(Dispatchers.IO) {
        val db = helper.readableDatabase
        val c = db.rawQuery("SELECT value FROM sync_metadata WHERE key = 'pull_cursor' LIMIT 1", null)
        val result = if (c.moveToFirst() && !c.isNull(0)) SyncCursor(c.getBlob(0)) else null
        c.close()
        result
    }

    override suspend fun setLastPullCursor(cursor: SyncCursor?) = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        if (cursor != null) {
            db.execSQL(
                "INSERT OR REPLACE INTO sync_metadata(key, value) VALUES ('pull_cursor', ?)",
                arrayOf(cursor.value)
            )
        } else {
            db.delete("sync_metadata", "key = ?", arrayOf("pull_cursor"))
        }
        Unit
    }

    private fun fetchLocalPoint(db: SQLiteDatabase, id: String): LocationPoint? {
        fetchReplicaPoint(db, id)?.let { return it }
        return fetchPendingPointById(db, id)
    }

    private fun fetchReplicaPoint(db: SQLiteDatabase, id: String): LocationPoint? {
        val c = db.rawQuery(
            "SELECT id, user_id, device_id, recorded_at, lat, lng, horizontal_accuracy, vertical_accuracy, altitude, speed, course FROM sync_replica_location_points WHERE id = ?",
            arrayOf(id)
        )
        val point = if (c.moveToFirst()) rowToPoint(c) else null
        c.close()
        return point
    }

    private fun fetchPendingPointById(db: SQLiteDatabase, id: String): LocationPoint? {
        val c = db.rawQuery(
            "SELECT id, user_id, device_id, recorded_at, lat, lng, horizontal_accuracy, vertical_accuracy, altitude, speed, course FROM location_points WHERE id = ? AND $pendingWhere",
            arrayOf(id)
        )
        val point = if (c.moveToFirst()) rowToPoint(c) else null
        c.close()
        return point
    }

    private fun rowToPoint(c: Cursor): LocationPoint =
        LocationPoint(
            id = c.getString(0),
            userId = c.getString(1),
            deviceId = c.getString(2),
            recordedAtMs = c.getLong(3),
            lat = c.getDouble(4),
            lng = c.getDouble(5),
            horizontalAccuracy = if (c.isNull(6)) null else c.getDouble(6),
            verticalAccuracy = if (c.isNull(7)) null else c.getDouble(7),
            altitude = if (c.isNull(8)) null else c.getDouble(8),
            speed = if (c.isNull(9)) null else c.getDouble(9),
            course = if (c.isNull(10)) null else c.getDouble(10)
        )

    private fun upsertReplica(db: SQLiteDatabase, point: LocationPoint) {
        val now = System.currentTimeMillis()
        db.execSQL(
            """INSERT OR REPLACE INTO sync_replica_location_points
                (id, user_id, device_id, recorded_at, lat, lng, horizontal_accuracy, vertical_accuracy, altitude, speed, course, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            arrayOf(
                point.id, point.userId, point.deviceId, point.recordedAtMs, point.lat, point.lng,
                point.horizontalAccuracy, point.verticalAccuracy, point.altitude, point.speed, point.course,
                now
            )
        )
    }

    private class RtlsDbHelper(context: Context) : SQLiteOpenHelper(
        context,
        "rtlsync.db",
        null,
        2
    ) {
        init {
            setWriteAheadLoggingEnabled(true)
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.execSQL("PRAGMA synchronous = NORMAL")
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE location_points (
                    id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    recorded_at INTEGER NOT NULL,
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
            db.execSQL(
                "CREATE INDEX idx_location_points_pending ON location_points(sent_at, failed_at, recorded_at)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_replica_location_points (
                        id TEXT PRIMARY KEY,
                        user_id TEXT NOT NULL,
                        device_id TEXT NOT NULL,
                        recorded_at INTEGER NOT NULL,
                        lat REAL NOT NULL,
                        lng REAL NOT NULL,
                        horizontal_accuracy REAL,
                        vertical_accuracy REAL,
                        altitude REAL,
                        speed REAL,
                        course REAL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS sync_metadata (
                        key TEXT PRIMARY KEY,
                        value BLOB
                    )
                """.trimIndent())
            }
        }
    }
}
