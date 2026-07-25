package io.github.jacekkardys.systemproof.examples.ingestion;

public record SmsIngestionCommand(
    String externalMessageId,
    String sourceAddress,
    String destinationAddress,
    String content
) {
}
