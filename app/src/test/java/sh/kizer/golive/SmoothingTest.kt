package sh.kizer.golive

import org.junit.Assert.assertEquals
import org.junit.Test

class SmoothingTest {
    private val minute = 60_000L

    private fun series(vararg avg: Double, step: Long = minute) =
        avg.mapIndexed { i, v -> Bucket(i * step, v, v - 1, v + 1, 1) }

    @Test
    fun bucketSizesSnapToNiceDurations() {
        assertEquals(minute, Resampling.bucketFor(60 * minute, 150))
        assertEquals(2 * minute, Resampling.bucketFor(240 * minute, 150))
        assertEquals(10 * minute, Resampling.bucketFor(24 * 60 * minute, 150))
        assertEquals(24 * 60 * minute, Resampling.bucketFor(365L * 24 * 60 * minute, 150))
        assertEquals("2 minutes", Resampling.describe(2 * minute))
        assertEquals("an hour", Resampling.describe(60 * minute))
        assertEquals("a day", Resampling.describe(24 * 60 * minute))
    }

    @Test
    fun movingAverageIsCentredAndNarrowsAtTheEnds() {
        val out = Smoothing.filter(listOf(0.0, 3.0, 6.0, 9.0), SmoothingMethod.MOVING_AVERAGE, 3)
        assertEquals(listOf(1.5, 3.0, 6.0, 7.5), out)
    }

    @Test
    fun medianIgnoresASpike() {
        val out = Smoothing.filter(listOf(370.0, 371.0, 420.0, 370.0, 372.0), SmoothingMethod.MEDIAN, 3)
        assertEquals(listOf(370.5, 371.0, 371.0, 372.0, 371.0), out)
    }

    @Test
    fun exponentialKeepsAFlatLineAndDoesNotShiftAPeak() {
        assertEquals(List(5) { 400.0 }, Smoothing.filter(List(5) { 400.0 }, SmoothingMethod.EXPONENTIAL, 7))
        val peak = Smoothing.filter(listOf(0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0), SmoothingMethod.EXPONENTIAL, 3)
        assertEquals(3, peak.indices.maxBy { peak[it] })
        assertEquals(peak[2], peak[4], 0.5) // close to symmetric around the peak
    }

    @Test
    fun aMissedMinuteIsNotAGapButTenMinutesIs() {
        val buckets = listOf(0L, 1, 3, 4, 20, 21).map { Bucket(it * minute, 1.0, 1.0, 1.0, 1) }
        val runs = Smoothing.runs(buckets, Smoothing.gapMs(minute))
        assertEquals(listOf(4, 2), runs.map { it.size })
    }

    @Test
    fun smoothingNeverCrossesAGapAndKeepsTheBandAroundTheLine() {
        val a = series(0.0, 0.0, 0.0)
        val b = series(100.0, 100.0).map { it.copy(start = it.start + 60 * minute) }
        val out = Smoothing.smooth(a + b, minute, SmoothingMethod.MOVING_AVERAGE, 7)
        assertEquals(listOf(0.0, 0.0, 0.0, 100.0, 100.0), out.map { it.avg })
        out.forEach { assertEquals(true, it.min <= it.avg && it.avg <= it.max) }
        assertEquals(a + b, Smoothing.smooth(a + b, minute, SmoothingMethod.NONE, 7))
    }
}
