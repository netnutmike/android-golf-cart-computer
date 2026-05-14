package com.golfcart.gcd.data.bluetooth

import android.util.Log
import com.juul.kable.Peripheral
import com.juul.kable.WriteType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Handles the Meshtastic BLE protocol layer: packet framing, MTU-aware splitting,
 * FROMNUM notification subscription, and FROMRADIO polling.
 *
 * This class sits between the BLE transport (Kable Peripheral) and the application
 * logic, handling the protocol details of communicating with a Meshtastic radio.
 *
 * Protocol flow:
 * 1. Subscribe to FROMNUM notifications to detect when new data is available
 * 2. When FROMNUM notification arrives, poll FROMRADIO until empty response
 * 3. Outbound messages are framed with 4-byte big-endian length prefix
 * 4. Large framed packets are split into MTU-sized chunks for BLE writes
 *
 * Note: Protobuf encoding/decoding uses Wire-generated classes:
 * - Encoding: `ToRadio.ADAPTER.encode(toRadioMessage)` returns ByteArray
 * - Decoding: `FromRadio.ADAPTER.decode(bytes)` returns FromRadio object
 *
 * Since Wire-generated classes are produced at build time, this class uses ByteArray
 * as the message type and documents where Wire ADAPTER calls would be used.
 *
 * Validates: Requirements 1.4, 1.5, 1.6, 1.7, 18.1, 18.2, 18.3
 */
class MeshtasticProtocol(
    private val peripheral: Peripheral,
    private val mtuPayloadSize: Int = MeshtasticConstants.DEFAULT_PAYLOAD_SIZE
) {

    companion object {
        private const val TAG = "MeshtasticProtocol"

        /** An empty response from FROMRADIO indicates no more data available. */
        private const val EMPTY_RESPONSE_THRESHOLD = 0
    }

    private val _receivedPackets = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)

    /**
     * Flow of decoded FromRadio protobuf bytes received from the radio.
     * Each emission is the raw protobuf payload (without the length prefix).
     *
     * In production, consumers would decode these bytes using:
     * `FromRadio.ADAPTER.decode(bytes)`
     */
    val receivedPackets: SharedFlow<ByteArray> = _receivedPackets.asSharedFlow()

    /**
     * Subscribes to FROMNUM characteristic notifications on the peripheral.
     *
     * FROMNUM is a 4-byte notification-only characteristic. When the radio has
     * new data available on FROMRADIO, it increments FROMNUM and sends a notification.
     * The actual value of FROMNUM is not meaningful — it's purely a signal to poll.
     *
     * This method returns a Flow that emits each time a FROMNUM notification arrives.
     * The caller should collect this flow and call [pollFromRadio] on each emission.
     *
     * @return A Flow of ByteArray representing FROMNUM notification values.
     */
    fun observeFromNumNotifications(): Flow<ByteArray> {
        return peripheral.observe(MeshtasticConstants.fromNumCharacteristic)
    }

    /**
     * Polls the FROMRADIO characteristic repeatedly until an empty response is received.
     *
     * This implements the Meshtastic BLE read pattern: after receiving a FROMNUM
     * notification, the client must read FROMRADIO in a loop until the radio returns
     * an empty (zero-length) response, indicating no more data is queued.
     *
     * Each non-empty response is a complete protobuf-encoded FromRadio message
     * (without a length prefix — the length prefix is only used for outbound TORADIO writes).
     *
     * Decoded packets are emitted to [receivedPackets] for processing by the application layer.
     *
     * In production, each response would be decoded using:
     * `FromRadio.ADAPTER.decode(responseBytes)`
     *
     * @return The list of raw FromRadio protobuf byte arrays read during this poll cycle.
     */
    suspend fun pollFromRadio(): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()

        try {
            while (true) {
                val response = peripheral.read(MeshtasticConstants.fromRadioCharacteristic)

                // Empty response signals no more data available
                if (response.isEmpty()) {
                    break
                }

                packets.add(response)
                _receivedPackets.emit(response)

                Log.d(TAG, "Read FromRadio packet: ${response.size} bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error polling FROMRADIO", e)
        }

        if (packets.isNotEmpty()) {
            Log.d(TAG, "Poll cycle complete: ${packets.size} packets received")
        }

        return packets
    }

    /**
     * Writes a protobuf-encoded ToRadio message to the TORADIO characteristic.
     *
     * The message bytes are:
     * 1. Framed with a 4-byte big-endian length prefix
     * 2. Split into MTU-sized chunks if the framed packet exceeds the payload size
     * 3. Written sequentially to the TORADIO characteristic
     *
     * In production, the caller would first encode the ToRadio message:
     * ```kotlin
     * val protobufBytes = ToRadio.ADAPTER.encode(toRadioMessage)
     * protocol.writeToRadio(protobufBytes)
     * ```
     *
     * @param protobufBytes The protobuf-encoded ToRadio message bytes.
     * @throws IllegalArgumentException if protobufBytes is empty.
     */
    suspend fun writeToRadio(protobufBytes: ByteArray) {
        require(protobufBytes.isNotEmpty()) { "Cannot write empty protobuf message" }

        // Step 1: Frame with 4-byte big-endian length prefix
        val framedPacket = PacketFramer.frame(protobufBytes)

        // Step 2: Split into MTU-sized chunks
        val chunks = PacketFramer.splitForMtu(framedPacket, mtuPayloadSize)

        // Step 3: Write each chunk to TORADIO characteristic
        for ((index, chunk) in chunks.withIndex()) {
            try {
                peripheral.write(
                    MeshtasticConstants.toRadioCharacteristic,
                    chunk,
                    WriteType.WithResponse
                )
                Log.d(TAG, "Wrote chunk ${index + 1}/${chunks.size}: ${chunk.size} bytes")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write chunk ${index + 1}/${chunks.size}", e)
                throw e
            }
        }

        Log.d(TAG, "WriteToRadio complete: ${protobufBytes.size} payload bytes, " +
                "${framedPacket.size} framed bytes, ${chunks.size} chunk(s)")
    }

    /**
     * Encodes a ToRadio protobuf message to bytes.
     *
     * This is a documentation placeholder showing how Wire-generated classes are used.
     * In production, this would use the Wire ADAPTER:
     *
     * ```kotlin
     * fun encodeToRadio(message: ToRadio): ByteArray {
     *     return ToRadio.ADAPTER.encode(message)
     * }
     * ```
     *
     * @param messageBytes The raw protobuf bytes representing a ToRadio message.
     *        In production, this parameter would be a `ToRadio` object.
     * @return The protobuf-encoded byte array (identity function for raw bytes).
     */
    fun encodeToRadio(messageBytes: ByteArray): ByteArray {
        // In production with Wire-generated classes:
        // return ToRadio.ADAPTER.encode(toRadioMessage)
        return messageBytes
    }

    /**
     * Decodes a FromRadio protobuf message from bytes.
     *
     * This is a documentation placeholder showing how Wire-generated classes are used.
     * In production, this would use the Wire ADAPTER:
     *
     * ```kotlin
     * fun decodeFromRadio(bytes: ByteArray): FromRadio {
     *     return FromRadio.ADAPTER.decode(bytes)
     * }
     * ```
     *
     * @param bytes The raw protobuf bytes received from the FROMRADIO characteristic.
     * @return The raw bytes (in production, would return a decoded `FromRadio` object).
     */
    fun decodeFromRadio(bytes: ByteArray): ByteArray {
        // In production with Wire-generated classes:
        // return FromRadio.ADAPTER.decode(bytes)
        return bytes
    }
}
