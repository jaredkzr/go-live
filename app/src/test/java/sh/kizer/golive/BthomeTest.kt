package sh.kizer.golive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Vectors match products/go/tests/go_bthome.tests.cpp in jaredkzr/firmware-core.
class BthomeTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun decodesFullGoPacket() {
        val data = bytes(
            0x40,
            0x00, 0x07,
            0x01, 0x57,
            0x02, 0x2A, 0x09,
            0x0D, 0x0D, 0x00,
            0x0E, 0x12, 0x00,
            0x12, 0x67, 0x02,
            0x2E, 0x2E,
            0x3D, 0x78, 0x00,
        )
        assertEquals(
            Bthome.Packet(
                packetId = 7, battery = 87.0, temperature = 23.46, humidity = 46.0,
                pm25 = 13.0, pm10 = 18.0, co2 = 615.0, tvoc = 120.0,
            ),
            Bthome.decode(data),
        )
    }

    @Test
    fun skipsNoxFromOlderFirmware() {
        val p = Bthome.decode(bytes(0x40, 0x00, 0x07, 0x3E, 0x01, 0x00, 0x00, 0x00, 0x2E, 0x2E))!!
        assertEquals(false, p.truncated)
        assertEquals(46.0, p.humidity!!, 1e-9)
        assertNull(p.pm10)
    }

    @Test
    fun decodesPacketIdOnly() {
        val p = Bthome.decode(bytes(0x40, 0x00, 0xFF))!!
        assertEquals(255, p.packetId)
        assertEquals(false, p.looksLikeGo)
    }

    @Test
    fun decodesNegativeTemperature() {
        assertEquals(-5.25, Bthome.decode(bytes(0x40, 0x02, 0xF3, 0xFD))!!.temperature!!, 1e-9)
    }

    @Test
    fun rejectsEncryptedAndOtherVersions() {
        assertNull(Bthome.decode(bytes(0x41, 0x00, 0x01)))
        assertNull(Bthome.decode(bytes(0x20, 0x00, 0x01)))
        assertNull(Bthome.decode(ByteArray(0)))
    }

    @Test
    fun stopsAtUnknownOrTruncatedObjects() {
        val unknown = Bthome.decode(bytes(0x40, 0x00, 0x01, 0xF0, 0x12, 0x20, 0x03))!!
        assertEquals(1, unknown.packetId)
        assertNull(unknown.co2)
        assertTrue(unknown.truncated)
        val short = Bthome.decode(bytes(0x40, 0x00, 0x01, 0x12, 0x20))!!
        assertNull(short.co2)
        assertTrue(short.truncated)
    }
}
