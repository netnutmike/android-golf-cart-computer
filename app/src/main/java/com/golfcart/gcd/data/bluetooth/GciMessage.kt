package com.golfcart.gcd.data.bluetooth

/**
 * Message envelope for GCI communication protocol.
 *
 * Mirrors the ESP-NOW packet structure used by the GCI ESP-32 computer.
 * The wire format is:
 * ```
 * | type (1 byte) | timestamp (4 bytes) | seq_num (2 bytes) | data_len (2 bytes) | data (variable) |
 * ```
 *
 * All multi-byte fields are little-endian (matching ESP-32 native byte order).
 */
data class GciMessage(
    /** Message type code identifying the payload content. */
    val type: GciMessageType,

    /** Unix timestamp in seconds when the message was created. */
    val timestamp: Long,

    /** Sequence number for message ordering and deduplication. */
    val sequenceNumber: Int,

    /** Raw payload bytes (content depends on [type]). */
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GciMessage) return false
        return type == other.type &&
            timestamp == other.timestamp &&
            sequenceNumber == other.sequenceNumber &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + sequenceNumber
        result = 31 * result + payload.contentHashCode()
        return result
    }

    override fun toString(): String {
        return "GciMessage(type=$type, timestamp=$timestamp, seq=$sequenceNumber, dataLen=${payload.size})"
    }
}

/**
 * GCI message type codes identifying the content of a [GciMessage] payload.
 */
enum class GciMessageType(val code: Int) {
    /** Plain text message. */
    TEXT(0),

    /** GPS data payload (lat, lon, alt, speed, heading, sats). */
    GPS_DATA(1),

    /** Telemetry data from GCI sensors (modeLights, lum, temp, volts, fuel). */
    TELEMETRY(2),

    /** Command message (e.g., pairing command with MAC address). */
    COMMAND(3),

    /** Acknowledgment response. */
    ACK(4),

    /** Heartbeat keep-alive message. */
    HEARTBEAT(5),

    /** "Is at home" status notification. */
    IS_HOME(6),

    /** "Is daytime" status notification. */
    IS_DAYTIME(7);

    companion object {
        /**
         * Looks up a [GciMessageType] by its wire code.
         *
         * @param code The 1-byte type code from the message envelope.
         * @return The matching type, or null if the code is unknown.
         */
        fun fromCode(code: Int): GciMessageType? =
            entries.firstOrNull { it.code == code }
    }
}
