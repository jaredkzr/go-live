package sh.kizer.golive

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

class TrendRangeTest {
    private val la = TimeZone.getTimeZone("America/Los_Angeles")

    @Test
    fun yearBucketsStartAtLocalMidnight() {
        val now = ZonedDateTime.of(2026, 10, 11, 15, 30, 0, 0, ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()
        val w = TrendRange.YEAR.window(now, la)
        val midnight = ZonedDateTime.of(2026, 10, 12, 0, 0, 0, 0, ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()
        assertEquals(midnight, w.to)
        assertEquals(w.to - TrendRange.YEAR.spanMs, w.from)
        assertEquals(24 * 60 * 60 * 1000L, w.bucketMs)
        assertEquals(midnight - w.bucketMs, Buckets.start(now, w.bucketMs, w.offsetMs))
    }

    @Test
    fun dayWindowEndsAfterTheCurrentBucket() {
        val now = 1_000_000_123L
        val w = TrendRange.DAY_1.window(now, TimeZone.getTimeZone("UTC"))
        assertEquals(5 * 60 * 1000L, w.bucketMs)
        assertEquals(0L, w.to % w.bucketMs)
        assertEquals(true, now < w.to && now >= w.to - w.bucketMs)
    }

    @Test
    fun pointCountsStayReasonable() {
        for (r in TrendRange.entries) {
            val n = r.spanMs / Resampling.bucketFor(r.spanMs)
            // A day is the largest bucket, so a year has 365 points.
            assertEquals("${r.name} has $n buckets", true, n in 60..365)
        }
    }
}
