package com.golfcart.gcd.ui.entertainment

import com.golfcart.gcd.domain.parser.VenueEvent

/**
 * UI state model for the Entertainment screen.
 *
 * Holds the list of venue/event entries, the timestamp of last data receipt,
 * and whether the data was loaded from cache.
 *
 * Requirements: 4.3, 4.4, 4.5, 4.6, 4.7, 4.8
 *
 * @property venues List of venue/event pairs (up to 12 entries).
 * @property receivedTimestamp Timestamp string of when the data was last received.
 * @property isStored True if the data was loaded from cache rather than received live.
 */
data class EntertainmentData(
    val venues: List<VenueEvent>,
    val receivedTimestamp: String,
    val isStored: Boolean
)
