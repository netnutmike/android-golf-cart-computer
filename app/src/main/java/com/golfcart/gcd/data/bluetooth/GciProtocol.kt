package com.golfcart.gcd.data.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Protocol handler for GCI message envelope parsing and construction.
 *
 * The GCI protocol uses a fixed-header message envelope mirroring the ESP-NOW
 * packet structure:
 * ```
 * | type (1 byte) | timestamp (4 bytes) | seq_num (2 bytes) | data_len (2 bytes) | data (variable) |
 * ```
 *
 * All multi-byte fields use little-endian byte order (ESP-32 native).
 */
object GciProtocol {

    /** Minimum envelope size: type(1) + timestamp(4) + seq_num(2) + data_len(2) = 9 bytes. */
    const val HEADER_SIZE = 9

    /**
     * Parses a raw byte array into a [GciMessage].
     *
     * @param data The raw bytes received from the GCI Bluetooth connection.
     * @return The parsed message, or null if the data is malformed.
     */
    fun parseMessage(data: ByteArray): GciMessage? {
        if (data.size < HEADER_SIZE) {
            return null
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        // Type: 1 byte (unsigned)
        val typeCode = buffer.get().toInt() and 0xFF
        val type = GciMessageType.fromCode(typeCode) ?: return null

        // Timestamp: 4 bytes (unsigned 32-bit → stored as Long)
        val timestamp = buffer.getInt().toLong() and 0xFFFFFFFFL

        // Sequence number: 2 bytes (unsigned 16-bit)
        val sequenceNumber = buffer.getShort().toInt() and 0xFFFF

        // Data length: 2 bytes (unsigned 16-bit)
        val dataLen = buffer.getShort().toInt() and 0xFFFF

        // Validate that we have enough bytes for the declared payload
        if (data.size < HEADER_SIZE + dataLen) {
            return null
        }

        // Extract payload
        val payload = ByteArray(dataLen)
        buffer.get(payload)

        return GciMessage(
            type = type,
            timestamp = timestamp,
            sequenceNumber = sequenceNumber,
            payload = payload
        )
    }

    /**
     * Constructs a raw byte array from a [GciMessage] for transmission.
     *
     * @param message The message to serialize.
     * @return The serialized bytes ready for Bluetooth transmission.
     */
    fun buildMessage(message: GciMessage): ByteArray {
        val totalSize = HEADER_SIZE + message.payload.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Type: 1 byte
        buffer.put(message.type.code.toByte())

        // Timestamp: 4 bytes
        buffer.putInt(message.timestamp.toInt())

        // Sequence number: 2 bytes
        buffer.putShort(message.sequenceNumber.toShort())

        // Data length: 2 bytes
        buffer.putShort(message.payload.size.toShort())

        // Payload
        buffer.put(message.payload)

        return buffer.array()
    }

    /**
     * Parses a telemetry payload from a TELEMETRY message.
     *
     * Telemetry payload format (little-endian):
     * - modeLights: 4 bytes (int)
     * - outdoorLum: 4 bytes (int)
     * - airTemp: 4 bytes (float)
     * - battVolts: 4 bytes (float)
     * - fuel: 4 bytes (float)
     *
     * Total: 20 bytes
     *
     * @param payload The raw payload bytes from a TELEMETRY [GciMessage].
     * @return The parsed [TelemetryData], or null if the payload is malformed.
     */
    fun parseTelemetryPayload(payload: ByteArray): TelemetryData? {
        if (payload.size < TELEMETRY_PAYLOAD_SIZE) {
            return null
        }

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

        val modeLights = buffer.getInt()
        val outdoorLum = buffer.getInt()
        val airTemp = buffer.getFloat()
        val battVolts = buffer.getFloat()
        val fuel = buffer.getFloat()

        return TelemetryData(
            headlightMode = modeLights,
            outdoorLuminosity = outdoorLum,
            airTemperature = airTemp,
            batteryVoltage = battVolts,
            fuelLevel = fuel,
            lastUpdated = System.currentTimeMillis()
        )
    }

    /**
     * Builds a heartbeat message payload (empty payload).
     *
     * @param timestamp Current Unix timestamp in seconds.
     * @param sequenceNumber Current sequence number.
     * @return The serialized heartbeat message bytes.
     */
    fun buildHeartbeatMessage(timestamp: Long, sequenceNumber: Int): ByteArray {
        val message = GciMessage(
            type = GciMessageType.HEARTBEAT,
            timestamp = timestamp,
            sequenceNumber = sequenceNumber,
            payload = ByteArray(0)
        )
        return buildMessage(message)
    }

    /**
     * Builds a GPS data message for transmission to GCI.
     *
     * GPS payload format (little-endian):
     * - latitude: 4 bytes (float)
     * - longitude: 4 bytes (float)
     * - altitude: 4 bytes (float)
     * - speed: 4 bytes (float)
     * - heading: 4 bytes (float)
     * - satellites: 4 bytes (int)
     *
     * @param latitude Latitude in degrees.
     * @param longitude Longitude in degrees.
     * @param altitude Altitude in meters.
     * @param speed Speed in mph.
     * @param heading Heading in degrees.
     * @param satellites Number of satellites.
     * @param timestamp Current Unix timestamp in seconds.
     * @param sequenceNumber Current sequence number.
     * @return The serialized GPS data message bytes.
     */
    fun buildGpsDataMessage(
        latitude: Float,
        longitude: Float,
        altitude: Float,
        speed: Float,
        heading: Float,
        satellites: Int,
        timestamp: Long,
        sequenceNumber: Int
    ): ByteArray {
        val payloadBuffer = ByteBuffer.allocate(GPS_PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        payloadBuffer.putFloat(latitude)
        payloadBuffer.putFloat(longitude)
        payloadBuffer.putFloat(altitude)
        payloadBuffer.putFloat(speed)
        payloadBuffer.putFloat(heading)
        payloadBuffer.putInt(satellites)

        val message = GciMessage(
            type = GciMessageType.GPS_DATA,
            timestamp = timestamp,
            sequenceNumber = sequenceNumber,
            payload = payloadBuffer.array()
        )
        return buildMessage(message)
    }

    /**
     * Builds an IS_HOME status message for transmission to GCI.
     *
     * @param isHome Whether the device is within the home geofence.
     * @param timestamp Current Unix timestamp in seconds.
     * @param sequenceNumber Current sequence number.
     * @return The serialized IS_HOME message bytes.
     */
    fun buildIsHomeMessage(isHome: Boolean, timestamp: Long, sequenceNumber: Int): ByteArray {
        val payload = byteArrayOf(if (isHome) 1 else 0)
        val message = GciMessage(
            type = GciMessageType.IS_HOME,
            timestamp = timestamp,
            sequenceNumber = sequenceNumber,
            payload = payload
        )
        return buildMessage(message)
    }

    /**
     * Builds an IS_DAYTIME status message for transmission to GCI.
     *
     * @param isDaytime Whether it is currently daytime (between sunrise and sunset).
     * @param timestamp Current Unix timestamp in seconds.
     * @param sequenceNumber Current sequence number.
     * @return The serialized IS_DAYTIME message bytes.
     */
    fun buildIsDaytimeMessage(isDaytime: Boolean, timestamp: Long, sequenceNumber: Int): ByteArray {
        val payload = byteArrayOf(if (isDaytime) 1 else 0)
        val message = GciMessage(
            type = GciMessageType.IS_DAYTIME,
            timestamp = timestamp,
            sequenceNumber = sequenceNumber,
            payload = payload
        )
        return buildMessage(message)
    }

    /**
     * Builds a pairing command message for GCI peer discovery.
     *
     * The pairing command payload format (little-endian):
     * - cmdNumber: 4 bytes (int) — GCI_CMD_ADD_PEER = 1
     * - macAddress: 6 bytes — the GCD's Bluetooth MAC address
     *
     * Total payload: 10 bytes
     *
     * @param macAddress The GCD's Bluetooth MAC address as a 6-byte array.
     * @param timestamp Current Unix timestamp in seconds.
     * @param sequenceNumber Current sequence number.
     * @return The serialized pairing command message bytes.
     * @throws IllegalArgumentException if macAddress is not exactly 6 bytes.
     */
    fun buildPairingCommand(macAddress: ByteArray, timestamp: Long, sequenceNumber: Int): ByteArray {
        require(macAddress.size == MAC_ADDRESS_SIZE) {
            "MAC address must be exactly $MAC_ADDRESS_SIZE bytes, got ${macAddress.size}"
        }

        val payloadBuffer = ByteBuffer.allocate(PAIRING_PAYLOAD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        payloadBuffer.putInt(GCI_CMD_ADD_PEER)
        payloadBuffer.put(macAddress)

        val message = GciMessage(
            type = GciMessageType.COMMAND,
            timestamp = timestamp,
            sequenceNumber = sequenceNumber,
            payload = payloadBuffer.array()
        )
        return buildMessage(message)
    }

    /**
     * Parses a Bluetooth MAC address string (e.g., "AA:BB:CC:DD:EE:FF") into a 6-byte array.
     *
     * @param macString The MAC address in colon-separated hex format.
     * @return The 6-byte MAC address array.
     * @throws IllegalArgumentException if the format is invalid.
     */
    fun parseMacAddress(macString: String): ByteArray {
        val parts = macString.split(":")
        require(parts.size == MAC_ADDRESS_SIZE) {
            "Invalid MAC address format: $macString (expected 6 colon-separated hex bytes)"
        }
        return ByteArray(MAC_ADDRESS_SIZE) { i ->
            parts[i].toInt(16).toByte()
        }
    }

    /** Expected size of a telemetry payload: int + int + float + float + float = 20 bytes. */
    const val TELEMETRY_PAYLOAD_SIZE = 20

    /** Expected size of a GPS data payload: 5 floats + 1 int = 24 bytes. */
    const val GPS_PAYLOAD_SIZE = 24

    /** Expected size of a pairing command payload: int(4) + mac(6) = 10 bytes. */
    const val PAIRING_PAYLOAD_SIZE = 10

    /** MAC address size in bytes. */
    const val MAC_ADDRESS_SIZE = 6

    /** GCI command number for adding a peer (pairing). */
    const val GCI_CMD_ADD_PEER = 1
}
