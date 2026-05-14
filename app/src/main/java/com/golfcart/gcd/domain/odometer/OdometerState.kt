package com.golfcart.gcd.domain.odometer

/**
 * Represents the current state of the odometer system.
 *
 * Both values are displayed with 1 decimal place precision in miles.
 *
 * Requirements: 6.1, 6.2, 6.3
 */
data class OdometerState(
    /** Total accumulated distance in miles. Rolls over at 100,000 miles. */
    val totalMiles: Float = 0.0f,

    /** Trip odometer distance in miles. Resettable by the user. */
    val tripMiles: Float = 0.0f
)
