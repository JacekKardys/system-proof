package pl.gov.il.test.aml.ingestion.environment.component.smsc;

/** Correlated deliver_sm and deliver_sm_resp observation from the simulator journal. */
public record SmscResponse(
    int deliverSmCount,
    int responseCount,
    Integer deliveredSequenceNumber,
    String deliveredSessionId,
    long deliveredEventIndex,
    Integer sequenceNumber,
    String sessionId,
    Integer commandStatus,
    long eventIndex
) {}
