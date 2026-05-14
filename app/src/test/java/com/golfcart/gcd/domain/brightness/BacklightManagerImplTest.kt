package com.golfcart.gcd.domain.brightness

import com.golfcart.gcd.data.persistence.*
import com.golfcart.gcd.domain.gps.GpsProcessor
import com.golfcart.gcd.domain.gps.MeshtasticPosition
import com.golfcart.gcd.domain.gps.NavigationData
import com.golfcart.gcd.domain.gps.ProcessedGpsData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BacklightManagerImpl].
 *
 * Tests cover:
 * - Day/night brightness selection based on isDaytime
 * - Configurable brightness levels (0-10 scale)
 * - Inactivity timeout for screen dimming
 * - Activity restoring brightness
 * - Timeout of 0 disabling auto-dim
 *
 * Requirements: 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("BacklightManagerImpl")
class BacklightManagerImplTest {

    // =========================================================================
    // Test helpers
    // =========================================================================

    private fun createManager(
        dayBrightness: Int = 7,
        nightBrightness: Int = 3,
        timeoutMinutes: Int = 5,
        isDaytime: Boolean = true
    ): BacklightManagerImpl {
        return BacklightManagerImpl(
            dayBrightness = dayBrightness,
            nightBrightness = nightBrightness,
            timeoutMinutes = timeoutMinutes,
            isDaytime = isDaytime
        )
    }

    private fun createManagerWithScope(
        testScope: TestScope,
        initialPrefs: UserPreferences = UserPreferences(),
        initialNavData: NavigationData = NavigationData()
    ): Pair<BacklightManagerImpl, FakeGpsProcessor> {
        val fakeGpsProcessor = FakeGpsProcessor(initialNavData)
        val fakeRepo = FakeDataStoreRepository(initialPrefs)
        val manager = BacklightManagerImpl(
            gpsProcessor = fakeGpsProcessor,
            dataStoreRepository = fakeRepo,
            coroutineScope = testScope.backgroundScope
        )
        return Pair(manager, fakeGpsProcessor)
    }

    // =========================================================================
    // Day/Night brightness selection
    // =========================================================================

    @Nested
    @DisplayName("Day/Night brightness selection")
    inner class DayNightSelection {

        @Test
        @DisplayName("Uses day brightness when isDaytime is true")
        fun usesDayBrightnessWhenDaytime() {
            val manager = createManager(dayBrightness = 8, nightBrightness = 2, isDaytime = true)

            val state = manager.brightnessState.value
            assertEquals(8, state.brightnessLevel)
            assertTrue(state.isDaytime)
            assertFalse(state.isDimmed)
        }

        @Test
        @DisplayName("Uses night brightness when isDaytime is false")
        fun usesNightBrightnessWhenNight() {
            val manager = createManager(dayBrightness = 8, nightBrightness = 2, isDaytime = false)

            val state = manager.brightnessState.value
            assertEquals(2, state.brightnessLevel)
            assertFalse(state.isDaytime)
            assertFalse(state.isDimmed)
        }

        @Test
        @DisplayName("Switches to night brightness when isDaytime changes to false")
        fun switchesToNightOnDaytimeChange() {
            val manager = createManager(dayBrightness = 9, nightBrightness = 1, isDaytime = true)

            assertEquals(9, manager.brightnessState.value.brightnessLevel)

            // Simulate daytime changing to night
            manager.onNavigationDataChanged(NavigationData(isDaytime = false))

            val state = manager.brightnessState.value
            assertEquals(1, state.brightnessLevel)
            assertFalse(state.isDaytime)
        }

        @Test
        @DisplayName("Switches to day brightness when isDaytime changes to true")
        fun switchesToDayOnDaytimeChange() {
            val manager = createManager(dayBrightness = 10, nightBrightness = 4, isDaytime = false)

            assertEquals(4, manager.brightnessState.value.brightnessLevel)

            // Simulate night changing to day
            manager.onNavigationDataChanged(NavigationData(isDaytime = true))

            val state = manager.brightnessState.value
            assertEquals(10, state.brightnessLevel)
            assertTrue(state.isDaytime)
        }
    }

    // =========================================================================
    // Configurable brightness levels
    // =========================================================================

    @Nested
    @DisplayName("Configurable brightness levels")
    inner class ConfigurableLevels {

        @Test
        @DisplayName("Brightness level 0 is valid (minimum)")
        fun brightnessLevelZeroIsValid() {
            val manager = createManager(dayBrightness = 0, isDaytime = true)
            assertEquals(0, manager.brightnessState.value.brightnessLevel)
        }

        @Test
        @DisplayName("Brightness level 10 is valid (maximum)")
        fun brightnessLevelTenIsValid() {
            val manager = createManager(dayBrightness = 10, isDaytime = true)
            assertEquals(10, manager.brightnessState.value.brightnessLevel)
        }

        @Test
        @DisplayName("Preferences update changes brightness level")
        fun preferencesUpdateChangesBrightness() {
            val manager = createManager(dayBrightness = 5, nightBrightness = 2, isDaytime = true)

            assertEquals(5, manager.brightnessState.value.brightnessLevel)

            // Simulate preference change
            manager.onPreferencesChanged(UserPreferences(dayBrightness = 9, nightBrightness = 4))

            assertEquals(9, manager.brightnessState.value.brightnessLevel)
        }

        @Test
        @DisplayName("Brightness values are clamped to 0-10 range")
        fun brightnessValuesAreClamped() {
            val manager = createManager(dayBrightness = 7, isDaytime = true)

            // Simulate out-of-range preference
            manager.onPreferencesChanged(UserPreferences(dayBrightness = 15, nightBrightness = -3))

            // Day brightness should be clamped to 10
            assertEquals(10, manager.brightnessState.value.brightnessLevel)

            // Switch to night — should be clamped to 0
            manager.onNavigationDataChanged(NavigationData(isDaytime = false))
            assertEquals(0, manager.brightnessState.value.brightnessLevel)
        }
    }

    // =========================================================================
    // Inactivity timeout
    // =========================================================================

    @Nested
    @DisplayName("Inactivity timeout")
    inner class InactivityTimeout {

        @Test
        @DisplayName("Display dims after inactivity timeout expires")
        fun displayDimsAfterTimeout() = runTest {
            val (manager, _) = createManagerWithScope(
                this,
                initialPrefs = UserPreferences(
                    dayBrightness = 7,
                    nightBrightness = 3,
                    backlightTimeoutMinutes = 2
                )
            )

            // Initially not dimmed
            assertFalse(manager.brightnessState.value.isDimmed)

            // Advance time past the timeout (2 minutes)
            advanceTimeBy(2 * 60 * 1000L + 100L)

            // Should now be dimmed
            assertTrue(manager.brightnessState.value.isDimmed)
            assertEquals(0, manager.brightnessState.value.brightnessLevel)
        }

        @Test
        @DisplayName("Display does not dim before timeout expires")
        fun displayDoesNotDimBeforeTimeout() = runTest {
            val (manager, _) = createManagerWithScope(
                this,
                initialPrefs = UserPreferences(backlightTimeoutMinutes = 5)
            )

            // Advance time to just before timeout (4 minutes 59 seconds)
            advanceTimeBy(4 * 60 * 1000L + 59 * 1000L)

            // Should NOT be dimmed yet
            assertFalse(manager.brightnessState.value.isDimmed)
        }

        @Test
        @DisplayName("Timeout of 0 disables automatic dimming")
        fun timeoutZeroDisablesAutoDim() = runTest {
            val (manager, _) = createManagerWithScope(
                this,
                initialPrefs = UserPreferences(backlightTimeoutMinutes = 0)
            )

            // Advance time significantly
            advanceTimeBy(60 * 60 * 1000L) // 1 hour

            // Should NOT be dimmed
            assertFalse(manager.brightnessState.value.isDimmed)
        }
    }

    // =========================================================================
    // Activity restoring brightness
    // =========================================================================

    @Nested
    @DisplayName("Activity restoring brightness")
    inner class ActivityRestore {

        @Test
        @DisplayName("reportActivity restores brightness when dimmed")
        fun reportActivityRestoresBrightness() = runTest {
            val (manager, _) = createManagerWithScope(
                this,
                initialPrefs = UserPreferences(
                    dayBrightness = 8,
                    backlightTimeoutMinutes = 1
                )
            )

            // Wait for timeout to dim
            advanceTimeBy(1 * 60 * 1000L + 100L)
            assertTrue(manager.brightnessState.value.isDimmed)
            assertEquals(0, manager.brightnessState.value.brightnessLevel)

            // Report activity
            manager.reportActivity()

            // Should be restored
            assertFalse(manager.brightnessState.value.isDimmed)
            assertEquals(8, manager.brightnessState.value.brightnessLevel)
        }

        @Test
        @DisplayName("reportActivity resets inactivity timer")
        fun reportActivityResetsTimer() = runTest {
            val (manager, _) = createManagerWithScope(
                this,
                initialPrefs = UserPreferences(backlightTimeoutMinutes = 2)
            )

            // Advance 1.5 minutes (not yet timed out)
            advanceTimeBy(90 * 1000L)
            assertFalse(manager.brightnessState.value.isDimmed)

            // Report activity — resets the timer
            manager.reportActivity()

            // Advance another 1.5 minutes (total 3 min from start, but only 1.5 from activity)
            advanceTimeBy(90 * 1000L)
            assertFalse(manager.brightnessState.value.isDimmed)

            // Advance past the full timeout from last activity
            advanceTimeBy(31 * 1000L) // Now 2 min + 1s from last activity
            assertTrue(manager.brightnessState.value.isDimmed)
        }

        @Test
        @DisplayName("reportActivity when not dimmed just resets timer")
        fun reportActivityWhenNotDimmedResetsTimer() = runTest {
            val prefs = UserPreferences(
                dayBrightness = 6,
                backlightTimeoutMinutes = 3
            )
            val (manager, _) = createManagerWithScope(
                this,
                initialPrefs = prefs
            )

            // Ensure preferences are applied
            manager.onPreferencesChanged(prefs)

            // Not dimmed initially
            assertFalse(manager.brightnessState.value.isDimmed)
            assertEquals(6, manager.brightnessState.value.brightnessLevel)

            // Report activity
            manager.reportActivity()

            // Still not dimmed, same brightness
            assertFalse(manager.brightnessState.value.isDimmed)
            assertEquals(6, manager.brightnessState.value.brightnessLevel)
        }

        @Test
        @DisplayName("Restores correct brightness level based on current day/night state")
        fun restoresCorrectBrightnessForDayNight() = runTest {
            val prefs = UserPreferences(
                dayBrightness = 9,
                nightBrightness = 2,
                backlightTimeoutMinutes = 1
            )
            val (manager, _) = createManagerWithScope(
                this,
                initialPrefs = prefs,
                initialNavData = NavigationData(isDaytime = true)
            )

            // Ensure preferences and navigation data are applied
            manager.onPreferencesChanged(prefs)
            manager.onNavigationDataChanged(NavigationData(isDaytime = true))

            // Wait for timeout
            advanceTimeBy(1 * 60 * 1000L + 100L)
            assertTrue(manager.brightnessState.value.isDimmed)

            // Change to nighttime while dimmed
            manager.onNavigationDataChanged(NavigationData(isDaytime = false))

            // Report activity — should restore to night brightness
            manager.reportActivity()

            assertFalse(manager.brightnessState.value.isDimmed)
            assertEquals(2, manager.brightnessState.value.brightnessLevel)
            assertFalse(manager.brightnessState.value.isDaytime)
        }
    }

    // =========================================================================
    // selectBrightnessLevel unit tests
    // =========================================================================

    @Nested
    @DisplayName("selectBrightnessLevel")
    inner class SelectBrightnessLevel {

        @Test
        @DisplayName("Returns day brightness for daytime=true")
        fun returnsDayBrightnessForDaytime() {
            val manager = createManager(dayBrightness = 6, nightBrightness = 2, isDaytime = true)
            assertEquals(6, manager.selectBrightnessLevel(true))
        }

        @Test
        @DisplayName("Returns night brightness for daytime=false")
        fun returnsNightBrightnessForNight() {
            val manager = createManager(dayBrightness = 6, nightBrightness = 2, isDaytime = false)
            assertEquals(2, manager.selectBrightnessLevel(false))
        }
    }

    // =========================================================================
    // Fake implementations
    // =========================================================================

    private class FakeGpsProcessor(initialNavData: NavigationData = NavigationData()) : GpsProcessor {
        private val _gpsState = MutableStateFlow(ProcessedGpsData())
        override val gpsState: StateFlow<ProcessedGpsData> = _gpsState

        private val _navigationData = MutableStateFlow(initialNavData)
        override val navigationData: StateFlow<NavigationData> = _navigationData

        fun emitNavigationData(data: NavigationData) {
            _navigationData.value = data
        }

        override fun onLocationUpdate(
            latitude: Double, longitude: Double, altitude: Double,
            speedMps: Float, bearing: Float, accuracy: Float,
            satellites: Int, timestamp: Long, hasSpeed: Boolean, hasBearing: Boolean
        ) {}

        override fun onMeshtasticPositionUpdate(position: MeshtasticPosition) {}
        override fun setDimmed(dimmed: Boolean) {}
    }

    private class FakeDataStoreRepository(
        private val prefs: UserPreferences = UserPreferences()
    ) : DataStoreRepository {
        override fun getPreferences(): Flow<UserPreferences> = flowOf(prefs)
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
}
