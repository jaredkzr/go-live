package sh.kizer.golive

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat

/**
 * Listens for the Go's BTHome broadcast in the background. A foreground
 * service keeps the process alive; the scan is filtered on the BTHome service
 * data in the Bluetooth controller and runs in low-power mode, so the phone
 * sleeps between matches and keeps scanning with the screen off.
 */
class ScanService : Service() {
    private val repo by lazy { repository }
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private val restart = Runnable { restartScan() }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        startForeground(
            Notifications.ID,
            Notifications.build(this, repo),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        ContextCompat.registerReceiver(
            this, bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            repo.setCollecting(false)
            stopSelf()
            return START_NOT_STICKY
        }
        startScan()
        return START_STICKY
    }

    override fun onDestroy() {
        stopScan()
        unregisterReceiver(bluetoothState)
        repo.status.value = ScanStatus.Stopped
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // checked first
    private fun startScan() {
        if (scanning) return
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            setStatus(ScanStatus.NoPermission)
            stopSelf()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val leScanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (leScanner == null) {
            setStatus(ScanStatus.BluetoothOff)
            return
        }
        val filter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(Bthome.SERVICE_UUID), ByteArray(0))
            .apply { repo.device.value?.let { setDeviceAddress(it) } }
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            leScanner.startScan(listOf(filter), settings, callback)
        } catch (e: Exception) {
            setStatus(ScanStatus.Failed(-1))
            handler.postDelayed(restart, RETRY_MS)
            return
        }
        scanner = leScanner
        scanning = true
        setStatus(ScanStatus.Scanning)
        // Long-running scans can be demoted by the system; a fresh one isn't.
        handler.postDelayed(restart, RESTART_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        handler.removeCallbacks(restart)
        if (scanning) {
            try {
                scanner?.stopScan(callback)
            } catch (_: Exception) {
                // Bluetooth already off.
            }
        }
        scanning = false
        scanner = null
    }

    private fun restartScan() {
        stopScan()
        startScan()
    }

    private fun setStatus(status: ScanStatus) {
        repo.status.value = status
        Notifications.update(this, repo)
    }

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            setStatus(ScanStatus.Failed(errorCode))
            handler.removeCallbacks(restart)
            handler.postDelayed(restart, RETRY_MS)
        }
    }

    private fun handle(result: ScanResult) {
        val data = result.scanRecord?.getServiceData(ParcelUuid(Bthome.SERVICE_UUID)) ?: return
        val packet = Bthome.decode(data) ?: return
        val device = repo.device.value
        val stored = repo.onBroadcast(result.device.address, result.rssi, packet, System.currentTimeMillis())
        // Present when the scan response was received (firmware with the units scan response).
        val units = Units.decode(result.scanRecord?.getManufacturerSpecificData(Units.COMPANY_ID))
        val unitsChanged = units != null && repo.onDeviceUnits(result.device.address, units)
        if (device == null && repo.device.value != null) {
            // Just locked onto the Go: narrow the hardware filter to it.
            handler.post { restartScan() }
        }
        if (stored != null || unitsChanged) Notifications.update(this, repo)
    }

    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> startScan()
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    stopScan()
                    setStatus(ScanStatus.BluetoothOff)
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP = "sh.kizer.golive.STOP"
        private const val RESTART_MS = 25 * 60 * 1000L
        private const val RETRY_MS = 30 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScanService::class.java))
        }

        fun canScan(context: Context) =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    }
}
