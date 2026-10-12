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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.abs
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
import androidx.compose.material3.Switch
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
import sh.kizer.golive.Units
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val FIRMWARE_URL = "https://github.com/jaredkzr/firmware-core"

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val followDevice by vm.repo.followDevice.collectAsStateWithLifecycle()
    val deviceUnits by vm.repo.deviceUnits.collectAsStateWithLifecycle()
    val manualUnits by vm.repo.manualUnits.collectAsStateWithLifecycle()
    val device by vm.repo.device.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val exportMessage by vm.exportMessage.collectAsStateWithLifecycle()
    val backup by vm.repo.backupInfo.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val units by vm.repo.units.collectAsStateWithLifecycle()
    val knownCorrection by vm.repo.temperatureCorrection.collectAsStateWithLifecycle()
    val correctionState by vm.correction.collectAsStateWithLifecycle()
    val connectPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.readCorrection() else vm.correction.value =
            MainViewModel.CorrectionState.Failed("Go Live needs the Nearby devices permission to change the Go's settings.")
    }
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
            Section("Units") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Match the Go", style = MaterialTheme.typography.bodyLarge)
                        Body(
                            deviceUnits?.let { "The Go shows ${describe(it)}." }
                                ?: "The Go hasn't reported its units yet; this needs the custom firmware from October 2026 or later.",
                        )
                    }
                    Switch(checked = followDevice, onCheckedChange = vm.repo::setFollowDevice)
                }
                // Manual units apply when not matching, or until the Go reports its own.
                if (!followDevice || deviceUnits == null) {
                    Choice(listOf("°C", "°F"), if (manualUnits.fahrenheit) 1 else 0) {
                        vm.repo.setManualUnits(manualUnits.copy(fahrenheit = it == 1))
                    }
                    Choice(listOf("µg/m³", "US AQI"), if (manualUnits.pmUsAqi) 1 else 0) {
                        vm.repo.setManualUnits(manualUnits.copy(pmUsAqi = it == 1))
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

            Section("Temperature correction") {
                Body(
                    knownCorrection?.let { k ->
                        if (k.correction.custom) {
                            "The Go corrects every temperature by ${formatOffset(k.correction.intercept, units)}" +
                                (if (k.correction.scale != 1.0) " (scale ×${"%.3f".format(k.correction.scale)})" else "") +
                                " (checked ${dateTime(k.time)})."
                        } else {
                            "The Go reports temperatures uncorrected (checked ${dateTime(k.time)})."
                        }
                    } ?: "The Go reads warm from its own board heat. An offset set here is stored on the Go, so its " +
                        "screen, its broadcast and this app all show corrected temperatures.",
                )
                FilledTonalButton(onClick = {
                    if (context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {
                        vm.readCorrection()
                    } else {
                        connectPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                    }
                }, enabled = device != null) { Text("Adjust") }
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

    if (correctionState != MainViewModel.CorrectionState.Idle) {
        CorrectionDialog(correctionState, units, vm)
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

/** A full-width segmented choice; no check mark, so four or more labels fit. */
@Composable
fun Choice(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected,
                onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, labels.size),
                icon = {},
                label = { Text(label, maxLines = 1, softWrap = false) },
            )
        }
    }
}

private fun describe(units: Units) =
    (if (units.fahrenheit) "°F" else "°C") + " and PM2.5 in " + (if (units.pmUsAqi) "US AQI" else "µg/m³")

/** "−5.0 °F": a temperature difference in °C shown in the display unit. */
private fun formatOffset(interceptC: Double, units: Units): String {
    val v = if (units.fahrenheit) interceptC * 9 / 5 else interceptC
    val sign = if (v < -0.005) "−" else "+"
    return "$sign${"%.1f".format(abs(v))} ${if (units.fahrenheit) "°F" else "°C"}"
}

@Composable
private fun CorrectionDialog(state: MainViewModel.CorrectionState, units: Units, vm: MainViewModel) {
    val unit = if (units.fahrenheit) "°F" else "°C"
    val toDisplay = { c: Double -> if (units.fahrenheit) c * 9 / 5 else c }
    val toCelsius = { d: Double -> if (units.fahrenheit) d * 5 / 9 else d }
    val loaded = (state as? MainViewModel.CorrectionState.Loaded)?.correction
    var text by remember(loaded) {
        mutableStateOf(loaded?.let { "%.1f".format(toDisplay(if (it.custom) it.intercept else 0.0)) } ?: "")
    }
    val value = text.replace(',', '.').replace('−', '-').toDoubleOrNull()

    AlertDialog(
        onDismissRequest = { vm.closeCorrection() },
        title = { Text("Temperature correction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    is MainViewModel.CorrectionState.Working -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                            Text(state.message)
                        }
                        Body("If the Go is asleep, unlock it: in Portable Sleep it only accepts a connection for a moment every 12 s.")
                    }
                    is MainViewModel.CorrectionState.Loaded -> {
                        Body("Added to every temperature the Go measures. Negative lowers the readings.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { text = "%.1f".format((value ?: 0.0) - 0.1) }) { Text("−0.1") }
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                singleLine = true,
                                suffix = { Text(unit) },
                                isError = value == null,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { text = "%.1f".format((value ?: 0.0) + 0.1) }) { Text("+0.1") }
                        }
                        if (state.correction.custom && state.correction.scale != 1.0) {
                            Body("The Go's scale of ×${"%.3f".format(state.correction.scale)} is kept.")
                        }
                        Body("It applies from the Go's next measurement. Readings already stored don't change.")
                    }
                    is MainViewModel.CorrectionState.Saved -> Body(
                        if (state.correction.custom) {
                            "Saved. The Go now corrects temperatures by ${formatOffset(state.correction.intercept, units)}."
                        } else {
                            "Saved. The Go now reports temperatures uncorrected."
                        },
                    )
                    is MainViewModel.CorrectionState.Failed -> Body(state.message)
                    MainViewModel.CorrectionState.Idle -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is MainViewModel.CorrectionState.Loaded -> TextButton(
                    onClick = { value?.let { vm.saveCorrection(state.correction, toCelsius(it)) } },
                    enabled = value != null && abs(value) <= MAX_OFFSET,
                ) { Text("Save") }
                is MainViewModel.CorrectionState.Failed -> TextButton(onClick = { vm.readCorrection() }) { Text("Try again") }
                is MainViewModel.CorrectionState.Saved -> TextButton(onClick = { vm.closeCorrection() }) { Text("Done") }
                else -> Unit
            }
        },
        dismissButton = {
            if (state !is MainViewModel.CorrectionState.Saved) {
                TextButton(onClick = { vm.closeCorrection() }) { Text("Cancel") }
            }
        },
    )
}

/** Offsets beyond this (in the display unit) are almost certainly typos. */
private const val MAX_OFFSET = 20.0

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
