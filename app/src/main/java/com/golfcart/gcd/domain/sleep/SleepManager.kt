package com.golfcart.gcd.domain.sleep

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the three-state power management system.
 *
 * Manages transitions between STARTUP_GRACE, GCI_MODE, and STANDALONE_MODE
 * based on GCI connection status and timeout periods. In standalone mode,
 * adjusts Meshtastic GPS update interval based on at-home status and ensures
 * the device never enters deep sleep (backlight dimming only).
 *
 * Persists odometer and driving hours before entering sleep.
 *
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
 */
interface SleepManager {

    /** Emits the current operating mode as a reactive flow. */
    val operatingMode: StateFlow<OperatingMode>

    /**
     * Persists odometer and driving hours before sleep/shutdown.
     *
     * Requirement 11.7
     */
    suspend fun persistBeforeSleep()
}
