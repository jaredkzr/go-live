package sh.kizer.golive

import java.io.BufferedReader
import java.io.File
import java.io.Writer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A gzipped CSV copy of every reading, rewritten about once a day, for
 * Android's cloud backup. The live database is excluded from cloud backup
 * (a year is ~30 MB, over the 25 MB limit that would stop all backups); this
 * copy compresses about tenfold, and a fresh install restores from it.
 */
object Backup {
    const val HEADER = "time_ms,device,packet_id,pm25,co2,temperature,humidity,tvoc,pm10,battery,rssi"

    /** Backups from before PM10 replaced NOx: the same layout with NOx in PM10's place. */
    const val LEGACY_HEADER = "time_ms,device,packet_id,pm25,co2,temperature,humidity,tvoc,nox,battery,rssi"
    private const val FIELDS = 11

    fun encode(r: Reading): String {
        fun n(v: Double?) = when {
            v == null -> ""
            v == Math.floor(v) && !v.isInfinite() -> v.toLong().toString()
            else -> v.toString()
        }
        return listOf(
            r.time.toString(), r.device, r.packetId.toString(),
            n(r.pm25), n(r.co2), n(r.temperature), n(r.humidity), n(r.tvoc), n(r.pm10), n(r.battery),
            r.rssi?.toString() ?: "",
        ).joinToString(",")
    }

    /** Null for the header or a line that doesn't parse. [legacy] lines drop their NOx. */
    fun decode(line: String, legacy: Boolean = false): Reading? {
        val f = line.split(',')
        if (f.size != FIELDS) return null
        val time = f[0].toLongOrNull() ?: return null
        val packetId = f[2].toIntOrNull() ?: return null
        return Reading(
            time = time,
            device = f[1],
            packetId = packetId,
            pm25 = f[3].toDoubleOrNull(),
            co2 = f[4].toDoubleOrNull(),
            temperature = f[5].toDoubleOrNull(),
            humidity = f[6].toDoubleOrNull(),
            tvoc = f[7].toDoubleOrNull(),
            pm10 = if (legacy) null else f[8].toDoubleOrNull(),
            battery = f[9].toDoubleOrNull(),
            rssi = f[10].toIntOrNull(),
        )
    }

    /** Writes every reading from [source] to [file], replacing it only once complete. */
    fun write(file: File, source: ((Reading) -> Unit) -> Unit): Int {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        var rows = 0
        GZIPOutputStream(tmp.outputStream().buffered()).bufferedWriter().use { out: Writer ->
            out.appendLine(HEADER)
            source { out.appendLine(encode(it)); rows++ }
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            error("Couldn't replace ${file.name}")
        }
        return rows
    }

    /** Reads [file] in chunks of [chunk] readings. */
    fun read(file: File, chunk: Int = 5000, sink: (List<Reading>) -> Unit) {
        GZIPInputStream(file.inputStream().buffered()).bufferedReader().use { input: BufferedReader ->
            val batch = ArrayList<Reading>(chunk)
            var legacy = false
            input.lineSequence().mapNotNull { line ->
                if (line == LEGACY_HEADER) legacy = true
                decode(line, legacy)
            }.forEach {
                batch += it
                if (batch.size == chunk) {
                    sink(batch.toList())
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) sink(batch)
        }
    }
}
