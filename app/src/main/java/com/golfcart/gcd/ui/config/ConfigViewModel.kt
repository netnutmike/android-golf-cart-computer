package com.golfcart.gcd.ui.config

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golfcart.gcd.BuildConfig
import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.MeshtasticConnection
import com.golfcart.gcd.data.bluetooth.TelemetryConnection
import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.data.persistence.PreferenceKey
import com.golfcart.gcd.data.persistence.UserPreferences
import com.golfcart.gcd.domain.audio.AudioManager
import com.golfcart.gcd.domain.geofence.GeofenceManager
import com.golfcart.gcd.domain.geofence.SetHomeResult
import com.golfcart.gcd.domain.gps.GpsProcessor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Configuration screen.
 *
 * Manages all user-configurable settings, device information display,
 * and action commands (pairing, reboot, reset).
 *
 * Requirements: 13.4, 13.5, 13.7, 13.8, 13.9, 12.1, 12.2, 12.7
 */
@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val dataStoreRepository: DataStoreRepository,
    private val geofenceManager: GeofenceManager,
    private val meshtasticConnection: MeshtasticConnection,
    private val telemetryConnection: TelemetryConnection,
    private val gpsProcessor: GpsProcessor,
    private val audioManager: AudioManager
) : ViewModel() {

    companion object {
        private const val TAG = "ConfigViewModel"

        /** Delay in seconds before Meshtastic radio reboots. */
        const val RADIO_REBOOT_DELAY_SECONDS = 5

        /** Timeout in seconds for GCI pairing window. */
        const val GCI_PAIRING_TIMEOUT_SECONDS = 6
    }

    /** User preferences state. */
    val preferences: StateFlow<UserPreferences> = dataStoreRepository.getPreferences()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences()
        )

    /** Meshtastic connection state. */
    val meshtasticConnectionState: StateFlow<ConnectionState> = meshtasticConnection.connectionState

    /** GCI telemetry connection state. */
    val gciConnectionState: StateFlow<ConnectionState> = telemetryConnection.connectionState

    /** Meshtastic radio node ID (hex format, e.g., "!a1b2c3d4"). */
    val meshtasticNodeId: StateFlow<String> = meshtasticConnection.nodeId

    /** Status message for user feedback on actions. */
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    /** Whether a GCI pairing operation is in progress. */
    private val _pairingInProgress = MutableStateFlow(false)
    val pairingInProgress: StateFlow<Boolean> = _pairingInProgress.asStateFlow()

    /** App version string from BuildConfig. */
    val appVersion: String = BuildConfig.VERSION_NAME

    // --- Preference Update Methods ---

    /**
     * Updates the day brightness preference.
     * @param value Brightness level 0-10
     */
    fun setDayBrightness(value: Int) {
        updatePreference(PreferenceKey.DAY_BRIGHTNESS, value.coerceIn(0, 10))
    }

    /**
     * Updates the night brightness preference.
     * @param value Brightness level 0-10
     */
    fun setNightBrightness(value: Int) {
        updatePreference(PreferenceKey.NIGHT_BRIGHTNESS, value.coerceIn(0, 10))
    }

    /**
     * Updates the speaker volume preference.
     * Also updates the AudioManager's volume level in real-time.
     * @param value Volume level 0-20
     *
     * Requirement 14.7: Configurable speaker volume level (range 0-20).
     */
    fun setSpeakerVolume(value: Int) {
        val clamped = value.coerceIn(0, 20)
        audioManager.setVolume(clamped)
        updatePreference(PreferenceKey.SPEAKER_VOLUME, clamped)
    }

    /**
     * Updates the screen flip (rotation) preference.
     * @param enabled Whether the screen should be flipped
     *
     * Requirement 13.9: Support screen rotation (flip) configurable by the user.
     */
    fun setFlipScreen(enabled: Boolean) {
        updatePreference(PreferenceKey.FLIP_SCREEN, enabled)
    }

    /**
     * Updates the backlight timeout preference.
     * @param minutes Timeout in minutes (0 disables auto-dim)
     */
    fun setBacklightTimeout(minutes: Int) {
        updatePreference(PreferenceKey.BACKLIGHT_TIMEOUT_MINUTES, minutes.coerceAtLeast(0))
    }

    /**
     * Updates the temperature offset preference.
     * @param offset Temperature offset in degrees Fahrenheit
     */
    fun setTemperatureOffset(offset: Float) {
        updatePreference(PreferenceKey.TEMPERATURE_OFFSET, offset)
    }

    /**
     * Updates the service interval preference.
     * @param hours Service interval in hours
     */
    fun setServiceInterval(hours: Int) {
        updatePreference(PreferenceKey.SERVICE_INTERVAL_HOURS, hours.coerceAtLeast(1))
    }

    /**
     * Enables or disables the Meshtastic connection.
     *
     * Requirement 12.7: Allow enabling/disabling the Meshtastic connection.
     */
    fun setMeshtasticEnabled(enabled: Boolean) {
        updatePreference(PreferenceKey.MESHTASTIC_ENABLED, enabled)
        if (!enabled) {
            viewModelScope.launch {
                try {
                    meshtasticConnection.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "Error disconnecting Meshtastic", e)
                }
            }
        }
    }

    // --- Home Location Actions ---

    /**
     * Sets the current GPS position as the home location.
     *
     * Uses the latest GPS coordinates from the GpsProcessor. If GPS is unavailable,
     * displays an error message and plays error tone.
     *
     * Requirement 9.1, 9.8
     */
    fun setHomeLocation() {
        val gpsState = gpsProcessor.gpsState.value
        if (!gpsState.isValid) {
            _statusMessage.value = "Cannot set home: GPS not available"
            audioManager.playError()
            return
        }

        val result = geofenceManager.setHomeLocation(gpsState.latitude, gpsState.longitude)
        when (result) {
            is SetHomeResult.Success -> {
                _statusMessage.value = "Home location set"
                audioManager.playConfirmation()
                Log.i(TAG, "Home location set to ${gpsState.latitude}, ${gpsState.longitude}")
            }
            is SetHomeResult.Error -> {
                _statusMessage.value = "Error: ${result.message}"
                audioManager.playError()
                Log.w(TAG, "Failed to set home location: ${result.message}")
            }
        }
    }

    /**
     * Clears the saved home location.
     *
     * Requirement 9.2
     */
    fun clearHomeLocation() {
        geofenceManager.clearHomeLocation()
        _statusMessage.value = "Home location cleared"
        audioManager.playConfirmation()
        Log.i(TAG, "Home location cleared")
    }

    // --- GCI Pairing ---

    /**
     * Initiates GCI pairing with a 6-second discovery window.
     *
     * Requirement 8.9, 8.10, 8.11
     */
    fun startGciPairing() {
        if (_pairingInProgress.value) return

        _pairingInProgress.value = true
        _statusMessage.value = "Pairing... waiting for GCI response"
        audioManager.playClick()

        viewModelScope.launch {
            try {
                telemetryConnection.pairNewDevice(GCI_PAIRING_TIMEOUT_SECONDS)
                _statusMessage.value = "GCI paired successfully"
                audioManager.playConfirmation()
                Log.i(TAG, "GCI pairing completed successfully")
            } catch (e: Exception) {
                _statusMessage.value = "Pairing failed: ${e.message}"
                audioManager.playError()
                Log.w(TAG, "GCI pairing failed", e)
            } finally {
                _pairingInProgress.value = false
            }
        }
    }

    // --- Meshtastic Radio Commands ---

    /**
     * Sends a reboot command to the connected Meshtastic radio.
     *
     * Requirement 12.2: Support sending a reboot command to the Meshtastic radio.
     */
    fun rebootMeshtasticRadio() {
        viewModelScope.launch {
            try {
                meshtasticConnection.rebootRadio(RADIO_REBOOT_DELAY_SECONDS)
                _statusMessage.value = "Radio reboot command sent"
                audioManager.playConfirmation()
                Log.i(TAG, "Meshtastic radio reboot command sent (delay=${RADIO_REBOOT_DELAY_SECONDS}s)")
            } catch (e: Exception) {
                _statusMessage.value = "Radio reboot failed: ${e.message}"
                audioManager.playError()
                Log.e(TAG, "Failed to reboot Meshtastic radio", e)
            }
        }
    }

    // --- System Actions ---

    /**
     * Resets all preferences to defaults.
     *
     * Requirement 15.4: Provide a "reset all preferences" option that clears all saved settings.
     */
    fun resetAllPreferences() {
        viewModelScope.launch {
            try {
                dataStoreRepository.resetAllPreferences()
                _statusMessage.value = "All preferences reset to defaults"
                audioManager.playConfirmation()
                Log.i(TAG, "All preferences reset")
            } catch (e: Exception) {
                _statusMessage.value = "Reset failed: ${e.message}"
                audioManager.playError()
                Log.e(TAG, "Failed to reset preferences", e)
            }
        }
    }

    /**
     * Clears the current status message.
     */
    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    /**
     * Helper to update a preference value via the repository.
     */
    private fun updatePreference(key: PreferenceKey, value: Any) {
        viewModelScope.launch {
            try {
                dataStoreRepository.updatePreference(key, value)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update preference $key", e)
                _statusMessage.value = "Failed to save setting"
            }
        }
    }
}
