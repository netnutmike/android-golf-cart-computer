package com.golfcart.gcd.domain.brightness

/**
 * Represents the current brightness state of the display.
 *
 * Emitted via StateFlow by [BacklightManager] and consumed by the UI layer
 * to control actual display brightness.
 *
 * Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7
 */
data class BrightnessState(
    /** Current brightness level (0-10 scale). 0 means display is off/dimmed. */
    val brightnessLevel: Int = DEFAULT_DAY_BRIGHTNESS,

    /** Whether the display is currently dimmed due to inactivity timeout. */
    val isDimmed: Boolean = false,

    /** Whether the current brightness is the day brightness (true) or night brightness (false). */
    val isDaytime: Boolean = true
) {
    companion object {
        const val DEFAULT_DAY_BRIGHTNESS = 7
        const val DEFAULT_NIGHT_BRIGHTNESS = 3
        const val MIN_BRIGHTNESS = 0
        const val MAX_BRIGHTNESS = 10
    }
}
