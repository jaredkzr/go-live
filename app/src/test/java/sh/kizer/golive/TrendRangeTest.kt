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
        val w = TrendRange.YEAR.window(now, Resolution.NORMAL, zone = la)
        val midnight = ZonedDateTime.of(2026, 10, 12, 0, 0, 0, 0, ZoneId.of("America/Los_Angeles")).toInstant().toEpochMilli()
        assertEquals(midnight, w.to)
        assertEquals(w.to - TrendRange.YEAR.spanMs, w.from)
        assertEquals(24 * 60 * 60 * 1000L, w.bucketMs)
        assertEquals(midnight - w.bucketMs, Buckets.start(now, w.bucketMs, w.offsetMs))
    }

    @Test
    fun dayWindowEndsAfterTheCurrentBucket() {
        val now = 1_000_000_123L
        val w = TrendRange.DAY_1.window(now, Resolution.NORMAL, zone = TimeZone.getTimeZone("UTC"))
        assertEquals(10 * 60 * 1000L, w.bucketMs)
        assertEquals(0L, w.to % w.bucketMs)
        assertEquals(true, now < w.to && now >= w.to - w.bucketMs)
    }

    @Test
    fun resolutionsKeepPointCountsReasonable() {
        for (res in Resolution.entries) {
            for (r in TrendRange.entries) {
                val n = r.spanMs / Resampling.bucketFor(r.spanMs, res.targetPoints)
                // A day is the largest bucket, so a year has 365 points even at Fine.
                assertEquals("${r.name} at ${res.name} has $n buckets", true, n in 30..maxOf(res.targetPoints, 365))
            }
        }
    }
}
