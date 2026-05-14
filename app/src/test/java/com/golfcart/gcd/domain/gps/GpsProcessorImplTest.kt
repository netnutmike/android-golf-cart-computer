package com.golfcart.gcd.domain.gps

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [GpsProcessorImpl] — the GPS speed filtering pipeline.
 *
 * Tests cover:
 * - GPS dither elimination (speeds < 2.5 mph → zero)
 * - Speed spike rejection (> 8 mph/s acceleration)
 * - Responsive stop detection (< 4 mph and decreasing → zero)
 * - Consecutive readings threshold (2 normal, 3 dimmed)
 * - Invalid speed fallback logic
 * - Cardinal direction mapping
 * - HDOP estimation
 * - Satellite count debounce
 * - Satellite/HDOP display formatting
 * - StateFlow emission
 */
class GpsProcessorImplTest {

    private lateinit var processor: GpsProcessorImpl

    @BeforeEach
    fun setUp() {
        processor = GpsProcessorImpl(NavigationDataProcessor())
    }

    // --- Step 1: GPS dither elimination ---

    @Test
    fun `speeds below 2_5 mph are filtered to zero`() {
        val result = processor.applySpeedFilter(2.0f, 1000L)
        assertEquals(0f, result)
    }

    @Test
    fun `speed exactly at 2_5 mph is NOT filtered to zero`() {
        // 2.5 is not below 2.5, so it should pass through dither filter
        // But it still needs consecutive readings to report movement
        val result = processor.applySpeedFilter(2.5f, 1000L)
        // First reading above threshold — not yet reporting movement
        assertEquals(0f, result)
    }

    @Test
    fun `speed of zero is filtered to zero`() {
        val result = processor.applySpeedFilter(0f, 1000L)
        assertEquals(0f, result)
    }

    @Test
    fun `speed of 1 mph is filtered to zero`() {
        val result = processor.applySpeedFilter(1.0f, 1000L)
        assertEquals(0f, result)
    }

    // --- Step 2: Speed spike rejection ---

    @Test
    fun `speed spike exceeding 8 mph per second is rejected`() {
        // First reading at 5 mph
        processor.applySpeedFilter(5.0f, 1000L)
        // Simulate internal state update
        setProcessorState(previousRawSpeed = 5.0f, previousTimestamp = 1000L)

        // Second reading at 20 mph, 1 second later → 15 mph/s acceleration
        val result = processor.applySpeedFilter(20.0f, 2000L)
        // Should retain previous filtered speed (which was 0 since first reading didn't meet consecutive threshold)
        assertEquals(0f, result)
    }

    @Test
    fun `speed change within 8 mph per second is accepted`() {
        // Build up consecutive readings first
        processor.applySpeedFilter(5.0f, 1000L)
        setProcessorState(previousRawSpeed = 5.0f, previousTimestamp = 1000L, previousFiltered = 0f)
        processor.applySpeedFilter(5.0f, 2000L)
        setProcessorState(previousRawSpeed = 5.0f, previousTimestamp = 2000L, previousFiltered = 5.0f, consecutive = 2, reporting = true)

        // Now a reasonable acceleration: 5 → 12 mph in 1 second = 7 mph/s (under 8)
        val result = processor.applySpeedFilter(12.0f, 3000L)
        assertEquals(12.0f, result)
    }

    // --- Step 3: Responsive stop detection ---

    @Test
    fun `speed below 4 mph and decreasing reports zero`() {
        // Set previous raw speed to 3.5 mph
        setProcessorState(previousRawSpeed = 3.5f, previousTimestamp = 1000L)

        // Current speed 3.0 mph (below 4 and decreasing)
        val result = processor.applySpeedFilter(3.0f, 2000L)
        assertEquals(0f, result)
    }

    @Test
    fun `speed below 4 mph but increasing does not trigger stop detection`() {
        // Set previous raw speed to 2.5 mph
        setProcessorState(previousRawSpeed = 2.5f, previousTimestamp = 1000L)

        // Current speed 3.5 mph (below 4 but increasing) — still needs consecutive readings
        val result = processor.applySpeedFilter(3.5f, 2000L)
        // First reading above threshold, not yet reporting
        assertEquals(0f, result)
    }

    @Test
    fun `speed at 4 mph and decreasing does not trigger stop detection`() {
        // 4.0 is not below 4.0, so stop detection doesn't apply
        setProcessorState(previousRawSpeed = 5.0f, previousTimestamp = 1000L)

        val result = processor.applySpeedFilter(4.0f, 2000L)
        // First reading above threshold, not yet reporting
        assertEquals(0f, result)
    }

    // --- Step 4: Consecutive readings threshold ---

    @Test
    fun `requires 2 consecutive readings above threshold before reporting movement`() {
        // First reading at 10 mph
        val first = processor.applySpeedFilter(10.0f, 1000L)
        assertEquals(0f, first) // Not yet reporting

        setProcessorState(previousRawSpeed = 10.0f, previousTimestamp = 1000L, previousFiltered = 0f)

        // Second reading at 10 mph
        val second = processor.applySpeedFilter(10.0f, 2000L)
        assertEquals(10.0f, second) // Now reporting
    }

    @Test
    fun `requires 3 consecutive readings when dimmed`() {
        processor.setDimmed(true)

        // First reading at 10 mph
        val first = processor.applySpeedFilter(10.0f, 1000L)
        assertEquals(0f, first)

        setProcessorState(previousRawSpeed = 10.0f, previousTimestamp = 1000L, previousFiltered = 0f)

        // Second reading at 10 mph
        val second = processor.applySpeedFilter(10.0f, 2000L)
        assertEquals(0f, second) // Still not reporting (need 3)

        setProcessorState(previousRawSpeed = 10.0f, previousTimestamp = 2000L, previousFiltered = 0f)

        // Third reading at 10 mph
        val third = processor.applySpeedFilter(10.0f, 3000L)
        assertEquals(10.0f, third) // Now reporting
    }

    @Test
    fun `consecutive counter resets when speed drops below dither threshold`() {
        // First reading above threshold
        processor.applySpeedFilter(10.0f, 1000L)
        setProcessorState(previousRawSpeed = 10.0f, previousTimestamp = 1000L, previousFiltered = 0f)

        // Drop below dither threshold
        processor.applySpeedFilter(1.0f, 2000L)
        setProcessorState(previousRawSpeed = 1.0f, previousTimestamp = 2000L, previousFiltered = 0f)

        // Back above threshold — counter should have reset
        val result = processor.applySpeedFilter(10.0f, 3000L)
        assertEquals(0f, result) // First reading again, not reporting
    }

    @Test
    fun `once reporting movement, continues reporting without needing consecutive readings`() {
        // Build up to reporting state
        processor.applySpeedFilter(10.0f, 1000L)
        setProcessorState(previousRawSpeed = 10.0f, previousTimestamp = 1000L, previousFiltered = 0f)
        processor.applySpeedFilter(10.0f, 2000L)
        setProcessorState(previousRawSpeed = 10.0f, previousTimestamp = 2000L, previousFiltered = 10.0f, consecutive = 2, reporting = true)

        // Next reading should report immediately
        val result = processor.applySpeedFilter(12.0f, 3000L)
        assertEquals(12.0f, result)
    }

    // --- Step 5: Invalid speed fallback ---

    @Test
    fun `invalid speed with valid location and last speed below 5 mph reports zero`() {
        setProcessorState(previousFiltered = 3.0f)
        val result = processor.applyInvalidSpeedFallback(locationValid = true)
        assertEquals(0f, result)
    }

    @Test
    fun `invalid speed with valid location and last speed at 5 mph retains last speed`() {
        setProcessorState(previousFiltered = 5.0f)
        val result = processor.applyInvalidSpeedFallback(locationValid = true)
        assertEquals(5.0f, result)
    }

    @Test
    fun `invalid speed with valid location and last speed above 5 mph retains last speed`() {
        setProcessorState(previousFiltered = 10.0f)
        val result = processor.applyInvalidSpeedFallback(locationValid = true)
        assertEquals(10.0f, result)
    }

    @Test
    fun `invalid speed with invalid location retains last speed`() {
        setProcessorState(previousFiltered = 3.0f)
        val result = processor.applyInvalidSpeedFallback(locationValid = false)
        assertEquals(3.0f, result)
    }

    // --- Cardinal direction mapping ---

    @Test
    fun `bearing 0 degrees maps to N`() {
        assertEquals("N", processor.bearingToCardinal(0f))
    }

    @Test
    fun `bearing 90 degrees maps to E`() {
        assertEquals("E", processor.bearingToCardinal(90f))
    }

    @Test
    fun `bearing 180 degrees maps to S`() {
        assertEquals("S", processor.bearingToCardinal(180f))
    }

    @Test
    fun `bearing 270 degrees maps to W`() {
        assertEquals("W", processor.bearingToCardinal(270f))
    }

    @Test
    fun `bearing 45 degrees maps to NE`() {
        assertEquals("NE", processor.bearingToCardinal(45f))
    }

    @Test
    fun `bearing 359 degrees maps to N`() {
        assertEquals("N", processor.bearingToCardinal(359f))
    }

    @Test
    fun `bearing 22 degrees maps to NNE`() {
        assertEquals("NNE", processor.bearingToCardinal(22.5f))
    }

    @Test
    fun `negative bearing is normalized`() {
        // -90 degrees should normalize to 270 → W
        assertEquals("W", processor.bearingToCardinal(-90f))
    }

    @Test
    fun `bearing above 360 is normalized`() {
        // 450 degrees should normalize to 90 → E
        assertEquals("E", processor.bearingToCardinal(450f))
    }

    // --- HDOP estimation ---

    @Test
    fun `6 or more satellites estimates HDOP as 1_5`() {
        assertEquals(1.5f, processor.estimateHdop(6))
        assertEquals(1.5f, processor.estimateHdop(10))
        assertEquals(1.5f, processor.estimateHdop(20))
    }

    @Test
    fun `4 to 5 satellites estimates HDOP as 2_0`() {
        assertEquals(2.0f, processor.estimateHdop(4))
        assertEquals(2.0f, processor.estimateHdop(5))
    }

    @Test
    fun `fewer than 4 satellites estimates HDOP as 99_0`() {
        assertEquals(99.0f, processor.estimateHdop(0))
        assertEquals(99.0f, processor.estimateHdop(1))
        assertEquals(99.0f, processor.estimateHdop(3))
    }

    // --- Full pipeline integration via onLocationUpdate ---

    @Test
    fun `onLocationUpdate emits ProcessedGpsData via StateFlow`() {
        processor.onLocationUpdate(
            latitude = 28.9,
            longitude = -81.9,
            altitude = 30.0,
            speedMps = 0.5f, // ~1.1 mph, below dither threshold
            bearing = 90f,
            accuracy = 5f,
            satellites = 8,
            timestamp = 1000L,
            hasSpeed = true,
            hasBearing = true
        )

        val state = processor.gpsState.value
        assertEquals(28.9, state.latitude)
        assertEquals(-81.9, state.longitude)
        assertEquals(30.0, state.altitude)
        assertEquals(0, state.speedMph) // Filtered to zero (below 2.5 mph)
        assertEquals(90f, state.headingDegrees)
        assertEquals("E", state.cardinalDirection)
        assertEquals(8, state.satelliteCount)
        assertEquals(1.5f, state.hdop)
        assertEquals(1000L, state.timestamp)
        assertTrue(state.isValid)
    }

    @Test
    fun `onLocationUpdate with invalid speed uses fallback`() {
        // Set up previous state with low speed
        processor.onLocationUpdate(
            latitude = 28.9, longitude = -81.9, altitude = 30.0,
            speedMps = 0.5f, bearing = 90f, accuracy = 5f,
            satellites = 8, timestamp = 1000L,
            hasSpeed = true, hasBearing = true
        )

        // Now send update with invalid speed
        processor.onLocationUpdate(
            latitude = 28.91, longitude = -81.91, altitude = 30.0,
            speedMps = 0f, bearing = 90f, accuracy = 5f,
            satellites = 8, timestamp = 2000L,
            hasSpeed = false, hasBearing = true
        )

        val state = processor.gpsState.value
        // Previous filtered speed was 0 (below 5 mph), location valid → report zero
        assertEquals(0, state.speedMph)
    }

    @Test
    fun `onLocationUpdate with zero lat and lon marks as invalid`() {
        processor.onLocationUpdate(
            latitude = 0.0, longitude = 0.0, altitude = 0.0,
            speedMps = 5.0f, bearing = 0f, accuracy = 50f,
            satellites = 0, timestamp = 1000L,
            hasSpeed = true, hasBearing = true
        )

        val state = processor.gpsState.value
        assertFalse(state.isValid)
    }

    // --- Satellite count debounce ---

    @Test
    fun `non-zero satellite count is reported immediately`() {
        assertEquals(8, processor.debounceSatelliteCount(8))
    }

    @Test
    fun `first zero satellite reading retains last non-zero count`() {
        processor.debounceSatelliteCount(8) // Set last reported to 8
        assertEquals(8, processor.debounceSatelliteCount(0)) // First zero — still reports 8
    }

    @Test
    fun `second zero satellite reading retains last non-zero count`() {
        processor.debounceSatelliteCount(8)
        processor.debounceSatelliteCount(0) // First zero
        assertEquals(8, processor.debounceSatelliteCount(0)) // Second zero — still reports 8
    }

    @Test
    fun `third consecutive zero satellite reading reports zero`() {
        processor.debounceSatelliteCount(8)
        processor.debounceSatelliteCount(0) // First zero
        processor.debounceSatelliteCount(0) // Second zero
        assertEquals(0, processor.debounceSatelliteCount(0)) // Third zero — now reports 0
    }

    @Test
    fun `non-zero reading after zeros resets debounce counter`() {
        processor.debounceSatelliteCount(8)
        processor.debounceSatelliteCount(0) // First zero
        processor.debounceSatelliteCount(0) // Second zero
        assertEquals(6, processor.debounceSatelliteCount(6)) // Non-zero resets counter

        // Now need 3 more consecutive zeros
        assertEquals(6, processor.debounceSatelliteCount(0)) // First zero after reset
        assertEquals(6, processor.debounceSatelliteCount(0)) // Second zero
        assertEquals(0, processor.debounceSatelliteCount(0)) // Third zero — reports 0
    }

    @Test
    fun `initial state with no previous readings and zero satellites reports zero after 3`() {
        // lastReportedSatelliteCount starts at 0, so debounce returns 0 (the last reported)
        assertEquals(0, processor.debounceSatelliteCount(0)) // First zero, last reported is 0
        assertEquals(0, processor.debounceSatelliteCount(0)) // Second zero
        assertEquals(0, processor.debounceSatelliteCount(0)) // Third zero — confirmed 0
    }

    // --- Satellite/HDOP display formatting ---

    @Test
    fun `formatSatelliteHdop formats correctly with good signal`() {
        assertEquals("8/1.50", processor.formatSatelliteHdop(8, 1.5f))
    }

    @Test
    fun `formatSatelliteHdop formats correctly with medium signal`() {
        assertEquals("4/2.00", processor.formatSatelliteHdop(4, 2.0f))
    }

    @Test
    fun `formatSatelliteHdop formats correctly with poor signal`() {
        assertEquals("2/99.00", processor.formatSatelliteHdop(2, 99.0f))
    }

    @Test
    fun `formatSatelliteHdop formats zero satellites`() {
        assertEquals("0/99.00", processor.formatSatelliteHdop(0, 99.0f))
    }

    // --- Full pipeline integration with satellite debounce ---

    @Test
    fun `onLocationUpdate uses debounced satellite count in state`() {
        // First update with 8 satellites
        processor.onLocationUpdate(
            latitude = 28.9, longitude = -81.9, altitude = 30.0,
            speedMps = 0.5f, bearing = 90f, accuracy = 5f,
            satellites = 8, timestamp = 1000L,
            hasSpeed = true, hasBearing = true
        )
        assertEquals(8, processor.gpsState.value.satelliteCount)
        assertEquals("8/1.50", processor.gpsState.value.satelliteHdopDisplay)

        // First zero — should still report 8
        processor.onLocationUpdate(
            latitude = 28.9, longitude = -81.9, altitude = 30.0,
            speedMps = 0.5f, bearing = 90f, accuracy = 5f,
            satellites = 0, timestamp = 2000L,
            hasSpeed = true, hasBearing = true
        )
        assertEquals(8, processor.gpsState.value.satelliteCount)
        assertEquals("8/1.50", processor.gpsState.value.satelliteHdopDisplay)

        // Second zero — should still report 8
        processor.onLocationUpdate(
            latitude = 28.9, longitude = -81.9, altitude = 30.0,
            speedMps = 0.5f, bearing = 90f, accuracy = 5f,
            satellites = 0, timestamp = 3000L,
            hasSpeed = true, hasBearing = true
        )
        assertEquals(8, processor.gpsState.value.satelliteCount)
        assertEquals("8/1.50", processor.gpsState.value.satelliteHdopDisplay)

        // Third zero — now reports 0
        processor.onLocationUpdate(
            latitude = 28.9, longitude = -81.9, altitude = 30.0,
            speedMps = 0.5f, bearing = 90f, accuracy = 5f,
            satellites = 0, timestamp = 4000L,
            hasSpeed = true, hasBearing = true
        )
        assertEquals(0, processor.gpsState.value.satelliteCount)
        assertEquals("0/99.00", processor.gpsState.value.satelliteHdopDisplay)
    }

    // --- Helper to set internal state via reflection for isolated testing ---

    private fun setProcessorState(
        previousRawSpeed: Float? = null,
        previousTimestamp: Long? = null,
        previousFiltered: Float? = null,
        consecutive: Int? = null,
        reporting: Boolean? = null
    ) {
        previousRawSpeed?.let {
            val field = GpsProcessorImpl::class.java.getDeclaredField("previousRawSpeedMph")
            field.isAccessible = true
            field.setFloat(processor, it)
        }
        previousTimestamp?.let {
            val field = GpsProcessorImpl::class.java.getDeclaredField("previousTimestamp")
            field.isAccessible = true
            field.setLong(processor, it)
        }
        previousFiltered?.let {
            val field = GpsProcessorImpl::class.java.getDeclaredField("previousFilteredSpeedMph")
            field.isAccessible = true
            field.setFloat(processor, it)
        }
        consecutive?.let {
            val field = GpsProcessorImpl::class.java.getDeclaredField("consecutiveAboveThreshold")
            field.isAccessible = true
            field.setInt(processor, it)
        }
        reporting?.let {
            val field = GpsProcessorImpl::class.java.getDeclaredField("isReportingMovement")
            field.isAccessible = true
            field.setBoolean(processor, it)
        }
    }
}
