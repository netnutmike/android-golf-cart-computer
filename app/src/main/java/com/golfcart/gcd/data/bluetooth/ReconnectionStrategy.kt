package com.golfcart.gcd.data.bluetooth

import android.util.Log
import kotlinx.coroutines.delay

/**
 * Implements exponential backoff reconnection strategy for Bluetooth connections.
 *
 * Each connection failure increases the delay before the next reconnection attempt:
 * 1s → 2s → 4s → 8s → 16s → 30s (max). The backoff resets when a connection
 * is successfully established.
 *
 * This strategy is used independently by both [MeshtasticService] and [TelemetryService]
 * to ensure one connection's reconnection attempts do not affect the other.
 *
 * Validates: Requirements 17.3, 17.4 (automatic reconnection without affecting the other)
 */
class ReconnectionStrategy(
    private val tag: String = "ReconnectionStrategy",
    private val initialDelayMs: Long = INITIAL_DELAY_MS,
    private val maxDelayMs: Long = MAX_DELAY_MS,
    private val multiplier: Double = BACKOFF_MULTIPLIER
) {

    companion object {
        /** Initial reconnection delay: 1 second. */
        const val INITIAL_DELAY_MS = 1_000L

        /** Maximum reconnection delay: 30 seconds. */
        const val MAX_DELAY_MS = 30_000L

        /** Backoff multiplier: doubles each attempt. */
        const val BACKOFF_MULTIPLIER = 2.0
    }

    /** Current delay for the next reconnection attempt. */
    @Volatile
    var currentDelayMs: Long = initialDelayMs
        private set

    /** Number of consecutive reconnection attempts since last successful connection. */
    @Volatile
    var attemptCount: Int = 0
        private set

    /**
     * Waits for the current backoff delay before the next reconnection attempt.
     * After waiting, advances the delay for the subsequent attempt.
     *
     * @return The delay that was waited (in milliseconds).
     */
    suspend fun waitForNextAttempt(): Long {
        val delayToWait = currentDelayMs
        Log.d(tag, "Reconnection attempt #${attemptCount + 1}, waiting ${delayToWait}ms")
        delay(delayToWait)
        advanceBackoff()
        return delayToWait
    }

    /**
     * Advances the backoff delay for the next attempt.
     * Caps at [maxDelayMs].
     */
    private fun advanceBackoff() {
        attemptCount++
        currentDelayMs = (currentDelayMs * multiplier).toLong().coerceAtMost(maxDelayMs)
    }

    /**
     * Resets the backoff state after a successful connection.
     * Should be called when the connection is established successfully.
     */
    fun reset() {
        currentDelayMs = initialDelayMs
        attemptCount = 0
        Log.d(tag, "Reconnection backoff reset")
    }

    /**
     * Returns whether the strategy has exceeded a reasonable number of attempts.
     * This can be used to decide whether to stop attempting reconnection.
     *
     * @param maxAttempts The maximum number of attempts before giving up (default: unlimited).
     * @return true if the attempt count exceeds maxAttempts.
     */
    fun hasExceededMaxAttempts(maxAttempts: Int = Int.MAX_VALUE): Boolean {
        return attemptCount >= maxAttempts
    }
}
