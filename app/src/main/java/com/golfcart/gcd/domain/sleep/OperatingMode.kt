package com.golfcart.gcd.domain.sleep

/**
 * Represents the three operating modes of the power management system.
 *
 * State Transitions:
 * - STARTUP_GRACE → GCI_MODE: GCI connects during grace period
 * - STARTUP_GRACE → STANDALONE_MODE: Grace period expires without GCI connection
 * - GCI_MODE → STANDALONE_MODE: GCI disconnected for timeout period
 * - STANDALONE_MODE → GCI_MODE: GCI reconnects
 *
 * Requirements: 11.1
 */
enum class OperatingMode {
    /**
     * Initial state after startup. Waits for the GCI to connect before
     * determining the operating mode. Duration equals the backlight timeout
     * setting (minimum 30 seconds).
     *
     * Requirement 11.2
     */
    STARTUP_GRACE,

    /**
     * GCI is connected and communicating. Sleep behavior is controlled by
     * the GCI connection status.
     *
     * Requirement 11.3
     */
    GCI_MODE,

    /**
     * GCI has never connected or has been disconnected for the timeout period.
     * Backlight dimming only — never enters deep sleep. Meshtastic GPS interval
     * is adjusted based on at-home status.
     *
     * Requirement 11.4
     */
    STANDALONE_MODE
}
