package com.golfcart.gcd.domain.gps

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [GpsProcessor] with a multi-stage speed filtering pipeline.
 *
 * The filtering pipeline applies the following steps in order:
 * 1. Filter speeds below 2.5 mph to zero (GPS dither elimination)
 * 2. Reject speed spikes exceeding 8 mph/second acceleration
 * 3. When speed < 4 mph and decreasing → report zero (responsive stop detection)
 * 4. Require 2 consecutive readings above threshold before reporting movement (3 when dimmed)
 * 5. If speed invalid but location valid and last speed < 5 mph → report zero; else retain last speed
 *
 * Supports two GPS sources:
 * - **Primary:** Android FusedLocationProvider (higher accuracy, more frequent updates)
 * - **Secondary:** Meshtastic radio POSITION_APP packets (used as fallback when Android GPS is stale)
 *
 * Also delegates to [NavigationDataProcessor] for date/time/sunrise/sunset calculations.
 *
 * Requirements: 5.1, 5.2
 */
@Singleton
class GpsProcessorImpl @Inject constructor(
    private val navigationDataProcessor: NavigationDataProcessor
) : GpsProcessor {

    private val _gpsState = MutableStateFlow(ProcessedGpsData())
    override val gpsState: StateFlow<ProcessedGpsData> = _gpsState.asStateFlow()

    /** Navigation data (date, time, sunrise/sunset) delegated to [NavigationDataProcessor]. */
    override val navigationData: StateFlow<NavigationData>
        get() = navigationDataProcessor.navigationData

    // Speed filtering state
    private var previousRawSpeedMph: Float = 0f
    private var previousFilteredSpeedMph: Float = 0f
    private var previousTimestamp: Long = 0L
    private var consecutiveAboveThreshold: Int = 0
    private var isReportingMovement: Boolean = false

    // Satellite count debounce state
    private var consecutiveZeroSatellites: Int = 0
    private var lastReportedSatelliteCount: Int = 0

    // Dimmed state affects consecutive readings threshold
    @Volatile
    private var isDimmed: Boolean = false

    // Timestamp of the last Android GPS update (for staleness check)
    @Volatile
    private var lastAndroidGpsTimestamp: Long = 0L

    companion object {
        private const val TAG = "GpsProcessorImpl"

        /** Speeds below this threshold are filtered to zero (GPS dither). */
        const val DITHER_THRESHOLD_MPH = 2.5f

        /** Maximum allowed acceleration in mph per second. */
        const val MAX_ACCELERATION_MPH_PER_SEC = 8.0f

        /** Speed below which decreasing readings report zero. */
        const val STOP_DETECTION_THRESHOLD_MPH = 4.0f

        /** Threshold for the fallback rule when speed is invalid. */
        const val INVALID_SPEED_FALLBACK_THRESHOLD_MPH = 5.0f

        /** Consecutive readings required when display is active. */
        const val CONSECUTIVE_THRESHOLD_NORMAL = 2

        /** Consecutive readings required when display is dimmed. */
        const val CONSECUTIVE_THRESHOLD_DIMMED = 3

        /** Conversion factor from meters/second to miles/hour. */
        const val MPS_TO_MPH = 2.23694f

        /** Number of consecutive zero-satellite readings required before displaying zero. */
        const val SATELLITE_DEBOUNCE_COUNT = 3
    }

    override fun onLocationUpdate(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        speedMps: Float,
        bearing: Float,
        accuracy: Float,
        satellites: Int,
        timestamp: Long,
        hasSpeed: Boolean,
        hasBearing: Boolean
    ) {
        // Track the Android GPS update time for staleness comparison
        lastAndroidGpsTimestamp = System.currentTimeMillis()

        val rawSpeedMph = speedMps * MPS_TO_MPH
        val locationValid = latitude != 0.0 || longitude != 0.0

        val filteredSpeed = if (hasSpeed) {
            applySpeedFilter(rawSpeedMph, timestamp)
        } else {
            applyInvalidSpeedFallback(locationValid)
        }

        // Update previous state for next iteration
        if (hasSpeed) {
            previousRawSpeedMph = rawSpeedMph
            previousTimestamp = timestamp
        }
        previousFilteredSpeedMph = filteredSpeed

        val headingDegrees = if (hasBearing) bearing else _gpsState.value.headingDegrees
        val cardinalDirection = bearingToCardinal(headingDegrees)

        val debouncedSatellites = debounceSatelliteCount(satellites)
        val hdop = estimateHdop(debouncedSatellites)
        val satelliteHdopDisplay = formatSatelliteHdop(debouncedSatellites, hdop)

        _gpsState.value = ProcessedGpsData(
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            speedMph = filteredSpeed.toInt(),
            rawSpeedMph = rawSpeedMph,
            headingDegrees = headingDegrees,
            cardinalDirection = cardinalDirection,
            satelliteCount = debouncedSatellites,
            hdop = hdop,
            satelliteHdopDisplay = satelliteHdopDisplay,
            timestamp = timestamp,
            isValid = locationValid
        )

        // Forward GPS time and location to NavigationDataProcessor for date/time/sunrise/sunset
        if (timestamp > 0 && locationValid) {
            navigationDataProcessor.onGpsTimeUpdate(
                gpsTimestampMillis = timestamp,
                latitude = latitude,
                longitude = longitude
            )
        }
    }

    /**
     * Process a position update from the connected Meshtastic radio (secondary GPS source).
     *
     * The Meshtastic position is only used when Android GPS is stale (no update within
     * [GpsProcessor.ANDROID_GPS_STALENESS_MS]). This ensures the more accurate Android GPS
     * is always preferred when available.
     *
     * The same speed filtering pipeline is applied to Meshtastic speed data.
     *
     * @param position The parsed Meshtastic position data.
     */
    override fun onMeshtasticPositionUpdate(position: MeshtasticPosition) {
        val now = System.currentTimeMillis()
        val androidGpsAge = now - lastAndroidGpsTimestamp

        // Only use Meshtastic GPS when Android GPS is stale or has never been received
        if (lastAndroidGpsTimestamp > 0 && androidGpsAge < GpsProcessor.ANDROID_GPS_STALENESS_MS) {
            Log.d(TAG, "Ignoring Meshtastic position: Android GPS is fresh (${androidGpsAge}ms old)")
            return
        }

        Log.d(TAG, "Using Meshtastic position as secondary source " +
                "(Android GPS age: ${if (lastAndroidGpsTimestamp == 0L) "never" else "${androidGpsAge}ms"})")

        val rawSpeedMph = position.groundSpeedMps * MPS_TO_MPH
        val hasSpeed = position.groundSpeedMps > 0f || rawSpeedMph == 0f
        val hasBearing = position.groundTrackDegrees > 0f
        val locationValid = position.latitude != 0.0 || position.longitude != 0.0
        val timestamp = if (position.timestampMillis > 0) position.timestampMillis else now

        val filteredSpeed = if (hasSpeed) {
            applySpeedFilter(rawSpeedMph, timestamp)
        } else {
            applyInvalidSpeedFallback(locationValid)
        }

        // Update previous state for next iteration
        if (hasSpeed) {
            previousRawSpeedMph = rawSpeedMph
            previousTimestamp = timestamp
        }
        previousFilteredSpeedMph = filteredSpeed

        val headingDegrees = if (hasBearing) position.groundTrackDegrees else _gpsState.value.headingDegrees
        val cardinalDirection = bearingToCardinal(headingDegrees)

        // Use Meshtastic satellite count and HDOP if available
        val satellites = position.satelliteCount
        val debouncedSatellites = debounceSatelliteCount(satellites)
        val hdop = if (position.hdop > 0f) position.hdop else estimateHdop(debouncedSatellites)
        val satelliteHdopDisplay = formatSatelliteHdop(debouncedSatellites, hdop)

        _gpsState.value = ProcessedGpsData(
            latitude = position.latitude,
            longitude = position.longitude,
            altitude = position.altitude,
            speedMph = filteredSpeed.toInt(),
            rawSpeedMph = rawSpeedMph,
            headingDegrees = headingDegrees,
            cardinalDirection = cardinalDirection,
            satelliteCount = debouncedSatellites,
            hdop = hdop,
            satelliteHdopDisplay = satelliteHdopDisplay,
            timestamp = timestamp,
            isValid = locationValid
        )

        // Forward GPS time and location to NavigationDataProcessor
        if (timestamp > 0 && locationValid) {
            navigationDataProcessor.onGpsTimeUpdate(
                gpsTimestampMillis = timestamp,
                latitude = position.latitude,
                longitude = position.longitude
            )
        }
    }

    override fun setDimmed(dimmed: Boolean) {
        isDimmed = dimmed
    }

    /**
     * Apply the full speed filtering pipeline to a valid speed reading.
     *
     * Steps:
     * 1. Filter below 2.5 mph to zero
     * 2. Reject spikes > 8 mph/s acceleration
     * 3. Below 4 mph and decreasing → zero
     * 4. Require consecutive readings above threshold
     */
    internal fun applySpeedFilter(rawSpeedMph: Float, timestamp: Long): Float {
        // Step 1: GPS dither elimination — filter speeds below 2.5 mph to zero
        if (rawSpeedMph < DITHER_THRESHOLD_MPH) {
            consecutiveAboveThreshold = 0
            isReportingMovement = false
            return 0f
        }

        // Step 2: Reject speed spikes exceeding 8 mph/second acceleration
        if (previousTimestamp > 0 && timestamp > previousTimestamp) {
            val timeDeltaSeconds = (timestamp - previousTimestamp) / 1000.0f
            if (timeDeltaSeconds > 0) {
                val acceleration = Math.abs(rawSpeedMph - previousRawSpeedMph) / timeDeltaSeconds
                if (acceleration > MAX_ACCELERATION_MPH_PER_SEC) {
                    // Spike detected — retain previous filtered speed
                    return previousFilteredSpeedMph
                }
            }
        }

        // Step 3: When speed < 4 mph and decreasing → report zero
        if (rawSpeedMph < STOP_DETECTION_THRESHOLD_MPH && rawSpeedMph < previousRawSpeedMph) {
            consecutiveAboveThreshold = 0
            isReportingMovement = false
            return 0f
        }

        // Step 4: Require consecutive readings above threshold before reporting movement
        val requiredConsecutive = if (isDimmed) CONSECUTIVE_THRESHOLD_DIMMED else CONSECUTIVE_THRESHOLD_NORMAL
        consecutiveAboveThreshold++

        if (!isReportingMovement) {
            if (consecutiveAboveThreshold >= requiredConsecutive) {
                isReportingMovement = true
            } else {
                // Not enough consecutive readings yet — report zero
                return 0f
            }
        }

        return rawSpeedMph
    }

    /**
     * Apply fallback logic when speed data is invalid but location may be valid.
     *
     * Rule: If speed invalid but location valid and last speed < 5 mph → zero;
     * otherwise retain last known speed.
     */
    internal fun applyInvalidSpeedFallback(locationValid: Boolean): Float {
        return if (locationValid && previousFilteredSpeedMph < INVALID_SPEED_FALLBACK_THRESHOLD_MPH) {
            0f
        } else {
            previousFilteredSpeedMph
        }
    }

    /**
     * Debounce satellite count: require 3 consecutive zero readings before displaying zero.
     *
     * This prevents display flicker during brief signal dropouts. Any non-zero reading
     * resets the consecutive-zero counter and displays immediately.
     */
    internal fun debounceSatelliteCount(rawCount: Int): Int {
        return if (rawCount == 0) {
            consecutiveZeroSatellites++
            if (consecutiveZeroSatellites >= SATELLITE_DEBOUNCE_COUNT) {
                lastReportedSatelliteCount = 0
                0
            } else {
                lastReportedSatelliteCount
            }
        } else {
            consecutiveZeroSatellites = 0
            lastReportedSatelliteCount = rawCount
            rawCount
        }
    }

    /**
     * Format satellite count and HDOP as "sats/hdop" display string (e.g., "8/1.50").
     */
    internal fun formatSatelliteHdop(satellites: Int, hdop: Float): String {
        return "$satellites/${"%.2f".format(hdop)}"
    }

    /**
     * Convert bearing degrees to 16-point cardinal direction.
     */
    internal fun bearingToCardinal(degrees: Float): String {
        val directions = arrayOf(
            "N", "NNE", "NE", "ENE",
            "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW",
            "W", "WNW", "NW", "NNW"
        )
        // Normalize to 0-360 range
        val normalized = ((degrees % 360) + 360) % 360
        // Each direction covers 22.5 degrees, offset by 11.25 to center
        val index = ((normalized + 11.25f) / 22.5f).toInt() % 16
        return directions[index]
    }

    /**
     * Estimate HDOP from satellite count when direct HDOP data is unavailable.
     *
     * - ≥6 satellites: HDOP = 1.5
     * - 4-5 satellites: HDOP = 2.0
     * - <4 satellites: HDOP = 99.0
     */
    internal fun estimateHdop(satellites: Int): Float {
        return when {
            satellites >= 6 -> 1.5f
            satellites >= 4 -> 2.0f
            else -> 99.0f
        }
    }
}
