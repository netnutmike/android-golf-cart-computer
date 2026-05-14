package com.golfcart.gcd.data.bluetooth

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/**
 * Manages the Meshtastic BLE connection handshake sequence.
 *
 * After subscribing to FROMNUM notifications and establishing the BLE connection,
 * this class initiates the configuration handshake by sending a `ToRadio(want_config_id=<random>)`
 * message and processing the resulting `FromRadio` responses.
 *
 * Handshake sequence:
 * 1. Generate a random config_id (non-zero Int)
 * 2. Construct and send a ToRadio message with `want_config_id` set
 * 3. Process incoming FromRadio messages:
 *    - `my_info` (tag 3): Extract the local node number
 *    - `config` (tag 5): Read position configuration
 *    - `config_complete_id` (tag 7): Handshake complete when value matches our config_id
 * 4. Transition state from HANDSHAKING to READY
 *
 * Protobuf field tags in FromRadio:
 * - Tag 3 (my_info): Contains MyNodeInfo with `my_node_num` field (tag 1, varint)
 * - Tag 5 (config): Contains Config message with position config sub-message
 * - Tag 7 (config_complete_id): Contains the config_id echoed back (varint)
 *
 * Protobuf field tags in ToRadio:
 * - Tag 3 (want_config_id): The random config_id to initiate handshake (varint)
 * - Tag 4 (disconnect): Boolean to signal graceful disconnect
 *
 * Note: In production with Wire-generated classes, these would be:
 * ```kotlin
 * val toRadio = ToRadio(want_config_id = configId)
 * val bytes = ToRadio.ADAPTER.encode(toRadio)
 * ```
 *
 * Validates: Requirements 1.9, 18.4, 18.5
 */
class MeshtasticHandshake {

    companion object {
        private const val TAG = "MeshtasticHandshake"

        // Protobuf wire type constants
        private const val WIRE_TYPE_VARINT = 0
        private const val WIRE_TYPE_LENGTH_DELIMITED = 2

        // FromRadio field tags
        private const val FROM_RADIO_MY_INFO_TAG = 3
        private const val FROM_RADIO_CONFIG_TAG = 5
        private const val FROM_RADIO_CONFIG_COMPLETE_TAG = 7

        // ToRadio field tags
        private const val TO_RADIO_WANT_CONFIG_ID_TAG = 3
        private const val TO_RADIO_DISCONNECT_TAG = 4

        // MyNodeInfo field tags
        private const val MY_NODE_INFO_MY_NODE_NUM_TAG = 1
    }

    /**
     * Represents the current state of the handshake process.
     */
    enum class HandshakeState {
        /** Handshake not yet started. */
        IDLE,
        /** Handshake initiated, waiting for config responses. */
        WAITING_FOR_CONFIG,
        /** Received my_info with node number. */
        RECEIVED_MY_INFO,
        /** Handshake complete, config_complete_id matched. */
        COMPLETE,
        /** Handshake failed (timeout or error). */
        FAILED
    }

    private val _handshakeState = MutableStateFlow(HandshakeState.IDLE)
    val handshakeState: StateFlow<HandshakeState> = _handshakeState.asStateFlow()

    /** The random config_id used for this handshake session. */
    var configId: Int = 0
        private set

    /** The local node number extracted from my_info during handshake. */
    var myNodeNum: Long = 0L
        private set

    /** The node ID formatted as hex string (e.g., "!a1b2c3d4"). */
    val nodeIdHex: String
        get() = if (myNodeNum != 0L) "!${myNodeNum.toString(16).padStart(8, '0')}" else ""

    /** Whether position config was received during handshake. */
    var positionConfigReceived: Boolean = false
        private set

    /** Raw position config bytes (for later processing). */
    var positionConfigBytes: ByteArray? = null
        private set

    /**
     * Initiates the handshake by generating a config_id and constructing the
     * ToRadio(want_config_id) message bytes.
     *
     * @return The protobuf-encoded ToRadio message bytes to write via [MeshtasticProtocol.writeToRadio].
     */
    fun initiateHandshake(): ByteArray {
        // Generate a random non-zero config_id
        configId = Random.nextInt(1, Int.MAX_VALUE)
        _handshakeState.value = HandshakeState.WAITING_FOR_CONFIG
        positionConfigReceived = false
        positionConfigBytes = null
        myNodeNum = 0L

        Log.i(TAG, "Initiating handshake with config_id=$configId")

        // Construct ToRadio(want_config_id=configId) protobuf bytes
        // In production with Wire: ToRadio.ADAPTER.encode(ToRadio(want_config_id = configId))
        return encodeToRadioWantConfigId(configId)
    }

    /**
     * Processes a raw FromRadio protobuf message received during the handshake.
     *
     * Looks for:
     * - my_info (tag 3): Extracts node number
     * - config (tag 5): Stores position config
     * - config_complete_id (tag 7): Completes handshake if value matches configId
     *
     * @param fromRadioBytes The raw protobuf bytes of a FromRadio message.
     * @return true if the handshake is now complete, false otherwise.
     */
    fun processFromRadio(fromRadioBytes: ByteArray): Boolean {
        if (_handshakeState.value == HandshakeState.COMPLETE) {
            return true
        }

        if (_handshakeState.value == HandshakeState.IDLE) {
            // Handshake not initiated yet, ignore
            return false
        }

        try {
            // Parse the FromRadio message to identify which field is set
            val parsedFields = parseProtobufFields(fromRadioBytes)

            for ((fieldTag, wireType, fieldData) in parsedFields) {
                when (fieldTag) {
                    FROM_RADIO_MY_INFO_TAG -> {
                        if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                            processMyInfo(fieldData)
                        }
                    }
                    FROM_RADIO_CONFIG_TAG -> {
                        if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                            processConfig(fieldData)
                        }
                    }
                    FROM_RADIO_CONFIG_COMPLETE_TAG -> {
                        if (wireType == WIRE_TYPE_VARINT) {
                            val completedId = decodeVarintValue(fieldData)
                            processConfigComplete(completedId.toInt())
                            if (_handshakeState.value == HandshakeState.COMPLETE) {
                                return true
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing FromRadio during handshake", e)
        }

        return false
    }

    /**
     * Resets the handshake state for a new connection attempt.
     */
    fun reset() {
        _handshakeState.value = HandshakeState.IDLE
        configId = 0
        myNodeNum = 0L
        positionConfigReceived = false
        positionConfigBytes = null
        Log.d(TAG, "Handshake state reset")
    }

    /**
     * Constructs a ToRadio(disconnect=true) message for graceful disconnect.
     *
     * In production with Wire:
     * ```kotlin
     * ToRadio.ADAPTER.encode(ToRadio(disconnect = true))
     * ```
     *
     * @return The protobuf-encoded ToRadio disconnect message bytes.
     */
    fun createDisconnectMessage(): ByteArray {
        return encodeToRadioDisconnect()
    }

    // --- Private Processing Methods ---

    private fun processMyInfo(myInfoBytes: ByteArray) {
        // Parse MyNodeInfo to extract my_node_num (tag 1, varint)
        val fields = parseProtobufFields(myInfoBytes)
        for ((tag, wireType, data) in fields) {
            if (tag == MY_NODE_INFO_MY_NODE_NUM_TAG && wireType == WIRE_TYPE_VARINT) {
                myNodeNum = decodeVarintValue(data)
                _handshakeState.value = HandshakeState.RECEIVED_MY_INFO
                Log.i(TAG, "Received my_info: node_num=$myNodeNum (${nodeIdHex})")
                return
            }
        }
        Log.w(TAG, "my_info received but could not extract node number")
    }

    private fun processConfig(configBytes: ByteArray) {
        // Store the raw config bytes for later processing
        // In production, this would be decoded as Config and checked for position config
        positionConfigReceived = true
        positionConfigBytes = configBytes.copyOf()
        Log.d(TAG, "Received config message: ${configBytes.size} bytes")
    }

    private fun processConfigComplete(completedId: Int) {
        if (completedId == configId) {
            _handshakeState.value = HandshakeState.COMPLETE
            Log.i(TAG, "Handshake complete! config_complete_id=$completedId matches our config_id")
        } else {
            Log.w(TAG, "config_complete_id=$completedId does not match our config_id=$configId")
        }
    }

    // --- Protobuf Encoding Helpers ---

    /**
     * Encodes a ToRadio message with want_config_id field set.
     *
     * Protobuf encoding:
     * - Field 3 (want_config_id), wire type 0 (varint)
     * - Tag byte: (3 << 3) | 0 = 24 = 0x18
     * - Followed by varint-encoded config_id value
     */
    internal fun encodeToRadioWantConfigId(wantConfigId: Int): ByteArray {
        val tagByte = ((TO_RADIO_WANT_CONFIG_ID_TAG shl 3) or WIRE_TYPE_VARINT).toByte()
        val varintBytes = encodeVarint(wantConfigId.toLong() and 0xFFFFFFFFL)
        return byteArrayOf(tagByte) + varintBytes
    }

    /**
     * Encodes a ToRadio message with disconnect=true field set.
     *
     * Protobuf encoding:
     * - Field 4 (disconnect), wire type 0 (varint, bool encoded as 1)
     * - Tag byte: (4 << 3) | 0 = 32 = 0x20
     * - Followed by varint 1 (true)
     */
    internal fun encodeToRadioDisconnect(): ByteArray {
        val tagByte = ((TO_RADIO_DISCONNECT_TAG shl 3) or WIRE_TYPE_VARINT).toByte()
        return byteArrayOf(tagByte, 0x01)
    }

    // --- Protobuf Decoding Helpers ---

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
     *
     * Supports wire types:
     * - 0 (varint): Variable-length integer
     * - 2 (length-delimited): Length-prefixed bytes
     *
     * This is a minimal parser sufficient for the handshake fields.
     */
    internal fun parseProtobufFields(bytes: ByteArray): List<ProtobufField> {
        val fields = mutableListOf<ProtobufField>()
        var offset = 0

        while (offset < bytes.size) {
            // Read tag byte(s) - simplified: assume single-byte tag for common fields
            if (offset >= bytes.size) break
            val tagByte = bytes[offset].toInt() and 0xFF
            offset++

            val wireType = tagByte and 0x07
            val fieldTag = tagByte shr 3

            when (wireType) {
                WIRE_TYPE_VARINT -> {
                    // Read varint bytes
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
                    // Read length as varint, then read that many bytes
                    val lengthResult = readVarintAt(bytes, offset)
                    val length = lengthResult.first.toInt()
                    offset = lengthResult.second
                    if (offset + length <= bytes.size) {
                        val fieldData = bytes.copyOfRange(offset, offset + length)
                        fields.add(ProtobufField(fieldTag, wireType, fieldData))
                        offset += length
                    } else {
                        // Truncated data, skip
                        break
                    }
                }
                else -> {
                    // Unknown wire type, cannot continue parsing safely
                    Log.w(TAG, "Unknown wire type $wireType at offset ${offset - 1}")
                    break
                }
            }
        }

        return fields
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

    /**
     * Encodes a Long value as a protobuf varint byte array.
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
}
