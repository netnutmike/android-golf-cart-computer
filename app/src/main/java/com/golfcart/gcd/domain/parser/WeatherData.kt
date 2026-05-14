package com.golfcart.gcd.domain.parser

/**
 * Parsed weather data from a HoT weather packet (type 01).
 *
 * Contains the current temperature and exactly 4 hourly forecasts.
 *
 * @property currentTemp The current temperature in degrees (-99 to 999).
 * @property forecasts Exactly 4 [HourForecast] entries for the upcoming hours.
 * @property receivedTimestamp The timestamp when this data was received (formatted string).
 * @property isStored Whether this data was loaded from cache (true) or received live (false).
 */
data class WeatherData(
    val currentTemp: Int,
    val forecasts: List<HourForecast>,
    val receivedTimestamp: String,
    val isStored: Boolean = false
)
