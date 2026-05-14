package com.golfcart.gcd.domain.gps

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for GPS data processing.
 *
 * Consumes raw location updates from Android's FusedLocationProvider
 * and Meshtastic position packets (POSITION_APP port 3), applying a
 * multi-stage speed filtering pipeline to produce clean, reliable GPS
 * data for display and distance calculations.
 *
 * Android GPS is the primary source; Meshtastic GPS is a secondary source
 * used when Android GPS is unavailable or stale.
 *
 * Also provides navigation data (date, time, sunrise/sunset) via [NavigationData].
 */
interface GpsProcessor {

    /** Emits processed GPS data after speed filtering and heading calculation. */
    val gpsState: StateFlow<ProcessedGpsData>

    /** Emits navigation data including formatted date, time, and sunrise/sunset. */
    val navigationData: StateFlow<NavigationData>

    /**
     * Process a raw location update from the Android location provider (primary source).
     *
     * @param latitude Latitude in decimal degrees
     * @param longitude Longitude in decimal degrees
     * @param altitude Altitude in meters
     * @param speedMps Speed in meters per second (from GPS Doppler)
     * @param bearing Bearing/heading in degrees (0-360)
     * @param accuracy Horizontal accuracy in meters
     * @param satellites Number of satellites used in fix
     * @param timestamp UTC timestamp in milliseconds
     * @param hasSpeed Whether the speed value is valid
     * @param hasBearing Whether the bearing value is valid
     */
    fun onLocationUpdate(
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
    )

    /**
     * Process a position update received from the connected Meshtastic radio (secondary source).
     *
     * Meshtastic GPS data is used as a fallback when Android GPS is unavailable or stale.
     * If Android GPS has provided a recent update (within [ANDROID_GPS_STALENESS_MS]),
     * the Meshtastic position is ignored in favor of the more accurate Android GPS.
     *
     * @param position The parsed Meshtastic position data from a POSITION_APP packet.
     */
    fun onMeshtasticPositionUpdate(position: MeshtasticPosition)

    /**
     * Set whether the display is currently dimmed.
     *
     * When dimmed, the consecutive readings threshold for reporting movement
     * increases from 2 to 3.
     *
     * @param dimmed true if the display is currently dimmed
     */
    fun setDimmed(dimmed: Boolean)

    companion object {
        /**
         * If Android GPS has provided an update within this time window (in milliseconds),
         * Meshtastic position data is ignored as Android GPS is considered more accurate.
         * Default: 10 seconds.
         */
        const val ANDROID_GPS_STALENESS_MS = 10_000L
    }
}
