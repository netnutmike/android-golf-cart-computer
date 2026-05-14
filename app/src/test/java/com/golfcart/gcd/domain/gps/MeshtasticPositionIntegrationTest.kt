package com.golfcart.gcd.domain.gps

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for Meshtastic position data integration in [GpsProcessorImpl].
 *
 * Tests cover:
 * - Meshtastic position is used when Android GPS has never been received
 * - Meshtastic position is used when Android GPS is stale (> 10 seconds)
 * - Meshtastic position is ignored when Android GPS is fresh (< 10 seconds)
 * - Speed filtering pipeline is applied to Meshtastic speed data
 * - Meshtastic HDOP is used directly when available
 * - HDOP is estimated from satellite count when Meshtastic HDOP is zero
 * - Heading from Meshtastic is used when available
 * - NavigationData is updated from Meshtastic position
 *
 * Requirements: 5.2
 */
class MeshtasticPositionIntegrationTest {

    private lateinit var processor: GpsProcessorImpl

    @BeforeEach
    fun setUp() {
        processor = GpsProcessorImpl(NavigationDataProcessor())
    }

    @Test
    fun `meshtastic position is used when android GPS has never been received`() {
        val position = MeshtasticPosition(
            latitude = 28.9,
            longitude = -81.9,
            altitude = 30.0,
            groundSpeedMps = 0f,
            groundTrackDegrees = 90f,
            satelliteCount = 8,
            hdop = 1.5f,
            timestampMillis = 1700000000000L
        )

        processor.onMeshtasticPositionUpdate(position)

        val state = processor.gpsState.value
        assertEquals(28.9, state.latitude, 0.0001)
        assertEquals(-81.9, state.longitude, 0.0001)
        assertEquals(30.0, state.altitude, 0.01)
        assertTrue(state.isValid)
    }

    @Test
    fun `meshtastic position is ignored when android GPS is fresh`() {
        // First, provide a fresh Android GPS update
        processor.onLocationUpdate(
            latitude = 28.8,
            longitude = -81.8,
            altitude = 25.0,
            speedMps = 0f,
            bearing = 0f,
            accuracy = 5f,
            satellites = 10,
            timestamp = System.currentTimeMillis(),
            hasSpeed = true,
            hasBearing = true
        )

        // Now try a Meshtastic position update — should be ignored
        val position = MeshtasticPosition(
            latitude = 28.9,
            longitude = -81.9,
            altitude = 30.0,
            groundSpeedMps = 0f,
            groundTrackDegrees = 90f,
            satelliteCount = 8,
            hdop = 1.5f,
            timestampMillis = 1700000000000L
        )

        processor.onMeshtasticPositionUpdate(position)

        // State should still reflect the Android GPS data
        val state = processor.gpsState.value
        assertEquals(28.8, state.latitude, 0.0001)
        assertEquals(-81.8, state.longitude, 0.0001)
    }

    @Test
    fun `meshtastic position is used when android GPS is stale`() {
        // Simulate a stale Android GPS by setting the internal timestamp to the past
        processor.onLocationUpdate(
            latitude = 28.8,
            longitude = -81.8,
            altitude = 25.0,
            speedMps = 0f,
            bearing = 0f,
            accuracy = 5f,
            satellites = 10,
            timestamp = System.currentTimeMillis() - 20000, // 20 seconds ago
            hasSpeed = true,
            hasBearing = true
        )

        // Force the lastAndroidGpsTimestamp to be stale via reflection
        val field = GpsProcessorImpl::class.java.getDeclaredField("lastAndroidGpsTimestamp")
        field.isAccessible = true
        field.setLong(processor, System.currentTimeMillis() - 15000L) // 15 seconds ago (> 10s threshold)

        // Now Meshtastic position should be accepted
        val position = MeshtasticPosition(
            latitude = 28.9,
            longitude = -81.9,
            altitude = 30.0,
            groundSpeedMps = 0f,
            groundTrackDegrees = 180f,
            satelliteCount = 6,
            hdop = 2.0f,
            timestampMillis = System.currentTimeMillis()
        )

        processor.onMeshtasticPositionUpdate(position)

        val state = processor.gpsState.value
        assertEquals(28.9, state.latitude, 0.0001)
        assertEquals(-81.9, state.longitude, 0.0001)
        assertEquals(30.0, state.altitude, 0.01)
    }

    @Test
    fun `meshtastic speed is filtered through the same pipeline`() {
        // Meshtastic reports speed below dither threshold (2.5 mph)
        // 1 m/s ≈ 2.24 mph — just below threshold
        val position = MeshtasticPosition(
            latitude = 28.9,
            longitude = -81.9,
            altitude = 30.0,
            groundSpeedMps = 1.0f, // ~2.24 mph, below 2.5 threshold
            groundTrackDegrees = 90f,
            satelliteCount = 8,
            hdop = 1.5f,
            timestampMillis = 1700000000000L
        )

        processor.onMeshtasticPositionUpdate(position)

        val state = processor.gpsState.value
        assertEquals(0, state.speedMph) // Filtered to zero (GPS dither)
    }

    @Test
    fun `meshtastic HDOP is used directly when available`() {
        val position = MeshtasticPosition(
            latitude = 28.9,
            longitude = -81.9,
            altitude = 30.0,
            groundSpeedMps = 0f,
            groundTrackDegrees = 0f,
            satelliteCount = 8,
            hdop = 1.2f, // Direct HDOP from Meshtastic
            timestampMillis = 1700000000000L
        )

        processor.onMeshtasticPositionUpdate(position)

        val state = processor.gpsState.value
        assertEquals(1.2f, state.hdop, 0.01f)
    }

    @Test
    fun `HDOP is estimated from satellite count when meshtastic HDOP is zero`() {
        val position = MeshtasticPosition(
            latitude = 28.9,
            longitude = -81.9,
            altitude = 30.0,
            groundSpeedMps = 0f,
            groundTrackDegrees = 0f,
            satelliteCount = 5,
            hdop = 0f, // No HDOP available
            timestampMillis = 1700000000000L
        )

        processor.onMeshtasticPositionUpdate(position)

        val state = processor.gpsState.value
        // 4-5 satellites → estimated HDOP = 2.0
        assertEquals(2.0f, state.hdop, 0.01f)
    }

    @Test
    fun `meshtastic heading is used when ground track is available`() {
        val position = MeshtasticPosition(
            latitude = 28.9,
            longitude = -81.9,
            altitude = 30.0,
            groundSpeedMps = 0f,
            groundTrackDegrees = 225f, // SW
            satelliteCount = 8,
            hdop = 1.5f,
            timestampMillis = 1700000000000L
        )

        processor.onMeshtasticPositionUpdate(position)

        val state = processor.gpsState.value
        assertEquals(225f, state.headingDegrees, 0.01f)
        assertEquals("SW", state.cardinalDirection)
    }

    @Test
    fun `meshtastic position with zero ground track retains previous heading`() {
        // First set a heading via Android GPS
        processor.onLocationUpdate(
            latitude = 28.8, longitude = -81.8, altitude = 25.0,
            speedMps = 0f, bearing = 90f, accuracy = 5f,
            satellites = 10, timestamp = 1000L,
            hasSpeed = true, hasBearing = true
        )

        // Force stale Android GPS
        val field = GpsProcessorImpl::class.java.getDeclaredField("lastAndroidGpsTimestamp")
        field.isAccessible = true
        field.setLong(processor, System.currentTimeMillis() - 15000L)

        // Meshtastic position with zero ground track
        val position = MeshtasticPosition(
            latitude = 28.9,
            longitude = -81.9,
            altitude = 30.0,
            groundSpeedMps = 0f,
            groundTrackDegrees = 0f, // No heading data
            satelliteCount = 8,
            hdop = 1.5f,
            timestampMillis = 1700000000000L
        )

        processor.onMeshtasticPositionUpdate(position)

        val state = processor.gpsState.value
        // Should retain the previous heading from Android GPS (90 degrees = E)
        assertEquals(90f, state.headingDegrees, 0.01f)
        assertEquals("E", state.cardinalDirection)
    }

    @Test
    fun `meshtastic position with invalid lat lon is marked as not valid`() {
        val position = MeshtasticPosition(
            latitude = 0.0,
            longitude = 0.0,
            altitude = 0.0,
            groundSpeedMps = 0f,
            groundTrackDegrees = 0f,
            satelliteCount = 0,
            hdop = 0f,
            timestampMillis = 0L
        )

        processor.onMeshtasticPositionUpdate(position)

        val state = processor.gpsState.value
        assertFalse(state.isValid)
    }

    @Test
    fun `meshtastic position updates satellite HDOP display format`() {
        val position = MeshtasticPosition(
            latitude = 28.9,
            longitude = -81.9,
            altitude = 30.0,
            groundSpeedMps = 0f,
            groundTrackDegrees = 0f,
            satelliteCount = 10,
            hdop = 1.1f,
            timestampMillis = 1700000000000L
        )

        processor.onMeshtasticPositionUpdate(position)

        val state = processor.gpsState.value
        assertEquals("10/1.10", state.satelliteHdopDisplay)
    }
}
