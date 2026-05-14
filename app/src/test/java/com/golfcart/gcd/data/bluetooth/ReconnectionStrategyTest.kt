package com.golfcart.gcd.data.bluetooth

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [ReconnectionStrategy].
 *
 * Verifies exponential backoff behavior: 1s → 2s → 4s → 8s → 16s → 30s (max),
 * reset on success, and attempt counting.
 *
 * Validates: Requirements 17.3, 17.4 (automatic reconnection with backoff)
 */
@DisplayName("ReconnectionStrategy")
class ReconnectionStrategyTest {

    private lateinit var strategy: ReconnectionStrategy

    @BeforeEach
    fun setUp() {
        strategy = ReconnectionStrategy(
            tag = "TestReconnect",
            initialDelayMs = 1_000L,
            maxDelayMs = 30_000L,
            multiplier = 2.0
        )
    }

    @Test
    @DisplayName("initial delay is 1 second")
    fun initialDelayIsOneSecond() {
        assertEquals(1_000L, strategy.currentDelayMs)
    }

    @Test
    @DisplayName("initial attempt count is zero")
    fun initialAttemptCountIsZero() {
        assertEquals(0, strategy.attemptCount)
    }

    @Test
    @DisplayName("exponential backoff doubles delay each attempt")
    fun exponentialBackoffDoublesDelay() = runTest {
        // First attempt: waits 1s, then advances to 2s
        strategy.waitForNextAttempt()
        assertEquals(2_000L, strategy.currentDelayMs)
        assertEquals(1, strategy.attemptCount)

        // Second attempt: waits 2s, then advances to 4s
        strategy.waitForNextAttempt()
        assertEquals(4_000L, strategy.currentDelayMs)
        assertEquals(2, strategy.attemptCount)

        // Third attempt: waits 4s, then advances to 8s
        strategy.waitForNextAttempt()
        assertEquals(8_000L, strategy.currentDelayMs)
        assertEquals(3, strategy.attemptCount)

        // Fourth attempt: waits 8s, then advances to 16s
        strategy.waitForNextAttempt()
        assertEquals(16_000L, strategy.currentDelayMs)
        assertEquals(4, strategy.attemptCount)

        // Fifth attempt: waits 16s, then advances to 30s (capped at max)
        strategy.waitForNextAttempt()
        assertEquals(30_000L, strategy.currentDelayMs)
        assertEquals(5, strategy.attemptCount)
    }

    @Test
    @DisplayName("delay is capped at maximum (30 seconds)")
    fun delayCappedAtMaximum() = runTest {
        // Advance past the cap
        repeat(10) {
            strategy.waitForNextAttempt()
        }
        assertEquals(30_000L, strategy.currentDelayMs)
    }

    @Test
    @DisplayName("reset restores initial state")
    fun resetRestoresInitialState() = runTest {
        // Advance a few times
        strategy.waitForNextAttempt()
        strategy.waitForNextAttempt()
        strategy.waitForNextAttempt()

        // Reset
        strategy.reset()

        assertEquals(1_000L, strategy.currentDelayMs)
        assertEquals(0, strategy.attemptCount)
    }

    @Test
    @DisplayName("hasExceededMaxAttempts returns false when under limit")
    fun hasExceededMaxAttemptsReturnsFalseUnderLimit() = runTest {
        strategy.waitForNextAttempt()
        strategy.waitForNextAttempt()

        assertFalse(strategy.hasExceededMaxAttempts(5))
    }

    @Test
    @DisplayName("hasExceededMaxAttempts returns true when at or over limit")
    fun hasExceededMaxAttemptsReturnsTrueAtLimit() = runTest {
        repeat(5) {
            strategy.waitForNextAttempt()
        }

        assertTrue(strategy.hasExceededMaxAttempts(5))
    }

    @Test
    @DisplayName("waitForNextAttempt returns the delay that was waited")
    fun waitForNextAttemptReturnsDelayWaited() = runTest {
        val firstDelay = strategy.waitForNextAttempt()
        assertEquals(1_000L, firstDelay)

        val secondDelay = strategy.waitForNextAttempt()
        assertEquals(2_000L, secondDelay)

        val thirdDelay = strategy.waitForNextAttempt()
        assertEquals(4_000L, thirdDelay)
    }

    @Test
    @DisplayName("custom initial delay and multiplier work correctly")
    fun customParametersWork() = runTest {
        val customStrategy = ReconnectionStrategy(
            tag = "Custom",
            initialDelayMs = 500L,
            maxDelayMs = 5_000L,
            multiplier = 3.0
        )

        assertEquals(500L, customStrategy.currentDelayMs)

        customStrategy.waitForNextAttempt()
        assertEquals(1_500L, customStrategy.currentDelayMs)

        customStrategy.waitForNextAttempt()
        assertEquals(4_500L, customStrategy.currentDelayMs)

        customStrategy.waitForNextAttempt()
        assertEquals(5_000L, customStrategy.currentDelayMs) // Capped at max
    }
}
