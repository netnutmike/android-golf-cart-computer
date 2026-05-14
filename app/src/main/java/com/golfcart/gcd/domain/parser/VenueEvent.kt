package com.golfcart.gcd.domain.parser

/**
 * A venue/event pair parsed from a HoT venue/event packet (type 02).
 *
 * @property venueName The name of the venue.
 * @property eventName The name of the event at the venue.
 */
data class VenueEvent(
    val venueName: String,
    val eventName: String
)
