package com.golfcart.gcd.domain.parser

import net.jqwik.api.*
import net.jqwik.api.Combinators
import org.junit.jupiter.api.Assertions.*

/**
 * Property-based tests for venue/event packet parsing in HotPacketParserImpl.
 *
 * Feature: android-golf-cart-computer, Property 9: Venue/event packet parsing
 *
 * Validates: Requirements 4.1, 4.2, 4.4
 */
@Label("Venue/Event Packet Property Tests")
@Tag("Feature: android-golf-cart-computer")
class VenueEventPacketPropertyTest {

    private val parser = HotPacketParserImpl()

    // =========================================================================
    // Property 9: Venue/event packet parsing
    // =========================================================================

    /**
     * Property 9a: Valid venue/event packets (starting with |#02# and containing
     * 1-12 venue,event pairs) are always accepted by the parser.
     *
     * Validates: Requirements 4.1, 4.2, 4.4
     */
    @Property(tries = 10)
    @Label("Property 9: Valid venue/event packets are always accepted")
    @Tag("Property 9: Venue/event packet parsing")
    fun validVenueEventPacketsAreAccepted(@ForAll("validVenueEventPackets") packet: String) {
        val result = parser.parseVenueEventPacket(packet)

        assertTrue(result.isSuccess,
            "A structurally valid venue/event packet must be accepted. Packet: $packet")
    }

    /**
     * Property 9b: The number of parsed VenueEvent objects equals the number of
     * venue,event pairs in the input packet (up to 12).
     *
     * Validates: Requirements 4.1, 4.2, 4.4
     */
    @Property(tries = 10)
    @Label("Property 9: Parsed list length matches number of input pairs")
    @Tag("Property 9: Venue/event packet parsing")
    fun parsedListLengthMatchesInputPairCount(
        @ForAll("validVenueEventPairLists") pairs: List<VenueEventInput>
    ) {
        val packet = buildVenueEventPacket(pairs)
        val result = parser.parseVenueEventPacket(packet)

        assertTrue(result.isSuccess)
        assertEquals(pairs.size, result.getOrThrow().size,
            "Parsed list length must equal the number of input pairs")
    }

    /**
     * Property 9c: Each parsed VenueEvent's venueName matches the corresponding
     * venue name from the input packet.
     *
     * Validates: Requirements 4.1, 4.2, 4.4
     */
    @Property(tries = 10)
    @Label("Property 9: Parsed venue names match input")
    @Tag("Property 9: Venue/event packet parsing")
    fun parsedVenueNamesMatchInput(
        @ForAll("validVenueEventPairLists") pairs: List<VenueEventInput>
    ) {
        val packet = buildVenueEventPacket(pairs)
        val result = parser.parseVenueEventPacket(packet)

        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow()

        for (i in pairs.indices) {
            assertEquals(pairs[i].venueName, parsed[i].venueName,
                "Venue name at index $i must match input")
        }
    }

    /**
     * Property 9d: Each parsed VenueEvent's eventName matches the corresponding
     * event name from the input packet.
     *
     * Validates: Requirements 4.1, 4.2, 4.4
     */
    @Property(tries = 10)
    @Label("Property 9: Parsed event names match input")
    @Tag("Property 9: Venue/event packet parsing")
    fun parsedEventNamesMatchInput(
        @ForAll("validVenueEventPairLists") pairs: List<VenueEventInput>
    ) {
        val packet = buildVenueEventPacket(pairs)
        val result = parser.parseVenueEventPacket(packet)

        assertTrue(result.isSuccess)
        val parsed = result.getOrThrow()

        for (i in pairs.indices) {
            assertEquals(pairs[i].eventName, parsed[i].eventName,
                "Event name at index $i must match input")
        }
    }

    /**
     * Property 9e: Packets without the correct prefix |#02# are rejected.
     *
     * Validates: Requirements 4.1
     */
    @Property(tries = 10)
    @Label("Property 9: Packets without correct prefix are rejected")
    @Tag("Property 9: Venue/event packet parsing")
    fun packetsWithoutCorrectPrefixAreRejected(@ForAll("invalidPrefixPackets") packet: String) {
        val result = parser.parseVenueEventPacket(packet)

        assertTrue(result.isFailure,
            "A packet without |#02# prefix must be rejected. Packet: $packet")
    }

    /**
     * Property 9f: Packets with more than 12 venue/event pairs are rejected.
     *
     * Validates: Requirements 4.4
     */
    @Property(tries = 10)
    @Label("Property 9: Packets with more than 12 entries are rejected")
    @Tag("Property 9: Venue/event packet parsing")
    fun packetsWithTooManyEntriesAreRejected(@ForAll("tooManyEntriesPackets") packet: String) {
        val result = parser.parseVenueEventPacket(packet)

        assertTrue(result.isFailure,
            "A packet with more than 12 entries must be rejected. Packet: $packet")
    }

    /**
     * Property 9g: Packets with pairs missing the comma separator are rejected.
     *
     * Validates: Requirements 4.2
     */
    @Property(tries = 10)
    @Label("Property 9: Packets with missing comma separator are rejected")
    @Tag("Property 9: Venue/event packet parsing")
    fun packetsWithMissingCommaAreRejected(@ForAll("missingCommaPackets") packet: String) {
        val result = parser.parseVenueEventPacket(packet)

        assertTrue(result.isFailure,
            "A packet with a pair missing comma separator must be rejected. Packet: $packet")
    }

    /**
     * Property 9h: Packets with empty venue or event names are rejected.
     *
     * Validates: Requirements 4.2
     */
    @Property(tries = 10)
    @Label("Property 9: Packets with empty venue or event names are rejected")
    @Tag("Property 9: Venue/event packet parsing")
    fun packetsWithEmptyNamesAreRejected(@ForAll("emptyNamePackets") packet: String) {
        val result = parser.parseVenueEventPacket(packet)

        assertTrue(result.isFailure,
            "A packet with empty venue or event name must be rejected. Packet: $packet")
    }

    // =========================================================================
    // Helper: Build venue/event packet from components
    // =========================================================================

    private fun buildVenueEventPacket(pairs: List<VenueEventInput>): String {
        val sb = StringBuilder()
        sb.append("|#02#")
        for (pair in pairs) {
            sb.append(pair.venueName)
            sb.append(',')
            sb.append(pair.eventName)
            sb.append('#')
        }
        return sb.toString()
    }

    // =========================================================================
    // Data class for venue/event input
    // =========================================================================

    data class VenueEventInput(
        val venueName: String,
        val eventName: String
    )

    // =========================================================================
    // Generators
    // =========================================================================

    /**
     * Generates fully valid venue/event packets that should always be accepted.
     */
    @Provide
    fun validVenueEventPackets(): Arbitrary<String> {
        return validVenueEventPairLists().map { pairs -> buildVenueEventPacket(pairs) }
    }

    /**
     * Generates valid lists of 1-12 VenueEventInput pairs.
     */
    @Provide
    fun validVenueEventPairLists(): Arbitrary<List<VenueEventInput>> {
        return validVenueEventPair().list().ofMinSize(1).ofMaxSize(12)
    }

    /**
     * Generates packets that do NOT start with "|#02#".
     */
    @Provide
    fun invalidPrefixPackets(): Arbitrary<String> {
        val badPrefixes = Arbitraries.of(
            "|#01#", "|#03#", "#02#", "02#", "|02#", "X#02#", "",
            "|#2#", "|#002#", "hello", "|#00#"
        )
        return Combinators.combine(
            badPrefixes,
            validVenueEventPairLists()
        ).`as` { prefix, pairs ->
            val sb = StringBuilder()
            sb.append(prefix)
            for (pair in pairs) {
                sb.append(pair.venueName)
                sb.append(',')
                sb.append(pair.eventName)
                sb.append('#')
            }
            sb.toString()
        }
    }

    /**
     * Generates packets with more than 12 venue/event entries.
     */
    @Provide
    fun tooManyEntriesPackets(): Arbitrary<String> {
        return validVenueEventPair().list().ofMinSize(13).ofMaxSize(20)
            .map { pairs -> buildVenueEventPacket(pairs) }
    }

    /**
     * Generates packets where at least one pair is missing the comma separator.
     */
    @Provide
    fun missingCommaPackets(): Arbitrary<String> {
        return Combinators.combine(
            validVenueEventPairLists(),
            validVenueName()
        ).`as` { pairs, badEntry ->
            val sb = StringBuilder()
            sb.append("|#02#")
            // First entry has no comma (just a venue name with no separator)
            sb.append(badEntry)
            sb.append('#')
            // Remaining entries are valid
            for (pair in pairs) {
                sb.append(pair.venueName)
                sb.append(',')
                sb.append(pair.eventName)
                sb.append('#')
            }
            sb.toString()
        }
    }

    /**
     * Generates packets where at least one pair has an empty venue or event name.
     */
    @Provide
    fun emptyNamePackets(): Arbitrary<String> {
        val emptyVenueOrEvent = Arbitraries.of("emptyVenue", "emptyEvent")
        return Combinators.combine(
            validVenueEventPairLists(),
            emptyVenueOrEvent,
            validVenueName(),
            validEventName()
        ).`as` { pairs, mode, venue, event ->
            val sb = StringBuilder()
            sb.append("|#02#")
            // First entry has an empty venue or event name
            if (mode == "emptyVenue") {
                sb.append(',')
                sb.append(event)
            } else {
                sb.append(venue)
                sb.append(',')
            }
            sb.append('#')
            // Remaining entries are valid
            for (pair in pairs) {
                sb.append(pair.venueName)
                sb.append(',')
                sb.append(pair.eventName)
                sb.append('#')
            }
            sb.toString()
        }
    }

    // =========================================================================
    // Component generators
    // =========================================================================

    private fun validVenueEventPair(): Arbitrary<VenueEventInput> {
        return Combinators.combine(
            validVenueName(),
            validEventName()
        ).`as` { venue, event -> VenueEventInput(venue, event) }
    }

    private fun validVenueName(): Arbitrary<String> {
        // Realistic venue names — no '#' or ',' characters allowed
        return Arbitraries.of(
            "Katie Belles", "The Barn", "Brownwood Paddock",
            "Lake Sumter Landing", "Spanish Springs", "Eisenhower Rec",
            "Savannah Center", "Sharon Morse PAC", "Colony Cottage",
            "Laurel Manor", "Rohan Rec Center", "Everglades Rec",
            "Fenney Rec", "Bridgeport Rec", "Mulberry Grove",
            "SeaBreeze Rec", "Paradise Rec", "Riverbend Rec"
        )
    }

    private fun validEventName(): Arbitrary<String> {
        // Realistic event names — no '#' or ',' characters allowed
        return Arbitraries.of(
            "Live Band", "Karaoke Night", "DJ Dance Party",
            "Trivia Night", "Line Dancing", "Jazz Ensemble",
            "Country Western", "Rock & Roll", "Oldies Night",
            "Open Mic", "Comedy Show", "Bingo Night",
            "Movie Night", "Pool Party", "Happy Hour",
            "Blues Band", "Acoustic Set", "Swing Dancing"
        )
    }
}
