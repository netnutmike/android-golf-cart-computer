package com.golfcart.gcd.data.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [DataStoreRepository] using Jetpack DataStore Preferences.
 *
 * Provides persistent storage for user preferences with 2-second debounce
 * for slider/spinner values to reduce storage wear (Requirement 15.3).
 *
 * Requirements: 15.1, 15.2, 15.3, 15.4
 */
@Singleton
class DataStoreRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope
) : DataStoreRepository {

    companion object {
        /** Debounce delay for slider/spinner preference writes. */
        const val DEBOUNCE_DELAY_MS = 2000L
    }

    /**
     * Tracks pending debounced write jobs per preference key.
     * When a new write arrives for the same key within the debounce window,
     * the previous job is cancelled and a new one is scheduled.
     */
    private val debounceJobs = mutableMapOf<PreferenceKey, Job>()

    // --- Preferences ---

    override fun getPreferences(): Flow<UserPreferences> {
        return dataStore.data.map { preferences ->
            UserPreferences(
                dayBrightness = preferences[PreferenceKeys.DAY_BRIGHTNESS]
                    ?: UserPreferences.DEFAULT_DAY_BRIGHTNESS,
                nightBrightness = preferences[PreferenceKeys.NIGHT_BRIGHTNESS]
                    ?: UserPreferences.DEFAULT_NIGHT_BRIGHTNESS,
                speakerVolume = preferences[PreferenceKeys.SPEAKER_VOLUME]
                    ?: UserPreferences.DEFAULT_SPEAKER_VOLUME,
                flipScreen = preferences[PreferenceKeys.FLIP_SCREEN]
                    ?: UserPreferences.DEFAULT_FLIP_SCREEN,
                backlightTimeoutMinutes = preferences[PreferenceKeys.BACKLIGHT_TIMEOUT_MINUTES]
                    ?: UserPreferences.DEFAULT_BACKLIGHT_TIMEOUT_MINUTES,
                temperatureOffset = preferences[PreferenceKeys.TEMPERATURE_OFFSET]
                    ?: UserPreferences.DEFAULT_TEMPERATURE_OFFSET,
                serviceIntervalHours = preferences[PreferenceKeys.SERVICE_INTERVAL_HOURS]
                    ?: UserPreferences.DEFAULT_SERVICE_INTERVAL_HOURS,
                gciMacAddress = preferences[PreferenceKeys.GCI_MAC_ADDRESS],
                homeLatitude = preferences[PreferenceKeys.HOME_LATITUDE],
                homeLongitude = preferences[PreferenceKeys.HOME_LONGITUDE],
                homeFenceRadiusMeters = preferences[PreferenceKeys.HOME_FENCE_RADIUS_METERS]
                    ?: UserPreferences.DEFAULT_HOME_FENCE_RADIUS_METERS,
                meshtasticEnabled = preferences[PreferenceKeys.MESHTASTIC_ENABLED]
                    ?: UserPreferences.DEFAULT_MESHTASTIC_ENABLED,
                meshtasticDeviceAddress = preferences[PreferenceKeys.MESHTASTIC_DEVICE_ADDRESS]
            )
        }
    }

    override suspend fun updatePreference(key: PreferenceKey, value: Any) {
        if (isDebouncedKey(key)) {
            debouncedWrite(key, value)
        } else {
            writePreference(key, value)
        }
    }

    override suspend fun resetAllPreferences() {
        // Cancel any pending debounced writes
        debounceJobs.values.forEach { it.cancel() }
        debounceJobs.clear()

        dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    // --- Cached data (stub implementations for Task 3.2) ---

    override fun getCachedWeather(): Flow<CachedWeatherData> {
        return dataStore.data.map { preferences ->
            val rawPacket = preferences[CacheKeys.WEATHER_RAW_PACKET]
            val timestamp = preferences[CacheKeys.WEATHER_TIMESTAMP]
            val date = preferences[CacheKeys.WEATHER_DATE] ?: 0
            CachedWeatherData(rawPacket, timestamp, date)
        }
    }

    override fun getCachedVenueEvents(): Flow<CachedVenueData> {
        return dataStore.data.map { preferences ->
            val rawPacket = preferences[CacheKeys.VENUE_RAW_PACKET]
            val timestamp = preferences[CacheKeys.VENUE_TIMESTAMP]
            val date = preferences[CacheKeys.VENUE_DATE] ?: 0
            CachedVenueData(rawPacket, timestamp, date)
        }
    }

    override suspend fun cacheWeatherData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {
        dataStore.edit { preferences ->
            preferences[CacheKeys.WEATHER_RAW_PACKET] = rawPacket
            preferences[CacheKeys.WEATHER_TIMESTAMP] = timestamp
            preferences[CacheKeys.WEATHER_DATE] = dateYYYYMMDD
        }
    }

    override suspend fun cacheVenueData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {
        dataStore.edit { preferences ->
            preferences[CacheKeys.VENUE_RAW_PACKET] = rawPacket
            preferences[CacheKeys.VENUE_TIMESTAMP] = timestamp
            preferences[CacheKeys.VENUE_DATE] = dateYYYYMMDD
        }
    }

    // --- Odometer/Service (stub implementations for Task 3.3) ---

    override suspend fun persistOdometer(accumDistance: Float, tripDistance: Float) {
        dataStore.edit { preferences ->
            preferences[OdometerKeys.ACCUM_DISTANCE] = accumDistance
            preferences[OdometerKeys.TRIP_DISTANCE] = tripDistance
        }
    }

    override suspend fun persistDrivingHours(tenthsOfHours: Int) {
        dataStore.edit { preferences ->
            preferences[OdometerKeys.DRIVING_HOURS_TENTHS] = tenthsOfHours
        }
    }

    override fun getPersistedOdometer(): Flow<OdometerData> {
        return dataStore.data.map { preferences ->
            OdometerData(
                accumDistance = preferences[OdometerKeys.ACCUM_DISTANCE] ?: 0.0f,
                tripDistance = preferences[OdometerKeys.TRIP_DISTANCE] ?: 0.0f
            )
        }
    }

    override fun getPersistedDrivingHours(): Flow<Int> {
        return dataStore.data.map { preferences ->
            preferences[OdometerKeys.DRIVING_HOURS_TENTHS] ?: 0
        }
    }

    // --- Private helpers ---

    /**
     * Determines whether a preference key should use debounced writes.
     * Slider and spinner values are debounced to reduce storage wear.
     */
    private fun isDebouncedKey(key: PreferenceKey): Boolean {
        return when (key) {
            PreferenceKey.DAY_BRIGHTNESS,
            PreferenceKey.NIGHT_BRIGHTNESS,
            PreferenceKey.SPEAKER_VOLUME,
            PreferenceKey.BACKLIGHT_TIMEOUT_MINUTES,
            PreferenceKey.TEMPERATURE_OFFSET,
            PreferenceKey.SERVICE_INTERVAL_HOURS,
            PreferenceKey.HOME_FENCE_RADIUS_METERS -> true
            else -> false
        }
    }

    /**
     * Schedules a debounced write for the given preference key.
     * If a write for the same key is already pending, it is cancelled
     * and replaced with the new value.
     */
    private fun debouncedWrite(key: PreferenceKey, value: Any) {
        debounceJobs[key]?.cancel()
        debounceJobs[key] = scope.launch {
            delay(DEBOUNCE_DELAY_MS)
            writePreference(key, value)
            debounceJobs.remove(key)
        }
    }

    /**
     * Immediately writes a preference value to DataStore.
     */
    private suspend fun writePreference(key: PreferenceKey, value: Any) {
        dataStore.edit { preferences ->
            when (key) {
                PreferenceKey.DAY_BRIGHTNESS ->
                    preferences[PreferenceKeys.DAY_BRIGHTNESS] = value as Int
                PreferenceKey.NIGHT_BRIGHTNESS ->
                    preferences[PreferenceKeys.NIGHT_BRIGHTNESS] = value as Int
                PreferenceKey.SPEAKER_VOLUME ->
                    preferences[PreferenceKeys.SPEAKER_VOLUME] = value as Int
                PreferenceKey.FLIP_SCREEN ->
                    preferences[PreferenceKeys.FLIP_SCREEN] = value as Boolean
                PreferenceKey.BACKLIGHT_TIMEOUT_MINUTES ->
                    preferences[PreferenceKeys.BACKLIGHT_TIMEOUT_MINUTES] = value as Int
                PreferenceKey.TEMPERATURE_OFFSET ->
                    preferences[PreferenceKeys.TEMPERATURE_OFFSET] = value as Float
                PreferenceKey.SERVICE_INTERVAL_HOURS ->
                    preferences[PreferenceKeys.SERVICE_INTERVAL_HOURS] = value as Int
                PreferenceKey.GCI_MAC_ADDRESS ->
                    if (value is String?) {
                        if (value != null) {
                            preferences[PreferenceKeys.GCI_MAC_ADDRESS] = value
                        } else {
                            preferences.remove(PreferenceKeys.GCI_MAC_ADDRESS)
                        }
                    } else {
                        preferences[PreferenceKeys.GCI_MAC_ADDRESS] = value as String
                    }
                PreferenceKey.HOME_LATITUDE ->
                    if (value is Double?) {
                        if (value != null) {
                            preferences[PreferenceKeys.HOME_LATITUDE] = value
                        } else {
                            preferences.remove(PreferenceKeys.HOME_LATITUDE)
                        }
                    } else {
                        preferences[PreferenceKeys.HOME_LATITUDE] = value as Double
                    }
                PreferenceKey.HOME_LONGITUDE ->
                    if (value is Double?) {
                        if (value != null) {
                            preferences[PreferenceKeys.HOME_LONGITUDE] = value
                        } else {
                            preferences.remove(PreferenceKeys.HOME_LONGITUDE)
                        }
                    } else {
                        preferences[PreferenceKeys.HOME_LONGITUDE] = value as Double
                    }
                PreferenceKey.HOME_FENCE_RADIUS_METERS ->
                    preferences[PreferenceKeys.HOME_FENCE_RADIUS_METERS] = value as Int
                PreferenceKey.MESHTASTIC_ENABLED ->
                    preferences[PreferenceKeys.MESHTASTIC_ENABLED] = value as Boolean
                PreferenceKey.MESHTASTIC_DEVICE_ADDRESS ->
                    if (value is String?) {
                        if (value != null) {
                            preferences[PreferenceKeys.MESHTASTIC_DEVICE_ADDRESS] = value
                        } else {
                            preferences.remove(PreferenceKeys.MESHTASTIC_DEVICE_ADDRESS)
                        }
                    } else {
                        preferences[PreferenceKeys.MESHTASTIC_DEVICE_ADDRESS] = value as String
                    }
            }
        }
    }
}
