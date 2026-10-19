package sh.kizer.golive

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** CSV export: one row per reading, in the units the Go measures in. */
object Csv {
    const val HEADER =
        "time,time_ms,device,packet_id,pm25_ugm3,co2_ppm,temperature_c,humidity_pct,tvoc_index,pm10_ugm3,battery_pct,rssi_dbm"

    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun row(r: Reading, zone: ZoneId = ZoneId.systemDefault()): String {
        fun n(v: Double?) = when {
            v == null -> ""
            v == Math.floor(v) && !v.isInfinite() -> v.toLong().toString()
            else -> v.toString()
        }
        val time = ISO.format(Instant.ofEpochMilli(r.time).atZone(zone).withNano(0))
        return listOf(
            time, r.time.toString(), r.device, r.packetId.toString(),
            n(r.pm25), n(r.co2), n(r.temperature), n(r.humidity), n(r.tvoc), n(r.pm10), n(r.battery),
            r.rssi?.toString() ?: "",
        ).joinToString(",")
    }
}
