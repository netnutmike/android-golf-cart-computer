package com.golfcart.gcd.domain.gps;

import net.jqwik.api.*;
import net.jqwik.api.constraints.FloatRange;
import net.jqwik.api.constraints.IntRange;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for navigation calculations: cardinal direction mapping,
 * satellite count debounce, and HDOP estimation.
 *
 * Feature: android-golf-cart-computer, Properties 12, 13, 14
 *
 * Validates: Requirements 5.8, 5.11, 5.12
 */
@Label("Properties 12-14: Navigation calculations")
@Tag("Feature: android-golf-cart-computer")
@Tag("Property 12: Cardinal direction mapping")
@Tag("Property 13: Satellite count debounce")
@Tag("Property 14: HDOP estimation from satellite count")
class NavigationPropertyTest {

    private static final List<String> VALID_DIRECTIONS = Arrays.asList(
            "N", "NNE", "NE", "ENE",
            "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW",
            "W", "WNW", "NW", "NNW"
    );

    // --- Property 12: Cardinal direction mapping ---

    /**
     * Property 12a: For any bearing in [0, 360), the result is one of the 16 valid directions.
     *
     * **Validates: Requirements 5.8**
     */
    @Property(tries = 10)
    @Label("Any bearing in [0, 360) maps to a valid 16-point direction")
    void anyBearingMapsToValidDirection(
            @ForAll @FloatRange(min = 0f, max = 359.99f) float bearing) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());
        String result = invokeBearingToCardinal(processor, bearing);

        assertTrue(VALID_DIRECTIONS.contains(result),
                "Bearing " + bearing + "° mapped to '" + result +
                "' which is not one of the 16 valid cardinal directions");
    }

    /**
     * Property 12b: Same input always produces same output (deterministic mapping).
     *
     * **Validates: Requirements 5.8**
     */
    @Property(tries = 10)
    @Label("Cardinal direction mapping is deterministic")
    void cardinalDirectionMappingIsDeterministic(
            @ForAll @FloatRange(min = 0f, max = 359.99f) float bearing) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());
        String result1 = invokeBearingToCardinal(processor, bearing);
        String result2 = invokeBearingToCardinal(processor, bearing);

        assertEquals(result1, result2,
                "Bearing " + bearing + "° produced different results: '" +
                result1 + "' and '" + result2 + "'");
    }

    /**
     * Property 12c: Each direction covers exactly 22.5 degrees, centered on the cardinal point.
     * The center of N is 0°, NNE is 22.5°, NE is 45°, etc.
     * A bearing at the exact center of a direction's range must map to that direction.
     *
     * **Validates: Requirements 5.8**
     */
    @Property(tries = 10)
    @Label("Direction centers map correctly at 22.5° intervals")
    void directionCentersMapCorrectly(
            @ForAll @IntRange(min = 0, max = 15) int directionIndex) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());
        // Center of each direction: N=0, NNE=22.5, NE=45, ENE=67.5, ...
        float centerBearing = directionIndex * 22.5f;
        String expected = VALID_DIRECTIONS.get(directionIndex);

        String result = invokeBearingToCardinal(processor, centerBearing);

        assertEquals(expected, result,
                "Center bearing " + centerBearing + "° should map to '" +
                expected + "' but got '" + result + "'");
    }

    /**
     * Property 12d: Bearings within ±11.25° of a direction's center map to that direction.
     * This validates the 22.5° interval boundaries.
     *
     * **Validates: Requirements 5.8**
     */
    @Property(tries = 10)
    @Label("Bearings within ±11.25° of center map to correct direction")
    void bearingsWithinHalfIntervalMapCorrectly(
            @ForAll @IntRange(min = 0, max = 15) int directionIndex,
            @ForAll @FloatRange(min = -11.0f, max = 11.0f) float offset) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());
        float centerBearing = directionIndex * 22.5f;
        // Apply offset and normalize to [0, 360)
        float bearing = ((centerBearing + offset) % 360 + 360) % 360;
        String expected = VALID_DIRECTIONS.get(directionIndex);

        String result = invokeBearingToCardinal(processor, bearing);

        assertEquals(expected, result,
                "Bearing " + bearing + "° (center=" + centerBearing +
                "° + offset=" + offset + "°) should map to '" +
                expected + "' but got '" + result + "'");
    }

    // --- Property 13: Satellite count debounce ---

    /**
     * Property 13a: Zero satellite count is only displayed after 3 or more consecutive zeros.
     * Fewer than 3 consecutive zeros should retain the last non-zero value.
     *
     * **Validates: Requirements 5.11**
     */
    @Property(tries = 10)
    @Label("Zero only displayed after 3 consecutive zero readings")
    void zeroOnlyAfterThreeConsecutiveZeros(
            @ForAll @IntRange(min = 1, max = 20) int initialSatCount,
            @ForAll @IntRange(min = 1, max = 2) int zeroCount) {

        // Feed a non-zero value, then fewer than 3 zeros — should NOT report zero
        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());

        // Establish a non-zero satellite count
        int established = invokeDebounceSatelliteCount(processor, initialSatCount);
        assertEquals(initialSatCount, established);

        // Feed fewer than 3 zeros — should retain last non-zero value
        int lastResult = established;
        for (int i = 0; i < zeroCount; i++) {
            lastResult = invokeDebounceSatelliteCount(processor, 0);
        }

        assertEquals(initialSatCount, lastResult,
                "After " + zeroCount + " consecutive zeros (< 3), should retain " +
                "last non-zero value " + initialSatCount + " but got " + lastResult);
    }

    /**
     * Property 13b: After exactly 3 consecutive zeros, zero IS displayed.
     *
     * **Validates: Requirements 5.11**
     */
    @Property(tries = 10)
    @Label("Zero displayed after exactly 3 consecutive zeros")
    void zeroDisplayedAfterThreeConsecutiveZeros(
            @ForAll @IntRange(min = 1, max = 20) int initialSatCount,
            @ForAll @IntRange(min = 3, max = 10) int zeroCount) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());

        // Establish a non-zero satellite count
        invokeDebounceSatelliteCount(processor, initialSatCount);

        // Feed 3 or more zeros — should eventually report zero
        int lastResult = -1;
        for (int i = 0; i < zeroCount; i++) {
            lastResult = invokeDebounceSatelliteCount(processor, 0);
        }

        assertEquals(0, lastResult,
                "After " + zeroCount + " consecutive zeros (>= 3), should display 0 " +
                "but got " + lastResult);
    }

    /**
     * Property 13c: Any non-zero reading resets the counter and displays immediately.
     *
     * **Validates: Requirements 5.11**
     */
    @Property(tries = 10)
    @Label("Non-zero reading resets counter and displays immediately")
    void nonZeroReadingResetsCounterAndDisplaysImmediately(
            @ForAll @IntRange(min = 1, max = 20) int initialSatCount,
            @ForAll @IntRange(min = 1, max = 2) int zerosBeforeReset,
            @ForAll @IntRange(min = 1, max = 20) int newSatCount) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());

        // Establish initial count
        invokeDebounceSatelliteCount(processor, initialSatCount);

        // Feed some zeros (but fewer than 3)
        for (int i = 0; i < zerosBeforeReset; i++) {
            invokeDebounceSatelliteCount(processor, 0);
        }

        // Feed a non-zero value — should display immediately
        int result = invokeDebounceSatelliteCount(processor, newSatCount);

        assertEquals(newSatCount, result,
                "Non-zero reading " + newSatCount + " after " + zerosBeforeReset +
                " zeros should display immediately, but got " + result);
    }

    /**
     * Property 13d: After a non-zero reading resets the counter, it takes
     * another 3 consecutive zeros before zero is displayed again.
     *
     * **Validates: Requirements 5.11**
     */
    @Property(tries = 10)
    @Label("Counter reset requires fresh 3 consecutive zeros")
    void counterResetRequiresFreshThreeConsecutiveZeros(
            @ForAll @IntRange(min = 1, max = 20) int initialSatCount,
            @ForAll @IntRange(min = 1, max = 2) int zerosBeforeReset,
            @ForAll @IntRange(min = 1, max = 20) int resetValue,
            @ForAll @IntRange(min = 1, max = 2) int zerosAfterReset) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());

        // Establish, add some zeros, then reset with non-zero
        invokeDebounceSatelliteCount(processor, initialSatCount);
        for (int i = 0; i < zerosBeforeReset; i++) {
            invokeDebounceSatelliteCount(processor, 0);
        }
        invokeDebounceSatelliteCount(processor, resetValue);

        // Now feed fewer than 3 zeros — should NOT report zero
        int lastResult = -1;
        for (int i = 0; i < zerosAfterReset; i++) {
            lastResult = invokeDebounceSatelliteCount(processor, 0);
        }

        assertEquals(resetValue, lastResult,
                "After reset to " + resetValue + " and " + zerosAfterReset +
                " zeros (< 3), should retain " + resetValue + " but got " + lastResult);
    }

    // --- Property 14: HDOP estimation from satellite count ---

    /**
     * Property 14a: For satellite count >= 6, HDOP is 1.5.
     *
     * **Validates: Requirements 5.12**
     */
    @Property(tries = 10)
    @Label("HDOP is 1.5 for satellite count >= 6")
    void hdopIs1Point5ForSixOrMoreSatellites(
            @ForAll @IntRange(min = 6, max = 30) int satellites) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());
        float hdop = invokeEstimateHdop(processor, satellites);

        assertEquals(1.5f, hdop,
                "Satellite count " + satellites + " (>= 6) should produce HDOP 1.5 " +
                "but got " + hdop);
    }

    /**
     * Property 14b: For satellite count 4-5, HDOP is 2.0.
     *
     * **Validates: Requirements 5.12**
     */
    @Property(tries = 10)
    @Label("HDOP is 2.0 for satellite count 4-5")
    void hdopIs2Point0ForFourOrFiveSatellites(
            @ForAll @IntRange(min = 4, max = 5) int satellites) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());
        float hdop = invokeEstimateHdop(processor, satellites);

        assertEquals(2.0f, hdop,
                "Satellite count " + satellites + " (4-5) should produce HDOP 2.0 " +
                "but got " + hdop);
    }

    /**
     * Property 14c: For satellite count < 4, HDOP is 99.0.
     *
     * **Validates: Requirements 5.12**
     */
    @Property(tries = 10)
    @Label("HDOP is 99.0 for satellite count < 4")
    void hdopIs99Point0ForFewerThanFourSatellites(
            @ForAll @IntRange(min = 0, max = 3) int satellites) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());
        float hdop = invokeEstimateHdop(processor, satellites);

        assertEquals(99.0f, hdop,
                "Satellite count " + satellites + " (< 4) should produce HDOP 99.0 " +
                "but got " + hdop);
    }

    /**
     * Property 14d: HDOP thresholds are mutually exclusive and exhaustive —
     * every non-negative satellite count maps to exactly one HDOP value.
     *
     * **Validates: Requirements 5.12**
     */
    @Property(tries = 10)
    @Label("HDOP mapping is exhaustive for any non-negative satellite count")
    void hdopMappingIsExhaustive(
            @ForAll @IntRange(min = 0, max = 50) int satellites) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());
        float hdop = invokeEstimateHdop(processor, satellites);

        boolean isValid = (hdop == 1.5f) || (hdop == 2.0f) || (hdop == 99.0f);
        assertTrue(isValid,
                "Satellite count " + satellites + " produced unexpected HDOP " + hdop +
                ". Expected one of: 1.5, 2.0, 99.0");

        // Verify correct bucket
        if (satellites >= 6) {
            assertEquals(1.5f, hdop);
        } else if (satellites >= 4) {
            assertEquals(2.0f, hdop);
        } else {
            assertEquals(99.0f, hdop);
        }
    }

    // --- Reflection helpers ---

    /**
     * Invokes the internal bearingToCardinal method via reflection.
     */
    private String invokeBearingToCardinal(GpsProcessorImpl processor, float degrees) {
        try {
            Method method = null;
            for (Method m : processor.getClass().getDeclaredMethods()) {
                if (m.getName().startsWith("bearingToCardinal")) {
                    method = m;
                    break;
                }
            }
            if (method == null) {
                throw new RuntimeException("Could not find bearingToCardinal method");
            }
            method.setAccessible(true);
            return (String) method.invoke(processor, degrees);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke bearingToCardinal", e);
        }
    }

    /**
     * Invokes the internal debounceSatelliteCount method via reflection.
     */
    private int invokeDebounceSatelliteCount(GpsProcessorImpl processor, int rawCount) {
        try {
            Method method = null;
            for (Method m : processor.getClass().getDeclaredMethods()) {
                if (m.getName().startsWith("debounceSatelliteCount")) {
                    method = m;
                    break;
                }
            }
            if (method == null) {
                throw new RuntimeException("Could not find debounceSatelliteCount method");
            }
            method.setAccessible(true);
            return (int) method.invoke(processor, rawCount);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke debounceSatelliteCount", e);
        }
    }

    /**
     * Invokes the internal estimateHdop method via reflection.
     */
    private float invokeEstimateHdop(GpsProcessorImpl processor, int satellites) {
        try {
            Method method = null;
            for (Method m : processor.getClass().getDeclaredMethods()) {
                if (m.getName().startsWith("estimateHdop")) {
                    method = m;
                    break;
                }
            }
            if (method == null) {
                throw new RuntimeException("Could not find estimateHdop method");
            }
            method.setAccessible(true);
            return (float) method.invoke(processor, satellites);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke estimateHdop", e);
        }
    }
}
