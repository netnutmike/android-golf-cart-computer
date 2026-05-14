package com.golfcart.gcd.di

import android.util.Log
import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.TelemetryConnection
import com.golfcart.gcd.domain.geofence.GeofenceManager
import com.golfcart.gcd.domain.gps.GpsProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes at_home and is_daytime status changes and notifies the GCI.
 *
 * Responsibilities:
 * 1. When the GCI connection is established, send current at_home and is_daytime status.
 * 2. When at_home or is_daytime status changes while GCI is connected, notify the GCI.
 *
 * Requirements: 8.13, 8.14
 */
@Singleton
class GciStatusNotifier @Inject constructor(
    private val telemetryConnection: TelemetryConnection,
    private val geofenceManager: GeofenceManager,
    private val gpsProcessor: GpsProcessor
) {

    companion object {
        private const val TAG = "GciStatusNotifier"
    }

    /** Track the last sent values to avoid redundant sends. */
    private var lastSentIsHome: Boolean? = null
    private var lastSentIsDaytime: Boolean? = null

    /**
     * Starts observing status changes and GCI connection state.
     * Should be called once from the application scope during initialization.
     *
     * @param scope The coroutine scope to launch collection in (typically application scope).
     */
    fun start(scope: CoroutineScope) {
        // Observe GCI connection state changes — send current status on connection
        scope.launch {
            var previousState: ConnectionState? = null
            telemetryConnection.connectionState.collect { state ->
                if (state != previousState) {
                    previousState = state
                    if (state == ConnectionState.READY || state == ConnectionState.CONNECTED) {
                        sendCurrentStatus()
                    } else if (state == ConnectionState.DISCONNECTED) {
                        // Reset last sent values so we re-send on reconnection
                        lastSentIsHome = null
                        lastSentIsDaytime = null
                    }
                }
            }
        }

        // Observe at_home status changes — notify GCI when it changes
        scope.launch {
            var previousIsHome: Boolean? = null
            geofenceManager.geofenceState.collect { geofenceState ->
                val isAtHome = geofenceState.isAtHome
                if (isAtHome != previousIsHome) {
                    previousIsHome = isAtHome
                    val gciState = telemetryConnection.connectionState.value
                    if (gciState == ConnectionState.READY || gciState == ConnectionState.CONNECTED) {
                        if (lastSentIsHome != isAtHome) {
                            sendIsHome(isAtHome)
                        }
                    }
                }
            }
        }

        // Observe is_daytime status changes — notify GCI when it changes
        scope.launch {
            var previousIsDaytime: Boolean? = null
            gpsProcessor.navigationData.collect { navData ->
                val isDaytime = navData.isDaytime
                if (isDaytime != previousIsDaytime) {
                    previousIsDaytime = isDaytime
                    val gciState = telemetryConnection.connectionState.value
                    if (gciState == ConnectionState.READY || gciState == ConnectionState.CONNECTED) {
                        if (lastSentIsDaytime != isDaytime) {
                            sendIsDaytime(isDaytime)
                        }
                    }
                }
            }
        }

        Log.i(TAG, "GciStatusNotifier started — observing at_home and is_daytime changes")
    }

    /**
     * Sends the current at_home and is_daytime status to the GCI.
     * Called when the GCI connection is first established.
     *
     * Requirement 8.13: When GCI connection is established, send current status.
     */
    private suspend fun sendCurrentStatus() {
        val isAtHome = geofenceManager.geofenceState.value.isAtHome
        val isDaytime = gpsProcessor.navigationData.value.isDaytime

        sendIsHome(isAtHome)
        sendIsDaytime(isDaytime)

        Log.i(TAG, "Sent initial status to GCI: at_home=$isAtHome, is_daytime=$isDaytime")
    }

    /**
     * Sends the at_home status to the GCI.
     *
     * Requirement 8.13, 8.14: Notify GCI of at_home status on connection and changes.
     */
    private suspend fun sendIsHome(isHome: Boolean) {
        try {
            telemetryConnection.sendIsHome(isHome)
            lastSentIsHome = isHome
            Log.d(TAG, "Sent is_home=$isHome to GCI")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send is_home status to GCI: ${e.message}")
        }
    }

    /**
     * Sends the is_daytime status to the GCI.
     *
     * Requirement 8.13, 8.14: Notify GCI of is_daytime status on connection and changes.
     */
    private suspend fun sendIsDaytime(isDaytime: Boolean) {
        try {
            telemetryConnection.sendIsDaytime(isDaytime)
            lastSentIsDaytime = isDaytime
            Log.d(TAG, "Sent is_daytime=$isDaytime to GCI")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send is_daytime status to GCI: ${e.message}")
        }
    }
}
