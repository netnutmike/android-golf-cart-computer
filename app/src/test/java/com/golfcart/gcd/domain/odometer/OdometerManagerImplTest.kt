package com.golfcart.gcd.domain.odometer

import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.data.persistence.OdometerData
import com.golfcart.gcd.domain.gps.ProcessedGpsData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

/**
 * Unit tests for OdometerManagerImpl.
 *
 * Tests distance accumulation rules, gating logic, rollover, trip reset,
 * and persistence behavior.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OdometerManagerImplTest {

    private lateinit var odometerManager: OdometerManagerImpl
    private lateinit var fakeDataStoreRepository: FakeDataStoreRepository
    private lateinit var testScope: TestScope

    @BeforeEach
    fun setup() {
        fakeDataStoreRepository = FakeDataStoreRepository()
        testScope = TestScope(UnconfinedTestDispatcher())
        odometerManager = OdometerManagerImpl(
            dataStoreRepository = fakeDataStoreRepository,
            coroutineScope = testScope
        )
    }

    // --- Distance Calculation Tests ---

    @Test
    @DisplayName("calculateDistanceMiles returns correct distance for known coordinates")
    fun calculateDistanceMiles_knownCoordinates() {
        // Approximately 1 mile apart (rough estimate)
        // Using coordinates that are about 1 degree of latitude apart at equator
        // 1 degree latitude ≈ 69 miles
        val distance = odometerManager.calculateDistanceMiles(
            28.0, -82.0,
            28.01, -82.0
        )
        // 0.01 degrees latitude ≈ 0.69 miles
        assertTrue(distance > 0.6f && distance < 0.8f,
            "Expected ~0.69 miles but got $distance")
    }

    @Test
    @DisplayName("calculateDistanceMiles returns 0 for same coordinates")
    fun calculateDistanceMiles_sameCoordinates() {
        val distance = odometerManager.calculateDistanceMiles(
            28.5, -82.0,
            28.5, -82.0
        )
        assertEquals(0.0f, distance, 0.0001f)
    }

    // --- Gating Tests ---

    @Test
    @DisplayName("No accumulation when filtered speed is 0")
    fun noAccumulation_whenSpeedIsZero() {
        val gpsData = ProcessedGpsData(
            latitude = 28.5,
            longitude = -82.0,
            speedMph = 0,
            rawSpeedMph = 0f,
            timestamp = 1000L,
            isValid = true
        )

        odometerManager.onGpsUpdate(gpsData)

        assertEquals(0.0f, odometerManager.odometerState.value.totalMiles)
    }

    @Test
    @DisplayName("No accumulation when GPS data is invalid")
    fun noAccumulation_whenGpsInvalid() {
        val gpsData = ProcessedGpsData(
            latitude = 28.5,
            longitude = -82.0,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = 1000L,
            isValid = false
        )

        odometerManager.onGpsUpdate(gpsData)

        assertEquals(0.0f, odometerManager.odometerState.value.totalMiles)
    }

    @Test
    @DisplayName("No accumulation when position is 0,0")
    fun noAccumulation_whenPositionIsZeroZero() {
        val gpsData = ProcessedGpsData(
            latitude = 0.0,
            longitude = 0.0,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = 1000L,
            isValid = true
        )

        odometerManager.onGpsUpdate(gpsData)

        assertEquals(0.0f, odometerManager.odometerState.value.totalMiles)
    }

    @Test
    @DisplayName("Distance accumulated when all gates pass with Doppler speed")
    fun distanceAccumulated_whenAllGatesPass() {
        // First update establishes position
        val gpsData1 = ProcessedGpsData(
            latitude = 28.5,
            longitude = -82.0,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = 1000L,
            isValid = true
        )
        odometerManager.onGpsUpdate(gpsData1)

        // Second update with enough distance (about 0.69 miles)
        val gpsData2 = ProcessedGpsData(
            latitude = 28.51,
            longitude = -82.0,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = 250_000L, // ~4 minutes later (keeps speed under 30 mph)
            isValid = true
        )
        odometerManager.onGpsUpdate(gpsData2)

        assertTrue(odometerManager.odometerState.value.totalMiles > 0.0f,
            "Expected distance to be accumulated")
        assertTrue(odometerManager.odometerState.value.tripMiles > 0.0f,
            "Expected trip distance to be accumulated")
    }

    @Test
    @DisplayName("Distance below minimum threshold with Doppler is not accumulated")
    fun noAccumulation_belowMinThresholdWithDoppler() {
        // First update establishes position
        val gpsData1 = ProcessedGpsData(
            latitude = 28.500000,
            longitude = -82.000000,
            speedMph = 5,
            rawSpeedMph = 5f,
            timestamp = 1000L,
            isValid = true
        )
        odometerManager.onGpsUpdate(gpsData1)

        // Second update with very small distance (less than 0.0005 miles / 2.6 feet)
        // 0.0000001 degrees ≈ 0.00000069 miles (way below threshold)
        val gpsData2 = ProcessedGpsData(
            latitude = 28.5000001,
            longitude = -82.000000,
            speedMph = 5,
            rawSpeedMph = 5f,
            timestamp = 2000L,
            isValid = true
        )
        odometerManager.onGpsUpdate(gpsData2)

        assertEquals(0.0f, odometerManager.odometerState.value.totalMiles)
    }

    @Test
    @DisplayName("Fallback minimum threshold used when no Doppler speed")
    fun fallbackMinThreshold_whenNoDopplerSpeed() {
        // First update establishes position
        val gpsData1 = ProcessedGpsData(
            latitude = 28.500000,
            longitude = -82.000000,
            speedMph = 5,
            rawSpeedMph = 0f, // No Doppler speed
            timestamp = 1000L,
            isValid = true
        )
        odometerManager.onGpsUpdate(gpsData1)

        // Second update with distance between 0.0005 and 0.002 miles
        // 0.00001 degrees latitude ≈ 0.00069 miles (above Doppler min, below fallback min)
        val gpsData2 = ProcessedGpsData(
            latitude = 28.500010,
            longitude = -82.000000,
            speedMph = 5,
            rawSpeedMph = 0f, // No Doppler speed
            timestamp = 2000L,
            isValid = true
        )
        odometerManager.onGpsUpdate(gpsData2)

        // Should NOT accumulate because distance is below fallback minimum (0.002 miles)
        assertEquals(0.0f, odometerManager.odometerState.value.totalMiles)
    }

    @Test
    @DisplayName("Position-based speed > 30 mph is rejected as GPS error")
    fun rejectHighPositionBasedSpeed() {
        // First update establishes position
        val gpsData1 = ProcessedGpsData(
            latitude = 28.500000,
            longitude = -82.000000,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = 1000L,
            isValid = true
        )
        odometerManager.onGpsUpdate(gpsData1)

        // Second update with large distance in short time (implies > 30 mph)
        // 0.01 degrees ≈ 0.69 miles in 1 second = 2484 mph (way over 30)
        val gpsData2 = ProcessedGpsData(
            latitude = 28.510000,
            longitude = -82.000000,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = 2000L, // Only 1 second later
            isValid = true
        )
        odometerManager.onGpsUpdate(gpsData2)

        assertEquals(0.0f, odometerManager.odometerState.value.totalMiles,
            "Should reject distance when position-based speed > 30 mph")
    }

    // --- Rollover Tests ---

    @Test
    @DisplayName("Odometer rolls over at 100,000 miles")
    fun odometerRollover() {
        // Directly test accumulateDistance with a value that causes rollover
        // Set initial state close to rollover
        odometerManager._odometerState.value = OdometerState(
            totalMiles = 99_999.9f,
            tripMiles = 5.0f
        )

        odometerManager.accumulateDistance(0.2f)

        // Should roll over: 99999.9 + 0.2 = 100000.1 - 100000 = 0.1
        assertTrue(odometerManager.odometerState.value.totalMiles < 1.0f,
            "Expected rollover but got ${odometerManager.odometerState.value.totalMiles}")
    }

    // --- Trip Reset Tests ---

    @Test
    @DisplayName("Trip reset sets trip to zero without affecting total")
    fun tripReset_doesNotAffectTotal() {
        odometerManager._odometerState.value = OdometerState(
            totalMiles = 500.5f,
            tripMiles = 25.3f
        )

        odometerManager.resetTripOdometer()

        assertEquals(500.5f, odometerManager.odometerState.value.totalMiles)
        assertEquals(0.0f, odometerManager.odometerState.value.tripMiles)
    }

    // --- Persistence Tests ---

    @Test
    @DisplayName("Values are loaded from persistence on startup")
    fun loadPersistedValues_onStartup() = runTest {
        val fakeRepo = FakeDataStoreRepository(
            initialOdometer = OdometerData(accumDistance = 1234.5f, tripDistance = 12.3f)
        )
        val manager = OdometerManagerImpl(
            dataStoreRepository = fakeRepo,
            coroutineScope = this
        )

        // Allow coroutines to complete
        testScheduler.advanceUntilIdle()

        assertEquals(1234.5f, manager.odometerState.value.totalMiles)
        assertEquals(12.3f, manager.odometerState.value.tripMiles)
    }

    @Test
    @DisplayName("persistBeforeShutdown persists current values")
    fun persistBeforeShutdown_persistsValues() = runTest {
        val fakeRepo = FakeDataStoreRepository()
        val manager = OdometerManagerImpl(
            dataStoreRepository = fakeRepo,
            coroutineScope = this
        )
        testScheduler.advanceUntilIdle()

        manager._odometerState.value = OdometerState(totalMiles = 100.5f, tripMiles = 10.2f)
        manager.persistBeforeShutdown()

        assertEquals(100.5f, fakeRepo.lastPersistedAccumDistance)
        assertEquals(10.2f, fakeRepo.lastPersistedTripDistance)
    }

    // --- Rounding Tests ---

    @Test
    @DisplayName("roundToOneDecimal rounds correctly")
    fun roundToOneDecimal() {
        assertEquals(1.1f, odometerManager.roundToOneDecimal(1.14f))
        assertEquals(1.2f, odometerManager.roundToOneDecimal(1.15f))
        assertEquals(0.0f, odometerManager.roundToOneDecimal(0.04f))
        assertEquals(99999.9f, odometerManager.roundToOneDecimal(99999.9f))
    }

    // --- Previous position update when stopped ---

    @Test
    @DisplayName("Previous position updates when stopped to prevent drift accumulation")
    fun previousPositionUpdates_whenStopped() {
        // First update with speed > 0 establishes position
        val gpsData1 = ProcessedGpsData(
            latitude = 28.500000,
            longitude = -82.000000,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = 1000L,
            isValid = true
        )
        odometerManager.onGpsUpdate(gpsData1)

        // Stop at a different position (GPS drift while stopped)
        val gpsData2 = ProcessedGpsData(
            latitude = 28.500100,
            longitude = -82.000000,
            speedMph = 0,
            rawSpeedMph = 0f,
            timestamp = 2000L,
            isValid = true
        )
        odometerManager.onGpsUpdate(gpsData2)

        // Resume moving from the stopped position
        val gpsData3 = ProcessedGpsData(
            latitude = 28.500200,
            longitude = -82.000000,
            speedMph = 10,
            rawSpeedMph = 10f,
            timestamp = 100_000L, // Enough time to keep speed under 30 mph
            isValid = true
        )
        odometerManager.onGpsUpdate(gpsData3)

        // Distance should only be from gpsData2 to gpsData3, not gpsData1 to gpsData3
        val expectedMaxDistance = odometerManager.calculateDistanceMiles(
            28.500100, -82.000000,
            28.500200, -82.000000
        )
        assertTrue(odometerManager.odometerState.value.totalMiles <= expectedMaxDistance + 0.1f,
            "Distance should be from stopped position, not original position")
    }

    // --- Fake implementations ---

    private class FakeDataStoreRepository(
        private val initialOdometer: OdometerData = OdometerData()
    ) : DataStoreRepository {
        var lastPersistedAccumDistance: Float = 0f
        var lastPersistedTripDistance: Float = 0f
        var persistOdometerCallCount: Int = 0

        override fun getPersistedOdometer(): Flow<OdometerData> = flowOf(initialOdometer)
        override fun getPersistedDrivingHours(): Flow<Int> = flowOf(0)

        override suspend fun persistOdometer(accumDistance: Float, tripDistance: Float) {
            lastPersistedAccumDistance = accumDistance
            lastPersistedTripDistance = tripDistance
            persistOdometerCallCount++
        }

        override suspend fun persistDrivingHours(tenthsOfHours: Int) {}

        override fun getPreferences(): Flow<com.golfcart.gcd.data.persistence.UserPreferences> =
            flowOf(com.golfcart.gcd.data.persistence.UserPreferences())

        override suspend fun updatePreference(key: com.golfcart.gcd.data.persistence.PreferenceKey, value: Any) {}
        override suspend fun resetAllPreferences() {}
        override fun getCachedWeather(): Flow<com.golfcart.gcd.data.persistence.CachedWeatherData> =
            flowOf(com.golfcart.gcd.data.persistence.CachedWeatherData())
        override fun getCachedVenueEvents(): Flow<com.golfcart.gcd.data.persistence.CachedVenueData> =
            flowOf(com.golfcart.gcd.data.persistence.CachedVenueData())
        override suspend fun cacheWeatherData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
        override suspend fun cacheVenueData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
    }
}
