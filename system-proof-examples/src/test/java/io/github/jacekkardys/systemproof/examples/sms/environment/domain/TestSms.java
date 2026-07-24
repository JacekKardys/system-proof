package io.github.jacekkardys.systemproof.examples.sms.environment.domain;

import java.util.Objects;
import java.util.UUID;

public record TestSms(
    String id,
    String sourceAddress,
    String destinationAddress,
    String content
) {
    public TestSms {
        id = requireText(id, "message ID");
        sourceAddress = requireText(sourceAddress, "source address");
        destinationAddress = requireText(destinationAddress, "destination address");
        content = requireText(content, "content");
    }

    public static TestSms unique() {
        String id = "SYSTEM-PROOF-PERSISTENCE-" + UUID.randomUUID();
        return new TestSms(id, "999000000001", "99001", id);
    }

    private static String requireText(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }
}
