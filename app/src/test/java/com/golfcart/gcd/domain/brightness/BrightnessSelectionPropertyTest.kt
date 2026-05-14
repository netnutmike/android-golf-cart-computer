package com.golfcart.gcd.domain.brightness

import net.jqwik.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Property-based tests for brightness level selection.
 *
 * Feature: android-golf-cart-computer, Property 19: Brightness level selection
 *
 * The core property: the selected brightness level should be the day brightness
 * value when the current time is between sunrise and sunset (isDaytime = true),
 * and the night brightness value otherwise (isDaytime = false).
 *
 * **Validates: Requirements 10.1, 10.2**
 */
@Label("Property 19: Brightness level selection")
@Tag("Feature: android-golf-cart-computer")
@Tag("Property 19: Brightness level selection")
class BrightnessSelectionPropertyTest {

    // =========================================================================
    // Property 19: Brightness level selection
    // =========================================================================

    /**
     * Property 19a: When isDaytime is true (between sunrise and sunset),
     * selectBrightnessLevel returns the configured day brightness level.
     *
     * **Validates: Requirements 10.1**
     */
    @Property(tries = 10)
    @Label("Day brightness returned when between sunrise and sunset")
    fun dayBrightnessReturnedWhenDaytime(
        @ForAll("brightnessLevels") dayBrightness: Int,
        @ForAll("brightnessLevels") nightBrightness: Int,
        @ForAll("timeoutValues") timeoutMinutes: Int
    ) {
        val manager = BacklightManagerImpl(
            dayBrightness = dayBrightness,
            nightBrightness = nightBrightness,
            timeoutMinutes = timeoutMinutes,
            isDaytime = true
        )

        val result = manager.selectBrightnessLevel(daytime = true)

        assertEquals(dayBrightness, result,
            "selectBrightnessLevel(true) should return dayBrightness=$dayBrightness, " +
            "but got $result (nightBrightness=$nightBrightness)")
    }

    /**
     * Property 19b: When isDaytime is false (between sunset and sunrise),
     * selectBrightnessLevel returns the configured night brightness level.
     *
     * **Validates: Requirements 10.2**
     */
    @Property(tries = 10)
    @Label("Night brightness returned when between sunset and sunrise")
    fun nightBrightnessReturnedWhenNighttime(
        @ForAll("brightnessLevels") dayBrightness: Int,
        @ForAll("brightnessLevels") nightBrightness: Int,
        @ForAll("timeoutValues") timeoutMinutes: Int
    ) {
        val manager = BacklightManagerImpl(
            dayBrightness = dayBrightness,
            nightBrightness = nightBrightness,
            timeoutMinutes = timeoutMinutes,
            isDaytime = false
        )

        val result = manager.selectBrightnessLevel(daytime = false)

        assertEquals(nightBrightness, result,
            "selectBrightnessLevel(false) should return nightBrightness=$nightBrightness, " +
            "but got $result (dayBrightness=$dayBrightness)")
    }

    /**
     * Property 19c: The initial brightness state reflects the correct level based
     * on the isDaytime flag — day brightness when daytime, night brightness otherwise.
     * This validates the full state emission, not just the selectBrightnessLevel method.
     *
     * **Validates: Requirements 10.1, 10.2**
     */
    @Property(tries = 10)
    @Label("Initial brightness state matches day/night selection")
    fun initialBrightnessStateMatchesDayNightSelection(
        @ForAll("brightnessLevels") dayBrightness: Int,
        @ForAll("brightnessLevels") nightBrightness: Int,
        @ForAll("timeoutValues") timeoutMinutes: Int,
        @ForAll isDaytime: Boolean
    ) {
        val manager = BacklightManagerImpl(
            dayBrightness = dayBrightness,
            nightBrightness = nightBrightness,
            timeoutMinutes = timeoutMinutes,
            isDaytime = isDaytime
        )

        val state = manager.brightnessState.value
        val expectedLevel = if (isDaytime) dayBrightness else nightBrightness

        assertEquals(expectedLevel, state.brightnessLevel,
            "brightnessState.brightnessLevel should be $expectedLevel " +
            "(isDaytime=$isDaytime, dayBrightness=$dayBrightness, nightBrightness=$nightBrightness)")
        assertEquals(isDaytime, state.isDaytime,
            "brightnessState.isDaytime should match the configured isDaytime=$isDaytime")
        assertFalse(state.isDimmed,
            "brightnessState.isDimmed should be false on initial creation")
    }

    /**
     * Property 19d: The brightness selection is deterministic — for the same
     * configuration and daytime flag, the result is always the same.
     *
     * **Validates: Requirements 10.1, 10.2**
     */
    @Property(tries = 10)
    @Label("Brightness selection is deterministic")
    fun brightnessSelectionIsDeterministic(
        @ForAll("brightnessLevels") dayBrightness: Int,
        @ForAll("brightnessLevels") nightBrightness: Int,
        @ForAll("timeoutValues") timeoutMinutes: Int,
        @ForAll isDaytime: Boolean
    ) {
        val manager1 = BacklightManagerImpl(
            dayBrightness = dayBrightness,
            nightBrightness = nightBrightness,
            timeoutMinutes = timeoutMinutes,
            isDaytime = isDaytime
        )

        val manager2 = BacklightManagerImpl(
            dayBrightness = dayBrightness,
            nightBrightness = nightBrightness,
            timeoutMinutes = timeoutMinutes,
            isDaytime = isDaytime
        )

        val result1 = manager1.selectBrightnessLevel(isDaytime)
        val result2 = manager2.selectBrightnessLevel(isDaytime)

        assertEquals(result1, result2,
            "Same configuration and isDaytime=$isDaytime should always produce the same brightness level")
    }

    /**
     * Property 19e: The brightness level is always within the valid range [0, 10]
     * regardless of the configured values.
     *
     * **Validates: Requirements 10.1, 10.2**
     */
    @Property(tries = 10)
    @Label("Brightness level is always within valid range 0-10")
    fun brightnessLevelAlwaysWithinValidRange(
        @ForAll("brightnessLevels") dayBrightness: Int,
        @ForAll("brightnessLevels") nightBrightness: Int,
        @ForAll("timeoutValues") timeoutMinutes: Int,
        @ForAll isDaytime: Boolean
    ) {
        val manager = BacklightManagerImpl(
            dayBrightness = dayBrightness,
            nightBrightness = nightBrightness,
            timeoutMinutes = timeoutMinutes,
            isDaytime = isDaytime
        )

        val result = manager.selectBrightnessLevel(isDaytime)

        assertTrue(result in BrightnessState.MIN_BRIGHTNESS..BrightnessState.MAX_BRIGHTNESS,
            "Brightness level $result should be within [${BrightnessState.MIN_BRIGHTNESS}, ${BrightnessState.MAX_BRIGHTNESS}]")
    }

    // =========================================================================
    // Generators
    // =========================================================================

    /**
     * Brightness levels in the valid range 0-10.
     */
    @Provide
    fun brightnessLevels(): Arbitrary<Int> {
        return Arbitraries.integers().between(
            BrightnessState.MIN_BRIGHTNESS,
            BrightnessState.MAX_BRIGHTNESS
        )
    }

    /**
     * Timeout values in minutes — 0 (disabled) through realistic max values.
     */
    @Provide
    fun timeoutValues(): Arbitrary<Int> {
        return Arbitraries.integers().between(0, 60)
    }
}
