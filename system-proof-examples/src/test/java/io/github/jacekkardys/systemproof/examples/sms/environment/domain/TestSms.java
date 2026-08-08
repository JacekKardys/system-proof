package io.github.jacekkardys.systemproof.examples.sms.environment.domain;

import java.util.Objects;
import java.util.UUID;

public record TestSms(
    String id,
    String sourceAddress,
    String destinationAddress,
    String content
) {
    private static final String DESTINATION_CANARY = "990019900199001";

    public TestSms {
        id = requireText(id, "message ID");
        sourceAddress = requireText(sourceAddress, "source address");
        destinationAddress = requireText(destinationAddress, "destination address");
        content = requireText(content, "content");
    }

    public static TestSms unique() {
        String id = "SYSTEM-PROOF-PERSISTENCE-" + UUID.randomUUID();
        return new TestSms(id, "999000000001", DESTINATION_CANARY, id);
    }

    /** Creates one logical proof message carrying a caller-owned high-entropy discriminator. */
    public static TestSms forProof(String proofDiscriminator) {
        proofDiscriminator = requireText(proofDiscriminator, "proof discriminator");
        if (proofDiscriminator.length() < 32) {
            throw new IllegalArgumentException(
                "proof discriminator must contain at least 32 characters"
            );
        }
        return new TestSms(
            "proof-sms-" + UUID.randomUUID(),
            "999000000001",
            DESTINATION_CANARY,
            "SYSTEM-PROOF-PERSISTENCE-" + proofDiscriminator
        );
    }

    private static String requireText(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }
}
