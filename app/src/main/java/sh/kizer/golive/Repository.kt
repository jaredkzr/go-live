package sh.kizer.golive

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.Executors

class GoLiveApp : Application() {
    val repository by lazy { Repository(this) }
}

val Context.repository: Repository get() = (applicationContext as GoLiveApp).repository

/** What the scanner is doing, for the status card and the notification. */
sealed interface ScanStatus {
    data object Stopped : ScanStatus
    data object Scanning : ScanStatus
    data object BluetoothOff : ScanStatus
    data object NoPermission : ScanStatus
    data class Failed(val code: Int) : ScanStatus
}

/** The last broadcast heard from the Go, repeats included. */
data class Live(val time: Long, val packetId: Int, val rssi: Int?)

/**
 * Process-wide state shared by the scan service and the UI: settings, the
 * latest reading and the store. Writes go through one background thread.
 */
class Repository(context: Context) {
    val store = ReadingStore(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val writer = Executors.newSingleThreadExecutor()
    private val filter = ReadingFilter()

    private val _latest = MutableStateFlow<Reading?>(null)
    val latest: StateFlow<Reading?> = _latest.asStateFlow()

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    /** Bumped after every write, so views reload their queries. */
    private val _dataVersion = MutableStateFlow(0L)
    val dataVersion: StateFlow<Long> = _dataVersion.asStateFlow()

    val status = MutableStateFlow<ScanStatus>(ScanStatus.Stopped)

    private val _collecting = MutableStateFlow(prefs.getBoolean(KEY_COLLECTING, true))
    val collecting: StateFlow<Boolean> = _collecting.asStateFlow()

    /** Show readings in the Go's units (true) or in [manualUnits]. */
    private val _followDevice = MutableStateFlow(prefs.getBoolean(KEY_FOLLOW_DEVICE, true))
    val followDevice: StateFlow<Boolean> = _followDevice.asStateFlow()

    private val _manualUnits = MutableStateFlow(
        Units(prefs.getBoolean(KEY_FAHRENHEIT, false), prefs.getBoolean(KEY_PM_US_AQI, false)),
    )
    val manualUnits: StateFlow<Units> = _manualUnits.asStateFlow()

    /** The units the Go last reported, or null before it has (older firmware never does). */
    private val _deviceUnits = MutableStateFlow(
        if (prefs.contains(KEY_DEVICE_FAHRENHEIT)) {
            Units(prefs.getBoolean(KEY_DEVICE_FAHRENHEIT, false), prefs.getBoolean(KEY_DEVICE_PM_US_AQI, false))
        } else {
            null
        },
    )
    val deviceUnits: StateFlow<Units?> = _deviceUnits.asStateFlow()

    /** The units everything is shown in. */
    private val _units = MutableStateFlow(currentUnits())
    val units: StateFlow<Units> = _units.asStateFlow()

    private fun currentUnits(): Units =
        if (_followDevice.value) _deviceUnits.value ?: _manualUnits.value else _manualUnits.value

    /** The Go's Bluetooth address, picked from the first broadcast that looks like a Go. */
    private val _device = MutableStateFlow(prefs.getString(KEY_DEVICE, null))
    val device: StateFlow<String?> = _device.asStateFlow()

    /** The compressed copy cloud backup carries (see Backup). */
    private val backupFile = File(context.filesDir, "backup/readings.csv.gz")

    /** When the backup copy was last written and its size, for Settings. */
    data class BackupInfo(val time: Long, val bytes: Long)

    private val _backupInfo = MutableStateFlow(backupInfoNow())
    val backupInfo: StateFlow<BackupInfo?> = _backupInfo.asStateFlow()

    init {
        writer.execute {
            // A fresh install restored from cloud backup has the copy but no database.
            if (store.isEmpty() && backupFile.exists()) {
                try {
                    Backup.read(backupFile) { store.insertAll(it) }
                } catch (_: Exception) {
                    // A damaged copy: start empty; the next backup replaces it.
                }
                _dataVersion.update { it + 1 }
            }
            store.latest()?.let {
                _latest.value = it
                synchronized(filter) { filter.remember(it.device, it.packetId, it.time) }
            }
        }
    }

    /**
     * Handles one BTHome broadcast. Returns the reading when it was a new
     * measurement from the Go and is being stored, null otherwise.
     */
    fun onBroadcast(address: String, rssi: Int?, packet: Bthome.Packet, now: Long): Reading? {
        val packetId = packet.packetId ?: return null
        synchronized(filter) {
            when (_device.value) {
                null -> if (packet.looksLikeGo) setDevice(address) else return null
                address -> Unit
                else -> return null
            }
            _live.value = Live(now, packetId, rssi)
            if (!filter.accept(address, packetId, now)) return null
        }
        val reading = Reading.from(packet, address, now, rssi)
        _latest.value = reading
        writer.execute {
            store.insert(reading)
            _dataVersion.update { it + 1 }
            if (now - (_backupInfo.value?.time ?: 0) > BACKUP_INTERVAL_MS) writeBackup()
        }
        return reading
    }

    fun setCollecting(on: Boolean) {
        _collecting.value = on
        prefs.edit { putBoolean(KEY_COLLECTING, on) }
    }

    fun setFollowDevice(on: Boolean) {
        _followDevice.value = on
        prefs.edit { putBoolean(KEY_FOLLOW_DEVICE, on) }
        _units.value = currentUnits()
    }

    fun setManualUnits(units: Units) {
        _manualUnits.value = units
        prefs.edit {
            putBoolean(KEY_FAHRENHEIT, units.fahrenheit)
            putBoolean(KEY_PM_US_AQI, units.pmUsAqi)
        }
        _units.value = currentUnits()
    }

    /** The Go's units from its scan response; returns true when they changed. */
    fun onDeviceUnits(address: String, units: Units): Boolean {
        if (address != _device.value || units == _deviceUnits.value) return false
        _deviceUnits.value = units
        prefs.edit {
            putBoolean(KEY_DEVICE_FAHRENHEIT, units.fahrenheit)
            putBoolean(KEY_DEVICE_PM_US_AQI, units.pmUsAqi)
        }
        _units.value = currentUnits()
        return true
    }

    private fun setDevice(address: String?) {
        _device.value = address
        prefs.edit { putString(KEY_DEVICE, address) }
    }

    /** Picks up whichever Go broadcasts next. Stored readings are kept. */
    fun forgetDevice() {
        synchronized(filter) { setDevice(null) }
        _live.value = null
        _deviceUnits.value = null
        prefs.edit {
            remove(KEY_DEVICE_FAHRENHEIT)
            remove(KEY_DEVICE_PM_US_AQI)
        }
        _units.value = currentUnits()
    }

    /** Runs on the writer thread. */
    private fun writeBackup() {
        try {
            Backup.write(backupFile) { emit -> store.forEach(emit) }
        } catch (_: Exception) {
            // Out of space or similar: try again with the next reading after the interval.
        }
        _backupInfo.value = backupInfoNow() ?: BackupInfo(System.currentTimeMillis(), 0)
    }

    private fun backupInfoNow() = backupFile.takeIf { it.exists() }?.let { BackupInfo(it.lastModified(), it.length()) }

    fun clearData() = writer.execute {
        store.clear()
        backupFile.delete()
        _backupInfo.value = null
        _latest.value = null
        _dataVersion.update { it + 1 }
    }

    companion object {
        private const val KEY_COLLECTING = "collecting"
        private const val KEY_FAHRENHEIT = "fahrenheit"
        private const val KEY_PM_US_AQI = "pm_us_aqi"
        private const val KEY_FOLLOW_DEVICE = "follow_device"
        private const val KEY_DEVICE_FAHRENHEIT = "device_fahrenheit"
        private const val KEY_DEVICE_PM_US_AQI = "device_pm_us_aqi"
        private const val KEY_DEVICE = "device"
        private const val BACKUP_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
