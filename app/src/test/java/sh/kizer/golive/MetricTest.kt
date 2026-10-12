package sh.kizer.golive

import org.junit.Assert.assertEquals
import org.junit.Test

class MetricTest {
    @Test
    fun bandsIncludeTheirUpperBound() {
        assertEquals(Level.GOOD, Metric.PM25.band(9.0)!!.level)
        assertEquals(Level.MODERATE, Metric.PM25.band(9.1)!!.level)
        assertEquals(Level.BAD, Metric.CO2.band(5000.0)!!.level)
        assertEquals(null, Metric.HUMIDITY.band(50.0))
    }

    @Test
    fun temperatureConvertsToFahrenheit() {
        assertEquals(74.228, Metric.TEMPERATURE.display(23.46, Units(fahrenheit = true)), 1e-9)
        assertEquals("°F", Metric.TEMPERATURE.unit(Units(fahrenheit = true)))
        assertEquals("µg/m³", Metric.PM25.unit(Units(fahrenheit = true)))
    }
}
