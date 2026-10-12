package sh.kizer.golive

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.core.database.sqlite.transaction

/**
 * Every measurement, in one SQLite table. About one row a minute while the Go
 * is in range: roughly 525k rows and 30 MB a year, which aggregates fast
 * enough on the time index that no roll-up tables are needed.
 */
class ReadingStore(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE readings (
                time INTEGER NOT NULL,
                device TEXT NOT NULL,
                packet_id INTEGER NOT NULL,
                pm25 REAL, co2 REAL, temperature REAL, humidity REAL,
                tvoc REAL, nox REAL, battery REAL, rssi INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX readings_time ON readings(time)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(r: Reading) {
        val values = ContentValues().apply {
            put("time", r.time)
            put("device", r.device)
            put("packet_id", r.packetId)
            for (m in Metric.entries) put(m.column, r.value(m))
            put("rssi", r.rssi)
        }
        writableDatabase.insertOrThrow("readings", null, values)
    }

    /** Inserts many readings in one transaction (restoring a backup). */
    fun insertAll(readings: List<Reading>) {
        writableDatabase.transaction { readings.forEach(::insert) }
    }

    fun isEmpty(): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM readings LIMIT 1", null).use { !it.moveToFirst() }

    fun latest(): Reading? =
        readableDatabase.rawQuery("SELECT $COLUMNS FROM readings ORDER BY time DESC LIMIT 1", null).use {
            if (it.moveToFirst()) it.toReading() else null
        }

    /** Average, minimum and maximum of [metric] per bucket, skipping empty buckets. */
    fun series(metric: Metric, w: Window): List<Bucket> {
        // Numbers are inlined (all Longs) so SQLite does integer division.
        val c = metric.column
        val sql = """
            SELECT ((time + ${w.offsetMs}) / ${w.bucketMs}) * ${w.bucketMs} - ${w.offsetMs} AS b,
                AVG($c), MIN($c), MAX($c), COUNT($c)
            FROM readings WHERE time >= ${w.from} AND time < ${w.to} AND $c IS NOT NULL
            GROUP BY b ORDER BY b
        """.trimIndent()
        return readableDatabase.rawQuery(sql, null).use { cur ->
            buildList {
                while (cur.moveToNext()) {
                    add(Bucket(cur.getLong(0), cur.getDouble(1), cur.getDouble(2), cur.getDouble(3), cur.getInt(4)))
                }
            }
        }
    }

    /** Aggregates of [metric] over the whole window, or null with no readings. */
    fun summary(metric: Metric, w: Window): Bucket? {
        val c = metric.column
        val sql = "SELECT AVG($c), MIN($c), MAX($c), COUNT($c) FROM readings " +
            "WHERE time >= ${w.from} AND time < ${w.to} AND $c IS NOT NULL"
        return readableDatabase.rawQuery(sql, null).use { cur ->
            if (cur.moveToFirst() && cur.getInt(3) > 0) {
                Bucket(w.from, cur.getDouble(0), cur.getDouble(1), cur.getDouble(2), cur.getInt(3))
            } else {
                null
            }
        }
    }

    data class Stats(val count: Long, val first: Long?, val last: Long?, val bytes: Long)

    fun stats(context: Context): Stats =
        readableDatabase.rawQuery("SELECT COUNT(*), MIN(time), MAX(time) FROM readings", null).use { cur ->
            cur.moveToFirst()
            val file = context.getDatabasePath(NAME)
            val wal = java.io.File(file.path + "-wal")
            Stats(
                count = cur.getLong(0),
                first = if (cur.isNull(1)) null else cur.getLong(1),
                last = if (cur.isNull(2)) null else cur.getLong(2),
                bytes = file.length() + wal.length(),
            )
        }

    /** Every reading, oldest first, for export. */
    fun forEach(block: (Reading) -> Unit) {
        readableDatabase.rawQuery("SELECT $COLUMNS FROM readings ORDER BY time", null).use { cur ->
            while (cur.moveToNext()) block(cur.toReading())
        }
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM readings")
        writableDatabase.execSQL("VACUUM")
    }

    private fun Cursor.toReading(): Reading {
        fun d(i: Int) = if (isNull(i)) null else getDouble(i)
        return Reading(
            time = getLong(0),
            device = getString(1),
            packetId = getInt(2),
            pm25 = d(3), co2 = d(4), temperature = d(5), humidity = d(6),
            tvoc = d(7), nox = d(8), battery = d(9),
            rssi = if (isNull(10)) null else getInt(10),
        )
    }

    companion object {
        private const val NAME = "readings.db"
        private const val VERSION = 1
        private const val COLUMNS = "time, device, packet_id, pm25, co2, temperature, humidity, tvoc, nox, battery, rssi"
    }
}
