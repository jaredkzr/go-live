package sh.kizer.golive.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.kizer.golive.Bucket
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
                        label = { Text(r.label) },
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
                    val sel = t?.buckets?.getOrNull(selected ?: -1)
                    Header(metric, t, sel, units)
                    Box(Modifier.fillMaxWidth().height(260.dp).padding(top = 8.dp), contentAlignment = Alignment.Center) {
                        when {
                            t == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            t.buckets.isEmpty() -> Text("No readings in this range yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            Text(
                "Each point is the average over ${bucketName(range)}; the band spans the lowest and highest reading in it. " +
                    "Touch and drag the chart to read values.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
    }
}

@Composable
private fun Header(metric: Metric, trend: Trend?, selected: Bucket?, units: Units) {
    val (big, small) = when {
        selected != null && trend != null -> metric.formatWithUnit(selected.avg, units) to
            "${bucketTime(trend, selected)} · ${metric.format(selected.min, units)}–${metric.formatWithUnit(selected.max, units)}"
        trend?.summary != null -> metric.formatWithUnit(trend.summary.avg, units) to "Average over ${rangeName(trend.range)}"
        else -> "—" to " "
    }
    Column(Modifier.padding(start = 4.dp)) {
        Text(big, style = MaterialTheme.typography.headlineSmall)
        Text(small, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

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
    return when (trend.range) {
        TrendRange.YEAR -> day.format(start)
        else -> "${day.format(start)}, ${time.format(start)}–${time.format(end)}"
    }
}

private fun bucketName(range: TrendRange) = when (range) {
    TrendRange.DAY_1 -> "5 minutes"
    TrendRange.WEEK -> "30 minutes"
    TrendRange.MONTH -> "2 hours"
    TrendRange.YEAR -> "a day"
}

private fun rangeName(range: TrendRange) = when (range) {
    TrendRange.DAY_1 -> "the last 24 hours"
    TrendRange.WEEK -> "the last 7 days"
    TrendRange.MONTH -> "the last 30 days"
    TrendRange.YEAR -> "the last year"
}
