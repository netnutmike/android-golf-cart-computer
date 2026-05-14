package com.golfcart.gcd.data.bluetooth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MeshtasticHandshake] — the Meshtastic BLE connection handshake handler.
 *
 * Tests cover:
 * - Handshake initiation and config_id generation
 * - ToRadio(want_config_id) protobuf encoding
 * - FromRadio message processing (my_info, config, config_complete_id)
 * - Graceful disconnect message construction
 * - Protobuf varint encoding/decoding
 * - State transitions during handshake
 */
class MeshtasticHandshakeTest {

    private lateinit var handshake: MeshtasticHandshake

    @BeforeEach
    fun setUp() {
        handshake = MeshtasticHandshake()
    }

    // --- Handshake Initiation ---

    @Test
    fun `initiateHandshake generates non-zero config_id`() {
        handshake.initiateHandshake()
        assertTrue(handshake.configId > 0)
    }

    @Test
    fun `initiateHandshake transitions state to WAITING_FOR_CONFIG`() {
        assertEquals(MeshtasticHandshake.HandshakeState.IDLE, handshake.handshakeState.value)
        handshake.initiateHandshake()
        assertEquals(MeshtasticHandshake.HandshakeState.WAITING_FOR_CONFIG, handshake.handshakeState.value)
    }

    @Test
    fun `initiateHandshake returns non-empty byte array`() {
        val bytes = handshake.initiateHandshake()
        assertTrue(bytes.isNotEmpty())
    }

    @Test
    fun `initiateHandshake encodes correct tag byte for want_config_id`() {
        val bytes = handshake.initiateHandshake()
        // Tag 3, wire type 0 (varint): (3 << 3) | 0 = 24 = 0x18
        assertEquals(0x18.toByte(), bytes[0])
    }

    // --- ToRadio Encoding ---

    @Test
    fun `encodeToRadioWantConfigId encodes small value correctly`() {
        val bytes = handshake.encodeToRadioWantConfigId(1)
        // Tag byte: 0x18, varint 1: 0x01
        assertArrayEquals(byteArrayOf(0x18, 0x01), bytes)
    }

    @Test
    fun `encodeToRadioWantConfigId encodes 128 as two-byte varint`() {
        val bytes = handshake.encodeToRadioWantConfigId(128)
        // Tag byte: 0x18, varint 128: 0x80 0x01
        assertArrayEquals(byteArrayOf(0x18, 0x80.toByte(), 0x01), bytes)
    }

    @Test
    fun `encodeToRadioWantConfigId encodes 300 correctly`() {
        val bytes = handshake.encodeToRadioWantConfigId(300)
        // 300 = 0x12C → varint: 0xAC 0x02
        assertArrayEquals(byteArrayOf(0x18, 0xAC.toByte(), 0x02), bytes)
    }

    @Test
    fun `encodeToRadioDisconnect encodes correctly`() {
        val bytes = handshake.encodeToRadioDisconnect()
        // Tag 4, wire type 0: (4 << 3) | 0 = 32 = 0x20, value = 1 (true)
        assertArrayEquals(byteArrayOf(0x20, 0x01), bytes)
    }

    // --- Varint Encoding/Decoding ---

    @Test
    fun `encodeVarint encodes zero`() {
        val bytes = handshake.encodeVarint(0L)
        assertArrayEquals(byteArrayOf(0x00), bytes)
    }

    @Test
    fun `encodeVarint encodes 1`() {
        val bytes = handshake.encodeVarint(1L)
        assertArrayEquals(byteArrayOf(0x01), bytes)
    }

    @Test
    fun `encodeVarint encodes 127 as single byte`() {
        val bytes = handshake.encodeVarint(127L)
        assertArrayEquals(byteArrayOf(0x7F), bytes)
    }

    @Test
    fun `encodeVarint encodes 128 as two bytes`() {
        val bytes = handshake.encodeVarint(128L)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), bytes)
    }

    @Test
    fun `encodeVarint encodes 300 correctly`() {
        val bytes = handshake.encodeVarint(300L)
        // 300 = 0b100101100 → 0b0101100 (44) with continuation, 0b10 (2)
        assertArrayEquals(byteArrayOf(0xAC.toByte(), 0x02), bytes)
    }

    @Test
    fun `decodeVarintValue decodes single byte`() {
        val value = handshake.decodeVarintValue(byteArrayOf(0x01))
        assertEquals(1L, value)
    }

    @Test
    fun `decodeVarintValue decodes multi-byte varint`() {
        val value = handshake.decodeVarintValue(byteArrayOf(0xAC.toByte(), 0x02))
        assertEquals(300L, value)
    }

    @Test
    fun `decodeVarintValue decodes zero`() {
        val value = handshake.decodeVarintValue(byteArrayOf(0x00))
        assertEquals(0L, value)
    }

    @Test
    fun `varint encode then decode round-trip`() {
        val testValues = listOf(0L, 1L, 127L, 128L, 255L, 300L, 16383L, 16384L, 100000L)
        for (value in testValues) {
            val encoded = handshake.encodeVarint(value)
            val decoded = handshake.decodeVarintValue(encoded)
            assertEquals(value, decoded, "Round-trip failed for value $value")
        }
    }

    // --- FromRadio Processing ---

    @Test
    fun `processFromRadio returns false when handshake not initiated`() {
        val result = handshake.processFromRadio(byteArrayOf(0x18, 0x01))
        assertFalse(result)
        assertEquals(MeshtasticHandshake.HandshakeState.IDLE, handshake.handshakeState.value)
    }

    @Test
    fun `processFromRadio extracts node number from my_info`() {
        handshake.initiateHandshake()

        // Construct a FromRadio with my_info (tag 3, length-delimited)
        // my_info contains my_node_num (tag 1, varint) = 0x12345678
        val nodeNum = 0x12345678L
        val nodeNumVarint = handshake.encodeVarint(nodeNum)
        // MyNodeInfo: tag 1 varint + value
        val myNodeInfoPayload = byteArrayOf(0x08.toByte()) + nodeNumVarint // tag 1, wire type 0 = 0x08
        // FromRadio: tag 3, wire type 2 (length-delimited) = (3 << 3) | 2 = 0x1A
        val lengthVarint = handshake.encodeVarint(myNodeInfoPayload.size.toLong())
        val fromRadioBytes = byteArrayOf(0x1A.toByte()) + lengthVarint + myNodeInfoPayload

        val result = handshake.processFromRadio(fromRadioBytes)

        assertFalse(result) // Not complete yet
        assertEquals(nodeNum, handshake.myNodeNum)
        assertEquals(MeshtasticHandshake.HandshakeState.RECEIVED_MY_INFO, handshake.handshakeState.value)
        assertEquals("!12345678", handshake.nodeIdHex)
    }

    @Test
    fun `processFromRadio detects config message`() {
        handshake.initiateHandshake()

        // Construct a FromRadio with config (tag 5, length-delimited)
        // Config payload: some arbitrary bytes
        val configPayload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        // FromRadio: tag 5, wire type 2 = (5 << 3) | 2 = 0x2A
        val lengthVarint = handshake.encodeVarint(configPayload.size.toLong())
        val fromRadioBytes = byteArrayOf(0x2A.toByte()) + lengthVarint + configPayload

        handshake.processFromRadio(fromRadioBytes)

        assertTrue(handshake.positionConfigReceived)
        assertNotNull(handshake.positionConfigBytes)
        assertArrayEquals(configPayload, handshake.positionConfigBytes)
    }

    @Test
    fun `processFromRadio completes handshake on matching config_complete_id`() {
        handshake.initiateHandshake()
        val expectedConfigId = handshake.configId

        // Construct a FromRadio with config_complete_id (tag 7, varint)
        // Tag 7, wire type 0 = (7 << 3) | 0 = 0x38
        val configIdVarint = handshake.encodeVarint(expectedConfigId.toLong() and 0xFFFFFFFFL)
        val fromRadioBytes = byteArrayOf(0x38.toByte()) + configIdVarint

        val result = handshake.processFromRadio(fromRadioBytes)

        assertTrue(result)
        assertEquals(MeshtasticHandshake.HandshakeState.COMPLETE, handshake.handshakeState.value)
    }

    @Test
    fun `processFromRadio does not complete on mismatched config_complete_id`() {
        handshake.initiateHandshake()

        // Send a config_complete_id that doesn't match
        val wrongId = handshake.configId + 1
        val configIdVarint = handshake.encodeVarint(wrongId.toLong() and 0xFFFFFFFFL)
        val fromRadioBytes = byteArrayOf(0x38.toByte()) + configIdVarint

        val result = handshake.processFromRadio(fromRadioBytes)

        assertFalse(result)
        assertNotEquals(MeshtasticHandshake.HandshakeState.COMPLETE, handshake.handshakeState.value)
    }

    @Test
    fun `full handshake sequence completes correctly`() {
        handshake.initiateHandshake()
        val configId = handshake.configId

        // Step 1: Receive my_info with node number
        val nodeNum = 0xABCD1234L
        val nodeNumVarint = handshake.encodeVarint(nodeNum)
        val myNodeInfoPayload = byteArrayOf(0x08.toByte()) + nodeNumVarint
        val lengthVarint1 = handshake.encodeVarint(myNodeInfoPayload.size.toLong())
        val myInfoMessage = byteArrayOf(0x1A.toByte()) + lengthVarint1 + myNodeInfoPayload
        assertFalse(handshake.processFromRadio(myInfoMessage))
        assertEquals(MeshtasticHandshake.HandshakeState.RECEIVED_MY_INFO, handshake.handshakeState.value)

        // Step 2: Receive config
        val configPayload = byteArrayOf(0x10, 0x20, 0x30)
        val lengthVarint2 = handshake.encodeVarint(configPayload.size.toLong())
        val configMessage = byteArrayOf(0x2A.toByte()) + lengthVarint2 + configPayload
        assertFalse(handshake.processFromRadio(configMessage))
        assertTrue(handshake.positionConfigReceived)

        // Step 3: Receive config_complete_id matching our config_id
        val configIdVarint = handshake.encodeVarint(configId.toLong() and 0xFFFFFFFFL)
        val completeMessage = byteArrayOf(0x38.toByte()) + configIdVarint
        assertTrue(handshake.processFromRadio(completeMessage))
        assertEquals(MeshtasticHandshake.HandshakeState.COMPLETE, handshake.handshakeState.value)

        // Verify final state
        assertEquals(nodeNum, handshake.myNodeNum)
        assertEquals("!abcd1234", handshake.nodeIdHex)
    }

    // --- Reset ---

    @Test
    fun `reset clears all handshake state`() {
        handshake.initiateHandshake()
        handshake.reset()

        assertEquals(MeshtasticHandshake.HandshakeState.IDLE, handshake.handshakeState.value)
        assertEquals(0, handshake.configId)
        assertEquals(0L, handshake.myNodeNum)
        assertEquals("", handshake.nodeIdHex)
        assertFalse(handshake.positionConfigReceived)
        assertNull(handshake.positionConfigBytes)
    }

    // --- Disconnect Message ---

    @Test
    fun `createDisconnectMessage returns valid protobuf bytes`() {
        val bytes = handshake.createDisconnectMessage()
        // Should be tag 4 (disconnect), wire type 0, value 1 (true)
        assertEquals(2, bytes.size)
        assertEquals(0x20.toByte(), bytes[0]) // (4 << 3) | 0
        assertEquals(0x01.toByte(), bytes[1]) // true
    }

    // --- Protobuf Field Parsing ---

    @Test
    fun `parseProtobufFields parses varint field`() {
        // Tag 1, wire type 0, value 42
        val bytes = byteArrayOf(0x08.toByte(), 0x2A.toByte())
        val fields = handshake.parseProtobufFields(bytes)

        assertEquals(1, fields.size)
        assertEquals(1, fields[0].tag)
        assertEquals(0, fields[0].wireType)
        assertEquals(42L, handshake.decodeVarintValue(fields[0].data))
    }

    @Test
    fun `parseProtobufFields parses length-delimited field`() {
        // Tag 2, wire type 2, length 3, data [0x01, 0x02, 0x03]
        val bytes = byteArrayOf(0x12.toByte(), 0x03, 0x01, 0x02, 0x03)
        val fields = handshake.parseProtobufFields(bytes)

        assertEquals(1, fields.size)
        assertEquals(2, fields[0].tag)
        assertEquals(2, fields[0].wireType)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), fields[0].data)
    }

    @Test
    fun `parseProtobufFields parses multiple fields`() {
        // Tag 1 varint 5, Tag 2 length-delimited [0xAA, 0xBB]
        val bytes = byteArrayOf(
            0x08.toByte(), 0x05,                    // field 1, varint 5
            0x12.toByte(), 0x02, 0xAA.toByte(), 0xBB.toByte()  // field 2, bytes [0xAA, 0xBB]
        )
        val fields = handshake.parseProtobufFields(bytes)

        assertEquals(2, fields.size)
        assertEquals(1, fields[0].tag)
        assertEquals(5L, handshake.decodeVarintValue(fields[0].data))
        assertEquals(2, fields[1].tag)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte()), fields[1].data)
    }

    @Test
    fun `parseProtobufFields handles empty input`() {
        val fields = handshake.parseProtobufFields(byteArrayOf())
        assertTrue(fields.isEmpty())
    }

    // --- Node ID Formatting ---

    @Test
    fun `nodeIdHex returns empty string when no node number`() {
        assertEquals("", handshake.nodeIdHex)
    }

    @Test
    fun `nodeIdHex formats with leading zeros`() {
        handshake.initiateHandshake()
        // Simulate receiving a small node number
        val nodeNum = 0x00FF.toLong()
        val nodeNumVarint = handshake.encodeVarint(nodeNum)
        val myNodeInfoPayload = byteArrayOf(0x08.toByte()) + nodeNumVarint
        val lengthVarint = handshake.encodeVarint(myNodeInfoPayload.size.toLong())
        val fromRadioBytes = byteArrayOf(0x1A.toByte()) + lengthVarint + myNodeInfoPayload

        handshake.processFromRadio(fromRadioBytes)

        assertEquals("!000000ff", handshake.nodeIdHex)
    }
}
