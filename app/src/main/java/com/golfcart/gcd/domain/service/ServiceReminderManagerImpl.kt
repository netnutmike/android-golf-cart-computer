package com.golfcart.gcd.domain.service

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

/**
 * Implementation of [ServiceReminderManager] that accumulates driving hours
 * based on GPS time deltas when the vehicle is in motion.
 *
 * Driving hours are accumulated only when:
 * - Filtered speed > 0 (vehicle is moving)
 * - Time delta between consecutive GPS updates is between 0 and 10 seconds
 *
 * GPS time is used as the primary time source for atomic clock accuracy.
 * Falls back to system clock when GPS time is unavailable (timestamp == 0).
 *
 * Hours are stored in tenths of hours (6-minute resolution) and persisted
 * every 1.0 hours of driving.
 *
 * Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9
 */
@Singleton
class ServiceReminderManagerImpl @Inject constructor(
    private val gpsProcessor: GpsProcessor,
    private val dataStoreRepository: DataStoreRepository,
    private val coroutineScope: CoroutineScope
) : ServiceReminderManager {

    internal val _serviceReminderState = MutableStateFlow(ServiceReminderState())
    override val serviceReminderState: StateFlow<ServiceReminderState> = _serviceReminderState.asStateFlow()

    /** Previous timestamp in milliseconds for time delta calculation. */
    private var previousTimestampMs: Long = 0L

    /** Whether we have a valid previous timestamp to calculate deltas from. */
    private var hasPreviousTimestamp: Boolean = false

    /**
     * Accumulated tenths-of-hours since last persistence.
     * Used to track when to persist (every 1.0 hours = 10 tenths).
     */
    private var tenthsSinceLastPersist: Int = 0

    /**
     * Sub-tenth accumulator in milliseconds.
     * Accumulates time until a full tenth of an hour (6 minutes = 360,000 ms) is reached.
     */
    private var subTenthAccumulatorMs: Long = 0L

    companion object {
        private const val TAG = "ServiceReminderMgr"

        /** Minimum valid time delta in milliseconds (0 seconds). */
        const val MIN_TIME_DELTA_MS = 0L

        /** Maximum valid time delta in milliseconds (10 seconds). */
        const val MAX_TIME_DELTA_MS = 10_000L

        /** One tenth of an hour in milliseconds (6 minutes). */
        const val TENTH_HOUR_MS = 360_000L

        /** Persistence interval in tenths of hours (1.0 hours = 10 tenths). */
        const val PERSIST_INTERVAL_TENTHS = 10
    }

    init {
        // Load persisted values on startup
        coroutineScope.launch {
            loadPersistedValues()
        }

        // Observe GPS state for time accumulation (skip for NoOp test processor)
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

    override fun resetServiceHours() {
        _serviceReminderState.value = _serviceReminderState.value.copy(
            tenthsOfHoursSinceService = 0
        )
        // Reset sub-tenth accumulator and persistence tracking
        subTenthAccumulatorMs = 0L
        tenthsSinceLastPersist = 0
        // Persist immediately after reset
        coroutineScope.launch {
            persistCurrentValues()
        }
        Log.d(TAG, "Service hours reset to 0")
    }

    override fun setServiceInterval(hours: Int) {
        _serviceReminderState.value = _serviceReminderState.value.copy(
            serviceIntervalHours = hours
        )
        Log.d(TAG, "Service interval set to $hours hours")
    }

    override suspend fun persistBeforeShutdown() {
        persistCurrentValues()
    }

    /**
     * Process a GPS update for driving hours accumulation.
     *
     * Accumulation rules:
     * 1. Only accumulate when filtered speed > 0 (vehicle is moving)
     * 2. Use GPS timestamp as primary time source
     * 3. Fall back to system clock if GPS timestamp is 0
     * 4. Only accept time deltas between 0 and 10 seconds
     */
    internal fun onGpsUpdate(gpsData: ProcessedGpsData) {
        // Determine the current timestamp
        // Use GPS time as primary source (Requirement 7.3)
        // Fall back to system clock if GPS time unavailable (Requirement 7.4)
        val currentTimestampMs = if (gpsData.timestamp > 0L) {
            gpsData.timestamp
        } else {
            System.currentTimeMillis()
        }

        // If no previous timestamp, establish one and return
        if (!hasPreviousTimestamp) {
            previousTimestampMs = currentTimestampMs
            hasPreviousTimestamp = true
            return
        }

        // Calculate time delta
        val timeDeltaMs = currentTimestampMs - previousTimestampMs

        // Update previous timestamp for next iteration
        previousTimestampMs = currentTimestampMs

        // Gate: only accept time deltas between 0 and 10 seconds (Requirement 7.5)
        if (timeDeltaMs < MIN_TIME_DELTA_MS || timeDeltaMs > MAX_TIME_DELTA_MS) {
            Log.d(TAG, "Rejecting time delta: ${timeDeltaMs}ms (outside 0-10s range)")
            return
        }

        // Gate: only accumulate when speed > 0 (Requirement 7.1)
        if (gpsData.speedMph <= 0) {
            return
        }

        // Accumulate time
        accumulateTime(timeDeltaMs)
    }

    /**
     * Accumulate driving time in milliseconds.
     * Converts to tenths of hours when enough time has accumulated.
     */
    internal fun accumulateTime(deltaMs: Long) {
        subTenthAccumulatorMs += deltaMs

        // Convert accumulated milliseconds to tenths of hours
        var newTenths = 0
        while (subTenthAccumulatorMs >= TENTH_HOUR_MS) {
            subTenthAccumulatorMs -= TENTH_HOUR_MS
            newTenths++
        }

        if (newTenths > 0) {
            val current = _serviceReminderState.value
            _serviceReminderState.value = current.copy(
                tenthsOfHoursSinceService = current.tenthsOfHoursSinceService + newTenths
            )

            // Track tenths since last persist
            tenthsSinceLastPersist += newTenths
            if (tenthsSinceLastPersist >= PERSIST_INTERVAL_TENTHS) {
                tenthsSinceLastPersist = 0
                coroutineScope.launch {
                    persistCurrentValues()
                }
            }

            Log.d(TAG, "Accumulated $newTenths tenths. Total: ${_serviceReminderState.value.tenthsOfHoursSinceService} tenths (${_serviceReminderState.value.hoursSinceService} hours)")
        }
    }

    /**
     * Load persisted driving hours from DataStore on startup.
     */
    private suspend fun loadPersistedValues() {
        try {
            val tenthsOfHours = dataStoreRepository.getPersistedDrivingHours().first()
            val preferences = dataStoreRepository.getPreferences().first()
            _serviceReminderState.value = ServiceReminderState(
                tenthsOfHoursSinceService = tenthsOfHours,
                serviceIntervalHours = preferences.serviceIntervalHours
            )
            Log.d(TAG, "Loaded persisted driving hours: $tenthsOfHours tenths (${tenthsOfHours / 10.0f} hours), interval: ${preferences.serviceIntervalHours} hours")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted driving hours", e)
        }
    }

    /**
     * Persist current driving hours to DataStore.
     */
    private suspend fun persistCurrentValues() {
        try {
            val current = _serviceReminderState.value
            dataStoreRepository.persistDrivingHours(current.tenthsOfHoursSinceService)
            Log.d(TAG, "Persisted driving hours: ${current.tenthsOfHoursSinceService} tenths")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist driving hours", e)
        }
    }
}
