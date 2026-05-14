package com.golfcart.gcd.domain.gps

/**
 * Processed GPS data after speed filtering and heading calculation.
 *
 * This data class represents the output of the GPS processing pipeline,
 * with filtered speed values, cardinal direction, and validity indicators.
 */
data class ProcessedGpsData(
    /** Current latitude in decimal degrees. */
    val latitude: Double = 0.0,

    /** Current longitude in decimal degrees. */
    val longitude: Double = 0.0,

    /** Current altitude in meters. */
    val altitude: Double = 0.0,

    /** Filtered speed in miles per hour (integer, after full filtering pipeline). */
    val speedMph: Int = 0,

    /** Raw (unfiltered) speed in miles per hour for internal calculations. */
    val rawSpeedMph: Float = 0f,

    /** Heading in degrees (0-360). */
    val headingDegrees: Float = 0f,

    /** 16-point cardinal direction (N, NNE, NE, ENE, E, ESE, SE, SSE, S, SSW, SW, WSW, W, WNW, NW, NNW). */
    val cardinalDirection: String = "N",

    /** Number of satellites in view (debounced — requires 3 consecutive zeros before displaying zero). */
    val satelliteCount: Int = 0,

    /** Horizontal Dilution of Precision. */
    val hdop: Float = 99.0f,

    /** Formatted satellite/HDOP display string (e.g., "8/1.50"). */
    val satelliteHdopDisplay: String = "0/99.00",

    /** UTC timestamp in milliseconds when this reading was taken. */
    val timestamp: Long = 0L,

    /** Whether this GPS reading is considered valid. */
    val isValid: Boolean = false
)
