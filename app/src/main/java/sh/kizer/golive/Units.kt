package sh.kizer.golive

/**
 * How readings are shown: the Go's own display units (sent in the BTHome
 * broadcast's scan response) or the user's choice in Settings. Stored readings
 * always stay in °C and µg/m³.
 */
data class Units(val fahrenheit: Boolean = false, val pmUsAqi: Boolean = false) {
    companion object {
        /** Manufacturer data company id in the scan response (no company id assigned). */
        const val COMPANY_ID = 0xFFFF
        private const val VERSION = 1
        private const val FLAG_FAHRENHEIT = 0x01
        private const val FLAG_PM_US_AQI = 0x02

        /**
         * Decodes the manufacturer data after the company id: version, flags
         * (see products/go/docs/ble_service.md, "Units Scan Response", in
         * jaredkzr/firmware-core). Null for anything else.
         */
        fun decode(bytes: ByteArray?): Units? {
            if (bytes == null || bytes.size < 2 || bytes[0].toInt() != VERSION) return null
            val flags = bytes[1].toInt() and 0xFF
            return Units(fahrenheit = flags and FLAG_FAHRENHEIT != 0, pmUsAqi = flags and FLAG_PM_US_AQI != 0)
        }
    }
}

/** US AQI from PM2.5, as the Go computes it (airgradient-common aqi.cpp, 2024 EPA breakpoints). */
object Aqi {
    private class Segment(val pmHigh: Double, val aqiLow: Double, val aqiHigh: Double, val pmLow: Double, val pmFrom: Double)

    // Mirrors the firmware's interpolation constants exactly, including its
    // use of 9.0 (not 9.1) as the second segment's starting concentration.
    private val SEGMENTS = listOf(
        Segment(9.0, 0.0, 50.0, 0.0, 0.0),
        Segment(35.4, 51.0, 100.0, 9.1, 9.0),
        Segment(55.4, 101.0, 150.0, 35.5, 35.5),
        Segment(125.4, 151.0, 200.0, 55.5, 55.5),
        Segment(225.4, 201.0, 300.0, 125.5, 125.5),
        Segment(325.4, 301.0, 500.0, 225.5, 225.5),
    )

    fun fromPm25(pm: Double): Int {
        if (pm.isNaN() || pm < 0) return 0
        for (s in SEGMENTS) {
            if (pm <= s.pmHigh) {
                return ((s.aqiHigh - s.aqiLow) / (s.pmHigh - s.pmLow) * (pm - s.pmFrom) + s.aqiLow).toInt()
            }
        }
        return 500
    }
}
