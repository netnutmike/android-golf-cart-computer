package com.golfcart.gcd;

import net.jqwik.api.Property;
import net.jqwik.api.ForAll;
import net.jqwik.api.constraints.IntRange;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Placeholder jqwik property-based test to verify the PBT framework is configured correctly.
 */
class PlaceholderPropertyTest {

    @Property(tries = 10)
    boolean additionIsCommutative(@ForAll int a, @ForAll int b) {
        return (long) a + b == (long) b + a;
    }

    @Property(tries = 10)
    boolean absoluteValueIsNonNegative(@ForAll @IntRange(min = Integer.MIN_VALUE + 1) int n) {
        return Math.abs(n) >= 0;
    }
}
