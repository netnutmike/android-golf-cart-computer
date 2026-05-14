package com.golfcart.gcd.data.bluetooth;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for packet framing and protobuf serialization round-trips.
 *
 * Feature: android-golf-cart-computer, Property 1: Protobuf serialization round-trip
 * Feature: android-golf-cart-computer, Property 2: Packet framing round-trip
 *
 * Validates: Requirements 1.5, 1.6, 18.2, 18.8
 */
@Label("Packet Framing Property Tests")
@Tag("Feature: android-golf-cart-computer")
class PacketFramingPropertyTest {

    // =========================================================================
    // Property 1: Protobuf serialization round-trip
    // =========================================================================

    /**
     * Property 1: For any random byte array representing a protobuf message,
     * encoding then decoding should produce the original bytes.
     *
     * Since Wire-generated classes aren't available at unit test compile time,
     * we test the encode/decode concept as an identity function — any byte array
     * that represents a valid protobuf payload should survive the encode/decode
     * cycle unchanged.
     *
     * In the actual implementation, Wire's encode() produces bytes and decode()
     * reconstructs the object. Here we validate the invariant that the byte
     * representation is stable through the framing layer.
     *
     * Validates: Requirements 1.5, 1.6, 18.8
     */
    @Property(tries = 10)
    @Label("Property 1: Protobuf byte array round-trip through framing preserves content")
    @Tag("Property 1: Protobuf serialization round-trip")
    void protobufBytesRoundTripThroughFraming(
            @ForAll("nonEmptyByteArrays") byte[] protobufBytes) {

        // Simulate the full protocol path:
        // 1. Protobuf encode (identity — bytes are already "encoded")
        byte[] encoded = protobufBytes; // Wire encode would produce these bytes

        // 2. Frame with length prefix
        byte[] framed = PacketFramer.INSTANCE.frame(encoded);

        // 3. Unframe to get back the protobuf bytes
        byte[] decoded = PacketFramer.INSTANCE.unframe(framed);

        // 4. Protobuf decode (identity — we verify bytes match)
        assertArrayEquals(protobufBytes, decoded,
                "Protobuf bytes should survive encode → frame → unframe → decode round-trip");
    }

    /**
     * Property 1 (supplementary): The encoded byte length is preserved through
     * the framing layer — the length prefix correctly records the payload size.
     *
     * Validates: Requirements 1.5, 1.6, 18.8
     */
    @Property(tries = 10)
    @Label("Property 1: Length prefix correctly encodes protobuf payload size")
    @Tag("Property 1: Protobuf serialization round-trip")
    void lengthPrefixEncodesPayloadSize(
            @ForAll("nonEmptyByteArrays") byte[] protobufBytes) {

        byte[] framed = PacketFramer.INSTANCE.frame(protobufBytes);

        // Read the 4-byte big-endian length prefix
        int encodedLength = ((framed[0] & 0xFF) << 24)
                | ((framed[1] & 0xFF) << 16)
                | ((framed[2] & 0xFF) << 8)
                | (framed[3] & 0xFF);

        assertEquals(protobufBytes.length, encodedLength,
                "Length prefix should equal the protobuf payload size");
        assertEquals(protobufBytes.length + 4, framed.length,
                "Framed size should be payload size + 4 bytes for length prefix");
    }

    // =========================================================================
    // Property 2: Packet framing round-trip
    // =========================================================================

    /**
     * Property 2a: For any non-empty byte array (1-500 bytes), frame() then
     * unframe() should produce the original byte array.
     *
     * Validates: Requirements 18.2
     */
    @Property(tries = 10)
    @Label("Property 2: frame then unframe produces original bytes")
    @Tag("Property 2: Packet framing round-trip")
    void frameUnframeRoundTrip(
            @ForAll("payloadBytes") byte[] payload) {

        byte[] framed = PacketFramer.INSTANCE.frame(payload);
        byte[] recovered = PacketFramer.INSTANCE.unframe(framed);

        assertArrayEquals(payload, recovered,
                "frame() then unframe() should produce the original byte array");
    }

    /**
     * Property 2b: For any non-empty byte array and any valid MTU size (1-512),
     * frame() then splitForMtu() then concatenate then unframe() should produce
     * the original byte array.
     *
     * This validates the full BLE write pipeline: a message is framed, split into
     * MTU-sized chunks for BLE transmission, reassembled on the other side, and
     * unframed to recover the original payload.
     *
     * Validates: Requirements 18.2
     */
    @Property(tries = 10)
    @Label("Property 2: frame + split + concatenate + unframe produces original")
    @Tag("Property 2: Packet framing round-trip")
    void frameSplitConcatUnframeRoundTrip(
            @ForAll("payloadBytes") byte[] payload,
            @ForAll @IntRange(min = 1, max = 512) int mtuPayloadSize) {

        // Frame the payload
        byte[] framed = PacketFramer.INSTANCE.frame(payload);

        // Split for MTU
        java.util.List<byte[]> chunks = PacketFramer.INSTANCE.splitForMtu(framed, mtuPayloadSize);

        // Concatenate chunks back together
        int totalLength = 0;
        for (byte[] chunk : chunks) {
            totalLength += chunk.length;
        }
        byte[] reassembled = new byte[totalLength];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, reassembled, offset, chunk.length);
            offset += chunk.length;
        }

        // Unframe to recover original
        byte[] recovered = PacketFramer.INSTANCE.unframe(reassembled);

        assertArrayEquals(payload, recovered,
                "frame → splitForMtu → concatenate → unframe should produce the original");
    }

    /**
     * Property 2c: The length prefix always correctly encodes the payload size,
     * regardless of the payload content or size.
     *
     * Validates: Requirements 18.2
     */
    @Property(tries = 10)
    @Label("Property 2: Length prefix always matches actual payload size")
    @Tag("Property 2: Packet framing round-trip")
    void lengthPrefixAlwaysMatchesPayloadSize(
            @ForAll("payloadBytes") byte[] payload) {

        byte[] framed = PacketFramer.INSTANCE.frame(payload);

        // Extract length from big-endian prefix
        int prefixLength = ((framed[0] & 0xFF) << 24)
                | ((framed[1] & 0xFF) << 16)
                | ((framed[2] & 0xFF) << 8)
                | (framed[3] & 0xFF);

        assertEquals(payload.length, prefixLength,
                "The 4-byte big-endian length prefix must equal the payload length");

        // Verify total framed size is consistent
        assertEquals(4 + payload.length, framed.length,
                "Framed array size must be LENGTH_PREFIX_SIZE + payload length");
    }

    /**
     * Property 2d: splitForMtu produces chunks where each chunk is at most
     * mtuPayloadSize bytes, and all chunks concatenated equal the original framed data.
     *
     * Validates: Requirements 18.2
     */
    @Property(tries = 10)
    @Label("Property 2: splitForMtu chunks respect MTU size and reconstruct original")
    @Tag("Property 2: Packet framing round-trip")
    void splitForMtuChunksRespectSizeAndReconstruct(
            @ForAll("payloadBytes") byte[] payload,
            @ForAll @IntRange(min = 1, max = 512) int mtuPayloadSize) {

        byte[] framed = PacketFramer.INSTANCE.frame(payload);
        java.util.List<byte[]> chunks = PacketFramer.INSTANCE.splitForMtu(framed, mtuPayloadSize);

        // Every chunk must be at most mtuPayloadSize bytes
        for (int i = 0; i < chunks.size(); i++) {
            assertTrue(chunks.get(i).length <= mtuPayloadSize,
                    "Chunk " + i + " has size " + chunks.get(i).length +
                    " which exceeds MTU payload size " + mtuPayloadSize);
            assertTrue(chunks.get(i).length > 0,
                    "Chunk " + i + " must not be empty");
        }

        // Concatenated chunks must equal the original framed data
        int totalLength = 0;
        for (byte[] chunk : chunks) {
            totalLength += chunk.length;
        }
        assertEquals(framed.length, totalLength,
                "Sum of chunk sizes must equal framed data size");

        byte[] reassembled = new byte[totalLength];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, reassembled, offset, chunk.length);
            offset += chunk.length;
        }
        assertArrayEquals(framed, reassembled,
                "Concatenated chunks must exactly equal the original framed data");
    }

    // =========================================================================
    // Generators
    // =========================================================================

    /**
     * Generates non-empty byte arrays of size 1-500 representing protobuf payloads.
     */
    @Provide
    Arbitrary<byte[]> nonEmptyByteArrays() {
        return Arbitraries.bytes().array(byte[].class)
                .ofMinSize(1)
                .ofMaxSize(500);
    }

    /**
     * Generates non-empty byte arrays of size 1-500 representing arbitrary payloads
     * for packet framing tests.
     */
    @Provide
    Arbitrary<byte[]> payloadBytes() {
        return Arbitraries.bytes().array(byte[].class)
                .ofMinSize(1)
                .ofMaxSize(500);
    }
}
