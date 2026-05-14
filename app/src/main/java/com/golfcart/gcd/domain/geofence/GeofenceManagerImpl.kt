package com.golfcart.gcd.domain.geofence

import android.util.Log
import com.golfcart.gcd.data.bluetooth.TelemetryConnection
import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.data.persistence.PreferenceKey
import com.golfcart.gcd.domain.gps.GpsProcessor
import com.golfcart.gcd.domain.gps.ProcessedGpsData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.*

/**
 * Implementation of [GeofenceManager] that calculates distance from the current
 * GPS position to a saved home location and determines at-home status.
 *
 * Continuously observes GPS state from [GpsProcessor] and recalculates distance
 * whenever the position changes. Notifies the GCI via [TelemetryConnection] when
 * the at-home status transitions.
 *
 * Home location coordinates are persisted to DataStore and restored on startup.
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8
 */
@Singleton
class GeofenceManagerImpl @Inject constructor(
    private val gpsProcessor: GpsProcessor,
    private val dataStoreRepository: DataStoreRepository,
    private val telemetryConnection: TelemetryConnection,
    private val coroutineScope: CoroutineScope
) : GeofenceManager {

    internal val _geofenceState = MutableStateFlow(GeofenceState())
    override val geofenceState: StateFlow<GeofenceState> = _geofenceState.asStateFlow()

    companion object {
        private const val TAG = "GeofenceManagerImpl"

        /** Earth's mean radius in meters for Haversine calculation. */
        const val EARTH_RADIUS_METERS = 6_371_000.0

        /** Minimum valid latitude. */
        const val MIN_LATITUDE = -90.0

        /** Maximum valid latitude. */
        const val MAX_LATITUDE = 90.0

        /** Minimum valid longitude. */
        const val MIN_LONGITUDE = -180.0

        /** Maximum valid longitude. */
        const val MAX_LONGITUDE = 180.0
    }

    init {
        // Load persisted home location on startup
        coroutineScope.launch {
            loadPersistedHomeLocation()
        }

        // Observe GPS state for continuous distance calculation
        coroutineScope.launch {
            gpsProcessor.gpsState.collect { gpsData ->
                onGpsUpdate(gpsData)
            }
        }
    }

    /**
     * Internal constructor for testing without auto-starting GPS observation.
     */
    internal constructor(
        dataStoreRepository: DataStoreRepository,
        telemetryConnection: TelemetryConnection,
        coroutineScope: CoroutineScope
    ) : this(
        gpsProcessor = NoOpGpsProcessor,
        dataStoreRepository = dataStoreRepository,
        telemetryConnection = telemetryConnection,
        coroutineScope = coroutineScope
    )

    override fun setHomeLocation(lat: Double, lon: Double): SetHomeResult {
        // Validate GPS coordinates
        if (!isValidCoordinate(lat, lon)) {
            val message = "GPS not available. Cannot set home location."
            Log.w(TAG, message)
            return SetHomeResult.Error(message)
        }

        val currentState = _geofenceState.value

        // Update state with new home location
        _geofenceState.value = currentState.copy(
            homeLatitude = lat,
            homeLongitude = lon,
            isHomeSet = true
        )

        // Persist home location
        coroutineScope.launch {
            persistHomeLocation(lat, lon)
        }

        // Recalculate distance with current GPS position
        val currentGps = gpsProcessor.gpsState.value
        if (currentGps.isValid) {
            recalculateDistance(currentGps.latitude, currentGps.longitude)
        }

        Log.d(TAG, "Home location set: lat=$lat, lon=$lon")
        return SetHomeResult.Success
    }

    override fun clearHomeLocation() {
        val previousState = _geofenceState.value
        val wasAtHome = previousState.isAtHome

        // Reset state
        _geofenceState.value = GeofenceState(
            fenceRadius = previousState.fenceRadius
        )

        // Persist cleared home location
        coroutineScope.launch {
            clearPersistedHomeLocation()
        }

        // Notify GCI if at-home status changed (was at home, now not)
        if (wasAtHome) {
            coroutineScope.launch {
                notifyGciAtHomeStatus(false)
            }
        }

        Log.d(TAG, "Home location cleared")
    }

    override fun setFenceRadius(radiusMeters: Int) {
        val validRadius = radiusMeters.coerceAtLeast(1)
        val currentState = _geofenceState.value
        val previousAtHome = currentState.isAtHome

        _geofenceState.value = currentState.copy(fenceRadius = validRadius)

        // Persist the new radius
        coroutineScope.launch {
            dataStoreRepository.updatePreference(PreferenceKey.HOME_FENCE_RADIUS_METERS, validRadius)
        }

        // Recalculate at-home status with new radius
        if (currentState.isHomeSet) {
            val newAtHome = currentState.distanceFromHome <= validRadius
            if (newAtHome != previousAtHome) {
                _geofenceState.value = _geofenceState.value.copy(isAtHome = newAtHome)
                coroutineScope.launch {
                    notifyGciAtHomeStatus(newAtHome)
                }
            }
        }

        Log.d(TAG, "Fence radius updated: $validRadius meters")
    }

    /**
     * Process a GPS update to recalculate distance from home.
     *
     * Called continuously as GPS position changes. Only calculates distance
     * when a home location is set and GPS data is valid.
     */
    internal fun onGpsUpdate(gpsData: ProcessedGpsData) {
        val currentState = _geofenceState.value

        // Only calculate if home is set and GPS is valid
        if (!currentState.isHomeSet || !gpsData.isValid) {
            return
        }

        // Skip if position is at origin (invalid)
        if (gpsData.latitude == 0.0 && gpsData.longitude == 0.0) {
            return
        }

        recalculateDistance(gpsData.latitude, gpsData.longitude)
    }

    /**
     * Recalculate distance from the given position to home and update at-home status.
     */
    internal fun recalculateDistance(currentLat: Double, currentLon: Double) {
        val currentState = _geofenceState.value
        val homeLat = currentState.homeLatitude ?: return
        val homeLon = currentState.homeLongitude ?: return

        val distanceMeters = calculateDistanceMeters(currentLat, currentLon, homeLat, homeLon)
        val newAtHome = distanceMeters <= currentState.fenceRadius
        val previousAtHome = currentState.isAtHome

        _geofenceState.value = currentState.copy(
            distanceFromHome = distanceMeters,
            isAtHome = newAtHome
        )

        // Notify GCI if at-home status changed
        if (newAtHome != previousAtHome) {
            coroutineScope.launch {
                notifyGciAtHomeStatus(newAtHome)
            }
            Log.d(TAG, "At-home status changed: $newAtHome (distance: ${distanceMeters}m, radius: ${currentState.fenceRadius}m)")
        }
    }

    /**
     * Calculate distance between two GPS coordinates using the Haversine formula.
     *
     * @return Distance in meters
     */
    internal fun calculateDistanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Float {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (EARTH_RADIUS_METERS * c).toFloat()
    }

    /**
     * Validate that the given coordinates represent a valid GPS position.
     *
     * Rejects coordinates at exactly (0, 0) as this typically indicates
     * GPS is not available, as well as coordinates outside valid ranges.
     */
    internal fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        // Reject (0, 0) as GPS unavailable indicator
        if (lat == 0.0 && lon == 0.0) {
            return false
        }
        // Validate ranges
        if (lat < MIN_LATITUDE || lat > MAX_LATITUDE) {
            return false
        }
        if (lon < MIN_LONGITUDE || lon > MAX_LONGITUDE) {
            return false
        }
        // Reject NaN or Infinity
        if (lat.isNaN() || lat.isInfinite() || lon.isNaN() || lon.isInfinite()) {
            return false
        }
        return true
    }

    /**
     * Notify the GCI of an at-home status change.
     */
    private suspend fun notifyGciAtHomeStatus(isAtHome: Boolean) {
        try {
            telemetryConnection.sendIsHome(isAtHome)
            Log.d(TAG, "Notified GCI: isAtHome=$isAtHome")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to notify GCI of at-home status", e)
        }
    }

    /**
     * Load persisted home location from DataStore on startup.
     */
    private suspend fun loadPersistedHomeLocation() {
        try {
            val prefs = dataStoreRepository.getPreferences().first()
            val homeLat = prefs.homeLatitude
            val homeLon = prefs.homeLongitude
            val fenceRadius = prefs.homeFenceRadiusMeters

            if (homeLat != null && homeLon != null) {
                _geofenceState.value = GeofenceState(
                    homeLatitude = homeLat,
                    homeLongitude = homeLon,
                    isHomeSet = true,
                    fenceRadius = fenceRadius
                )
                Log.d(TAG, "Loaded persisted home: lat=$homeLat, lon=$homeLon, radius=$fenceRadius")
            } else {
                _geofenceState.value = GeofenceState(fenceRadius = fenceRadius)
                Log.d(TAG, "No persisted home location found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted home location", e)
        }
    }

    /**
     * Persist home location coordinates to DataStore.
     */
    private suspend fun persistHomeLocation(lat: Double, lon: Double) {
        try {
            dataStoreRepository.updatePreference(PreferenceKey.HOME_LATITUDE, lat)
            dataStoreRepository.updatePreference(PreferenceKey.HOME_LONGITUDE, lon)
            Log.d(TAG, "Persisted home location: lat=$lat, lon=$lon")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist home location", e)
        }
    }

    /**
     * Clear persisted home location from DataStore.
     */
    private suspend fun clearPersistedHomeLocation() {
        try {
            // Setting to the default (null) effectively clears the home location
            // We use a special sentinel approach - update with a value that the
            // DataStoreRepositoryImpl interprets as "clear"
            dataStoreRepository.updatePreference(PreferenceKey.HOME_LATITUDE, Double.NaN)
            dataStoreRepository.updatePreference(PreferenceKey.HOME_LONGITUDE, Double.NaN)
            Log.d(TAG, "Cleared persisted home location")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear persisted home location", e)
        }
    }

    /**
     * No-op GPS processor used for testing when GPS observation is not needed.
     */
    private object NoOpGpsProcessor : GpsProcessor {
        private val _gpsState = MutableStateFlow(ProcessedGpsData())
        override val gpsState: StateFlow<ProcessedGpsData> = _gpsState.asStateFlow()
        override val navigationData: StateFlow<com.golfcart.gcd.domain.gps.NavigationData> =
            MutableStateFlow(com.golfcart.gcd.domain.gps.NavigationData())
        override fun onLocationUpdate(
            latitude: Double, longitude: Double, altitude: Double,
            speedMps: Float, bearing: Float, accuracy: Float,
            satellites: Int, timestamp: Long, hasSpeed: Boolean, hasBearing: Boolean
        ) {}
        override fun onMeshtasticPositionUpdate(position: com.golfcart.gcd.domain.gps.MeshtasticPosition) {}
        override fun setDimmed(dimmed: Boolean) {}
    }
}
