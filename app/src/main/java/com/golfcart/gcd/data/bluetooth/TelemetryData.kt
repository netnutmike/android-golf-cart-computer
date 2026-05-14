package com.golfcart.gcd.data.bluetooth

/**
 * Telemetry data received from the GCI (Golf Cart Internal) ESP-32 computer.
 *
 * Contains vehicle sensor readings transmitted over Bluetooth.
 */
data class TelemetryData(
    /** Headlight mode indicator (e.g., off, low, high). */
    val headlightMode: Int = 0,

    /** Outdoor luminosity sensor reading. */
    val outdoorLuminosity: Int = 0,

    /** Air temperature in degrees Fahrenheit. */
    val airTemperature: Float = 0f,

    /** Battery voltage reading. */
    val batteryVoltage: Float = 0f,

    /** Fuel level reading. */
    val fuelLevel: Float = 0f,

    /** Timestamp (epoch millis) when this data was last updated. */
    val lastUpdated: Long = 0L
)
