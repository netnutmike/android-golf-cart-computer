package com.golfcart.gcd.domain.odometer

import android.util.Log
import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.domain.gps.GpsProcessor
import com.golfcart.gcd.domain.gps.MeshtasticPosition
import com.golfcart.gcd.domain.gps.NavigationData
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
 * Implementation of [OdometerManager] that accumulates distance using GPS position calculations.
 *
 * Distance accumulation is gated by Doppler speed (filtered speed > 0) to prevent
 * false accumulation when stationary. Position-based distance is calculated using
 * the Haversine formula and validated against minimum thresholds and maximum speed limits.
 *
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 6.10
 */
@Singleton
class OdometerManagerImpl @Inject constructor(
    private val gpsProcessor: GpsProcessor,
    private val dataStoreRepository: DataStoreRepository,
    private val coroutineScope: CoroutineScope
) : OdometerManager {

    internal val _odometerState = MutableStateFlow(OdometerState())
    override val odometerState: StateFlow<OdometerState> = _odometerState.asStateFlow()

    // Previous position for distance calculation
    private var previousLatitude: Double = 0.0
    private var previousLongitude: Double = 0.0
    private var previousTimestamp: Long = 0L
    private var hasPreviousPosition: Boolean = false

    // Persistence tracking
    private var distanceSinceLastPersist: Float = 0.0f

    companion object {
        private const val TAG = "OdometerManagerImpl"

        /** Minimum position change in miles when Doppler speed confirms motion (2.6 feet). */
        const val MIN_DISTANCE_WITH_DOPPLER_MILES = 0.0005f

        /** Minimum position change in miles when Doppler speed is unavailable (10 feet). */
        const val MIN_DISTANCE_NO_DOPPLER_MILES = 0.002f

        /** Maximum position-based speed in mph before rejecting as GPS error. */
        const val MAX_POSITION_SPEED_MPH = 30.0f

        /** Odometer rollover value in miles. */
        const val ROLLOVER_MILES = 100_000.0f

        /** Persist interval in miles. */
        const val PERSIST_INTERVAL_MILES = 0.5f

        /** Earth's mean radius in miles for Haversine calculation. */
        const val EARTH_RADIUS_MILES = 3958.8
    }

    init {
        // Load persisted values on startup
        coroutineScope.launch {
            loadPersistedValues()
        }

        // Observe GPS state for distance accumulation (skip for NoOp test processor)
        if (gpsProcessor !== NoOpGpsProcessor) {
            coroutineScope.launch {
                gpsProcessor.gpsState.collect { gpsData ->
                    onGpsUpdate(gpsData)
                }
            }
        }
    }

    /**
     * Constructor for testing that does NOT auto-start GPS observation.
     * Allows tests to call [onGpsUpdate] directly without blocking on flow collection.
     */
    internal constructor(
        dataStoreRepository: DataStoreRepository,
        coroutineScope: CoroutineScope
    ) : this(
        gpsProcessor = NoOpGpsProcessor,
        dataStoreRepository = dataStoreRepository,
        coroutineScope = coroutineScope
    )

    /** No-op GPS processor used for testing when GPS observation is not needed. */
    private object NoOpGpsProcessor : GpsProcessor {
        private val _gpsState = MutableStateFlow(ProcessedGpsData())
        override val gpsState: StateFlow<ProcessedGpsData> = _gpsState.asStateFlow()
        override val navigationData: StateFlow<NavigationData> = MutableStateFlow(NavigationData())
        override fun onLocationUpdate(
            latitude: Double, longitude: Double, altitude: Double,
            speedMps: Float, bearing: Float, accuracy: Float,
            satellites: Int, timestamp: Long, hasSpeed: Boolean, hasBearing: Boolean
        ) {}
        override fun onMeshtasticPositionUpdate(position: MeshtasticPosition) {}
        override fun setDimmed(dimmed: Boolean) {}
    }

    override fun resetTripOdometer() {
        val current = _odometerState.value
        _odometerState.value = current.copy(tripMiles = 0.0f)
        // Persist immediately after reset
        coroutineScope.launch {
            persistCurrentValues()
        }
    }

    override suspend fun persistBeforeShutdown() {
        persistCurrentValues()
    }

    /**
     * Process a GPS update for distance accumulation.
     *
     * Gating rules:
     * 1. Only accumulate when filtered speed > 0 (Doppler speed gating)
     * 2. GPS data must be valid
     * 3. Position change must exceed minimum threshold
     * 4. Position-based speed must not exceed 30 mph
     */
    internal fun onGpsUpdate(gpsData: ProcessedGpsData) {
        // Gate: only accumulate when filtered speed > 0
        if (gpsData.speedMph <= 0) {
            // Update previous position even when stopped to avoid
            // accumulating drift distance when motion resumes
            if (gpsData.isValid && (gpsData.latitude != 0.0 || gpsData.longitude != 0.0)) {
                previousLatitude = gpsData.latitude
                previousLongitude = gpsData.longitude
                previousTimestamp = gpsData.timestamp
                hasPreviousPosition = true
            }
            return
        }

        // Gate: GPS data must be valid
        if (!gpsData.isValid) {
            return
        }

        // Gate: must have a valid position (not 0,0)
        if (gpsData.latitude == 0.0 && gpsData.longitude == 0.0) {
            return
        }

        // If no previous position, establish one and return
        if (!hasPreviousPosition) {
            previousLatitude = gpsData.latitude
            previousLongitude = gpsData.longitude
            previousTimestamp = gpsData.timestamp
            hasPreviousPosition = true
            return
        }

        // Calculate distance between previous and current position
        val distanceMiles = calculateDistanceMiles(
            previousLatitude, previousLongitude,
            gpsData.latitude, gpsData.longitude
        )

        // Determine minimum distance threshold
        // rawSpeedMph > 0 indicates Doppler speed is available
        val hasDopplerSpeed = gpsData.rawSpeedMph > 0f
        val minDistance = if (hasDopplerSpeed) {
            MIN_DISTANCE_WITH_DOPPLER_MILES
        } else {
            MIN_DISTANCE_NO_DOPPLER_MILES
        }

        // Gate: position change must exceed minimum threshold
        if (distanceMiles < minDistance) {
            return
        }

        // Gate: reject position-based speed > 30 mph as GPS error
        if (gpsData.timestamp > previousTimestamp && previousTimestamp > 0L) {
            val timeDeltaHours = (gpsData.timestamp - previousTimestamp) / 3_600_000.0f
            if (timeDeltaHours > 0f) {
                val positionBasedSpeed = distanceMiles / timeDeltaHours
                if (positionBasedSpeed > MAX_POSITION_SPEED_MPH) {
                    Log.d(TAG, "Rejecting distance: position-based speed ${positionBasedSpeed} mph > $MAX_POSITION_SPEED_MPH mph")
                    // Update position to avoid accumulating the rejected segment later
                    previousLatitude = gpsData.latitude
                    previousLongitude = gpsData.longitude
                    previousTimestamp = gpsData.timestamp
                    return
                }
            }
        }

        // Accumulate distance
        accumulateDistance(distanceMiles)

        // Update previous position
        previousLatitude = gpsData.latitude
        previousLongitude = gpsData.longitude
        previousTimestamp = gpsData.timestamp
    }

    /**
     * Accumulate distance to both total and trip odometers.
     * Handles rollover at 100,000 miles and persistence every 0.5 miles.
     */
    internal fun accumulateDistance(distanceMiles: Float) {
        val current = _odometerState.value

        var newTotal = current.totalMiles + distanceMiles
        // Rollover at 100,000 miles
        if (newTotal >= ROLLOVER_MILES) {
            newTotal -= ROLLOVER_MILES
        }

        val newTrip = current.tripMiles + distanceMiles

        // Round to 1 decimal place for display precision
        _odometerState.value = OdometerState(
            totalMiles = roundToOneDecimal(newTotal),
            tripMiles = roundToOneDecimal(newTrip)
        )

        // Track distance since last persist
        distanceSinceLastPersist += distanceMiles
        if (distanceSinceLastPersist >= PERSIST_INTERVAL_MILES) {
            distanceSinceLastPersist = 0.0f
            coroutineScope.launch {
                persistCurrentValues()
            }
        }
    }

    /**
     * Calculate distance between two GPS coordinates using the Haversine formula.
     *
     * @return Distance in miles
     */
    internal fun calculateDistanceMiles(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Float {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (EARTH_RADIUS_MILES * c).toFloat()
    }

    /**
     * Round a float value to 1 decimal place.
     */
    internal fun roundToOneDecimal(value: Float): Float {
        return (Math.round(value * 10.0f) / 10.0f)
    }

    /**
     * Load persisted odometer values from DataStore on startup.
     */
    private suspend fun loadPersistedValues() {
        try {
            val odometerData = dataStoreRepository.getPersistedOdometer().first()
            _odometerState.value = OdometerState(
                totalMiles = odometerData.accumDistance,
                tripMiles = odometerData.tripDistance
            )
            Log.d(TAG, "Loaded persisted odometer: total=${odometerData.accumDistance}, trip=${odometerData.tripDistance}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted odometer values", e)
        }
    }

    /**
     * Persist current odometer values to DataStore.
     */
    private suspend fun persistCurrentValues() {
        try {
            val current = _odometerState.value
            dataStoreRepository.persistOdometer(current.totalMiles, current.tripMiles)
            Log.d(TAG, "Persisted odometer: total=${current.totalMiles}, trip=${current.tripMiles}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist odometer values", e)
        }
    }
}
