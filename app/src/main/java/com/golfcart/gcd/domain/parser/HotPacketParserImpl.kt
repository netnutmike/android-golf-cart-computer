package com.golfcart.gcd.domain.parser

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [HotPacketParser] for parsing structured HoT data packets.
 *
 * Handles weather packets (type 01) and venue/event packets (type 02) received
 * via Meshtastic text messages. Malformed packets are discarded with diagnostic logging.
 *
 * Weather packet format: `|#01#<current_temp>#<hr>,<glyph>,<temp>,<precip>#<hr>,<glyph>,<temp>,<precip>#<hr>,<glyph>,<temp>,<precip>#<hr>,<glyph>,<temp>,<precip>#`
 *
 * Requirements: 3.1, 3.2, 3.3, 3.9, 3.10, 3.11, 3.12
 */
@Singleton
class HotPacketParserImpl @Inject constructor() : HotPacketParser {

    companion object {
        private const val TAG = "HotPacketParser"

        /** Weather packet type prefix. */
        const val WEATHER_PREFIX = "|#01#"

        /** Venue/event packet type prefix. */
        const val VENUE_PREFIX = "|#02#"

        /** Required number of `#` delimiters in a valid weather packet. */
        const val WEATHER_HASH_COUNT = 7

        /** Required number of `,` delimiters in a valid weather packet. */
        const val WEATHER_COMMA_COUNT = 12

        /** Minimum valid temperature value. */
        const val MIN_TEMPERATURE = -99

        /** Maximum valid temperature value. */
        const val MAX_TEMPERATURE = 999

        /** Maximum length for hour labels. */
        const val MAX_HOUR_LABEL_LENGTH = 6

        /** Precipitation value that should be cleared to empty string. */
        const val ZERO_PRECIPITATION = "0.0"

        /** Number of forecast hours in a weather packet. */
        const val FORECAST_HOUR_COUNT = 4

        /** Number of comma-separated fields per forecast hour. */
        const val FIELDS_PER_FORECAST = 4

        /** Maximum number of venue/event entries. */
        const val MAX_VENUE_ENTRIES = 12

        /** Maximum characters to log from a malformed packet for diagnostics. */
        const val DIAGNOSTIC_LOG_LENGTH = 40
    }

    override fun isHotPacket(text: String): Boolean {
        return text.isNotEmpty() && text[0] == '|'
    }

    override fun parsePacketType(text: String): Int {
        // Type code is after "|#" — expect format "|#XX#..."
        if (text.length < 4 || text[0] != '|' || text[1] != '#') {
            return -1
        }
        // Find the next '#' after position 2 to extract the type code
        val endIndex = text.indexOf('#', 2)
        if (endIndex == -1) {
            return -1
        }
        val typeStr = text.substring(2, endIndex)
        return typeStr.toIntOrNull() ?: -1
    }

    override fun parseWeatherPacket(rawPacket: String): Result<WeatherData> {
        // Validate prefix
        if (!rawPacket.startsWith(WEATHER_PREFIX)) {
            logMalformed("Weather packet does not start with $WEATHER_PREFIX", rawPacket)
            return Result.failure(IllegalArgumentException("Invalid weather packet prefix"))
        }

        // Validate delimiter counts
        val hashCount = rawPacket.count { it == '#' }
        if (hashCount != WEATHER_HASH_COUNT) {
            logMalformed("Weather packet has $hashCount '#' delimiters (expected $WEATHER_HASH_COUNT)", rawPacket)
            return Result.failure(IllegalArgumentException("Invalid # delimiter count: $hashCount"))
        }

        val commaCount = rawPacket.count { it == ',' }
        if (commaCount != WEATHER_COMMA_COUNT) {
            logMalformed("Weather packet has $commaCount ',' delimiters (expected $WEATHER_COMMA_COUNT)", rawPacket)
            return Result.failure(IllegalArgumentException("Invalid , delimiter count: $commaCount"))
        }

        // Split by '#' — format: |#01#<temp>#<hr1data>#<hr2data>#<hr3data>#<hr4data>#
        // After split: ["", "01", "<temp>", "<hr1data>", "<hr2data>", "<hr3data>", "<hr4data>", ""]
        // The leading "|" is before the first "#", so segments[0] = "|"
        val segments = rawPacket.split('#')
        // segments: ["|", "01", "<temp>", "<hr1>", "<hr2>", "<hr3>", "<hr4>", ""]

        // Parse current temperature (segment index 2)
        val currentTempStr = segments[2]
        val currentTemp = currentTempStr.toIntOrNull()
        if (currentTemp == null) {
            logMalformed("Cannot parse current temperature: '$currentTempStr'", rawPacket)
            return Result.failure(IllegalArgumentException("Invalid current temperature: $currentTempStr"))
        }

        if (currentTemp < MIN_TEMPERATURE || currentTemp > MAX_TEMPERATURE) {
            logMalformed("Current temperature $currentTemp out of range [$MIN_TEMPERATURE, $MAX_TEMPERATURE]", rawPacket)
            return Result.failure(IllegalArgumentException("Temperature out of range: $currentTemp"))
        }

        // Parse 4 forecast hours (segments 3, 4, 5, 6)
        val forecasts = mutableListOf<HourForecast>()
        for (i in 0 until FORECAST_HOUR_COUNT) {
            val forecastSegment = segments[3 + i]
            val forecastResult = parseForecastSegment(forecastSegment, rawPacket)
                ?: return Result.failure(IllegalArgumentException("Invalid forecast segment at index $i"))
            forecasts.add(forecastResult)
        }

        val weatherData = WeatherData(
            currentTemp = currentTemp,
            forecasts = forecasts,
            receivedTimestamp = "",
            isStored = false
        )

        return Result.success(weatherData)
    }

    override fun parseVenueEventPacket(rawPacket: String): Result<List<VenueEvent>> {
        // Validate prefix
        if (!rawPacket.startsWith(VENUE_PREFIX)) {
            logMalformed("Venue/event packet does not start with $VENUE_PREFIX", rawPacket)
            return Result.failure(IllegalArgumentException("Invalid venue/event packet prefix"))
        }

        // Extract data after the header (5 characters: |#02#)
        val data = rawPacket.substring(VENUE_PREFIX.length)

        // Split by '#' to get venue,event pairs
        // The packet ends with '#', so the last element after split will be empty
        val segments = data.split('#')

        // Filter out empty segments (trailing '#' produces an empty last element)
        val pairs = segments.filter { it.isNotEmpty() }

        // Validate we have at least one entry
        if (pairs.isEmpty()) {
            logMalformed("Venue/event packet contains no entries", rawPacket)
            return Result.failure(IllegalArgumentException("No venue/event entries found"))
        }

        // Validate we don't exceed maximum entries
        if (pairs.size > MAX_VENUE_ENTRIES) {
            logMalformed("Venue/event packet has ${pairs.size} entries (max $MAX_VENUE_ENTRIES)", rawPacket)
            return Result.failure(IllegalArgumentException("Too many venue/event entries: ${pairs.size}"))
        }

        // Parse each venue,event pair
        val venueEvents = mutableListOf<VenueEvent>()
        for (pair in pairs) {
            val commaIndex = pair.indexOf(',')
            if (commaIndex == -1) {
                logMalformed("Venue/event pair missing comma separator: '$pair'", rawPacket)
                return Result.failure(IllegalArgumentException("Invalid venue/event pair (no comma): $pair"))
            }

            val venueName = pair.substring(0, commaIndex)
            val eventName = pair.substring(commaIndex + 1)

            if (venueName.isEmpty()) {
                logMalformed("Venue name is empty in pair: '$pair'", rawPacket)
                return Result.failure(IllegalArgumentException("Empty venue name in pair: $pair"))
            }

            if (eventName.isEmpty()) {
                logMalformed("Event name is empty in pair: '$pair'", rawPacket)
                return Result.failure(IllegalArgumentException("Empty event name in pair: $pair"))
            }

            venueEvents.add(VenueEvent(venueName = venueName, eventName = eventName))
        }

        return Result.success(venueEvents)
    }

    /**
     * Parse a single forecast segment of format "hourLabel,glyphCode,temperature,precipitation".
     *
     * @return Parsed [HourForecast] or null if the segment is malformed.
     */
    private fun parseForecastSegment(segment: String, rawPacket: String): HourForecast? {
        val fields = segment.split(',')
        if (fields.size != FIELDS_PER_FORECAST) {
            logMalformed("Forecast segment has ${fields.size} fields (expected $FIELDS_PER_FORECAST): '$segment'", rawPacket)
            return null
        }

        val hourLabel = fields[0]
        val glyphCodeStr = fields[1]
        val tempStr = fields[2]
        val precipStr = fields[3]

        // Validate hour label length
        if (hourLabel.length > MAX_HOUR_LABEL_LENGTH) {
            logMalformed("Hour label '${hourLabel}' exceeds $MAX_HOUR_LABEL_LENGTH characters", rawPacket)
            return null
        }

        // Parse glyph code
        val glyphCode = glyphCodeStr.toIntOrNull()
        if (glyphCode == null) {
            logMalformed("Cannot parse glyph code: '$glyphCodeStr'", rawPacket)
            return null
        }

        // Parse temperature
        val temperature = tempStr.toIntOrNull()
        if (temperature == null) {
            logMalformed("Cannot parse forecast temperature: '$tempStr'", rawPacket)
            return null
        }

        if (temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE) {
            logMalformed("Forecast temperature $temperature out of range [$MIN_TEMPERATURE, $MAX_TEMPERATURE]", rawPacket)
            return null
        }

        // Clear zero precipitation values
        val precipitation = if (precipStr == ZERO_PRECIPITATION) "" else precipStr

        return HourForecast(
            hourLabel = hourLabel,
            glyphCode = glyphCode,
            temperature = temperature,
            precipitation = precipitation
        )
    }

    /**
     * Log a diagnostic message for a malformed packet.
     * Logs the first [DIAGNOSTIC_LOG_LENGTH] characters of the packet for debugging.
     */
    private fun logMalformed(reason: String, rawPacket: String) {
        val preview = if (rawPacket.length > DIAGNOSTIC_LOG_LENGTH) {
            rawPacket.substring(0, DIAGNOSTIC_LOG_LENGTH) + "..."
        } else {
            rawPacket
        }
        Log.w(TAG, "Malformed HoT packet discarded: $reason | Packet: $preview")
    }
}
