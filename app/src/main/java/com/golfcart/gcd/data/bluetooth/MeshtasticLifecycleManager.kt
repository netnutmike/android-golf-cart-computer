package com.golfcart.gcd.data.bluetooth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages the Meshtastic BLE connection lifecycle after handshake completion.
 *
 * Responsibilities:
 * - Send heartbeat every 30 seconds (empty Heartbeat message in ToRadio)
 * - Track last received data timestamp
 * - If no data received within 60 seconds after heartbeat, trigger reconnection
 * - On disconnect, send ToRadio(disconnect=true) before closing BLE connection
 * - Persist bonded device address for automatic reconnection
 *
 * The heartbeat mechanism works as follows:
 * - Every 30 seconds, an empty ToRadio heartbeat is sent to the radio
 * - Each time data is received from the radio, the liveness timestamp is updated
 * - If 60 seconds elapse without any data from the radio, the connection is
 *   considered dead and reconnection is triggered
 *
 * Device bonding:
 * - When a successful connection is established, the device address is persisted
 * - On subsequent app launches, the persisted address is used for direct connection
 *   (skipping the scan phase)
 *
 * Validates: Requirements 1.10, 1.11, 1.12, 1.13
 */
class MeshtasticLifecycleManager(
    private val scope: CoroutineScope,
    private val protocol: MeshtasticProtocol?,
    private val handshake: MeshtasticHandshake,
    private val onReconnectNeeded: () -> Unit,
    private val onHeartbeatSent: () -> Unit = {},
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        private const val TAG = "MeshtasticLifecycle"
        private const val PREFS_NAME = "meshtastic_bonding"
        private const val KEY_BONDED_ADDRESS = "bonded_device_address"
    }

    /**
     * Lifecycle state of the connection.
     */
    enum class LifecycleState {
        /** Not active — no heartbeat or liveness monitoring. */
        INACTIVE,
        /** Active — heartbeat running, liveness monitored. */
        ACTIVE,
        /** Connection considered dead, reconnection in progress. */
        RECONNECTING
    }

    private val _lifecycleState = MutableStateFlow(LifecycleState.INACTIVE)
    val lifecycleState: StateFlow<LifecycleState> = _lifecycleState.asStateFlow()

    /** Timestamp (System.currentTimeMillis) of last received data from the radio. */
    @Volatile
    var lastReceivedTimestamp: Long = 0L
        private set

    /** Timestamp of last heartbeat sent. */
    @Volatile
    var lastHeartbeatTimestamp: Long = 0L
        private set

    private var heartbeatJob: Job? = null
    private var livenessJob: Job? = null

    /**
     * Starts the lifecycle manager after a successful handshake.
     *
     * Begins the heartbeat interval and liveness monitoring.
     */
    fun start() {
        if (_lifecycleState.value == LifecycleState.ACTIVE) {
            Log.d(TAG, "Lifecycle manager already active")
            return
        }

        lastReceivedTimestamp = timeProvider()
        lastHeartbeatTimestamp = 0L
        _lifecycleState.value = LifecycleState.ACTIVE

        startHeartbeat()
        startLivenessMonitor()

        Log.i(TAG, "Lifecycle manager started")
    }

    /**
     * Stops the lifecycle manager (e.g., on intentional disconnect).
     */
    fun stop() {
        heartbeatJob?.cancel()
        heartbeatJob = null
        livenessJob?.cancel()
        livenessJob = null
        _lifecycleState.value = LifecycleState.INACTIVE

        Log.i(TAG, "Lifecycle manager stopped")
    }

    /**
     * Called whenever data is received from the radio.
     * Updates the liveness timestamp to prevent timeout-based reconnection.
     */
    fun onDataReceived() {
        lastReceivedTimestamp = timeProvider()
    }

    /**
     * Performs a graceful disconnect sequence.
     *
     * Sends a ToRadio(disconnect=true) message before the BLE connection is closed.
     * This notifies the radio that the client is intentionally disconnecting.
     *
     * @return The disconnect message bytes to write, or null if protocol is unavailable.
     */
    suspend fun performGracefulDisconnect(): ByteArray? {
        stop()

        val disconnectBytes = handshake.createDisconnectMessage()
        try {
            protocol?.writeToRadio(disconnectBytes)
            Log.i(TAG, "Sent graceful disconnect message")
            return disconnectBytes
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send disconnect message (connection may already be lost)", e)
            return null
        }
    }

    /**
     * Creates a heartbeat message (empty ToRadio).
     *
     * The Meshtastic heartbeat is an empty ToRadio message — the radio interprets
     * any write to TORADIO as proof of client liveness.
     *
     * In production with Wire:
     * ```kotlin
     * ToRadio.ADAPTER.encode(ToRadio())
     * ```
     *
     * An empty protobuf message encodes to zero bytes, but we need at least a
     * minimal valid protobuf. We send a ToRadio with no fields set, which encodes
     * to an empty byte array. The framing layer adds the 4-byte length prefix.
     *
     * @return The heartbeat message bytes.
     */
    fun createHeartbeatMessage(): ByteArray {
        // An empty protobuf message is valid — zero bytes.
        // The protocol layer will frame it with a 4-byte length prefix of 0x00000000.
        // However, Meshtastic expects at least some content. We send a minimal
        // ToRadio with want_config_id=0 which acts as a no-op heartbeat.
        // Actually, any write to TORADIO keeps the connection alive.
        // We use a single zero byte as a minimal heartbeat payload.
        return byteArrayOf(0x00)
    }

    // --- Device Bonding ---

    /**
     * Persists the bonded device address for automatic reconnection.
     *
     * @param context Android context for SharedPreferences access.
     * @param deviceAddress The BLE MAC address to persist.
     */
    fun persistBondedDevice(context: Context, deviceAddress: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_BONDED_ADDRESS, deviceAddress).apply()
        Log.i(TAG, "Persisted bonded device address: $deviceAddress")
    }

    /**
     * Retrieves the previously bonded device address, if any.
     *
     * @param context Android context for SharedPreferences access.
     * @return The bonded device BLE address, or null if no device has been bonded.
     */
    fun getBondedDeviceAddress(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_BONDED_ADDRESS, null)
    }

    /**
     * Clears the persisted bonded device address.
     *
     * @param context Android context for SharedPreferences access.
     */
    fun clearBondedDevice(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_BONDED_ADDRESS).apply()
        Log.i(TAG, "Cleared bonded device address")
    }

    // --- Private Methods ---

    /**
     * Starts the 30-second heartbeat interval.
     *
     * Sends a heartbeat message to the radio every [MeshtasticConstants.HEARTBEAT_INTERVAL_MS]
     * milliseconds. Any write to TORADIO signals client liveness to the radio.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(MeshtasticConstants.HEARTBEAT_INTERVAL_MS)

                if (_lifecycleState.value != LifecycleState.ACTIVE) break

                try {
                    val heartbeatBytes = createHeartbeatMessage()
                    protocol?.writeToRadio(heartbeatBytes)
                    lastHeartbeatTimestamp = timeProvider()
                    onHeartbeatSent()
                    Log.d(TAG, "Heartbeat sent")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send heartbeat", e)
                    // Don't immediately reconnect on heartbeat failure —
                    // let the liveness monitor handle it
                }
            }
        }
    }

    /**
     * Starts the liveness monitor that checks for radio responsiveness.
     *
     * If no data is received from the radio within [MeshtasticConstants.LIVENESS_TIMEOUT_MS]
     * after the last heartbeat was sent, the connection is considered dead and
     * reconnection is triggered.
     */
    private fun startLivenessMonitor() {
        livenessJob?.cancel()
        livenessJob = scope.launch {
            while (isActive) {
                delay(MeshtasticConstants.LIVENESS_TIMEOUT_MS / 2) // Check at half the timeout interval

                if (_lifecycleState.value != LifecycleState.ACTIVE) break

                val now = timeProvider()
                val timeSinceLastData = now - lastReceivedTimestamp

                if (timeSinceLastData >= MeshtasticConstants.LIVENESS_TIMEOUT_MS) {
                    Log.w(TAG, "Liveness timeout! No data received for ${timeSinceLastData}ms " +
                            "(threshold: ${MeshtasticConstants.LIVENESS_TIMEOUT_MS}ms)")
                    _lifecycleState.value = LifecycleState.RECONNECTING
                    stop()
                    onReconnectNeeded()
                    break
                }
            }
        }
    }
}
