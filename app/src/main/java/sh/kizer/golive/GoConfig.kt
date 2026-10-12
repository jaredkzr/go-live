package sh.kizer.golive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Reads and writes the Go's Config characteristic (go_ble_client.md §7) over a
 * short GATT connection. The phone must already be paired with the Go (for
 * example through the AirGradient app); Android then encrypts the link with
 * the stored bond. Each call connects, does one thing and disconnects, so the
 * Go can go back to sleep.
 */
class GoConfig(private val context: Context, private val address: String) {

    class GoConfigException(message: String) : Exception(message)

    /** The Go's temperature correction. */
    suspend fun readTemperatureCorrection(): LinearCorrection = session {
        val config = Cbor.decode(read())
        LinearCorrection.fromConfig(config) ?: throw GoConfigException("The Go's settings have no temperature correction.")
    }

    /** Replaces the temperature correction and returns what the Go reports afterwards. */
    suspend fun writeTemperatureCorrection(c: LinearCorrection): LinearCorrection = session {
        write(LinearCorrection.setTemperaturePayload(c))
        // A rejected write leaves the stored value as it was, so read it back to confirm.
        LinearCorrection.fromConfig(Cbor.decode(read()))
            ?: throw GoConfigException("Couldn't read the correction back.")
    }

    sealed interface Event {
        data class Connection(val connected: Boolean, val status: Int) : Event
        data class Mtu(val status: Int) : Event
        data class Services(val status: Int) : Event
        data class Read(val value: ByteArray, val status: Int) : Event
        data class Write(val status: Int) : Event
    }

    private inner class Session(val gatt: BluetoothGatt, val events: Channel<Event>) {
        private val characteristic: BluetoothGattCharacteristic by lazy {
            gatt.getService(SERVICE)?.getCharacteristic(CONFIG)
                ?: throw GoConfigException("This Go has no Config characteristic.")
        }

        /** The next event of [type], failing if the Go disconnects first. */
        suspend fun <E : Event> next(type: Class<E>, timeoutMs: Long = OP_TIMEOUT_MS): E = withTimeout(timeoutMs) {
            var found: E? = null
            while (found == null) {
                val e = events.receive()
                when {
                    type.isInstance(e) -> found = type.cast(e)
                    e is Event.Connection && !e.connected -> throw GoConfigException("The Go disconnected.")
                }
            }
            found
        }

        @SuppressLint("MissingPermission")
        suspend fun read(): ByteArray {
            if (!gatt.readCharacteristic(characteristic)) throw GoConfigException("Couldn't start reading the Go's settings.")
            val r = next(Event.Read::class.java)
            if (r.status != BluetoothGatt.GATT_SUCCESS) throw GoConfigException("Reading the Go's settings failed (${r.status}).")
            return r.value
        }

        @SuppressLint("MissingPermission")
        @Suppress("DEPRECATION")
        suspend fun write(value: ByteArray) {
            val started = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = value
                gatt.writeCharacteristic(characteristic)
            }
            if (!started) throw GoConfigException("Couldn't start writing to the Go.")
            val w = next(Event.Write::class.java)
            if (w.status != BluetoothGatt.GATT_SUCCESS) throw GoConfigException("The Go refused the change (${w.status}).")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun <T> session(block: suspend Session.() -> T): T {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: throw GoConfigException("Bluetooth isn't available.")
        if (!adapter.isEnabled) throw GoConfigException("Bluetooth is off.")
        val device = adapter.getRemoteDevice(address)
        if (device.bondState != BluetoothDevice.BOND_BONDED) {
            throw GoConfigException("This phone isn't paired with the Go. Pair it in the AirGradient app first.")
        }

        val events = Channel<Event>(Channel.UNLIMITED)
        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                events.trySend(Event.Connection(newState == BluetoothProfile.STATE_CONNECTED, status))
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                events.trySend(Event.Mtu(status))
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                events.trySend(Event.Services(status))
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                events.trySend(Event.Read(value, status))
            }

            @Deprecated("Android 12 delivers reads here")
            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < 33) events.trySend(Event.Read(c.value ?: ByteArray(0), status))
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                events.trySend(Event.Write(status))
            }
        }

        val gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw GoConfigException("Couldn't start connecting.")
        try {
            // In Portable Sleep the Go is connectable for about a second every 12 s.
            withTimeout(CONNECT_TIMEOUT_MS) {
                while (true) {
                    val e = events.receive()
                    if (e is Event.Connection) {
                        if (e.connected) break
                        throw GoConfigException("Couldn't connect to the Go (${e.status}).")
                    }
                }
            }
            val session = Session(gatt, events)
            // The Config snapshot and writes are longer than the default 20-byte payload.
            if (gatt.requestMtu(MTU)) session.next(Event.Mtu::class.java)
            if (!gatt.discoverServices()) throw GoConfigException("Couldn't look up the Go's services.")
            val s = session.next(Event.Services::class.java)
            if (s.status != BluetoothGatt.GATT_SUCCESS) throw GoConfigException("Looking up the Go's services failed (${s.status}).")
            return session.block()
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw GoConfigException("The Go didn't respond. Unlock it so it stays awake, and stay close.")
        } finally {
            gatt.disconnect()
            gatt.close()
            events.close()
        }
    }

    companion object {
        private val SERVICE = UUID.fromString("d1c0c0a0-6b48-4b2a-9b1d-59f9f2b0a1e1")
        private val CONFIG = UUID.fromString("d1c0c0a3-6b48-4b2a-9b1d-59f9f2b0a1e1")
        private const val MTU = 247
        private const val CONNECT_TIMEOUT_MS = 40_000L
        private const val OP_TIMEOUT_MS = 15_000L
    }
}
