package io.github.jacekkardys.systemproof.examples.smsc;

public record MessageDispatch(String testMessageId, String sessionId, int sequenceNumber) {
}
