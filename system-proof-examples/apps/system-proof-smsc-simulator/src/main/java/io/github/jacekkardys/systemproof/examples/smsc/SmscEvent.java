package io.github.jacekkardys.systemproof.examples.smsc;

import java.time.Instant;
import java.util.Map;

public record SmscEvent(
    long eventIndex,
    SmscEventType eventType,
    String sessionId,
    String testMessageId,
    Integer sequenceNumber,
    Integer commandStatus,
    Instant occurredAt,
    Map<String, String> details
) {
    public SmscEvent {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
