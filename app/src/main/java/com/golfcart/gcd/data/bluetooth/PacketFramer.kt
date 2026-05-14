package com.golfcart.gcd.data.bluetooth

/**
 * Utility class for Meshtastic BLE packet framing.
 *
 * The Meshtastic BLE protocol requires outbound ToRadio messages to be framed
 * with a 4-byte big-endian length prefix before writing to the TORADIO characteristic.
 * Large framed packets must be split into chunks that fit within the negotiated MTU.
 *
 * Frame format: [4-byte big-endian length][protobuf payload bytes]
 *
 * Validates: Requirements 1.4, 1.5, 18.2, 18.3
 */
object PacketFramer {

    /** Size of the length prefix in bytes. */
    const val LENGTH_PREFIX_SIZE = 4

    /**
     * Frames a protobuf-encoded byte array with a 4-byte big-endian length prefix.
     *
     * The length prefix encodes the size of the payload (not including the prefix itself).
     *
     * @param protobufBytes The protobuf-encoded message bytes to frame.
     * @return A new byte array containing [4-byte length prefix][protobufBytes].
     * @throws IllegalArgumentException if protobufBytes is empty.
     */
    fun frame(protobufBytes: ByteArray): ByteArray {
        require(protobufBytes.isNotEmpty()) { "Cannot frame an empty payload" }

        val length = protobufBytes.size
        val framed = ByteArray(LENGTH_PREFIX_SIZE + length)

        // Write 4-byte big-endian length prefix
        framed[0] = ((length shr 24) and 0xFF).toByte()
        framed[1] = ((length shr 16) and 0xFF).toByte()
        framed[2] = ((length shr 8) and 0xFF).toByte()
        framed[3] = (length and 0xFF).toByte()

        // Copy payload after prefix
        protobufBytes.copyInto(framed, destinationOffset = LENGTH_PREFIX_SIZE)

        return framed
    }

    /**
     * Unframes a length-prefixed byte array, extracting the payload.
     *
     * Reads the 4-byte big-endian length prefix and returns the payload bytes.
     *
     * @param framedBytes The framed byte array (length prefix + payload).
     * @return The extracted payload bytes.
     * @throws IllegalArgumentException if framedBytes is too short or the length prefix
     *         indicates a size that doesn't match the available data.
     */
    fun unframe(framedBytes: ByteArray): ByteArray {
        require(framedBytes.size >= LENGTH_PREFIX_SIZE) {
            "Framed data too short: need at least $LENGTH_PREFIX_SIZE bytes, got ${framedBytes.size}"
        }

        // Read 4-byte big-endian length prefix
        val length = ((framedBytes[0].toInt() and 0xFF) shl 24) or
                ((framedBytes[1].toInt() and 0xFF) shl 16) or
                ((framedBytes[2].toInt() and 0xFF) shl 8) or
                (framedBytes[3].toInt() and 0xFF)

        require(length >= 0) { "Invalid length prefix: $length" }
        require(framedBytes.size >= LENGTH_PREFIX_SIZE + length) {
            "Framed data incomplete: length prefix indicates $length bytes but only " +
                    "${framedBytes.size - LENGTH_PREFIX_SIZE} bytes available"
        }

        return framedBytes.copyOfRange(LENGTH_PREFIX_SIZE, LENGTH_PREFIX_SIZE + length)
    }

    /**
     * Splits a framed byte array into chunks that fit within the BLE MTU payload size.
     *
     * Each chunk will be at most [mtuPayloadSize] bytes. The chunks, when concatenated
     * in order, reconstruct the original framed byte array.
     *
     * The MTU payload size is typically (negotiated MTU - 3) bytes. Without MTU
     * negotiation, the default safe payload size is 20 bytes.
     *
     * @param framedBytes The complete framed packet (length prefix + payload).
     * @param mtuPayloadSize The maximum number of bytes per BLE write operation.
     *        Defaults to [MeshtasticConstants.DEFAULT_PAYLOAD_SIZE] (20 bytes).
     * @return A list of byte array chunks, each at most [mtuPayloadSize] bytes.
     * @throws IllegalArgumentException if mtuPayloadSize is less than 1.
     */
    fun splitForMtu(
        framedBytes: ByteArray,
        mtuPayloadSize: Int = MeshtasticConstants.DEFAULT_PAYLOAD_SIZE
    ): List<ByteArray> {
        require(mtuPayloadSize >= 1) { "MTU payload size must be at least 1, got $mtuPayloadSize" }

        if (framedBytes.isEmpty()) {
            return emptyList()
        }

        // If the entire framed packet fits in one write, no splitting needed
        if (framedBytes.size <= mtuPayloadSize) {
            return listOf(framedBytes)
        }

        // Split into chunks of mtuPayloadSize
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < framedBytes.size) {
            val chunkSize = minOf(mtuPayloadSize, framedBytes.size - offset)
            chunks.add(framedBytes.copyOfRange(offset, offset + chunkSize))
            offset += chunkSize
        }

        return chunks
    }
}
