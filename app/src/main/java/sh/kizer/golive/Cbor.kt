package sh.kizer.golive

import java.io.ByteArrayOutputStream

/**
 * The little CBOR the Go's Config characteristic needs (RFC 8949): maps,
 * arrays, text, integers, floats, booleans and null. Decoding returns
 * Map<Any?, Any?>, List<Any?>, String, Long, Double, Boolean or null.
 */
object Cbor {
    class Writer {
        private val out = ByteArrayOutputStream()

        private fun head(major: Int, n: Long) {
            val m = major shl 5
            when {
                n < 24 -> out.write(m or n.toInt())
                n < 0x100 -> { out.write(m or 24); out.write(n.toInt()) }
                n < 0x10000 -> { out.write(m or 25); be(n, 2) }
                n < 0x100000000 -> { out.write(m or 26); be(n, 4) }
                else -> { out.write(m or 27); be(n, 8) }
            }
        }

        private fun be(v: Long, bytes: Int) {
            for (i in bytes - 1 downTo 0) out.write(((v ushr (8 * i)) and 0xFF).toInt())
        }

        fun map(size: Int) = apply { head(5, size.toLong()) }
        fun array(size: Int) = apply { head(4, size.toLong()) }
        fun text(s: String) = apply { val b = s.toByteArray(); head(3, b.size.toLong()); out.write(b) }
        fun uint(v: Long) = apply { require(v >= 0); head(0, v) }

        /** Always a 32-bit float: the Go rejects half floats and integers where it expects a float. */
        fun float32(v: Float) = apply { out.write(0xFA); be(v.toRawBits().toLong() and 0xFFFFFFFFL, 4) }

        fun bytes(): ByteArray = out.toByteArray()
    }

    class FormatException(message: String) : Exception(message)

    fun decode(bytes: ByteArray): Any? = Reader(bytes).item()

    private class Reader(private val b: ByteArray) {
        private var at = 0

        private fun byte(): Int {
            if (at >= b.size) throw FormatException("truncated")
            return b[at++].toInt() and 0xFF
        }

        private fun be(n: Int): Long {
            var v = 0L
            repeat(n) { v = (v shl 8) or byte().toLong() }
            return v
        }

        private fun argument(info: Int): Long = when (info) {
            in 0..23 -> info.toLong()
            24 -> be(1)
            25 -> be(2)
            26 -> be(4)
            27 -> be(8)
            else -> throw FormatException("indefinite length not supported")
        }

        fun item(): Any? {
            val first = byte()
            val major = first shr 5
            val info = first and 0x1F
            return when (major) {
                0 -> argument(info)
                1 -> -1 - argument(info)
                2 -> ByteArray(argument(info).toInt()) { byte().toByte() }
                3 -> String(ByteArray(argument(info).toInt()) { byte().toByte() })
                4 -> List(argument(info).toInt()) { item() }
                5 -> {
                    val n = argument(info).toInt()
                    LinkedHashMap<Any?, Any?>().apply { repeat(n) { put(item(), item()) } }
                }
                6 -> { argument(info); item() } // tag: keep the value
                else -> when (info) {
                    20 -> false
                    21 -> true
                    22, 23 -> null
                    25 -> half(be(2).toInt())
                    26 -> Float.fromBits(be(4).toInt()).toDouble()
                    27 -> Double.fromBits(be(8))
                    else -> throw FormatException("unsupported simple value $info")
                }
            }
        }

        private fun half(h: Int): Double {
            val exp = (h shr 10) and 0x1F
            val mant = h and 0x3FF
            val v = when (exp) {
                0 -> mant * Math.pow(2.0, -24.0)
                31 -> if (mant == 0) Double.POSITIVE_INFINITY else Double.NaN
                else -> (mant + 1024) * Math.pow(2.0, exp - 25.0)
            }
            return if (h and 0x8000 != 0) -v else v
        }
    }
}

/** A temperature or humidity correction as the Go stores it: value = scale × raw + intercept. */
data class LinearCorrection(val custom: Boolean, val scale: Double, val intercept: Double) {
    companion object {
        val NONE = LinearCorrection(custom = false, scale = 1.0, intercept = 0.0)
        private const val SCHEMA = 1L
        private const val ALGORITHM_NONE = 0L
        private const val ALGORITHM_CUSTOM = 1L

        /** Reads "temp_corr" from a Config snapshot (go_ble_client.md §7.1). Null when absent or malformed. */
        fun fromConfig(config: Any?, key: String = "temp_corr"): LinearCorrection? {
            val group = (config as? Map<*, *>)?.get(key) as? Map<*, *> ?: return null
            if (group["s"] != SCHEMA) return null
            val v = group["v"] as? List<*> ?: return null
            if (v.size != 3) return null
            val algorithm = v[0] as? Long ?: return null
            val scale = (v[1] as? Number)?.toDouble() ?: return null
            val intercept = (v[2] as? Number)?.toDouble() ?: return null
            return when (algorithm) {
                ALGORITHM_NONE -> LinearCorrection(false, scale, intercept)
                ALGORITHM_CUSTOM -> LinearCorrection(true, scale, intercept)
                else -> null
            }
        }

        /** The Config write that replaces the temperature correction (go_ble_client.md §7.2). */
        fun setTemperaturePayload(c: LinearCorrection): ByteArray = Cbor.Writer()
            .map(2)
            .text("op").text("set")
            .text("temp_corr").map(2)
            .text("s").uint(SCHEMA)
            .text("v").array(3)
            .uint(if (c.custom) ALGORITHM_CUSTOM else ALGORITHM_NONE)
            .float32(c.scale.toFloat())
            .float32(c.intercept.toFloat())
            .bytes()
    }
}
