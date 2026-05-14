package com.golfcart.gcd.domain.geofence

/**
 * Data class representing the current geofence state.
 *
 * Contains the home location coordinates (if set), the calculated distance
 * from the current position to home, and the at-home status determination.
 *
 * Requirements: 9.1, 9.2, 9.3, 9.4, 9.5, 9.6
 */
data class GeofenceState(
    /** Home latitude in decimal degrees, or null if home is not set. */
    val homeLatitude: Double? = null,

    /** Home longitude in decimal degrees, or null if home is not set. */
    val homeLongitude: Double? = null,

    /** Whether a home location has been set. */
    val isHomeSet: Boolean = false,

    /** Distance from current position to home in meters. 0 if home is not set. */
    val distanceFromHome: Float = 0f,

    /** Whether the device is currently within the home geofence radius. */
    val isAtHome: Boolean = false,

    /** Configured geofence radius in meters. Default: 500 meters. */
    val fenceRadius: Int = DEFAULT_FENCE_RADIUS_METERS
) {
    companion object {
        /** Default geofence radius in meters. */
        const val DEFAULT_FENCE_RADIUS_METERS = 500
    }
}
