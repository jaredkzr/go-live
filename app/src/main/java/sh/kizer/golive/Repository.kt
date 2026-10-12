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

    private val _fahrenheit = MutableStateFlow(prefs.getBoolean(KEY_FAHRENHEIT, false))
    val fahrenheit: StateFlow<Boolean> = _fahrenheit.asStateFlow()

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

    fun setFahrenheit(on: Boolean) {
        _fahrenheit.value = on
        prefs.edit { putBoolean(KEY_FAHRENHEIT, on) }
    }

    private fun setDevice(address: String?) {
        _device.value = address
        prefs.edit { putString(KEY_DEVICE, address) }
    }

    /** Picks up whichever Go broadcasts next. Stored readings are kept. */
    fun forgetDevice() {
        synchronized(filter) { setDevice(null) }
        _live.value = null
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
        private const val KEY_DEVICE = "device"
        private const val BACKUP_INTERVAL_MS = 24 * 60 * 60 * 1000L
    }
}
