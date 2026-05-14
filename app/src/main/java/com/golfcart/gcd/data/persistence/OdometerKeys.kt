package com.golfcart.gcd.data.persistence

import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey

/**
 * DataStore preference keys for odometer and driving hours persistence.
 * Used by [DataStoreRepositoryImpl] for odometer/service storage operations.
 */
object OdometerKeys {
    val ACCUM_DISTANCE = floatPreferencesKey("odometer_accum_distance")
    val TRIP_DISTANCE = floatPreferencesKey("odometer_trip_distance")
    val DRIVING_HOURS_TENTHS = intPreferencesKey("driving_hours_tenths")
}
