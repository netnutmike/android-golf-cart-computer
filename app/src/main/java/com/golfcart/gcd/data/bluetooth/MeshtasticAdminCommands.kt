package com.golfcart.gcd.data.bluetooth

import android.util.Log

/**
 * Handles Meshtastic radio administration commands including:
 * - Radio reboot via ADMIN_APP port
 * - Position config read during handshake (parsing Config wrapper)
 * - GPS update interval setting using read-modify-write pattern
 * - GPS interval adjustment based on at_home status
 *
 * The read-modify-write pattern ensures that when updating the GPS interval,
 * all other position config fields (position_broadcast_secs, gps_mode,
 * position_flags, etc.) are preserved from the config received during handshake.
 *
 * Protobuf structure reference:
 * ```
 * Config {
 *   oneof payload_variant {
 *     PositionConfig position = 2;  // tag 2, length-delimited
 *   }
 * }
 *
 * PositionConfig {
 *   uint32 position_broadcast_secs = 1;
 *   bool position_broadcast_smart_enabled = 2;
 *   uint32 fixed_position = 3;
 *   GpsMode gps_mode = 4;
 *   uint32 gps_update_interval = 5;  // <-- the field we modify
 *   uint32 gps_attempt_time = 6;
 *   uint32 position_flags = 7;
 *   uint32 rx_gpio = 8;
 *   uint32 tx_gpio = 9;
 *   uint32 broadcast_smart_minimum_distance = 10;
 *   uint32 broadcast_smart_minimum_interval_secs = 11;
 *   uint32 gps_en_gpio = 12;
 * }
 * ```
 *
 * AdminMessage for reboot:
 * ```
 * AdminMessage {
 *   int32 reboot_seconds = 95;  // tag 95, varint
 * }
 * ```
 *
 * AdminMessage for set_config:
 * ```
 * AdminMessage {
 *   Config set_config = 34;  // tag 34, length-delimited
 * }
 * ```
 *
 * Validates: Requirements 12.2, 12.3, 12.4, 12.5, 12.6
 */
class MeshtasticAdminCommands {

    companion object {
        private const val TAG = "MeshtasticAdminCmds"

        // --- Protobuf Wire Types ---
        private const val WIRE_TYPE_VARINT = 0
        private const val WIRE_TYPE_LENGTH_DELIMITED = 2

        // --- Config field tags ---
        /** Config.position field tag (PositionConfig is tag 2 in Config oneof). */
        private const val CONFIG_POSITION_TAG = 2

        // --- PositionConfig field tags ---
        /** PositionConfig.gps_update_interval field tag. */
        const val POSITION_CONFIG_GPS_UPDATE_INTERVAL_TAG = 5

        // --- AdminMessage field tags ---
        /** AdminMessage.reboot_seconds field tag. */
        private const val ADMIN_REBOOT_SECONDS_TAG = 95

        /** AdminMessage.set_config field tag. */
        private const val ADMIN_SET_CONFIG_TAG = 34

        // --- GPS interval constants ---
        /** GPS update interval when at home (seconds). */
        const val GPS_INTERVAL_AT_HOME = 120

        /** GPS update interval when away from home (seconds). */
        const val GPS_INTERVAL_AWAY = 8
    }

    /**
     * The position config bytes received during the handshake, extracted from
     * the Config wrapper. This is the raw PositionConfig protobuf bytes.
     * Used as the base for read-modify-write operations.
     */
    var storedPositionConfig: ByteArray? = null
        private set

    /**
     * Parses the Config message received during handshake to extract the
     * PositionConfig sub-message (tag 2 in Config's oneof payload_variant).
     *
     * The handshake receives a Config message which wraps one of several config
     * types. We look for the position variant (tag 2) and store its bytes.
     *
     * @param configBytes The raw Config protobuf bytes from the handshake.
     * @return true if a PositionConfig was found and stored, false otherwise.
     */
    fun parsePositionConfigFromHandshake(configBytes: ByteArray): Boolean {
        try {
            val fields = parseProtobufFields(configBytes)
            for ((fieldTag, wireType, fieldData) in fields) {
                if (fieldTag == CONFIG_POSITION_TAG && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                    storedPositionConfig = fieldData.copyOf()
                    Log.i(TAG, "Extracted PositionConfig from handshake: ${fieldData.size} bytes")
                    return true
                }
            }
            // This Config message is not a PositionConfig variant (could be device, power, etc.)
            Log.d(TAG, "Config message does not contain PositionConfig (different config type)")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse PositionConfig from handshake config", e)
            return false
        }
    }

    /**
     * Builds a PositionConfig with the GPS update interval modified, preserving
     * all other fields from the stored config (read-modify-write pattern).
     *
     * If no stored config is available (handshake didn't provide one), creates
     * a minimal PositionConfig with only the gps_update_interval field set.
     *
     * @param intervalSeconds The desired GPS update interval in seconds.
     * @return The protobuf-encoded PositionConfig bytes with the interval modified.
     */
    fun buildPositionConfigWithGpsInterval(intervalSeconds: Int): ByteArray {
        val baseConfig = storedPositionConfig

        return if (baseConfig != null) {
            // Read-modify-write: replace or insert gps_update_interval in existing config
            modifyGpsUpdateInterval(baseConfig, intervalSeconds)
        } else {
            // No stored config — create minimal PositionConfig with just the interval
            Log.w(TAG, "No stored PositionConfig, creating minimal config with interval=$intervalSeconds")
            encodeMinimalPositionConfig(intervalSeconds)
        }
    }

    /**
     * Builds the complete AdminMessage bytes for a reboot command.
     *
     * AdminMessage {
     *   reboot_seconds (tag 95, varint) = delaySeconds
     * }
     *
     * @param delaySeconds Seconds to wait before rebooting.
     * @return The protobuf-encoded AdminMessage bytes.
     */
    fun buildRebootCommand(delaySeconds: Int): ByteArray {
        // Tag 95, wire type 0 (varint): (95 << 3) | 0 = 760
        // 760 in varint: 760 = 0x2F8 → bytes: 0xF8, 0x05
        val tagBytes = encodeVarint((ADMIN_REBOOT_SECONDS_TAG.toLong() shl 3) or WIRE_TYPE_VARINT.toLong())
        val valueBytes = encodeSignedVarint(delaySeconds)
        return tagBytes + valueBytes
    }

    /**
     * Builds the complete AdminMessage bytes for setting position config.
     *
     * AdminMessage {
     *   set_config (tag 34, length-delimited) = Config {
     *     position (tag 2, length-delimited) = PositionConfig { ... }
     *   }
     * }
     *
     * @param positionConfigBytes The protobuf-encoded PositionConfig bytes.
     * @return The protobuf-encoded AdminMessage bytes.
     */
    fun buildSetPositionConfigCommand(positionConfigBytes: ByteArray): ByteArray {
        // Wrap PositionConfig in Config message (position is tag 2, length-delimited)
        val configPositionTagBytes = encodeVarint(
            (CONFIG_POSITION_TAG.toLong() shl 3) or WIRE_TYPE_LENGTH_DELIMITED.toLong()
        )
        val configBytes = configPositionTagBytes +
                encodeVarint(positionConfigBytes.size.toLong()) +
                positionConfigBytes

        // Wrap Config in AdminMessage (set_config is tag 34, length-delimited)
        val adminSetConfigTagBytes = encodeVarint(
            (ADMIN_SET_CONFIG_TAG.toLong() shl 3) or WIRE_TYPE_LENGTH_DELIMITED.toLong()
        )
        return adminSetConfigTagBytes +
                encodeVarint(configBytes.size.toLong()) +
                configBytes
    }

    /**
     * Determines the appropriate GPS interval based on at_home status.
     *
     * @param isAtHome Whether the device is within the home geofence.
     * @return The GPS update interval in seconds (120 at home, 8 away).
     */
    fun getGpsIntervalForHomeStatus(isAtHome: Boolean): Int {
        return if (isAtHome) GPS_INTERVAL_AT_HOME else GPS_INTERVAL_AWAY
    }

    /**
     * Resets the stored position config (e.g., on disconnect).
     */
    fun reset() {
        storedPositionConfig = null
        Log.d(TAG, "Admin commands state reset")
    }

    // =========================================================================
    // Read-Modify-Write Implementation
    // =========================================================================

    /**
     * Modifies the gps_update_interval field in an existing PositionConfig,
     * preserving all other fields unchanged.
     *
     * Strategy: Parse all fields from the existing config, replace or add the
     * gps_update_interval field (tag 5), then re-encode all fields.
     *
     * @param existingConfig The existing PositionConfig protobuf bytes.
     * @param intervalSeconds The new GPS update interval value.
     * @return The modified PositionConfig protobuf bytes.
     */
    internal fun modifyGpsUpdateInterval(existingConfig: ByteArray, intervalSeconds: Int): ByteArray {
        val fields = parseProtobufFields(existingConfig)
        val result = mutableListOf<Byte>()
        var intervalFieldFound = false

        for ((fieldTag, wireType, fieldData) in fields) {
            if (fieldTag == POSITION_CONFIG_GPS_UPDATE_INTERVAL_TAG && wireType == WIRE_TYPE_VARINT) {
                // Replace this field with the new interval value
                intervalFieldFound = true
                val tagBytes = encodeVarint(
                    (POSITION_CONFIG_GPS_UPDATE_INTERVAL_TAG.toLong() shl 3) or WIRE_TYPE_VARINT.toLong()
                )
                val valueBytes = encodeVarint(intervalSeconds.toLong() and 0xFFFFFFFFL)
                result.addAll(tagBytes.toList())
                result.addAll(valueBytes.toList())
            } else {
                // Preserve this field as-is by re-encoding it
                val tagBytes = encodeVarint((fieldTag.toLong() shl 3) or wireType.toLong())
                result.addAll(tagBytes.toList())

                when (wireType) {
                    WIRE_TYPE_VARINT -> {
                        result.addAll(fieldData.toList())
                    }
                    WIRE_TYPE_LENGTH_DELIMITED -> {
                        val lengthBytes = encodeVarint(fieldData.size.toLong())
                        result.addAll(lengthBytes.toList())
                        result.addAll(fieldData.toList())
                    }
                    else -> {
                        // For fixed-width types, just append the raw data
                        result.addAll(fieldData.toList())
                    }
                }
            }
        }

        // If the interval field wasn't in the original config, append it
        if (!intervalFieldFound) {
            val tagBytes = encodeVarint(
                (POSITION_CONFIG_GPS_UPDATE_INTERVAL_TAG.toLong() shl 3) or WIRE_TYPE_VARINT.toLong()
            )
            val valueBytes = encodeVarint(intervalSeconds.toLong() and 0xFFFFFFFFL)
            result.addAll(tagBytes.toList())
            result.addAll(valueBytes.toList())
        }

        val modifiedConfig = result.toByteArray()

        // Update stored config with the modified version for future operations
        storedPositionConfig = modifiedConfig

        Log.d(TAG, "Modified GPS interval to ${intervalSeconds}s " +
                "(${if (intervalFieldFound) "replaced" else "appended"} field)")

        return modifiedConfig
    }

    /**
     * Creates a minimal PositionConfig with only the gps_update_interval field.
     *
     * PositionConfig {
     *   gps_update_interval (tag 5, varint) = intervalSeconds
     * }
     *
     * @param intervalSeconds The GPS update interval in seconds.
     * @return The protobuf-encoded PositionConfig bytes.
     */
    internal fun encodeMinimalPositionConfig(intervalSeconds: Int): ByteArray {
        val tagBytes = encodeVarint(
            (POSITION_CONFIG_GPS_UPDATE_INTERVAL_TAG.toLong() shl 3) or WIRE_TYPE_VARINT.toLong()
        )
        val valueBytes = encodeVarint(intervalSeconds.toLong() and 0xFFFFFFFFL)
        return tagBytes + valueBytes
    }

    // =========================================================================
    // Protobuf Encoding/Decoding Helpers
    // =========================================================================

    /**
     * Represents a parsed protobuf field.
     */
    data class ProtobufField(
        val tag: Int,
        val wireType: Int,
        val data: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProtobufField) return false
            return tag == other.tag && wireType == other.wireType && data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = tag
            result = 31 * result + wireType
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /**
     * Parses raw protobuf bytes into a list of fields (tag, wire type, data).
     */
    internal fun parseProtobufFields(bytes: ByteArray): List<ProtobufField> {
        val fields = mutableListOf<ProtobufField>()
        var offset = 0

        while (offset < bytes.size) {
            // Read tag as varint
            val tagResult = readVarintAt(bytes, offset)
            val tagValue = tagResult.first.toInt()
            offset = tagResult.second

            val wireType = tagValue and 0x07
            val fieldTag = tagValue ushr 3

            when (wireType) {
                WIRE_TYPE_VARINT -> {
                    val varintStart = offset
                    while (offset < bytes.size && (bytes[offset].toInt() and 0x80) != 0) {
                        offset++
                    }
                    if (offset < bytes.size) {
                        offset++ // Include the final byte (MSB not set)
                    }
                    val varintData = bytes.copyOfRange(varintStart, offset)
                    fields.add(ProtobufField(fieldTag, wireType, varintData))
                }
                WIRE_TYPE_LENGTH_DELIMITED -> {
                    val lengthResult = readVarintAt(bytes, offset)
                    val length = lengthResult.first.toInt()
                    offset = lengthResult.second
                    if (offset + length <= bytes.size) {
                        val fieldData = bytes.copyOfRange(offset, offset + length)
                        fields.add(ProtobufField(fieldTag, wireType, fieldData))
                        offset += length
                    } else {
                        break
                    }
                }
                5 -> {
                    // Wire type 5: fixed32 (4 bytes)
                    if (offset + 4 <= bytes.size) {
                        val fieldData = bytes.copyOfRange(offset, offset + 4)
                        fields.add(ProtobufField(fieldTag, wireType, fieldData))
                        offset += 4
                    } else {
                        break
                    }
                }
                1 -> {
                    // Wire type 1: fixed64 (8 bytes)
                    if (offset + 8 <= bytes.size) {
                        val fieldData = bytes.copyOfRange(offset, offset + 8)
                        fields.add(ProtobufField(fieldTag, wireType, fieldData))
                        offset += 8
                    } else {
                        break
                    }
                }
                else -> {
                    Log.w(TAG, "Unknown wire type $wireType for field $fieldTag")
                    break
                }
            }
        }

        return fields
    }

    /**
     * Encodes a Long value as a protobuf unsigned varint byte array.
     */
    internal fun encodeVarint(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)

        val bytes = mutableListOf<Byte>()
        var remaining = value
        while (remaining != 0L) {
            var byte = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) {
                byte = byte or 0x80
            }
            bytes.add(byte.toByte())
        }
        return bytes.toByteArray()
    }

    /**
     * Encodes a signed int32 as a protobuf varint (using zigzag or standard encoding).
     * Protobuf int32 uses standard two's complement varint encoding (not zigzag).
     */
    internal fun encodeSignedVarint(value: Int): ByteArray {
        // Protobuf int32 is encoded as a 64-bit varint with sign extension
        val longValue = value.toLong()
        return encodeVarint(longValue and 0xFFFFFFFFL)
    }

    /**
     * Decodes a varint from raw bytes into a Long value.
     */
    internal fun decodeVarintValue(varintBytes: ByteArray): Long {
        var result = 0L
        var shift = 0
        for (byte in varintBytes) {
            val b = byte.toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            shift += 7
            if ((b and 0x80) == 0) break
        }
        return result
    }

    /**
     * Reads a varint starting at the given offset in the byte array.
     *
     * @return Pair of (decoded value, new offset after varint)
     */
    private fun readVarintAt(bytes: ByteArray, startOffset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var offset = startOffset
        while (offset < bytes.size) {
            val b = bytes[offset].toInt() and 0xFF
            offset++
            result = result or ((b.toLong() and 0x7F) shl shift)
            shift += 7
            if ((b and 0x80) == 0) break
        }
        return Pair(result, offset)
    }
}
