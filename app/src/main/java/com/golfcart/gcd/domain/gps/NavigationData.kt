package com.golfcart.gcd.domain.gps

/**
 * Navigation data containing formatted date, time, and sunrise/sunset information.
 *
 * This data class is emitted via StateFlow by [NavigationDataProcessor] and consumed
 * by the UI layer for display.
 */
data class NavigationData(
    /** Formatted date string, e.g., "Mon, Jan 15" or "NO GPS" if stale. */
    val dateString: String = "NO GPS",

    /** Formatted time string in 12-hour format, e.g., "2:30 PM". */
    val timeString: String = "--:-- --",

    /** Formatted sunrise time in 12-hour format, e.g., "6:45 AM". */
    val sunriseTime: String = "--:-- --",

    /** Formatted sunset time in 12-hour format, e.g., "7:30 PM". */
    val sunsetTime: String = "--:-- --",

    /** Whether the current time is between sunrise and sunset. */
    val isDaytime: Boolean = true
)
