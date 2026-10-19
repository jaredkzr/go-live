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
    fun pmBandsFollowTheFullEpaScale() {
        assertEquals(Level.BAD, Metric.PM25.band(125.4)!!.level)
        assertEquals(Level.VERY_BAD, Metric.PM25.band(125.5)!!.level)
        assertEquals(Level.HAZARDOUS, Metric.PM25.band(225.5)!!.level)
        assertEquals(Level.VERY_BAD, Metric.PM10.band(424.0)!!.level)
        assertEquals(Level.HAZARDOUS, Metric.PM10.band(425.0)!!.level)
    }

    @Test
    fun healthRangesCoverEveryBand() {
        for (m in Metric.entries.filter { it.guidance != null }) {
            for (b in m.bands) assert(b.range.isNotEmpty()) { "${m.label} ${b.label} has no range" }
        }
        assertEquals(listOf("0–50", "51–100", "101–150", "151–200", "201–300", "301+"), Metric.PM25.bands.map { it.aqi })
        assertEquals(null, Metric.BATTERY.guidance)
    }

    @Test
    fun temperatureConvertsToFahrenheit() {
        assertEquals(74.228, Metric.TEMPERATURE.display(23.46, Units(fahrenheit = true)), 1e-9)
        assertEquals("°F", Metric.TEMPERATURE.unit(Units(fahrenheit = true)))
        assertEquals("µg/m³", Metric.PM25.unit(Units(fahrenheit = true)))
    }

    @Test
    fun onlyPm25ShowsUsAqi() {
        assertEquals(true, Metric.PM25.showsUsAqi(Units(pmUsAqi = true)))
        assertEquals(false, Metric.PM25.showsUsAqi(Units()))
        assertEquals(false, Metric.PM10.showsUsAqi(Units(pmUsAqi = true)))
    }
}
