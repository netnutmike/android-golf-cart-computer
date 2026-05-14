package com.golfcart.gcd.data.persistence

import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Validates whether cached data should be restored based on the stored date vs current date.
 *
 * Cache is considered fresh (valid) if and only if the stored date in YYYYMMDD format
 * matches today's date. Otherwise, the cache is stale and fresh data should be requested.
 *
 * Requirements: 15.5, 19.1, 19.2, 19.3, 19.4, 19.5
 */
@Singleton
open class CacheDateValidator @Inject constructor() {

    /**
     * Determines if cached data with the given stored date is still fresh.
     *
     * @param storedDateYYYYMMDD The date the data was cached, in YYYYMMDD integer format
     *                           (e.g., 20250115 for January 15, 2025)
     * @return [CacheValidity.FRESH] if the stored date matches today's date,
     *         [CacheValidity.STALE] otherwise
     */
    open fun validate(storedDateYYYYMMDD: Int): CacheValidity {
        return validate(storedDateYYYYMMDD, getCurrentDateYYYYMMDD())
    }

    /**
     * Determines if cached data with the given stored date is still fresh,
     * compared against a specified current date.
     *
     * This overload is useful for testing and for cases where the current date
     * is derived from GPS time rather than the system clock.
     *
     * @param storedDateYYYYMMDD The date the data was cached, in YYYYMMDD integer format
     * @param currentDateYYYYMMDD The current date to compare against, in YYYYMMDD integer format
     * @return [CacheValidity.FRESH] if the stored date equals the current date,
     *         [CacheValidity.STALE] otherwise
     */
    fun validate(storedDateYYYYMMDD: Int, currentDateYYYYMMDD: Int): CacheValidity {
        return if (storedDateYYYYMMDD == currentDateYYYYMMDD) {
            CacheValidity.FRESH
        } else {
            CacheValidity.STALE
        }
    }

    /**
     * Gets the current date in YYYYMMDD integer format.
     *
     * @return Today's date as an integer (e.g., 20250115 for January 15, 2025)
     */
    open fun getCurrentDateYYYYMMDD(): Int {
        val today = LocalDate.now()
        return today.year * 10000 + today.monthValue * 100 + today.dayOfMonth
    }
}

/**
 * Represents the validity state of cached data based on date comparison.
 */
enum class CacheValidity {
    /** Cache is from today — data should be restored with "(stored)" indicator. */
    FRESH,
    /** Cache is from a previous day or absent — fresh data should be requested. */
    STALE
}
