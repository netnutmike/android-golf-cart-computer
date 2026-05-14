package com.golfcart.gcd.domain.gps;

import net.jqwik.api.*;
import net.jqwik.api.constraints.FloatRange;
import net.jqwik.api.constraints.IntRange;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test for GPS speed filtering pipeline.
 *
 * Feature: android-golf-cart-computer, Property 11: GPS speed filtering
 *
 * Validates: Requirements 5.4, 5.5, 5.6, 5.7
 */
@Label("Property 11: GPS speed filtering")
@Tag("Feature: android-golf-cart-computer")
@Tag("Property 11: GPS speed filtering")
class GpsSpeedFilteringPropertyTest {

    /**
     * Invokes the internal applySpeedFilter method via reflection.
     * Kotlin internal methods are compiled with a module-name suffix in bytecode.
     */
    private float applySpeedFilter(GpsProcessorImpl processor, float rawSpeedMph, long timestamp) {
        try {
            // Kotlin internal methods get a $module_name suffix in bytecode
            Method method = null;
            for (Method m : processor.getClass().getDeclaredMethods()) {
                if (m.getName().startsWith("applySpeedFilter")) {
                    method = m;
                    break;
                }
            }
            if (method == null) {
                throw new RuntimeException("Could not find applySpeedFilter method");
            }
            method.setAccessible(true);
            return (float) method.invoke(processor, rawSpeedMph, timestamp);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke applySpeedFilter", e);
        }
    }

    /**
     * Property 1: For any raw speed below 2.5 mph, the filtered output is always zero.
     *
     * This validates the GPS dither elimination rule: stationary GPS receivers
     * report small non-zero speeds due to signal noise, and these must be
     * filtered to zero.
     *
     * **Validates: Requirements 5.4**
     */
    @Property(tries = 10)
    @Label("Speeds below 2.5 mph always filter to zero")
    void speedsBelowDitherThresholdAlwaysFilterToZero(
            @ForAll @FloatRange(min = 0f, max = 2.49f) float rawSpeed,
            @ForAll("timestamps") long timestamp) {

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());
        float result = applySpeedFilter(processor, rawSpeed, timestamp);

        assertEquals(0f, result,
                "Raw speed " + rawSpeed + " mph is below 2.5 mph dither threshold " +
                "and must be filtered to zero, but got " + result);
    }

    /**
     * Property 2: For any sequence where acceleration exceeds 8 mph/s,
     * the spike reading is rejected (output doesn't jump).
     *
     * When a speed spike occurs (acceleration > 8 mph/s), the filter retains
     * the previous filtered speed rather than reporting the spike.
     *
     * **Validates: Requirements 5.5**
     */
    @Property(tries = 10)
    @Label("Speed spikes exceeding 8 mph/s are rejected")
    void speedSpikesExceedingMaxAccelerationAreRejected(
            @ForAll @FloatRange(min = 5.0f, max = 20.0f) float initialSpeed,
            @ForAll @FloatRange(min = 10.0f, max = 40.0f) float spikeAmount,
            @ForAll @IntRange(min = 500, max = 2000) int timeDeltaMs) {

        // Ensure the spike actually exceeds 8 mph/s acceleration
        float timeDeltaSeconds = timeDeltaMs / 1000.0f;
        float spikeSpeed = initialSpeed + spikeAmount;
        float acceleration = spikeAmount / timeDeltaSeconds;
        Assume.that(acceleration > GpsProcessorImpl.MAX_ACCELERATION_MPH_PER_SEC);
        Assume.that(initialSpeed >= GpsProcessorImpl.DITHER_THRESHOLD_MPH);

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());

        long baseTimestamp = 10000L;

        // Establish movement state: feed initial speed to build consecutive readings
        setProcessorState(processor, initialSpeed, baseTimestamp, initialSpeed, 2, true);

        // Confirm stable speed — this becomes the "previous" for spike detection
        float stableResult = applySpeedFilter(processor, initialSpeed, baseTimestamp + timeDeltaMs);
        // Update state after stable reading
        setProcessorState(processor, initialSpeed, baseTimestamp + timeDeltaMs, stableResult, 3, true);

        // Now apply the spike
        long spikeTimestamp = baseTimestamp + (2L * timeDeltaMs);
        float spikeResult = applySpeedFilter(processor, spikeSpeed, spikeTimestamp);

        // The spike should be rejected — output should equal the previous filtered speed
        assertEquals(stableResult, spikeResult,
                "Speed spike from " + initialSpeed + " to " + spikeSpeed +
                " mph in " + timeDeltaSeconds + "s (acceleration=" + acceleration +
                " mph/s) should be rejected. Expected previous filtered=" +
                stableResult + " but got " + spikeResult);
    }

    /**
     * Property 3: For any speed below 4 mph that is decreasing from the
     * previous reading, output is zero.
     *
     * This validates the responsive stop detection: when the cart is slowing
     * down at low speed, we immediately report zero rather than waiting.
     *
     * **Validates: Requirements 5.6**
     */
    @Property(tries = 10)
    @Label("Speed below 4 mph and decreasing reports zero")
    void speedBelowStopThresholdAndDecreasingReportsZero(
            @ForAll @FloatRange(min = 2.5f, max = 3.99f) float currentSpeed,
            @ForAll @FloatRange(min = 0.01f, max = 3.0f) float previousDelta) {

        // Previous speed must be higher than current (decreasing)
        float previousSpeed = currentSpeed + previousDelta;
        // Current speed must be below stop detection threshold (4.0 mph)
        // and above dither threshold (2.5 mph) to reach step 3
        Assume.that(currentSpeed < GpsProcessorImpl.STOP_DETECTION_THRESHOLD_MPH);
        Assume.that(currentSpeed >= GpsProcessorImpl.DITHER_THRESHOLD_MPH);
        Assume.that(currentSpeed < previousSpeed);

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());

        long baseTimestamp = 10000L;
        long timeDelta = 2000L; // 2 seconds — keeps acceleration reasonable

        // Ensure the speed change doesn't trigger spike detection
        float acceleration = Math.abs(currentSpeed - previousSpeed) / (timeDelta / 1000.0f);
        Assume.that(acceleration <= GpsProcessorImpl.MAX_ACCELERATION_MPH_PER_SEC);

        // Set up processor with previous speed established
        setProcessorState(processor, previousSpeed, baseTimestamp, previousSpeed, 2, true);

        float result = applySpeedFilter(processor, currentSpeed, baseTimestamp + timeDelta);

        assertEquals(0f, result,
                "Speed " + currentSpeed + " mph (below 4.0) decreasing from " +
                previousSpeed + " mph should report zero, but got " + result);
    }

    /**
     * Property 4: Movement is only reported after 2 consecutive readings
     * above the dither threshold.
     *
     * The first reading above threshold should NOT report movement. Only after
     * the second consecutive reading above threshold should movement be reported.
     *
     * **Validates: Requirements 5.7**
     */
    @Property(tries = 10)
    @Label("Movement requires 2 consecutive readings above threshold")
    void movementRequiresTwoConsecutiveReadingsAboveThreshold(
            @ForAll @FloatRange(min = 5.0f, max = 25.0f) float speed,
            @ForAll @IntRange(min = 800, max = 2000) int timeDeltaMs) {

        // Ensure speed is well above all thresholds to isolate the consecutive-readings rule
        Assume.that(speed >= GpsProcessorImpl.STOP_DETECTION_THRESHOLD_MPH);

        GpsProcessorImpl processor = new GpsProcessorImpl(new NavigationDataProcessor());
        long baseTimestamp = 10000L;

        // First reading above threshold — should NOT report movement
        float firstResult = applySpeedFilter(processor, speed, baseTimestamp);
        assertEquals(0f, firstResult,
                "First reading at " + speed + " mph should return 0 (need 2 consecutive), " +
                "but got " + firstResult);

        // Set state for second reading (simulate normal state progression)
        setProcessorState(processor, speed, baseTimestamp, 0f, 1, false);

        // Second reading above threshold — should report movement
        long secondTimestamp = baseTimestamp + timeDeltaMs;
        float secondResult = applySpeedFilter(processor, speed, secondTimestamp);
        assertEquals(speed, secondResult,
                "Second consecutive reading at " + speed + " mph should report movement, " +
                "but got " + secondResult);
    }

    // --- Helpers ---

    /**
     * Sets internal state of GpsProcessorImpl via reflection for isolated property testing.
     */
    private void setProcessorState(GpsProcessorImpl processor,
                                   Float previousRawSpeed,
                                   Long previousTimestamp,
                                   Float previousFiltered,
                                   Integer consecutive,
                                   Boolean reporting) {
        try {
            if (previousRawSpeed != null) {
                java.lang.reflect.Field field = GpsProcessorImpl.class.getDeclaredField("previousRawSpeedMph");
                field.setAccessible(true);
                field.setFloat(processor, previousRawSpeed);
            }
            if (previousTimestamp != null) {
                java.lang.reflect.Field field = GpsProcessorImpl.class.getDeclaredField("previousTimestamp");
                field.setAccessible(true);
                field.setLong(processor, previousTimestamp);
            }
            if (previousFiltered != null) {
                java.lang.reflect.Field field = GpsProcessorImpl.class.getDeclaredField("previousFilteredSpeedMph");
                field.setAccessible(true);
                field.setFloat(processor, previousFiltered);
            }
            if (consecutive != null) {
                java.lang.reflect.Field field = GpsProcessorImpl.class.getDeclaredField("consecutiveAboveThreshold");
                field.setAccessible(true);
                field.setInt(processor, consecutive);
            }
            if (reporting != null) {
                java.lang.reflect.Field field = GpsProcessorImpl.class.getDeclaredField("isReportingMovement");
                field.setAccessible(true);
                field.setBoolean(processor, reporting);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to set processor state via reflection", e);
        }
    }

    /**
     * Provides valid timestamps (positive long values representing milliseconds).
     */
    @Provide
    Arbitrary<Long> timestamps() {
        return Arbitraries.longs().between(1000L, 1_000_000_000L);
    }
}
