package com.golfcart.gcd.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.MeshtasticConnection
import com.golfcart.gcd.data.bluetooth.TelemetryConnection
import com.golfcart.gcd.data.bluetooth.TelemetryData
import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.data.persistence.UserPreferences
import com.golfcart.gcd.domain.brightness.BacklightManager
import com.golfcart.gcd.domain.geofence.GeofenceManager
import com.golfcart.gcd.domain.gps.GpsProcessor
import com.golfcart.gcd.domain.gps.NavigationData
import com.golfcart.gcd.domain.gps.ProcessedGpsData
import com.golfcart.gcd.domain.odometer.OdometerManager
import com.golfcart.gcd.domain.odometer.OdometerState
import com.golfcart.gcd.domain.service.ServiceReminderManager
import com.golfcart.gcd.domain.service.ServiceReminderState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the main display screen.
 *
 * Aggregates state from all domain managers and data layer connections to provide
 * a unified UI state for the main Compose screen. Handles the "new data received"
 * indicator with 5-second auto-clear.
 *
 * Requirements: 13.1, 13.6, 13.10, 13.11, 8.3, 8.4, 8.5, 8.6
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val gpsProcessor: GpsProcessor,
    private val odometerManager: OdometerManager,
    private val serviceReminderManager: ServiceReminderManager,
    private val backlightManager: BacklightManager,
    private val geofenceManager: GeofenceManager,
    private val telemetryConnection: TelemetryConnection,
    private val meshtasticConnection: MeshtasticConnection,
    private val dataStoreRepository: DataStoreRepository
) : ViewModel() {

    /** GPS data (speed, heading, satellites, HDOP). */
    val gpsState: StateFlow<ProcessedGpsData> = gpsProcessor.gpsState

    /** Navigation data (date, time, sunrise/sunset). */
    val navigationData: StateFlow<NavigationData> = gpsProcessor.navigationData

    /** Odometer state (total miles, trip miles). */
    val odometerState: StateFlow<OdometerState> = odometerManager.odometerState

    /** Service reminder state (hours since service). */
    val serviceReminderState: StateFlow<ServiceReminderState> = serviceReminderManager.serviceReminderState

    /** Meshtastic connection state. */
    val meshtasticConnectionState: StateFlow<ConnectionState> = meshtasticConnection.connectionState

    /** GCI telemetry connection state. */
    val gciConnectionState: StateFlow<ConnectionState> = telemetryConnection.connectionState

    /** GCI telemetry data (battery, fuel, headlight, temperature). */
    val telemetryData: StateFlow<TelemetryData> = telemetryConnection.telemetryData

    /** "New data received" indicator — auto-clears after 5 seconds. */
    private val _newDataReceived = MutableStateFlow(false)
    val newDataReceived: StateFlow<Boolean> = _newDataReceived.asStateFlow()

    /** Job for the auto-clear timer. */
    private var newDataClearJob: Job? = null

    /** User preferences (for temperature offset). */
    val userPreferences: StateFlow<UserPreferences> = dataStoreRepository.getPreferences()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = UserPreferences()
        )

    /**
     * Computes the outdoor air temperature with the user-configurable offset applied.
     *
     * Requirement 8.5: Display outdoor air temperature with configurable offset.
     */
    val adjustedTemperature: StateFlow<Float> = combine(
        telemetryConnection.telemetryData,
        dataStoreRepository.getPreferences()
    ) { telemetry, prefs ->
        telemetry.airTemperature + prefs.temperatureOffset
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = 0f
    )

    /**
     * Signals that new data (weather or entertainment) has been received.
     * Sets the indicator to true and starts a 5-second auto-clear timer.
     *
     * Requirement 13.10, 13.11: "New data received" indicator with 5-second auto-clear.
     */
    fun onNewDataReceived() {
        _newDataReceived.value = true
        newDataClearJob?.cancel()
        newDataClearJob = viewModelScope.launch {
            delay(NEW_DATA_INDICATOR_DURATION_MS)
            _newDataReceived.value = false
        }
    }

    companion object {
        /** Duration in milliseconds before the "new data received" indicator auto-clears. */
        const val NEW_DATA_INDICATOR_DURATION_MS = 5_000L
    }
}
