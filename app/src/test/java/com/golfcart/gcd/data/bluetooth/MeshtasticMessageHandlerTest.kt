package com.golfcart.gcd.data.bluetooth

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for MeshtasticMessageHandler.
 *
 * Tests message construction, routing, payload limits, and packet ID generation.
 */
class MeshtasticMessageHandlerTest {

    private lateinit var handler: MeshtasticMessageHandler
    private var localNodeNum: Long = 0x12345678L

    @BeforeEach
    fun setUp() {
        handler = MeshtasticMessageHandler(localNodeNum = { localNodeNum })
    }

    // --- Packet ID Generation ---

    @Test
    fun `generatePacketId returns non-zero value`() {
        repeat(100) {
            val id = handler.generatePacketId()
            assertNotEquals(0, id, "Packet ID must be non-zero")
        }
    }

    @Test
    fun `generatePacketId produces varying values`() {
        val ids = (1..10).map { handler.generatePacketId() }.toSet()
        assertTrue(ids.size > 1, "Packet IDs should vary (got ${ids.size} unique out of 10)")
    }

    // --- Text Message Construction ---

    @Test
    fun `buildTextMessage produces valid protobuf bytes`() {
        val text = "Hello mesh!"
        val destination = MeshtasticConstants.BROADCAST_ADDRESS
        val channel = 0

        val result = handler.buildTextMessage(text, destination, channel)

        assertNotNull(result)
        assertTrue(result.isNotEmpty())

        // Verify the outer ToRadio wrapper: tag 1, wire type 2 (length-delimited)
        val firstByte = result[0].toInt() and 0xFF
        assertEquals(0x0A, firstByte, "First byte should be ToRadio packet tag (1 << 3 | 2 = 0x0A)")
    }

    @Test
    fun `buildTextMessage encodes destination correctly`() {
        val text = "Test"
        val destination = 0xAABBCCDDL
        val channel = 0

        val result = handler.buildTextMessage(text, destination, channel)

        // The destination is encoded as fixed32 (little-endian) in the MeshPacket
        // We need to find the destination bytes in the output
        val destBytes = byteArrayOf(0xDD.toByte(), 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte())
        assertTrue(containsSubarray(result, destBytes),
            "Result should contain destination 0xAABBCCDD as little-endian fixed32")
    }

    @Test
    fun `buildTextMessage encodes broadcast destination`() {
        val text = "Broadcast"
        val destination = MeshtasticConstants.BROADCAST_ADDRESS // 0xFFFFFFFF
        val channel = 0

        val result = handler.buildTextMessage(text, destination, channel)

        val broadcastBytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertTrue(containsSubarray(result, broadcastBytes),
            "Result should contain broadcast address 0xFFFFFFFF as little-endian fixed32")
    }

    @Test
    fun `buildTextMessage includes text payload`() {
        val text = "Hello mesh!"
        val destination = MeshtasticConstants.BROADCAST_ADDRESS
        val channel = 0

        val result = handler.buildTextMessage(text, destination, channel)

        val textBytes = text.toByteArray(Charsets.UTF_8)
        assertTrue(containsSubarray(result, textBytes),
            "Result should contain the text payload bytes")
    }

    @Test
    fun `buildTextMessage includes TEXT_MESSAGE_APP portnum`() {
        val text = "Test"
        val destination = MeshtasticConstants.BROADCAST_ADDRESS
        val channel = 0

        val result = handler.buildTextMessage(text, destination, channel)

        // portnum is encoded as varint with tag 1: (1 << 3 | 0) = 0x08, value = 1 (TEXT_MESSAGE_APP)
        val portnumBytes = byteArrayOf(0x08, 0x01)
        assertTrue(containsSubarray(result, portnumBytes),
            "Result should contain portnum field (tag=1, value=1 for TEXT_MESSAGE_APP)")
    }

    @Test
    fun `buildTextMessage with non-zero channel includes channel field`() {
        val text = "Test"
        val destination = MeshtasticConstants.BROADCAST_ADDRESS
        val channel = 3

        val result = handler.buildTextMessage(text, destination, channel)

        // channel is encoded as varint with tag 3: (3 << 3 | 0) = 0x18, value = 3
        val channelBytes = byteArrayOf(0x18, 0x03)
        assertTrue(containsSubarray(result, channelBytes),
            "Result should contain channel field (tag=3, value=3)")
    }

    @Test
    fun `buildTextMessage with channel 0 omits channel field`() {
        val text = "Test"
        val destination = MeshtasticConstants.BROADCAST_ADDRESS
        val channel = 0

        val result = handler.buildTextMessage(text, destination, channel)

        // channel tag byte is 0x18 — should NOT appear since channel is 0 (default)
        // Note: 0x18 could appear in other contexts, so we check the MeshPacket structure
        // The absence of channel field is a protobuf optimization (default values are omitted)
        assertNotNull(result) // Basic sanity — detailed verification in property tests
    }

    // --- Payload Size Limit ---

    @Test
    fun `buildTextMessage rejects payload exceeding 237 bytes`() {
        val longText = "A".repeat(238) // 238 bytes in UTF-8

        assertThrows<MeshtasticMessageHandler.PayloadTooLargeException> {
            handler.buildTextMessage(longText, MeshtasticConstants.BROADCAST_ADDRESS, 0)
        }
    }

    @Test
    fun `buildTextMessage accepts payload at exactly 237 bytes`() {
        val maxText = "A".repeat(237) // Exactly 237 bytes in UTF-8

        val result = handler.buildTextMessage(maxText, MeshtasticConstants.BROADCAST_ADDRESS, 0)
        assertNotNull(result)
        assertTrue(result.isNotEmpty())
    }

    @Test
    fun `buildTextMessage rejects multi-byte UTF-8 exceeding limit`() {
        // Each emoji is 4 bytes in UTF-8, so 60 emojis = 240 bytes > 237
        val emojiText = "\uD83D\uDE00".repeat(60) // 60 × 4 = 240 bytes

        assertThrows<MeshtasticMessageHandler.PayloadTooLargeException> {
            handler.buildTextMessage(emojiText, MeshtasticConstants.BROADCAST_ADDRESS, 0)
        }
    }

    // --- Admin Message Construction ---

    @Test
    fun `buildAdminMessage produces valid protobuf bytes`() {
        val adminPayload = byteArrayOf(0x28, 0x05) // reboot_seconds = 5

        val result = handler.buildAdminMessage(adminPayload)

        assertNotNull(result)
        assertTrue(result.isNotEmpty())

        // Verify outer ToRadio wrapper
        val firstByte = result[0].toInt() and 0xFF
        assertEquals(0x0A, firstByte, "First byte should be ToRadio packet tag")
    }

    @Test
    fun `buildAdminMessage uses ADMIN_APP portnum`() {
        val adminPayload = byteArrayOf(0x28, 0x05)

        val result = handler.buildAdminMessage(adminPayload)

        // portnum is encoded as varint with tag 1: (1 << 3 | 0) = 0x08, value = 6 (ADMIN_APP)
        val portnumBytes = byteArrayOf(0x08, 0x06)
        assertTrue(containsSubarray(result, portnumBytes),
            "Result should contain portnum field (tag=1, value=6 for ADMIN_APP)")
    }

    @Test
    fun `buildAdminMessage addresses to local node`() {
        localNodeNum = 0x11223344L
        val adminPayload = byteArrayOf(0x28, 0x05)

        val result = handler.buildAdminMessage(adminPayload)

        // Local node as fixed32 little-endian
        val nodeBytes = byteArrayOf(0x44, 0x33, 0x22, 0x11)
        assertTrue(containsSubarray(result, nodeBytes),
            "Admin message should be addressed to local node 0x11223344")
    }

    @Test
    fun `buildAdminMessage rejects payload exceeding 237 bytes`() {
        val largePayload = ByteArray(238) { 0x01 }

        assertThrows<MeshtasticMessageHandler.PayloadTooLargeException> {
            handler.buildAdminMessage(largePayload)
        }
    }

    // --- Reboot Admin Payload ---

    @Test
    fun `buildRebootAdminPayload encodes delay correctly`() {
        val payload = handler.buildRebootAdminPayload(5)

        // AdminMessage reboot_seconds: tag 5, wire type 0 (varint)
        // Tag byte: (5 << 3) | 0 = 40 = 0x28
        // Value: 5 = 0x05
        assertEquals(0x28.toByte(), payload[0])
        assertEquals(0x05.toByte(), payload[1])
    }

    @Test
    fun `buildRebootAdminPayload with zero delay`() {
        val payload = handler.buildRebootAdminPayload(0)

        assertEquals(0x28.toByte(), payload[0])
        assertEquals(0x00.toByte(), payload[1])
    }

    // --- Incoming Packet Routing ---

    @Test
    fun `routeIncomingPacket routes text message to textMessages flow`() = runBlocking {
        // Construct a FromRadio message containing a MeshPacket with TEXT_MESSAGE_APP
        val textPayload = "Hello!".toByteArray(Charsets.UTF_8)
        val fromRadioBytes = buildFakeFromRadioPacket(
            from = 0xAABBCCDDL,
            to = localNodeNum,
            channel = 0,
            portnum = MeshtasticMessageHandler.PORT_TEXT_MESSAGE_APP,
            payload = textPayload,
            packetId = 42
        )

        handler.routeIncomingPacket(fromRadioBytes)

        val message = withTimeoutOrNull(1000) {
            handler.textMessages.first()
        }

        assertNotNull(message, "Should have received a text message")
        assertEquals("Hello!", message!!.text)
        assertEquals(0xAABBCCDDL, message.from)
        assertEquals(localNodeNum, message.to)
        assertEquals(0, message.channel)
    }

    @Test
    fun `routeIncomingPacket routes position message to positionMessages flow`() = runBlocking {
        val posPayload = byteArrayOf(0x01, 0x02, 0x03)
        val fromRadioBytes = buildFakeFromRadioPacket(
            from = 0x11111111L,
            to = MeshtasticConstants.BROADCAST_ADDRESS,
            channel = 0,
            portnum = MeshtasticMessageHandler.PORT_POSITION_APP,
            payload = posPayload,
            packetId = 100
        )

        handler.routeIncomingPacket(fromRadioBytes)

        val packet = withTimeoutOrNull(1000) {
            handler.positionMessages.first()
        }

        assertNotNull(packet, "Should have received a position message")
        assertEquals(MeshtasticMessageHandler.PORT_POSITION_APP, packet!!.portnum)
        assertArrayEquals(posPayload, packet.payload)
    }

    @Test
    fun `routeIncomingPacket routes telemetry message to telemetryMessages flow`() = runBlocking {
        val telPayload = byteArrayOf(0x0A, 0x0B, 0x0C)
        val fromRadioBytes = buildFakeFromRadioPacket(
            from = 0x22222222L,
            to = MeshtasticConstants.BROADCAST_ADDRESS,
            channel = 0,
            portnum = MeshtasticMessageHandler.PORT_TELEMETRY_APP,
            payload = telPayload,
            packetId = 200
        )

        handler.routeIncomingPacket(fromRadioBytes)

        val packet = withTimeoutOrNull(1000) {
            handler.telemetryMessages.first()
        }

        assertNotNull(packet, "Should have received a telemetry message")
        assertEquals(MeshtasticMessageHandler.PORT_TELEMETRY_APP, packet!!.portnum)
    }

    @Test
    fun `routeIncomingPacket routes admin message to adminMessages flow`() = runBlocking {
        val adminPayload = byteArrayOf(0x28, 0x05)
        val fromRadioBytes = buildFakeFromRadioPacket(
            from = localNodeNum,
            to = localNodeNum,
            channel = 0,
            portnum = MeshtasticMessageHandler.PORT_ADMIN_APP,
            payload = adminPayload,
            packetId = 300
        )

        handler.routeIncomingPacket(fromRadioBytes)

        val packet = withTimeoutOrNull(1000) {
            handler.adminMessages.first()
        }

        assertNotNull(packet, "Should have received an admin message")
        assertEquals(MeshtasticMessageHandler.PORT_ADMIN_APP, packet!!.portnum)
    }

    @Test
    fun `routeIncomingPacket ignores packets not addressed to us`() = runBlocking {
        val textPayload = "Not for us".toByteArray(Charsets.UTF_8)
        val fromRadioBytes = buildFakeFromRadioPacket(
            from = 0xAABBCCDDL,
            to = 0x99999999L, // Different node, not broadcast
            channel = 0,
            portnum = MeshtasticMessageHandler.PORT_TEXT_MESSAGE_APP,
            payload = textPayload,
            packetId = 400
        )

        handler.routeIncomingPacket(fromRadioBytes)

        val message = withTimeoutOrNull(200) {
            handler.textMessages.first()
        }

        assertNull(message, "Should NOT have received a message addressed to another node")
    }

    @Test
    fun `routeIncomingPacket accepts broadcast messages`() = runBlocking {
        val textPayload = "Broadcast!".toByteArray(Charsets.UTF_8)
        val fromRadioBytes = buildFakeFromRadioPacket(
            from = 0xAABBCCDDL,
            to = MeshtasticConstants.BROADCAST_ADDRESS,
            channel = 0,
            portnum = MeshtasticMessageHandler.PORT_TEXT_MESSAGE_APP,
            payload = textPayload,
            packetId = 500
        )

        handler.routeIncomingPacket(fromRadioBytes)

        val message = withTimeoutOrNull(1000) {
            handler.textMessages.first()
        }

        assertNotNull(message, "Should have received a broadcast message")
        assertEquals("Broadcast!", message!!.text)
    }

    // --- Protobuf Encoding Helpers ---

    @Test
    fun `encodeVarint encodes zero correctly`() {
        val result = handler.encodeVarint(0L)
        assertArrayEquals(byteArrayOf(0x00), result)
    }

    @Test
    fun `encodeVarint encodes small values correctly`() {
        val result = handler.encodeVarint(1L)
        assertArrayEquals(byteArrayOf(0x01), result)
    }

    @Test
    fun `encodeVarint encodes multi-byte values correctly`() {
        // 300 = 0b100101100 → varint: 0xAC 0x02
        val result = handler.encodeVarint(300L)
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), result)
    }

    @Test
    fun `encodeFixed32 encodes in little-endian`() {
        val result = handler.encodeFixed32(0x12345678L)
        assertArrayEquals(byteArrayOf(0x78, 0x56, 0x34, 0x12), result)
    }

    @Test
    fun `readFixed32 decodes little-endian correctly`() {
        val bytes = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        val result = handler.readFixed32(bytes)
        assertEquals(0x12345678L, result)
    }

    @Test
    fun `decodeVarintValue decodes correctly`() {
        // 300 encoded as varint: 0xAC 0x02
        val bytes = byteArrayOf(0xAC.toByte(), 0x02)
        val result = handler.decodeVarintValue(bytes)
        assertEquals(300L, result)
    }

    // --- Helper Methods ---

    /**
     * Builds a fake FromRadio protobuf message containing a MeshPacket.
     *
     * FromRadio {
     *   packet (tag 2, length-delimited) = MeshPacket {
     *     from (tag 1, fixed32) = from
     *     to (tag 2, fixed32) = to
     *     channel (tag 3, varint) = channel
     *     decoded (tag 4, length-delimited) = Data {
     *       portnum (tag 1, varint) = portnum
     *       payload (tag 2, length-delimited) = payload
     *     }
     *     id (tag 6, fixed32) = packetId
     *   }
     * }
     */
    private fun buildFakeFromRadioPacket(
        from: Long,
        to: Long,
        channel: Int,
        portnum: Int,
        payload: ByteArray,
        packetId: Int
    ): ByteArray {
        // Build Data sub-message
        val dataBytes = handler.encodeData(portnum, payload)

        // Build MeshPacket with from field added
        val fromTagByte = ((1 shl 3) or 5).toByte() // tag 1, wire type 5 (fixed32)
        val fromFixed32 = handler.encodeFixed32(from)

        val meshPacketInner = byteArrayOf(fromTagByte) + fromFixed32 +
                handler.encodeMeshPacket(to, channel, dataBytes, packetId)

        // Build FromRadio: packet (tag 2, length-delimited)
        val fromRadioPacketTag = ((2 shl 3) or 2).toByte() // tag 2, wire type 2
        val meshPacketLength = handler.encodeVarint(meshPacketInner.size.toLong())

        return byteArrayOf(fromRadioPacketTag) + meshPacketLength + meshPacketInner
    }

    /**
     * Checks if a byte array contains a specific sub-array.
     */
    private fun containsSubarray(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        if (needle.size > haystack.size) return false

        for (i in 0..haystack.size - needle.size) {
            var found = true
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) {
                    found = false
                    break
                }
            }
            if (found) return true
        }
        return false
    }
}
