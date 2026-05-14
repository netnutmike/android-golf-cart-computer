package com.golfcart.gcd.data.bluetooth

import com.juul.kable.characteristicOf

/**
 * BLE UUIDs and constants for the Meshtastic radio protocol.
 */
object MeshtasticConstants {

    /** Meshtastic BLE GATT service UUID. */
    const val SERVICE_UUID = "6ba1b218-15a8-461f-9fa8-5dcae273eafd"

    /** TORADIO characteristic UUID — write outbound protobuf packets here. */
    const val TORADIO_UUID = "f75c76d2-129e-4dad-a1dd-7866124401e7"

    /** FROMRADIO characteristic UUID — read inbound protobuf packets from here. */
    const val FROMRADIO_UUID = "2c55e69e-4993-11ed-b878-0242ac120002"

    /** FROMNUM characteristic UUID — subscribe to notifications to detect new data. */
    const val FROMNUM_UUID = "ed9da18c-a800-4f66-a670-aa7547e34453"

    /** Kable characteristic reference for TORADIO. */
    val toRadioCharacteristic = characteristicOf(
        service = SERVICE_UUID,
        characteristic = TORADIO_UUID
    )

    /** Kable characteristic reference for FROMRADIO. */
    val fromRadioCharacteristic = characteristicOf(
        service = SERVICE_UUID,
        characteristic = FROMRADIO_UUID
    )

    /** Kable characteristic reference for FROMNUM (notifications). */
    val fromNumCharacteristic = characteristicOf(
        service = SERVICE_UUID,
        characteristic = FROMNUM_UUID
    )

    /** Regex pattern for Meshtastic device names (e.g., "Meshtastic_a1b2"). */
    val DEVICE_NAME_PATTERN = Regex("^.*_([0-9a-fA-F]{4})")

    /** Default safe BLE payload size without MTU negotiation. */
    const val DEFAULT_PAYLOAD_SIZE = 20

    /** Desired MTU to negotiate (maximum BLE allows is 517, but 512 is common). */
    const val DESIRED_MTU = 512

    /** Overhead bytes subtracted from MTU to get usable payload size. */
    const val MTU_OVERHEAD = 3

    /** Heartbeat interval in milliseconds (30 seconds). */
    const val HEARTBEAT_INTERVAL_MS = 30_000L

    /** Liveness timeout in milliseconds (60 seconds). */
    const val LIVENESS_TIMEOUT_MS = 60_000L

    /** Maximum outbound message payload size in bytes. */
    const val MAX_PAYLOAD_SIZE = 237

    /** Broadcast destination address. */
    const val BROADCAST_ADDRESS = 0xFFFFFFFFL

    /** Notification channel ID for the foreground service. */
    const val NOTIFICATION_CHANNEL_ID = "meshtastic_service_channel"

    /** Notification ID for the foreground service. */
    const val NOTIFICATION_ID = 1001

    /** Foreground service notification channel name. */
    const val NOTIFICATION_CHANNEL_NAME = "Meshtastic Connection"
}
