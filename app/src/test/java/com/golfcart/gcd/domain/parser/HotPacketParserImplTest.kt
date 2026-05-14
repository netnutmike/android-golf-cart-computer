package com.golfcart.gcd.domain.parser

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Unit tests for [HotPacketParserImpl] weather packet parsing.
 *
 * Validates: Requirements 3.1, 3.2, 3.3, 3.9, 3.10, 3.11, 3.12
 */
@DisplayName("HotPacketParserImpl")
class HotPacketParserImplTest {

    private lateinit var parser: HotPacketParserImpl

    @BeforeEach
    fun setUp() {
        parser = HotPacketParserImpl()
    }

    @Nested
    @DisplayName("isHotPacket")
    inner class IsHotPacketTests {

        @Test
        fun `returns true for text starting with pipe character`() {
            assertTrue(parser.isHotPacket("|#01#72#10am,3,75,0.0#11am,5,78,0.2#12pm,3,80,0.0#1pm,2,82,0.0#"))
        }

        @Test
        fun `returns true for minimal pipe-only text`() {
            assertTrue(parser.isHotPacket("|"))
        }

        @Test
        fun `returns false for empty string`() {
            assertFalse(parser.isHotPacket(""))
        }

        @Test
        fun `returns false for text not starting with pipe`() {
            assertFalse(parser.isHotPacket("Hello world"))
        }

        @Test
        fun `returns false for text with pipe not at start`() {
            assertFalse(parser.isHotPacket("abc|def"))
        }
    }

    @Nested
    @DisplayName("parsePacketType")
    inner class ParsePacketTypeTests {

        @Test
        fun `returns 1 for weather packet prefix`() {
            assertEquals(1, parser.parsePacketType("|#01#72#10am,3,75,0.0#11am,5,78,0.2#12pm,3,80,0.0#1pm,2,82,0.0#"))
        }

        @Test
        fun `returns 2 for venue event packet prefix`() {
            assertEquals(2, parser.parsePacketType("|#02#Venue1,Event1#Venue2,Event2#"))
        }

        @Test
        fun `returns -1 for empty string`() {
            assertEquals(-1, parser.parsePacketType(""))
        }

        @Test
        fun `returns -1 for text without proper format`() {
            assertEquals(-1, parser.parsePacketType("hello"))
        }

        @Test
        fun `returns -1 for pipe without hash`() {
            assertEquals(-1, parser.parsePacketType("|abc"))
        }

        @Test
        fun `returns -1 for non-numeric type code`() {
            assertEquals(-1, parser.parsePacketType("|#AB#data"))
        }

        @Test
        fun `returns type for multi-digit codes`() {
            assertEquals(12, parser.parsePacketType("|#12#data"))
        }
    }

    @Nested
    @DisplayName("parseWeatherPacket - valid packets")
    inner class ParseWeatherPacketValidTests {

        @Test
        fun `parses a valid weather packet with all fields`() {
            val packet = "|#01#72#10am,3,75,0.2#11am,5,78,1.5#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isSuccess)
            val weather = result.getOrThrow()
            assertEquals(72, weather.currentTemp)
            assertEquals(4, weather.forecasts.size)
            assertFalse(weather.isStored)
        }

        @Test
        fun `parses current temperature correctly`() {
            val packet = "|#01#85#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertEquals(85, result.getOrThrow().currentTemp)
        }

        @Test
        fun `parses negative current temperature`() {
            val packet = "|#01#-5#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertEquals(-5, result.getOrThrow().currentTemp)
        }

        @Test
        fun `parses forecast hour labels correctly`() {
            val packet = "|#01#72#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)
            val forecasts = result.getOrThrow().forecasts

            assertEquals("10am", forecasts[0].hourLabel)
            assertEquals("11am", forecasts[1].hourLabel)
            assertEquals("12pm", forecasts[2].hourLabel)
            assertEquals("1pm", forecasts[3].hourLabel)
        }

        @Test
        fun `parses forecast glyph codes correctly`() {
            val packet = "|#01#72#10am,3,75,0.0#11am,5,78,0.0#12pm,7,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)
            val forecasts = result.getOrThrow().forecasts

            assertEquals(3, forecasts[0].glyphCode)
            assertEquals(5, forecasts[1].glyphCode)
            assertEquals(7, forecasts[2].glyphCode)
            assertEquals(2, forecasts[3].glyphCode)
        }

        @Test
        fun `parses forecast temperatures correctly`() {
            val packet = "|#01#72#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)
            val forecasts = result.getOrThrow().forecasts

            assertEquals(75, forecasts[0].temperature)
            assertEquals(78, forecasts[1].temperature)
            assertEquals(80, forecasts[2].temperature)
            assertEquals(82, forecasts[3].temperature)
        }

        @Test
        fun `clears zero precipitation to empty string`() {
            val packet = "|#01#72#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)
            val forecasts = result.getOrThrow().forecasts

            assertEquals("", forecasts[0].precipitation)
            assertEquals("", forecasts[1].precipitation)
            assertEquals("", forecasts[2].precipitation)
            assertEquals("", forecasts[3].precipitation)
        }

        @Test
        fun `preserves non-zero precipitation values`() {
            val packet = "|#01#72#10am,3,75,0.2#11am,5,78,1.5#12pm,3,80,30%#1pm,2,82,0.1#"
            val result = parser.parseWeatherPacket(packet)
            val forecasts = result.getOrThrow().forecasts

            assertEquals("0.2", forecasts[0].precipitation)
            assertEquals("1.5", forecasts[1].precipitation)
            assertEquals("30%", forecasts[2].precipitation)
            assertEquals("0.1", forecasts[3].precipitation)
        }

        @Test
        fun `handles boundary temperature -99`() {
            val packet = "|#01#-99#10am,3,-99,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isSuccess)
            assertEquals(-99, result.getOrThrow().currentTemp)
            assertEquals(-99, result.getOrThrow().forecasts[0].temperature)
        }

        @Test
        fun `handles boundary temperature 999`() {
            val packet = "|#01#999#10am,3,999,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isSuccess)
            assertEquals(999, result.getOrThrow().currentTemp)
            assertEquals(999, result.getOrThrow().forecasts[0].temperature)
        }

        @Test
        fun `handles 6-character hour labels`() {
            val packet = "|#01#72#10amXX,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isSuccess)
            assertEquals("10amXX", result.getOrThrow().forecasts[0].hourLabel)
        }

        @Test
        fun `handles zero current temperature`() {
            val packet = "|#01#0#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isSuccess)
            assertEquals(0, result.getOrThrow().currentTemp)
        }
    }

    @Nested
    @DisplayName("parseWeatherPacket - invalid packets")
    inner class ParseWeatherPacketInvalidTests {

        @Test
        fun `rejects packet without weather prefix`() {
            val packet = "|#02#72#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with wrong hash count - too few`() {
            val packet = "|#01#72#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with wrong hash count - too many`() {
            val packet = "|#01#72#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#extra#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with wrong comma count`() {
            val packet = "|#01#72#10am,3,75#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with non-numeric current temperature`() {
            val packet = "|#01#abc#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with current temperature below -99`() {
            val packet = "|#01#-100#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with current temperature above 999`() {
            val packet = "|#01#1000#10am,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with forecast temperature out of range`() {
            val packet = "|#01#72#10am,3,1000,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with hour label exceeding 6 characters`() {
            val packet = "|#01#72#10amXXX,3,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with non-numeric glyph code`() {
            val packet = "|#01#72#10am,abc,75,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with non-numeric forecast temperature`() {
            val packet = "|#01#72#10am,3,hot,0.0#11am,5,78,0.0#12pm,3,80,0.0#1pm,2,82,0.0#"
            val result = parser.parseWeatherPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects empty string`() {
            val result = parser.parseWeatherPacket("")

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with only prefix`() {
            val result = parser.parseWeatherPacket("|#01#")

            assertTrue(result.isFailure)
        }
    }

    @Nested
    @DisplayName("parseVenueEventPacket - valid packets")
    inner class ParseVenueEventPacketValidTests {

        @Test
        fun `parses a single venue event pair`() {
            val packet = "|#02#Katie Belles,Karaoke#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isSuccess)
            val venues = result.getOrThrow()
            assertEquals(1, venues.size)
            assertEquals("Katie Belles", venues[0].venueName)
            assertEquals("Karaoke", venues[0].eventName)
        }

        @Test
        fun `parses multiple venue event pairs`() {
            val packet = "|#02#Katie Belles,Karaoke#Cody's,Live Band#The Pub,Trivia Night#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isSuccess)
            val venues = result.getOrThrow()
            assertEquals(3, venues.size)
            assertEquals("Katie Belles", venues[0].venueName)
            assertEquals("Karaoke", venues[0].eventName)
            assertEquals("Cody's", venues[1].venueName)
            assertEquals("Live Band", venues[1].eventName)
            assertEquals("The Pub", venues[2].venueName)
            assertEquals("Trivia Night", venues[2].eventName)
        }

        @Test
        fun `parses exactly 12 venue event pairs`() {
            val pairs = (1..12).joinToString("#") { "Venue$it,Event$it" }
            val packet = "|#02#$pairs#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isSuccess)
            val venues = result.getOrThrow()
            assertEquals(12, venues.size)
            for (i in 1..12) {
                assertEquals("Venue$i", venues[i - 1].venueName)
                assertEquals("Event$i", venues[i - 1].eventName)
            }
        }

        @Test
        fun `handles venue and event names with spaces`() {
            val packet = "|#02#The Town Square,Jazz Night Live#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isSuccess)
            val venues = result.getOrThrow()
            assertEquals("The Town Square", venues[0].venueName)
            assertEquals("Jazz Night Live", venues[0].eventName)
        }

        @Test
        fun `handles event name containing commas after first comma`() {
            val packet = "|#02#Venue,Event with, extra commas#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isSuccess)
            val venues = result.getOrThrow()
            assertEquals("Venue", venues[0].venueName)
            assertEquals("Event with, extra commas", venues[0].eventName)
        }
    }

    @Nested
    @DisplayName("parseVenueEventPacket - invalid packets")
    inner class ParseVenueEventPacketInvalidTests {

        @Test
        fun `rejects packet without venue prefix`() {
            val packet = "|#01#Venue1,Event1#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects empty string`() {
            val result = parser.parseVenueEventPacket("")

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with no entries after prefix`() {
            val packet = "|#02#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with pair missing comma`() {
            val packet = "|#02#VenueWithoutEvent#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with empty venue name`() {
            val packet = "|#02#,Event1#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with empty event name`() {
            val packet = "|#02#Venue1,#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with more than 12 entries`() {
            val pairs = (1..13).joinToString("#") { "Venue$it,Event$it" }
            val packet = "|#02#$pairs#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isFailure)
        }

        @Test
        fun `rejects packet with wrong prefix type`() {
            val packet = "|#03#Venue1,Event1#"
            val result = parser.parseVenueEventPacket(packet)

            assertTrue(result.isFailure)
        }
    }
}
