package com.golfcart.gcd.data.persistence;

import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Property-based test for cache date validation logic.
 *
 * Feature: android-golf-cart-computer, Property 10: Cache date validation
 *
 * Validates: Requirements 3.7, 3.8, 4.7, 19.3, 19.4, 19.5
 */
@Label("Property 10: Cache date validation")
@Tag("Feature: android-golf-cart-computer")
@Tag("Property 10: Cache date validation")
class CacheDateValidationPropertyTest {

    private final CacheDateValidator validator = new CacheDateValidator();

    /**
     * Property: Cache is FRESH if and only if storedDate == currentDate.
     *
     * For any two valid YYYYMMDD dates, the cache validity should be FRESH
     * when and only when the stored date equals the current date.
     *
     * Validates: Requirements 3.7, 3.8, 4.7, 19.3, 19.4, 19.5
     */
    @Property(tries = 10)
    @Label("Cache is FRESH iff storedDate equals currentDate")
    void cacheIsFreshIffDatesMatch(
            @ForAll("validDateYYYYMMDD") int storedDate,
            @ForAll("validDateYYYYMMDD") int currentDate) {

        CacheValidity result = validator.validate(storedDate, currentDate);

        if (storedDate == currentDate) {
            assertEquals(CacheValidity.FRESH, result,
                    "Cache should be FRESH when storedDate (" + storedDate +
                    ") equals currentDate (" + currentDate + ")");
        } else {
            assertEquals(CacheValidity.STALE, result,
                    "Cache should be STALE when storedDate (" + storedDate +
                    ") differs from currentDate (" + currentDate + ")");
        }
    }

    /**
     * Property: Matching dates always produce FRESH.
     *
     * For any valid YYYYMMDD date, validating it against itself must return FRESH.
     *
     * Validates: Requirements 3.7, 19.3, 19.4
     */
    @Property(tries = 10)
    @Label("Same date always produces FRESH")
    void sameDateAlwaysProducesFresh(@ForAll("validDateYYYYMMDD") int date) {
        CacheValidity result = validator.validate(date, date);
        assertEquals(CacheValidity.FRESH, result,
                "Cache should always be FRESH when stored date equals current date: " + date);
    }

    /**
     * Property: Different dates always produce STALE.
     *
     * For any two distinct valid YYYYMMDD dates, the result must be STALE.
     *
     * Validates: Requirements 3.8, 4.7, 19.5
     */
    @Property(tries = 10)
    @Label("Different dates always produce STALE")
    void differentDatesAlwaysProduceStale(
            @ForAll("validDateYYYYMMDD") int storedDate,
            @ForAll("validDateYYYYMMDD") int currentDate) {

        Assume.that(storedDate != currentDate);

        CacheValidity result = validator.validate(storedDate, currentDate);
        assertEquals(CacheValidity.STALE, result,
                "Cache should be STALE when storedDate (" + storedDate +
                ") differs from currentDate (" + currentDate + ")");
    }

    /**
     * Provides valid YYYYMMDD integer dates.
     * Years: 2020-2030, Months: 1-12, Days: 1-28 (avoids month-length edge cases).
     */
    @Provide
    Arbitrary<Integer> validDateYYYYMMDD() {
        Arbitrary<Integer> years = Arbitraries.integers().between(2020, 2030);
        Arbitrary<Integer> months = Arbitraries.integers().between(1, 12);
        Arbitrary<Integer> days = Arbitraries.integers().between(1, 28);

        return Combinators.combine(years, months, days)
                .as((year, month, day) -> year * 10000 + month * 100 + day);
    }
}
