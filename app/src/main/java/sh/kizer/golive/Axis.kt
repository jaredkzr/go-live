package sh.kizer.golive

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Chart axis ticks. Pure logic, tested on the host. */
object Axis {
    data class Tick(val value: Double, val label: String)
    data class TimeTick(val time: Long, val label: String)

    /** A 1-2-2.5-5 step giving about [target] ticks between [min] and [max]. */
    fun niceStep(min: Double, max: Double, target: Int = 4): Double {
        val raw = (max - min).coerceAtLeast(1e-9) / target
        val mag = 10.0.pow(floor(log10(raw)))
        val norm = raw / mag
        val nice = when {
            norm <= 1.0 -> 1.0
            norm <= 2.0 -> 2.0
            norm <= 2.5 -> 2.5
            norm <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * mag
    }

    fun valueTicks(min: Double, max: Double, target: Int = 4): List<Tick> {
        val step = niceStep(min, max, target)
        val decimals = if (step >= 1) 0 else ceil(-log10(step)).toInt()
        val ticks = mutableListOf<Tick>()
        var v = ceil(min / step) * step
        while (v <= max + step * 1e-6) {
            ticks += Tick(v, String.format(Locale.getDefault(), "%.${decimals}f", v + 0.0))
            v += step
        }
        return ticks
    }

    /** Labelled times inside the window: hours for a day, days for a week, and so on. */
    fun timeTicks(range: TrendRange, from: Long, to: Long, zone: ZoneId, use24h: Boolean, locale: Locale = Locale.getDefault()): List<TimeTick> {
        val start = Instant.ofEpochMilli(from).atZone(zone)
        val (first: ZonedDateTime, step: (ZonedDateTime) -> ZonedDateTime, format: DateTimeFormatter) = when (range) {
            TrendRange.DAY_1 -> Triple(
                start.truncatedTo(ChronoUnit.DAYS).let { d -> generateSequence(d) { it.plusHours(6) }.first { !it.isBefore(start) } },
                { t: ZonedDateTime -> t.plusHours(6) },
                DateTimeFormatter.ofPattern(if (use24h) "HH:mm" else "h a", locale),
            )
            TrendRange.WEEK -> Triple(
                start.truncatedTo(ChronoUnit.DAYS).plusDays(1),
                { t: ZonedDateTime -> t.plusDays(1) },
                DateTimeFormatter.ofPattern("EEE", locale),
            )
            TrendRange.MONTH -> Triple(
                start.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.next(DayOfWeek.MONDAY)),
                { t: ZonedDateTime -> t.plusWeeks(1) },
                DateTimeFormatter.ofPattern("MMM d", locale),
            )
            TrendRange.YEAR -> Triple(
                start.truncatedTo(ChronoUnit.DAYS).with(TemporalAdjusters.firstDayOfNextMonth())
                    .let { if (it.monthValue % 2 == 0) it.plusMonths(1) else it },
                { t: ZonedDateTime -> t.plusMonths(2) },
                DateTimeFormatter.ofPattern("MMM", locale),
            )
        }
        return generateSequence(first, step)
            .takeWhile { it.toInstant().toEpochMilli() < to }
            .map { TimeTick(it.toInstant().toEpochMilli(), format.format(it)) }
            .toList()
    }
}
