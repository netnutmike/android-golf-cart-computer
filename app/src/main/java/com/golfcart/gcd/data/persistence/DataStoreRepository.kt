package com.golfcart.gcd.data.persistence

import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for persistent data storage using Jetpack DataStore.
 *
 * Provides type-safe access to user preferences, cached weather/venue data,
 * and odometer/service hour persistence.
 *
 * Requirements: 15.1, 15.2, 15.3, 15.4
 */
interface DataStoreRepository {

    // --- Preferences ---

    /**
     * Returns a Flow of [UserPreferences] that emits whenever any preference value changes.
     * On first collection, emits the current stored preferences (or defaults if none stored).
     *
     * Requirement 15.2: Load all persisted settings on application startup.
     */
    fun getPreferences(): Flow<UserPreferences>

    /**
     * Updates a single preference value identified by [key].
     * For slider/spinner values, writes are debounced by 2 seconds to reduce storage wear.
     *
     * Requirement 15.3: Debounce writes to persistent storage by 2 seconds for slider/spinner values.
     *
     * @param key The preference to update
     * @param value The new value (type must match the preference key's expected type)
     */
    suspend fun updatePreference(key: PreferenceKey, value: Any)

    /**
     * Clears all saved settings, restoring defaults.
     *
     * Requirement 15.4: Provide a "reset all preferences" option that clears all saved settings.
     */
    suspend fun resetAllPreferences()

    // --- Cached data (implemented in Task 3.2) ---

    /**
     * Returns a Flow of cached weather data.
     */
    fun getCachedWeather(): Flow<CachedWeatherData>

    /**
     * Returns a Flow of cached venue/event data.
     */
    fun getCachedVenueEvents(): Flow<CachedVenueData>

    /**
     * Caches weather data with its timestamp and date.
     *
     * @param rawPacket The raw weather packet string
     * @param timestamp The parsed timestamp string
     * @param dateYYYYMMDD The date in YYYYMMDD integer format
     */
    suspend fun cacheWeatherData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int)

    /**
     * Caches venue/event data with its timestamp and date.
     *
     * @param rawPacket The raw venue/event packet string
     * @param timestamp The parsed timestamp string
     * @param dateYYYYMMDD The date in YYYYMMDD integer format
     */
    suspend fun cacheVenueData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int)

    // --- Odometer/Service (implemented in Task 3.3) ---

    /**
     * Persists odometer values.
     *
     * @param accumDistance Total accumulated distance in miles
     * @param tripDistance Trip odometer distance in miles
     */
    suspend fun persistOdometer(accumDistance: Float, tripDistance: Float)

    /**
     * Persists driving hours.
     *
     * @param tenthsOfHours Driving hours in tenths (6-minute resolution)
     */
    suspend fun persistDrivingHours(tenthsOfHours: Int)

    /**
     * Returns a Flow of persisted odometer values (accumulated and trip distances).
     * Emits default values (0.0f) if no data has been persisted yet.
     *
     * Requirement 6.10: Load persisted odometer values on startup.
     */
    fun getPersistedOdometer(): Flow<OdometerData>

    /**
     * Returns a Flow of persisted driving hours in tenths (6-minute resolution).
     * Emits 0 if no data has been persisted yet.
     *
     * Requirement 7.8: Persist driving hours to local storage every 1.0 hours of driving.
     */
    fun getPersistedDrivingHours(): Flow<Int>
}

/**
 * Data class representing cached weather data from DataStore.
 */
data class CachedWeatherData(
    val rawPacket: String? = null,
    val timestamp: String? = null,
    val dateYYYYMMDD: Int = 0
)

/**
 * Data class representing cached venue/event data from DataStore.
 */
data class CachedVenueData(
    val rawPacket: String? = null,
    val timestamp: String? = null,
    val dateYYYYMMDD: Int = 0
)
