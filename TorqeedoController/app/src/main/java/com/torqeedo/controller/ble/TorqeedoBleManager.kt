package com.torqeedo.controller.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
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
import com.torqeedo.controller.protocol.TorqeedoProtocol
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

/**
 * Nordic BleManager for the AC6328 BLE-UART bridge.
 */
class TorqeedoBleManager(private val context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "TorqeedoBle"

        val SERVICE_AE30_UUID: UUID = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
        val SERVICE_AE00_UUID: UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
        
        val CHAR_AE10_UUID: UUID  = UUID.fromString("0000ae10-0000-1000-8000-00805f9b34fb")
        val CHAR_AE02_UUID: UUID  = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
    }

    private var ae10Char: BluetoothGattCharacteristic? = null
    private var ae02Char: BluetoothGattCharacteristic? = null

    private var isLoggingEnabled = true
    private var isRawDataEnabled = true

    // ── Logging to File ─────────────────────────────────────────────────────
    private val logFile: File by lazy {
        File(context.getExternalFilesDir(null), "torqeedo_ble_log.txt")
    }

    private fun logToFile(direction: String, data: ByteArray) {
        if (!isLoggingEnabled) return
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val hex = data.joinToString(" ") { "%02X".format(it) }
            val line = "[$timestamp] $direction: $hex\n"
            
            FileOutputStream(logFile, true).use { output ->
                output.write(line.toByteArray())
            }
        } catch (e: Exception) {
            Log.e(TAG, "File log failed", e)
        }
    }

    private fun logTextToFile(direction: String, text: String) {
        if (!isLoggingEnabled) return
        try {
            val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "[$timestamp] $direction: $text\n"
            FileOutputStream(logFile, true).use { it.write(line.toByteArray()) }
        } catch (e: Exception) {
            Log.e(TAG, "File log text failed", e)
        }
    }

    fun setLoggingEnabled(enabled: Boolean) {
        isLoggingEnabled = enabled
    }

    fun setRawDataEnabled(enabled: Boolean) {
        isRawDataEnabled = enabled
    }

    // ── Public state ────────────────────────────────────────────────────────
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _statusFlow = MutableSharedFlow<TorqeedoProtocol.MotorStatus>(replay = 1)
    val statusFlow: SharedFlow<TorqeedoProtocol.MotorStatus> = _statusFlow.asSharedFlow()

    private val _sensorCurrent = MutableStateFlow(0f)
    val sensorCurrent: StateFlow<Float> = _sensorCurrent.asStateFlow()

    private val _rawStatusFlow = MutableSharedFlow<ByteArray>(replay = 1)
    val rawStatusFlow: SharedFlow<ByteArray> = _rawStatusFlow.asSharedFlow()

    // ── Buffer for fragmented BLE packets ──────────────────────────────────
    private val rxBuffer = mutableListOf<Byte>()

    override fun getGattCallback(): BleManagerGattCallback = GattCallback()

    private inner class GattCallback : BleManagerGattCallback() {

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val service = gatt.getService(SERVICE_AE30_UUID) 
                ?: gatt.getService(SERVICE_AE00_UUID)
                ?: run {
                    Log.w(TAG, "Compatible Torqeedo service not found")
                    return false
                }

            ae10Char = service.getCharacteristic(CHAR_AE10_UUID)
            ae02Char = service.getCharacteristic(CHAR_AE02_UUID)
            return ae10Char != null && ae02Char != null
        }

        override fun initialize() {
            requestMtu(100).enqueue()

            setNotificationCallback(ae02Char)
                .with { _, data ->
                    data.value?.let { bytes ->
                        logToFile("RECV_RAW", bytes)
                        rxBuffer.addAll(bytes.toList())
                        processBuffer()
                    }
                }
            enableNotifications(ae02Char).enqueue()
        }

        override fun onServicesInvalidated() {
            ae10Char = null
            ae02Char = null
        }
    }

    /**
     * Improved framing logic based on user logs.
     * Some messages end with 0xAD (FOOTER), others seem to be separated by 0xAC (HEADER)
     * or are short bursts.
     */
    private fun processBuffer() {
        while (rxBuffer.isNotEmpty()) {
            val startIdx = rxBuffer.indexOf(TorqeedoProtocol.HEADER)
            if (startIdx == -1) {
                rxBuffer.clear()
                return
            }
            if (startIdx > 0) {
                repeat(startIdx) { rxBuffer.removeAt(0) }
            }

            // Now rxBuffer[0] is HEADER (0xAC)
            // Look for the NEXT header or a footer
            var frameEndIdx = -1
            for (i in 1 until rxBuffer.size) {
                if (rxBuffer[i] == TorqeedoProtocol.HEADER || rxBuffer[i] == TorqeedoProtocol.FOOTER) {
                    frameEndIdx = i
                    break
                }
            }

            if (frameEndIdx == -1) {
                // No delimiter found yet. If buffer is too big, discard.
                if (rxBuffer.size > 256) rxBuffer.removeAt(0)
                return
            }

            // Include the footer if that's what we found
            val frameLength = if (rxBuffer[frameEndIdx] == TorqeedoProtocol.FOOTER) frameEndIdx + 1 else frameEndIdx
            
            val frame = rxBuffer.take(frameLength).toByteArray()
            repeat(frameLength) { rxBuffer.removeAt(0) }

            if (isRawDataEnabled) {
                logToFile("FRAME", frame)
                _rawStatusFlow.tryEmit(frame)
            }
            
            TorqeedoProtocol.parseStatus(frame)?.let { status ->
                _statusFlow.tryEmit(status)
            }
        }
    }

    suspend fun connectToDevice(device: BluetoothDevice) {
        _connectionState.value = ConnectionState.CONNECTING
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            if (isLoggingEnabled) {
                FileOutputStream(logFile, true).use { 
                    it.write("\n--- Session Start: $timestamp (${device.address}) ---\n".toByteArray()) 
                }
            }
            rxBuffer.clear()
            connect(device).retry(3, 300).timeout(10_000).suspend()
            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            throw e
        }
    }

    fun disconnectDevice() {
        disconnect().enqueue()
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun sendDrive(speed: Int) {
        val char = ae10Char ?: return
        val frame = TorqeedoProtocol.buildDrive(speed)
        logToFile("SEND", frame)
        writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    fun sendStatusQuery() {
        val char = ae10Char ?: return
        val frame = TorqeedoProtocol.buildStatusQuery()
        logToFile("SEND_STAT", frame)
        writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }

    fun readCurrentSensor() {
        val char = ae10Char ?: return
        readCharacteristic(char).with { _, data ->
            data.value?.let { bytes ->
                val s = String(bytes)
                if (s.startsWith("V")) {
                    try {
                        val mvStr = s.substring(1).filter { it.isDigit() }
                        if (mvStr.isNotEmpty()) {
                            val mv = mvStr.toInt()
                            // Sensor: CC6903SO-30A
                            // 3.3V supply: 1.65V (1650mV) = 0A. 
                            // 30A full scale corresponds to 0V or 3.3V (approx 55mV/A)
                            val amps = abs(mv - 1650) / 55.0f
                            _sensorCurrent.value = amps
                            logTextToFile("RECV_CURR", "Raw: $s, Amps: $amps")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse current sensor string: $s", e)
                    }
                }
            }
        }.enqueue()
    }

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }
}
