package sh.kizer.golive

import java.util.TimeZone

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** A chart span and the bucket size that keeps it around 300 points. */
enum class TrendRange(val label: String, val spanMs: Long, val bucketMs: Long) {
    DAY_1("24 h", DAY, 5 * MINUTE),
    WEEK("7 d", 7 * DAY, 30 * MINUTE),
    MONTH("30 d", 30 * DAY, 2 * HOUR),
    YEAR("1 y", 365 * DAY, DAY),
    ;

    /**
     * The window ending at [now], with buckets aligned to local time so a
     * year's daily buckets start at local midnight.
     */
    fun window(now: Long, zone: TimeZone = TimeZone.getDefault()): Window {
        val offset = zone.getOffset(now).toLong()
        val end = Buckets.start(now, bucketMs, offset) + bucketMs
        return Window(from = end - spanMs, to = end, bucketMs = bucketMs, offsetMs = offset)
    }
}

/** [from, to) in epoch ms, split into buckets of [bucketMs] shifted by [offsetMs]. */
data class Window(val from: Long, val to: Long, val bucketMs: Long, val offsetMs: Long)

object Buckets {
    /** The start of the bucket holding [time]; matches the SQL in ReadingStore.series. */
    fun start(time: Long, bucketMs: Long, offsetMs: Long): Long =
        Math.floorDiv(time + offsetMs, bucketMs) * bucketMs - offsetMs
}

/** Aggregates for one bucket, or for a whole window. */
data class Bucket(val start: Long, val avg: Double, val min: Double, val max: Double, val count: Int)
