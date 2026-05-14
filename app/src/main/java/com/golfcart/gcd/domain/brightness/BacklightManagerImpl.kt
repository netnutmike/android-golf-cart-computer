package com.golfcart.gcd.domain.brightness

import android.util.Log
import com.golfcart.gcd.data.persistence.DataStoreRepository
import com.golfcart.gcd.data.persistence.UserPreferences
import com.golfcart.gcd.domain.gps.GpsProcessor
import com.golfcart.gcd.domain.gps.NavigationData
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
 * Implementation of [BacklightManager] that controls display brightness based on
 * time of day and user activity.
 *
 * Observes [NavigationData.isDaytime] from [GpsProcessor] to determine whether to
 * use day or night brightness. Observes [UserPreferences] from [DataStoreRepository]
 * for configurable brightness levels and inactivity timeout.
 *
 * When no touch or movement activity is detected for the configured timeout period,
 * the display is dimmed to off (brightness 0). Activity restores brightness to the
 * appropriate day/night level. Setting the timeout to 0 disables automatic dimming.
 *
 * Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7
 */
@Singleton
class BacklightManagerImpl @Inject constructor(
    private val gpsProcessor: GpsProcessor,
    private val dataStoreRepository: DataStoreRepository,
    private val coroutineScope: CoroutineScope
) : BacklightManager {

    internal val _brightnessState = MutableStateFlow(BrightnessState())
    override val brightnessState: StateFlow<BrightnessState> = _brightnessState.asStateFlow()

    /** Current user preferences for brightness levels and timeout. */
    private var dayBrightness: Int = UserPreferences.DEFAULT_DAY_BRIGHTNESS
    private var nightBrightness: Int = UserPreferences.DEFAULT_NIGHT_BRIGHTNESS
    private var timeoutMinutes: Int = UserPreferences.DEFAULT_BACKLIGHT_TIMEOUT_MINUTES

    /** Whether it is currently daytime (between sunrise and sunset). */
    private var isDaytime: Boolean = true

    /** Job for the inactivity timeout timer. Cancelled and restarted on activity. */
    private var timeoutJob: Job? = null

    companion object {
        private const val TAG = "BacklightManagerImpl"
    }

    init {
        // Observe user preferences for brightness settings
        coroutineScope.launch {
            dataStoreRepository.getPreferences().collect { prefs ->
                onPreferencesChanged(prefs)
            }
        }

        // Observe navigation data for daytime status
        coroutineScope.launch {
            gpsProcessor.navigationData.collect { navData ->
                onNavigationDataChanged(navData)
            }
        }
    }

    /**
     * Internal constructor for testing without auto-starting observation coroutines.
     */
    internal constructor(
        dayBrightness: Int,
        nightBrightness: Int,
        timeoutMinutes: Int,
        isDaytime: Boolean
    ) : this(
        gpsProcessor = NoOpGpsProcessor,
        dataStoreRepository = NoOpDataStoreRepository,
        coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
    ) {
        this.dayBrightness = dayBrightness
        this.nightBrightness = nightBrightness
        this.timeoutMinutes = timeoutMinutes
        this.isDaytime = isDaytime
        updateBrightness(dimmed = false)
    }

    override fun reportActivity() {
        if (_brightnessState.value.isDimmed) {
            // Restore brightness from dimmed state
            Log.d(TAG, "Activity detected while dimmed, restoring brightness")
        }
        updateBrightness(dimmed = false)
        resetInactivityTimer()
    }

    /**
     * Stops all background coroutines. Call when the manager is no longer needed.
     */
    fun stop() {
        timeoutJob?.cancel()
        timeoutJob = null
    }

    // =========================================================================
    // Internal logic
    // =========================================================================

    /**
     * Called when user preferences change. Updates brightness levels and timeout,
     * then recalculates the current brightness state.
     */
    internal fun onPreferencesChanged(prefs: UserPreferences) {
        dayBrightness = prefs.dayBrightness.coerceIn(BrightnessState.MIN_BRIGHTNESS, BrightnessState.MAX_BRIGHTNESS)
        nightBrightness = prefs.nightBrightness.coerceIn(BrightnessState.MIN_BRIGHTNESS, BrightnessState.MAX_BRIGHTNESS)
        timeoutMinutes = prefs.backlightTimeoutMinutes.coerceAtLeast(0)

        // If not currently dimmed, update to new brightness level
        if (!_brightnessState.value.isDimmed) {
            updateBrightness(dimmed = false)
        }

        // Reset the inactivity timer with the new timeout value
        resetInactivityTimer()
    }

    /**
     * Called when navigation data changes. Updates the daytime status and
     * recalculates brightness if not dimmed.
     */
    internal fun onNavigationDataChanged(navData: NavigationData) {
        val wasDaytime = isDaytime
        isDaytime = navData.isDaytime

        if (wasDaytime != isDaytime && !_brightnessState.value.isDimmed) {
            Log.d(TAG, "Daytime status changed: isDaytime=$isDaytime")
            updateBrightness(dimmed = false)
        }
    }

    /**
     * Updates the brightness state based on current conditions.
     *
     * @param dimmed Whether the display should be dimmed (brightness 0) due to inactivity
     */
    internal fun updateBrightness(dimmed: Boolean) {
        val level = if (dimmed) {
            BrightnessState.MIN_BRIGHTNESS
        } else {
            selectBrightnessLevel(isDaytime)
        }

        _brightnessState.value = BrightnessState(
            brightnessLevel = level,
            isDimmed = dimmed,
            isDaytime = isDaytime
        )
    }

    /**
     * Selects the appropriate brightness level based on whether it is daytime.
     *
     * Requirement 10.1: Day brightness between sunrise and sunset.
     * Requirement 10.2: Night brightness between sunset and sunrise.
     *
     * @param daytime true if current time is between sunrise and sunset
     * @return brightness level (0-10)
     */
    internal fun selectBrightnessLevel(daytime: Boolean): Int {
        return if (daytime) dayBrightness else nightBrightness
    }

    /**
     * Resets the inactivity timer. If timeout is 0, auto-dimming is disabled.
     * Otherwise, starts a new timer that will dim the display after the configured
     * number of minutes.
     *
     * Requirement 10.4: Configurable inactivity timeout.
     * Requirement 10.7: Timeout of 0 disables automatic dimming.
     */
    internal fun resetInactivityTimer() {
        timeoutJob?.cancel()

        if (timeoutMinutes <= 0) {
            // Auto-dimming disabled
            return
        }

        timeoutJob = coroutineScope.launch {
            delay(timeoutMinutes * 60L * 1000L)
            // Timeout expired — dim the display
            Log.d(TAG, "Inactivity timeout expired ($timeoutMinutes min), dimming display")
            updateBrightness(dimmed = true)
        }
    }
}

// =========================================================================
// No-op implementations for testing constructor
// =========================================================================

private object NoOpGpsProcessor : GpsProcessor {
    override val gpsState: StateFlow<com.golfcart.gcd.domain.gps.ProcessedGpsData> =
        MutableStateFlow(com.golfcart.gcd.domain.gps.ProcessedGpsData())
    override val navigationData: StateFlow<NavigationData> =
        MutableStateFlow(NavigationData())
    override fun onLocationUpdate(
        latitude: Double, longitude: Double, altitude: Double,
        speedMps: Float, bearing: Float, accuracy: Float,
        satellites: Int, timestamp: Long, hasSpeed: Boolean, hasBearing: Boolean
    ) {}
    override fun onMeshtasticPositionUpdate(position: com.golfcart.gcd.domain.gps.MeshtasticPosition) {}
    override fun setDimmed(dimmed: Boolean) {}
}

private object NoOpDataStoreRepository : DataStoreRepository {
    override fun getPreferences() = kotlinx.coroutines.flow.flowOf(UserPreferences())
    override suspend fun updatePreference(key: com.golfcart.gcd.data.persistence.PreferenceKey, value: Any) {}
    override suspend fun resetAllPreferences() {}
    override fun getCachedWeather() = kotlinx.coroutines.flow.flowOf(com.golfcart.gcd.data.persistence.CachedWeatherData())
    override fun getCachedVenueEvents() = kotlinx.coroutines.flow.flowOf(com.golfcart.gcd.data.persistence.CachedVenueData())
    override suspend fun cacheWeatherData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
    override suspend fun cacheVenueData(rawPacket: String, timestamp: String, dateYYYYMMDD: Int) {}
    override suspend fun persistOdometer(accumDistance: Float, tripDistance: Float) {}
    override suspend fun persistDrivingHours(tenthsOfHours: Int) {}
    override fun getPersistedOdometer() = kotlinx.coroutines.flow.flowOf(com.golfcart.gcd.data.persistence.OdometerData())
    override fun getPersistedDrivingHours() = kotlinx.coroutines.flow.flowOf(0)
}
