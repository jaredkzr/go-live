package sh.kizer.golive

import java.util.TimeZone

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** A chart span; the bucket size comes from Resampling. */
enum class TrendRange(val label: String, val spanMs: Long) {
    HOUR_1("1 h", HOUR),
    HOURS_4("4 h", 4 * HOUR),
    DAY_1("24 h", DAY),
    WEEK("7 d", 7 * DAY),
    MONTH("30 d", 30 * DAY),
    YEAR("1 y", 365 * DAY),
    ;

    /** The window ending at [now], in Resampling's bucket size for this span. */
    fun window(now: Long, zone: TimeZone = TimeZone.getDefault()): Window = window(now, Resampling.bucketFor(spanMs), zone)

    /**
     * The window ending at [now] in buckets of [bucketMs], aligned to local
     * time so daily buckets start at local midnight.
     */
    fun window(now: Long, bucketMs: Long, zone: TimeZone = TimeZone.getDefault()): Window {
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
