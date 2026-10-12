package sh.kizer.golive

import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothingTest {
    private val minute = 60_000L

    @Test
    fun bucketSizesSnapToNiceDurations() {
        assertEquals(minute, Resampling.bucketFor(60 * minute))
        assertEquals(minute, Resampling.bucketFor(240 * minute))
        assertEquals(5 * minute, Resampling.bucketFor(24 * 60 * minute))
        assertEquals(60 * minute, Resampling.bucketFor(7 * 24 * 60 * minute))
        assertEquals(3 * 60 * minute, Resampling.bucketFor(30 * 24 * 60 * minute))
        assertEquals(24 * 60 * minute, Resampling.bucketFor(365L * 24 * 60 * minute))
        assertEquals("2 minutes", Resampling.describe(2 * minute))
        assertEquals("an hour", Resampling.describe(60 * minute))
        assertEquals("a day", Resampling.describe(24 * 60 * minute))
    }

    @Test
    fun emaKeepsAFlatLineAndDoesNotShiftAPeak() {
        assertEquals(List(5) { 400.0 }, Smoothing.ema(List(5) { 400.0 }))
        val peak = Smoothing.ema(listOf(0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0))
        assertEquals(3, peak.indices.maxBy { peak[it] })
        assertEquals(peak[2], peak[4], 0.5) // close to symmetric around the peak
        assertEquals(true, peak[3] < 10.0 && peak[3] > 2.0) // softened, not erased
    }

    @Test
    fun aMissedMinuteIsNotAGapButTenMinutesIs() {
        val buckets = listOf(0L, 1, 3, 4, 20, 21).map { Bucket(it * minute, 1.0, 1.0, 1.0, 1) }
        val runs = Smoothing.runs(buckets, Smoothing.gapMs(minute))
        assertEquals(listOf(4, 2), runs.map { it.size })
    }

    @Test
    fun smoothingNeverCrossesAGapAndKeepsTheBandAroundTheLine() {
        val a = List(3) { Bucket(it * minute, 0.0, -1.0, 1.0, 1) }
        val b = List(2) { Bucket((60 + it) * minute, 100.0, 99.0, 101.0, 1) }
        val out = Smoothing.smooth(a + b, minute)
        assertEquals(listOf(0.0, 0.0, 0.0, 100.0, 100.0), out.map { it.avg })
        out.forEach { assertEquals(true, it.min <= it.avg && it.avg <= it.max) }
    }
}
