package io.github.jacekkardys.systemproof.examples.smsc;

import java.util.Arrays;
import java.util.Map;

public record SmsTestMessage(
    String testMessageId,
    String sourceAddress,
    String destinationAddress,
    byte[] payload,
    byte dataCoding,
    byte esmClass,
    byte priorityFlag,
    String validityPeriod,
    byte registeredDelivery,
    Map<Integer, byte[]> optionalParameters,
    Integer requestedSequenceNumber
) {
    public SmsTestMessage {
        requireText(testMessageId, "testMessageId");
        requireText(sourceAddress, "sourceAddress");
        requireText(destinationAddress, "destinationAddress");
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        if (payload.length > 254) {
            throw new IllegalArgumentException("payload must not exceed 254 bytes");
        }
        optionalParameters = optionalParameters == null ? Map.of() : Map.copyOf(optionalParameters);
        if (requestedSequenceNumber != null && requestedSequenceNumber <= 0) {
            throw new IllegalArgumentException("requestedSequenceNumber must be positive");
        }
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
