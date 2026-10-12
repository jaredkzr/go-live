package sh.kizer.golive.ui

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val FIRMWARE_URL = "https://github.com/jaredkzr/firmware-core"

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val fahrenheit by vm.repo.fahrenheit.collectAsStateWithLifecycle()
    val device by vm.repo.device.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val exportMessage by vm.exportMessage.collectAsStateWithLifecycle()
    val backup by vm.repo.backupInfo.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let(vm::export)
    }

    // Re-check the battery setting whenever the screen comes back from system settings.
    val power = context.getSystemService(PowerManager::class.java)
    var unrestricted by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            unrestricted = power.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            Section("Temperature") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to "°C", true to "°F").forEachIndexed { i, (f, label) ->
                        SegmentedButton(
                            selected = fahrenheit == f,
                            onClick = { vm.repo.setFahrenheit(f) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                            label = { Text(label) },
                        )
                    }
                }
            }

            Section("Background collecting") {
                Body(
                    if (unrestricted) {
                        "Battery use is unrestricted, so Android won't hold back collecting."
                    } else {
                        "Battery use is optimized. To make sure collecting keeps running, open App info → " +
                            "App battery usage and choose Unrestricted."
                    },
                )
                if (!unrestricted) {
                    FilledTonalButton(onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }) { Text("Open App info") }
                }
            }

            Section("Device") {
                Body(
                    device?.let { "Recording from the Go at $it. Other Bluetooth sensors nearby are ignored." }
                        ?: "Not paired yet: the first Go heard is used.",
                )
                if (device != null) {
                    OutlinedButton(onClick = { vm.repo.forgetDevice() }) { Text("Use a different Go") }
                }
            }

            Section("Data") {
                val s = stats
                Body(
                    when {
                        s == null -> "…"
                        s.count == 0L -> "No readings yet."
                        else -> "${s.count} reading${if (s.count == 1L) "" else "s"} since ${date(s.first!!)} · " +
                            Formatter.formatShortFileSize(context, s.bytes)
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { exporter.launch("go-live-readings.csv") }, enabled = (s?.count ?: 0) > 0) {
                        Text("Export CSV")
                    }
                    TextButton(onClick = { confirmDelete = true }, enabled = (s?.count ?: 0) > 0) { Text("Delete all") }
                }
                Body(
                    backup?.let {
                        "Google backup gets a compressed copy (${Formatter.formatShortFileSize(context, it.bytes)}, " +
                            "made ${dateTime(it.time)}), refreshed daily. A new phone restores from it."
                    } ?: "Google backup gets a compressed copy, made after the first reading and refreshed daily.",
                )
                exportMessage?.let { Body(it) }
            }

            Section("About") {
                Body(
                    "Go Live records the BTHome broadcast of an AirGradient Go. Only the custom Go firmware sends it; " +
                        "stock AirGradient firmware doesn't.",
                )
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, FIRMWARE_URL.toUri())) }) {
                    Text("Custom firmware on GitHub")
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete all readings?") },
            text = { Text("This removes every stored reading from this phone. Export a CSV first to keep a copy.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.clearData() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun dateTime(ms: Long): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

private fun date(ms: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))
