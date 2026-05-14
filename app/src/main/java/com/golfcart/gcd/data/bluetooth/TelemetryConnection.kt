package com.golfcart.gcd.data.bluetooth

import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the GCI Bluetooth telemetry connection, exposed to the domain layer.
 *
 * Provides reactive state flows for connection status and telemetry data,
 * and suspend functions for sending status updates and managing pairing.
 */
interface TelemetryConnection {

    /** Current connection state as a reactive flow. */
    val connectionState: StateFlow<ConnectionState>

    /** Latest telemetry data received from the GCI as a reactive flow. */
    val telemetryData: StateFlow<TelemetryData>

    /**
     * Send a heartbeat message to the GCI to maintain connection liveness.
     */
    suspend fun sendHeartbeat()

    /**
     * Send GPS data to the GCI.
     *
     * @param latitude Latitude in degrees.
     * @param longitude Longitude in degrees.
     * @param altitude Altitude in meters.
     * @param speed Speed in mph.
     * @param heading Heading in degrees.
     * @param satellites Number of satellites.
     */
    suspend fun sendGpsData(
        latitude: Float,
        longitude: Float,
        altitude: Float,
        speed: Float,
        heading: Float,
        satellites: Int
    )

    /**
     * Send "at home" status to the GCI.
     *
     * @param isHome Whether the device is within the home geofence.
     */
    suspend fun sendIsHome(isHome: Boolean)

    /**
     * Send "is daytime" status to the GCI.
     *
     * @param isDaytime Whether it is currently daytime.
     */
    suspend fun sendIsDaytime(isDaytime: Boolean)

    /**
     * Initiate pairing with a new GCI device.
     *
     * Opens a discovery window for the specified timeout, broadcasts a pairing
     * command containing the GCD's MAC address, and waits for an ACK response.
     *
     * @param timeoutSeconds The pairing window duration in seconds (default: 6).
     */
    suspend fun pairNewDevice(timeoutSeconds: Int = 6)

    /**
     * Connect to a GCI device at the given Bluetooth address.
     *
     * @param deviceAddress The Bluetooth MAC address of the GCI device.
     */
    suspend fun connect(deviceAddress: String)

    /**
     * Disconnect from the currently connected GCI device.
     */
    suspend fun disconnect()
}
