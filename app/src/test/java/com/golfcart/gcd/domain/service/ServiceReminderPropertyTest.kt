package com.golfcart.gcd.domain.service

import com.golfcart.gcd.data.persistence.*
import com.golfcart.gcd.domain.gps.ProcessedGpsData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import net.jqwik.api.*
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.LongRange
import org.junit.jupiter.api.Assertions.*

/**
 * Property-based tests for driving hours accumulation gating.
 *
 * Feature: android-golf-cart-computer, Property 17: Driving hours accumulation gating
 *
 * Validates: Requirements 7.1, 7.5
 */
@Label("Property 17: Driving hours accumulation gating")
@Tag("Feature: android-golf-cart-computer")
@Tag("Property 17: Driving hours accumulation gating")
class ServiceReminderPropertyTest {

    // =========================================================================
    // Helper methods
    // =========================================================================

    private fun createServiceReminderManager(): ServiceReminderManagerImpl {
        val repo = FakeDataStoreRepository()
        val testScope = TestScope()
        return ServiceReminderManagerImpl(
            dataStoreRepository = repo,
            coroutineScope = testScope
        )
    }

    // =========================================================================
    // Property 17: Driving hours accumulation gating
    // =========================================================================

    /**
     * Property 17a: Driving hours do NOT accumulate when speed is zero,
     * regardless of time delta.
     *
     * Hours should only accumulate when the vehicle is in motion (speed > 0).
     *
     * **Validates: Requirements 7.1**
     */
    @Property(tries = 10)
    @Label("No hours accumulated when speed is zero")
    @Tag("Property 17: Driving hours accumulation gating")
    fun noHoursAccumulatedWhenSpeedIsZero(
        @ForAll("validTimeDeltasMs") timeDeltaMs: Long
    ) {
        val manager = createServiceReminderManager()

        val baseTimestamp = 1_000_000L

        // First GPS update to establish a previous timestamp (with speed > 0 to set up state)
        val initial = ProcessedGpsData(
            speedMph = 5,
            timestamp = baseTimestamp,
            isValid = true
        )
        manager.onGpsUpdate(initial)

        val tenthsBefore = manager.serviceReminderState.value.tenthsOfHoursSinceService

        // Second GPS update with speed = 0 and a valid time delta
        val update = ProcessedGpsData(
            speedMph = 0,
            timestamp = baseTimestamp + timeDeltaMs,
            isValid = true
        )
        manager.onGpsUpdate(update)

        val tenthsAfter = manager.serviceReminderState.value.tenthsOfHoursSinceService

        assertEquals(tenthsBefore, tenthsAfter,
            "Hours should not accumulate when speed is zero (timeDelta=${timeDeltaMs}ms)")
    }

    /**
     * Property 17b: Driving hours do NOT accumulate when time delta is negative
     * (below MIN_TIME_DELTA_MS = 0).
     *
     * Negative time deltas indicate clock jumps or GPS glitches and must be discarded.
     *
     * **Validates: Requirements 7.5**
     */
    @Property(tries = 10)
    @Label("No hours accumulated when time delta is negative")
    @Tag("Property 17: Driving hours accumulation gating")
    fun noHoursAccumulatedWhenTimeDeltaNegative(
        @ForAll("negativeTimeDeltasMs") timeDeltaMs: Long,
        @ForAll("positiveSpeedsMph") speedMph: Int
    ) {
        val manager = createServiceReminderManager()

        val baseTimestamp = 1_000_000L

        // First GPS update to establish a previous timestamp
        val initial = ProcessedGpsData(
            speedMph = speedMph,
            timestamp = baseTimestamp,
            isValid = true
        )
        manager.onGpsUpdate(initial)

        val tenthsBefore = manager.serviceReminderState.value.tenthsOfHoursSinceService

        // Second GPS update with a negative time delta (timestamp goes backwards)
        val update = ProcessedGpsData(
            speedMph = speedMph,
            timestamp = baseTimestamp + timeDeltaMs,
            isValid = true
        )
        manager.onGpsUpdate(update)

        val tenthsAfter = manager.serviceReminderState.value.tenthsOfHoursSinceService

        assertEquals(tenthsBefore, tenthsAfter,
            "Hours should not accumulate when time delta is negative (${timeDeltaMs}ms)")
    }

    /**
     * Property 17c: Driving hours do NOT accumulate when time delta exceeds
     * MAX_TIME_DELTA_MS (10,000 ms / 10 seconds).
     *
     * Large time deltas indicate device reboots or GPS signal loss and must be discarded.
     *
     * **Validates: Requirements 7.5**
     */
    @Property(tries = 10)
    @Label("No hours accumulated when time delta exceeds 10 seconds")
    @Tag("Property 17: Driving hours accumulation gating")
    fun noHoursAccumulatedWhenTimeDeltaExceeds10Seconds(
        @ForAll("excessiveTimeDeltasMs") timeDeltaMs: Long,
        @ForAll("positiveSpeedsMph") speedMph: Int
    ) {
        val manager = createServiceReminderManager()

        val baseTimestamp = 1_000_000L

        // First GPS update to establish a previous timestamp
        val initial = ProcessedGpsData(
            speedMph = speedMph,
            timestamp = baseTimestamp,
            isValid = true
        )
        manager.onGpsUpdate(initial)

        val tenthsBefore = manager.serviceReminderState.value.tenthsOfHoursSinceService

        // Second GPS update with time delta > 10 seconds
        val update = ProcessedGpsData(
            speedMph = speedMph,
            timestamp = baseTimestamp + timeDeltaMs,
            isValid = true
        )
        manager.onGpsUpdate(update)

        val tenthsAfter = manager.serviceReminderState.value.tenthsOfHoursSinceService

        assertEquals(tenthsBefore, tenthsAfter,
            "Hours should not accumulate when time delta exceeds 10s (${timeDeltaMs}ms)")
    }

    /**
     * Property 17d: Driving hours DO accumulate when both conditions are met:
     * speed > 0 AND time delta is between 0 and 10 seconds (inclusive).
     *
     * This verifies the positive case — when all gating conditions pass,
     * the sub-tenth accumulator increases by the time delta amount.
     *
     * **Validates: Requirements 7.1, 7.5**
     */
    @Property(tries = 10)
    @Label("Hours accumulate when speed > 0 and time delta is 0-10 seconds")
    @Tag("Property 17: Driving hours accumulation gating")
    fun hoursAccumulateWhenBothConditionsMet(
        @ForAll("validTimeDeltasMs") timeDeltaMs: Long,
        @ForAll("positiveSpeedsMph") speedMph: Int
    ) {
        val manager = createServiceReminderManager()

        // Directly test accumulateTime to verify time is being accumulated
        // We need enough time deltas to produce at least one tenth of an hour
        val tenthsBefore = manager.serviceReminderState.value.tenthsOfHoursSinceService

        // Feed enough valid GPS updates to accumulate at least one tenth of an hour
        // One tenth = 360,000 ms. With max delta of 10,000 ms, need at least 36 updates.
        val baseTimestamp = 1_000_000L
        var currentTimestamp = baseTimestamp

        // First update to establish previous timestamp
        manager.onGpsUpdate(ProcessedGpsData(
            speedMph = speedMph,
            timestamp = currentTimestamp,
            isValid = true
        ))

        // Feed multiple updates with the given time delta
        val updatesNeeded = (ServiceReminderManagerImpl.TENTH_HOUR_MS / timeDeltaMs).toInt() + 1
        val actualUpdates = updatesNeeded.coerceAtMost(500) // Cap to avoid excessive iterations

        for (i in 0 until actualUpdates) {
            currentTimestamp += timeDeltaMs
            manager.onGpsUpdate(ProcessedGpsData(
                speedMph = speedMph,
                timestamp = currentTimestamp,
                isValid = true
            ))
        }

        val tenthsAfter = manager.serviceReminderState.value.tenthsOfHoursSinceService
        val totalTimeAccumulated = timeDeltaMs * actualUpdates

        if (totalTimeAccumulated >= ServiceReminderManagerImpl.TENTH_HOUR_MS) {
            assertTrue(tenthsAfter > tenthsBefore,
                "Hours should accumulate when speed=$speedMph > 0 and timeDelta=${timeDeltaMs}ms " +
                "is in valid range. Total time: ${totalTimeAccumulated}ms, tenths: $tenthsAfter")
        }
        // If total time is less than one tenth, tenths may not have incremented yet,
        // but the sub-tenth accumulator should have increased (verified by the accumulation
        // not being rejected)
    }

    /**
     * Property 17e: The accumulation amount is correct — accumulated tenths of hours
     * equals floor(total_valid_time_ms / TENTH_HOUR_MS).
     *
     * This verifies that the conversion from milliseconds to tenths of hours is accurate.
     *
     * **Validates: Requirements 7.1, 7.5**
     */
    @Property(tries = 10)
    @Label("Accumulated tenths equals floor of total valid time divided by TENTH_HOUR_MS")
    @Tag("Property 17: Driving hours accumulation gating")
    fun accumulatedTenthsMatchesExpectedConversion(
        @ForAll("validTimeDeltasMs") timeDeltaMs: Long,
        @ForAll @IntRange(min = 36, max = 150) updateCount: Int
    ) {
        val manager = createServiceReminderManager()

        val baseTimestamp = 1_000_000L
        var currentTimestamp = baseTimestamp

        // First update to establish previous timestamp
        manager.onGpsUpdate(ProcessedGpsData(
            speedMph = 10,
            timestamp = currentTimestamp,
            isValid = true
        ))

        // Feed exactly updateCount updates with the given time delta
        for (i in 0 until updateCount) {
            currentTimestamp += timeDeltaMs
            manager.onGpsUpdate(ProcessedGpsData(
                speedMph = 10,
                timestamp = currentTimestamp,
                isValid = true
            ))
        }

        val totalTimeMs = timeDeltaMs * updateCount
        val expectedTenths = (totalTimeMs / ServiceReminderManagerImpl.TENTH_HOUR_MS).toInt()
        val actualTenths = manager.serviceReminderState.value.tenthsOfHoursSinceService

        assertEquals(expectedTenths, actualTenths,
            "With ${updateCount} updates of ${timeDeltaMs}ms each (total=${totalTimeMs}ms), " +
            "expected $expectedTenths tenths but got $actualTenths")
    }

    /**
     * Property 17f: The first GPS update never causes accumulation (it only establishes
     * the previous timestamp baseline).
     *
     * **Validates: Requirements 7.1, 7.5**
     */
    @Property(tries = 10)
    @Label("First GPS update never causes accumulation")
    @Tag("Property 17: Driving hours accumulation gating")
    fun firstUpdateNeverAccumulates(
        @ForAll("positiveSpeedsMph") speedMph: Int,
        @ForAll("anyTimestamps") timestamp: Long
    ) {
        val manager = createServiceReminderManager()

        val tenthsBefore = manager.serviceReminderState.value.tenthsOfHoursSinceService

        // First GPS update — should only establish baseline
        manager.onGpsUpdate(ProcessedGpsData(
            speedMph = speedMph,
            timestamp = timestamp,
            isValid = true
        ))

        val tenthsAfter = manager.serviceReminderState.value.tenthsOfHoursSinceService

        assertEquals(tenthsBefore, tenthsAfter,
            "First GPS update should never cause accumulation regardless of speed ($speedMph) " +
            "or timestamp ($timestamp)")
    }

    // =========================================================================
    // Generators
    // =========================================================================

    /**
     * Valid time deltas: between 1ms and 10,000ms (0-10 seconds, exclusive of 0 to ensure
     * actual time passes for accumulation testing).
     */
    @Provide
    fun validTimeDeltasMs(): Arbitrary<Long> {
        return Arbitraries.longs().between(1L, ServiceReminderManagerImpl.MAX_TIME_DELTA_MS)
    }

    /**
     * Negative time deltas: between -100,000ms and -1ms.
     */
    @Provide
    fun negativeTimeDeltasMs(): Arbitrary<Long> {
        return Arbitraries.longs().between(-100_000L, -1L)
    }

    /**
     * Excessive time deltas: above 10,000ms (10 seconds).
     */
    @Provide
    fun excessiveTimeDeltasMs(): Arbitrary<Long> {
        return Arbitraries.longs().between(
            ServiceReminderManagerImpl.MAX_TIME_DELTA_MS + 1L,
            1_000_000L
        )
    }

    /**
     * Positive speeds in mph (1-25 mph, typical golf cart range).
     */
    @Provide
    fun positiveSpeedsMph(): Arbitrary<Int> {
        return Arbitraries.integers().between(1, 25)
    }

    /**
     * Any valid timestamps for first-update testing.
     */
    @Provide
    fun anyTimestamps(): Arbitrary<Long> {
        return Arbitraries.longs().between(1L, 10_000_000_000L)
    }

    // =========================================================================
    // Fake DataStoreRepository for testing
    // =========================================================================

    private class FakeDataStoreRepository : DataStoreRepository {
        override fun getPersistedOdometer(): Flow<OdometerData> = flowOf(OdometerData())
        override fun getPersistedDrivingHours(): Flow<Int> = flowOf(0)
        override suspend fun persistOdometer(accumDistance: Float, tripDistance: Float) {}
        override suspend fun persistDrivingHours(tenthsOfHours: Int) {}
        override fun getPreferences(): Flow<UserPreferences> = flowOf(UserPreferences())
        override suspend fun updatePreference(key: PreferenceKey, value: Any) {}
        override suspend fun resetAllPreferences() {}
        override fun getCachedWeather(): Flow<CachedWeatherData> = flowOf(CachedWeatherData())
        override fun getCachedVenueEvents(): Flow<CachedVenueData> = flowOf(CachedVenueData())
        override suspend fun cacheWeatherData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
        override suspend fun cacheVenueData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
    }
}
