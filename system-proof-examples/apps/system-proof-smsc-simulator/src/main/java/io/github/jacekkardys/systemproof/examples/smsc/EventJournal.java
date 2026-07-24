package io.github.jacekkardys.systemproof.examples.smsc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

final class EventJournal {
    private final AtomicLong index = new AtomicLong();
    private final CopyOnWriteArrayList<SmscEvent> events = new CopyOnWriteArrayList<>();

    void append(
        SmscEventType type,
        String sessionId,
        String testMessageId,
        Integer sequenceNumber,
        Integer commandStatus,
        Map<String, String> details
    ) {
        events.add(new SmscEvent(
            index.incrementAndGet(),
            type,
            sessionId,
            testMessageId,
            sequenceNumber,
            commandStatus,
            Instant.now(),
            details
        ));
    }

    List<SmscEvent> forMessage(String testMessageId) {
        return events.stream().filter(event -> testMessageId.equals(event.testMessageId())).toList();
    }
}
