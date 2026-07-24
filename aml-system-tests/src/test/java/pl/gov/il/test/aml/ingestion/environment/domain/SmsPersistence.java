package pl.gov.il.test.aml.ingestion.environment.domain;

public record SmsPersistence(
    long rawCount,
    long outboxCount,
    String rawId,
    String outboxAggregateId,
    String externalMessageId,
    String sourceAddress,
    String destinationAddress,
    String content
) {}
