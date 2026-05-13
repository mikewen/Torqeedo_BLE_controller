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
 * Nordic BleManager for the AC6328 BLE-UART bridge or WitMotion sensors.
 */
class TorqeedoBleManager(private val context: Context) : BleManager(context) {

    companion object {
        private const val TAG = "TorqeedoBle"

        // Torqeedo / AC6328 UUIDs
        val SERVICE_AE30_UUID: UUID = UUID.fromString("0000ae30-0000-1000-8000-00805f9b34fb")
        val SERVICE_AE00_UUID: UUID = UUID.fromString("0000ae00-0000-1000-8000-00805f9b34fb")
        
        val CHAR_AE10_UUID: UUID  = UUID.fromString("0000ae10-0000-1000-8000-00805f9b34fb")
        val CHAR_AE02_UUID: UUID  = UUID.fromString("0000ae02-0000-1000-8000-00805f9b34fb")
        val CHAR_AE03_UUID: UUID  = UUID.fromString("0000ae03-0000-1000-8000-00805f9b34fb")

        // WitMotion UUIDs
        val SERVICE_WIT_UUID: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val CHAR_WIT_UUID: UUID    = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")

        private const val SENSOR_HEADER: Byte = 0xA5.toByte()
        private const val WIT_HEADER: Byte = 0x55.toByte()
    }

    private var ae10Char: BluetoothGattCharacteristic? = null
    private var ae02Char: BluetoothGattCharacteristic? = null
    private var ae03Char: BluetoothGattCharacteristic? = null

    private var isLoggingEnabled = true
    private var isRawDataEnabled = true

    // ── GPS for logging ─────────────────────────────────────────────────────
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    private var lastSpeed: Float? = null
    private var lastCog: Int? = null

    fun updateGpsInfo(lat: Double?, lon: Double?, speedKnots: Float?, cog: Int?) {
        lastLat = lat
        lastLon = lon
        lastSpeed = speedKnots
        lastCog = cog
    }

    // ── Logging to File ─────────────────────────────────────────────────────
    private val logFile: File by lazy {
        File(context.getExternalFilesDir(null), "torqeedo_ble_log.txt")
    }

    private fun getLogPrefix(): String {
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val gps = if (lastLat != null && lastLon != null) {
            " [%.6f, %.6f, %.1f kn, %s°]".format(
                Locale.US, lastLat, lastLon, lastSpeed ?: 0f, lastCog?.toString() ?: "-"
            )
        } else {
            ""
        }
        return "[$timestamp]$gps"
    }

    private fun logToFile(direction: String, data: ByteArray) {
        if (!isLoggingEnabled) return
        try {
            val prefix = getLogPrefix()
            val hex = data.joinToString(" ") { "%02X".format(it) }
            val line = "$prefix $direction: $hex\n"
            
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
            val prefix = getLogPrefix()
            val line = "$prefix $direction: $text\n"
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

    private val telemetryAccumulator = TorqeedoProtocol.TelemetryAccumulator()
    private val _statusFlow = MutableSharedFlow<TorqeedoProtocol.MotorStatus>(replay = 1)
    val statusFlow: SharedFlow<TorqeedoProtocol.MotorStatus> = _statusFlow.asSharedFlow()

    private val _sensorCurrent = MutableStateFlow(0f)
    val sensorCurrent: StateFlow<Float> = _sensorCurrent.asStateFlow()

    private val _magnetometerData = MutableSharedFlow<ByteArray>(replay = 1)
    val magnetometerData: SharedFlow<ByteArray> = _magnetometerData.asSharedFlow()

    private val _witMotionData = MutableSharedFlow<ByteArray>(replay = 1)
    val witMotionData: SharedFlow<ByteArray> = _witMotionData.asSharedFlow()

    private val _rawStatusFlow = MutableSharedFlow<ByteArray>(replay = 1)
    val rawStatusFlow: SharedFlow<ByteArray> = _rawStatusFlow.asSharedFlow()

    // ── Buffer for fragmented BLE packets ──────────────────────────────────
    private val rxBuffer = mutableListOf<Byte>()

    override fun getGattCallback(): BleManagerGattCallback = GattCallback()

    private inner class GattCallback : BleManagerGattCallback() {

        override fun isRequiredServiceSupported(gatt: BluetoothGatt): Boolean {
            val tqService = gatt.getService(SERVICE_AE30_UUID) ?: gatt.getService(SERVICE_AE00_UUID)
            if (tqService != null) {
                ae10Char = tqService.getCharacteristic(CHAR_AE10_UUID)
                ae02Char = tqService.getCharacteristic(CHAR_AE02_UUID)
                ae03Char = tqService.getCharacteristic(CHAR_AE03_UUID)
                return ae10Char != null && ae02Char != null && ae03Char != null
            }
            
            val witService = gatt.getService(SERVICE_WIT_UUID)
            if (witService != null) {
                ae02Char = witService.getCharacteristic(CHAR_WIT_UUID)
                // Map other chars to the same characteristic for consistency
                ae10Char = ae02Char 
                ae03Char = ae02Char
                return ae02Char != null
            }
            
            return false
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
            ae03Char = null
        }
    }

    private fun processBuffer() {
        while (rxBuffer.isNotEmpty()) {
            val idxAC = rxBuffer.indexOf(TorqeedoProtocol.HEADER)
            val idxA5 = rxBuffer.indexOf(SENSOR_HEADER)
            val idx55 = rxBuffer.indexOf(WIT_HEADER)

            val startIdx = listOf(idxAC, idxA5, idx55).filter { it != -1 }.minOrNull() ?: -1

            if (startIdx == -1) {
                if (rxBuffer.size > 1024) rxBuffer.clear()
                return
            }
            if (startIdx > 0) {
                repeat(startIdx) { rxBuffer.removeAt(0) }
            }

            // Now rxBuffer[0] is either 0xAC, 0xA5 or 0x55
            val header = rxBuffer[0]

            when (header) {
                SENSOR_HEADER -> {
                    // Sensor packet is 11 bytes: [0xA5, SEQ, DATA x 9]
                    if (rxBuffer.size >= 11) {
                        val frame = rxBuffer.take(11).toByteArray()
                        repeat(11) { rxBuffer.removeAt(0) }

                        val rawMagData = frame.copyOfRange(2, 11)
                        _magnetometerData.tryEmit(rawMagData)

                        if (isRawDataEnabled) {
                            logToFile("RECV_MAG", frame)
                            _rawStatusFlow.tryEmit(frame)
                        }
                    } else {
                        return
                    }
                }
                WIT_HEADER -> {
                    // WitMotion packet can be 11 bytes (serial) or 20 bytes (BLE 5.0)
                    if (rxBuffer.size >= 2) {
                        val type = rxBuffer[1].toInt() and 0xFF
                        val len = if (type == 0x61) 20 else 11
                        
                        if (rxBuffer.size >= len) {
                            val frame = rxBuffer.take(len).toByteArray()
                            repeat(len) { rxBuffer.removeAt(0) }
                            
                            _witMotionData.tryEmit(frame)

                            if (isRawDataEnabled) {
                                logToFile("RECV_WIT", frame)
                                _rawStatusFlow.tryEmit(frame)
                            }
                        } else {
                            return // Wait for more data
                        }
                    } else {
                        return // Wait for type byte
                    }
                }
                else -> {
                    // TQ Bus logic (0xAC)
                    var frameEndIdx = -1
                    for (i in 1 until rxBuffer.size) {
                        if (rxBuffer[i] == TorqeedoProtocol.HEADER ||
                            rxBuffer[i] == TorqeedoProtocol.FOOTER ||
                            rxBuffer[i] == SENSOR_HEADER ||
                            rxBuffer[i] == WIT_HEADER) {
                            frameEndIdx = i
                            break
                        }
                    }

                    if (frameEndIdx == -1) {
                        if (rxBuffer.size > 256) rxBuffer.removeAt(0)
                        return
                    }

                    val frameLength = if (rxBuffer[frameEndIdx] == TorqeedoProtocol.FOOTER) frameEndIdx + 1 else frameEndIdx
                    val frame = rxBuffer.take(frameLength).toByteArray()
                    repeat(frameLength) { rxBuffer.removeAt(0) }

                    if (isRawDataEnabled) {
                        logToFile("FRAME", frame)
                        _rawStatusFlow.tryEmit(frame)
                    }

                    telemetryAccumulator.update(frame)
                    telemetryAccumulator.build()?.let { status ->
                        _statusFlow.tryEmit(status)
                    }
                }
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
            telemetryAccumulator.clear()
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
        logToFile("SEND_DRIVE", frame)
        writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }
    
    fun sendSteer(value: Int, runtimeMs: Int = 0) {
        val char = ae03Char ?: return
        val dir = if (value < 0) 'L' else 'R'
        val power: Byte = 100 
        val rtLo = (runtimeMs and 0xFF).toByte()
        val rtHi = ((runtimeMs shr 8) and 0xFF).toByte()
        val frame = byteArrayOf('s'.code.toByte(), dir.code.toByte(), power, rtLo, rtHi)
        Log.d(TAG, "SEND_STEER: ${frame.joinToString(" ") { "%02X".format(it) }}")
        logToFile("SEND_STEER", frame)
        writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE).enqueue()
    }

    fun sendStatusQuery() {
        val char = ae10Char ?: return
        val frame = TorqeedoProtocol.buildStatusQuery(TorqeedoProtocol.MOTOR_ADDR)
        logToFile("SEND_STAT", frame)
        writeCharacteristic(char, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT).enqueue()
    }
    
    fun sendSteerStatusQuery() {
        val char = ae10Char ?: return
        val frame = TorqeedoProtocol.buildStatusQuery(TorqeedoProtocol.STEER_ADDR)
        logToFile("SEND_STAT_STEER", frame)
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
