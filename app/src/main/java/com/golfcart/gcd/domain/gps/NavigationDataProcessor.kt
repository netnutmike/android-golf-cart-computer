package com.golfcart.gcd.domain.gps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Processes GPS timestamps and location data to produce formatted navigation data
 * including date, time, sunrise/sunset times, and daytime status.
 *
 * Key behaviors:
 * - Formats date as "Day, Mon DD" (e.g., "Mon, Jan 15")
 * - Formats time in 12-hour format with AM/PM (e.g., "2:30 PM")
 * - Handles timezone conversion with automatic DST transitions
 * - Calculates sunrise/sunset times based on GPS location and date
 * - Displays "NO GPS" for date field if no GPS time update for 60 seconds
 * - Emits [NavigationData] via StateFlow
 *
 * Requirements: 5.13, 5.14, 5.15, 5.16, 5.17, 5.18
 */
@Singleton
class NavigationDataProcessor @Inject constructor() {

    private val _navigationData = MutableStateFlow(NavigationData())
    val navigationData: StateFlow<NavigationData> = _navigationData.asStateFlow()

    /** Last time a GPS timestamp update was received (System.currentTimeMillis()). */
    @Volatile
    private var lastGpsUpdateTimeMillis: Long = 0L

    /** Last known latitude for sunrise/sunset calculation. */
    @Volatile
    private var lastLatitude: Double = Double.NaN

    /** Last known longitude for sunrise/sunset calculation. */
    @Volatile
    private var lastLongitude: Double = Double.NaN

    companion object {
        /** Timeout in milliseconds after which "NO GPS" is displayed. */
        const val GPS_TIMEOUT_MS = 60_000L

        /** Date format: "Mon, Jan 15" */
        private val DATE_FORMATTER = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

        /** Time format: "2:30 PM" */
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

        /** Degrees to radians conversion factor. */
        private const val DEG_TO_RAD = Math.PI / 180.0

        /** Radians to degrees conversion factor. */
        private const val RAD_TO_DEG = 180.0 / Math.PI

        /**
         * Solar zenith angle for official sunrise/sunset (90.833 degrees).
         * This accounts for atmospheric refraction and the sun's apparent diameter.
         */
        private const val ZENITH = 90.833
    }

    /**
     * Called when a GPS timestamp update is received.
     *
     * @param gpsTimestampMillis UTC timestamp from GPS in milliseconds
     * @param latitude Current latitude in decimal degrees
     * @param longitude Current longitude in decimal degrees
     * @param zoneId The timezone to use for formatting (defaults to system default)
     * @param currentTimeMillis Current system time for timeout tracking (injectable for testing)
     */
    fun onGpsTimeUpdate(
        gpsTimestampMillis: Long,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId = ZoneId.systemDefault(),
        currentTimeMillis: Long = System.currentTimeMillis()
    ) {
        lastGpsUpdateTimeMillis = currentTimeMillis
        lastLatitude = latitude
        lastLongitude = longitude

        val instant = Instant.ofEpochMilli(gpsTimestampMillis)
        val localDateTime = LocalDateTime.ofInstant(instant, zoneId)
        val localDate = localDateTime.toLocalDate()
        val localTime = localDateTime.toLocalTime()

        val dateString = formatDate(localDateTime)
        val timeString = formatTime(localDateTime)

        // Calculate sunrise/sunset if we have valid coordinates
        val (sunriseTime, sunsetTime, isDaytime) = if (!latitude.isNaN() && !longitude.isNaN()) {
            calculateSunriseSunset(localDate, latitude, longitude, zoneId, localTime)
        } else {
            Triple("--:-- --", "--:-- --", true)
        }

        _navigationData.value = NavigationData(
            dateString = dateString,
            timeString = timeString,
            sunriseTime = sunriseTime,
            sunsetTime = sunsetTime,
            isDaytime = isDaytime
        )
    }

    /**
     * Check if GPS data is stale and update the display accordingly.
     *
     * Should be called periodically (e.g., every second) to detect GPS timeout.
     *
     * @param currentTimeMillis Current system time (injectable for testing)
     */
    fun checkGpsTimeout(currentTimeMillis: Long = System.currentTimeMillis()) {
        if (lastGpsUpdateTimeMillis == 0L) {
            // Never received GPS — already showing "NO GPS"
            return
        }

        val elapsed = currentTimeMillis - lastGpsUpdateTimeMillis
        if (elapsed > GPS_TIMEOUT_MS) {
            val current = _navigationData.value
            if (current.dateString != "NO GPS") {
                _navigationData.value = current.copy(dateString = "NO GPS")
            }
        }
    }

    /**
     * Returns whether GPS data is considered stale (no update for > 60 seconds).
     *
     * @param currentTimeMillis Current system time (injectable for testing)
     */
    fun isGpsStale(currentTimeMillis: Long = System.currentTimeMillis()): Boolean {
        if (lastGpsUpdateTimeMillis == 0L) return true
        return (currentTimeMillis - lastGpsUpdateTimeMillis) > GPS_TIMEOUT_MS
    }

    /**
     * Format a LocalDateTime as "Day, Mon DD" (e.g., "Mon, Jan 15").
     */
    internal fun formatDate(dateTime: LocalDateTime): String {
        return dateTime.format(DATE_FORMATTER)
    }

    /**
     * Format a LocalDateTime as "h:mm a" (e.g., "2:30 PM").
     */
    internal fun formatTime(dateTime: LocalDateTime): String {
        return dateTime.format(TIME_FORMATTER)
    }

    /**
     * Calculate sunrise and sunset times for a given date and location.
     *
     * Uses a simplified solar position algorithm based on the NOAA Solar Calculator.
     * Accuracy is within a few minutes, which is acceptable for a golf cart display.
     *
     * @param date The local date for calculation
     * @param latitude Latitude in decimal degrees (positive = North)
     * @param longitude Longitude in decimal degrees (positive = East)
     * @param zoneId Timezone for output formatting
     * @param currentTime Current local time for isDaytime determination
     * @return Triple of (sunriseString, sunsetString, isDaytime)
     */
    internal fun calculateSunriseSunset(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId,
        currentTime: LocalTime
    ): Triple<String, String, Boolean> {
        val sunriseMinutes = calculateSolarEvent(date, latitude, longitude, zoneId, isSunrise = true)
        val sunsetMinutes = calculateSolarEvent(date, latitude, longitude, zoneId, isSunrise = false)

        if (sunriseMinutes == null || sunsetMinutes == null) {
            // Polar day or polar night — no sunrise/sunset
            // Determine if it's polar day (sun always up) or polar night
            val isPolarDay = isPolarDay(date, latitude)
            return Triple("--:-- --", "--:-- --", isPolarDay)
        }

        val sunriseTime = minutesToLocalTime(sunriseMinutes)
        val sunsetTime = minutesToLocalTime(sunsetMinutes)

        val sunriseString = sunriseTime.format(TIME_FORMATTER)
        val sunsetString = sunsetTime.format(TIME_FORMATTER)

        val isDaytime = !currentTime.isBefore(sunriseTime) && currentTime.isBefore(sunsetTime)

        return Triple(sunriseString, sunsetString, isDaytime)
    }

    /**
     * Calculate the time of a solar event (sunrise or sunset) using the NOAA simplified algorithm.
     *
     * Based on the US Naval Observatory algorithm and NOAA Solar Calculator.
     *
     * @return Minutes from midnight in local time, or null if no sunrise/sunset (polar regions)
     */
    internal fun calculateSolarEvent(
        date: LocalDate,
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId,
        isSunrise: Boolean
    ): Double? {
        // Day of year
        val dayOfYear = date.dayOfYear

        // Timezone offset in hours
        val zoneOffset = zoneId.rules.getOffset(date.atStartOfDay().atZone(zoneId).toInstant())
        val tzOffsetHours = zoneOffset.totalSeconds / 3600.0

        // Approximate time of event
        val lngHour = longitude / 15.0
        val t = if (isSunrise) {
            dayOfYear + ((6.0 - lngHour) / 24.0)
        } else {
            dayOfYear + ((18.0 - lngHour) / 24.0)
        }

        // Sun's mean anomaly
        val meanAnomaly = (0.9856 * t) - 3.289

        // Sun's true longitude
        var sunLongitude = meanAnomaly +
                (1.916 * sin(meanAnomaly * DEG_TO_RAD)) +
                (0.020 * sin(2.0 * meanAnomaly * DEG_TO_RAD)) +
                282.634
        sunLongitude = normalizeDegrees(sunLongitude)

        // Sun's right ascension
        var rightAscension = RAD_TO_DEG * kotlin.math.atan(0.91764 * tan(sunLongitude * DEG_TO_RAD))
        rightAscension = normalizeDegrees(rightAscension)

        // Right ascension must be in same quadrant as sun longitude
        val lQuadrant = (floor(sunLongitude / 90.0) * 90.0)
        val raQuadrant = (floor(rightAscension / 90.0) * 90.0)
        rightAscension += (lQuadrant - raQuadrant)

        // Convert to hours
        rightAscension /= 15.0

        // Sun's declination
        val sinDec = 0.39782 * sin(sunLongitude * DEG_TO_RAD)
        val cosDec = cos(asin(sinDec))

        // Sun's local hour angle
        val cosH = (cos(ZENITH * DEG_TO_RAD) - (sinDec * sin(latitude * DEG_TO_RAD))) /
                (cosDec * cos(latitude * DEG_TO_RAD))

        // Check for no sunrise/sunset (polar regions)
        if (cosH > 1.0) {
            // Sun never rises at this location on this date
            return null
        }
        if (cosH < -1.0) {
            // Sun never sets at this location on this date
            return null
        }

        // Calculate hour angle
        val h = if (isSunrise) {
            360.0 - RAD_TO_DEG * acos(cosH)
        } else {
            RAD_TO_DEG * acos(cosH)
        }
        val hHours = h / 15.0

        // Local mean time of event
        val localMeanTime = hHours + rightAscension - (0.06571 * t) - 6.622

        // Convert to UTC
        var utcTime = localMeanTime - lngHour
        utcTime = normalizeHours(utcTime)

        // Convert to local time
        var localTime = utcTime + tzOffsetHours
        localTime = normalizeHours(localTime)

        // Convert hours to minutes from midnight
        return localTime * 60.0
    }

    /**
     * Determine if a location experiences polar day (midnight sun) on a given date.
     * This is a simplified check based on latitude and solar declination.
     */
    private fun isPolarDay(date: LocalDate, latitude: Double): Boolean {
        val dayOfYear = date.dayOfYear
        // Approximate solar declination
        val declination = 23.45 * sin(((284.0 + dayOfYear) / 365.0) * 360.0 * DEG_TO_RAD)
        // In the northern hemisphere, polar day when latitude > (90 - declination)
        // In the southern hemisphere, polar day when latitude < -(90 - abs(declination))
        return if (latitude >= 0) {
            latitude > (90.0 - declination)
        } else {
            latitude < -(90.0 + declination)
        }
    }

    /**
     * Convert minutes from midnight to a LocalTime.
     */
    private fun minutesToLocalTime(minutes: Double): LocalTime {
        val totalMinutes = minutes.toInt().coerceIn(0, 1439) // 0 to 23:59
        val hour = totalMinutes / 60
        val minute = totalMinutes % 60
        return LocalTime.of(hour, minute)
    }

    /**
     * Normalize degrees to [0, 360) range.
     */
    private fun normalizeDegrees(degrees: Double): Double {
        var result = degrees % 360.0
        if (result < 0) result += 360.0
        return result
    }

    /**
     * Normalize hours to [0, 24) range.
     */
    private fun normalizeHours(hours: Double): Double {
        var result = hours % 24.0
        if (result < 0) result += 24.0
        return result
    }
}
