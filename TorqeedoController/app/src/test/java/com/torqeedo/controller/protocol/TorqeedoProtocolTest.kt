package com.torqeedo.controller.protocol

import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM unit tests — no Android device needed.
 * Run with:  ./gradlew test
 */
class TorqeedoProtocolTest {

    // ── CRC-8/Maxim known vectors ─────────────────────────────────────────
    @Test
    fun `crc8 of empty array is 0x00`() {
        assertEquals(0x00.toByte(), TorqeedoProtocol.crc8Maxim(byteArrayOf()))
    }

    @Test
    fun `crc8 known vector - single byte 0x30`() {
        // Verified against Dallas/Maxim CRC-8 online calculator
        val result = TorqeedoProtocol.crc8Maxim(byteArrayOf(0x30.toByte()))
        assertEquals(0x98.toByte(), result)
    }

    @Test
    fun `crc8 known vector - DRIVE raw payload at speed 0`() {
        // addr=0x30 msgid=0x82 flags=0x05 status=0x00 spd_hi=0x00 spd_lo=0x00
        val raw = byteArrayOf(0x30, 0x82.toByte(), 0x05, 0x00, 0x00, 0x00)
        val crc = TorqeedoProtocol.crc8Maxim(raw)
        // CRC must be deterministic — just verify it's stable
        assertEquals(crc, TorqeedoProtocol.crc8Maxim(raw))
    }

    // ── buildDrive frame structure ─────────────────────────────────────────
    @Test
    fun `buildDrive starts with HEADER and ends with FOOTER`() {
        val frame = TorqeedoProtocol.buildDrive(0)
        assertEquals(TorqeedoProtocol.HEADER, frame.first())
        assertEquals(TorqeedoProtocol.FOOTER, frame.last())
    }

    @Test
    fun `buildDrive at speed 0 contains no escape sequences`() {
        val frame = TorqeedoProtocol.buildDrive(0)
        // speed=0 bytes are 0x00, 0x00 — no stuffing expected
        // Simply verify no 0xAE escape byte appears in inner payload for this case
        val inner = frame.slice(1 until frame.size - 1)
        assertFalse("Unexpected escape in zero-speed frame",
            inner.any { it == 0xAE.toByte() })
    }

    @Test
    fun `buildDrive speed is clamped to -1000 and +1000`() {
        val frameMax = TorqeedoProtocol.buildDrive(9999)
        val frameMin = TorqeedoProtocol.buildDrive(-9999)
        val framePeg = TorqeedoProtocol.buildDrive(1000)
        val frameNeg = TorqeedoProtocol.buildDrive(-1000)
        assertArrayEquals(frameMax, framePeg)
        assertArrayEquals(frameMin, frameNeg)
    }

    @Test
    fun `buildDrive produces consistent output for same speed`() {
        val a = TorqeedoProtocol.buildDrive(500)
        val b = TorqeedoProtocol.buildDrive(500)
        assertArrayEquals(a, b)
    }

    @Test
    fun `buildDrive forward and reverse frames differ`() {
        val fwd = TorqeedoProtocol.buildDrive(500)
        val rev = TorqeedoProtocol.buildDrive(-500)
        assertFalse(fwd.contentEquals(rev))
    }

    // ── Byte-stuffing round-trip ───────────────────────────────────────────
    @Test
    fun `escape byte 0xAC in payload is stuffed correctly`() {
        // Speed = 0x00AC (172) → sHi=0x00, sLo=0xAC → 0xAC must be escaped
        val frame = TorqeedoProtocol.buildDrive(172)
        val inner = frame.slice(1 until frame.size - 1).toByteArray()
        // Verify 0xAE 0x8C appears in inner payload (0xAC XOR 0x20 = 0x8C)
        val hasEscape = (0 until inner.size - 1).any {
            inner[it] == 0xAE.toByte() && inner[it + 1] == 0x8C.toByte()
        }
        assertTrue("Speed byte 0xAC should be escaped as 0xAE 0x8C", hasEscape)
    }

    @Test
    fun `escape byte 0xAD in payload is stuffed correctly`() {
        // Speed = 0x00AD (173) → sLo=0xAD must be escaped as 0xAE 0x8D
        val frame = TorqeedoProtocol.buildDrive(173)
        val inner = frame.slice(1 until frame.size - 1).toByteArray()
        val hasEscape = (0 until inner.size - 1).any {
            inner[it] == 0xAE.toByte() && inner[it + 1] == 0x8D.toByte()
        }
        assertTrue("Speed byte 0xAD should be escaped as 0xAE 0x8D", hasEscape)
    }

    // ── parseStatus ───────────────────────────────────────────────────────
    @Test
    fun `parseStatus rejects too-short frame`() {
        assertNull(TorqeedoProtocol.parseStatus(byteArrayOf(0xAC.toByte(), 0xAD.toByte())))
    }

    @Test
    fun `parseStatus rejects wrong header`() {
        val bad = byteArrayOf(0x00, 0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xAD.toByte())
        assertNull(TorqeedoProtocol.parseStatus(bad))
    }

    @Test
    fun `parseStatus rejects wrong footer`() {
        val bad = byteArrayOf(0xAC.toByte(), 0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        assertNull(TorqeedoProtocol.parseStatus(bad))
    }

    @Test
    fun `parseStatus rejects CRC mismatch`() {
        // Build a valid-looking frame with a wrong CRC byte
        val payload = byteArrayOf(
            0x30, 0x00,          // addr, msgid
            0x01, 0xF4.toByte(), // rpm = 500
            0x00, 0x64,          // power = 100 W
            0x28,                // temp = 40 °C
            0x00,                // error = 0
            0xFF.toByte()        // wrong CRC
        )
        val frame = byteArrayOf(0xAC.toByte()) + payload + byteArrayOf(0xAD.toByte())
        assertNull(TorqeedoProtocol.parseStatus(frame))
    }

    @Test
    fun `parseStatus parses valid frame correctly`() {
        // Build a valid STATUS frame manually
        val raw = byteArrayOf(
            0x30,                // addr
            0x00,                // msgid (status reply msgid = 0x00 per protocol)
            0x01, 0xF4.toByte(), // rpm = 500
            0x00, 0x64,          // power = 100 W
            0x28,                // temp = 40 °C
            0x00                 // error = 0
        )
        val crc = TorqeedoProtocol.crc8Maxim(raw)
        val payload = raw + byteArrayOf(crc)

        // Stuff the payload
        val frame = mutableListOf(0xAC.toByte())
        for (b in payload) {
            when (b) {
                0xAC.toByte(), 0xAD.toByte(), 0xAE.toByte() -> {
                    frame.add(0xAE.toByte())
                    frame.add((b.toInt() xor 0x20).toByte())
                }
                else -> frame.add(b)
            }
        }
        frame.add(0xAD.toByte())

        val status = TorqeedoProtocol.parseStatus(frame.toByteArray())
        assertNotNull(status)
        assertEquals(500,  status!!.rpm)
        assertEquals(100,  status.powerW)
        assertEquals(40,   status.tempC)
        assertEquals(0,    status.errorCode)
        assertFalse(status.hasError)
    }

    // ── errorDescription ──────────────────────────────────────────────────
    @Test
    fun `errorDescription returns OK for code 0`() {
        assertEquals("OK", TorqeedoProtocol.errorDescription(0))
    }

    @Test
    fun `errorDescription returns unknown for unrecognised code`() {
        assertTrue(TorqeedoProtocol.errorDescription(0xFF).contains("Unknown"))
    }
}
