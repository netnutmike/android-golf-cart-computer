package com.golfcart.gcd.data.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CacheDateValidator")
class CacheDateValidatorTest {

    private val validator = CacheDateValidator()

    @Nested
    @DisplayName("validate(storedDate, currentDate)")
    inner class ValidateWithExplicitDate {

        @Test
        @DisplayName("returns FRESH when stored date matches current date")
        fun freshWhenDatesMatch() {
            val result = validator.validate(20250115, 20250115)
            assertEquals(CacheValidity.FRESH, result)
        }

        @Test
        @DisplayName("returns STALE when stored date is from previous day")
        fun staleWhenPreviousDay() {
            val result = validator.validate(20250114, 20250115)
            assertEquals(CacheValidity.STALE, result)
        }

        @Test
        @DisplayName("returns STALE when stored date is from next day (future)")
        fun staleWhenFutureDay() {
            val result = validator.validate(20250116, 20250115)
            assertEquals(CacheValidity.STALE, result)
        }

        @Test
        @DisplayName("returns STALE when stored date is from different month")
        fun staleWhenDifferentMonth() {
            val result = validator.validate(20250215, 20250115)
            assertEquals(CacheValidity.STALE, result)
        }

        @Test
        @DisplayName("returns STALE when stored date is from different year")
        fun staleWhenDifferentYear() {
            val result = validator.validate(20240115, 20250115)
            assertEquals(CacheValidity.STALE, result)
        }

        @Test
        @DisplayName("returns STALE when stored date is zero (no cached data)")
        fun staleWhenStoredDateIsZero() {
            val result = validator.validate(0, 20250115)
            assertEquals(CacheValidity.STALE, result)
        }

        @Test
        @DisplayName("returns FRESH for year boundary date match")
        fun freshAtYearBoundary() {
            val result = validator.validate(20251231, 20251231)
            assertEquals(CacheValidity.FRESH, result)
        }

        @Test
        @DisplayName("returns STALE across year boundary")
        fun staleAcrossYearBoundary() {
            val result = validator.validate(20241231, 20250101)
            assertEquals(CacheValidity.STALE, result)
        }

        @Test
        @DisplayName("returns FRESH for leap day")
        fun freshOnLeapDay() {
            val result = validator.validate(20240229, 20240229)
            assertEquals(CacheValidity.FRESH, result)
        }

        @Test
        @DisplayName("returns STALE when stored date is day before leap day")
        fun staleBeforeLeapDay() {
            val result = validator.validate(20240228, 20240229)
            assertEquals(CacheValidity.STALE, result)
        }
    }

    @Nested
    @DisplayName("validate(storedDate) - uses system clock")
    inner class ValidateWithSystemClock {

        @Test
        @DisplayName("returns FRESH when stored date matches today")
        fun freshWhenMatchesToday() {
            val today = validator.getCurrentDateYYYYMMDD()
            val result = validator.validate(today)
            assertEquals(CacheValidity.FRESH, result)
        }

        @Test
        @DisplayName("returns STALE when stored date is zero")
        fun staleWhenZero() {
            val result = validator.validate(0)
            assertEquals(CacheValidity.STALE, result)
        }

        @Test
        @DisplayName("returns STALE when stored date is a fixed past date")
        fun staleWhenPastDate() {
            // 2020-01-01 is always in the past
            val result = validator.validate(20200101)
            assertEquals(CacheValidity.STALE, result)
        }
    }

    @Nested
    @DisplayName("getCurrentDateYYYYMMDD")
    inner class GetCurrentDate {

        @Test
        @DisplayName("returns a valid 8-digit date in YYYYMMDD format")
        fun returnsValidFormat() {
            val date = validator.getCurrentDateYYYYMMDD()
            // Must be 8 digits: YYYYMMDD
            assert(date in 10000101..99991231) {
                "Date $date is not in valid YYYYMMDD range"
            }
        }

        @Test
        @DisplayName("year component is reasonable (2020-2100)")
        fun yearIsReasonable() {
            val date = validator.getCurrentDateYYYYMMDD()
            val year = date / 10000
            assert(year in 2020..2100) {
                "Year $year is not in reasonable range"
            }
        }

        @Test
        @DisplayName("month component is valid (01-12)")
        fun monthIsValid() {
            val date = validator.getCurrentDateYYYYMMDD()
            val month = (date % 10000) / 100
            assert(month in 1..12) {
                "Month $month is not valid"
            }
        }

        @Test
        @DisplayName("day component is valid (01-31)")
        fun dayIsValid() {
            val date = validator.getCurrentDateYYYYMMDD()
            val day = date % 100
            assert(day in 1..31) {
                "Day $day is not valid"
            }
        }
    }
}
