package sh.kizer.golive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BackupTest {
    private val full = Reading(
        time = 1_791_763_200_000, device = "10:BD:A3:E5:10:1A", packetId = 7,
        pm25 = 13.0, co2 = 615.0, temperature = 23.46, humidity = 46.0, pm1 = 8.0, pm10 = 18.0, battery = 87.0, rssi = -61,
    )
    private val sparse = Reading(time = 1_791_763_260_000, device = "10:BD:A3:E5:10:1A", packetId = 8, co2 = 620.0)

    @Test
    fun linesRoundTrip() {
        assertEquals(full, Backup.decode(Backup.encode(full)))
        assertEquals(sparse, Backup.decode(Backup.encode(sparse)))
        assertNull(Backup.decode(Backup.HEADER))
        assertNull(Backup.decode("garbage"))
        assertNull(Backup.decode(Backup.HEADER_TVOC_PM10))
        assertNull(Backup.decode(Backup.HEADER_TVOC_NOX))
    }

    private fun restore(header: String): List<Reading> {
        val dir = Files.createTempDirectory("backup").toFile()
        val file = File(dir, "readings.csv.gz")
        java.util.zip.GZIPOutputStream(file.outputStream()).bufferedWriter().use {
            it.appendLine(header)
            it.appendLine("1791763200000,10:BD:A3:E5:10:1A,7,13,615,23.46,46,120,18,87,-61")
        }
        val back = mutableListOf<Reading>()
        Backup.read(file) { back += it }
        dir.deleteRecursively()
        return back
    }

    @Test
    fun olderBackupsDropRetiredFields() {
        // TVOC 120 sits in PM1.0's slot; NOx (or PM10 18) in PM10's.
        assertEquals(listOf(full.copy(pm1 = null)), restore(Backup.HEADER_TVOC_PM10))
        assertEquals(listOf(full.copy(pm1 = null, pm10 = null)), restore(Backup.HEADER_TVOC_NOX))
        assertEquals(listOf(full.copy(pm1 = 120.0)), restore(Backup.HEADER))
    }

    @Test
    fun fileRoundTripInChunks() {
        val dir = Files.createTempDirectory("backup").toFile()
        val file = File(dir, "nested/readings.csv.gz")
        val readings = (0 until 12_345).map { full.copy(time = full.time + it * 60_000L, packetId = it % 256) }
        assertEquals(readings.size, Backup.write(file) { emit -> readings.forEach(emit) })

        val back = mutableListOf<Reading>()
        val chunks = mutableListOf<Int>()
        Backup.read(file, chunk = 5000) { back += it; chunks += it.size }
        assertEquals(readings, back)
        assertEquals(listOf(5000, 5000, 2345), chunks)
        // A year at one reading a minute should stay far below the 25 MB backup limit.
        val perRow = file.length().toDouble() / readings.size
        assert(perRow * 525_600 < 15_000_000) { "~${(perRow * 525_600 / 1e6).toInt()} MB a year" }
        dir.deleteRecursively()
    }
}
