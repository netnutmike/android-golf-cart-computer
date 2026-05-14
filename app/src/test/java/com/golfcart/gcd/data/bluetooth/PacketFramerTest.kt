package com.golfcart.gcd.data.bluetooth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [PacketFramer] — the Meshtastic BLE packet framing utility.
 *
 * Tests cover:
 * - 4-byte big-endian length prefix framing
 * - Unframing (extracting payload from framed data)
 * - Frame/unframe round-trip
 * - MTU-based packet splitting
 * - Edge cases and error conditions
 */
class PacketFramerTest {

    // --- frame() tests ---

    @Test
    fun `frame prepends 4-byte big-endian length prefix`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03)
        val framed = PacketFramer.frame(payload)

        assertEquals(7, framed.size) // 4 prefix + 3 payload
        // Length = 3 in big-endian: 0x00 0x00 0x00 0x03
        assertEquals(0x00.toByte(), framed[0])
        assertEquals(0x00.toByte(), framed[1])
        assertEquals(0x00.toByte(), framed[2])
        assertEquals(0x03.toByte(), framed[3])
        // Payload follows
        assertEquals(0x01.toByte(), framed[4])
        assertEquals(0x02.toByte(), framed[5])
        assertEquals(0x03.toByte(), framed[6])
    }

    @Test
    fun `frame handles single byte payload`() {
        val payload = byteArrayOf(0xFF.toByte())
        val framed = PacketFramer.frame(payload)

        assertEquals(5, framed.size)
        // Length = 1
        assertEquals(0x00.toByte(), framed[0])
        assertEquals(0x00.toByte(), framed[1])
        assertEquals(0x00.toByte(), framed[2])
        assertEquals(0x01.toByte(), framed[3])
        assertEquals(0xFF.toByte(), framed[4])
    }

    @Test
    fun `frame handles large payload with multi-byte length`() {
        // 256 bytes → length prefix 0x00 0x00 0x01 0x00
        val payload = ByteArray(256) { it.toByte() }
        val framed = PacketFramer.frame(payload)

        assertEquals(260, framed.size)
        assertEquals(0x00.toByte(), framed[0])
        assertEquals(0x00.toByte(), framed[1])
        assertEquals(0x01.toByte(), framed[2])
        assertEquals(0x00.toByte(), framed[3])
    }

    @Test
    fun `frame rejects empty payload`() {
        assertThrows<IllegalArgumentException> {
            PacketFramer.frame(byteArrayOf())
        }
    }

    // --- unframe() tests ---

    @Test
    fun `unframe extracts payload from framed data`() {
        val framed = byteArrayOf(0x00, 0x00, 0x00, 0x03, 0x0A, 0x0B, 0x0C)
        val payload = PacketFramer.unframe(framed)

        assertArrayEquals(byteArrayOf(0x0A, 0x0B, 0x0C), payload)
    }

    @Test
    fun `unframe handles single byte payload`() {
        val framed = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x42)
        val payload = PacketFramer.unframe(framed)

        assertArrayEquals(byteArrayOf(0x42), payload)
    }

    @Test
    fun `unframe rejects data shorter than length prefix`() {
        assertThrows<IllegalArgumentException> {
            PacketFramer.unframe(byteArrayOf(0x00, 0x00, 0x00))
        }
    }

    @Test
    fun `unframe rejects data with insufficient payload`() {
        // Length says 5 bytes but only 2 available
        val framed = byteArrayOf(0x00, 0x00, 0x00, 0x05, 0x01, 0x02)
        assertThrows<IllegalArgumentException> {
            PacketFramer.unframe(framed)
        }
    }

    // --- Round-trip tests ---

    @Test
    fun `frame then unframe produces original payload`() {
        val original = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50)
        val roundTripped = PacketFramer.unframe(PacketFramer.frame(original))

        assertArrayEquals(original, roundTripped)
    }

    @Test
    fun `frame then unframe works for typical protobuf message size`() {
        // Simulate a typical protobuf message (50 bytes)
        val original = ByteArray(50) { (it * 3).toByte() }
        val roundTripped = PacketFramer.unframe(PacketFramer.frame(original))

        assertArrayEquals(original, roundTripped)
    }

    // --- splitForMtu() tests ---

    @Test
    fun `splitForMtu returns single chunk when packet fits in MTU`() {
        val framed = ByteArray(15) { it.toByte() }
        val chunks = PacketFramer.splitForMtu(framed, mtuPayloadSize = 20)

        assertEquals(1, chunks.size)
        assertArrayEquals(framed, chunks[0])
    }

    @Test
    fun `splitForMtu splits packet exceeding MTU into correct chunks`() {
        val framed = ByteArray(50) { it.toByte() }
        val chunks = PacketFramer.splitForMtu(framed, mtuPayloadSize = 20)

        assertEquals(3, chunks.size)
        assertEquals(20, chunks[0].size)
        assertEquals(20, chunks[1].size)
        assertEquals(10, chunks[2].size)
    }

    @Test
    fun `splitForMtu chunks concatenate to original`() {
        val framed = ByteArray(55) { it.toByte() }
        val chunks = PacketFramer.splitForMtu(framed, mtuPayloadSize = 20)

        val reassembled = chunks.fold(byteArrayOf()) { acc, chunk -> acc + chunk }
        assertArrayEquals(framed, reassembled)
    }

    @Test
    fun `splitForMtu with default MTU uses 20 bytes`() {
        val framed = ByteArray(45) { it.toByte() }
        val chunks = PacketFramer.splitForMtu(framed)

        assertEquals(3, chunks.size)
        assertEquals(20, chunks[0].size)
        assertEquals(20, chunks[1].size)
        assertEquals(5, chunks[2].size)
    }

    @Test
    fun `splitForMtu handles exact MTU boundary`() {
        val framed = ByteArray(40) { it.toByte() }
        val chunks = PacketFramer.splitForMtu(framed, mtuPayloadSize = 20)

        assertEquals(2, chunks.size)
        assertEquals(20, chunks[0].size)
        assertEquals(20, chunks[1].size)
    }

    @Test
    fun `splitForMtu returns empty list for empty input`() {
        val chunks = PacketFramer.splitForMtu(byteArrayOf(), mtuPayloadSize = 20)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `splitForMtu rejects zero MTU payload size`() {
        assertThrows<IllegalArgumentException> {
            PacketFramer.splitForMtu(byteArrayOf(0x01), mtuPayloadSize = 0)
        }
    }

    @Test
    fun `splitForMtu with MTU of 1 splits into individual bytes`() {
        val framed = byteArrayOf(0x0A, 0x0B, 0x0C)
        val chunks = PacketFramer.splitForMtu(framed, mtuPayloadSize = 1)

        assertEquals(3, chunks.size)
        assertArrayEquals(byteArrayOf(0x0A), chunks[0])
        assertArrayEquals(byteArrayOf(0x0B), chunks[1])
        assertArrayEquals(byteArrayOf(0x0C), chunks[2])
    }

    @Test
    fun `splitForMtu with large MTU returns single chunk`() {
        val framed = ByteArray(100) { it.toByte() }
        val chunks = PacketFramer.splitForMtu(framed, mtuPayloadSize = 509)

        assertEquals(1, chunks.size)
        assertArrayEquals(framed, chunks[0])
    }

    // --- Integration: frame + split round-trip ---

    @Test
    fun `frame then split then reassemble then unframe produces original`() {
        val original = ByteArray(100) { (it * 7).toByte() }

        // Frame
        val framed = PacketFramer.frame(original)

        // Split for 20-byte MTU
        val chunks = PacketFramer.splitForMtu(framed, mtuPayloadSize = 20)

        // Reassemble
        val reassembled = chunks.fold(byteArrayOf()) { acc, chunk -> acc + chunk }

        // Unframe
        val recovered = PacketFramer.unframe(reassembled)

        assertArrayEquals(original, recovered)
    }
}
