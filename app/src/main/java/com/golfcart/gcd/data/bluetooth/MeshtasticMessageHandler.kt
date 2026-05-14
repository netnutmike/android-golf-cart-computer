package com.golfcart.gcd.data.bluetooth

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.random.Random

/**
 * Handles Meshtastic message construction and incoming packet routing.
 *
 * Responsibilities:
 * - Construct outbound MeshPacket protobuf bytes for text messages and admin messages
 * - Route incoming MeshPacket bytes based on portnum
 * - Enforce the 237-byte payload limit on outbound messages
 * - Generate random non-zero packet IDs
 * - Emit routed messages to appropriate SharedFlows
 *
 * Protobuf structure for outbound text message:
 * ```
 * ToRadio {
 *   packet (tag 1, length-delimited) = MeshPacket {
 *     to (tag 2, fixed32) = destination
 *     channel (tag 3, varint) = channelIndex
 *     decoded (tag 4, length-delimited) = Data {
 *       portnum (tag 1, varint) = TEXT_MESSAGE_APP (1)
 *       payload (tag 2, length-delimited) = text.toByteArray(UTF-8)
 *     }
 *     id (tag 6, fixed32) = randomPacketId
 *   }
 * }
 * ```
 *
 * Port Numbers (from portnums.proto):
 * - TEXT_MESSAGE_APP (1) — Text messages
 * - POSITION_APP (3) — Position data
 * - ADMIN_APP (6) — Admin commands
 * - TELEMETRY_APP (67) — Node telemetry
 *
 * In production with Wire-generated classes:
 * ```kotlin
 * val data = Data(portnum = Portnum.TEXT_MESSAGE_APP, payload = text.encodeToByteArray().toByteString())
 * val meshPacket = MeshPacket(to = destination.toInt(), channel = channel, decoded = data, id = randomId)
 * val toRadio = ToRadio(packet = meshPacket)
 * val bytes = ToRadio.ADAPTER.encode(toRadio)
 * ```
 *
 * Validates: Requirements 2.3, 2.4, 2.7, 2.9, 18.6, 18.7, 18.8
 */
class MeshtasticMessageHandler(
    private val localNodeNum: () -> Long
) {

    companion object {
        private const val TAG = "MeshtasticMsgHandler"

        // --- Port Numbers (from portnums.proto) ---
        const val PORT_TEXT_MESSAGE_APP = 1
        const val PORT_POSITION_APP = 3
        const val PORT_ADMIN_APP = 6
        const val PORT_TELEMETRY_APP = 67

        // --- Protobuf Wire Types ---
        private const val WIRE_TYPE_VARINT = 0
        private const val WIRE_TYPE_FIXED32 = 5
        private const val WIRE_TYPE_LENGTH_DELIMITED = 2

        // --- ToRadio field tags ---
        private const val TO_RADIO_PACKET_TAG = 1  // MeshPacket (length-delimited)

        // --- MeshPacket field tags ---
        private const val MESH_PACKET_TO_TAG = 2       // fixed32
        private const val MESH_PACKET_CHANNEL_TAG = 3  // varint
        private const val MESH_PACKET_DECODED_TAG = 4  // Data (length-delimited)
        private const val MESH_PACKET_ID_TAG = 6       // fixed32

        // --- Data field tags ---
        private const val DATA_PORTNUM_TAG = 1   // varint
        private const val DATA_PAYLOAD_TAG = 2   // length-delimited

        // --- FromRadio field tags ---
        private const val FROM_RADIO_PACKET_TAG = 2  // MeshPacket (length-delimited)

        // --- MeshPacket decoded fields for incoming ---
        private const val MESH_PACKET_FROM_TAG = 1     // fixed32
    }

    // --- SharedFlows for routed incoming messages ---

    private val _textMessages = MutableSharedFlow<IncomingTextMessage>(replay = 1, extraBufferCapacity = 64)
    /** Flow of incoming text messages (TEXT_MESSAGE_APP port). */
    val textMessages: SharedFlow<IncomingTextMessage> = _textMessages.asSharedFlow()

    private val _adminMessages = MutableSharedFlow<IncomingPacket>(replay = 1, extraBufferCapacity = 16)
    /** Flow of incoming admin messages (ADMIN_APP port). */
    val adminMessages: SharedFlow<IncomingPacket> = _adminMessages.asSharedFlow()

    private val _positionMessages = MutableSharedFlow<IncomingPacket>(replay = 1, extraBufferCapacity = 16)
    /** Flow of incoming position messages (POSITION_APP port). */
    val positionMessages: SharedFlow<IncomingPacket> = _positionMessages.asSharedFlow()

    private val _telemetryMessages = MutableSharedFlow<IncomingPacket>(replay = 1, extraBufferCapacity = 16)
    /** Flow of incoming telemetry messages (TELEMETRY_APP port). */
    val telemetryMessages: SharedFlow<IncomingPacket> = _telemetryMessages.asSharedFlow()

    private val _unknownMessages = MutableSharedFlow<IncomingPacket>(replay = 1, extraBufferCapacity = 16)
    /** Flow of incoming messages with unrecognized port numbers. */
    val unknownMessages: SharedFlow<IncomingPacket> = _unknownMessages.asSharedFlow()

    // --- Outbound Message Construction ---

    /**
     * Constructs a ToRadio protobuf byte array containing a text message MeshPacket.
     *
     * @param text The message text to send.
     * @param destination The destination node number (use 0xFFFFFFFF for broadcast).
     * @param channel The channel index to send on.
     * @return The protobuf-encoded ToRadio bytes, or null if the payload exceeds 237 bytes.
     * @throws PayloadTooLargeException if the text payload exceeds 237 bytes when encoded as UTF-8.
     */
    fun buildTextMessage(text: String, destination: Long, channel: Int): ByteArray {
        val payloadBytes = text.toByteArray(Charsets.UTF_8)

        if (payloadBytes.size > MeshtasticConstants.MAX_PAYLOAD_SIZE) {
            throw PayloadTooLargeException(
                "Text payload is ${payloadBytes.size} bytes, exceeds maximum of " +
                        "${MeshtasticConstants.MAX_PAYLOAD_SIZE} bytes"
            )
        }

        val packetId = generatePacketId()

        // Build Data sub-message: portnum (tag 1, varint) + payload (tag 2, length-delimited)
        val dataBytes = encodeData(PORT_TEXT_MESSAGE_APP, payloadBytes)

        // Build MeshPacket: to (tag 2, fixed32) + channel (tag 3, varint) + decoded (tag 4, LD) + id (tag 6, fixed32)
        val meshPacketBytes = encodeMeshPacket(destination, channel, dataBytes, packetId)

        // Build ToRadio: packet (tag 1, length-delimited)
        val toRadioBytes = encodeToRadioPacket(meshPacketBytes)

        Log.d(TAG, "Built text message: ${payloadBytes.size} bytes payload, " +
                "dest=${destination.toString(16)}, channel=$channel, id=$packetId")

        return toRadioBytes
    }

    /**
     * Constructs a ToRadio protobuf byte array containing an admin message MeshPacket.
     *
     * Admin messages are sent to the local node (self-addressed) on channel 0.
     *
     * @param adminPayload The protobuf-encoded AdminMessage bytes.
     * @return The protobuf-encoded ToRadio bytes.
     * @throws PayloadTooLargeException if the admin payload exceeds 237 bytes.
     */
    fun buildAdminMessage(adminPayload: ByteArray): ByteArray {
        if (adminPayload.size > MeshtasticConstants.MAX_PAYLOAD_SIZE) {
            throw PayloadTooLargeException(
                "Admin payload is ${adminPayload.size} bytes, exceeds maximum of " +
                        "${MeshtasticConstants.MAX_PAYLOAD_SIZE} bytes"
            )
        }

        val packetId = generatePacketId()
        val myNode = localNodeNum()

        // Build Data sub-message: portnum (tag 1, varint = ADMIN_APP) + payload (tag 2, LD)
        val dataBytes = encodeData(PORT_ADMIN_APP, adminPayload)

        // Admin messages are sent to self (local node) on channel 0
        val meshPacketBytes = encodeMeshPacket(myNode, 0, dataBytes, packetId)

        // Build ToRadio: packet (tag 1, length-delimited)
        val toRadioBytes = encodeToRadioPacket(meshPacketBytes)

        Log.d(TAG, "Built admin message: ${adminPayload.size} bytes payload, id=$packetId")

        return toRadioBytes
    }

    /**
     * Constructs a reboot admin command.
     *
     * AdminMessage protobuf structure:
     * ```
     * AdminMessage {
     *   reboot_seconds (tag 5, varint) = delaySeconds
     * }
     * ```
     *
     * In production with Wire:
     * ```kotlin
     * val adminMsg = AdminMessage(reboot_seconds = delaySeconds)
     * AdminMessage.ADAPTER.encode(adminMsg)
     * ```
     *
     * @param delaySeconds Seconds to wait before rebooting.
     * @return The protobuf-encoded AdminMessage bytes.
     */
    fun buildRebootAdminPayload(delaySeconds: Int): ByteArray {
        // AdminMessage field: reboot_seconds (tag 5, varint)
        val tagByte = ((5 shl 3) or WIRE_TYPE_VARINT).toByte()
        val varintBytes = encodeVarint(delaySeconds.toLong() and 0xFFFFFFFFL)
        return byteArrayOf(tagByte) + varintBytes
    }

    /**
     * Constructs a position config admin command (set_config variant).
     *
     * AdminMessage protobuf structure for setting config:
     * ```
     * AdminMessage {
     *   set_config (tag 3, length-delimited) = Config {
     *     position (tag 4, length-delimited) = PositionConfig { ... }
     *   }
     * }
     * ```
     *
     * @param positionConfigBytes The protobuf-encoded PositionConfig bytes.
     * @return The protobuf-encoded AdminMessage bytes.
     */
    fun buildSetPositionConfigPayload(positionConfigBytes: ByteArray): ByteArray {
        // Wrap PositionConfig in Config message (position is tag 4)
        val configPositionTag = ((4 shl 3) or WIRE_TYPE_LENGTH_DELIMITED).toByte()
        val configBytes = byteArrayOf(configPositionTag) +
                encodeVarint(positionConfigBytes.size.toLong()) +
                positionConfigBytes

        // Wrap Config in AdminMessage (set_config is tag 3, length-delimited)
        val adminSetConfigTag = ((3 shl 3) or WIRE_TYPE_LENGTH_DELIMITED).toByte()
        return byteArrayOf(adminSetConfigTag) +
                encodeVarint(configBytes.size.toLong()) +
                configBytes
    }

    // --- Incoming Packet Routing ---

    /**
     * Routes an incoming FromRadio packet based on the portnum in the decoded MeshPacket.
     *
     * Parses the FromRadio message to extract the MeshPacket, then reads the Data
     * sub-message to determine the port number and route to the appropriate SharedFlow.
     *
     * @param fromRadioBytes The raw protobuf bytes of a FromRadio message.
     */
    fun routeIncomingPacket(fromRadioBytes: ByteArray) {
        try {
            val fields = parseProtobufFields(fromRadioBytes)

            for ((fieldTag, wireType, fieldData) in fields) {
                if (fieldTag == FROM_RADIO_PACKET_TAG && wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                    // This is a MeshPacket — parse it
                    routeMeshPacket(fieldData)
                    return
                }
            }

            // Not a packet-bearing FromRadio message (could be config, nodeinfo, etc.)
            // These are handled elsewhere (handshake, etc.)
        } catch (e: Exception) {
            Log.e(TAG, "Error routing incoming packet", e)
        }
    }

    /**
     * Parses and routes a MeshPacket based on its decoded Data portnum.
     */
    private fun routeMeshPacket(meshPacketBytes: ByteArray) {
        val fields = parseProtobufFields(meshPacketBytes)

        var from: Long = 0
        var to: Long = 0
        var channel = 0
        var packetId: Int = 0
        var decodedData: ByteArray? = null

        for ((fieldTag, wireType, fieldData) in fields) {
            when (fieldTag) {
                MESH_PACKET_FROM_TAG -> {
                    if (wireType == WIRE_TYPE_FIXED32) {
                        from = readFixed32(fieldData)
                    }
                }
                MESH_PACKET_TO_TAG -> {
                    if (wireType == WIRE_TYPE_FIXED32) {
                        to = readFixed32(fieldData)
                    }
                }
                MESH_PACKET_CHANNEL_TAG -> {
                    if (wireType == WIRE_TYPE_VARINT) {
                        channel = decodeVarintValue(fieldData).toInt()
                    }
                }
                MESH_PACKET_DECODED_TAG -> {
                    if (wireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        decodedData = fieldData
                    }
                }
                MESH_PACKET_ID_TAG -> {
                    if (wireType == WIRE_TYPE_FIXED32) {
                        packetId = readFixed32(fieldData).toInt()
                    }
                }
            }
        }

        if (decodedData == null) {
            Log.d(TAG, "MeshPacket has no decoded data (encrypted?), skipping")
            return
        }

        // Check if this message is addressed to us or is a broadcast
        val myNode = localNodeNum()
        if (to != MeshtasticConstants.BROADCAST_ADDRESS && to != myNode) {
            Log.d(TAG, "Ignoring packet not addressed to us: to=${to.toString(16)}, " +
                    "myNode=${myNode.toString(16)}")
            return
        }

        // Parse the Data sub-message to get portnum and payload
        val dataFields = parseProtobufFields(decodedData)
        var portnum = 0
        var payload: ByteArray = ByteArray(0)

        for ((dataTag, dataWireType, dataFieldData) in dataFields) {
            when (dataTag) {
                DATA_PORTNUM_TAG -> {
                    if (dataWireType == WIRE_TYPE_VARINT) {
                        portnum = decodeVarintValue(dataFieldData).toInt()
                    }
                }
                DATA_PAYLOAD_TAG -> {
                    if (dataWireType == WIRE_TYPE_LENGTH_DELIMITED) {
                        payload = dataFieldData
                    }
                }
            }
        }

        val incomingPacket = IncomingPacket(
            from = from,
            to = to,
            channel = channel,
            packetId = packetId,
            portnum = portnum,
            payload = payload
        )

        // Route based on portnum
        when (portnum) {
            PORT_TEXT_MESSAGE_APP -> {
                val text = payload.toString(Charsets.UTF_8)
                val textMessage = IncomingTextMessage(
                    from = from,
                    to = to,
                    channel = channel,
                    packetId = packetId,
                    text = text
                )
                _textMessages.tryEmit(textMessage)
                Log.d(TAG, "Routed text message from ${from.toString(16)}: " +
                        "'${text.take(40)}${if (text.length > 40) "..." else ""}'")
            }
            PORT_ADMIN_APP -> {
                _adminMessages.tryEmit(incomingPacket)
                Log.d(TAG, "Routed admin message from ${from.toString(16)}: " +
                        "${payload.size} bytes")
            }
            PORT_POSITION_APP -> {
                _positionMessages.tryEmit(incomingPacket)
                Log.d(TAG, "Routed position message from ${from.toString(16)}: " +
                        "${payload.size} bytes")
            }
            PORT_TELEMETRY_APP -> {
                _telemetryMessages.tryEmit(incomingPacket)
                Log.d(TAG, "Routed telemetry message from ${from.toString(16)}: " +
                        "${payload.size} bytes")
            }
            else -> {
                _unknownMessages.tryEmit(incomingPacket)
                Log.d(TAG, "Routed unknown portnum=$portnum from ${from.toString(16)}: " +
                        "${payload.size} bytes")
            }
        }
    }

    // --- Protobuf Encoding Helpers ---

    /**
     * Encodes a Data sub-message with portnum and payload.
     *
     * Data {
     *   portnum (tag 1, varint) = portNumber
     *   payload (tag 2, length-delimited) = payloadBytes
     * }
     */
    internal fun encodeData(portNumber: Int, payloadBytes: ByteArray): ByteArray {
        // portnum field: tag 1, wire type 0 (varint)
        val portnumTagByte = ((DATA_PORTNUM_TAG shl 3) or WIRE_TYPE_VARINT).toByte()
        val portnumVarint = encodeVarint(portNumber.toLong() and 0xFFFFFFFFL)

        // payload field: tag 2, wire type 2 (length-delimited)
        val payloadTagByte = ((DATA_PAYLOAD_TAG shl 3) or WIRE_TYPE_LENGTH_DELIMITED).toByte()
        val payloadLength = encodeVarint(payloadBytes.size.toLong())

        return byteArrayOf(portnumTagByte) + portnumVarint +
                byteArrayOf(payloadTagByte) + payloadLength + payloadBytes
    }

    /**
     * Encodes a MeshPacket with destination, channel, decoded data, and packet ID.
     *
     * MeshPacket {
     *   to (tag 2, fixed32) = destination
     *   channel (tag 3, varint) = channelIndex
     *   decoded (tag 4, length-delimited) = dataBytes
     *   id (tag 6, fixed32) = packetId
     * }
     */
    internal fun encodeMeshPacket(
        destination: Long,
        channel: Int,
        dataBytes: ByteArray,
        packetId: Int
    ): ByteArray {
        // to field: tag 2, wire type 5 (fixed32)
        val toTagByte = ((MESH_PACKET_TO_TAG shl 3) or WIRE_TYPE_FIXED32).toByte()
        val toFixed32 = encodeFixed32(destination)

        // channel field: tag 3, wire type 0 (varint) — only include if non-zero
        val channelBytes = if (channel != 0) {
            val channelTagByte = ((MESH_PACKET_CHANNEL_TAG shl 3) or WIRE_TYPE_VARINT).toByte()
            val channelVarint = encodeVarint(channel.toLong() and 0xFFFFFFFFL)
            byteArrayOf(channelTagByte) + channelVarint
        } else {
            ByteArray(0)
        }

        // decoded field: tag 4, wire type 2 (length-delimited)
        val decodedTagByte = ((MESH_PACKET_DECODED_TAG shl 3) or WIRE_TYPE_LENGTH_DELIMITED).toByte()
        val decodedLength = encodeVarint(dataBytes.size.toLong())

        // id field: tag 6, wire type 5 (fixed32)
        val idTagByte = ((MESH_PACKET_ID_TAG shl 3) or WIRE_TYPE_FIXED32).toByte()
        val idFixed32 = encodeFixed32(packetId.toLong() and 0xFFFFFFFFL)

        return byteArrayOf(toTagByte) + toFixed32 +
                channelBytes +
                byteArrayOf(decodedTagByte) + decodedLength + dataBytes +
                byteArrayOf(idTagByte) + idFixed32
    }

    /**
     * Wraps a MeshPacket in a ToRadio message.
     *
     * ToRadio {
     *   packet (tag 1, length-delimited) = meshPacketBytes
     * }
     */
    internal fun encodeToRadioPacket(meshPacketBytes: ByteArray): ByteArray {
        val packetTagByte = ((TO_RADIO_PACKET_TAG shl 3) or WIRE_TYPE_LENGTH_DELIMITED).toByte()
        val packetLength = encodeVarint(meshPacketBytes.size.toLong())
        return byteArrayOf(packetTagByte) + packetLength + meshPacketBytes
    }

    // --- Protobuf Primitive Encoding ---

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

    /**
     * Encodes a value as a 4-byte little-endian fixed32.
     */
    internal fun encodeFixed32(value: Long): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    // --- Protobuf Decoding Helpers ---

    /**
     * Reads a fixed32 (4-byte little-endian) value from raw bytes.
     */
    internal fun readFixed32(bytes: ByteArray): Long {
        if (bytes.size < 4) return 0L
        return ((bytes[0].toLong() and 0xFF)) or
                ((bytes[1].toLong() and 0xFF) shl 8) or
                ((bytes[2].toLong() and 0xFF) shl 16) or
                ((bytes[3].toLong() and 0xFF) shl 24)
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
     * Parses raw protobuf bytes into a list of fields (tag, wire type, data).
     *
     * Supports wire types:
     * - 0 (varint): Variable-length integer
     * - 2 (length-delimited): Length-prefixed bytes
     * - 5 (fixed32): 4-byte little-endian value
     */
    internal fun parseProtobufFields(bytes: ByteArray): List<ProtobufField> {
        val fields = mutableListOf<ProtobufField>()
        var offset = 0

        while (offset < bytes.size) {
            if (offset >= bytes.size) break

            // Read tag (varint, but typically single byte for small field numbers)
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
                WIRE_TYPE_FIXED32 -> {
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

    // --- Packet ID Generation ---

    /**
     * Generates a random non-zero packet ID for outbound messages.
     *
     * Meshtastic requires packet IDs to be non-zero for proper deduplication.
     *
     * @return A random non-zero Int value.
     */
    fun generatePacketId(): Int {
        var id: Int
        do {
            id = Random.nextInt()
        } while (id == 0)
        return id
    }

    // --- Data Classes ---

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
     * Represents an incoming mesh packet with routing metadata.
     */
    data class IncomingPacket(
        val from: Long,
        val to: Long,
        val channel: Int,
        val packetId: Int,
        val portnum: Int,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is IncomingPacket) return false
            return from == other.from && to == other.to && channel == other.channel &&
                    packetId == other.packetId && portnum == other.portnum &&
                    payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = from.hashCode()
            result = 31 * result + to.hashCode()
            result = 31 * result + channel
            result = 31 * result + packetId
            result = 31 * result + portnum
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /**
     * Represents an incoming text message with decoded text.
     */
    data class IncomingTextMessage(
        val from: Long,
        val to: Long,
        val channel: Int,
        val packetId: Int,
        val text: String
    )

    /**
     * Exception thrown when an outbound payload exceeds the 237-byte limit.
     */
    class PayloadTooLargeException(message: String) : IllegalArgumentException(message)
}
