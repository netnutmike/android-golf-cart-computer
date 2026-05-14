package com.golfcart.gcd.domain.service

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for service reminder and maintenance tracking.
 *
 * Accumulates driving hours only when the vehicle is in motion (speed > 0).
 * Hours are stored in tenths of hours (6-minute resolution) for precision.
 * Uses GPS time as the primary time source (atomic clock accuracy), falling
 * back to system clock when GPS time is unavailable.
 *
 * Time deltas are validated to be between 0 and 10 seconds to filter GPS
 * glitches and device reboots.
 *
 * Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7, 7.8, 7.9
 */
interface ServiceReminderManager {

    /** Emits the current service reminder state (hours since service, interval). */
    val serviceReminderState: StateFlow<ServiceReminderState>

    /**
     * Resets the service hour counter to zero after maintenance is performed.
     *
     * Requirement 7.9
     */
    fun resetServiceHours()

    /**
     * Updates the configurable service interval.
     *
     * @param hours The new service interval in hours
     *
     * Requirement 7.7
     */
    fun setServiceInterval(hours: Int)

    /**
     * Persists the current driving hours to storage.
     * Called before sleep/shutdown.
     *
     * Requirement 7.8
     */
    suspend fun persistBeforeShutdown()
}
