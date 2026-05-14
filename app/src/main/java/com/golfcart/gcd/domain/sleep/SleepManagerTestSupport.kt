package com.golfcart.gcd.domain.sleep

import com.golfcart.gcd.data.bluetooth.ConnectionState
import com.golfcart.gcd.data.bluetooth.MeshtasticConnection
import com.golfcart.gcd.data.bluetooth.TelemetryConnection
import com.golfcart.gcd.data.bluetooth.TelemetryData
import com.golfcart.gcd.data.persistence.CachedVenueData
import com.golfcart.gcd.data.persistence.CachedWeatherData
import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.data.persistence.OdometerData
import com.golfcart.gcd.data.persistence.PreferenceKey
import com.golfcart.gcd.data.persistence.UserPreferences
import com.golfcart.gcd.domain.geofence.GeofenceManager
import com.golfcart.gcd.domain.geofence.GeofenceState
import com.golfcart.gcd.domain.geofence.SetHomeResult
import com.golfcart.gcd.domain.odometer.OdometerManager
import com.golfcart.gcd.domain.odometer.OdometerState
import com.golfcart.gcd.domain.service.ServiceReminderManager
import com.golfcart.gcd.domain.service.ServiceReminderState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

// =========================================================================
// No-op implementations for testing constructor
// =========================================================================

internal object NoOpTelemetryConnection : TelemetryConnection {
    override val connectionState: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.DISCONNECTED)
    override val telemetryData: StateFlow<TelemetryData> =
        MutableStateFlow(TelemetryData())
    override suspend fun sendHeartbeat() {}
    override suspend fun sendGpsData(
        latitude: Float, longitude: Float, altitude: Float,
        speed: Float, heading: Float, satellites: Int
    ) {}
    override suspend fun sendIsHome(isHome: Boolean) {}
    override suspend fun sendIsDaytime(isDaytime: Boolean) {}
    override suspend fun pairNewDevice(timeoutSeconds: Int) {}
    override suspend fun connect(deviceAddress: String) {}
    override suspend fun disconnect() {}
}

internal object NoOpMeshtasticConnection : MeshtasticConnection {
    override val connectionState: StateFlow<ConnectionState> =
        MutableStateFlow(ConnectionState.DISCONNECTED)
    override val nodeId: StateFlow<String> = MutableStateFlow("")
    override val incomingPackets: SharedFlow<ByteArray> =
        kotlinx.coroutines.flow.MutableSharedFlow()
    override suspend fun sendTextMessage(text: String, destination: Long, channel: Int) {}
    override suspend fun sendAdminMessage(message: ByteArray) {}
    override suspend fun setPositionConfig(config: ByteArray) {}
    override suspend fun rebootRadio(delaySeconds: Int) {}
    override suspend fun disconnect() {}
    override suspend fun connect(deviceAddress: String) {}
    override suspend fun startScan() {}
    override suspend fun stopScan() {}
}

internal object NoOpOdometerManager : OdometerManager {
    override val odometerState: StateFlow<OdometerState> =
        MutableStateFlow(OdometerState())
    override fun resetTripOdometer() {}
    override suspend fun persistBeforeShutdown() {}
}

internal object NoOpServiceReminderManager : ServiceReminderManager {
    override val serviceReminderState: StateFlow<ServiceReminderState> =
        MutableStateFlow(ServiceReminderState())
    override fun resetServiceHours() {}
    override fun setServiceInterval(hours: Int) {}
    override suspend fun persistBeforeShutdown() {}
}

internal object NoOpGeofenceManager : GeofenceManager {
    override val geofenceState: StateFlow<GeofenceState> =
        MutableStateFlow(GeofenceState())
    override fun setHomeLocation(lat: Double, lon: Double): SetHomeResult = SetHomeResult.Success
    override fun clearHomeLocation() {}
    override fun setFenceRadius(radiusMeters: Int) {}
}

internal object NoOpSleepDataStoreRepository : DataStoreRepository {
    override fun getPreferences(): Flow<UserPreferences> = flowOf(UserPreferences())
    override suspend fun updatePreference(key: PreferenceKey, value: Any) {}
    override suspend fun resetAllPreferences() {}
    override fun getCachedWeather(): Flow<CachedWeatherData> = flowOf(CachedWeatherData())
    override fun getCachedVenueEvents(): Flow<CachedVenueData> = flowOf(CachedVenueData())
    override suspend fun cacheWeatherData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
    override suspend fun cacheVenueData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
    override suspend fun persistOdometer(accumDistance: Float, tripDistance: Float) {}
    override suspend fun persistDrivingHours(tenthsOfHours: Int) {}
    override fun getPersistedOdometer(): Flow<OdometerData> = flowOf(OdometerData())
    override fun getPersistedDrivingHours(): Flow<Int> = flowOf(0)
}
