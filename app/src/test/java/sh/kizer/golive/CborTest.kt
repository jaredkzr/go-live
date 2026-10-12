package sh.kizer.golive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CborTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

    @Test
    fun setPayloadMatchesTheOneTheFirmwareAccepted() {
        // Checked against the firmware's BleService::decode_config_write on 2026-10-12.
        val payload = LinearCorrection.setTemperaturePayload(LinearCorrection(custom = true, scale = 1.0, intercept = -5.0 / 1.8))
        assertEquals("A2626F70637365746974656D705F636F7272A261730161768301FA3F800000FAC031C71C", payload.hex())
    }

    @Test
    fun turningTheCorrectionOffSendsAlgorithmNone() {
        val payload = LinearCorrection.setTemperaturePayload(LinearCorrection.NONE)
        val decoded = Cbor.decode(payload) as Map<*, *>
        assertEquals("set", decoded["op"])
        assertEquals(LinearCorrection.NONE, LinearCorrection.fromConfig(decoded))
    }

    @Test
    fun readsTheCorrectionFromAConfigSnapshot() {
        // {"meas_int": 60, "temp_f": true, "abc": -1, "pm_aqi": false,
        //  "temp_corr": {"s": 1, "v": [1, 1.0 (half), -2.7778 (float32)]}}
        val snapshot = hex(
            "A5" + "686D6561735F696E74" + "183C" + "6674656D705F66" + "F5" + "63616263" + "20" +
                "66706D5F617169" + "F4" +
                "6974656D705F636F7272" + "A2" + "6173" + "01" + "6176" + "83" + "01" + "F93C00" + "FAC031C71C",
        )
        val config = Cbor.decode(snapshot) as Map<*, *>
        assertEquals(60L, config["meas_int"])
        assertEquals(true, config["temp_f"])
        assertEquals(-1L, config["abc"])
        val c = LinearCorrection.fromConfig(config)!!
        assertEquals(true, c.custom)
        assertEquals(1.0, c.scale, 0.0)
        assertEquals(-2.7778, c.intercept, 1e-4)
    }

    @Test
    fun rejectsMissingOrMalformedGroups() {
        assertNull(LinearCorrection.fromConfig(mapOf("temp_f" to true)))
        assertNull(LinearCorrection.fromConfig(mapOf("temp_corr" to mapOf("s" to 2L, "v" to listOf(1L, 1.0, 0.0)))))
        assertNull(LinearCorrection.fromConfig(mapOf("temp_corr" to mapOf("s" to 1L, "v" to listOf(7L, 1.0, 0.0)))))
        assertNull(LinearCorrection.fromConfig(null))
    }

    @Test(expected = Cbor.FormatException::class)
    fun truncatedInputFails() {
        Cbor.decode(hex("A2626F70"))
    }
}
