package com.golfcart.gcd.domain.sleep

import com.golfcart.gcd.data.bluetooth.ConnectionState
import net.jqwik.api.*
import net.jqwik.api.lifecycle.BeforeProperty
import org.junit.jupiter.api.Assertions.*

/**
 * Property-based tests for sleep state machine transitions.
 *
 * Feature: android-golf-cart-computer, Property 20: Sleep state machine transitions
 *
 * The core property: for any sequence of events (GCI connect, GCI disconnect,
 * timeout expiry, grace period expiry), the sleep operating mode should transition
 * correctly following the defined state machine rules:
 * - STARTUP_GRACE → GCI_MODE: only when GCI connects
 * - STARTUP_GRACE → STANDALONE_MODE: only when grace period expires without GCI
 * - GCI_MODE → STANDALONE_MODE: only when GCI disconnected for timeout period
 * - STANDALONE_MODE → GCI_MODE: only when GCI reconnects
 *
 * **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5**
 */
@Label("Property 20: Sleep state machine transitions")
@Tag("Feature: android-golf-cart-computer")
@Tag("Property 20: Sleep state machine transitions")
class SleepStateMachinePropertyTest {

    // =========================================================================
    // Property 20a: STARTUP_GRACE → GCI_MODE only when GCI connects
    // =========================================================================

    /**
     * Property 20a: From STARTUP_GRACE, the state transitions to GCI_MODE
     * if and only if a GCI connection event (CONNECTED or READY) is received.
     *
     * **Validates: Requirements 11.1, 11.2, 11.3**
     */
    @Property(tries = 10)
    @Label("STARTUP_GRACE transitions to GCI_MODE only on GCI connect")
    fun startupGraceTransitionsToGciModeOnlyOnGciConnect(
        @ForAll("backlightTimeouts") timeoutMinutes: Int,
        @ForAll("connectionStates") connectionState: ConnectionState
    ) {
        val manager = SleepManagerImpl(timeoutMinutes)

        // Verify initial state
        assertEquals(OperatingMode.STARTUP_GRACE, manager.operatingMode.value)

        // Apply connection state change
        manager.onGciConnectionStateChanged(connectionState)

        val gciConnected = connectionState == ConnectionState.READY ||
                connectionState == ConnectionState.CONNECTED

        if (gciConnected) {
            assertEquals(OperatingMode.GCI_MODE, manager.operatingMode.value,
                "STARTUP_GRACE should transition to GCI_MODE when GCI state is $connectionState")
        } else {
            assertEquals(OperatingMode.STARTUP_GRACE, manager.operatingMode.value,
                "STARTUP_GRACE should NOT transition when GCI state is $connectionState")
        }
    }

    // =========================================================================
    // Property 20b: STARTUP_GRACE → STANDALONE_MODE only on grace period expiry
    // =========================================================================

    /**
     * Property 20b: From STARTUP_GRACE, the state transitions to STANDALONE_MODE
     * if and only if the grace period expires without a GCI connection.
     *
     * **Validates: Requirements 11.1, 11.2, 11.4**
     */
    @Property(tries = 10)
    @Label("STARTUP_GRACE transitions to STANDALONE_MODE only on grace period expiry")
    fun startupGraceTransitionsToStandaloneModeOnGracePeriodExpiry(
        @ForAll("backlightTimeouts") timeoutMinutes: Int
    ) {
        val manager = SleepManagerImpl(timeoutMinutes)

        // Verify initial state
        assertEquals(OperatingMode.STARTUP_GRACE, manager.operatingMode.value)

        // Grace period expires without GCI connecting
        manager.onGracePeriodExpired()

        assertEquals(OperatingMode.STANDALONE_MODE, manager.operatingMode.value,
            "STARTUP_GRACE should transition to STANDALONE_MODE when grace period expires")
    }

    /**
     * Property 20b-supplement: Grace period expiry has no effect if GCI already connected
     * (i.e., if we're already in GCI_MODE).
     *
     * **Validates: Requirements 11.2, 11.3**
     */
    @Property(tries = 10)
    @Label("Grace period expiry has no effect when already in GCI_MODE")
    fun gracePeriodExpiryNoEffectInGciMode(
        @ForAll("backlightTimeouts") timeoutMinutes: Int
    ) {
        val manager = SleepManagerImpl(timeoutMinutes)

        // Transition to GCI_MODE first
        manager.onGciConnectionStateChanged(ConnectionState.READY)
        assertEquals(OperatingMode.GCI_MODE, manager.operatingMode.value)

        // Grace period expiry should have no effect
        manager.onGracePeriodExpired()

        assertEquals(OperatingMode.GCI_MODE, manager.operatingMode.value,
            "Grace period expiry should not affect GCI_MODE")
    }

    // =========================================================================
    // Property 20c: GCI_MODE → STANDALONE_MODE only on disconnect timeout
    // =========================================================================

    /**
     * Property 20c: From GCI_MODE, the state transitions to STANDALONE_MODE
     * if and only if the GCI disconnect timeout expires.
     *
     * **Validates: Requirements 11.3, 11.5**
     */
    @Property(tries = 10)
    @Label("GCI_MODE transitions to STANDALONE_MODE only on disconnect timeout expiry")
    fun gciModeTransitionsToStandaloneModeOnDisconnectTimeout(
        @ForAll("backlightTimeouts") timeoutMinutes: Int
    ) {
        val manager = SleepManagerImpl(timeoutMinutes)

        // Get into GCI_MODE
        manager.onGciConnectionStateChanged(ConnectionState.READY)
        assertEquals(OperatingMode.GCI_MODE, manager.operatingMode.value)

        // GCI disconnects
        manager.onGciConnectionStateChanged(ConnectionState.DISCONNECTED)
        // Should still be in GCI_MODE (timeout hasn't expired yet)
        assertEquals(OperatingMode.GCI_MODE, manager.operatingMode.value,
            "GCI_MODE should NOT immediately transition on disconnect")

        // Disconnect timeout expires
        manager.onDisconnectTimeoutExpired()

        assertEquals(OperatingMode.STANDALONE_MODE, manager.operatingMode.value,
            "GCI_MODE should transition to STANDALONE_MODE when disconnect timeout expires")
    }

    /**
     * Property 20c-supplement: Disconnect timeout expiry has no effect if not in GCI_MODE.
     *
     * **Validates: Requirements 11.3, 11.5**
     */
    @Property(tries = 10)
    @Label("Disconnect timeout expiry has no effect when not in GCI_MODE")
    fun disconnectTimeoutExpiryNoEffectOutsideGciMode(
        @ForAll("backlightTimeouts") timeoutMinutes: Int
    ) {
        val manager = SleepManagerImpl(timeoutMinutes)

        // In STARTUP_GRACE — disconnect timeout should have no effect
        assertEquals(OperatingMode.STARTUP_GRACE, manager.operatingMode.value)
        manager.onDisconnectTimeoutExpired()
        assertEquals(OperatingMode.STARTUP_GRACE, manager.operatingMode.value,
            "Disconnect timeout expiry should not affect STARTUP_GRACE")

        // Transition to STANDALONE_MODE
        manager.onGracePeriodExpired()
        assertEquals(OperatingMode.STANDALONE_MODE, manager.operatingMode.value)

        // Disconnect timeout should have no effect in STANDALONE_MODE
        manager.onDisconnectTimeoutExpired()
        assertEquals(OperatingMode.STANDALONE_MODE, manager.operatingMode.value,
            "Disconnect timeout expiry should not affect STANDALONE_MODE")
    }

    // =========================================================================
    // Property 20d: STANDALONE_MODE → GCI_MODE only when GCI reconnects
    // =========================================================================

    /**
     * Property 20d: From STANDALONE_MODE, the state transitions to GCI_MODE
     * if and only if a GCI connection event (CONNECTED or READY) is received.
     *
     * **Validates: Requirements 11.4, 11.5**
     */
    @Property(tries = 10)
    @Label("STANDALONE_MODE transitions to GCI_MODE only on GCI reconnect")
    fun standaloneModeTransitionsToGciModeOnlyOnGciReconnect(
        @ForAll("backlightTimeouts") timeoutMinutes: Int,
        @ForAll("connectionStates") connectionState: ConnectionState
    ) {
        val manager = SleepManagerImpl(timeoutMinutes)

        // Get into STANDALONE_MODE via grace period expiry
        manager.onGracePeriodExpired()
        assertEquals(OperatingMode.STANDALONE_MODE, manager.operatingMode.value)

        // Apply connection state change
        manager.onGciConnectionStateChanged(connectionState)

        val gciConnected = connectionState == ConnectionState.READY ||
                connectionState == ConnectionState.CONNECTED

        if (gciConnected) {
            assertEquals(OperatingMode.GCI_MODE, manager.operatingMode.value,
                "STANDALONE_MODE should transition to GCI_MODE when GCI state is $connectionState")
        } else {
            assertEquals(OperatingMode.STANDALONE_MODE, manager.operatingMode.value,
                "STANDALONE_MODE should NOT transition when GCI state is $connectionState")
        }
    }

    // =========================================================================
    // Property 20e: Invalid transitions are rejected
    // =========================================================================

    /**
     * Property 20e: No direct transition from STARTUP_GRACE to STANDALONE_MODE
     * via GCI connection events (only grace period expiry causes this transition).
     *
     * **Validates: Requirements 11.1, 11.2**
     */
    @Property(tries = 10)
    @Label("Non-connected GCI states do not cause transitions from STARTUP_GRACE")
    fun nonConnectedGciStatesDoNotCauseTransitionsFromStartupGrace(
        @ForAll("backlightTimeouts") timeoutMinutes: Int,
        @ForAll("nonConnectedStates") connectionState: ConnectionState
    ) {
        val manager = SleepManagerImpl(timeoutMinutes)

        assertEquals(OperatingMode.STARTUP_GRACE, manager.operatingMode.value)

        // Non-connected states should not cause any transition
        manager.onGciConnectionStateChanged(connectionState)

        assertEquals(OperatingMode.STARTUP_GRACE, manager.operatingMode.value,
            "STARTUP_GRACE should remain unchanged for non-connected state $connectionState")
    }

    // =========================================================================
    // Property 20f: Event sequences maintain valid state machine invariants
    // =========================================================================

    /**
     * Property 20f: For any arbitrary sequence of events, the state machine
     * always remains in a valid OperatingMode and transitions follow the rules.
     *
     * **Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5**
     */
    @Property(tries = 10)
    @Label("Arbitrary event sequences maintain valid state machine invariants")
    fun arbitraryEventSequencesMaintainValidInvariants(
        @ForAll("backlightTimeouts") timeoutMinutes: Int,
        @ForAll("eventSequences") events: List<SleepEvent>
    ) {
        val manager = SleepManagerImpl(timeoutMinutes)

        var previousMode = manager.operatingMode.value

        for (event in events) {
            applyEvent(manager, event)
            val currentMode = manager.operatingMode.value

            // Verify the transition is valid
            if (previousMode != currentMode) {
                val validTransition = isValidTransition(previousMode, currentMode, event)
                assertTrue(validTransition,
                    "Invalid transition: $previousMode → $currentMode on event $event")
            }

            // Verify we're always in a valid state
            assertTrue(currentMode in OperatingMode.entries,
                "Operating mode should always be a valid enum value, got $currentMode")

            previousMode = currentMode
        }
    }

    /**
     * Property 20g: GCI reconnection in GCI_MODE cancels disconnect timeout
     * (state remains GCI_MODE, not transitioning to STANDALONE_MODE).
     *
     * **Validates: Requirements 11.3, 11.5**
     */
    @Property(tries = 10)
    @Label("GCI reconnection in GCI_MODE prevents transition to STANDALONE_MODE")
    fun gciReconnectionInGciModePreventsStandaloneTransition(
        @ForAll("backlightTimeouts") timeoutMinutes: Int
    ) {
        val manager = SleepManagerImpl(timeoutMinutes)

        // Get into GCI_MODE
        manager.onGciConnectionStateChanged(ConnectionState.READY)
        assertEquals(OperatingMode.GCI_MODE, manager.operatingMode.value)

        // GCI disconnects (starts timeout)
        manager.onGciConnectionStateChanged(ConnectionState.DISCONNECTED)
        assertEquals(OperatingMode.GCI_MODE, manager.operatingMode.value)

        // GCI reconnects before timeout expires (cancels timeout)
        manager.onGciConnectionStateChanged(ConnectionState.READY)
        assertEquals(OperatingMode.GCI_MODE, manager.operatingMode.value)

        // Disconnect timeout expiry should have no effect (was cancelled)
        manager.onDisconnectTimeoutExpired()
        assertEquals(OperatingMode.GCI_MODE, manager.operatingMode.value,
            "GCI_MODE should remain after reconnection cancels disconnect timeout")
    }

    // =========================================================================
    // Helper types and methods
    // =========================================================================

    /**
     * Represents events that can be applied to the sleep state machine.
     */
    enum class SleepEvent {
        GCI_CONNECTED,
        GCI_READY,
        GCI_DISCONNECTED,
        GCI_SCANNING,
        GCI_CONNECTING,
        GCI_HANDSHAKING,
        GRACE_PERIOD_EXPIRED,
        DISCONNECT_TIMEOUT_EXPIRED
    }

    /**
     * Applies an event to the sleep manager.
     */
    private fun applyEvent(manager: SleepManagerImpl, event: SleepEvent) {
        when (event) {
            SleepEvent.GCI_CONNECTED -> manager.onGciConnectionStateChanged(ConnectionState.CONNECTED)
            SleepEvent.GCI_READY -> manager.onGciConnectionStateChanged(ConnectionState.READY)
            SleepEvent.GCI_DISCONNECTED -> manager.onGciConnectionStateChanged(ConnectionState.DISCONNECTED)
            SleepEvent.GCI_SCANNING -> manager.onGciConnectionStateChanged(ConnectionState.SCANNING)
            SleepEvent.GCI_CONNECTING -> manager.onGciConnectionStateChanged(ConnectionState.CONNECTING)
            SleepEvent.GCI_HANDSHAKING -> manager.onGciConnectionStateChanged(ConnectionState.HANDSHAKING)
            SleepEvent.GRACE_PERIOD_EXPIRED -> manager.onGracePeriodExpired()
            SleepEvent.DISCONNECT_TIMEOUT_EXPIRED -> manager.onDisconnectTimeoutExpired()
        }
    }

    /**
     * Validates whether a state transition is valid given the event that caused it.
     */
    private fun isValidTransition(from: OperatingMode, to: OperatingMode, event: SleepEvent): Boolean {
        return when (from to to) {
            OperatingMode.STARTUP_GRACE to OperatingMode.GCI_MODE ->
                event == SleepEvent.GCI_CONNECTED || event == SleepEvent.GCI_READY

            OperatingMode.STARTUP_GRACE to OperatingMode.STANDALONE_MODE ->
                event == SleepEvent.GRACE_PERIOD_EXPIRED

            OperatingMode.GCI_MODE to OperatingMode.STANDALONE_MODE ->
                event == SleepEvent.DISCONNECT_TIMEOUT_EXPIRED

            OperatingMode.STANDALONE_MODE to OperatingMode.GCI_MODE ->
                event == SleepEvent.GCI_CONNECTED || event == SleepEvent.GCI_READY

            else -> false // No other transitions are valid
        }
    }

    // =========================================================================
    // Generators
    // =========================================================================

    /**
     * Backlight timeout values in minutes (0 to 60).
     * The grace period is max(timeout * 60000, 30000) ms.
     */
    @Provide
    fun backlightTimeouts(): Arbitrary<Int> {
        return Arbitraries.integers().between(0, 60)
    }

    /**
     * All possible ConnectionState values.
     */
    @Provide
    fun connectionStates(): Arbitrary<ConnectionState> {
        return Arbitraries.of(*ConnectionState.entries.toTypedArray())
    }

    /**
     * Non-connected ConnectionState values (states that should NOT trigger a transition to GCI_MODE).
     */
    @Provide
    fun nonConnectedStates(): Arbitrary<ConnectionState> {
        return Arbitraries.of(
            ConnectionState.DISCONNECTED,
            ConnectionState.SCANNING,
            ConnectionState.CONNECTING,
            ConnectionState.HANDSHAKING
        )
    }

    /**
     * Generates sequences of sleep events for state machine exploration.
     * Sequences are between 1 and 20 events long.
     */
    @Provide
    fun eventSequences(): Arbitrary<List<SleepEvent>> {
        return Arbitraries.of(*SleepEvent.entries.toTypedArray())
            .list()
            .ofMinSize(1)
            .ofMaxSize(20)
    }
}
