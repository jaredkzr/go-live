package sh.kizer.golive.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.kizer.golive.Bucket
import sh.kizer.golive.Resampling
import sh.kizer.golive.Metric
import sh.kizer.golive.Units
import sh.kizer.golive.TrendRange
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun TrendScreen(vm: MainViewModel, onBack: () -> Unit) {
    val metric by vm.trendMetric.collectAsStateWithLifecycle()
    val range by vm.trendRange.collectAsStateWithLifecycle()
    val trend by vm.trend.collectAsStateWithLifecycle()
    val units by vm.repo.units.collectAsStateWithLifecycle()
    val latest by vm.repo.latest.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Int?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(metric.label) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in Metric.entries) {
                    FilterChip(selected = m == metric, onClick = { vm.trendMetric.value = m; selected = null }, label = { Text(m.label) })
                }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                TrendRange.entries.forEachIndexed { i, r ->
                    SegmentedButton(
                        selected = r == range,
                        onClick = { vm.trendRange.value = r; selected = null },
                        shape = SegmentedButtonDefaults.itemShape(i, TrendRange.entries.size),
                        icon = {}, // no check mark: six labels share the row
                        label = { Text(r.label, maxLines = 1, softWrap = false) },
                    )
                }
            }

            val t = trend?.takeIf { it.metric == metric && it.range == range }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 10.dp)) {
                    Header(metric, t, selected, units)
                    Box(Modifier.fillMaxWidth().height(260.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        when {
                            t == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            t.smoothed.isEmpty() -> Text("No readings in this range yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else -> TrendChart(t, units, selected, { selected = it }, Modifier.fillMaxSize())
                        }
                    }
                }
            }
            t?.summary?.let { s ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Stat("Average", metric.formatWithUnit(s.avg, units), Modifier.weight(1f))
                        Stat("Low", metric.formatWithUnit(s.min, units), Modifier.weight(1f))
                        Stat("High", metric.formatWithUnit(s.max, units), Modifier.weight(1f))
                        Stat("Readings", s.count.toString(), Modifier.weight(1f))
                    }
                }
            }
            metric.guidance?.let { HealthRanges(metric, it, latest?.value(metric), units) }
            t?.let {
                Text(
                    caption(it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}

private fun caption(t: Trend): String =
    "Each point averages ${Resampling.describe(t.window.bucketMs)}, smoothed with a light exponential moving average; " +
        "the band spans the lowest and highest readings. The stats use the raw readings. " +
        "Touch and drag the chart to read values."

@Composable
private fun Header(metric: Metric, trend: Trend?, selectedIndex: Int?, units: Units) {
    val smoothed = selectedIndex?.let { trend?.smoothed?.getOrNull(it) }
    val raw = selectedIndex?.let { trend?.buckets?.getOrNull(it) }
    val (big, small) = when {
        smoothed != null && raw != null && trend != null -> metric.formatWithUnit(smoothed.avg, units) to
            "${bucketTime(trend, raw)} · readings ${metric.format(raw.min, units)}–${metric.formatWithUnit(raw.max, units)}"
        trend?.summary != null -> metric.formatWithUnit(trend.summary.avg, units) to "Average over ${rangeName(trend.range)}"
        else -> "—" to " "
    }
    Column(Modifier.padding(start = 4.dp)) {
        Text(big, style = MaterialTheme.typography.headlineSmall)
        Text(small, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * The metric's health ranges, marking the one the latest reading falls in.
 * The first line of each range is in the unit the app shows, the second in
 * the other one (µg/m³ and US AQI for PM).
 */
@Composable
private fun HealthRanges(metric: Metric, guidance: String, latest: Double?, units: Units) {
    val current = latest?.let { metric.band(it) }
    val unit = metric.unit(Units())
    val aqiFirst = metric.showsUsAqi(units)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        // Rows pad themselves so the highlight can extend past the text; the
        // title and note pad the same amount to stay aligned with the rows.
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Health ranges",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
            )
            for (band in metric.bands) {
                val now = band == current
                val weight = if (now) FontWeight.SemiBold else FontWeight.Normal
                val highlight = if (now) band.level.color().copy(alpha = HIGHLIGHT_ALPHA) else Color.Transparent
                Row(
                    Modifier.fillMaxWidth()
                        .background(highlight, RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(9.dp).background(band.level.color(), CircleShape))
                    Text(
                        if (now) "${band.label} · now" else band.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = weight,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    val concentration = "${band.range} $unit"
                    val (first, second) = when {
                        band.aqi == null -> concentration to null
                        aqiFirst -> "${band.aqi} US AQI" to concentration
                        else -> concentration to "AQI ${band.aqi}"
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(first, style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
                        second?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                guidance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp),
            )
        }
    }
}

/** Tint of the current range's row: its status colour, faint enough to keep text readable. */
private const val HIGHLIGHT_ALPHA = 0.18f

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

private fun bucketTime(trend: Trend, b: Bucket): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(b.start).atZone(zone)
    val end = Instant.ofEpochMilli(b.start + trend.window.bucketMs).atZone(zone)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val day = DateTimeFormatter.ofPattern("EEE MMM d")
    return if (trend.window.bucketMs >= DAY_MS) day.format(start) else "${day.format(start)}, ${time.format(start)}–${time.format(end)}"
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

private fun rangeName(range: TrendRange) = when (range) {
    TrendRange.HOUR_1 -> "the last hour"
    TrendRange.HOURS_4 -> "the last 4 hours"
    TrendRange.DAY_1 -> "the last 24 hours"
    TrendRange.WEEK -> "the last 7 days"
    TrendRange.MONTH -> "the last 30 days"
    TrendRange.YEAR -> "the last year"
}
