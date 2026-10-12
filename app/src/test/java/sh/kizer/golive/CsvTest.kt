package sh.kizer.golive

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class CsvTest {
    @Test
    fun formatsARow() {
        val r = Reading(
            time = 1_791_763_200_000, device = "10:BD:A3:E5:10:1A", packetId = 7,
            pm25 = 13.0, co2 = 615.0, temperature = 23.46, humidity = 46.0, tvoc = 120.0, nox = null, battery = 87.0, rssi = -61,
        )
        assertEquals(
            "2026-10-11T17:00:00-07:00,1791763200000,10:BD:A3:E5:10:1A,7,13,615,23.46,46,120,,87,-61",
            Csv.row(r, ZoneId.of("America/Los_Angeles")),
        )
        assertEquals(Csv.HEADER.split(",").size, Csv.row(r).split(",").size)
    }
}
