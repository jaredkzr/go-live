package sh.kizer.golive

import kotlin.math.max

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE
private const val DAY = 24 * HOUR

/** How the trend chart smooths bucket values. */
enum class SmoothingMethod(val label: String, val description: String) {
    NONE("Off", "not smoothed"),
    MOVING_AVERAGE("Average", "a centred moving average"),
    MEDIAN("Median", "a moving median, which ignores one-off spikes"),
    EXPONENTIAL("EMA", "an exponential moving average run both ways, so it doesn't lag"),
}

/** The smoothing window, in chart points. */
enum class SmoothingStrength(val label: String, val window: Int) {
    LIGHT("Light", 3),
    MEDIUM("Medium", 7),
    STRONG("Strong", 15),
}

/** About how many points a chart has; sets the bucket size. */
enum class Resolution(val label: String, val targetPoints: Int) {
    FINE("Fine", 300),
    NORMAL("Normal", 150),
    COARSE("Coarse", 60),
}

data class ChartOptions(
    val method: SmoothingMethod = SmoothingMethod.MOVING_AVERAGE,
    val strength: SmoothingStrength = SmoothingStrength.MEDIUM,
    val resolution: Resolution = Resolution.NORMAL,
    val curved: Boolean = true,
)

object Resampling {
    /** Bucket sizes that divide a day, so buckets line up with local hours and midnight. */
    private val NICE = listOf(
        MINUTE, 2 * MINUTE, 5 * MINUTE, 10 * MINUTE, 15 * MINUTE, 30 * MINUTE,
        HOUR, 2 * HOUR, 3 * HOUR, 6 * HOUR, 12 * HOUR, DAY,
    )

    /** The smallest nice bucket giving at most [targetPoints] over [spanMs]; one day at most. */
    fun bucketFor(spanMs: Long, targetPoints: Int): Long = NICE.firstOrNull { it * targetPoints >= spanMs } ?: DAY

    /** "5 minutes", "2 hours", "a day", for the chart's caption. */
    fun describe(bucketMs: Long): String = when {
        bucketMs == DAY -> "a day"
        bucketMs >= HOUR -> plural(bucketMs / HOUR, "hour")
        else -> plural(bucketMs / MINUTE, "minute")
    }

    private fun plural(n: Long, unit: String) = if (n == 1L) (if (unit == "hour") "an hour" else "a $unit") else "$n ${unit}s"
}

object Smoothing {
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
    fun smooth(buckets: List<Bucket>, bucketMs: Long, method: SmoothingMethod, window: Int): List<Bucket> {
        if (method == SmoothingMethod.NONE || window <= 1) return buckets
        return runs(buckets, gapMs(bucketMs)).flatMap { run ->
            val avg = filter(run.map { it.avg }, method, window)
            val min = filter(run.map { it.min }, method, window)
            val max = filter(run.map { it.max }, method, window)
            run.mapIndexed { i, b -> b.copy(avg = avg[i], min = minOf(min[i], avg[i]), max = maxOf(max[i], avg[i])) }
        }
    }

    fun filter(xs: List<Double>, method: SmoothingMethod, window: Int): List<Double> = when (method) {
        SmoothingMethod.NONE -> xs
        SmoothingMethod.MOVING_AVERAGE -> centred(xs, window) { it.average() }
        SmoothingMethod.MEDIAN -> centred(xs, window) { w -> w.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 } }
        SmoothingMethod.EXPONENTIAL -> exponential(xs, window)
    }

    /** Applies [f] to a window centred on each point, narrowed at the ends. */
    private fun centred(xs: List<Double>, window: Int, f: (List<Double>) -> Double): List<Double> {
        val half = window / 2
        return xs.indices.map { i -> f(xs.subList(max(0, i - half), minOf(xs.size, i + half + 1))) }
    }

    /** EMA with the span of [window] points, forwards then backwards so peaks stay in place. */
    private fun exponential(xs: List<Double>, window: Int): List<Double> {
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
