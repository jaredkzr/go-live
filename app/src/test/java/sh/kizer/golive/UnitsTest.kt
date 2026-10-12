package sh.kizer.golive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun decodesTheScanResponseFlags() {
        // Manufacturer data after company id 0xFFFF: version 1, flags.
        assertEquals(Units(), Units.decode(bytes(0x01, 0x00)))
        assertEquals(Units(fahrenheit = true), Units.decode(bytes(0x01, 0x01)))
        assertEquals(Units(fahrenheit = true, pmUsAqi = true), Units.decode(bytes(0x01, 0x07)))
        assertNull(Units.decode(bytes(0x02, 0x01)))
        assertNull(Units.decode(bytes(0x01)))
        assertNull(Units.decode(null))
    }

    @Test
    fun aqiMatchesTheFirmware() {
        // Values from airgradient-common aqi.cpp's interpolation.
        assertEquals(0, Aqi.fromPm25(0.0))
        assertEquals(50, Aqi.fromPm25(9.0))
        assertEquals(51, Aqi.fromPm25(9.1)) // (49 / 26.3) * 0.1 + 51 = 51.19
        assertEquals(100, Aqi.fromPm25(35.4))
        assertEquals(101, Aqi.fromPm25(35.5))
        assertEquals(150, Aqi.fromPm25(55.4))
        assertEquals(500, Aqi.fromPm25(400.0))
        assertEquals(67, Aqi.fromPm25(18.0)) // (49 / 26.3) * 9 + 51 = 67.77, truncated like the firmware
    }

    @Test
    fun metricsFollowTheUnits() {
        val us = Units(fahrenheit = true, pmUsAqi = true)
        assertEquals("74.2", Metric.TEMPERATURE.format(23.46, us).replace(',', '.'))
        assertEquals("°F", Metric.TEMPERATURE.unit(us))
        assertEquals("US AQI", Metric.PM25.unit(us))
        assertEquals("67 US AQI", Metric.PM25.formatWithUnit(18.0, us))
        assertEquals("18 µg/m³", Metric.PM25.formatWithUnit(18.0, Units()))
        assertEquals("615 ppm", Metric.CO2.formatWithUnit(615.0, us))
    }
}
