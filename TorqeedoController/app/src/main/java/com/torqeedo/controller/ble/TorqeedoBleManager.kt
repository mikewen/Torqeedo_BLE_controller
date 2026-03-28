package com.torqeedo.controller.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.ble.BleManager
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.ktx.asFlow
import com.torqeedo.controller.protocol.TorqeedoProtocol
import java.util.UUID

/**
 * Nordic BleManager for the AC6328 BLE-UART bridge.
 *
 * Supported Service UUIDs: 0xAE30, 0xAE00
 * ae10  (WRITE_NO_RESP) — send raw TQ Bus frames to motor
 * ae02  (NOTIFY)        — receive raw TQ Bus STATUS replies from motor
 */
class TorqeedoBleManager(context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "TorqeedoBle"

        val SERVICE_AE30_UUID: UUID = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
        val SERVICE_AE00_UUID: UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
        
        val CHAR_AE10_UUID: UUID  = UUID.fromString("0000ae10-0000-1000-8000-00805f9b34fb")
        val CHAR_AE02_UUID: UUID  = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
    }

    private var ae10Char: BluetoothGattCharacteristic? = null
    private var ae02Char: BluetoothGattCharacteristic? = null

    // ── Public state ────────────────────────────────────────────────────────
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusFlow = MutableSharedFlow<TorqeedoProtocol.MotorStatus>(replay = 1)
    val statusFlow: SharedFlow<TorqeedoProtocol.MotorStatus> = _statusFlow.asSharedFlow()

    // ── BleManager overrides ────────────────────────────────────────────────
    override fun getGattCallback(): BleManagerGattCallback = GattCallback()

    private inner class GattCallback : BleManagerGattCallback() {

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(SERVICE_AE30_UUID) 
                ?: gatt.getService(SERVICE_AE00_UUID)
                ?: run {
                    Log.w(TAG, "Compatible Torqeedo service not found (checked 0xAE30 and 0xAE00)")
                    return false
                }

            ae10Char = service.getCharacteristic(CHAR_AE10_UUID)
            ae02Char = service.getCharacteristic(CHAR_AE02_UUID)
            return ae10Char != null && ae02Char != null
        }

        override fun initialize() {
            // Request MTU — AC6328 supports up to 512, 100 is plenty for TQ Bus
            requestMtu(100).enqueue()

            setNotificationCallback(ae02Char)
                .with { _, data ->
                    data.value?.let { bytes ->
                        TorqeedoProtocol.parseStatus(bytes)?.let { status ->
                            _statusFlow.tryEmit(status)
                        } ?: Log.w(TAG, "Malformed STATUS frame: ${bytes.toHex()}")
                    }
                }
            enableNotifications(ae02Char).enqueue()
        }

        override fun onServicesInvalidated() {
            ae10Char = null
            ae02Char = null
        }
    }

    override fun log(priority: Int, message: String) {
        Log.println(priority, TAG, message)
    }

    // ── Connection lifecycle ────────────────────────────────────────────────
    suspend fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        try {
            connect(device)
                .retry(3, 300)
                .timeout(10_000)
                .suspend()
            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
            throw e
        }
    }

    fun disconnectDevice() {
        disconnect().enqueue()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    // ── DRIVE command ───────────────────────────────────────────────────────
    /**
     * Send a speed command to the motor.
     * @param speed  -1000 … +1000
     */
    fun sendDrive(speed: Int) {
        val char = ae10Char ?: return
        val frame = TorqeedoProtocol.buildDrive(speed)
        writeCharacteristic(
            char,
            frame,
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ).enqueue()
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private fun ByteArray.toHex(): String = joinToString(" ") { "0x%02X".format(it) }

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }
}
