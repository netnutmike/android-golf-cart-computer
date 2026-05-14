package com.golfcart.gcd.data.persistence

import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * DataStore preference keys for cached weather and venue/event data.
 * Used by [DataStoreRepositoryImpl] for cache storage operations.
 */
object CacheKeys {
    val WEATHER_RAW_PACKET = stringPreferencesKey("weather_raw_packet")
    val WEATHER_TIMESTAMP = stringPreferencesKey("weather_timestamp")
    val WEATHER_DATE = intPreferencesKey("weather_date_yyyymmdd")

    val VENUE_RAW_PACKET = stringPreferencesKey("venue_raw_packet")
    val VENUE_TIMESTAMP = stringPreferencesKey("venue_timestamp")
    val VENUE_DATE = intPreferencesKey("venue_date_yyyymmdd")
}
