package com.golfcart.gcd.data.persistence

/**
 * Data class representing all user-configurable preferences.
 *
 * Requirements: 15.1 - Persist day brightness, night brightness, speaker volume,
 * screen flip, backlight timeout, temperature offset, service interval hours,
 * GCI MAC address, home location coordinates, home geofence radius.
 */
data class UserPreferences(
    val dayBrightness: Int = DEFAULT_DAY_BRIGHTNESS,
    val nightBrightness: Int = DEFAULT_NIGHT_BRIGHTNESS,
    val speakerVolume: Int = DEFAULT_SPEAKER_VOLUME,
    val flipScreen: Boolean = DEFAULT_FLIP_SCREEN,
    val backlightTimeoutMinutes: Int = DEFAULT_BACKLIGHT_TIMEOUT_MINUTES,
    val temperatureOffset: Float = DEFAULT_TEMPERATURE_OFFSET,
    val serviceIntervalHours: Int = DEFAULT_SERVICE_INTERVAL_HOURS,
    val gciMacAddress: String? = DEFAULT_GCI_MAC_ADDRESS,
    val homeLatitude: Double? = DEFAULT_HOME_LATITUDE,
    val homeLongitude: Double? = DEFAULT_HOME_LONGITUDE,
    val homeFenceRadiusMeters: Int = DEFAULT_HOME_FENCE_RADIUS_METERS,
    val meshtasticEnabled: Boolean = DEFAULT_MESHTASTIC_ENABLED,
    val meshtasticDeviceAddress: String? = DEFAULT_MESHTASTIC_DEVICE_ADDRESS
) {
    companion object {
        const val DEFAULT_DAY_BRIGHTNESS = 7           // 0-10
        const val DEFAULT_NIGHT_BRIGHTNESS = 3         // 0-10
        const val DEFAULT_SPEAKER_VOLUME = 10          // 0-20
        const val DEFAULT_FLIP_SCREEN = false
        const val DEFAULT_BACKLIGHT_TIMEOUT_MINUTES = 5
        const val DEFAULT_TEMPERATURE_OFFSET = 0.0f
        const val DEFAULT_SERVICE_INTERVAL_HOURS = 100
        val DEFAULT_GCI_MAC_ADDRESS: String? = null
        val DEFAULT_HOME_LATITUDE: Double? = null
        val DEFAULT_HOME_LONGITUDE: Double? = null
        const val DEFAULT_HOME_FENCE_RADIUS_METERS = 500
        const val DEFAULT_MESHTASTIC_ENABLED = true
        val DEFAULT_MESHTASTIC_DEVICE_ADDRESS: String? = null
    }
}
