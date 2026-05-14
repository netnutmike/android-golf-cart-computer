package com.golfcart.gcd.domain.geofence

import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.TelemetryConnection
import com.golfcart.gcd.data.bluetooth.TelemetryData
import com.golfcart.gcd.data.persistence.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import net.jqwik.api.*
import net.jqwik.api.constraints.DoubleRange
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Assertions.*

/**
 * Property-based tests for geofence status determination.
 *
 * Feature: android-golf-cart-computer, Property 18: Geofence status determination
 *
 * The core property: at_home is true if and only if the calculated distance
 * between the current position and home location is less than or equal to
 * the configured geofence radius.
 *
 * Validates: Requirements 9.4, 9.5, 9.6
 */
@Label("Property 18: Geofence status determination")
@Tag("Feature: android-golf-cart-computer")
@Tag("Property 18: Geofence status determination")
class GeofencePropertyTest {

    // =========================================================================
    // Helper methods
    // =========================================================================

    private fun createGeofenceManager(): GeofenceManagerImpl {
        val repo = FakeDataStoreRepository()
        val telemetry = FakeTelemetryConnection()
        val testScope = TestScope()
        return GeofenceManagerImpl(
            dataStoreRepository = repo,
            telemetryConnection = telemetry,
            coroutineScope = testScope
        )
    }

    /**
     * Creates a GeofenceManagerImpl with a home location already set and a given radius.
     */
    private fun createGeofenceManagerWithHome(
        homeLat: Double,
        homeLon: Double,
        radiusMeters: Int
    ): GeofenceManagerImpl {
        val manager = createGeofenceManager()
        manager._geofenceState.value = GeofenceState(
            homeLatitude = homeLat,
            homeLongitude = homeLon,
            isHomeSet = true,
            fenceRadius = radiusMeters
        )
        return manager
    }

    // =========================================================================
    // Property 18: Geofence status determination
    // =========================================================================

    /**
     * Property 18a: When the calculated distance from current position to home
     * is less than or equal to the configured radius, at_home must be true.
     *
     * **Validates: Requirements 9.4, 9.5**
     */
    @Property(tries = 10)
    @Label("at_home is true when distance <= configured radius")
    fun atHomeIsTrueWhenDistanceWithinRadius(
        @ForAll("homeLatitudes") homeLat: Double,
        @ForAll("homeLongitudes") homeLon: Double,
        @ForAll("fenceRadii") radiusMeters: Int,
        @ForAll("bearings") bearingDegrees: Double,
        @ForAll("fractionWithinRadius") fraction: Double
    ) {
        val manager = createGeofenceManagerWithHome(homeLat, homeLon, radiusMeters)

        // Calculate a position that is within the radius
        // Move a fraction of the radius distance in the given bearing direction
        val targetDistanceMeters = radiusMeters * fraction
        val currentPos = offsetPosition(homeLat, homeLon, bearingDegrees, targetDistanceMeters)

        manager.recalculateDistance(currentPos.first, currentPos.second)

        val state = manager.geofenceState.value
        val calculatedDistance = manager.calculateDistanceMeters(
            currentPos.first, currentPos.second, homeLat, homeLon
        )

        // The position should be within the radius
        assertTrue(calculatedDistance <= radiusMeters,
            "Generated position should be within radius. " +
            "Distance: $calculatedDistance m, Radius: $radiusMeters m, Fraction: $fraction")

        assertTrue(state.isAtHome,
            "at_home should be true when distance ($calculatedDistance m) <= radius ($radiusMeters m)")
    }

    /**
     * Property 18b: When the calculated distance from current position to home
     * is greater than the configured radius, at_home must be false.
     *
     * **Validates: Requirements 9.4, 9.6**
     */
    @Property(tries = 10)
    @Label("at_home is false when distance > configured radius")
    fun atHomeIsFalseWhenDistanceExceedsRadius(
        @ForAll("homeLatitudes") homeLat: Double,
        @ForAll("homeLongitudes") homeLon: Double,
        @ForAll("fenceRadii") radiusMeters: Int,
        @ForAll("bearings") bearingDegrees: Double,
        @ForAll("fractionBeyondRadius") fraction: Double
    ) {
        val manager = createGeofenceManagerWithHome(homeLat, homeLon, radiusMeters)

        // Calculate a position that is beyond the radius
        val targetDistanceMeters = radiusMeters * fraction
        val currentPos = offsetPosition(homeLat, homeLon, bearingDegrees, targetDistanceMeters)

        manager.recalculateDistance(currentPos.first, currentPos.second)

        val state = manager.geofenceState.value
        val calculatedDistance = manager.calculateDistanceMeters(
            currentPos.first, currentPos.second, homeLat, homeLon
        )

        // The position should be beyond the radius
        assertTrue(calculatedDistance > radiusMeters,
            "Generated position should be beyond radius. " +
            "Distance: $calculatedDistance m, Radius: $radiusMeters m, Fraction: $fraction")

        assertFalse(state.isAtHome,
            "at_home should be false when distance ($calculatedDistance m) > radius ($radiusMeters m)")
    }

    /**
     * Property 18c: The at_home status is consistent with the distance comparison —
     * for any position, at_home == (distance <= radius). This is the biconditional property.
     *
     * **Validates: Requirements 9.4, 9.5, 9.6**
     */
    @Property(tries = 10)
    @Label("at_home is true iff distance <= radius (biconditional)")
    fun atHomeIffDistanceWithinRadius(
        @ForAll("homeLatitudes") homeLat: Double,
        @ForAll("homeLongitudes") homeLon: Double,
        @ForAll("fenceRadii") radiusMeters: Int,
        @ForAll("currentLatitudes") currentLat: Double,
        @ForAll("currentLongitudes") currentLon: Double
    ) {
        val manager = createGeofenceManagerWithHome(homeLat, homeLon, radiusMeters)

        manager.recalculateDistance(currentLat, currentLon)

        val state = manager.geofenceState.value
        val distance = manager.calculateDistanceMeters(currentLat, currentLon, homeLat, homeLon)
        val expectedAtHome = distance <= radiusMeters

        assertEquals(expectedAtHome, state.isAtHome,
            "at_home should be ${expectedAtHome} when distance=$distance m and radius=$radiusMeters m. " +
            "Home=($homeLat, $homeLon), Current=($currentLat, $currentLon)")
    }

    /**
     * Property 18d: At the exact home location (distance = 0), at_home is always true
     * regardless of the configured radius.
     *
     * **Validates: Requirements 9.4, 9.5**
     */
    @Property(tries = 10)
    @Label("at_home is always true at exact home location")
    fun atHomeAlwaysTrueAtExactHomeLocation(
        @ForAll("homeLatitudes") homeLat: Double,
        @ForAll("homeLongitudes") homeLon: Double,
        @ForAll("fenceRadii") radiusMeters: Int
    ) {
        val manager = createGeofenceManagerWithHome(homeLat, homeLon, radiusMeters)

        // Current position is exactly the home position
        manager.recalculateDistance(homeLat, homeLon)

        val state = manager.geofenceState.value

        assertEquals(0.0f, state.distanceFromHome, 0.01f,
            "Distance should be 0 when at exact home location")
        assertTrue(state.isAtHome,
            "at_home should be true when at exact home location (distance=0, radius=$radiusMeters)")
    }

    /**
     * Property 18e: The geofence determination is symmetric — if position A is within
     * radius of home, and we set home to A, then the original home position is also
     * within radius (since distance is symmetric).
     *
     * **Validates: Requirements 9.4, 9.5, 9.6**
     */
    @Property(tries = 10)
    @Label("Distance calculation is symmetric")
    fun distanceCalculationIsSymmetric(
        @ForAll("homeLatitudes") lat1: Double,
        @ForAll("homeLongitudes") lon1: Double,
        @ForAll("currentLatitudes") lat2: Double,
        @ForAll("currentLongitudes") lon2: Double
    ) {
        val manager = createGeofenceManager()

        val distanceAtoB = manager.calculateDistanceMeters(lat1, lon1, lat2, lon2)
        val distanceBtoA = manager.calculateDistanceMeters(lat2, lon2, lat1, lon1)

        assertEquals(distanceAtoB, distanceBtoA, 0.01f,
            "Distance from ($lat1,$lon1) to ($lat2,$lon2) should equal " +
            "distance from ($lat2,$lon2) to ($lat1,$lon1). " +
            "Got $distanceAtoB vs $distanceBtoA")
    }

    /**
     * Property 18f: Changing the fence radius correctly updates at_home status.
     * A position that was outside a smaller radius may be inside a larger radius.
     *
     * **Validates: Requirements 9.4, 9.5, 9.6**
     */
    @Property(tries = 10)
    @Label("Larger radius includes all positions that smaller radius includes")
    fun largerRadiusIsMoreInclusive(
        @ForAll("homeLatitudes") homeLat: Double,
        @ForAll("homeLongitudes") homeLon: Double,
        @ForAll("smallRadii") smallRadius: Int,
        @ForAll("bearings") bearingDegrees: Double,
        @ForAll("fractionWithinRadius") fraction: Double
    ) {
        val largeRadius = smallRadius * 3

        // Create a position within the small radius
        val targetDistanceMeters = smallRadius * fraction
        val currentPos = offsetPosition(homeLat, homeLon, bearingDegrees, targetDistanceMeters)

        // With small radius — should be at home
        val managerSmall = createGeofenceManagerWithHome(homeLat, homeLon, smallRadius)
        managerSmall.recalculateDistance(currentPos.first, currentPos.second)

        // With large radius — should also be at home (larger radius is more inclusive)
        val managerLarge = createGeofenceManagerWithHome(homeLat, homeLon, largeRadius)
        managerLarge.recalculateDistance(currentPos.first, currentPos.second)

        val atHomeSmall = managerSmall.geofenceState.value.isAtHome
        val atHomeLarge = managerLarge.geofenceState.value.isAtHome

        // If at home with small radius, must be at home with large radius
        if (atHomeSmall) {
            assertTrue(atHomeLarge,
                "If at_home with radius $smallRadius, must also be at_home with radius $largeRadius")
        }
    }

    // =========================================================================
    // Generators
    // =========================================================================

    /**
     * Home latitudes — valid GPS latitudes excluding poles and equator/prime meridian.
     * Uses The Villages, FL area as a realistic range but also includes global positions.
     */
    @Provide
    fun homeLatitudes(): Arbitrary<Double> {
        return Arbitraries.doubles().between(-85.0, 85.0)
            .filter { it != 0.0 } // Exclude 0.0 since (0,0) is treated as invalid
    }

    /**
     * Home longitudes — valid GPS longitudes excluding prime meridian.
     */
    @Provide
    fun homeLongitudes(): Arbitrary<Double> {
        return Arbitraries.doubles().between(-179.0, 179.0)
            .filter { it != 0.0 } // Exclude 0.0 since (0,0) is treated as invalid
    }

    /**
     * Current position latitudes — broader range for testing various distances.
     */
    @Provide
    fun currentLatitudes(): Arbitrary<Double> {
        return Arbitraries.doubles().between(-85.0, 85.0)
            .filter { it != 0.0 }
    }

    /**
     * Current position longitudes — broader range for testing various distances.
     */
    @Provide
    fun currentLongitudes(): Arbitrary<Double> {
        return Arbitraries.doubles().between(-179.0, 179.0)
            .filter { it != 0.0 }
    }

    /**
     * Fence radii — realistic geofence radius values in meters.
     * Range from small (50m) to large (5000m) to test various configurations.
     */
    @Provide
    fun fenceRadii(): Arbitrary<Int> {
        return Arbitraries.integers().between(50, 5000)
    }

    /**
     * Smaller radii for the monotonicity test.
     */
    @Provide
    fun smallRadii(): Arbitrary<Int> {
        return Arbitraries.integers().between(100, 1000)
    }

    /**
     * Bearing angles in degrees [0, 360) for positioning points around home.
     */
    @Provide
    fun bearings(): Arbitrary<Double> {
        return Arbitraries.doubles().between(0.0, 359.99)
    }

    /**
     * Fraction of radius to place a point within the geofence (0 to 0.95).
     * We use 0.95 max to avoid floating-point boundary issues at exactly the radius.
     */
    @Provide
    fun fractionWithinRadius(): Arbitrary<Double> {
        return Arbitraries.doubles().between(0.0, 0.95)
    }

    /**
     * Fraction of radius to place a point beyond the geofence (1.1 to 5.0).
     * We use 1.1 min to ensure we're clearly outside the radius.
     */
    @Provide
    fun fractionBeyondRadius(): Arbitrary<Double> {
        return Arbitraries.doubles().between(1.1, 5.0)
    }

    // =========================================================================
    // Utility: offset a GPS position by distance and bearing
    // =========================================================================

    /**
     * Calculates a new GPS position offset from a starting point by a given
     * distance (in meters) and bearing (in degrees).
     *
     * Uses the inverse Haversine formula (destination point given distance and bearing).
     */
    private fun offsetPosition(
        lat: Double,
        lon: Double,
        bearingDegrees: Double,
        distanceMeters: Double
    ): Pair<Double, Double> {
        val earthRadius = GeofenceManagerImpl.EARTH_RADIUS_METERS
        val angularDistance = distanceMeters / earthRadius

        val latRad = Math.toRadians(lat)
        val lonRad = Math.toRadians(lon)
        val bearingRad = Math.toRadians(bearingDegrees)

        val newLatRad = Math.asin(
            Math.sin(latRad) * Math.cos(angularDistance) +
            Math.cos(latRad) * Math.sin(angularDistance) * Math.cos(bearingRad)
        )

        val newLonRad = lonRad + Math.atan2(
            Math.sin(bearingRad) * Math.sin(angularDistance) * Math.cos(latRad),
            Math.cos(angularDistance) - Math.sin(latRad) * Math.sin(newLatRad)
        )

        return Pair(Math.toDegrees(newLatRad), Math.toDegrees(newLonRad))
    }

    // =========================================================================
    // Fake implementations for testing
    // =========================================================================

    private class FakeDataStoreRepository : DataStoreRepository {
        override fun getPreferences(): Flow<UserPreferences> = flowOf(UserPreferences())
        override suspend fun updatePreference(key: PreferenceKey, value: Any) {}
        override suspend fun resetAllPreferences() {}
        override fun getCachedWeather(): Flow<CachedWeatherData> = flowOf(CachedWeatherData())
        override fun getCachedVenueEvents(): Flow<CachedVenueData> = flowOf(CachedVenueData())
        override suspend fun cacheWeatherData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
        override suspend fun cacheVenueData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
        override suspend fun persistOdometer(accumDistance: Float, tripDistance: Float) {}
        override suspend fun persistDrivingHours(tenthsOfHours: Int) {}
        override fun getPersistedOdometer(): Flow<OdometerData> = flowOf(OdometerData())
        override fun getPersistedDrivingHours(): Flow<Int> = flowOf(0)
    }

    private class FakeTelemetryConnection : TelemetryConnection {
        override val connectionState: StateFlow<ConnectionState> =
            MutableStateFlow(ConnectionState.DISCONNECTED)
        override val telemetryData: StateFlow<TelemetryData> =
            MutableStateFlow(TelemetryData())
        override suspend fun sendHeartbeat() {}
        override suspend fun sendGpsData(
            latitude: Float, longitude: Float, altitude: Float,
            speed: Float, heading: Float, satellites: Int
        ) {}
        override suspend fun sendIsHome(isHome: Boolean) {}
        override suspend fun sendIsDaytime(isDaytime: Boolean) {}
        override suspend fun pairNewDevice(timeoutSeconds: Int) {}
        override suspend fun connect(deviceAddress: String) {}
        override suspend fun disconnect() {}
    }
}
