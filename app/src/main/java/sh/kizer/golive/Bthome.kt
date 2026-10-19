package sh.kizer.golive

import java.util.UUID

/**
 * BTHome v2 decoder for the AirGradient Go broadcast (https://bthome.io/format/).
 *
 * Mirrors products/go/main/go_bthome.cpp in https://github.com/jaredkzr/firmware-core
 * and go-viewer/bthome.js on kizer.sh. The Go sends PM10 as 0x0E and, since
 * BTHome has no PM1.0 object, PM1.0 (µg/m³) as a uint16 count (0x3D), which
 * older firmware used for the TVOC index. Firmware before PM10 also sent the
 * NOx index as a uint32 count (0x3E); it is still parsed so those packets
 * decode, then dropped.
 */
object Bthome {
    /** Service data UUID 0xFCD2. */
    val SERVICE_UUID: UUID = UUID.fromString("0000fcd2-0000-1000-8000-00805f9b34fb")

    private const val DEVICE_INFO_ENCRYPTED = 0x01
    private const val DEVICE_INFO_TRIGGER = 0x04
    private const val DEVICE_INFO_VERSION_SHIFT = 5
    private const val VERSION = 2

    private class Spec(val field: String, val length: Int, val signed: Boolean, val scale: Double)

    // Unknown ids end decoding because their length is unknown.
    private val OBJECTS = mapOf(
        0x00 to Spec("packetId", 1, false, 1.0),
        0x01 to Spec("battery", 1, false, 1.0),
        0x02 to Spec("temperature", 2, true, 0.01),
        0x03 to Spec("humidity", 2, false, 0.01),
        0x04 to Spec("pressure", 3, false, 0.01),
        0x0C to Spec("voltage", 2, false, 0.001),
        0x0D to Spec("pm25", 2, false, 1.0),
        0x0E to Spec("pm10", 2, false, 1.0),
        0x12 to Spec("co2", 2, false, 1.0),
        0x2E to Spec("humidity", 1, false, 1.0),
        0x3D to Spec("pm1", 2, false, 1.0),
        0x3E to Spec("nox", 4, false, 1.0),
    )

    data class Packet(
        val packetId: Int?,
        val battery: Double? = null,
        val temperature: Double? = null,
        val humidity: Double? = null,
        val pm1: Double? = null,
        val pm25: Double? = null,
        val pm10: Double? = null,
        val co2: Double? = null,
        val trigger: Boolean = false,
        val truncated: Boolean = false,
    ) {
        /** The Go always sends CO2 or PM; other BTHome sensors rarely do. */
        val looksLikeGo: Boolean get() = co2 != null || pm1 != null || pm10 != null
    }

    /**
     * Decode BTHome service data: the bytes after the 0xFCD2 UUID, starting at
     * the device info byte (what ScanRecord.getServiceData returns). Null when
     * the data is not unencrypted BTHome v2.
     */
    fun decode(bytes: ByteArray): Packet? {
        if (bytes.isEmpty()) return null
        val info = bytes[0].toInt() and 0xFF
        if (info shr DEVICE_INFO_VERSION_SHIFT != VERSION || info and DEVICE_INFO_ENCRYPTED != 0) return null

        val fields = HashMap<String, Double>()
        var truncated = false
        var at = 1
        while (at < bytes.size) {
            val spec = OBJECTS[bytes[at].toInt() and 0xFF]
            if (spec == null || at + 1 + spec.length > bytes.size) {
                truncated = true
                break
            }
            var raw = 0L
            for (i in spec.length - 1 downTo 0) raw = (raw shl 8) or (bytes[at + 1 + i].toLong() and 0xFF)
            if (spec.signed && raw >= 1L shl (8 * spec.length - 1)) raw -= 1L shl (8 * spec.length)
            fields[spec.field] = if (spec.scale == 1.0) raw.toDouble() else Math.round(raw * spec.scale * 1000) / 1000.0
            at += 1 + spec.length
        }
        return Packet(
            packetId = fields["packetId"]?.toInt(),
            battery = fields["battery"],
            temperature = fields["temperature"],
            humidity = fields["humidity"],
            pm1 = fields["pm1"],
            pm25 = fields["pm25"],
            pm10 = fields["pm10"],
            co2 = fields["co2"],
            trigger = info and DEVICE_INFO_TRIGGER != 0,
            truncated = truncated,
        )
    }
}
