package com.golfcart.gcd.data.persistence

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Defines all DataStore preference keys used by the application.
 *
 * Each key corresponds to a field in [UserPreferences] and is used
 * for type-safe read/write operations with Jetpack DataStore Preferences.
 */
object PreferenceKeys {
    val DAY_BRIGHTNESS = intPreferencesKey("day_brightness")
    val NIGHT_BRIGHTNESS = intPreferencesKey("night_brightness")
    val SPEAKER_VOLUME = intPreferencesKey("speaker_volume")
    val FLIP_SCREEN = booleanPreferencesKey("flip_screen")
    val BACKLIGHT_TIMEOUT_MINUTES = intPreferencesKey("backlight_timeout_minutes")
    val TEMPERATURE_OFFSET = floatPreferencesKey("temperature_offset")
    val SERVICE_INTERVAL_HOURS = intPreferencesKey("service_interval_hours")
    val GCI_MAC_ADDRESS = stringPreferencesKey("gci_mac_address")
    val HOME_LATITUDE = doublePreferencesKey("home_latitude")
    val HOME_LONGITUDE = doublePreferencesKey("home_longitude")
    val HOME_FENCE_RADIUS_METERS = intPreferencesKey("home_fence_radius_meters")
    val MESHTASTIC_ENABLED = booleanPreferencesKey("meshtastic_enabled")
    val MESHTASTIC_DEVICE_ADDRESS = stringPreferencesKey("meshtastic_device_address")
}

/**
 * Enum representing all preference keys that can be updated individually.
 * Used by [DataStoreRepository.updatePreference] to identify which preference to write.
 */
enum class PreferenceKey {
    DAY_BRIGHTNESS,
    NIGHT_BRIGHTNESS,
    SPEAKER_VOLUME,
    FLIP_SCREEN,
    BACKLIGHT_TIMEOUT_MINUTES,
    TEMPERATURE_OFFSET,
    SERVICE_INTERVAL_HOURS,
    GCI_MAC_ADDRESS,
    HOME_LATITUDE,
    HOME_LONGITUDE,
    HOME_FENCE_RADIUS_METERS,
    MESHTASTIC_ENABLED,
    MESHTASTIC_DEVICE_ADDRESS
}
