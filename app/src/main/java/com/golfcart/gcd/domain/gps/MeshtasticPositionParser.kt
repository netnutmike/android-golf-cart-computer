package com.golfcart.gcd.domain.gps

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses Meshtastic POSITION_APP (port 3) protobuf payloads into [MeshtasticPosition] data.
 *
 * The payload is a protobuf-encoded `Position` message from mesh.proto with fields:
 * - latitude_i (tag 1, sfixed32): latitude in degrees * 1e-7
 * - longitude_i (tag 2, sfixed32): longitude in degrees * 1e-7
 * - altitude (tag 3, varint): meters above MSL
 * - time (tag 4, fixed32): seconds since 1970
 * - ground_speed (tag 15, varint): ground speed in m/s
 * - ground_track (tag 16, varint): true north track in 1/100 degrees
 * - sats_in_view (tag 19, varint): satellites in view
 * - HDOP (tag 12, varint): horizontal dilution of precision in 1/100 units
 * - timestamp (tag 7, fixed32): positional timestamp in epoch seconds
 *
 * Requirements: 5.2
 */
@Singleton
class MeshtasticPositionParser @Inject constructor() {

    companion object {
        private const val TAG = "MeshPositionParser"

        // Position message field tags
        private const val FIELD_LATITUDE_I = 1       // sfixed32
        private const val FIELD_LONGITUDE_I = 2      // sfixed32
        private const val FIELD_ALTITUDE = 3         // int32 (varint)
        private const val FIELD_TIME = 4             // fixed32
        private const val FIELD_TIMESTAMP = 7        // fixed32
        private const val FIELD_HDOP = 12            // uint32 (varint)
        private const val FIELD_GROUND_SPEED = 15    // uint32 (varint)
        private const val FIELD_GROUND_TRACK = 16    // uint32 (varint)
        private const val FIELD_SATS_IN_VIEW = 19    // uint32 (varint)

        // Protobuf wire types
        private const val WIRE_TYPE_VARINT = 0
        private const val WIRE_TYPE_FIXED64 = 1
        private const val WIRE_TYPE_LENGTH_DELIMITED = 2
        private const val WIRE_TYPE_FIXED32 = 5

        /** Conversion factor: Meshtastic stores lat/lon as degrees * 1e7. */
        private const val POSITION_SCALE = 1e-7
    }

    /**
     * Parse a Meshtastic POSITION_APP protobuf payload into a [MeshtasticPosition].
     *
     * @param payload The raw protobuf bytes from the POSITION_APP packet.
     * @return The parsed position data, or null if the payload is invalid or empty.
     */
    fun parse(payload: ByteArray): MeshtasticPosition? {
        if (payload.isEmpty()) {
            Log.d(TAG, "Empty position payload, skipping")
            return null
        }

        try {
            var latitudeI: Int = 0
            var longitudeI: Int = 0
            var altitude: Int = 0
            var time: Long = 0
            var timestamp: Long = 0
            var groundSpeed: Int = 0
            var groundTrack: Int = 0
            var satsInView: Int = 0
            var hdop: Int = 0

            var offset = 0
            while (offset < payload.size) {
                val tagResult = readVarintAt(payload, offset)
                val tagValue = tagResult.first.toInt()
                offset = tagResult.second

                val wireType = tagValue and 0x07
                val fieldNumber = tagValue ushr 3

                when (wireType) {
                    WIRE_TYPE_VARINT -> {
                        val varintResult = readVarintAt(payload, offset)
                        offset = varintResult.second
                        val value = varintResult.first

                        when (fieldNumber) {
                            FIELD_ALTITUDE -> altitude = value.toInt()
                            FIELD_HDOP -> hdop = value.toInt()
                            FIELD_GROUND_SPEED -> groundSpeed = value.toInt()
                            FIELD_GROUND_TRACK -> groundTrack = value.toInt()
                            FIELD_SATS_IN_VIEW -> satsInView = value.toInt()
                        }
                    }
                    WIRE_TYPE_FIXED32 -> {
                        if (offset + 4 > payload.size) break
                        val fixed32Value = readFixed32(payload, offset)
                        offset += 4

                        when (fieldNumber) {
                            FIELD_LATITUDE_I -> latitudeI = fixed32Value
                            FIELD_LONGITUDE_I -> longitudeI = fixed32Value
                            FIELD_TIME -> time = fixed32Value.toLong() and 0xFFFFFFFFL
                            FIELD_TIMESTAMP -> timestamp = fixed32Value.toLong() and 0xFFFFFFFFL
                        }
                    }
                    WIRE_TYPE_FIXED64 -> {
                        // Skip 8 bytes
                        offset += 8
                    }
                    WIRE_TYPE_LENGTH_DELIMITED -> {
                        val lengthResult = readVarintAt(payload, offset)
                        val length = lengthResult.first.toInt()
                        offset = lengthResult.second + length
                    }
                    else -> {
                        // Unknown wire type, cannot continue parsing safely
                        Log.w(TAG, "Unknown wire type $wireType at field $fieldNumber")
                        break
                    }
                }
            }

            // Validate: at minimum we need a non-zero lat or lon
            if (latitudeI == 0 && longitudeI == 0) {
                Log.d(TAG, "Position has zero lat/lon, treating as invalid")
                return null
            }

            val latitude = latitudeI * POSITION_SCALE
            val longitude = longitudeI * POSITION_SCALE

            // Use positional timestamp if available, otherwise fall back to time field
            val positionTimestamp = if (timestamp > 0) timestamp else time
            val timestampMillis = if (positionTimestamp > 0) positionTimestamp * 1000L else 0L

            val position = MeshtasticPosition(
                latitude = latitude,
                longitude = longitude,
                altitude = altitude.toDouble(),
                groundSpeedMps = groundSpeed.toFloat(),
                groundTrackDegrees = groundTrack / 100.0f,
                satelliteCount = satsInView,
                hdop = if (hdop > 0) hdop / 100.0f else 0f,
                timestampMillis = timestampMillis
            )

            Log.d(TAG, "Parsed position: lat=${"%.6f".format(latitude)}, " +
                    "lon=${"%.6f".format(longitude)}, alt=$altitude, " +
                    "speed=${groundSpeed}m/s, sats=$satsInView")

            return position
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing position payload: ${e.message}", e)
            return null
        }
    }

    /**
     * Read a signed fixed32 (4-byte little-endian) value from the byte array at the given offset.
     */
    private fun readFixed32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF)) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * Read a varint starting at the given offset.
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

/**
 * Parsed position data from a Meshtastic POSITION_APP packet.
 *
 * This represents GPS data received from the connected Meshtastic radio,
 * used as a secondary GPS source when Android's internal GPS is unavailable
 * or to supplement position data.
 */
data class MeshtasticPosition(
    /** Latitude in decimal degrees. */
    val latitude: Double,

    /** Longitude in decimal degrees. */
    val longitude: Double,

    /** Altitude in meters above MSL. */
    val altitude: Double,

    /** Ground speed in meters per second. */
    val groundSpeedMps: Float,

    /** Ground track (heading) in degrees (0-360). */
    val groundTrackDegrees: Float,

    /** Number of satellites in view. */
    val satelliteCount: Int,

    /** Horizontal Dilution of Precision (0 if unavailable). */
    val hdop: Float,

    /** UTC timestamp in milliseconds (0 if unavailable). */
    val timestampMillis: Long
)
