package com.golfcart.gcd.domain.odometer

import com.golfcart.gcd.data.persistence.*
import com.golfcart.gcd.domain.gps.ProcessedGpsData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import net.jqwik.api.*
import net.jqwik.api.constraints.FloatRange
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Assertions.*

/**
 * Property-based tests for odometer distance accumulation gating and invariants.
 *
 * Feature: android-golf-cart-computer, Property 15: Distance accumulation gating
 * Feature: android-golf-cart-computer, Property 16: Odometer invariants
 *
 * Validates: Requirements 6.1, 6.2, 6.4, 6.5, 6.6, 6.7, 6.8
 */
@Label("Properties 15-16: Odometer distance accumulation and invariants")
@Tag("Feature: android-golf-cart-computer")
@Tag("Property 15: Distance accumulation gating")
@Tag("Property 16: Odometer invariants")
class OdometerPropertyTest {

    // =========================================================================
    // Helper methods
    // =========================================================================

    private fun createOdometerManager(): OdometerManagerImpl {
        val repo = FakeDataStoreRepository()
        val testScope = TestScope()
        return OdometerManagerImpl(
            dataStoreRepository = repo,
            coroutineScope = testScope
        )
    }

    private fun createOdometerManagerWithState(totalMiles: Float, tripMiles: Float): OdometerManagerImpl {
        val manager = createOdometerManager()
        manager._odometerState.value = OdometerState(totalMiles, tripMiles)
        return manager
    }

    private fun roundToOneDecimal(value: Float): Float {
        return Math.round(value * 10.0f) / 10.0f
    }

    // =========================================================================
    // Property 15: Distance accumulation gating
    // =========================================================================

    /**
     * Property 15a: Distance is NOT accumulated when filtered speed is zero.
     * The Doppler speed gating rule requires speed > 0 for any distance accumulation.
     *
     * **Validates: Requirements 6.4**
     */
    @Property(tries = 10)
    @Label("No distance accumulated when filtered speed is zero")
    @Tag("Property 15: Distance accumulation gating")
    fun noDistanceWhenSpeedIsZero(
        @ForAll("validLatitudes") latitude: Double,
        @ForAll("validLongitudes") longitude: Double,
        @ForAll("positiveTimestamps") timestamp: Long
    ) {
        val manager = createOdometerManager()

        // First update to establish position (with speed > 0)
        val initial = ProcessedGpsData(
            latitude = 28.5,
            longitude = -82.0,
            speedMph = 5,
            rawSpeedMph = 5f,
            timestamp = 1000L,
            isValid = true
        )
        manager.onGpsUpdate(initial)

        // Second update with speed = 0 at a different position
        val update = ProcessedGpsData(
            latitude = latitude,
            longitude = longitude,
            speedMph = 0,
            rawSpeedMph = 0f,
            timestamp = timestamp,
            isValid = true
        )
        manager.onGpsUpdate(update)

        assertEquals(0.0f, manager.odometerState.value.totalMiles,
            "Distance should not accumulate when speed is zero")
    }

    /**
     * Property 15b: Distance is NOT accumulated when position change is below the
     * Doppler-confirmed minimum threshold (0.0005 miles / 2.6 feet).
     *
     * **Validates: Requirements 6.5**
     */
    @Property(tries = 10)
    @Label("No distance accumulated below Doppler minimum threshold (0.0005 miles)")
    @Tag("Property 15: Distance accumulation gating")
    fun noDistanceBelowDopplerMinThreshold(
        @ForAll("baseLatitudes") baseLat: Double,
        @ForAll("baseLongitudes") baseLon: Double,
        @ForAll("tinyLatOffsets") latOffset: Double
    ) {
        val manager = createOdometerManager()

        // First update establishes position with Doppler speed available
        val initial = ProcessedGpsData(
            latitude = baseLat,
            longitude = baseLon,
            speedMph = 5,
            rawSpeedMph = 5f,
            timestamp = 1000L,
            isValid = true
        )
        manager.onGpsUpdate(initial)

        // Second update with very small position change (below 0.0005 miles)
        val update = ProcessedGpsData(
            latitude = baseLat + latOffset,
            longitude = baseLon,
            speedMph = 5,
            rawSpeedMph = 5f,
            timestamp = 100_000L,
            isValid = true
        )
        manager.onGpsUpdate(update)

        // Verify the distance is actually below threshold
        val distance = manager.calculateDistanceMiles(baseLat, baseLon, baseLat + latOffset, baseLon)
        if (distance < OdometerManagerImpl.MIN_DISTANCE_WITH_DOPPLER_MILES) {
            assertEquals(0.0f, manager.odometerState.value.totalMiles,
                "Distance $distance miles is below Doppler threshold " +
                "${OdometerManagerImpl.MIN_DISTANCE_WITH_DOPPLER_MILES} — should not accumulate")
        }
    }

    /**
     * Property 15c: Distance is NOT accumulated when position change is below the
     * no-Doppler fallback minimum threshold (0.002 miles / 10 feet) and Doppler speed
     * is unavailable (rawSpeedMph = 0).
     *
     * **Validates: Requirements 6.6**
     */
    @Property(tries = 10)
    @Label("No distance accumulated below fallback threshold when no Doppler (0.002 miles)")
    @Tag("Property 15: Distance accumulation gating")
    fun noDistanceBelowFallbackThresholdNoDoppler(
        @ForAll("baseLatitudes") baseLat: Double,
        @ForAll("baseLongitudes") baseLon: Double,
        @ForAll("smallLatOffsets") latOffset: Double
    ) {
        val manager = createOdometerManager()

        // First update establishes position — rawSpeedMph = 0 means no Doppler
        val initial = ProcessedGpsData(
            latitude = baseLat,
            longitude = baseLon,
            speedMph = 5,
            rawSpeedMph = 0f,
            timestamp = 1000L,
            isValid = true
        )
        manager.onGpsUpdate(initial)

        // Second update with position change between 0.0005 and 0.002 miles
        val update = ProcessedGpsData(
            latitude = baseLat + latOffset,
            longitude = baseLon,
            speedMph = 5,
            rawSpeedMph = 0f,
            timestamp = 100_000L,
            isValid = true
        )
        manager.onGpsUpdate(update)

        // Verify the distance is below fallback threshold
        val distance = manager.calculateDistanceMiles(baseLat, baseLon, baseLat + latOffset, baseLon)
        if (distance < OdometerManagerImpl.MIN_DISTANCE_NO_DOPPLER_MILES) {
            assertEquals(0.0f, manager.odometerState.value.totalMiles,
                "Distance $distance miles is below fallback threshold " +
                "${OdometerManagerImpl.MIN_DISTANCE_NO_DOPPLER_MILES} with no Doppler — should not accumulate")
        }
    }

    /**
     * Property 15d: Distance is NOT accumulated when the implied position-based speed
     * exceeds 30 mph (GPS error rejection).
     *
     * **Validates: Requirements 6.7**
     */
    @Property(tries = 10)
    @Label("No distance accumulated when implied speed exceeds 30 mph")
    @Tag("Property 15: Distance accumulation gating")
    fun noDistanceWhenImpliedSpeedExceeds30Mph(
        @ForAll("baseLatitudes") baseLat: Double,
        @ForAll("baseLongitudes") baseLon: Double,
        @ForAll("largeLatOffsets") latOffset: Double,
        @ForAll @IntRange(min = 1, max = 5) timeDeltaSeconds: Int
    ) {
        val manager = createOdometerManager()

        val startTime = 1_000_000L
        val endTime = startTime + (timeDeltaSeconds * 1000L)

        // First update establishes position
        val initial = ProcessedGpsData(
            latitude = baseLat,
            longitude = baseLon,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = startTime,
            isValid = true
        )
        manager.onGpsUpdate(initial)

        // Second update with large position change in short time
        val update = ProcessedGpsData(
            latitude = baseLat + latOffset,
            longitude = baseLon,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = endTime,
            isValid = true
        )
        manager.onGpsUpdate(update)

        // Calculate implied speed
        val distance = manager.calculateDistanceMiles(baseLat, baseLon, baseLat + latOffset, baseLon)
        val timeDeltaHours = timeDeltaSeconds / 3600.0f
        val impliedSpeed = distance / timeDeltaHours

        if (impliedSpeed > OdometerManagerImpl.MAX_POSITION_SPEED_MPH) {
            assertEquals(0.0f, manager.odometerState.value.totalMiles,
                "Implied speed $impliedSpeed mph exceeds 30 mph — distance should not accumulate")
        }
    }

    /**
     * Property 15e: Distance IS accumulated when all gating conditions are satisfied:
     * speed > 0, position change exceeds threshold, and implied speed ≤ 30 mph.
     *
     * **Validates: Requirements 6.4, 6.5, 6.7**
     */
    @Property(tries = 10)
    @Label("Distance accumulated when all gating conditions pass")
    @Tag("Property 15: Distance accumulation gating")
    fun distanceAccumulatedWhenAllGatesPass(
        @ForAll("baseLatitudes") baseLat: Double,
        @ForAll("baseLongitudes") baseLon: Double,
        @ForAll("validDistanceLatOffsets") latOffset: Double
    ) {
        val manager = createOdometerManager()

        // Calculate distance and time needed to keep implied speed under 30 mph
        val distance = manager.calculateDistanceMiles(baseLat, baseLon, baseLat + latOffset, baseLon)

        // Ensure distance exceeds minimum threshold AND is large enough to survive
        // rounding to 1 decimal place (must be >= 0.05 miles to round up)
        if (distance < 0.05f) {
            return // Skip — offset too small to produce a visible distance after rounding
        }

        // Time needed: distance / 20 mph (well under 30 mph limit) in hours, converted to ms
        val safeSpeedMph = 20.0f
        val minTimeHours = distance / safeSpeedMph
        val minTimeMs = (minTimeHours.toDouble() * 3_600_000.0).toLong() + 2000L

        val startTime = 1_000_000L
        val endTime = startTime + minTimeMs

        // First update establishes position with Doppler speed
        val initial = ProcessedGpsData(
            latitude = baseLat,
            longitude = baseLon,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = startTime,
            isValid = true
        )
        manager.onGpsUpdate(initial)

        // Second update with valid distance and speed
        val update = ProcessedGpsData(
            latitude = baseLat + latOffset,
            longitude = baseLon,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = endTime,
            isValid = true
        )
        manager.onGpsUpdate(update)

        assertTrue(manager.odometerState.value.totalMiles > 0.0f,
            "Distance should accumulate when speed > 0, distance $distance miles exceeds " +
            "threshold, and implied speed is under 30 mph (time delta: ${endTime - startTime}ms)")
    }

    // =========================================================================
    // Property 16: Odometer invariants
    // =========================================================================

    /**
     * Property 16a: Resetting the trip odometer sets trip to zero without affecting total.
     *
     * **Validates: Requirements 6.1, 6.2**
     */
    @Property(tries = 10)
    @Label("Trip reset sets trip to zero without affecting total distance")
    @Tag("Property 16: Odometer invariants")
    fun tripResetDoesNotAffectTotal(
        @ForAll @FloatRange(min = 0.0f, max = 99999.9f) totalMiles: Float,
        @ForAll @FloatRange(min = 0.0f, max = 9999.9f) tripMiles: Float
    ) {
        val roundedTotal = roundToOneDecimal(totalMiles)
        val roundedTrip = roundToOneDecimal(tripMiles)
        val manager = createOdometerManagerWithState(roundedTotal, roundedTrip)

        val totalBefore = manager.odometerState.value.totalMiles

        manager.resetTripOdometer()

        assertEquals(totalBefore, manager.odometerState.value.totalMiles, 0.01f,
            "Total miles should not change after trip reset")
        assertEquals(0.0f, manager.odometerState.value.tripMiles,
            "Trip miles should be zero after reset")
    }

    /**
     * Property 16b: Total distance equals the sum of all accumulated segments,
     * regardless of trip resets (modulo rollover).
     *
     * **Validates: Requirements 6.1, 6.2**
     */
    @Property(tries = 10)
    @Label("Total distance equals sum of all accumulated segments regardless of trip resets")
    @Tag("Property 16: Odometer invariants")
    fun totalEqualsSegmentSum(
        @ForAll("distanceSegments") segments: List<Float>,
        @ForAll("resetPositions") resetPositions: List<Int>
    ) {
        val manager = createOdometerManagerWithState(0.0f, 0.0f)

        var expectedTotal = 0.0f

        for (i in segments.indices) {
            manager.accumulateDistance(segments[i])
            // Match the implementation's rounding behavior: round after each accumulation
            expectedTotal += segments[i]
            // Apply rollover
            while (expectedTotal >= OdometerManagerImpl.ROLLOVER_MILES) {
                expectedTotal -= OdometerManagerImpl.ROLLOVER_MILES
            }
            expectedTotal = roundToOneDecimal(expectedTotal)

            // Check if we should reset trip after this segment
            if (resetPositions.contains(i)) {
                manager.resetTripOdometer()
            }
        }

        val actualTotal = manager.odometerState.value.totalMiles

        assertEquals(expectedTotal, actualTotal, 0.1f,
            "Total distance should equal sum of segments (with rounding tolerance). " +
            "Expected ~$expectedTotal but got $actualTotal")
    }

    /**
     * Property 16c: Odometer rolls over to zero at exactly 100,000 miles.
     * After rollover, the value should be the excess over 100,000.
     *
     * **Validates: Requirements 6.8**
     */
    @Property(tries = 10)
    @Label("Odometer rolls over at 100,000 miles")
    @Tag("Property 16: Odometer invariants")
    fun odometerRollsOverAt100000(
        @ForAll @FloatRange(min = 99900.0f, max = 99999.9f) startingMiles: Float,
        @ForAll @FloatRange(min = 0.1f, max = 200.0f) additionalDistance: Float
    ) {
        val roundedStart = roundToOneDecimal(startingMiles)
        val manager = createOdometerManagerWithState(roundedStart, 0.0f)

        manager.accumulateDistance(additionalDistance)

        val actualTotal = manager.odometerState.value.totalMiles
        val rawTotal = roundedStart + additionalDistance

        if (rawTotal >= OdometerManagerImpl.ROLLOVER_MILES) {
            // Should have rolled over
            val expectedAfterRollover = rawTotal - OdometerManagerImpl.ROLLOVER_MILES
            val roundedExpected = roundToOneDecimal(expectedAfterRollover)
            assertEquals(roundedExpected, actualTotal, 0.2f,
                "After rollover: starting=$roundedStart + distance=$additionalDistance = " +
                "$rawTotal should roll to ~$roundedExpected but got $actualTotal")
        } else {
            // Should not have rolled over
            assertTrue(actualTotal < OdometerManagerImpl.ROLLOVER_MILES,
                "Total $actualTotal should be below rollover threshold")
        }
    }

    /**
     * Property 16d: Total distance is always non-negative and below rollover value.
     *
     * **Validates: Requirements 6.1, 6.8**
     */
    @Property(tries = 10)
    @Label("Total distance is always in valid range [0, 100000)")
    @Tag("Property 16: Odometer invariants")
    fun totalDistanceAlwaysInValidRange(
        @ForAll @FloatRange(min = 0.0f, max = 99999.9f) startingMiles: Float,
        @ForAll("distanceSegments") segments: List<Float>
    ) {
        val roundedStart = roundToOneDecimal(startingMiles)
        val manager = createOdometerManagerWithState(roundedStart, 0.0f)

        for (segment in segments) {
            manager.accumulateDistance(segment)
        }

        val total = manager.odometerState.value.totalMiles
        assertTrue(total >= 0.0f,
            "Total distance should never be negative, got $total")
        assertTrue(total < OdometerManagerImpl.ROLLOVER_MILES,
            "Total distance should be below rollover value, got $total")
    }

    /**
     * Property 16e: Trip odometer accumulates independently and is not affected by
     * total odometer rollover.
     *
     * **Validates: Requirements 6.2**
     */
    @Property(tries = 10)
    @Label("Trip odometer accumulates independently of total rollover")
    @Tag("Property 16: Odometer invariants")
    fun tripAccumulatesIndependentlyOfRollover(
        @ForAll @FloatRange(min = 99990.0f, max = 99999.9f) startingTotal: Float,
        @ForAll @FloatRange(min = 5.0f, max = 50.0f) tripStartMiles: Float,
        @ForAll @FloatRange(min = 5.0f, max = 50.0f) additionalDistance: Float
    ) {
        val roundedStart = roundToOneDecimal(startingTotal)
        val roundedTrip = roundToOneDecimal(tripStartMiles)
        val manager = createOdometerManagerWithState(roundedStart, roundedTrip)

        manager.accumulateDistance(additionalDistance)

        val expectedTrip = roundToOneDecimal(roundedTrip + additionalDistance)
        val actualTrip = manager.odometerState.value.tripMiles

        assertEquals(expectedTrip, actualTrip, 0.2f,
            "Trip should accumulate to ~$expectedTrip regardless of total rollover, " +
            "but got $actualTrip")
    }

    // =========================================================================
    // Generators
    // =========================================================================

    @Provide
    fun validLatitudes(): Arbitrary<Double> {
        return Arbitraries.doubles().between(-85.0, 85.0)
    }

    @Provide
    fun validLongitudes(): Arbitrary<Double> {
        return Arbitraries.doubles().between(-180.0, 180.0)
    }

    @Provide
    fun baseLatitudes(): Arbitrary<Double> {
        // Use latitudes in typical golf cart range (The Villages, FL area)
        return Arbitraries.doubles().between(28.0, 29.0)
    }

    @Provide
    fun baseLongitudes(): Arbitrary<Double> {
        return Arbitraries.doubles().between(-82.5, -81.5)
    }

    @Provide
    fun positiveTimestamps(): Arbitrary<Long> {
        return Arbitraries.longs().between(1000L, 10_000_000L)
    }

    /**
     * Tiny latitude offsets that produce distances below 0.0005 miles (2.6 feet).
     * At latitude ~28°, 1 degree ≈ 69 miles, so 0.0000072° ≈ 0.0005 miles.
     * We use offsets well below that threshold.
     */
    @Provide
    fun tinyLatOffsets(): Arbitrary<Double> {
        return Arbitraries.doubles().between(0.0000001, 0.000005).ofScale(8)
    }

    /**
     * Small latitude offsets that produce distances between 0.0005 and 0.002 miles.
     * At latitude ~28°, 0.00001° ≈ 0.00069 miles (between thresholds).
     */
    @Provide
    fun smallLatOffsets(): Arbitrary<Double> {
        return Arbitraries.doubles().between(0.000008, 0.000025).ofScale(7)
    }

    /**
     * Large latitude offsets that produce distances implying > 30 mph in short time.
     * At latitude ~28°, 0.01° ≈ 0.69 miles. In 1-5 seconds that's 496-2484 mph.
     */
    @Provide
    fun largeLatOffsets(): Arbitrary<Double> {
        return Arbitraries.doubles().between(0.005, 0.05).ofScale(4)
    }

    /**
     * Valid distance latitude offsets that produce distances above the Doppler threshold
     * (0.0005 miles) AND large enough to survive rounding to 1 decimal place (>= 0.05 miles).
     * At latitude ~28°, 0.001° ≈ 0.069 miles, 0.005° ≈ 0.345 miles.
     */
    @Provide
    fun validDistanceLatOffsets(): Arbitrary<Double> {
        return Arbitraries.doubles().between(0.001, 0.005).ofScale(5)
    }

    /**
     * Generates lists of small positive distance segments for accumulation testing.
     */
    @Provide
    fun distanceSegments(): Arbitrary<List<Float>> {
        return Arbitraries.floats().between(0.1f, 10.0f)
            .list().ofMinSize(1).ofMaxSize(10)
    }

    /**
     * Generates lists of indices where trip resets should occur.
     */
    @Provide
    fun resetPositions(): Arbitrary<List<Int>> {
        return Arbitraries.integers().between(0, 9)
            .list().ofMinSize(0).ofMaxSize(3)
    }

    // =========================================================================
    // Fake DataStoreRepository for testing
    // =========================================================================

    private class FakeDataStoreRepository : DataStoreRepository {
        override fun getPersistedOdometer(): Flow<OdometerData> = flowOf(OdometerData())
        override fun getPersistedDrivingHours(): Flow<Int> = flowOf(0)
        override suspend fun persistOdometer(accumDistance: Float, tripDistance: Float) {}
        override suspend fun persistDrivingHours(tenthsOfHours: Int) {}
        override fun getPreferences(): Flow<UserPreferences> = flowOf(UserPreferences())
        override suspend fun updatePreference(key: PreferenceKey, value: Any) {}
        override suspend fun resetAllPreferences() {}
        override fun getCachedWeather(): Flow<CachedWeatherData> = flowOf(CachedWeatherData())
        override fun getCachedVenueEvents(): Flow<CachedVenueData> = flowOf(CachedVenueData())
        override suspend fun cacheWeatherData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
        override suspend fun cacheVenueData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
    }
}
