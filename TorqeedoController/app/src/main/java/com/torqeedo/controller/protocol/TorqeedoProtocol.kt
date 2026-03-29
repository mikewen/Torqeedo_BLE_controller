package com.torqeedo.controller.protocol

import kotlin.math.abs

/**
 * TQ Bus protocol — Option B implementation.
 * Updated to match the user's Python test snippet with dynamic power scaling.
 */
object TorqeedoProtocol {

    const val HEADER: Byte     = 0xAC.toByte()
    const val FOOTER: Byte     = 0xAD.toByte()
    private const val ESCAPE: Byte    = 0xAE.toByte()
    private const val ESC_XOR         = 0x20

    const val MOTOR_ADDR: Byte = 0x30.toByte()
    const val MSGID_DRIVE: Byte = 0x82.toByte()
    const val MSGID_STATUS: Byte = 0x81.toByte()

    // Aligning with Python snippet: flags=1 (enable)
    private const val DRIVE_FLAGS: Byte  = 0x01.toByte()

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

    private fun MutableList<Byte>.stuff(byte: Byte) {
        if (byte == HEADER || byte == FOOTER || byte == ESCAPE) {
            add(ESCAPE)
            add((byte.toInt() xor ESC_XOR).toByte())
        } else {
            add(byte)
        }
    }

    /**
     * @param speed  -1000 (full reverse) … 0 (stop) … +1000 (full forward)
     */
    fun buildDrive(speed: Int): ByteArray {
        val s = speed.coerceIn(-1000, 1000)
        val sHi = ((s shr 8) and 0xFF).toByte()
        val sLo = ( s         and 0xFF).toByte()

        // Convert speed (-1000..1000) to power percentage (0..100)
        val powerPct = (abs(s) / 10).toByte()

        // Order from Python snippet: ADDR, ID, FLAGS, POWER (%), SPEED_HI, SPEED_LO
        val raw = byteArrayOf(MOTOR_ADDR, MSGID_DRIVE, DRIVE_FLAGS, powerPct, sHi, sLo)
        val crc = crc8Maxim(raw)

        return buildList<Byte> {
            add(HEADER)
            raw.forEach { stuff(it) }
            stuff(crc)
            add(FOOTER)
        }.toByteArray()
    }

    data class MotorStatus(
        val rpm: Int,
        val powerW: Int,
        val tempC: Int,
        val errorCode: Int,
        val rawBytes: ByteArray
    ) {
        val hasError get() = errorCode != 0
    }

    /**
     * Un-escape and parse a STATUS frame.
     */
    fun parseStatus(raw: ByteArray): MotorStatus? {
        if (raw.size < 6) return null
        if (raw.first() != HEADER) return null
        if (raw.last()  != FOOTER) return null

        val payload = mutableListOf<Byte>()
        var i = 1
        while (i < raw.size - 1) {
            if (raw[i] == ESCAPE) {
                i++
                if (i >= raw.size - 1) return null
                payload.add((raw[i].toInt() xor ESC_XOR).toByte())
            } else {
                payload.add(raw[i])
            }
            i++
        }

        // Expected Status Payload: ADDR(0x30), ID(0x81), RPM_H, RPM_L, PWR_H, PWR_L, TEMP, ERR, CRC
        if (payload.size < 9) return null
        val crcRx   = payload.last()
        val crcCalc = crc8Maxim(payload.toByteArray(), payload.size - 1)
        if (crcRx != crcCalc) return null

        fun s16(hi: Byte, lo: Byte): Int =
            ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)

        return MotorStatus(
            rpm       = s16(payload[2], payload[3]).let { if (it > 32767) it - 65536 else it },
            powerW    = s16(payload[4], payload[5]).let { if (it > 32767) it - 65536 else it },
            tempC     = payload[6].toInt(),
            errorCode = payload[7].toInt() and 0xFF,
            rawBytes  = raw
        )
    }

    fun errorDescription(code: Int): String = when (code) {
        0x00 -> "OK"
        0x01 -> "Overcurrent"
        0x02 -> "Overvoltage"
        0x03 -> "Undervoltage"
        0x04 -> "Overtemperature"
        0x05 -> "Stall"
        0x06 -> "Comm Timeout"
        else -> "Fault 0x${code.toString(16).uppercase()}"
    }
}
