package com.rtls.kmp

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SqliteLocationStore(private val dbPath: String) : LocationStore, SentPointsPrunableLocationStore {

    private fun openDb(): SQLiteDatabase {
        val dbFile = File(dbPath)
        dbFile.parentFile?.takeIf { !it.exists() }?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbPath, null)
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS location_points (
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
        return db
    }

    override suspend fun insert(points: List<LocationPoint>) = withContext(Dispatchers.IO) {
        val db = openDb()
        try {
            db.beginTransaction()
            try {
                points.forEach { p ->
                    db.execSQL(
                        """INSERT OR REPLACE INTO location_points
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
        } finally {
            db.close()
        }
    }

    override suspend fun fetchPendingPoints(limit: Int): List<LocationPoint> = withContext(Dispatchers.IO) {
        val db = openDb()
        try {
            val c = db.rawQuery(
                "SELECT id, user_id, device_id, recorded_at, lat, lng, horizontal_accuracy, vertical_accuracy, altitude, speed, course FROM location_points WHERE sent_at IS NULL ORDER BY recorded_at ASC LIMIT ?",
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
        } finally {
            db.close()
        }
    }

    override suspend fun pendingCount(): Int = withContext(Dispatchers.IO) {
        val db = openDb()
        try {
            val c = db.rawQuery("SELECT COUNT(*) FROM location_points WHERE sent_at IS NULL", null)
            c.moveToFirst()
            val n = c.getInt(0)
            c.close()
            n
        } finally {
            db.close()
        }
    }

    override suspend fun oldestPendingRecordedAt(): Long? = withContext(Dispatchers.IO) {
        val db = openDb()
        try {
            val c = db.rawQuery("SELECT MIN(recorded_at) FROM location_points WHERE sent_at IS NULL", null)
            val result = if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            c.close()
            result
        } finally {
            db.close()
        }
    }

    override suspend fun markSent(pointIds: List<String>, sentAtMs: Long) = withContext(Dispatchers.IO) {
        val db = openDb()
        try {
            pointIds.forEach { id ->
                db.execSQL("UPDATE location_points SET sent_at = ? WHERE id = ?", arrayOf(sentAtMs, id))
            }
        } finally {
            db.close()
        }
    }

    override suspend fun markFailed(pointIds: List<String>, errorMessage: String) = withContext(Dispatchers.IO) {
        val db = openDb()
        try {
            val now = System.currentTimeMillis()
            pointIds.forEach { id ->
                db.execSQL("UPDATE location_points SET failed_at = ?, error_message = ? WHERE id = ?", arrayOf(now, errorMessage, id))
            }
        } finally {
            db.close()
        }
    }

    override suspend fun pruneSentPoints(olderThanRecordedMs: Long) = withContext(Dispatchers.IO) {
        val db = openDb()
        try {
            db.execSQL(
                "DELETE FROM location_points WHERE sent_at IS NOT NULL AND recorded_at < ?",
                arrayOf(olderThanRecordedMs)
            )
        } finally {
            db.close()
        }
    }
}
