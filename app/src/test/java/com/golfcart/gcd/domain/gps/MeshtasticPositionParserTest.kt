package com.golfcart.gcd.domain.gps

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MeshtasticPositionParser] — decoding Meshtastic POSITION_APP protobuf payloads.
 *
 * Tests cover:
 * - Parsing valid position payloads with all fields
 * - Parsing minimal position payloads (lat/lon only)
 * - Handling empty payloads
 * - Handling zero lat/lon as invalid
 * - Correct scaling of latitude/longitude (1e-7)
 * - Correct scaling of HDOP (1/100)
 * - Correct scaling of ground_track (1/100 degrees)
 * - Timestamp handling (positional timestamp vs time field)
 */
class MeshtasticPositionParserTest {

    private lateinit var parser: MeshtasticPositionParser

    @BeforeEach
    fun setUp() {
        parser = MeshtasticPositionParser()
    }

    @Test
    fun `empty payload returns null`() {
        val result = parser.parse(ByteArray(0))
        assertNull(result)
    }

    @Test
    fun `zero lat and lon returns null`() {
        // Encode a Position with latitude_i=0, longitude_i=0
        val payload = encodePosition(latitudeI = 0, longitudeI = 0)
        val result = parser.parse(payload)
        assertNull(result)
    }

    @Test
    fun `valid position with lat and lon is parsed correctly`() {
        // latitude_i = 289000000 → 28.9 degrees
        // longitude_i = -819000000 → -81.9 degrees
        val payload = encodePosition(
            latitudeI = 289000000,
            longitudeI = -819000000
        )
        val result = parser.parse(payload)

        assertNotNull(result)
        assertEquals(28.9, result!!.latitude, 0.0001)
        assertEquals(-81.9, result.longitude, 0.0001)
    }

    @Test
    fun `altitude is parsed correctly`() {
        val payload = encodePosition(
            latitudeI = 289000000,
            longitudeI = -819000000,
            altitude = 42
        )
        val result = parser.parse(payload)

        assertNotNull(result)
        assertEquals(42.0, result!!.altitude, 0.01)
    }

    @Test
    fun `ground speed is parsed correctly`() {
        val payload = encodePosition(
            latitudeI = 289000000,
            longitudeI = -819000000,
            groundSpeed = 5 // 5 m/s
        )
        val result = parser.parse(payload)

        assertNotNull(result)
        assertEquals(5.0f, result!!.groundSpeedMps, 0.01f)
    }

    @Test
    fun `ground track is parsed and scaled correctly`() {
        // ground_track = 18000 → 180.00 degrees
        val payload = encodePosition(
            latitudeI = 289000000,
            longitudeI = -819000000,
            groundTrack = 18000
        )
        val result = parser.parse(payload)

        assertNotNull(result)
        assertEquals(180.0f, result!!.groundTrackDegrees, 0.01f)
    }

    @Test
    fun `satellites in view is parsed correctly`() {
        val payload = encodePosition(
            latitudeI = 289000000,
            longitudeI = -819000000,
            satsInView = 12
        )
        val result = parser.parse(payload)

        assertNotNull(result)
        assertEquals(12, result!!.satelliteCount)
    }

    @Test
    fun `HDOP is parsed and scaled correctly`() {
        // HDOP = 150 → 1.50
        val payload = encodePosition(
            latitudeI = 289000000,
            longitudeI = -819000000,
            hdop = 150
        )
        val result = parser.parse(payload)

        assertNotNull(result)
        assertEquals(1.5f, result!!.hdop, 0.01f)
    }

    @Test
    fun `timestamp from positional timestamp field is used`() {
        val epochSeconds = 1700000000L // Some timestamp
        val payload = encodePosition(
            latitudeI = 289000000,
            longitudeI = -819000000,
            timestamp = epochSeconds.toInt()
        )
        val result = parser.parse(payload)

        assertNotNull(result)
        assertEquals(epochSeconds * 1000L, result!!.timestampMillis)
    }

    @Test
    fun `time field is used as fallback when timestamp is zero`() {
        val epochSeconds = 1700000000L
        val payload = encodePosition(
            latitudeI = 289000000,
            longitudeI = -819000000,
            time = epochSeconds.toInt(),
            timestamp = 0
        )
        val result = parser.parse(payload)

        assertNotNull(result)
        assertEquals(epochSeconds * 1000L, result!!.timestampMillis)
    }

    @Test
    fun `full position with all fields is parsed correctly`() {
        val payload = encodePosition(
            latitudeI = 289123456,
            longitudeI = -819876543,
            altitude = 30,
            time = 1700000000,
            timestamp = 1700000001,
            groundSpeed = 8,
            groundTrack = 9000, // 90.00 degrees
            satsInView = 10,
            hdop = 120 // 1.20
        )
        val result = parser.parse(payload)

        assertNotNull(result)
        assertEquals(28.9123456, result!!.latitude, 0.0000001)
        assertEquals(-81.9876543, result.longitude, 0.0000001)
        assertEquals(30.0, result.altitude, 0.01)
        assertEquals(8.0f, result.groundSpeedMps, 0.01f)
        assertEquals(90.0f, result.groundTrackDegrees, 0.01f)
        assertEquals(10, result.satelliteCount)
        assertEquals(1.2f, result.hdop, 0.01f)
        // Positional timestamp (tag 7) takes priority over time (tag 4)
        assertEquals(1700000001L * 1000L, result.timestampMillis)
    }

    @Test
    fun `zero HDOP results in zero hdop field`() {
        val payload = encodePosition(
            latitudeI = 289000000,
            longitudeI = -819000000,
            hdop = 0
        )
        val result = parser.parse(payload)

        assertNotNull(result)
        assertEquals(0f, result!!.hdop)
    }

    // --- Protobuf encoding helpers for test data ---

    /**
     * Manually encode a Meshtastic Position protobuf message for testing.
     *
     * Field encoding:
     * - latitude_i (tag 1, sfixed32 = wire type 5)
     * - longitude_i (tag 2, sfixed32 = wire type 5)
     * - altitude (tag 3, int32 = wire type 0, varint)
     * - time (tag 4, fixed32 = wire type 5)
     * - timestamp (tag 7, fixed32 = wire type 5)
     * - HDOP (tag 12, uint32 = wire type 0, varint)
     * - ground_speed (tag 15, uint32 = wire type 0, varint)
     * - ground_track (tag 16, uint32 = wire type 0, varint)
     * - sats_in_view (tag 19, uint32 = wire type 0, varint)
     */
    private fun encodePosition(
        latitudeI: Int = 0,
        longitudeI: Int = 0,
        altitude: Int = 0,
        time: Int = 0,
        timestamp: Int = 0,
        groundSpeed: Int = 0,
        groundTrack: Int = 0,
        satsInView: Int = 0,
        hdop: Int = 0
    ): ByteArray {
        val bytes = mutableListOf<Byte>()

        // latitude_i: tag 1, wire type 5 (sfixed32)
        if (latitudeI != 0) {
            bytes.add(((1 shl 3) or 5).toByte()) // tag byte
            bytes.addAll(encodeFixed32(latitudeI))
        }

        // longitude_i: tag 2, wire type 5 (sfixed32)
        if (longitudeI != 0) {
            bytes.add(((2 shl 3) or 5).toByte())
            bytes.addAll(encodeFixed32(longitudeI))
        }

        // altitude: tag 3, wire type 0 (varint)
        if (altitude != 0) {
            bytes.add(((3 shl 3) or 0).toByte())
            bytes.addAll(encodeVarint(altitude.toLong()))
        }

        // time: tag 4, wire type 5 (fixed32)
        if (time != 0) {
            bytes.add(((4 shl 3) or 5).toByte())
            bytes.addAll(encodeFixed32(time))
        }

        // timestamp: tag 7, wire type 5 (fixed32)
        if (timestamp != 0) {
            bytes.add(((7 shl 3) or 5).toByte())
            bytes.addAll(encodeFixed32(timestamp))
        }

        // HDOP: tag 12, wire type 0 (varint)
        if (hdop != 0) {
            bytes.add(((12 shl 3) or 0).toByte())
            bytes.addAll(encodeVarint(hdop.toLong()))
        }

        // ground_speed: tag 15, wire type 0 (varint)
        if (groundSpeed != 0) {
            bytes.add(((15 shl 3) or 0).toByte())
            bytes.addAll(encodeVarint(groundSpeed.toLong()))
        }

        // ground_track: tag 16, wire type 0 (varint)
        if (groundTrack != 0) {
            bytes.addAll(encodeVarint(((16 shl 3) or 0).toLong()))
            bytes.addAll(encodeVarint(groundTrack.toLong()))
        }

        // sats_in_view: tag 19, wire type 0 (varint)
        if (satsInView != 0) {
            bytes.addAll(encodeVarint(((19 shl 3) or 0).toLong()))
            bytes.addAll(encodeVarint(satsInView.toLong()))
        }

        return bytes.toByteArray()
    }

    private fun encodeFixed32(value: Int): List<Byte> {
        return listOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun encodeVarint(value: Long): List<Byte> {
        if (value == 0L) return listOf(0.toByte())
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
        return bytes
    }
}
