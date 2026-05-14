package com.golfcart.gcd.data.bluetooth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Unit tests for GciProtocol pairing command construction and MAC address parsing.
 *
 * Validates: Requirements 8.9, 8.10
 */
class GciProtocolPairingTest {

    @Test
    fun `buildPairingCommand creates valid COMMAND message with correct payload`() {
        val macAddress = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        val timestamp = 1700000000L
        val sequenceNumber = 42

        val result = GciProtocol.buildPairingCommand(macAddress, timestamp, sequenceNumber)

        // Parse the result back to verify structure
        val message = GciProtocol.parseMessage(result)
        assertNotNull(message)
        assertEquals(GciMessageType.COMMAND, message!!.type)
        assertEquals(timestamp, message.timestamp)
        assertEquals(sequenceNumber, message.sequenceNumber)
        assertEquals(GciProtocol.PAIRING_PAYLOAD_SIZE, message.payload.size)

        // Verify payload contents
        val payloadBuffer = ByteBuffer.wrap(message.payload).order(ByteOrder.LITTLE_ENDIAN)
        val cmdNumber = payloadBuffer.getInt()
        assertEquals(GciProtocol.GCI_CMD_ADD_PEER, cmdNumber)

        val extractedMac = ByteArray(6)
        payloadBuffer.get(extractedMac)
        assertArrayEquals(macAddress, extractedMac)
    }

    @Test
    fun `buildPairingCommand rejects MAC address with wrong size`() {
        val tooShort = byteArrayOf(0x01, 0x02, 0x03)
        assertThrows<IllegalArgumentException> {
            GciProtocol.buildPairingCommand(tooShort, 0L, 0)
        }

        val tooLong = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        assertThrows<IllegalArgumentException> {
            GciProtocol.buildPairingCommand(tooLong, 0L, 0)
        }
    }

    @Test
    fun `buildPairingCommand total message size is header plus payload`() {
        val macAddress = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val result = GciProtocol.buildPairingCommand(macAddress, 100L, 1)

        // Total size = HEADER_SIZE (9) + PAIRING_PAYLOAD_SIZE (10) = 19 bytes
        assertEquals(GciProtocol.HEADER_SIZE + GciProtocol.PAIRING_PAYLOAD_SIZE, result.size)
    }

    @Test
    fun `parseMacAddress parses valid colon-separated hex string`() {
        val macString = "AA:BB:CC:DD:EE:FF"
        val result = GciProtocol.parseMacAddress(macString)

        assertEquals(6, result.size)
        assertEquals(0xAA.toByte(), result[0])
        assertEquals(0xBB.toByte(), result[1])
        assertEquals(0xCC.toByte(), result[2])
        assertEquals(0xDD.toByte(), result[3])
        assertEquals(0xEE.toByte(), result[4])
        assertEquals(0xFF.toByte(), result[5])
    }

    @Test
    fun `parseMacAddress handles lowercase hex`() {
        val macString = "aa:bb:cc:dd:ee:ff"
        val result = GciProtocol.parseMacAddress(macString)

        assertEquals(6, result.size)
        assertEquals(0xAA.toByte(), result[0])
        assertEquals(0xFF.toByte(), result[5])
    }

    @Test
    fun `parseMacAddress rejects invalid format`() {
        assertThrows<IllegalArgumentException> {
            GciProtocol.parseMacAddress("AA:BB:CC")
        }

        assertThrows<IllegalArgumentException> {
            GciProtocol.parseMacAddress("AA:BB:CC:DD:EE:FF:00")
        }

        assertThrows<IllegalArgumentException> {
            GciProtocol.parseMacAddress("")
        }
    }

    @Test
    fun `parseMacAddress rejects non-hex values`() {
        assertThrows<NumberFormatException> {
            GciProtocol.parseMacAddress("GG:HH:II:JJ:KK:LL")
        }
    }

    @Test
    fun `buildPairingCommand with all-zero MAC address`() {
        val macAddress = ByteArray(6) // all zeros
        val result = GciProtocol.buildPairingCommand(macAddress, 0L, 0)

        val message = GciProtocol.parseMessage(result)
        assertNotNull(message)
        assertEquals(GciMessageType.COMMAND, message!!.type)

        val payloadBuffer = ByteBuffer.wrap(message.payload).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(GciProtocol.GCI_CMD_ADD_PEER, payloadBuffer.getInt())

        val extractedMac = ByteArray(6)
        payloadBuffer.get(extractedMac)
        assertArrayEquals(macAddress, extractedMac)
    }

    @Test
    fun `buildPairingCommand round-trip with parseMacAddress`() {
        val macString = "12:34:56:78:9A:BC"
        val macBytes = GciProtocol.parseMacAddress(macString)
        val timestamp = 1700000000L
        val seq = 100

        val commandBytes = GciProtocol.buildPairingCommand(macBytes, timestamp, seq)
        val message = GciProtocol.parseMessage(commandBytes)

        assertNotNull(message)
        assertEquals(GciMessageType.COMMAND, message!!.type)
        assertEquals(timestamp, message.timestamp)
        assertEquals(seq, message.sequenceNumber)

        // Extract MAC from payload
        val payloadBuffer = ByteBuffer.wrap(message.payload).order(ByteOrder.LITTLE_ENDIAN)
        payloadBuffer.getInt() // skip cmdNumber
        val extractedMac = ByteArray(6)
        payloadBuffer.get(extractedMac)
        assertArrayEquals(macBytes, extractedMac)
    }
}
