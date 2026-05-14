package com.golfcart.gcd.data.bluetooth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MeshtasticLifecycleManager] — heartbeat and liveness management.
 *
 * Tests cover:
 * - Lifecycle state transitions
 * - Heartbeat interval timing (30 seconds)
 * - Liveness timeout detection (60 seconds)
 * - Reconnection triggering
 * - Data received timestamp updates
 * - Graceful disconnect message creation
 * - Start/stop behavior
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshtasticLifecycleManagerTest {

    private lateinit var handshake: MeshtasticHandshake
    private lateinit var testScope: TestScope
    private var reconnectTriggered: Boolean = false
    private var heartbeatCount: Int = 0

    @BeforeEach
    fun setUp() {
        handshake = MeshtasticHandshake()
        reconnectTriggered = false
        heartbeatCount = 0
        testScope = TestScope(StandardTestDispatcher())
    }

    private fun createManager(
        protocol: MeshtasticProtocol? = null
    ): MeshtasticLifecycleManager {
        return MeshtasticLifecycleManager(
            scope = testScope,
            protocol = protocol,
            handshake = handshake,
            onReconnectNeeded = { reconnectTriggered = true },
            onHeartbeatSent = { heartbeatCount++ },
            timeProvider = { testScope.testScheduler.currentTime }
        )
    }

    // --- State Transitions ---

    @Test
    fun `initial state is INACTIVE`() {
        val manager = createManager()
        assertEquals(MeshtasticLifecycleManager.LifecycleState.INACTIVE, manager.lifecycleState.value)
    }

    @Test
    fun `start transitions to ACTIVE`() {
        val manager = createManager()
        manager.start()
        assertEquals(MeshtasticLifecycleManager.LifecycleState.ACTIVE, manager.lifecycleState.value)
    }

    @Test
    fun `stop transitions to INACTIVE`() {
        val manager = createManager()
        manager.start()
        manager.stop()
        assertEquals(MeshtasticLifecycleManager.LifecycleState.INACTIVE, manager.lifecycleState.value)
    }

    @Test
    fun `start when already active does not reset state`() {
        val manager = createManager()
        manager.start()
        val firstTimestamp = manager.lastReceivedTimestamp
        manager.start() // Should be a no-op
        assertEquals(firstTimestamp, manager.lastReceivedTimestamp)
    }

    // --- Data Received Tracking ---

    @Test
    fun `onDataReceived updates timestamp`() = testScope.runTest {
        val manager = createManager()
        manager.start()
        val initialTimestamp = manager.lastReceivedTimestamp

        // Advance virtual time so the timestamp changes
        advanceTimeBy(100)
        manager.onDataReceived()

        assertTrue(manager.lastReceivedTimestamp >= initialTimestamp)
    }

    // --- Heartbeat Timing ---

    @Test
    fun `heartbeat is sent after 30 seconds`() = testScope.runTest {
        val manager = createManager()
        manager.start()

        // Advance time by 30 seconds (heartbeat interval)
        advanceTimeBy(MeshtasticConstants.HEARTBEAT_INTERVAL_MS + 100)

        assertEquals(1, heartbeatCount)
    }

    @Test
    fun `multiple heartbeats are sent at 30-second intervals`() = testScope.runTest {
        val manager = createManager()
        manager.start()

        // Keep the connection alive by simulating data received between heartbeats
        // so the liveness monitor doesn't trigger a reconnect
        advanceTimeBy(MeshtasticConstants.HEARTBEAT_INTERVAL_MS + 100)
        manager.onDataReceived() // keep alive
        advanceTimeBy(MeshtasticConstants.HEARTBEAT_INTERVAL_MS)
        manager.onDataReceived() // keep alive
        advanceTimeBy(MeshtasticConstants.HEARTBEAT_INTERVAL_MS)

        assertEquals(3, heartbeatCount)
    }

    @Test
    fun `no heartbeat sent before 30 seconds`() = testScope.runTest {
        val manager = createManager()
        manager.start()

        // Advance time by less than heartbeat interval
        advanceTimeBy(MeshtasticConstants.HEARTBEAT_INTERVAL_MS - 1000)

        assertEquals(0, heartbeatCount)
    }

    @Test
    fun `heartbeat stops after stop is called`() = testScope.runTest {
        val manager = createManager()
        manager.start()

        // Let one heartbeat fire
        advanceTimeBy(MeshtasticConstants.HEARTBEAT_INTERVAL_MS + 100)
        assertEquals(1, heartbeatCount)

        // Stop the manager
        manager.stop()

        // Advance more time — no more heartbeats should fire
        advanceTimeBy(MeshtasticConstants.HEARTBEAT_INTERVAL_MS * 2)
        assertEquals(1, heartbeatCount)
    }

    // --- Liveness Timeout ---

    @Test
    fun `reconnect triggered after 60 seconds without data`() = testScope.runTest {
        val manager = createManager()
        manager.start()

        // Advance time past the liveness timeout
        // The liveness monitor checks at half the timeout interval (30s)
        advanceTimeBy(MeshtasticConstants.LIVENESS_TIMEOUT_MS + 1000)

        assertTrue(reconnectTriggered)
    }

    @Test
    fun `no reconnect when data is received within timeout`() = testScope.runTest {
        val manager = createManager()
        manager.start()

        // Simulate data received every 20 seconds (well within 60s timeout)
        repeat(5) {
            advanceTimeBy(20_000)
            manager.onDataReceived()
        }

        assertFalse(reconnectTriggered)
    }

    @Test
    fun `reconnect not triggered before timeout`() = testScope.runTest {
        val manager = createManager()
        manager.start()

        // Advance time to just before the liveness check would trigger reconnect
        // Liveness monitor checks at LIVENESS_TIMEOUT_MS / 2 = 30s intervals
        // At 30s check, timeSinceLastData = 30s which is < 60s, so no reconnect
        advanceTimeBy(MeshtasticConstants.LIVENESS_TIMEOUT_MS / 2 - 1000)

        assertFalse(reconnectTriggered)
    }

    // --- Heartbeat Message ---

    @Test
    fun `createHeartbeatMessage returns non-empty bytes`() {
        val manager = createManager()
        val heartbeat = manager.createHeartbeatMessage()
        assertTrue(heartbeat.isNotEmpty())
    }

    // --- Disconnect Message ---

    @Test
    fun `createDisconnectMessage via handshake returns valid bytes`() {
        val disconnectBytes = handshake.createDisconnectMessage()
        assertEquals(2, disconnectBytes.size)
        // Tag 4 (disconnect), wire type 0 = 0x20, value 1 (true)
        assertEquals(0x20.toByte(), disconnectBytes[0])
        assertEquals(0x01.toByte(), disconnectBytes[1])
    }

    // --- Constants Verification ---

    @Test
    fun `heartbeat interval is 30 seconds`() {
        assertEquals(30_000L, MeshtasticConstants.HEARTBEAT_INTERVAL_MS)
    }

    @Test
    fun `liveness timeout is 60 seconds`() {
        assertEquals(60_000L, MeshtasticConstants.LIVENESS_TIMEOUT_MS)
    }
}
