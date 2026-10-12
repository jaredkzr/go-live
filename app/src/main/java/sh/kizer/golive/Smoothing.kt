package sh.kizer.golive

import kotlin.math.max

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

object Resampling {
    /** About how many points a chart has; sets the bucket size. */
    const val TARGET_POINTS = 300

    /** Bucket sizes that divide a day, so buckets line up with local hours and midnight. */
    private val NICE = listOf(
        MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE,
        HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY,
    )

    /** The smallest nice bucket giving at most [targetPoints] over [spanMs]; one day at most. */
    fun bucketFor(spanMs: Long, targetPoints: Int = TARGET_POINTS): Long =
        NICE.firstOrNull { it * targetPoints >= spanMs } ?: DAY

    /** "5 minutes", "an hour", "a day", for the chart's caption. */
    fun describe(bucketMs: Long): String = when {
        bucketMs == DAY -> "a day"
        bucketMs >= HOUR -> plural(bucketMs / HOUR, "hour")
        else -> plural(bucketMs / MINUTE, "minute")
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) (if (unit == "hour") "an hour" else "a $unit") else "$n ${unit}s"
}

/**
 * Chart smoothing: a light exponential moving average run forwards and then
 * backwards, so it removes jitter without lagging behind real changes.
 */
object Smoothing {
    /** The EMA span, in chart points. */
    const val WINDOW = 3

    /**
     * Longest step between buckets drawn as one line. The Go measures about
     * once a minute with some jitter, so a missed bucket or two is not an
     * outage; ten minutes without a reading is.
     */
    fun gapMs(bucketMs: Long): Long = max(bucketMs * 5 / 2, 10 * MINUTE)

    /** Splits buckets into runs without gaps longer than [gapMs]. */
    fun runs(buckets: List<Bucket>, gapMs: Long): List<List<Bucket>> {
        val out = mutableListOf<MutableList<Bucket>>()
        for (b in buckets) {
            val run = out.lastOrNull()
            if (run == null || b.start - run.last().start > gapMs) out += mutableListOf(b) else run += b
        }
        return out
    }

    /**
     * Smooths average, minimum and maximum within each run (never across a
     * gap). Returns one bucket per input bucket, in order.
     */
    fun smooth(buckets: List<Bucket>, bucketMs: Long, window: Int = WINDOW): List<Bucket> =
        runs(buckets, gapMs(bucketMs)).flatMap { run ->
            val avg = ema(run.map { it.avg }, window)
            val min = ema(run.map { it.min }, window)
            val max = ema(run.map { it.max }, window)
            run.mapIndexed { i, b -> b.copy(avg = avg[i], min = minOf(min[i], avg[i]), max = maxOf(max[i], avg[i])) }
        }

    /** EMA with the span of [window] points, forwards then backwards so peaks stay in place. */
    fun ema(xs: List<Double>, window: Int = WINDOW): List<Double> {
        if (xs.isEmpty()) return xs
        val alpha = 2.0 / (window + 1)
        val forward = DoubleArray(xs.size)
        forward[0] = xs[0]
        for (i in 1 until xs.size) forward[i] = alpha * xs[i] + (1 - alpha) * forward[i - 1]
        val back = DoubleArray(xs.size)
        back[xs.size - 1] = forward[xs.size - 1]
        for (i in xs.size - 2 downTo 0) back[i] = alpha * forward[i] + (1 - alpha) * back[i + 1]
        return back.toList()
    }
}
