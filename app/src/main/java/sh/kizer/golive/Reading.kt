package sh.kizer.golive

import java.util.Locale
import kotlin.math.roundToLong

/** One stored measurement. Missing fields were not in the broadcast. */
data class Reading(
    val time: Long,
    val device: String,
    val packetId: Int,
    val pm25: Double? = null,
    val co2: Double? = null,
    val temperature: Double? = null,
    val humidity: Double? = null,
    val tvoc: Double? = null,
    val nox: Double? = null,
    val battery: Double? = null,
    val rssi: Int? = null,
) {
    fun value(metric: Metric): Double? = when (metric) {
        Metric.PM25 -> pm25
        Metric.CO2 -> co2
        Metric.TEMPERATURE -> temperature
        Metric.HUMIDITY -> humidity
        Metric.TVOC -> tvoc
        Metric.NOX -> nox
        Metric.BATTERY -> battery
    }

    companion object {
        fun from(packet: Bthome.Packet, device: String, time: Long, rssi: Int?) = Reading(
            time = time,
            device = device,
            packetId = packet.packetId ?: 0,
            pm25 = packet.pm25,
            co2 = packet.co2,
            temperature = packet.temperature,
            humidity = packet.humidity,
            tvoc = packet.tvoc,
            nox = packet.nox,
            battery = packet.battery,
            rssi = rssi,
        )
    }
}

enum class Level { GOOD, MODERATE, POOR, BAD }

/** A band covers values up to and including [upTo]. */
data class Band(val upTo: Double, val level: Level, val label: String)

/** What the app shows. [column] is the readings table column. */
enum class Metric(
    val column: String,
    val label: String,
    private val unit: String,
    private val decimals: Int,
    val bands: List<Band>,
) {
    PM25(
        "pm25", "PM2.5", "µg/m³", 0,
        // US EPA (2024) breakpoints.
        listOf(
            Band(9.0, Level.GOOD, "Good"),
            Band(35.4, Level.MODERATE, "Moderate"),
            Band(55.4, Level.POOR, "Unhealthy for sensitive"),
            Band(Double.MAX_VALUE, Level.BAD, "Unhealthy"),
        ),
    ),
    CO2(
        "co2", "CO₂", "ppm", 0,
        listOf(
            Band(800.0, Level.GOOD, "Good"),
            Band(1000.0, Level.MODERATE, "Moderate"),
            Band(1500.0, Level.POOR, "Poor"),
            Band(Double.MAX_VALUE, Level.BAD, "High"),
        ),
    ),
    TEMPERATURE("temperature", "Temperature", "°C", 1, emptyList()),
    HUMIDITY("humidity", "Humidity", "%", 0, emptyList()),
    TVOC(
        "tvoc", "TVOC index", "", 0,
        // Sensirion VOC index: 100 is the typical background.
        listOf(
            Band(150.0, Level.GOOD, "Good"),
            Band(250.0, Level.MODERATE, "Moderate"),
            Band(400.0, Level.POOR, "Poor"),
            Band(Double.MAX_VALUE, Level.BAD, "High"),
        ),
    ),
    NOX(
        "nox", "NOx index", "", 0,
        // Sensirion NOx index: 1 is the typical background.
        listOf(
            Band(20.0, Level.GOOD, "Good"),
            Band(150.0, Level.MODERATE, "Moderate"),
            Band(300.0, Level.POOR, "Poor"),
            Band(Double.MAX_VALUE, Level.BAD, "High"),
        ),
    ),
    BATTERY(
        "battery", "Battery", "%", 0,
        listOf(
            Band(10.0, Level.BAD, "Low"),
            Band(25.0, Level.MODERATE, "Getting low"),
            Band(Double.MAX_VALUE, Level.GOOD, "OK"),
        ),
    ),
    ;

    fun band(value: Double): Band? = bands.firstOrNull { value <= it.upTo }

    fun unit(units: Units): String = when {
        this == TEMPERATURE && units.fahrenheit -> "°F"
        this == PM25 && units.pmUsAqi -> "US AQI"
        else -> unit
    }

    /** Converts a stored value (°C, µg/m³) to the unit shown. */
    fun display(value: Double, units: Units): Double = when {
        this == TEMPERATURE && units.fahrenheit -> value * 9 / 5 + 32
        this == PM25 && units.pmUsAqi -> Aqi.fromPm25(value).toDouble()
        else -> value
    }

    /** The number as shown, without the unit. */
    fun format(value: Double, units: Units): String {
        val v = display(value, units)
        return if (decimals == 0) v.roundToLong().toString() else String.format(Locale.getDefault(), "%.${decimals}f", v)
    }

    /** The number and unit, for one-line summaries. */
    fun formatWithUnit(value: Double, units: Units): String {
        val u = unit(units)
        return when {
            u.isEmpty() -> format(value, units)
            u.startsWith("°") || u == "%" -> format(value, units) + u
            else -> format(value, units) + " " + u
        }
    }
}
