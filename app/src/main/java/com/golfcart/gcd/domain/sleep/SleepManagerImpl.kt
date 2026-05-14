package com.golfcart.gcd.domain.sleep

import android.util.Log
import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.MeshtasticConnection
import com.golfcart.gcd.data.bluetooth.TelemetryConnection
import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.domain.geofence.GeofenceManager
import com.golfcart.gcd.domain.odometer.OdometerManager
import com.golfcart.gcd.domain.service.ServiceReminderManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [SleepManager] with three-state power management.
 *
 * State machine:
 * - Starts in [OperatingMode.STARTUP_GRACE]
 * - Transitions to [OperatingMode.GCI_MODE] if GCI connects during grace period
 * - Transitions to [OperatingMode.STANDALONE_MODE] if grace period expires without GCI
 * - In GCI_MODE, transitions to STANDALONE_MODE if GCI disconnects for timeout period
 * - In STANDALONE_MODE, transitions back to GCI_MODE if GCI reconnects
 *
 * In standalone mode, adjusts Meshtastic GPS interval:
 * - At home: 120 seconds (2 minutes)
 * - Away: 8 seconds
 *
 * Persists odometer and driving hours before sleep.
 *
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
 */
@Singleton
class SleepManagerImpl @Inject constructor(
    private val telemetryConnection: TelemetryConnection,
    private val meshtasticConnection: MeshtasticConnection,
    private val odometerManager: OdometerManager,
    private val serviceReminderManager: ServiceReminderManager,
    private val geofenceManager: GeofenceManager,
    private val dataStoreRepository: DataStoreRepository,
    private val coroutineScope: CoroutineScope
) : SleepManager {

    private val _operatingMode = MutableStateFlow(OperatingMode.STARTUP_GRACE)
    override val operatingMode: StateFlow<OperatingMode> = _operatingMode.asStateFlow()

    /** Job for the startup grace period timer. */
    private var graceTimerJob: Job? = null

    /** Job for the GCI disconnect timeout timer. */
    private var disconnectTimerJob: Job? = null

    /** Whether the disconnect timeout is currently active (not cancelled by reconnection). */
    private var disconnectTimeoutActive: Boolean = false

    /** Job for observing geofence changes in standalone mode. */
    private var geofenceObserverJob: Job? = null

    /** The backlight timeout in minutes, used as the grace period and disconnect timeout. */
    private var backlightTimeoutMinutes: Int = DEFAULT_BACKLIGHT_TIMEOUT_MINUTES

    /** Whether GCI has ever connected during this session. */
    private var gciEverConnected: Boolean = false

    companion object {
        private const val TAG = "SleepManagerImpl"

        /** Minimum grace period in milliseconds (30 seconds). */
        const val MIN_GRACE_PERIOD_MS = 30_000L

        /** Default backlight timeout in minutes. */
        private const val DEFAULT_BACKLIGHT_TIMEOUT_MINUTES = 5

        /** GPS update interval when at home in standalone mode (seconds). */
        const val GPS_INTERVAL_AT_HOME_SECONDS = 120

        /** GPS update interval when away in standalone mode (seconds). */
        const val GPS_INTERVAL_AWAY_SECONDS = 8
    }

    init {
        // Observe user preferences for backlight timeout
        coroutineScope.launch {
            dataStoreRepository.getPreferences().collect { prefs ->
                backlightTimeoutMinutes = prefs.backlightTimeoutMinutes.coerceAtLeast(0)
            }
        }

        // Observe GCI connection state for mode transitions
        coroutineScope.launch {
            telemetryConnection.connectionState.collect { state ->
                onGciConnectionStateChanged(state)
            }
        }

        // Start the grace period timer
        startGracePeriodTimer()
    }

    /**
     * Internal constructor for testing without auto-starting observation coroutines.
     */
    internal constructor(
        backlightTimeoutMinutes: Int
    ) : this(
        telemetryConnection = NoOpTelemetryConnection,
        meshtasticConnection = NoOpMeshtasticConnection,
        odometerManager = NoOpOdometerManager,
        serviceReminderManager = NoOpServiceReminderManager,
        geofenceManager = NoOpGeofenceManager,
        dataStoreRepository = NoOpSleepDataStoreRepository,
        coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    ) {
        this.backlightTimeoutMinutes = backlightTimeoutMinutes
    }

    override suspend fun persistBeforeSleep() {
        Log.d(TAG, "Persisting odometer and driving hours before sleep")
        odometerManager.persistBeforeShutdown()
        serviceReminderManager.persistBeforeShutdown()
    }

    // =========================================================================
    // State machine logic
    // =========================================================================

    /**
     * Handles GCI connection state changes and triggers mode transitions.
     */
    internal fun onGciConnectionStateChanged(state: ConnectionState) {
        val gciConnected = state == ConnectionState.READY || state == ConnectionState.CONNECTED

        when (_operatingMode.value) {
            OperatingMode.STARTUP_GRACE -> {
                if (gciConnected) {
                    transitionToGciMode()
                }
            }
            OperatingMode.GCI_MODE -> {
                if (!gciConnected) {
                    startDisconnectTimeoutTimer()
                } else {
                    // GCI reconnected while timeout was running — cancel timeout
                    cancelDisconnectTimeoutTimer()
                }
            }
            OperatingMode.STANDALONE_MODE -> {
                if (gciConnected) {
                    transitionToGciMode()
                }
            }
        }

        if (gciConnected) {
            gciEverConnected = true
        }
    }

    /**
     * Starts the startup grace period timer.
     * Duration = backlight timeout (minimum 30 seconds).
     *
     * Requirement 11.2: Grace period equals backlight timeout, minimum 30 seconds.
     */
    internal fun startGracePeriodTimer() {
        graceTimerJob?.cancel()
        graceTimerJob = coroutineScope.launch {
            val gracePeriodMs = calculateGracePeriodMs()
            Log.d(TAG, "Starting grace period: ${gracePeriodMs}ms")
            delay(gracePeriodMs)
            onGracePeriodExpired()
        }
    }

    /**
     * Called when the grace period expires without GCI connecting.
     * Transitions to STANDALONE_MODE.
     */
    internal fun onGracePeriodExpired() {
        if (_operatingMode.value == OperatingMode.STARTUP_GRACE) {
            Log.d(TAG, "Grace period expired without GCI connection, entering standalone mode")
            transitionToStandaloneMode()
        }
    }

    /**
     * Transitions to GCI_MODE.
     * Cancels grace period timer and disconnect timeout timer.
     */
    internal fun transitionToGciMode() {
        Log.d(TAG, "Transitioning to GCI_MODE")
        graceTimerJob?.cancel()
        cancelDisconnectTimeoutTimer()
        stopGeofenceObserver()
        _operatingMode.value = OperatingMode.GCI_MODE
    }

    /**
     * Transitions to STANDALONE_MODE.
     * Starts observing geofence for GPS interval adjustment.
     *
     * Requirement 11.4: Standalone mode — backlight dimming only, never deep sleep.
     * Requirement 11.6: Adjust Meshtastic GPS interval based on at-home status.
     */
    internal fun transitionToStandaloneMode() {
        Log.d(TAG, "Transitioning to STANDALONE_MODE")
        graceTimerJob?.cancel()
        cancelDisconnectTimeoutTimer()
        _operatingMode.value = OperatingMode.STANDALONE_MODE
        startGeofenceObserver()
    }

    /**
     * Starts the disconnect timeout timer when GCI disconnects in GCI_MODE.
     * Duration = backlight timeout (minimum 30 seconds).
     *
     * Requirement 11.5: Transition to standalone mode when GCI disconnected for timeout period.
     */
    internal fun startDisconnectTimeoutTimer() {
        disconnectTimerJob?.cancel()
        disconnectTimeoutActive = true
        disconnectTimerJob = coroutineScope.launch {
            val timeoutMs = calculateGracePeriodMs()
            Log.d(TAG, "GCI disconnected, starting timeout: ${timeoutMs}ms")
            delay(timeoutMs)
            onDisconnectTimeoutExpired()
        }
    }

    /**
     * Called when the GCI disconnect timeout expires.
     * Transitions from GCI_MODE to STANDALONE_MODE.
     */
    internal fun onDisconnectTimeoutExpired() {
        if (_operatingMode.value == OperatingMode.GCI_MODE && disconnectTimeoutActive) {
            Log.d(TAG, "GCI disconnect timeout expired, transitioning to standalone mode")
            disconnectTimeoutActive = false
            transitionToStandaloneMode()
        }
    }

    /**
     * Cancels the disconnect timeout timer (e.g., when GCI reconnects).
     */
    private fun cancelDisconnectTimeoutTimer() {
        disconnectTimerJob?.cancel()
        disconnectTimerJob = null
        disconnectTimeoutActive = false
    }

    // =========================================================================
    // GPS interval management (standalone mode)
    // =========================================================================

    /**
     * Starts observing geofence state to adjust Meshtastic GPS interval.
     *
     * Requirement 11.6: In standalone mode, adjust GPS interval:
     * - At home: 120 seconds
     * - Away: 8 seconds
     */
    private fun startGeofenceObserver() {
        stopGeofenceObserver()
        geofenceObserverJob = coroutineScope.launch {
            geofenceManager.geofenceState.collect { geofenceState ->
                if (_operatingMode.value == OperatingMode.STANDALONE_MODE) {
                    val intervalSeconds = if (geofenceState.isAtHome) {
                        GPS_INTERVAL_AT_HOME_SECONDS
                    } else {
                        GPS_INTERVAL_AWAY_SECONDS
                    }
                    adjustMeshtasticGpsInterval(intervalSeconds)
                }
            }
        }
    }

    /**
     * Stops observing geofence state (when leaving standalone mode).
     */
    private fun stopGeofenceObserver() {
        geofenceObserverJob?.cancel()
        geofenceObserverJob = null
    }

    /**
     * Adjusts the Meshtastic radio GPS update interval using the read-modify-write
     * pattern. The MeshtasticAdminCommands class handles preserving unmodified fields
     * from the position config received during handshake.
     *
     * Requirement 12.4: Set GPS update interval (120s home, 8s away)
     * Requirement 12.5: Read-modify-write pattern preserves unmodified fields
     *
     * @param intervalSeconds The desired GPS update interval in seconds.
     */
    internal fun adjustMeshtasticGpsInterval(intervalSeconds: Int) {
        Log.d(TAG, "Adjusting Meshtastic GPS interval to ${intervalSeconds}s")
        coroutineScope.launch {
            try {
                val configBytes = encodeGpsIntervalConfig(intervalSeconds)
                meshtasticConnection.setPositionConfig(configBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to adjust Meshtastic GPS interval", e)
            }
        }
    }

    // =========================================================================
    // Utility methods
    // =========================================================================

    /**
     * Calculates the grace period / disconnect timeout in milliseconds.
     * Uses the backlight timeout setting with a minimum of 30 seconds.
     *
     * Requirement 11.2: Grace period = backlight timeout, minimum 30 seconds.
     */
    internal fun calculateGracePeriodMs(): Long {
        val timeoutMs = backlightTimeoutMinutes * 60L * 1000L
        return maxOf(timeoutMs, MIN_GRACE_PERIOD_MS)
    }

    /**
     * Encodes a GPS interval configuration as a PositionConfig protobuf byte array.
     *
     * This creates a minimal PositionConfig with only the gps_update_interval field
     * (tag 5, varint). The MeshtasticConnection.setPositionConfig wraps this in the
     * AdminMessage(set_config) structure.
     *
     * PositionConfig {
     *   gps_update_interval (tag 5, varint) = intervalSeconds
     * }
     *
     * Note: In a full read-modify-write scenario, the MeshtasticAdminCommands class
     * on the service side handles merging with the stored config from handshake.
     *
     * @param intervalSeconds The GPS update interval in seconds.
     * @return Byte array representing the PositionConfig protobuf.
     */
    internal fun encodeGpsIntervalConfig(intervalSeconds: Int): ByteArray {
        // Encode PositionConfig with gps_update_interval field (tag 5, wire type 0 = varint)
        // Tag byte: (5 << 3) | 0 = 40 = 0x28
        val tagByte: Byte = 0x28
        val varintBytes = encodeVarint(intervalSeconds.toLong() and 0xFFFFFFFFL)
        return byteArrayOf(tagByte) + varintBytes
    }

    /**
     * Encodes a Long value as a protobuf varint byte array.
     */
    private fun encodeVarint(value: Long): ByteArray {
        if (value == 0L) return byteArrayOf(0)

        val bytes = mutableListOf<Byte>()
        var remaining = value
        while (remaining != 0L) {
            var byte = (remaining and 0x7F).toInt()
            remaining = remaining ushr 7
            if (remaining != 0L) {
                byte = byte or 0x80
            }
            bytes.add(byte.toByte())
        }
        return bytes.toByteArray()
    }
}
