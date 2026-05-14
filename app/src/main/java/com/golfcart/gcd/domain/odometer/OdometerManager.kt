package com.golfcart.gcd.domain.odometer

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for odometer distance tracking.
 *
 * Accumulates total distance traveled using GPS position-based calculations,
 * gated by Doppler speed to prevent false accumulation when stationary.
 * Also maintains a resettable trip odometer.
 *
 * Distance Accumulation Rules:
 * - Only accumulate when filtered speed > 0 (Doppler speed gating)
 * - Minimum position change: 2.6 feet (0.0005 miles) with Doppler confirmation
 * - Fallback minimum (no Doppler): 10 feet (0.002 miles)
 * - Reject position-based speed > 30 mph as GPS error
 * - Rollover at 100,000 miles
 * - Persist every 0.5 miles and before sleep/shutdown
 *
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7, 6.8, 6.9, 6.10
 */
interface OdometerManager {

    /** Emits the current odometer state (total miles and trip miles). */
    val odometerState: StateFlow<OdometerState>

    /**
     * Resets the trip odometer to zero without affecting the total odometer.
     *
     * Requirement 6.2
     */
    fun resetTripOdometer()

    /**
     * Persists the current odometer values to storage.
     * Called before sleep/shutdown.
     *
     * Requirement 6.9
     */
    suspend fun persistBeforeShutdown()
}
