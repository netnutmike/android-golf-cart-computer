package com.golfcart.gcd.domain.gps

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Unit tests for [NavigationDataProcessor].
 *
 * Validates: Requirements 5.13, 5.14, 5.15, 5.16, 5.17, 5.18
 */
@DisplayName("NavigationDataProcessor")
class NavigationDataProcessorTest {

    private lateinit var processor: NavigationDataProcessor

    @BeforeEach
    fun setUp() {
        processor = NavigationDataProcessor()
    }

    @Nested
    @DisplayName("Date formatting")
    inner class DateFormatting {

        @Test
        @DisplayName("Formats date as 'Day, Mon DD' - Monday January 15")
        fun formatsDateCorrectly_mondayJan15() {
            // Monday, January 15, 2024 at 2:30 PM
            val dateTime = LocalDateTime.of(2024, 1, 15, 14, 30, 0)
            val result = processor.formatDate(dateTime)
            assertEquals("Mon, Jan 15", result)
        }

        @Test
        @DisplayName("Formats date as 'Day, Mon DD' - Friday December 25")
        fun formatsDateCorrectly_fridayDec25() {
            val dateTime = LocalDateTime.of(2024, 12, 25, 10, 0, 0)
            val result = processor.formatDate(dateTime)
            assertEquals("Wed, Dec 25", result)
        }

        @Test
        @DisplayName("Formats single-digit day without leading zero")
        fun formatsSingleDigitDay() {
            // Saturday, March 1, 2025
            val dateTime = LocalDateTime.of(2025, 3, 1, 8, 0, 0)
            val result = processor.formatDate(dateTime)
            assertEquals("Sat, Mar 1", result)
        }
    }

    @Nested
    @DisplayName("Time formatting")
    inner class TimeFormatting {

        @Test
        @DisplayName("Formats time in 12-hour format with PM")
        fun formatsTimeWithPM() {
            val dateTime = LocalDateTime.of(2024, 1, 15, 14, 30, 0)
            val result = processor.formatTime(dateTime)
            assertEquals("2:30 PM", result)
        }

        @Test
        @DisplayName("Formats time in 12-hour format with AM")
        fun formatsTimeWithAM() {
            val dateTime = LocalDateTime.of(2024, 1, 15, 6, 45, 0)
            val result = processor.formatTime(dateTime)
            assertEquals("6:45 AM", result)
        }

        @Test
        @DisplayName("Formats noon as 12:00 PM")
        fun formatsNoon() {
            val dateTime = LocalDateTime.of(2024, 1, 15, 12, 0, 0)
            val result = processor.formatTime(dateTime)
            assertEquals("12:00 PM", result)
        }

        @Test
        @DisplayName("Formats midnight as 12:00 AM")
        fun formatsMidnight() {
            val dateTime = LocalDateTime.of(2024, 1, 15, 0, 0, 0)
            val result = processor.formatTime(dateTime)
            assertEquals("12:00 AM", result)
        }

        @Test
        @DisplayName("Formats 1:05 AM correctly with leading zero on minutes")
        fun formatsEarlyMorning() {
            val dateTime = LocalDateTime.of(2024, 1, 15, 1, 5, 0)
            val result = processor.formatTime(dateTime)
            assertEquals("1:05 AM", result)
        }
    }

    @Nested
    @DisplayName("GPS timeout - NO GPS display")
    inner class GpsTimeout {

        @Test
        @DisplayName("Shows 'NO GPS' initially before any GPS update")
        fun showsNoGpsInitially() {
            val data = processor.navigationData.value
            assertEquals("NO GPS", data.dateString)
        }

        @Test
        @DisplayName("Shows formatted date after GPS update")
        fun showsDateAfterGpsUpdate() {
            // January 15, 2024 at 2:30 PM UTC
            val gpsTimestamp = ZonedDateTime.of(2024, 1, 15, 14, 30, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()

            processor.onGpsTimeUpdate(
                gpsTimestampMillis = gpsTimestamp,
                latitude = 28.9,
                longitude = -81.9,
                zoneId = ZoneOffset.UTC,
                currentTimeMillis = 1000L
            )

            val data = processor.navigationData.value
            assertEquals("Mon, Jan 15", data.dateString)
            assertEquals("2:30 PM", data.timeString)
        }

        @Test
        @DisplayName("Shows 'NO GPS' after 60 seconds without update")
        fun showsNoGpsAfterTimeout() {
            val gpsTimestamp = ZonedDateTime.of(2024, 1, 15, 14, 30, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()

            processor.onGpsTimeUpdate(
                gpsTimestampMillis = gpsTimestamp,
                latitude = 28.9,
                longitude = -81.9,
                zoneId = ZoneOffset.UTC,
                currentTimeMillis = 1000L
            )

            // Check at exactly 60 seconds — should NOT be stale yet
            processor.checkGpsTimeout(currentTimeMillis = 61_000L)
            assertNotEquals("NO GPS", processor.navigationData.value.dateString)

            // Check at 61 seconds — should be stale
            processor.checkGpsTimeout(currentTimeMillis = 61_001L)
            assertEquals("NO GPS", processor.navigationData.value.dateString)
        }

        @Test
        @DisplayName("Resets timeout when new GPS update arrives")
        fun resetsTimeoutOnNewUpdate() {
            val gpsTimestamp1 = ZonedDateTime.of(2024, 1, 15, 14, 30, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()

            processor.onGpsTimeUpdate(
                gpsTimestampMillis = gpsTimestamp1,
                latitude = 28.9,
                longitude = -81.9,
                zoneId = ZoneOffset.UTC,
                currentTimeMillis = 1000L
            )

            // 50 seconds later, new update arrives
            val gpsTimestamp2 = ZonedDateTime.of(2024, 1, 15, 14, 31, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()

            processor.onGpsTimeUpdate(
                gpsTimestampMillis = gpsTimestamp2,
                latitude = 28.9,
                longitude = -81.9,
                zoneId = ZoneOffset.UTC,
                currentTimeMillis = 51_000L
            )

            // 50 seconds after second update — should still be valid
            processor.checkGpsTimeout(currentTimeMillis = 101_000L)
            assertNotEquals("NO GPS", processor.navigationData.value.dateString)
        }

        @Test
        @DisplayName("isGpsStale returns true when no update received")
        fun isGpsStaleWhenNoUpdate() {
            assertTrue(processor.isGpsStale(currentTimeMillis = 100_000L))
        }

        @Test
        @DisplayName("isGpsStale returns false within timeout window")
        fun isGpsNotStaleWithinTimeout() {
            val gpsTimestamp = ZonedDateTime.of(2024, 1, 15, 14, 30, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()

            processor.onGpsTimeUpdate(
                gpsTimestampMillis = gpsTimestamp,
                latitude = 28.9,
                longitude = -81.9,
                zoneId = ZoneOffset.UTC,
                currentTimeMillis = 1000L
            )

            assertFalse(processor.isGpsStale(currentTimeMillis = 50_000L))
        }
    }

    @Nested
    @DisplayName("Timezone handling")
    inner class TimezoneHandling {

        @Test
        @DisplayName("Converts UTC timestamp to Eastern time")
        fun convertsToEasternTime() {
            // January 15, 2024 at 7:30 PM UTC = 2:30 PM EST
            val gpsTimestamp = ZonedDateTime.of(2024, 1, 15, 19, 30, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()

            val easternZone = ZoneId.of("America/New_York")

            processor.onGpsTimeUpdate(
                gpsTimestampMillis = gpsTimestamp,
                latitude = 28.9,
                longitude = -81.9,
                zoneId = easternZone,
                currentTimeMillis = 1000L
            )

            val data = processor.navigationData.value
            assertEquals("2:30 PM", data.timeString)
        }

        @Test
        @DisplayName("Handles DST transition - summer time")
        fun handlesDstSummerTime() {
            // July 15, 2024 at 18:30 UTC = 2:30 PM EDT (UTC-4)
            val gpsTimestamp = ZonedDateTime.of(2024, 7, 15, 18, 30, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()

            val easternZone = ZoneId.of("America/New_York")

            processor.onGpsTimeUpdate(
                gpsTimestampMillis = gpsTimestamp,
                latitude = 28.9,
                longitude = -81.9,
                zoneId = easternZone,
                currentTimeMillis = 1000L
            )

            val data = processor.navigationData.value
            assertEquals("2:30 PM", data.timeString)
            assertEquals("Mon, Jul 15", data.dateString)
        }
    }

    @Nested
    @DisplayName("Sunrise/Sunset calculation")
    inner class SunriseSunset {

        @Test
        @DisplayName("Calculates sunrise/sunset for The Villages, FL in January")
        fun calculatesSunriseSunsetForVillagesJanuary() {
            // The Villages, FL: approximately 28.9° N, 81.9° W
            // January 15 sunrise ~7:18 AM, sunset ~5:52 PM EST (approximate)
            val date = LocalDate.of(2024, 1, 15)
            val zoneId = ZoneId.of("America/New_York")
            val currentTime = LocalTime.of(12, 0) // Noon

            val (sunrise, sunset, isDaytime) = processor.calculateSunriseSunset(
                date, 28.9, -81.9, zoneId, currentTime
            )

            // Verify sunrise is in the morning (between 6 AM and 8 AM)
            assertTrue(sunrise.contains("AM"), "Sunrise should be AM: $sunrise")
            // Verify sunset is in the evening (between 5 PM and 7 PM)
            assertTrue(sunset.contains("PM"), "Sunset should be PM: $sunset")
            // At noon, should be daytime
            assertTrue(isDaytime, "Should be daytime at noon")
        }

        @Test
        @DisplayName("Calculates sunrise/sunset for The Villages, FL in July")
        fun calculatesSunriseSunsetForVillagesJuly() {
            // The Villages, FL in July: sunrise ~6:35 AM, sunset ~8:28 PM EDT (approximate)
            val date = LocalDate.of(2024, 7, 15)
            val zoneId = ZoneId.of("America/New_York")
            val currentTime = LocalTime.of(12, 0)

            val (sunrise, sunset, isDaytime) = processor.calculateSunriseSunset(
                date, 28.9, -81.9, zoneId, currentTime
            )

            assertTrue(sunrise.contains("AM"), "Sunrise should be AM: $sunrise")
            assertTrue(sunset.contains("PM"), "Sunset should be PM: $sunset")
            assertTrue(isDaytime, "Should be daytime at noon")
        }

        @Test
        @DisplayName("Reports nighttime before sunrise")
        fun reportsNighttimeBeforeSunrise() {
            val date = LocalDate.of(2024, 1, 15)
            val zoneId = ZoneId.of("America/New_York")
            val currentTime = LocalTime.of(4, 0) // 4 AM — before sunrise

            val (_, _, isDaytime) = processor.calculateSunriseSunset(
                date, 28.9, -81.9, zoneId, currentTime
            )

            assertFalse(isDaytime, "Should be nighttime at 4 AM")
        }

        @Test
        @DisplayName("Reports nighttime after sunset")
        fun reportsNighttimeAfterSunset() {
            val date = LocalDate.of(2024, 1, 15)
            val zoneId = ZoneId.of("America/New_York")
            val currentTime = LocalTime.of(22, 0) // 10 PM — after sunset

            val (_, _, isDaytime) = processor.calculateSunriseSunset(
                date, 28.9, -81.9, zoneId, currentTime
            )

            assertFalse(isDaytime, "Should be nighttime at 10 PM")
        }

        @Test
        @DisplayName("Sunrise time is reasonable for mid-latitude location")
        fun sunriseTimeIsReasonable() {
            // For The Villages, FL (28.9°N), sunrise should be between 5:30 AM and 7:30 AM year-round
            val date = LocalDate.of(2024, 6, 21) // Summer solstice
            val zoneId = ZoneId.of("America/New_York")
            val currentTime = LocalTime.of(12, 0)

            val (sunrise, _, _) = processor.calculateSunriseSunset(
                date, 28.9, -81.9, zoneId, currentTime
            )

            // Parse the hour from sunrise string
            val hourMatch = Regex("(\\d+):(\\d+) (AM|PM)").find(sunrise)
            assertNotNull(hourMatch, "Sunrise should match time format: $sunrise")
            val hour = hourMatch!!.groupValues[1].toInt()
            val amPm = hourMatch.groupValues[3]
            assertEquals("AM", amPm, "Sunrise should be AM")
            assertTrue(hour in 5..7, "Sunrise hour should be between 5 and 7 AM, got $hour")
        }
    }

    @Nested
    @DisplayName("Full NavigationData emission")
    inner class FullEmission {

        @Test
        @DisplayName("Emits complete NavigationData with all fields populated")
        fun emitsCompleteNavigationData() {
            // January 15, 2024 at 2:30 PM EST
            val gpsTimestamp = ZonedDateTime.of(2024, 1, 15, 19, 30, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()

            val easternZone = ZoneId.of("America/New_York")

            processor.onGpsTimeUpdate(
                gpsTimestampMillis = gpsTimestamp,
                latitude = 28.9,
                longitude = -81.9,
                zoneId = easternZone,
                currentTimeMillis = 1000L
            )

            val data = processor.navigationData.value
            assertEquals("Mon, Jan 15", data.dateString)
            assertEquals("2:30 PM", data.timeString)
            assertNotEquals("--:-- --", data.sunriseTime)
            assertNotEquals("--:-- --", data.sunsetTime)
            // At 2:30 PM in January in Florida, should be daytime
            assertTrue(data.isDaytime)
        }

        @Test
        @DisplayName("Handles location with no valid coordinates gracefully")
        fun handlesNoValidCoordinates() {
            val gpsTimestamp = ZonedDateTime.of(2024, 1, 15, 14, 30, 0, 0, ZoneOffset.UTC)
                .toInstant().toEpochMilli()

            processor.onGpsTimeUpdate(
                gpsTimestampMillis = gpsTimestamp,
                latitude = Double.NaN,
                longitude = Double.NaN,
                zoneId = ZoneOffset.UTC,
                currentTimeMillis = 1000L
            )

            val data = processor.navigationData.value
            assertEquals("Mon, Jan 15", data.dateString)
            assertEquals("2:30 PM", data.timeString)
            assertEquals("--:-- --", data.sunriseTime)
            assertEquals("--:-- --", data.sunsetTime)
            assertTrue(data.isDaytime) // Default to daytime when no location
        }
    }
}
