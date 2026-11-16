package app.painmeter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import app.painmeter.core.HrMeasurementParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** What the screens show about the strap. */
sealed interface HrState {
    data object NotPaired : HrState
    data object NoPermission : HrState
    data object BluetoothOff : HrState
    data class Connecting(val name: String) : HrState
    data class Connected(val name: String, val bpm: Int?, val contact: Boolean?) : HrState
}

data class FoundStrap(val address: String, val name: String, val rssi: Int)

/**
 * The Bluetooth LE heart-rate strap: scanning, pairing (remembering the address), connection with
 * automatic reconnection, and readings. All state changes happen on the main thread.
 *
 * The connection is held while someone uses it: the screens ("ui") or a ride ("ride").
 */
@SuppressLint("MissingPermission") // Every Bluetooth call is behind hasPermission().
object HrMonitor {
    private val HR_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCC_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val users = mutableSetOf<String>()
    private var gatt: BluetoothGatt? = null
    private var connectedAddress: String? = null

    private val _state = MutableStateFlow<HrState>(HrState.NotPaired)
    val state: StateFlow<HrState> = _state

    private val _found = MutableStateFlow<List<FoundStrap>>(emptyList())
    val found: StateFlow<List<FoundStrap>> = _found
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning

    /** Receives every reading (time, bpm): the ride in progress. */
    var onReading: ((Long, Int) -> Unit)? = null

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun acquire(who: String) {
        users += who
        refresh()
    }

    fun release(who: String) {
        users -= who
        if (users.isEmpty()) disconnect()
    }

    /** Connects to the paired strap if needed, or reports why it cannot. */
    fun refresh() {
        val s = Settings.current.value
        val address = s.hrDeviceAddress
        when {
            address == null -> { disconnect(); _state.value = HrState.NotPaired }
            !hasPermission() -> _state.value = HrState.NoPermission
            adapter()?.isEnabled != true -> _state.value = HrState.BluetoothOff
            users.isEmpty() -> Unit
            gatt != null && connectedAddress == address -> Unit
            else -> connect(address, s.hrDeviceName ?: "HR strap")
        }
    }

    fun pair(strap: FoundStrap) {
        stopScan()
        disconnect()
        Settings.update { it.copy(hrDeviceAddress = strap.address, hrDeviceName = strap.name) }
        refresh()
    }

    fun forget() {
        disconnect()
        Settings.update { it.copy(hrDeviceAddress = null, hrDeviceName = null) }
        _state.value = HrState.NotPaired
    }

    // ------------------------------------------------------------------ scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val strap = FoundStrap(result.device.address, result.device.name ?: result.scanRecord?.deviceName ?: "Unnamed strap", result.rssi)
            main.post {
                _found.value = (_found.value.filter { it.address != strap.address } + strap).sortedByDescending { it.rssi }
            }
        }
    }

    fun startScan() {
        if (!hasPermission()) { _state.value = HrState.NoPermission; return }
        val scanner = adapter()?.bluetoothLeScanner ?: run { _state.value = HrState.BluetoothOff; return }
        _found.value = emptyList()
        _scanning.value = true
        scanner.startScan(
            listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback,
        )
        main.postDelayed({ stopScan() }, 15_000)
    }

    fun stopScan() {
        if (!_scanning.value) return
        _scanning.value = false
        if (hasPermission()) adapter()?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    // ------------------------------------------------------------------ connection

    private fun connect(address: String, name: String) {
        disconnect()
        val device: BluetoothDevice = adapter()?.getRemoteDevice(address) ?: return
        _state.value = HrState.Connecting(name)
        connectedAddress = address
        // autoConnect = true: Android reconnects by itself whenever the strap comes back in range.
        gatt = device.connectGatt(app, true, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun disconnect() {
        gatt?.let { g ->
            if (hasPermission()) { g.disconnect(); g.close() }
        }
        gatt = null
        connectedAddress = null
        if (_state.value is HrState.Connected || _state.value is HrState.Connecting) {
            _state.value = Settings.current.value.hrDeviceName?.let { HrState.Connecting(it) } ?: HrState.NotPaired
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                if (g != gatt) return@post
                val name = Settings.current.value.hrDeviceName ?: "HR strap"
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _state.value = HrState.Connected(name, null, null)
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _state.value = HrState.Connecting(name)
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        // An error closes the link for good: open a new one after a short wait.
                        val address = connectedAddress ?: return@post
                        g.close()
                        gatt = null
                        main.postDelayed({ if (gatt == null && users.isNotEmpty()) connect(address, name) }, 3_000)
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            main.post {
                val ch = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT) ?: return@post
                g.setCharacteristicNotification(ch, true)
                val cccd = ch.getDescriptor(CCC_DESCRIPTOR) ?: return@post
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            onValue(ch, value)
        }

        @Deprecated("Called before Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            onValue(ch, ch.value ?: return)
        }

        private fun onValue(ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid != HR_MEASUREMENT) return
            val m = HrMeasurementParser.parse(value) ?: return
            val now = System.currentTimeMillis()
            main.post {
                val name = Settings.current.value.hrDeviceName ?: "HR strap"
                // A strap without skin contact often sends 0 or a stale value: do not record it.
                val usable = m.contact != false && m.bpm > 0
                _state.value = HrState.Connected(name, if (usable) m.bpm else null, m.contact)
                if (usable) onReading?.invoke(now, m.bpm)
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun adapter() = app.getSystemService(BluetoothManager::class.java)?.adapter

    fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermission(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED
    }
}
