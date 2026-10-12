package sh.kizer.golive

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class AxisTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun ms(y: Int, mo: Int, d: Int, h: Int = 0) = ZonedDateTime.of(y, mo, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun niceSteps() {
        assertEquals(10.0, Axis.niceStep(0.0, 40.0), 1e-9)
        assertEquals(250.0, Axis.niceStep(400.0, 1400.0), 1e-9)
        assertEquals(0.5, Axis.niceStep(21.0, 23.0), 1e-9)
    }

    @Test
    fun valueTicksStayInRange() {
        val ticks = Axis.valueTicks(3.2, 41.0)
        assertEquals(listOf(10.0, 20.0, 30.0, 40.0), ticks.map { it.value })
        assertEquals("10", ticks.first().label)
    }

    @Test
    fun dayTicksEverySixHours() {
        val ticks = Axis.timeTicks(TrendRange.DAY_1, ms(2026, 10, 10, 15), ms(2026, 10, 11, 15), zone, use24h = false, Locale.US)
        assertEquals(listOf("6 PM", "12 AM", "6 AM", "12 PM"), ticks.map { it.label })
    }

    @Test
    fun yearTicksEveryOtherMonth() {
        val ticks = Axis.timeTicks(TrendRange.YEAR, ms(2025, 10, 12), ms(2026, 10, 12), zone, use24h = false, Locale.US)
        assertEquals(listOf("Nov", "Jan", "Mar", "May", "Jul", "Sep"), ticks.map { it.label })
    }
}
