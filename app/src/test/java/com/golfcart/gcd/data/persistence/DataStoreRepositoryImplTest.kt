package com.golfcart.gcd.data.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("DataStoreRepositoryImpl")
class DataStoreRepositoryImplTest {

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher + Job())

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: DataStoreRepositoryImpl
    private lateinit var tempFile: File

    @BeforeEach
    fun setup() {
        tempFile = File.createTempFile("test_preferences", ".preferences_pb")
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope
        ) { tempFile }
        repository = DataStoreRepositoryImpl(dataStore, testScope)
    }

    @AfterEach
    fun tearDown() {
        testScope.cancel()
        tempFile.delete()
    }

    @Test
    @DisplayName("getPreferences returns defaults when no preferences are stored")
    fun getPreferencesReturnsDefaults() = testScope.runTest {
        repository.getPreferences().test {
            val prefs = awaitItem()
            assertEquals(UserPreferences.DEFAULT_DAY_BRIGHTNESS, prefs.dayBrightness)
            assertEquals(UserPreferences.DEFAULT_NIGHT_BRIGHTNESS, prefs.nightBrightness)
            assertEquals(UserPreferences.DEFAULT_SPEAKER_VOLUME, prefs.speakerVolume)
            assertEquals(UserPreferences.DEFAULT_FLIP_SCREEN, prefs.flipScreen)
            assertEquals(UserPreferences.DEFAULT_BACKLIGHT_TIMEOUT_MINUTES, prefs.backlightTimeoutMinutes)
            assertEquals(UserPreferences.DEFAULT_TEMPERATURE_OFFSET, prefs.temperatureOffset)
            assertEquals(UserPreferences.DEFAULT_SERVICE_INTERVAL_HOURS, prefs.serviceIntervalHours)
            assertNull(prefs.gciMacAddress)
            assertNull(prefs.homeLatitude)
            assertNull(prefs.homeLongitude)
            assertEquals(UserPreferences.DEFAULT_HOME_FENCE_RADIUS_METERS, prefs.homeFenceRadiusMeters)
            assertTrue(prefs.meshtasticEnabled)
            assertNull(prefs.meshtasticDeviceAddress)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("updatePreference writes non-debounced values immediately")
    fun updatePreferenceWritesImmediately() = testScope.runTest {
        repository.updatePreference(PreferenceKey.FLIP_SCREEN, true)
        advanceUntilIdle()

        repository.getPreferences().test {
            val prefs = awaitItem()
            assertTrue(prefs.flipScreen)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("updatePreference writes non-debounced string values immediately")
    fun updatePreferenceWritesStringImmediately() = testScope.runTest {
        repository.updatePreference(PreferenceKey.GCI_MAC_ADDRESS, "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        repository.getPreferences().test {
            val prefs = awaitItem()
            assertEquals("AA:BB:CC:DD:EE:FF", prefs.gciMacAddress)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("updatePreference debounces slider values by 2 seconds")
    fun updatePreferenceDebouncesSliderValues() = testScope.runTest {
        repository.updatePreference(PreferenceKey.DAY_BRIGHTNESS, 9)

        // Before debounce delay, value should not be written
        advanceTimeBy(1000)
        repository.getPreferences().test {
            val prefs = awaitItem()
            assertEquals(UserPreferences.DEFAULT_DAY_BRIGHTNESS, prefs.dayBrightness)
            cancelAndConsumeRemainingEvents()
        }

        // After debounce delay, value should be written
        advanceTimeBy(1500)
        advanceUntilIdle()
        repository.getPreferences().test {
            val prefs = awaitItem()
            assertEquals(9, prefs.dayBrightness)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("updatePreference debounce replaces pending write with latest value")
    fun updatePreferenceDebounceReplacesValue() = testScope.runTest {
        // Rapid slider changes
        repository.updatePreference(PreferenceKey.SPEAKER_VOLUME, 5)
        advanceTimeBy(500)
        repository.updatePreference(PreferenceKey.SPEAKER_VOLUME, 8)
        advanceTimeBy(500)
        repository.updatePreference(PreferenceKey.SPEAKER_VOLUME, 15)

        // Wait for debounce to complete
        advanceTimeBy(2500)
        advanceUntilIdle()

        repository.getPreferences().test {
            val prefs = awaitItem()
            // Only the last value should be persisted
            assertEquals(15, prefs.speakerVolume)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("updatePreference writes Double values for home coordinates")
    fun updatePreferenceWritesDoubleValues() = testScope.runTest {
        repository.updatePreference(PreferenceKey.HOME_LATITUDE, 28.9108)
        repository.updatePreference(PreferenceKey.HOME_LONGITUDE, -81.9628)
        advanceUntilIdle()

        repository.getPreferences().test {
            val prefs = awaitItem()
            assertEquals(28.9108, prefs.homeLatitude)
            assertEquals(-81.9628, prefs.homeLongitude)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("updatePreference writes meshtasticEnabled boolean")
    fun updatePreferenceWritesMeshtasticEnabled() = testScope.runTest {
        repository.updatePreference(PreferenceKey.MESHTASTIC_ENABLED, false)
        advanceUntilIdle()

        repository.getPreferences().test {
            val prefs = awaitItem()
            assertFalse(prefs.meshtasticEnabled)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("resetAllPreferences clears all stored values and returns defaults")
    fun resetAllPreferencesClearsAll() = testScope.runTest {
        // Set some preferences first
        repository.updatePreference(PreferenceKey.FLIP_SCREEN, true)
        repository.updatePreference(PreferenceKey.MESHTASTIC_ENABLED, false)
        repository.updatePreference(PreferenceKey.GCI_MAC_ADDRESS, "AA:BB:CC:DD:EE:FF")
        advanceUntilIdle()

        // Reset all
        repository.resetAllPreferences()
        advanceUntilIdle()

        // Verify defaults are restored
        repository.getPreferences().test {
            val prefs = awaitItem()
            assertEquals(UserPreferences(), prefs)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("resetAllPreferences cancels pending debounced writes")
    fun resetAllPreferencesCancelsPendingWrites() = testScope.runTest {
        // Start a debounced write
        repository.updatePreference(PreferenceKey.DAY_BRIGHTNESS, 10)
        advanceTimeBy(500)

        // Reset before debounce completes
        repository.resetAllPreferences()
        advanceUntilIdle()

        // The debounced value should NOT have been written
        repository.getPreferences().test {
            val prefs = awaitItem()
            assertEquals(UserPreferences.DEFAULT_DAY_BRIGHTNESS, prefs.dayBrightness)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("updatePreference handles temperature offset as Float")
    fun updatePreferenceHandlesFloat() = testScope.runTest {
        repository.updatePreference(PreferenceKey.TEMPERATURE_OFFSET, -2.5f)

        // Wait for debounce
        advanceTimeBy(2500)
        advanceUntilIdle()

        repository.getPreferences().test {
            val prefs = awaitItem()
            assertEquals(-2.5f, prefs.temperatureOffset)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("persistOdometer stores and retrieves distance values")
    fun persistOdometerStoresValues() = testScope.runTest {
        repository.persistOdometer(123.4f, 5.6f)
        advanceUntilIdle()

        repository.getPersistedOdometer().test {
            val data = awaitItem()
            assertEquals(123.4f, data.accumDistance)
            assertEquals(5.6f, data.tripDistance)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("persistDrivingHours stores tenths of hours")
    fun persistDrivingHoursStoresValue() = testScope.runTest {
        repository.persistDrivingHours(450)
        advanceUntilIdle()

        repository.getPersistedDrivingHours().test {
            val hours = awaitItem()
            assertEquals(450, hours)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("getPersistedOdometer returns defaults when no data is stored")
    fun getPersistedOdometerReturnsDefaults() = testScope.runTest {
        repository.getPersistedOdometer().test {
            val data = awaitItem()
            assertEquals(0.0f, data.accumDistance)
            assertEquals(0.0f, data.tripDistance)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("getPersistedDrivingHours returns zero when no data is stored")
    fun getPersistedDrivingHoursReturnsZero() = testScope.runTest {
        repository.getPersistedDrivingHours().test {
            val hours = awaitItem()
            assertEquals(0, hours)
            cancelAndConsumeRemainingEvents()
        }
    }

    @Test
    @DisplayName("getPersistedOdometer emits updated values after persist")
    fun getPersistedOdometerEmitsUpdates() = testScope.runTest {
        repository.getPersistedOdometer().test {
            // Initial defaults
            val initial = awaitItem()
            assertEquals(0.0f, initial.accumDistance)
            assertEquals(0.0f, initial.tripDistance)

            // Persist new values
            repository.persistOdometer(50.5f, 12.3f)
            advanceUntilIdle()

            val updated = awaitItem()
            assertEquals(50.5f, updated.accumDistance)
            assertEquals(12.3f, updated.tripDistance)

            cancelAndConsumeRemainingEvents()
        }
    }
}
