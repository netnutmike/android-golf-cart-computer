package com.golfcart.gcd.data.bluetooth

import com.squareup.wire.Message
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for the Meshtastic BLE connection, exposed to the domain layer.
 *
 * Provides reactive state flows for connection status and incoming packets,
 * and suspend functions for sending messages and controlling the connection.
 */
interface MeshtasticConnection {

    /** Current connection state as a reactive flow. */
    val connectionState: StateFlow<ConnectionState>

    /** Local node ID (hex string, e.g., "!a1b2c3d4") once handshake completes. */
    val nodeId: StateFlow<String>

    /** Incoming mesh packets emitted as a shared flow for multiple collectors. */
    val incomingPackets: SharedFlow<ByteArray>

    /**
     * Send a text message via the Meshtastic mesh network.
     *
     * @param text The message text to send.
     * @param destination The destination node number (use [MeshtasticConstants.BROADCAST_ADDRESS] for broadcast).
     * @param channel The channel index to send on.
     */
    suspend fun sendTextMessage(text: String, destination: Long, channel: Int)

    /**
     * Send an admin message to the connected radio.
     *
     * @param message The protobuf-encoded admin message bytes.
     */
    suspend fun sendAdminMessage(message: ByteArray)

    /**
     * Set the position configuration on the connected radio.
     *
     * @param config The protobuf-encoded position config bytes.
     */
    suspend fun setPositionConfig(config: ByteArray)

    /**
     * Reboot the connected Meshtastic radio.
     *
     * @param delaySeconds Delay before reboot in seconds.
     */
    suspend fun rebootRadio(delaySeconds: Int)

    /**
     * Disconnect from the currently connected device.
     */
    suspend fun disconnect()

    /**
     * Connect to a Meshtastic device at the given BLE address.
     *
     * @param deviceAddress The BLE MAC address of the device to connect to.
     */
    suspend fun connect(deviceAddress: String)

    /**
     * Start scanning for Meshtastic devices.
     * Results are reflected in [connectionState] transitioning to SCANNING.
     */
    suspend fun startScan()

    /**
     * Stop scanning for Meshtastic devices.
     */
    suspend fun stopScan()
}
