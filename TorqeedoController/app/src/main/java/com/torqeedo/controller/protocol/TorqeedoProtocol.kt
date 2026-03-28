package com.torqeedo.controller.protocol

/**
 * TQ Bus protocol — Option B implementation.
 * Android builds the complete framed packet; AC6328 is a pure UART bridge.
 *
 * Packet layout (before byte-stuffing):
 *   HEADER(0xAC)  ADDR(0x30)  MSGID  [payload…]  CRC8  FOOTER(0xAD)
 *
 * Byte-stuffing: any payload byte == 0xAC | 0xAD | 0xAE  →  emit 0xAE, byte XOR 0x20
 * CRC-8/Maxim: poly=0x8C, init=0x00, refin=true, refout=true
 * Covers: ADDR + MSGID + all payload bytes (raw, before escaping)
 */
object TorqeedoProtocol {

    const val HEADER: Byte     = 0xAC.toByte()
    const val FOOTER: Byte     = 0xAD.toByte()
    private const val ESCAPE: Byte    = 0xAE.toByte()
    private const val ESC_XOR         = 0x20

    const val MOTOR_ADDR: Byte = 0x30.toByte()
    const val MSGID_DRIVE: Byte = 0x82.toByte()
    const val MSGID_STATUS: Byte = 0x81.toByte()

    // DRIVE flags: bit0 = tiller pin present, bit2 = speed field valid
    private const val DRIVE_FLAGS: Byte  = 0x05.toByte()
    private const val DRIVE_STATUS: Byte = 0x00.toByte()

    // ── CRC-8 / Maxim (Dallas) ──────────────────────────────────────────────
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

    // ── Byte-stuffing helper ────────────────────────────────────────────────
    private fun MutableList<Byte>.stuff(byte: Byte) {
        if (byte == HEADER || byte == FOOTER || byte == ESCAPE) {
            add(ESCAPE)
            add((byte.toInt() xor ESC_XOR).toByte())
        } else {
            add(byte)
        }
    }

    // ── Build DRIVE packet ──────────────────────────────────────────────────
    /**
     * @param speed  -1000 (full reverse) … 0 (stop) … +1000 (full forward)
     * @return       framed, escaped byte array ready to write to BLE ae10
     */
    fun buildDrive(speed: Int): ByteArray {
        val s = speed.coerceIn(-1000, 1000)
        val sHi = ((s shr 8) and 0xFF).toByte()
        val sLo = ( s         and 0xFF).toByte()

        // Raw bytes that CRC covers
        val raw = byteArrayOf(MOTOR_ADDR, MSGID_DRIVE, DRIVE_FLAGS, DRIVE_STATUS, sHi, sLo)
        val crc = crc8Maxim(raw)

        return buildList<Byte> {
            add(HEADER)
            raw.forEach { stuff(it) }
            stuff(crc)
            add(FOOTER)
        }.toByteArray()
    }

    // ── Parse STATUS reply ──────────────────────────────────────────────────
    data class MotorStatus(
        val rpm: Int,        // motor RPM (signed)
        val powerW: Int,     // shaft power watts (signed)
        val tempC: Int,      // motor temp °C
        val errorCode: Int,  // 0 = no fault
        val rawBytes: ByteArray  // full raw frame for logging
    ) {
        val hasError get() = errorCode != 0
    }

    /**
     * Un-escape and parse a STATUS frame received from BLE notify (ae02_01).
     * Returns null if frame is malformed or CRC fails.
     */
    fun parseStatus(raw: ByteArray): MotorStatus? {
        if (raw.size < 6) return null
        if (raw.first() != HEADER) return null
        if (raw.last()  != FOOTER) return null

        // Un-escape payload between header and footer
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

        // payload: [0]=addr [1]=msgid [2..N-2]=data [N-1]=CRC
        if (payload.size < 8) return null
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

    // ── Human-readable error description ───────────────────────────────────
    fun errorDescription(code: Int): String = when (code) {
        0x00 -> "OK"
        0x01 -> "Overcurrent"
        0x02 -> "Overvoltage"
        0x03 -> "Undervoltage"
        0x04 -> "Overtemperature"
        0x05 -> "Stall"
        0x06 -> "Communication timeout"
        else -> "Unknown fault (0x${code.toString(16).uppercase()})"
    }
}
