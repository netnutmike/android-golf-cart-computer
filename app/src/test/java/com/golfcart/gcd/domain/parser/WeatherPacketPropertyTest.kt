package com.golfcart.gcd.domain.parser

import net.jqwik.api.*
import net.jqwik.api.Combinators
import net.jqwik.api.constraints.IntRange
import org.junit.jupiter.api.Assertions.*

/**
 * Property-based tests for weather packet parsing in HotPacketParserImpl.
 *
 * Feature: android-golf-cart-computer, Property 6: Weather packet structural validation
 * Feature: android-golf-cart-computer, Property 7: Weather packet parsing correctness
 * Feature: android-golf-cart-computer, Property 8: Precipitation zero-clearing
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.9, 3.10, 3.11, 3.12
 */
@Label("Weather Packet Property Tests")
@Tag("Feature: android-golf-cart-computer")
class WeatherPacketPropertyTest {

    private val parser = HotPacketParserImpl()

    // =========================================================================
    // Property 6: Weather packet structural validation
    // =========================================================================

    /**
     * Property 6a: A correctly formatted weather packet (correct prefix, delimiter
     * counts, temp range, label length) is always accepted by the parser.
     *
     * Validates: Requirements 3.2, 3.9, 3.10, 3.12
     */
    @Property(tries = 10)
    @Label("Property 6: Valid weather packets are always accepted")
    @Tag("Property 6: Weather packet structural validation")
    fun validWeatherPacketsAreAccepted(@ForAll("validWeatherPackets") packet: String) {
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isSuccess,
            "A structurally valid weather packet must be accepted. Packet: $packet")
    }

    /**
     * Property 6b: A packet that does NOT start with "|#01#" is always rejected.
     *
     * Validates: Requirements 3.2, 3.12
     */
    @Property(tries = 10)
    @Label("Property 6: Packets without correct prefix are rejected")
    @Tag("Property 6: Weather packet structural validation")
    fun packetsWithoutCorrectPrefixAreRejected(@ForAll("invalidPrefixPackets") packet: String) {
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isFailure,
            "A packet without |#01# prefix must be rejected. Packet: $packet")
    }

    /**
     * Property 6c: A packet with incorrect '#' delimiter count (not exactly 7) is rejected.
     *
     * Validates: Requirements 3.2, 3.12
     */
    @Property(tries = 10)
    @Label("Property 6: Packets with wrong hash delimiter count are rejected")
    @Tag("Property 6: Weather packet structural validation")
    fun packetsWithWrongHashCountAreRejected(@ForAll("wrongHashCountPackets") packet: String) {
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isFailure,
            "A packet with wrong # count must be rejected. Packet: $packet")
    }

    /**
     * Property 6d: A packet with incorrect ',' delimiter count (not exactly 12) is rejected.
     *
     * Validates: Requirements 3.2, 3.12
     */
    @Property(tries = 10)
    @Label("Property 6: Packets with wrong comma delimiter count are rejected")
    @Tag("Property 6: Weather packet structural validation")
    fun packetsWithWrongCommaCountAreRejected(@ForAll("wrongCommaCountPackets") packet: String) {
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isFailure,
            "A packet with wrong , count must be rejected. Packet: $packet")
    }

    /**
     * Property 6e: A packet with temperature outside -99 to 999 range is rejected.
     *
     * Validates: Requirements 3.9, 3.12
     */
    @Property(tries = 10)
    @Label("Property 6: Packets with out-of-range temperature are rejected")
    @Tag("Property 6: Weather packet structural validation")
    fun packetsWithOutOfRangeTemperatureAreRejected(@ForAll("outOfRangeTempPackets") packet: String) {
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isFailure,
            "A packet with temperature outside [-99, 999] must be rejected. Packet: $packet")
    }

    /**
     * Property 6f: A packet with hour label exceeding 6 characters is rejected.
     *
     * Validates: Requirements 3.10, 3.12
     */
    @Property(tries = 10)
    @Label("Property 6: Packets with hour label exceeding 6 chars are rejected")
    @Tag("Property 6: Weather packet structural validation")
    fun packetsWithLongHourLabelAreRejected(@ForAll("longHourLabelPackets") packet: String) {
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isFailure,
            "A packet with hour label > 6 chars must be rejected. Packet: $packet")
    }

    // =========================================================================
    // Property 7: Weather packet parsing correctness
    // =========================================================================

    /**
     * Property 7a: Valid packets always produce a WeatherData with exactly 4 forecasts.
     *
     * Validates: Requirements 3.1, 3.3
     */
    @Property(tries = 10)
    @Label("Property 7: Valid packets produce WeatherData with 4 forecasts")
    @Tag("Property 7: Weather packet parsing correctness")
    fun validPacketsProduceFourForecasts(@ForAll("validWeatherPackets") packet: String) {
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(4, data.forecasts.size,
            "Valid weather packet must produce exactly 4 forecasts")
    }

    /**
     * Property 7b: The parsed current temperature matches the value encoded in the packet.
     *
     * Validates: Requirements 3.1, 3.3
     */
    @Property(tries = 10)
    @Label("Property 7: Parsed current temperature matches input")
    @Tag("Property 7: Weather packet parsing correctness")
    fun parsedCurrentTempMatchesInput(
        @ForAll("validTemperature") currentTemp: Int,
        @ForAll("fourValidForecasts") forecasts: List<ForecastInput>
    ) {
        val packet = buildWeatherPacket(currentTemp, forecasts)
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isSuccess)
        assertEquals(currentTemp, result.getOrThrow().currentTemp,
            "Parsed current temperature must match the input value")
    }

    /**
     * Property 7c: Each forecast entry's hourLabel, glyphCode, and temperature
     * match the values encoded in the packet.
     *
     * Validates: Requirements 3.1, 3.3
     */
    @Property(tries = 10)
    @Label("Property 7: Parsed forecast fields match input values")
    @Tag("Property 7: Weather packet parsing correctness")
    fun parsedForecastFieldsMatchInput(
        @ForAll("validTemperature") currentTemp: Int,
        @ForAll("fourValidForecasts") forecasts: List<ForecastInput>
    ) {
        val packet = buildWeatherPacket(currentTemp, forecasts)
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow().forecasts

        for (i in 0 until 4) {
            val input = forecasts[i]
            val output = parsed[i]

            assertEquals(input.hourLabel, output.hourLabel,
                "Forecast $i hourLabel must match input")
            assertEquals(input.glyphCode, output.glyphCode,
                "Forecast $i glyphCode must match input")
            assertEquals(input.temperature, output.temperature,
                "Forecast $i temperature must match input")
        }
    }

    /**
     * Property 7d: Each forecast entry has a non-empty hour label.
     *
     * Validates: Requirements 3.1, 3.3
     */
    @Property(tries = 10)
    @Label("Property 7: All forecast hour labels are non-empty")
    @Tag("Property 7: Weather packet parsing correctness")
    fun allForecastHourLabelsAreNonEmpty(@ForAll("validWeatherPackets") packet: String) {
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isSuccess)
        for (forecast in result.getOrThrow().forecasts) {
            assertFalse(forecast.hourLabel.isEmpty(),
                "Forecast hour label must not be empty")
        }
    }

    // =========================================================================
    // Property 8: Precipitation zero-clearing
    // =========================================================================

    /**
     * Property 8a: When precipitation value is "0.0", the parsed precipitation
     * field is an empty string.
     *
     * Validates: Requirements 3.11
     */
    @Property(tries = 10)
    @Label("Property 8: Precipitation '0.0' maps to empty string")
    @Tag("Property 8: Precipitation zero-clearing")
    fun zeroPrecipitationClearedToEmpty(
        @ForAll("validTemperature") currentTemp: Int,
        @ForAll("fourForecastsWithZeroPrecip") forecasts: List<ForecastInput>
    ) {
        val packet = buildWeatherPacket(currentTemp, forecasts)
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isSuccess)
        for (forecast in result.getOrThrow().forecasts) {
            assertEquals("", forecast.precipitation,
                "Precipitation '0.0' must be cleared to empty string")
        }
    }

    /**
     * Property 8b: When precipitation value is NOT "0.0", the original value
     * is preserved in the parsed output.
     *
     * Validates: Requirements 3.11
     */
    @Property(tries = 10)
    @Label("Property 8: Non-zero precipitation values are preserved")
    @Tag("Property 8: Precipitation zero-clearing")
    fun nonZeroPrecipitationPreserved(
        @ForAll("validTemperature") currentTemp: Int,
        @ForAll("fourForecastsWithNonZeroPrecip") forecasts: List<ForecastInput>
    ) {
        val packet = buildWeatherPacket(currentTemp, forecasts)
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow().forecasts

        for (i in 0 until 4) {
            val inputPrecip = forecasts[i].precipitation
            val outputPrecip = parsed[i].precipitation
            assertEquals(inputPrecip, outputPrecip,
                "Non-zero precipitation '$inputPrecip' must be preserved")
        }
    }

    /**
     * Property 8c: For any precipitation value, the result is either empty (if input
     * was "0.0") or the original value (otherwise). No other transformation occurs.
     *
     * Validates: Requirements 3.11
     */
    @Property(tries = 10)
    @Label("Property 8: Precipitation transformation is exactly zero-clearing")
    @Tag("Property 8: Precipitation zero-clearing")
    fun precipitationTransformationIsExactlyZeroClearing(
        @ForAll("validTemperature") currentTemp: Int,
        @ForAll("fourForecastsWithMixedPrecip") forecasts: List<ForecastInput>
    ) {
        val packet = buildWeatherPacket(currentTemp, forecasts)
        val result = parser.parseWeatherPacket(packet)

        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow().forecasts

        for (i in 0 until 4) {
            val inputPrecip = forecasts[i].precipitation
            val outputPrecip = parsed[i].precipitation

            if (inputPrecip == "0.0") {
                assertEquals("", outputPrecip,
                    "Precipitation '0.0' must map to empty string")
            } else {
                assertEquals(inputPrecip, outputPrecip,
                    "Non-zero precipitation must be preserved unchanged")
            }
        }
    }

    // =========================================================================
    // Helper: Build weather packet from components
    // =========================================================================

    private fun buildWeatherPacket(currentTemp: Int, forecasts: List<ForecastInput>): String {
        val sb = StringBuilder()
        sb.append("|#01#")
        sb.append(currentTemp)
        for (f in forecasts) {
            sb.append('#')
            sb.append(f.hourLabel)
            sb.append(',')
            sb.append(f.glyphCode)
            sb.append(',')
            sb.append(f.temperature)
            sb.append(',')
            sb.append(f.precipitation)
        }
        sb.append('#')
        return sb.toString()
    }

    // =========================================================================
    // Data class for forecast input
    // =========================================================================

    data class ForecastInput(
        val hourLabel: String,
        val glyphCode: Int,
        val temperature: Int,
        val precipitation: String
    )

    // =========================================================================
    // Generators
    // =========================================================================

    /**
     * Generates fully valid weather packets that should always be accepted.
     */
    @Provide
    fun validWeatherPackets(): Arbitrary<String> {
        return Combinators.combine(
            validTemperature(),
            validForecastList()
        ).`as` { temp, forecasts -> buildWeatherPacket(temp, forecasts) }
    }

    /**
     * Generates packets that do NOT start with "|#01#".
     */
    @Provide
    fun invalidPrefixPackets(): Arbitrary<String> {
        val badPrefixes = Arbitraries.of(
            "|#02#", "|#03#", "#01#", "01#", "|01#", "X#01#", "",
            "|#1#", "|#001#", "hello", "|#00#"
        )
        return Combinators.combine(
            badPrefixes,
            validTemperature(),
            validForecastList()
        ).`as` { prefix, temp, forecasts ->
            val sb = StringBuilder()
            sb.append(prefix)
            sb.append(temp)
            for (f in forecasts) {
                sb.append('#')
                sb.append(f.hourLabel)
                sb.append(',')
                sb.append(f.glyphCode)
                sb.append(',')
                sb.append(f.temperature)
                sb.append(',')
                sb.append(f.precipitation)
            }
            sb.append('#')
            sb.toString()
        }
    }

    /**
     * Generates packets with wrong '#' delimiter count (too few or too many).
     */
    @Provide
    fun wrongHashCountPackets(): Arbitrary<String> {
        return Combinators.combine(
            validTemperature(),
            validForecastList(),
            Arbitraries.of("tooFew", "tooMany")
        ).`as` { temp, forecasts, mode ->
            if (mode == "tooFew") {
                // Build packet with only 3 forecast segments (6 # instead of 7)
                val sb = StringBuilder()
                sb.append("|#01#")
                sb.append(temp)
                for (i in 0 until 3) {
                    val f = forecasts[i]
                    sb.append('#')
                    sb.append(f.hourLabel)
                    sb.append(',')
                    sb.append(f.glyphCode)
                    sb.append(',')
                    sb.append(f.temperature)
                    sb.append(',')
                    sb.append(f.precipitation)
                }
                sb.append('#')
                sb.toString()
            } else {
                // Build packet with 5 forecast segments (8 # instead of 7)
                val sb = StringBuilder()
                sb.append("|#01#")
                sb.append(temp)
                for (f in forecasts) {
                    sb.append('#')
                    sb.append(f.hourLabel)
                    sb.append(',')
                    sb.append(f.glyphCode)
                    sb.append(',')
                    sb.append(f.temperature)
                    sb.append(',')
                    sb.append(f.precipitation)
                }
                // Add an extra segment
                sb.append('#')
                sb.append(forecasts[0].hourLabel)
                sb.append(',')
                sb.append(forecasts[0].glyphCode)
                sb.append(',')
                sb.append(forecasts[0].temperature)
                sb.append(',')
                sb.append(forecasts[0].precipitation)
                sb.append('#')
                sb.toString()
            }
        }
    }

    /**
     * Generates packets with wrong ',' delimiter count (not exactly 12).
     */
    @Provide
    fun wrongCommaCountPackets(): Arbitrary<String> {
        return Combinators.combine(
            validTemperature(),
            validForecastList()
        ).`as` { temp, forecasts ->
            // Build a packet where first forecast segment has only 3 fields (missing one comma)
            val sb = StringBuilder()
            sb.append("|#01#")
            sb.append(temp)
            // First forecast: only 3 fields (missing precipitation)
            val f0 = forecasts[0]
            sb.append('#')
            sb.append(f0.hourLabel)
            sb.append(',')
            sb.append(f0.glyphCode)
            sb.append(',')
            sb.append(f0.temperature)
            // Remaining forecasts are normal
            for (i in 1 until 4) {
                val f = forecasts[i]
                sb.append('#')
                sb.append(f.hourLabel)
                sb.append(',')
                sb.append(f.glyphCode)
                sb.append(',')
                sb.append(f.temperature)
                sb.append(',')
                sb.append(f.precipitation)
            }
            sb.append('#')
            sb.toString()
        }
    }

    /**
     * Generates packets with temperature values outside the valid range [-99, 999].
     */
    @Provide
    fun outOfRangeTempPackets(): Arbitrary<String> {
        val outOfRangeTemp = Arbitraries.oneOf(
            Arbitraries.integers().between(-9999, -100),
            Arbitraries.integers().between(1000, 9999)
        )
        return Combinators.combine(
            outOfRangeTemp,
            validForecastList()
        ).`as` { temp, forecasts -> buildWeatherPacket(temp, forecasts) }
    }

    /**
     * Generates packets with hour labels exceeding 6 characters.
     */
    @Provide
    fun longHourLabelPackets(): Arbitrary<String> {
        val longLabel = Arbitraries.strings()
            .alpha()
            .ofMinLength(7)
            .ofMaxLength(12)

        return Combinators.combine(
            validTemperature(),
            longLabel,
            validGlyphCode(),
            validTemperature(),
            validPrecipitation(),
            validForecastList()
        ).`as` { temp, badLabel, glyph, fTemp, precip, forecasts ->
            // Replace first forecast's hour label with the long one
            val sb = StringBuilder()
            sb.append("|#01#")
            sb.append(temp)
            // First forecast with long label
            sb.append('#')
            sb.append(badLabel)
            sb.append(',')
            sb.append(glyph)
            sb.append(',')
            sb.append(fTemp)
            sb.append(',')
            sb.append(precip)
            // Remaining 3 forecasts from the valid list
            for (i in 1 until 4) {
                val f = forecasts[i]
                sb.append('#')
                sb.append(f.hourLabel)
                sb.append(',')
                sb.append(f.glyphCode)
                sb.append(',')
                sb.append(f.temperature)
                sb.append(',')
                sb.append(f.precipitation)
            }
            sb.append('#')
            sb.toString()
        }
    }

    /**
     * Generates a list of 4 valid ForecastInput objects with "0.0" precipitation.
     */
    @Provide
    fun fourForecastsWithZeroPrecip(): Arbitrary<List<ForecastInput>> {
        val forecastArb = Combinators.combine(
            validHourLabel(),
            validGlyphCode(),
            validTemperature()
        ).`as` { label, glyph, temp -> ForecastInput(label, glyph, temp, "0.0") }

        return forecastArb.list().ofSize(4)
    }

    /**
     * Generates a list of 4 valid ForecastInput objects with non-"0.0" precipitation.
     */
    @Provide
    fun fourForecastsWithNonZeroPrecip(): Arbitrary<List<ForecastInput>> {
        val forecastArb = Combinators.combine(
            validHourLabel(),
            validGlyphCode(),
            validTemperature(),
            nonZeroPrecipitation()
        ).`as` { label, glyph, temp, precip -> ForecastInput(label, glyph, temp, precip) }

        return forecastArb.list().ofSize(4)
    }

    /**
     * Generates a list of 4 valid ForecastInput objects with mixed precipitation
     * (some "0.0", some non-zero).
     */
    @Provide
    fun fourForecastsWithMixedPrecip(): Arbitrary<List<ForecastInput>> {
        val forecastArb = Combinators.combine(
            validHourLabel(),
            validGlyphCode(),
            validTemperature(),
            validPrecipitation()
        ).`as` { label, glyph, temp, precip -> ForecastInput(label, glyph, temp, precip) }

        return forecastArb.list().ofSize(4)
    }

    /**
     * Generates a list of exactly 4 valid ForecastInput objects.
     */
    @Provide
    fun fourValidForecasts(): Arbitrary<List<ForecastInput>> {
        return validForecastList()
    }

    @Provide
    fun validTemperature(): Arbitrary<Int> {
        return Arbitraries.integers().between(-99, 999)
    }

    // =========================================================================
    // Component generators
    // =========================================================================

    private fun validHourLabel(): Arbitrary<String> {
        // Generate realistic hour labels (1-6 chars, no '#' or ',' characters)
        return Arbitraries.of(
            "1am", "2am", "3am", "4am", "5am", "6am",
            "7am", "8am", "9am", "10am", "11am", "12pm",
            "1pm", "2pm", "3pm", "4pm", "5pm", "6pm",
            "7pm", "8pm", "9pm", "10pm", "11pm", "12am",
            "Now", "1h", "2h", "3h"
        )
    }

    private fun validGlyphCode(): Arbitrary<Int> {
        return Arbitraries.integers().between(0, 50)
    }

    private fun validPrecipitation(): Arbitrary<String> {
        // Mix of "0.0" and non-zero values
        return Arbitraries.of(
            "0.0", "0.1", "0.2", "0.3", "0.5", "0.8",
            "1.0", "1.5", "2.0", "5.0", "10%", "20%",
            "30%", "50%", "80%", "100%"
        )
    }

    private fun nonZeroPrecipitation(): Arbitrary<String> {
        // Only non-"0.0" values — must not contain '#' or ','
        return Arbitraries.of(
            "0.1", "0.2", "0.3", "0.5", "0.8",
            "1.0", "1.5", "2.0", "5.0", "10%", "20%",
            "30%", "50%", "80%", "100%"
        )
    }

    private fun validForecast(): Arbitrary<ForecastInput> {
        return Combinators.combine(
            validHourLabel(),
            validGlyphCode(),
            validTemperature(),
            validPrecipitation()
        ).`as` { label, glyph, temp, precip -> ForecastInput(label, glyph, temp, precip) }
    }

    private fun validForecastList(): Arbitrary<List<ForecastInput>> {
        return validForecast().list().ofSize(4)
    }
}
