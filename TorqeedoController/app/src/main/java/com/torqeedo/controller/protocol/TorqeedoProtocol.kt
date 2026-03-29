package com.torqeedo.controller.protocol

import kotlin.math.abs

/**
 * TQ Bus protocol — Option B implementation.
 * Updated to match the user's Python test snippet and observed BLE logs.
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
        val rpm: Int? = null,
        val powerW: Int? = null,
        val tempC: Int? = null,
        val errorCode: Int? = null,
        val rawBytes: ByteArray? = null
    ) {
        val hasError get() = (errorCode ?: 0) != 0
    }

    /**
     * More flexible parser for various TQ Bus frames seen in the logs.
     */
    fun parseStatus(raw: ByteArray): MotorStatus? {
        if (raw.isEmpty() || raw[0] != HEADER) return null
        
        // Un-escape the payload (excluding HEADER and optional FOOTER)
        val hasFooter = raw.last() == FOOTER
        val endIdx = if (hasFooter) raw.size - 1 else raw.size
        
        val payload = mutableListOf<Byte>()
        var i = 1
        while (i < endIdx) {
            if (raw[i] == ESCAPE && i + 1 < endIdx) {
                i++
                payload.add((raw[i].toInt() xor ESC_XOR).toByte())
            } else {
                payload.add(raw[i])
            }
            i++
        }
        
        if (payload.size < 2) return null
        
        val addr = payload[0].toInt() and 0xFF
        val id   = payload[1].toInt() and 0xFF
        
        // 1. Handle the standard 0x30 / 0x81 status if it appears
        if (addr == 0x30 && id == 0x81 && payload.size >= 9) {
             val crcRx   = payload.last()
             val crcCalc = crc8Maxim(payload.toByteArray(), payload.size - 1)
             if (crcRx == crcCalc) {
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
        }

        // 2. Handle the 0xA7 and other messages seen in user logs
        if (addr == 0xA7 || addr == 0x30 || addr == 0xC5) {
            return when (id) {
                0xC4, 0xE0, 0x10, 0x60 -> { // IDs often seen with 2-byte data trailing
                    if (payload.size >= 5) {
                        // Pattern in logs suggests Little Endian signed 16-bit
                        fun s16le(lo: Byte, hi: Byte): Int =
                            ((hi.toInt() and 0xFF) shl 8) or (lo.toInt() and 0xFF)
                        
                        val value = s16le(payload[payload.size - 2], payload[payload.size - 1])
                            .let { if (it > 32767) it - 65536 else it }
                        
                        // Heuristic: if ID is C4, it's likely RPM
                        if (id == 0xC4) MotorStatus(rpm = value, rawBytes = raw)
                        else if (id == 0x10 || id == 0x60) MotorStatus(powerW = value, rawBytes = raw)
                        else null
                    } else null
                }
                0xB1 -> { // Likely Temperature
                    if (payload.size >= 3) {
                         MotorStatus(tempC = payload[2].toInt(), rawBytes = raw)
                    } else null
                }
                else -> null
            }
        }

        return null
    }

    fun errorDescription(code: Int?): String = when (code) {
        null -> "Waiting for data…"
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
