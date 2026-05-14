package com.golfcart.gcd.data.bluetooth

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MeshtasticAdminCommands].
 *
 * Tests the radio administration command encoding including:
 * - Reboot command construction
 * - Position config parsing from handshake
 * - GPS interval read-modify-write pattern
 * - GPS interval selection based on at_home status
 *
 * Validates: Requirements 12.2, 12.3, 12.4, 12.5, 12.6
 */
class MeshtasticAdminCommandsTest {

    private lateinit var adminCommands: MeshtasticAdminCommands

    @BeforeEach
    fun setUp() {
        adminCommands = MeshtasticAdminCommands()
    }

    @Nested
    inner class RebootCommand {

        @Test
        fun `buildRebootCommand encodes reboot_seconds field correctly`() {
            // AdminMessage { reboot_seconds (tag 95, varint) = 5 }
            // Tag 95 with wire type 0: (95 << 3) | 0 = 760
            // 760 in varint: 0xF8 0x05
            // Value 5 in varint: 0x05
            val result = adminCommands.buildRebootCommand(5)

            assertNotNull(result)
            assertTrue(result.isNotEmpty())

            // Verify we can parse it back
            val fields = adminCommands.parseProtobufFields(result)
            assertEquals(1, fields.size)
            assertEquals(95, fields[0].tag)
            assertEquals(0, fields[0].wireType) // varint
            assertEquals(5L, adminCommands.decodeVarintValue(fields[0].data))
        }

        @Test
        fun `buildRebootCommand with zero delay`() {
            val result = adminCommands.buildRebootCommand(0)

            val fields = adminCommands.parseProtobufFields(result)
            assertEquals(1, fields.size)
            assertEquals(95, fields[0].tag)
            assertEquals(0L, adminCommands.decodeVarintValue(fields[0].data))
        }

        @Test
        fun `buildRebootCommand with large delay`() {
            val result = adminCommands.buildRebootCommand(300)

            val fields = adminCommands.parseProtobufFields(result)
            assertEquals(1, fields.size)
            assertEquals(95, fields[0].tag)
            assertEquals(300L, adminCommands.decodeVarintValue(fields[0].data))
        }
    }

    @Nested
    inner class PositionConfigParsing {

        @Test
        fun `parsePositionConfigFromHandshake extracts position config from Config wrapper`() {
            // Build a Config message with position variant (tag 2, length-delimited)
            // PositionConfig with gps_update_interval=8 (tag 5, varint)
            val positionConfig = byteArrayOf(0x28, 0x08) // tag 5 varint, value 8
            val configBytes = buildConfigWithPosition(positionConfig)

            val result = adminCommands.parsePositionConfigFromHandshake(configBytes)

            assertTrue(result)
            assertNotNull(adminCommands.storedPositionConfig)
            assertArrayEquals(positionConfig, adminCommands.storedPositionConfig)
        }

        @Test
        fun `parsePositionConfigFromHandshake returns false for non-position config`() {
            // Build a Config message with device variant (tag 1, length-delimited)
            val deviceConfig = byteArrayOf(0x08, 0x01) // some device config field
            val configBytes = buildConfigWithDevice(deviceConfig)

            val result = adminCommands.parsePositionConfigFromHandshake(configBytes)

            assertFalse(result)
            assertNull(adminCommands.storedPositionConfig)
        }

        @Test
        fun `parsePositionConfigFromHandshake handles empty config bytes`() {
            val result = adminCommands.parsePositionConfigFromHandshake(byteArrayOf())

            assertFalse(result)
            assertNull(adminCommands.storedPositionConfig)
        }

        @Test
        fun `parsePositionConfigFromHandshake preserves multi-field position config`() {
            // PositionConfig with multiple fields:
            // position_broadcast_secs=30 (tag 1, varint)
            // gps_update_interval=8 (tag 5, varint)
            // position_flags=0x1FF (tag 7, varint)
            val positionConfig = byteArrayOf(
                0x08, 30,           // tag 1, value 30
                0x28, 0x08,         // tag 5, value 8
                0x38, 0xFF.toByte(), 0x03  // tag 7, value 511 (0x1FF)
            )
            val configBytes = buildConfigWithPosition(positionConfig)

            val result = adminCommands.parsePositionConfigFromHandshake(configBytes)

            assertTrue(result)
            assertArrayEquals(positionConfig, adminCommands.storedPositionConfig)
        }
    }

    @Nested
    inner class GpsIntervalReadModifyWrite {

        @Test
        fun `buildPositionConfigWithGpsInterval modifies existing config preserving other fields`() {
            // Set up stored config with multiple fields
            val originalConfig = byteArrayOf(
                0x08, 30,           // position_broadcast_secs=30 (tag 1)
                0x28, 0x08,         // gps_update_interval=8 (tag 5)
                0x38, 0x20          // position_flags=32 (tag 7)
            )
            val configWrapper = buildConfigWithPosition(originalConfig)
            adminCommands.parsePositionConfigFromHandshake(configWrapper)

            // Modify GPS interval to 120
            val result = adminCommands.buildPositionConfigWithGpsInterval(120)

            // Parse the result and verify
            val fields = adminCommands.parseProtobufFields(result)

            // Should have 3 fields: tag 1, tag 5 (modified), tag 7
            assertEquals(3, fields.size)

            // Tag 1 preserved
            val tag1 = fields.find { it.tag == 1 }
            assertNotNull(tag1)
            assertEquals(30L, adminCommands.decodeVarintValue(tag1!!.data))

            // Tag 5 modified to 120
            val tag5 = fields.find { it.tag == 5 }
            assertNotNull(tag5)
            assertEquals(120L, adminCommands.decodeVarintValue(tag5!!.data))

            // Tag 7 preserved
            val tag7 = fields.find { it.tag == 7 }
            assertNotNull(tag7)
            assertEquals(32L, adminCommands.decodeVarintValue(tag7!!.data))
        }

        @Test
        fun `buildPositionConfigWithGpsInterval appends field when not in original config`() {
            // Set up stored config WITHOUT gps_update_interval
            val originalConfig = byteArrayOf(
                0x08, 30,           // position_broadcast_secs=30 (tag 1)
                0x38, 0x20          // position_flags=32 (tag 7)
            )
            val configWrapper = buildConfigWithPosition(originalConfig)
            adminCommands.parsePositionConfigFromHandshake(configWrapper)

            // Set GPS interval to 8
            val result = adminCommands.buildPositionConfigWithGpsInterval(8)

            // Parse the result
            val fields = adminCommands.parseProtobufFields(result)

            // Should have 3 fields: tag 1, tag 7, and new tag 5
            assertEquals(3, fields.size)

            // Tag 5 should be present with value 8
            val tag5 = fields.find { it.tag == 5 }
            assertNotNull(tag5)
            assertEquals(8L, adminCommands.decodeVarintValue(tag5!!.data))
        }

        @Test
        fun `buildPositionConfigWithGpsInterval creates minimal config when no stored config`() {
            // No stored config (fresh connection without position config in handshake)
            assertNull(adminCommands.storedPositionConfig)

            val result = adminCommands.buildPositionConfigWithGpsInterval(120)

            // Should be a minimal config with just gps_update_interval
            val fields = adminCommands.parseProtobufFields(result)
            assertEquals(1, fields.size)
            assertEquals(5, fields[0].tag)
            assertEquals(120L, adminCommands.decodeVarintValue(fields[0].data))
        }

        @Test
        fun `buildPositionConfigWithGpsInterval updates stored config for future operations`() {
            // Set up initial config
            val originalConfig = byteArrayOf(0x28, 0x08) // gps_update_interval=8
            val configWrapper = buildConfigWithPosition(originalConfig)
            adminCommands.parsePositionConfigFromHandshake(configWrapper)

            // First modification: set to 120
            adminCommands.buildPositionConfigWithGpsInterval(120)

            // Second modification: set to 8 (should use the updated stored config)
            val result = adminCommands.buildPositionConfigWithGpsInterval(8)

            val fields = adminCommands.parseProtobufFields(result)
            val tag5 = fields.find { it.tag == 5 }
            assertNotNull(tag5)
            assertEquals(8L, adminCommands.decodeVarintValue(tag5!!.data))
        }
    }

    @Nested
    inner class SetPositionConfigCommand {

        @Test
        fun `buildSetPositionConfigCommand wraps in AdminMessage set_config`() {
            val positionConfig = byteArrayOf(0x28, 0x08) // gps_update_interval=8

            val result = adminCommands.buildSetPositionConfigCommand(positionConfig)

            // Parse outer AdminMessage
            val adminFields = adminCommands.parseProtobufFields(result)
            assertEquals(1, adminFields.size)
            assertEquals(34, adminFields[0].tag) // set_config tag
            assertEquals(2, adminFields[0].wireType) // length-delimited

            // Parse inner Config message
            val configFields = adminCommands.parseProtobufFields(adminFields[0].data)
            assertEquals(1, configFields.size)
            assertEquals(2, configFields[0].tag) // position tag in Config
            assertEquals(2, configFields[0].wireType) // length-delimited

            // Verify the PositionConfig bytes are preserved
            assertArrayEquals(positionConfig, configFields[0].data)
        }
    }

    @Nested
    inner class GpsIntervalForHomeStatus {

        @Test
        fun `getGpsIntervalForHomeStatus returns 120 when at home`() {
            assertEquals(120, adminCommands.getGpsIntervalForHomeStatus(true))
        }

        @Test
        fun `getGpsIntervalForHomeStatus returns 8 when away`() {
            assertEquals(8, adminCommands.getGpsIntervalForHomeStatus(false))
        }
    }

    @Nested
    inner class Reset {

        @Test
        fun `reset clears stored position config`() {
            // Store some config
            val config = byteArrayOf(0x28, 0x08)
            val configWrapper = buildConfigWithPosition(config)
            adminCommands.parsePositionConfigFromHandshake(configWrapper)
            assertNotNull(adminCommands.storedPositionConfig)

            // Reset
            adminCommands.reset()

            assertNull(adminCommands.storedPositionConfig)
        }
    }

    // =========================================================================
    // Helper methods for building test protobuf messages
    // =========================================================================

    /**
     * Builds a Config message with the position variant (tag 2, length-delimited).
     */
    private fun buildConfigWithPosition(positionConfigBytes: ByteArray): ByteArray {
        // Config { position (tag 2, length-delimited) = positionConfigBytes }
        // Tag: (2 << 3) | 2 = 18 = 0x12
        val tag = 0x12.toByte()
        val length = adminCommands.encodeVarint(positionConfigBytes.size.toLong())
        return byteArrayOf(tag) + length + positionConfigBytes
    }

    /**
     * Builds a Config message with the device variant (tag 1, length-delimited).
     */
    private fun buildConfigWithDevice(deviceConfigBytes: ByteArray): ByteArray {
        // Config { device (tag 1, length-delimited) = deviceConfigBytes }
        // Tag: (1 << 3) | 2 = 10 = 0x0A
        val tag = 0x0A.toByte()
        val length = adminCommands.encodeVarint(deviceConfigBytes.size.toLong())
        return byteArrayOf(tag) + length + deviceConfigBytes
    }
}
