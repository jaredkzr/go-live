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
        assertEquals(midnight - TrendRange.YEAR.bucketMs, Buckets.start(now, w.bucketMs, w.offsetMs))
    }

    @Test
    fun dayWindowEndsAfterTheCurrentBucket() {
        val now = 1_000_000_123L
        val w = TrendRange.DAY_1.window(now, TimeZone.getTimeZone("UTC"))
        assertEquals(0L, w.to % TrendRange.DAY_1.bucketMs)
        assertEquals(true, now < w.to && now >= w.to - TrendRange.DAY_1.bucketMs)
    }

    @Test
    fun bucketsStayBetweenSixtyAndFourHundred() {
        for (r in TrendRange.entries) {
            val n = r.spanMs / r.bucketMs
            assertEquals("${r.name} has $n buckets", true, n in 60..400)
        }
    }
}
