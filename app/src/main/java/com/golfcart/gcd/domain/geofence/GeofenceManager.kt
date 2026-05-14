package com.golfcart.gcd.domain.geofence

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for home location geofencing.
 *
 * Manages the home location, calculates continuous distance from the current
 * GPS position to home, and determines at-home status based on a configurable
 * geofence radius. Notifies the GCI when at-home status changes.
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6, 9.7, 9.8
 */
interface GeofenceManager {

    /** Emits the current geofence state including distance and at-home status. */
    val geofenceState: StateFlow<GeofenceState>

    /**
     * Sets the home location to the specified GPS coordinates.
     *
     * The coordinates are validated and persisted to local storage.
     * If GPS is not available (indicated by invalid coordinates), the request
     * is rejected with an error.
     *
     * @param lat Latitude in decimal degrees
     * @param lon Longitude in decimal degrees
     * @return A [SetHomeResult] indicating success or failure with an error message
     *
     * Requirement 9.1, 9.7, 9.8
     */
    fun setHomeLocation(lat: Double, lon: Double): SetHomeResult

    /**
     * Clears the saved home location.
     *
     * Resets the geofence state to no-home-set, distance to 0, and at-home to false.
     * Persists the cleared state to local storage.
     *
     * Requirement 9.2
     */
    fun clearHomeLocation()

    /**
     * Updates the geofence radius.
     *
     * @param radiusMeters The new geofence radius in meters
     *
     * Requirement 9.3
     */
    fun setFenceRadius(radiusMeters: Int)
}

/**
 * Result of a set-home-location request.
 */
sealed class SetHomeResult {
    /** Home location was set successfully. */
    data object Success : SetHomeResult()

    /** Home location could not be set due to an error. */
    data class Error(val message: String) : SetHomeResult()
}
