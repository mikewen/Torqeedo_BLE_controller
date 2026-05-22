package com.torqeedo.controller.protocol

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

object TorqeedoProtocol {

    const val HEADER: Byte = 0xAC.toByte()
    const val FOOTER: Byte = 0xAD.toByte()

    const val MASTER_ADDR: Byte  = 0x00.toByte()
    const val REMOTE_ADDR: Byte  = 0x01.toByte()
    const val REMOTE1_ADDR: Byte = 0x14.toByte()
    const val DISPLAY_ADDR: Byte = 0x20.toByte()
    const val MOTOR_ADDR: Byte   = 0x30.toByte()
    const val STEER_ADDR: Byte   = 0x38.toByte()
    const val BATTERY_ADDR: Byte = 0x80.toByte()
    
    const val MSGID_DRIVE: Byte = 0x82.toByte()
    const val MSGID_STATUS: Byte = 0x03.toByte() // Status query message ID (MOTOR_PARAM)

    private const val DRIVE_FLAGS: Byte = 0x01

    // ---------- File logging ----------
    private var logWriter: BufferedWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    /**
     * Call this once (e.g., in your Application or main Activity) to enable logging.
     * Requires WRITE_EXTERNAL_STORAGE permission (or use app-specific directory).
     */
    fun enableLogging(context: Context) {
        if (logWriter != null) return
        try {
            val logFile = File(context.getExternalFilesDir(null), "torqeedo_ble_log_status.txt")
            logWriter = BufferedWriter(FileWriter(logFile, true)) // append mode
            log("--- Logging started ---")
        } catch (e: Exception) {
            Log.e("TorqeedoProtocol", "Failed to open log file", e)
        }
    }

    fun disableLogging() {
        logWriter?.close()
        logWriter = null
    }

    private fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val fullMessage = "[$timestamp] $message"
        Log.d("TorqeedoProtocol", fullMessage)
        try {
            logWriter?.write(fullMessage)
            logWriter?.newLine()
            logWriter?.flush()
        } catch (e: Exception) {
            // ignore
        }
    }

    // ---------- Frame extraction ----------
    fun extractFrames(buffer: ByteArray): List<ByteArray> {
        val frames = mutableListOf<ByteArray>()
        var i = 0
        while (i < buffer.size) {
            if (buffer[i] == HEADER) {
                // A7 frames are fixed 5 bytes: AC A7 ID DATA_H DATA_L
                if (i + 1 < buffer.size && (buffer[i + 1].toInt() and 0xFF) == 0xA7) {
                    if (i + 5 <= buffer.size) {
                        frames.add(buffer.copyOfRange(i, i + 5))
                        i += 5
                        continue
                    } else break
                }
                // AC ... AD frames
                var j = i + 1
                var foundFooter = false
                while (j < buffer.size) {
                    if (buffer[j] == FOOTER) {
                        foundFooter = true
                        break
                    }
                    j++
                }
                if (foundFooter) {
                    frames.add(buffer.copyOfRange(i, j + 1))
                    i = j + 1
                } else {
                    break
                }
            } else {
                i++
            }
        }
        return frames
    }

    fun parseStatus(raw: ByteArray): MotorStatus? {
        val frames = extractFrames(raw)
        for (frame in frames) {
            val status = parseFrame(frame)
            if (status != null) return status
        }
        return null
    }

    fun parseFullStatus(raw: ByteArray): MotorStatus? {
        val frames = extractFrames(raw)
        val acc = TelemetryAccumulator()
        for (frame in frames) {
            acc.update(frame)
        }
        return acc.build()
    }

    // =========================
    // CRC (Maxim/Dallas CRC-8)
    // =========================
    fun crc8Maxim(data: ByteArray, length: Int = data.size): Byte {
        var crc = 0
        for (i in 0 until length) {
            var byte = data[i].toInt() and 0xFF
            repeat(8) {
                val mix = (crc xor byte) and 0x01
                crc = crc ushr 1
                if (mix != 0) crc = crc xor 0x8C
                byte = byte ushr 1
            }
        }
        return crc.toByte()
    }

    // =========================
    // BUILD DRIVE (AC ... AD)
    // =========================
    fun buildDrive(speed: Int): ByteArray = buildDrive(MOTOR_ADDR, speed)

    fun buildDrive(addr: Byte, speed: Int): ByteArray {
        val s = speed.coerceIn(-1000, 1000)
        val sHi = ((s shr 8) and 0xFF).toByte()
        val sLo = (s and 0xFF).toByte()
        val powerPct = (abs(s) / 10).toByte()
        val raw = byteArrayOf(addr, MSGID_DRIVE, DRIVE_FLAGS, powerPct, sHi, sLo)
        val crc = crc8Maxim(raw)
        return byteArrayOf(HEADER, *raw, crc, FOOTER)
    }

    /**
     * Builds the special drive response used in slave mode to reply to master polls.
     */
    fun buildSlaveResponse(speed: Int): ByteArray {
        val s = speed.coerceIn(-1000, 1000)
        val sHi = ((s shr 8) and 0xFF).toByte()
        val sLo = (s and 0xFF).toByte()
        val raw = byteArrayOf(0x30.toByte(), 0x82.toByte(), 0x05.toByte(), 0x00.toByte(), sHi, sLo)
        val crc = crc8Maxim(raw)
        return byteArrayOf(HEADER, *raw, crc, FOOTER)
    }

    // =========================
    // BUILD STATUS QUERY
    // =========================
    fun buildStatusQuery(addr: Byte = MOTOR_ADDR): ByteArray {
        val raw = byteArrayOf(addr, MSGID_STATUS)
        val crc = crc8Maxim(raw)
        return byteArrayOf(HEADER, *raw, crc, FOOTER)
    }

    fun errorDescription(errorCode: Int): String = when (errorCode) {
        0 -> "OK"
        1 -> "Overcurrent"
        2 -> "Overvoltage"
        3 -> "Undervoltage"
        4 -> "Overtemperature"
        5 -> "Motor blocked"
        else -> "Unknown ($errorCode)"
    }

    // =========================
    // DATA MODEL
    // =========================
    data class MotorStatus(
        val rpm: Int = 0,
        val voltage: Float = 0f,
        val current: Float = 0f,
        val powerW: Int = 0,
        val tempC: Float = 0f,
        val pcbTempC: Float = 0f,
        val statorTempC: Float = 0f,
        val errorCode: Int = 0,
        val rawBytes: ByteArray = byteArrayOf(),
        val targetSpeed: Int? = null,
        val destAddr: Byte? = null,
        val msgId: Byte? = null
    ) {
        val hasError: Boolean get() = errorCode != 0
        val errorDescription: String get() = errorDescription(errorCode)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as MotorStatus
            if (rpm != other.rpm) return false
            if (voltage != other.voltage) return false
            if (current != other.current) return false
            if (powerW != other.powerW) return false
            if (tempC != other.tempC) return false
            if (pcbTempC != other.pcbTempC) return false
            if (statorTempC != other.statorTempC) return false
            if (errorCode != other.errorCode) return false
            if (!rawBytes.contentEquals(other.rawBytes)) return false
            if (targetSpeed != other.targetSpeed) return false
            if (destAddr != other.destAddr) return false
            if (msgId != other.msgId) return false
            return true
        }

        override fun hashCode(): Int {
            var result = rpm
            result = 31 * result + voltage.hashCode()
            result = 31 * result + current.hashCode()
            result = 31 * result + powerW
            result = 31 * result + tempC.hashCode()
            result = 31 * result + pcbTempC.hashCode()
            result = 31 * result + statorTempC.hashCode()
            result = 31 * result + errorCode
            result = 31 * result + rawBytes.contentHashCode()
            result = 31 * result + (targetSpeed ?: 0)
            result = 31 * result + (destAddr?.toInt() ?: 0)
            result = 31 * result + (msgId?.toInt() ?: 0)
            return result
        }
    }

    // =========================
    // TELEMETRY ACCUMULATOR
    // =========================
    class TelemetryAccumulator {
        private var rpm: Int? = null
        private var voltage: Float? = null
        private var current: Float? = null
        private var tempC: Float? = null
        private var pcbTempC: Float? = null
        private var statorTempC: Float? = null
        private var powerW: Int? = null
        private var errorCode: Int? = null
        private var targetSpeed: Int? = null
        private var lastDestAddr: Byte? = null
        private var lastMsgId: Byte? = null
        private var rawBytesList = mutableListOf<ByteArray>()

        // Track first receipt of each field
        private var rpmReceived = false
        private var voltageReceived = false
        private var currentReceived = false
        private var tempReceived = false
        private var errorReceived = false

        private var lastUpdate = 0L
        private val STALE_TIMEOUT_MS = 5000L

        fun update(frame: ByteArray) {
            val status = parseFrame(frame) ?: return
            lastUpdate = System.currentTimeMillis()

            if (status.destAddr != null) {
                lastDestAddr = status.destAddr
                lastMsgId = status.msgId
            }

            // Update ALL values (including zeros) on first receipt
            if (!rpmReceived || status.rpm != 0) {
                rpm = status.rpm
                rpmReceived = true
            }
            if (!voltageReceived || status.voltage != 0f) {
                voltage = status.voltage
                voltageReceived = true
            }
            if (!currentReceived || status.current != 0f) {
                current = status.current
                currentReceived = true
            }
            if (!tempReceived || status.tempC != 0f) {
                tempC = status.tempC
                pcbTempC = status.pcbTempC
                statorTempC = status.statorTempC
                tempReceived = true
            }
            if (status.powerW != 0) {
                powerW = status.powerW
            }
            if (!errorReceived || status.errorCode != 0) {
                errorCode = status.errorCode
                errorReceived = true
            }
            if (status.targetSpeed != null) {
                targetSpeed = status.targetSpeed
            }
            if (status.rawBytes.isNotEmpty()) rawBytesList.add(status.rawBytes)
        }

        fun build(): MotorStatus? {
            if (!rpmReceived && !voltageReceived && !currentReceived &&
                !tempReceived && !errorReceived && targetSpeed == null && lastDestAddr == null)
                return null

            val v = voltage ?: 0f
            val c = current ?: 0f
            val p = powerW ?: (v * c).toInt()
            val combinedRaw = rawBytesList.flatMap { it.toList() }.toByteArray()

            return MotorStatus(
                rpm = rpm ?: 0,
                voltage = v,
                current = c,
                powerW = p,
                tempC = tempC ?: 0f,
                pcbTempC = pcbTempC ?: 0f,
                statorTempC = statorTempC ?: 0f,
                errorCode = errorCode ?: 0,
                rawBytes = combinedRaw,
                targetSpeed = targetSpeed,
                destAddr = lastDestAddr,
                msgId = lastMsgId
            )
        }

        fun isStale(): Boolean = System.currentTimeMillis() - lastUpdate > STALE_TIMEOUT_MS

        fun clear() {
            rpm = null; voltage = null; current = null; powerW = null
            tempC = null; pcbTempC = null; statorTempC = null; errorCode = null; targetSpeed = null
            lastDestAddr = null; lastMsgId = null
            rpmReceived = false; voltageReceived = false
            currentReceived = false; tempReceived = false
            errorReceived = false
            rawBytesList.clear()
            lastUpdate = 0L
        }
    }

    // =========================
    // STREAM FRAME EXTRACTOR
    // =========================
    class StreamParser {
        private val buffer = mutableListOf<Byte>()

        fun push(data: ByteArray): List<ByteArray> {
            buffer.addAll(data.toList())
            val frames = extractFrames(buffer.toByteArray())

            if (frames.isNotEmpty()) {
                val lastFrame = frames.last()
                val lastFrameBytes = lastFrame.toList()
                var lastIdx = -1
                for (k in buffer.size - lastFrame.size downTo 0) {
                    if (buffer.subList(k, k + lastFrame.size) == lastFrameBytes) {
                        lastIdx = k + lastFrame.size
                        break
                    }
                }
                if (lastIdx > 0) {
                    repeat(lastIdx) { buffer.removeAt(0) }
                }
            } else if (buffer.size > 256) {
                buffer.removeAt(0)
            }
            return frames
        }
    }

    // =========================
    // MAIN PARSER (with logging for AC 38, 0x38, and 0x30 status)
    // =========================
    fun parseFrame(frame: ByteArray): MotorStatus? {
        if (frame.isEmpty() || frame[0] != HEADER) return null

        // -------- A7 TELEMETRY FRAMES (5 bytes, no CRC) --------
        if (frame.size == 5 && (frame[1].toInt() and 0xFF) == 0xA7) {
            val id = frame[2].toInt() and 0xFF
            val hi = frame[3]
            val lo = frame[4]
            val raw = u16(hi, lo)  // Big-endian: (hi << 8) | lo

            // Torqeedo uses INVERSE encoding for most values
            val inverse = 65535 - raw

            log("A7 frame id=0x${id.toString(16)} raw=$raw inverse=$inverse bytes=${frame.joinToString(" ") { "%02X".format(it) }}")

            return when (id) {
                0xC4 -> {
                    // RPM/Speed - inverse encoding, divide by 10
                    val rpm = inverse / 10
                    MotorStatus(rpm = rpm, rawBytes = frame)
                }
                0x60 -> {
                    // Battery Voltage - inverse encoding, divide by 59
                    val voltage = inverse / 59.0f
                    MotorStatus(voltage = voltage, rawBytes = frame)
                }
                0x40 -> {
                    // Bus Voltage (duplicate of 0x60 usually)
                    val voltage = inverse / 59.0f
                    MotorStatus(voltage = voltage, rawBytes = frame)
                }
                0xB1 -> {
                    // Motor Temperature - direct value, divide by 1000
                    val tempC = raw / 1000.0f
                    MotorStatus(tempC = tempC, statorTempC = tempC, rawBytes = frame)
                }
                0x62 -> {
                    // Current (Amps) - direct value, divide by 1000
                    val current = raw / 1000.0f
                    MotorStatus(current = current, rawBytes = frame)
                }
                0x20 -> {
                    // System Status/Error Code
                    // Inverse value may indicate error severity
                    val errorCode = if (inverse > 2000) 1 else 0
                    MotorStatus(errorCode = errorCode, rawBytes = frame)
                }
                else -> {
                    log("Unknown A7 frame id=0x${id.toString(16)}")
                    null
                }
            }
        }

        // -------- AC ... AD FRAMES (with footer) --------
        if (frame.last() == FOOTER && frame.size >= 5) {
            val addr = frame[1].toInt() and 0xFF
            val msgId = frame[2].toInt() and 0xFF

            // New 17-byte INFO frame (motor response)
            // Example: AC 00 00 00 06 FF FF 13 10 FF FB 01 47 01 40 1B AD
            // Mapping verified via ArduPilot:
            // Frame[3,4]   = RPM (s16)
            // Frame[5,6]   = Power (u16)
            // Frame[7,8]   = Voltage (u16) * 0.01
            // Frame[9,10]  = Current (s16) * 0.1
            // Frame[11,12] = PCB Temp (s16) * 0.1
            // Frame[13,14] = Stator Temp (s16) * 0.1
            if (frame.size == 17 && addr == MASTER_ADDR.toInt() and 0xFF && msgId == 0x00) {
                val dataToCrc = frame.sliceArray(1 until 15)
                val crcRx = frame[15]
                if (crcRx == crc8Maxim(dataToCrc)) {
                    val rpm = s16(frame[3], frame[4])
                    val power = u16(frame[5], frame[6])
                    val voltage = u16(frame[7], frame[8]) * 0.01f
                    val current = s16(frame[9], frame[10]) * 0.1f
                    val pcbTemp = s16(frame[11], frame[12]) * 0.1f
                    val statorTemp = s16(frame[13], frame[14]) * 0.1f

                    return MotorStatus(
                        rpm = rpm,
                        voltage = voltage,
                        current = current,
                        powerW = power,
                        tempC = statorTemp,
                        pcbTempC = pcbTemp,
                        statorTempC = statorTemp,
                        rawBytes = frame,
                        destAddr = addr.toByte(),
                        msgId = msgId.toByte()
                    )
                } else {
                    log("INFO frame CRC mismatch: expected ${"%02X".format(crc8Maxim(dataToCrc))} got ${"%02X".format(crcRx)}")
                }
            }

            if (msgId == MSGID_DRIVE.toInt() and 0xFF && frame.size >= 9) {
                val sHi = frame[5].toInt() and 0xFF
                val sLo = frame[6].toInt() and 0xFF
                val speed = ((sHi shl 8) or sLo).toShort().toInt()
                return MotorStatus(targetSpeed = speed, rawBytes = frame, destAddr = addr.toByte(), msgId = msgId.toByte())
            }

            if (msgId == MSGID_STATUS.toInt() and 0xFF && frame.size >= 5) {
                return MotorStatus(rawBytes = frame, destAddr = addr.toByte(), msgId = msgId.toByte())
            }

            if (frame.size >= 9 && (addr == 0x30 || addr == 0x38)) {
                log("0x${addr.toString(16)} frame: ${frame.joinToString(" ") { "%02X".format(it) }}")

                if (frame.size == 9) {
                    val dataToCrc = frame.sliceArray(1 until 7)
                    val crcRx = frame[7]
                    if (crcRx == crc8Maxim(dataToCrc)) {
                        log("  -> Valid response (CRC ok)")
                        return MotorStatus(rawBytes = frame, destAddr = addr.toByte(), msgId = msgId.toByte())
                    } else {
                        log("  -> CRC mismatch")
                    }
                }
            }
            return parseLegacyStatus(frame)?.copy(destAddr = addr.toByte(), msgId = msgId.toByte())
        }

        return null
    }

    private fun parseLegacyStatus(raw: ByteArray): MotorStatus? {
        if (raw.size < 5) return null
        val payload = raw.sliceArray(1 until raw.size - 1)
        if (payload.isEmpty() || payload.all { it == 0.toByte() }) return null

        fun u16le(lo: Byte, hi: Byte): Int = (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)
        fun s16le(lo: Byte, hi: Byte): Int = u16le(lo, hi).toShort().toInt()

        var rpm = 0; var voltage = 0f; var current = 0f; var powerW = 0; var tempC = 0f; var errorCode = 0

        if (payload.size >= 7) {
            rpm = s16le(payload[1], payload[2])
            voltage = u16le(payload[3], payload[4]) / 10.0f
            current = s16le(payload[5], payload[6]) / 10.0f
            powerW = (voltage * current).toInt()
            if (payload.size >= 8) tempC = (payload[7].toInt() and 0xFF).toFloat()
            if (payload.size >= 9) errorCode = payload[8].toInt() and 0xFF
            log("Legacy status parsed: RPM=$rpm V=$voltage A=$current T=$tempC Err=$errorCode")
            return MotorStatus(rpm = rpm, voltage = voltage, current = current, powerW = powerW, tempC = tempC, statorTempC = tempC, errorCode = errorCode, rawBytes = raw)
        }
        return null
    }

    private fun u16(hi: Byte, lo: Byte): Int {
        return ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
    }

    private fun s16(hi: Byte, lo: Byte): Int = u16(hi, lo).toShort().toInt()

    private fun u16le(lo: Byte, hi: Byte): Int {
        return (lo.toInt() and 0xFF) or ((hi.toInt() and 0xFF) shl 8)
    }

    private fun s16le(lo: Byte, hi: Byte): Int = u16le(lo, hi).toShort().toInt()

    /**
     * Parses the new steering sensor frame (7 bytes starting with 0xA8)
     */
    fun parseSteerSensor(frame: ByteArray): SteerSensorData? {
        if (frame.size < 7 || (frame[0].toInt() and 0xFF) != 0xA8) return null

        val sensorA = u16le(frame[1], frame[2])
        val sensorB = u16le(frame[3], frame[4])
        val vcc = u16le(frame[5], frame[6])

        return SteerSensorData(sensorA, sensorB, vcc, frame)
    }

    /**
     * Parses the QMC6308 sensor frame (8 bytes starting with 0xA5)
     * [0] Header 0xA5, [1] Seq, [2-3] X, [4-5] Y, [6-7] Z
     */
    fun parseQmc6308(frame: ByteArray): QMC6308Data? {
        if (frame.size < 8 || (frame[0].toInt() and 0xFF) != 0xA5) return null
        val x = s16le(frame[2], frame[3])
        val y = s16le(frame[4], frame[5])
        val z = s16le(frame[6], frame[7])
        return QMC6308Data(x, y, z, frame)
    }

    /**
     * Data model for the new steer position sensor (0xA8)
     */
    data class SteerSensorData(
        val sensorA: Int,
        val sensorB: Int,
        val vcc: Int,
        val rawBytes: ByteArray
    )

    /**
     * Data model for the QMC6308 sensor (0xA5)
     */
    data class QMC6308Data(
        val x: Int,
        val y: Int,
        val z: Int,
        val rawBytes: ByteArray
    )
}
