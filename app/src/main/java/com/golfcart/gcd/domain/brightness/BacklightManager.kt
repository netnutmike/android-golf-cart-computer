package com.golfcart.gcd.domain.brightness

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for display brightness management.
 *
 * Controls display brightness based on time of day (day/night) and user activity.
 * Day brightness is used between sunrise and sunset; night brightness is used otherwise.
 * An inactivity timeout dims the display to off after a configurable period.
 * Touch or movement activity restores brightness to the appropriate level.
 *
 * Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7
 */
interface BacklightManager {

    /**
     * Emits the current brightness state including level, dimmed status, and day/night mode.
     */
    val brightnessState: StateFlow<BrightnessState>

    /**
     * Reports user activity (touch or movement) to the backlight manager.
     *
     * If the display is currently dimmed due to inactivity, this restores brightness
     * to the appropriate level (day or night). Also resets the inactivity timer.
     *
     * Requirement 10.6: Restore brightness on touch or movement activity.
     */
    fun reportActivity()
}
