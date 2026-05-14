package com.golfcart.gcd.domain.parser

/**
 * A single hour's weather forecast data parsed from a HoT weather packet.
 *
 * @property hourLabel The hour label (e.g., "10am", "2pm"). Maximum 6 characters.
 * @property glyphCode The weather icon/glyph index code.
 * @property temperature The forecast temperature in degrees (-99 to 999).
 * @property precipitation The precipitation probability string. Empty string if original value was "0.0".
 */
data class HourForecast(
    val hourLabel: String,
    val glyphCode: Int,
    val temperature: Int,
    val precipitation: String
)
