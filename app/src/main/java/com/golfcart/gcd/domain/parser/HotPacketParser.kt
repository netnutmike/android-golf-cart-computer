package com.golfcart.gcd.domain.parser

/**
 * Parser for HoT (Hands-off-Transmission) structured data packets received
 * via Meshtastic text messages.
 *
 * HoT packets are identified by a leading `|` character and contain a type code
 * after `|#`. Supported packet types:
 * - Type 01: Weather data (current temp + 4-hour forecast)
 * - Type 02: Venue/event entertainment data
 *
 * Malformed packets are discarded with a diagnostic log message.
 */
interface HotPacketParser {

    /**
     * Parse a weather packet (type 01) from raw text.
     *
     * Weather packet format: `|#01#<current_temp>#<hr>,<glyph>,<temp>,<precip>#...#`
     * (4 forecast hour sections)
     *
     * Validation rules:
     * - Must start with `|#01#`
     * - Must contain exactly 7 `#` delimiters
     * - Must contain exactly 12 `,` delimiters
     * - Temperature values must be in range -99 to 999
     * - Hour labels must be 6 characters or fewer
     * - Precipitation values of "0.0" are cleared to empty string
     *
     * @param rawPacket The raw packet text received from Meshtastic.
     * @return [Result.success] with parsed [WeatherData], or [Result.failure] if malformed.
     */
    fun parseWeatherPacket(rawPacket: String): Result<WeatherData>

    /**
     * Parse a venue/event packet (type 02) from raw text.
     *
     * Venue/event packet format: `|#02#<venue>,<event>#<venue>,<event>#...#`
     *
     * @param rawPacket The raw packet text received from Meshtastic.
     * @return [Result.success] with parsed list of [VenueEvent], or [Result.failure] if malformed.
     */
    fun parseVenueEventPacket(rawPacket: String): Result<List<VenueEvent>>

    /**
     * Check if a text message is a HoT packet.
     *
     * A HoT packet is identified by a leading `|` character.
     *
     * @param text The raw text message to check.
     * @return true if the text starts with `|`, false otherwise.
     */
    fun isHotPacket(text: String): Boolean

    /**
     * Extract the packet type code from a HoT packet.
     *
     * The type code is the two-digit number after `|#` (e.g., "01" for weather, "02" for venue).
     *
     * @param text The raw HoT packet text.
     * @return The packet type as an integer (e.g., 1 for weather, 2 for venue), or -1 if invalid.
     */
    fun parsePacketType(text: String): Int
}
