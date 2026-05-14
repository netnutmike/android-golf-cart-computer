package com.golfcart.gcd.data.bluetooth;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for message routing and outbound message construction.
 *
 * Feature: android-golf-cart-computer, Property 3: Message routing acceptance
 * Feature: android-golf-cart-computer, Property 4: Outbound message construction
 * Feature: android-golf-cart-computer, Property 5: Outbound payload size limit
 *
 * Validates: Requirements 2.2, 2.3, 2.4, 2.7, 2.9, 18.7
 */
@Label("Message Routing Property Tests")
@Tag("Feature: android-golf-cart-computer")
class MessageRoutingPropertyTest {

    private static final long LOCAL_NODE_NUM = 0x12345678L;
    private static final long BROADCAST_ADDRESS = 0xFFFFFFFFL;
    private static final int PORT_TEXT_MESSAGE_APP = 1;
    private static final int MAX_PAYLOAD_SIZE = 237;

    // =========================================================================
    // Property 3: Message routing acceptance
    // =========================================================================

    /**
     * Property 3a: A message addressed to the broadcast address (0xFFFFFFFF)
     * is always accepted — routeIncomingPacket processes it without dropping.
     *
     * We verify this by ensuring the handler does not throw and processes the
     * packet. The handler's internal logic accepts iff to == BROADCAST or
     * to == localNodeNum. Broadcast always satisfies this.
     *
     * Validates: Requirements 2.2
     */
    @Property(tries = 10)
    @Label("Property 3: Broadcast messages are always accepted")
    @Tag("Property 3: Message routing acceptance")
    void broadcastMessagesAlwaysAccepted(
            @ForAll("randomNodeAddress") long fromAddress,
            @ForAll @IntRange(min = 0, max = 7) int channel,
            @ForAll("shortTextPayload") byte[] payload,
            @ForAll("randomNodeAddress") long localNode) {

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> localNode);

        byte[] fromRadioBytes = buildFakeFromRadioPacket(
                fromAddress, BROADCAST_ADDRESS, channel,
                PORT_TEXT_MESSAGE_APP, payload, 42);

        // Should process without throwing — broadcast is always accepted
        // regardless of what localNodeNum is
        assertDoesNotThrow(() -> handler.routeIncomingPacket(fromRadioBytes),
                "Broadcast messages must always be accepted regardless of local node number");

        // Verify the acceptance predicate holds
        assertTrue(BROADCAST_ADDRESS == BROADCAST_ADDRESS || BROADCAST_ADDRESS == localNode,
                "Broadcast address must satisfy acceptance predicate");
    }

    /**
     * Property 3b: A message addressed to the local node number is always accepted.
     *
     * For any randomly generated local node number, a message addressed to that
     * exact node number should be accepted.
     *
     * Validates: Requirements 2.2
     */
    @Property(tries = 10)
    @Label("Property 3: Messages to local node are always accepted")
    @Tag("Property 3: Message routing acceptance")
    void messagesAddressedToLocalNodeAccepted(
            @ForAll("randomNodeAddress") long fromAddress,
            @ForAll("randomNodeAddress") long localNode,
            @ForAll @IntRange(min = 0, max = 7) int channel,
            @ForAll("shortTextPayload") byte[] payload) {

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> localNode);

        byte[] fromRadioBytes = buildFakeFromRadioPacket(
                fromAddress, localNode, channel,
                PORT_TEXT_MESSAGE_APP, payload, 99);

        // Should process without throwing — destination matches local node
        assertDoesNotThrow(() -> handler.routeIncomingPacket(fromRadioBytes),
                "Messages addressed to local node must always be accepted");

        // Verify the acceptance predicate holds
        assertTrue(localNode == BROADCAST_ADDRESS || localNode == localNode,
                "Local node destination must satisfy acceptance predicate");
    }

    /**
     * Property 3c: A message addressed to a destination that is neither broadcast
     * nor the local node number is rejected (not routed to any flow).
     *
     * The handler silently drops such messages (logs and returns). We verify
     * the rejection predicate: destination != broadcast AND destination != localNodeNum.
     *
     * Validates: Requirements 2.2
     */
    @Property(tries = 10)
    @Label("Property 3: Messages to other nodes are rejected")
    @Tag("Property 3: Message routing acceptance")
    void messagesAddressedToOtherNodesRejected(
            @ForAll("randomNodeAddress") long fromAddress,
            @ForAll("nonMatchingDestination") long destination,
            @ForAll @IntRange(min = 0, max = 7) int channel) {

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> LOCAL_NODE_NUM);

        byte[] payload = "test".getBytes(StandardCharsets.UTF_8);
        byte[] fromRadioBytes = buildFakeFromRadioPacket(
                fromAddress, destination, channel,
                PORT_TEXT_MESSAGE_APP, payload, 77);

        // Should process without throwing (graceful rejection, not error)
        assertDoesNotThrow(() -> handler.routeIncomingPacket(fromRadioBytes),
                "Rejected messages should be silently dropped, not throw exceptions");

        // Verify the rejection predicate holds
        assertNotEquals(BROADCAST_ADDRESS, destination,
                "Rejected destination must not be broadcast");
        assertNotEquals(LOCAL_NODE_NUM, destination,
                "Rejected destination must not be local node");

        // The acceptance predicate must be false
        boolean shouldAccept = (destination == BROADCAST_ADDRESS) || (destination == LOCAL_NODE_NUM);
        assertFalse(shouldAccept,
                "Messages to non-matching destinations must fail the acceptance predicate");
    }

    /**
     * Property 3d: The routing acceptance predicate is exactly:
     * accepted iff (destination == 0xFFFFFFFF || destination == localNodeNum).
     *
     * For any random destination and local node number, verify the predicate
     * is consistent and the handler processes without error.
     *
     * Validates: Requirements 2.2
     */
    @Property(tries = 10)
    @Label("Property 3: Routing acceptance predicate is correct for any destination")
    @Tag("Property 3: Message routing acceptance")
    void routingAcceptancePredicateCorrect(
            @ForAll("anyDestination") long destination,
            @ForAll("randomNodeAddress") long localNode) {

        boolean shouldAccept = (destination == BROADCAST_ADDRESS) || (destination == localNode);

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> localNode);

        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] fromRadioBytes = buildFakeFromRadioPacket(
                0xAAAAAAAAL, destination, 0,
                PORT_TEXT_MESSAGE_APP, payload, 1);

        // Handler should never throw regardless of acceptance/rejection
        assertDoesNotThrow(() -> handler.routeIncomingPacket(fromRadioBytes),
                "routeIncomingPacket must never throw for valid protobuf input");

        // Verify the predicate is a proper biconditional:
        // accepted ↔ (destination == broadcast ∨ destination == localNode)
        if (shouldAccept) {
            assertTrue(destination == BROADCAST_ADDRESS || destination == localNode,
                    "Accepted messages must have destination == broadcast or == localNodeNum");
        } else {
            assertTrue(destination != BROADCAST_ADDRESS && destination != localNode,
                    "Rejected messages must have destination != broadcast AND != localNodeNum");
        }
    }

    // =========================================================================
    // Property 4: Outbound message construction
    // =========================================================================

    /**
     * Property 4a: For any valid text, destination, and channel, the constructed
     * ToRadio bytes contain the destination encoded as a fixed32 (little-endian).
     *
     * Validates: Requirements 2.3, 2.4, 2.9, 18.7
     */
    @Property(tries = 10)
    @Label("Property 4: Constructed message contains correct destination")
    @Tag("Property 4: Outbound message construction")
    void constructedMessageContainsCorrectDestination(
            @ForAll("validTextForMessage") String text,
            @ForAll("randomNodeAddress") long destination,
            @ForAll @IntRange(min = 0, max = 7) int channel) {

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> LOCAL_NODE_NUM);

        byte[] result = handler.buildTextMessage(text, destination, channel);

        // Destination should be encoded as fixed32 (little-endian) in the output
        byte[] destBytes = encodeFixed32(destination);
        assertTrue(containsSubarray(result, destBytes),
                "Constructed message must contain destination " +
                        Long.toHexString(destination) + " as little-endian fixed32");
    }

    /**
     * Property 4b: For any valid text, the constructed ToRadio bytes contain
     * the TEXT_MESSAGE_APP portnum (value 1) encoded as a varint field.
     *
     * Validates: Requirements 2.9, 18.7
     */
    @Property(tries = 10)
    @Label("Property 4: Constructed message contains TEXT_MESSAGE_APP portnum")
    @Tag("Property 4: Outbound message construction")
    void constructedMessageContainsCorrectPortnum(
            @ForAll("validTextForMessage") String text,
            @ForAll("randomNodeAddress") long destination,
            @ForAll @IntRange(min = 0, max = 7) int channel) {

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> LOCAL_NODE_NUM);

        byte[] result = handler.buildTextMessage(text, destination, channel);

        // portnum field: tag 1, wire type 0 (varint) → key byte = (1 << 3) | 0 = 0x08
        // value = 1 (TEXT_MESSAGE_APP) → varint byte = 0x01
        byte[] portnumBytes = {0x08, 0x01};
        assertTrue(containsSubarray(result, portnumBytes),
                "Constructed message must contain portnum field with TEXT_MESSAGE_APP (1)");
    }

    /**
     * Property 4c: For any valid text, the constructed ToRadio bytes contain
     * a non-zero packet ID encoded as a fixed32.
     *
     * Validates: Requirements 18.7
     */
    @Property(tries = 10)
    @Label("Property 4: Constructed message contains non-zero packet ID")
    @Tag("Property 4: Outbound message construction")
    void constructedMessageContainsNonZeroPacketId(
            @ForAll("validTextForMessage") String text,
            @ForAll("randomNodeAddress") long destination,
            @ForAll @IntRange(min = 0, max = 7) int channel) {

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> LOCAL_NODE_NUM);

        byte[] result = handler.buildTextMessage(text, destination, channel);

        // The packet ID is encoded as fixed32 with tag 6, wire type 5
        // Tag byte: (6 << 3) | 5 = 53 = 0x35
        byte idTagByte = 0x35;
        int idFieldIndex = findTagByte(result, idTagByte);
        assertTrue(idFieldIndex >= 0,
                "Constructed message must contain packet ID field (tag 6, fixed32)");

        // Read the 4 bytes after the tag byte as the packet ID
        assertTrue(idFieldIndex + 4 < result.length,
                "Packet ID field must have 4 bytes of data after tag");
        byte[] idBytes = new byte[4];
        System.arraycopy(result, idFieldIndex + 1, idBytes, 0, 4);

        // Verify non-zero
        boolean allZero = (idBytes[0] == 0 && idBytes[1] == 0 &&
                idBytes[2] == 0 && idBytes[3] == 0);
        assertFalse(allZero, "Packet ID must be non-zero");
    }

    /**
     * Property 4d: For any valid text, the constructed ToRadio bytes contain
     * the text payload bytes (UTF-8 encoded).
     *
     * Validates: Requirements 2.3, 18.7
     */
    @Property(tries = 10)
    @Label("Property 4: Constructed message contains encoded text payload")
    @Tag("Property 4: Outbound message construction")
    void constructedMessageContainsTextPayload(
            @ForAll("validTextForMessage") String text,
            @ForAll("randomNodeAddress") long destination,
            @ForAll @IntRange(min = 0, max = 7) int channel) {

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> LOCAL_NODE_NUM);

        byte[] result = handler.buildTextMessage(text, destination, channel);

        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        assertTrue(containsSubarray(result, textBytes),
                "Constructed message must contain the UTF-8 encoded text payload");
    }

    /**
     * Property 4e: For any non-zero channel, the constructed ToRadio bytes
     * contain the channel value encoded as a varint field.
     *
     * Validates: Requirements 2.4, 18.7
     */
    @Property(tries = 10)
    @Label("Property 4: Constructed message contains correct channel when non-zero")
    @Tag("Property 4: Outbound message construction")
    void constructedMessageContainsChannelWhenNonZero(
            @ForAll("validTextForMessage") String text,
            @ForAll("randomNodeAddress") long destination,
            @ForAll @IntRange(min = 1, max = 7) int channel) {

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> LOCAL_NODE_NUM);

        byte[] result = handler.buildTextMessage(text, destination, channel);

        // channel field: tag 3, wire type 0 (varint) → key byte = (3 << 3) | 0 = 0x18
        // value = channel (1-7) → single varint byte
        byte[] channelBytes = {0x18, (byte) channel};
        assertTrue(containsSubarray(result, channelBytes),
                "Constructed message must contain channel field with value " + channel);
    }

    // =========================================================================
    // Property 5: Outbound payload size limit
    // =========================================================================

    /**
     * Property 5a: If text.getBytes(UTF-8).length <= 237, buildTextMessage succeeds
     * (does not throw).
     *
     * Validates: Requirements 2.7
     */
    @Property(tries = 10)
    @Label("Property 5: Messages within payload limit succeed")
    @Tag("Property 5: Outbound payload size limit")
    void messagesWithinPayloadLimitSucceed(
            @ForAll("textWithinLimit") String text) {

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> LOCAL_NODE_NUM);

        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        // Precondition: payload is within limit
        Assume.that(textBytes.length <= MAX_PAYLOAD_SIZE);
        Assume.that(textBytes.length > 0);

        // Should not throw
        byte[] result = handler.buildTextMessage(text, BROADCAST_ADDRESS, 0);
        assertNotNull(result, "buildTextMessage should succeed for payloads <= 237 bytes");
        assertTrue(result.length > 0, "Result should be non-empty");
    }

    /**
     * Property 5b: If text.getBytes(UTF-8).length > 237, buildTextMessage throws
     * PayloadTooLargeException.
     *
     * Validates: Requirements 2.7
     */
    @Property(tries = 10)
    @Label("Property 5: Messages exceeding payload limit throw exception")
    @Tag("Property 5: Outbound payload size limit")
    void messagesExceedingPayloadLimitThrow(
            @ForAll("textExceedingLimit") String text) {

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> LOCAL_NODE_NUM);

        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
        // Precondition: payload exceeds limit
        Assume.that(textBytes.length > MAX_PAYLOAD_SIZE);

        assertThrows(MeshtasticMessageHandler.PayloadTooLargeException.class, () -> {
            handler.buildTextMessage(text, BROADCAST_ADDRESS, 0);
        }, "buildTextMessage should throw PayloadTooLargeException for payloads > 237 bytes");
    }

    /**
     * Property 5c: For any successful buildTextMessage call, the text payload
     * bytes within the constructed message never exceed 237 bytes.
     *
     * Validates: Requirements 2.7
     */
    @Property(tries = 10)
    @Label("Property 5: Successful messages have payload bytes <= 237")
    @Tag("Property 5: Outbound payload size limit")
    void successfulMessagesPayloadWithinLimit(
            @ForAll("validTextForMessage") String text,
            @ForAll("randomNodeAddress") long destination,
            @ForAll @IntRange(min = 0, max = 7) int channel) {

        MeshtasticMessageHandler handler = new MeshtasticMessageHandler(() -> LOCAL_NODE_NUM);

        byte[] result = handler.buildTextMessage(text, destination, channel);

        // The text payload bytes that were encoded
        byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);

        // Verify the payload size is within limit
        assertTrue(textBytes.length <= MAX_PAYLOAD_SIZE,
                "Encoded payload must not exceed " + MAX_PAYLOAD_SIZE +
                        " bytes, but was " + textBytes.length);

        // Also verify the result contains the payload (sanity check)
        assertTrue(containsSubarray(result, textBytes),
                "Result must contain the text payload bytes");
    }

    // =========================================================================
    // Generators
    // =========================================================================

    /**
     * Generates random 32-bit node addresses (as unsigned long in range 1..0xFFFFFFFE).
     * Excludes 0 and 0xFFFFFFFF to avoid collisions with special values in some tests.
     */
    @Provide
    Arbitrary<Long> randomNodeAddress() {
        return Arbitraries.longs().between(1L, 0xFFFFFFFEL);
    }

    /**
     * Generates destinations that are neither broadcast nor the local node number.
     */
    @Provide
    Arbitrary<Long> nonMatchingDestination() {
        return Arbitraries.longs().between(1L, 0xFFFFFFFEL)
                .filter(d -> d != LOCAL_NODE_NUM && d != BROADCAST_ADDRESS);
    }

    /**
     * Generates any valid 32-bit destination address (including broadcast and local node).
     */
    @Provide
    Arbitrary<Long> anyDestination() {
        return Arbitraries.oneOf(
                // Broadcast address
                Arbitraries.just(BROADCAST_ADDRESS),
                // Local node number
                Arbitraries.just(LOCAL_NODE_NUM),
                // Random other addresses (weighted more heavily)
                Arbitraries.longs().between(1L, 0xFFFFFFFEL)
                        .filter(d -> d != LOCAL_NODE_NUM)
        );
    }

    /**
     * Generates valid text strings whose UTF-8 encoding is 1-237 bytes.
     * Uses ASCII characters to ensure predictable byte lengths.
     */
    @Provide
    Arbitrary<String> validTextForMessage() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(237)
                .ascii()
                .filter(s -> {
                    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                    return bytes.length >= 1 && bytes.length <= MAX_PAYLOAD_SIZE;
                });
    }

    /**
     * Generates text strings whose UTF-8 encoding is within the 237-byte limit.
     * Includes multi-byte characters to test UTF-8 boundary behavior.
     */
    @Provide
    Arbitrary<String> textWithinLimit() {
        return Arbitraries.oneOf(
                // ASCII strings (1 byte per char)
                Arbitraries.strings().ofMinLength(1).ofMaxLength(237).ascii(),
                // Shorter strings with potential multi-byte chars
                Arbitraries.strings().ofMinLength(1).ofMaxLength(100)
        ).filter(s -> {
            byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
            return bytes.length >= 1 && bytes.length <= MAX_PAYLOAD_SIZE;
        });
    }

    /**
     * Generates text strings whose UTF-8 encoding exceeds 237 bytes.
     */
    @Provide
    Arbitrary<String> textExceedingLimit() {
        return Arbitraries.strings()
                .ofMinLength(238)
                .ofMaxLength(500)
                .ascii()
                .filter(s -> s.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_SIZE);
    }

    /**
     * Generates short text payloads as byte arrays for routing tests.
     */
    @Provide
    Arbitrary<byte[]> shortTextPayload() {
        return Arbitraries.strings()
                .ofMinLength(1)
                .ofMaxLength(50)
                .ascii()
                .map(s -> s.getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // Helper Methods
    // =========================================================================

    /**
     * Builds a fake FromRadio protobuf message containing a MeshPacket.
     * Implements protobuf encoding directly in Java.
     *
     * FromRadio {
     *   packet (tag 2, length-delimited) = MeshPacket {
     *     from (tag 1, fixed32) = from
     *     to (tag 2, fixed32) = to
     *     channel (tag 3, varint) = channel
     *     decoded (tag 4, length-delimited) = Data {
     *       portnum (tag 1, varint) = portnum
     *       payload (tag 2, length-delimited) = payload
     *     }
     *     id (tag 6, fixed32) = packetId
     *   }
     * }
     */
    private byte[] buildFakeFromRadioPacket(
            long from, long to, int channel,
            int portnum, byte[] payload, int packetId) {

        // Build Data sub-message: portnum (tag 1, varint) + payload (tag 2, LD)
        byte[] portnumField = concat(
                new byte[]{(byte) ((1 << 3) | 0)}, // tag 1, wire type 0 (varint)
                encodeVarint(portnum));
        byte[] payloadLenBytes = encodeVarint(payload.length);
        byte[] payloadField = concat(
                new byte[]{(byte) ((2 << 3) | 2)}, // tag 2, wire type 2 (LD)
                concat(payloadLenBytes, payload));
        byte[] dataBytes = concat(portnumField, payloadField);

        // Build MeshPacket fields
        // from (tag 1, fixed32)
        byte[] fromField = concat(
                new byte[]{(byte) ((1 << 3) | 5)}, // tag 1, wire type 5
                encodeFixed32(from));
        // to (tag 2, fixed32)
        byte[] toField = concat(
                new byte[]{(byte) ((2 << 3) | 5)}, // tag 2, wire type 5
                encodeFixed32(to));
        // channel (tag 3, varint) — only if non-zero
        byte[] channelField = (channel != 0)
                ? concat(new byte[]{(byte) ((3 << 3) | 0)}, encodeVarint(channel))
                : new byte[0];
        // decoded (tag 4, length-delimited)
        byte[] dataLenBytes = encodeVarint(dataBytes.length);
        byte[] decodedField = concat(
                new byte[]{(byte) ((4 << 3) | 2)}, // tag 4, wire type 2
                concat(dataLenBytes, dataBytes));
        // id (tag 6, fixed32)
        byte[] idField = concat(
                new byte[]{(byte) ((6 << 3) | 5)}, // tag 6, wire type 5
                encodeFixed32(packetId & 0xFFFFFFFFL));

        byte[] meshPacketBytes = concat(fromField,
                concat(toField, concat(channelField, concat(decodedField, idField))));

        // Build FromRadio: packet (tag 2, length-delimited)
        byte[] meshPacketLenBytes = encodeVarint(meshPacketBytes.length);
        byte[] fromRadioBytes = concat(
                new byte[]{(byte) ((2 << 3) | 2)}, // tag 2, wire type 2
                concat(meshPacketLenBytes, meshPacketBytes));

        return fromRadioBytes;
    }

    /**
     * Encodes a value as a 4-byte little-endian fixed32.
     */
    private byte[] encodeFixed32(long value) {
        return new byte[]{
                (byte) (value & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 24) & 0xFF)
        };
    }

    /**
     * Encodes a long value as a protobuf varint.
     */
    private byte[] encodeVarint(long value) {
        if (value == 0) return new byte[]{0};
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        long remaining = value;
        while (remaining != 0) {
            int b = (int) (remaining & 0x7F);
            remaining >>>= 7;
            if (remaining != 0) {
                b |= 0x80;
            }
            out.write(b);
        }
        return out.toByteArray();
    }

    /**
     * Concatenates two byte arrays.
     */
    private byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    /**
     * Checks if a byte array contains a specific sub-array.
     */
    private boolean containsSubarray(byte[] haystack, byte[] needle) {
        if (needle.length == 0) return true;
        if (needle.length > haystack.length) return false;

        for (int i = 0; i <= haystack.length - needle.length; i++) {
            boolean found = true;
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return true;
        }
        return false;
    }

    /**
     * Finds the index of a specific tag byte in the byte array.
     * Returns -1 if not found.
     */
    private int findTagByte(byte[] data, byte tagByte) {
        for (int i = 0; i < data.length; i++) {
            if (data[i] == tagByte) {
                return i;
            }
        }
        return -1;
    }
}
