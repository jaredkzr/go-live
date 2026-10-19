package sh.kizer.golive

import java.util.Locale
import kotlin.math.roundToLong

/** One stored measurement. Missing fields were not in the broadcast. */
data class Reading(
    val time: Long,
    val device: String,
    val packetId: Int,
    val pm1: Double? = null,
    val pm25: Double? = null,
    val pm10: Double? = null,
    val co2: Double? = null,
    val temperature: Double? = null,
    val humidity: Double? = null,
    val battery: Double? = null,
    val rssi: Int? = null,
) {
    fun value(metric: Metric): Double? = when (metric) {
        Metric.PM1 -> pm1
        Metric.PM25 -> pm25
        Metric.PM10 -> pm10
        Metric.CO2 -> co2
        Metric.TEMPERATURE -> temperature
        Metric.HUMIDITY -> humidity
        Metric.BATTERY -> battery
    }

    companion object {
        fun from(packet: Bthome.Packet, device: String, time: Long, rssi: Int?) = Reading(
            time = time,
            device = device,
            packetId = packet.packetId ?: 0,
            pm1 = packet.pm1,
            pm25 = packet.pm25,
            pm10 = packet.pm10,
            co2 = packet.co2,
            temperature = packet.temperature,
            humidity = packet.humidity,
            battery = packet.battery,
            rssi = rssi,
        )
    }
}

/** Status levels, in the order of the US EPA AQI colours (green to maroon). */
enum class Level { GOOD, MODERATE, POOR, BAD, VERY_BAD, HAZARDOUS }

/**
 * A band covers values up to and including [upTo]. [range] is how it reads in
 * the health-ranges card (in the metric's unit), and [aqi] its US AQI span
 * where the band is an AQI category.
 */
data class Band(
    val upTo: Double,
    val level: Level,
    val label: String,
    val range: String = "",
    val aqi: String? = null,
)

/**
 * What the app shows. [column] is the readings table column; [guidance]
 * explains the health ranges, and is null for metrics without them.
 */
enum class Metric(
    val column: String,
    val label: String,
    private val unit: String,
    private val decimals: Int,
    val bands: List<Band>,
    val guidance: String? = null,
) {
    PM25(
        "pm25", "PM2.5", "µg/m³", 0,
        // US EPA (2024) AQI breakpoints, as in the firmware's aqi.cpp.
        listOf(
            Band(9.0, Level.GOOD, "Good", "0–9.0", "0–50"),
            Band(35.4, Level.MODERATE, "Moderate", "9.1–35.4", "51–100"),
            Band(55.4, Level.POOR, "Unhealthy for sensitive", "35.5–55.4", "101–150"),
            Band(125.4, Level.BAD, "Unhealthy", "55.5–125.4", "151–200"),
            Band(225.4, Level.VERY_BAD, "Very unhealthy", "125.5–225.4", "201–300"),
            Band(Double.MAX_VALUE, Level.HAZARDOUS, "Hazardous", "225.5+", "301+"),
        ),
        "US EPA Air Quality Index categories (2024). They are defined on 24-hour averages, so a short " +
            "spike is a smaller exposure than its category suggests. The WHO 2021 guideline is " +
            "15 µg/m³ as a 24-hour average and 5 µg/m³ as an annual average.",
    ),
    // No official PM1.0 bands, so no status.
    PM1(
        "pm1", "PM1.0", "µg/m³", 0, emptyList(),
        "There are no official health ranges for PM1.0. These finest particles are part of PM2.5, " +
            "so the PM2.5 ranges are a conservative guide.",
    ),
    PM10(
        "pm10", "PM10", "µg/m³", 0,
        // US EPA PM10 AQI breakpoints. The Go has no PM10 AQI, so values stay in µg/m³.
        listOf(
            Band(54.0, Level.GOOD, "Good", "0–54", "0–50"),
            Band(154.0, Level.MODERATE, "Moderate", "55–154", "51–100"),
            Band(254.0, Level.POOR, "Unhealthy for sensitive", "155–254", "101–150"),
            Band(354.0, Level.BAD, "Unhealthy", "255–354", "151–200"),
            Band(424.0, Level.VERY_BAD, "Very unhealthy", "355–424", "201–300"),
            Band(Double.MAX_VALUE, Level.HAZARDOUS, "Hazardous", "425+", "301+"),
        ),
        "US EPA Air Quality Index categories, defined on 24-hour averages. The WHO 2021 guideline " +
            "is 45 µg/m³ as a 24-hour average and 15 µg/m³ as an annual average.",
    ),
    CO2(
        "co2", "CO₂", "ppm", 0,
        listOf(
            Band(800.0, Level.GOOD, "Good", "0–800"),
            Band(1000.0, Level.MODERATE, "Moderate", "801–1000"),
            Band(1500.0, Level.POOR, "Poor", "1001–1500"),
            Band(Double.MAX_VALUE, Level.BAD, "High", "1500+"),
        ),
        "Indoor ventilation guidance, not a toxicity scale: outdoor air is about 420 ppm, and indoor " +
            "CO₂ rises with exhaled air. Rooms above about 1000 ppm feel stuffy, and some studies find " +
            "poorer concentration and decision-making there. The US workplace limit (OSHA) is 5000 ppm " +
            "averaged over 8 hours.",
    ),
    TEMPERATURE("temperature", "Temperature", "°C", 1, emptyList()),
    HUMIDITY("humidity", "Humidity", "%", 0, emptyList()),
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
