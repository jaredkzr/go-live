package sh.kizer.golive.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import sh.kizer.golive.Live
import sh.kizer.golive.Metric
import sh.kizer.golive.Units
import sh.kizer.golive.ScanStatus

private const val STALE_MS = 3 * 60 * 1000L

@Composable
fun MainScreen(
    vm: MainViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onCollectingChange: (Boolean) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenTrend: (Metric) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val latest by vm.repo.latest.collectAsStateWithLifecycle()
    val live by vm.repo.live.collectAsStateWithLifecycle()
    val status by vm.repo.status.collectAsStateWithLifecycle()
    val collecting by vm.repo.collecting.collectAsStateWithLifecycle()
    val units by vm.repo.units.collectAsStateWithLifecycle()
    val sparklines by vm.sparklines.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    val stale = latest?.let { now - it.time > STALE_MS } ?: false

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Go Live") },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                StatusCard(
                    hasPermission, collecting, status, live, latest?.time, now,
                    onRequestPermission, onCollectingChange, onOpenBluetoothSettings,
                )
            }
            items(Metric.entries, span = { GridItemSpan(if (it == Metric.PM25) maxLineSpan else 1) }) { metric ->
                MetricTile(
                    metric = metric,
                    value = latest?.value(metric),
                    units = units,
                    spark = sparklines[metric],
                    wide = metric == Metric.PM25,
                    dimmed = stale,
                    onClick = { onOpenTrend(metric) },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    hasPermission: Boolean,
    collecting: Boolean,
    status: ScanStatus,
    live: Live?,
    lastStored: Long?,
    now: Long,
    onRequestPermission: () -> Unit,
    onCollectingChange: (Boolean) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
) {
    val heard = live?.time ?: lastStored
    val (title, detail) = when {
        !hasPermission -> "Bluetooth permission needed" to "Go Live listens for the Go's broadcast; it never connects to it."
        !collecting -> "Not collecting" to "Turn on to record readings in the background."
        status == ScanStatus.BluetoothOff -> "Bluetooth is off" to "Readings resume when it's back on."
        status is ScanStatus.Failed -> "Scanning failed (${status.code})" to "Retrying shortly."
        heard == null -> "Listening for the Go…" to "It broadcasts about every 12 s, also in Portable Sleep."
        now - heard > STALE_MS -> "Last heard ${ago(now - heard)} ago" to "Out of range, or the Go is off."
        else -> "Updated ${ago(now - heard)} ago" to listOfNotNull(
            live?.let { "Packet ${it.packetId}" },
            live?.rssi?.let { "$it dBm" },
            "collecting in the background",
        ).joinToString(" · ")
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (hasPermission) {
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = collecting, onCheckedChange = onCollectingChange)
                }
            }
            when {
                !hasPermission -> FilledTonalButton(onClick = onRequestPermission, modifier = Modifier.padding(top = 10.dp)) {
                    Text("Allow Bluetooth")
                }
                collecting && status == ScanStatus.BluetoothOff ->
                    FilledTonalButton(onClick = onOpenBluetoothSettings, modifier = Modifier.padding(top = 10.dp)) {
                        Text("Bluetooth settings")
                    }
            }
        }
    }
}

@Composable
private fun MetricTile(
    metric: Metric,
    value: Double?,
    units: Units,
    spark: Spark?,
    wide: Boolean,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    metric.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                value?.let { metric.band(it) }?.let { StatusChip(it.level, it.label) }
            }
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 2.dp)) {
                val color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                Text(
                    value?.let { metric.format(it, units) } ?: "—",
                    fontSize = if (wide) 44.sp else 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                )
                val unit = metric.unit(units)
                if (value != null && unit.isNotEmpty()) {
                    Text(
                        unit,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 3.dp, bottom = if (wide) 9.dp else 6.dp),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().height(if (wide) 56.dp else 36.dp).padding(top = 6.dp)) {
                spark?.let {
                    Sparkline(it.buckets, it.window.bucketMs, it.window.from, it.window.to, Modifier.fillMaxWidth().height(if (wide) 50.dp else 30.dp))
                }
            }
        }
    }
}

@Composable
fun StatusChip(level: sh.kizer.golive.Level, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).background(level.color(), CircleShape))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 5.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "$s s"
        s < 3600 -> "${s / 60} min"
        s < 48 * 3600 -> "${s / 3600} h"
        else -> "${s / 86400} d"
    }
}
