package com.golfcart.gcd.data.bluetooth

/**
 * Represents the connection state of a Bluetooth device (Meshtastic or GCI).
 */
enum class ConnectionState {
    /** No active connection or connection attempt. */
    DISCONNECTED,

    /** Actively scanning for a device to connect to. */
    SCANNING,

    /** BLE connection in progress (GATT connect, MTU negotiation). */
    CONNECTING,

    /** BLE connection established, characteristics discovered. */
    CONNECTED,

    /** Meshtastic-specific: configuration download/handshake in progress. */
    HANDSHAKING,

    /** Fully operational — handshake complete, ready for messaging. */
    READY
}
