package io.github.jacekkardys.systemproof.smpp;

/** SMPP-specific hard limits applied inside the gateway's directional buffer limits. */
public record SmppProtocolLimits(
    int maximumPduBytes,
    int maximumOutstandingDeliveries,
    int maximumShortMessageBytes
) {
    static final int PDU_HEADER_BYTES = 16;
    // Supported deliver_sm metadata: empty/default fields and bounded addresses.
    static final int MINIMUM_DELIVER_SM_METADATA_BYTES = 19;
    static final int MAXIMUM_DELIVER_SM_METADATA_BYTES = 57;
    // Conservative map accounting: boxed key, node/table share, and exchange reference.
    private static final int ACCOUNTED_BYTES_PER_OUTSTANDING_DELIVERY = 128;
    private static final int DEFAULT_OUTSTANDING_MEMORY_BUDGET_BYTES = 8 * 1024;
    private static final int MAXIMUM_OUTSTANDING_MEMORY_BUDGET_BYTES = 64 * 1024;
    private static final int DEFAULT_SHORT_MESSAGE_BYTES = 140;

    public static final int MAXIMUM_SHORT_MESSAGE_BYTES = 254;
    public static final int MAXIMUM_PDU_BYTES = PDU_HEADER_BYTES
        + MAXIMUM_DELIVER_SM_METADATA_BYTES
        + MAXIMUM_SHORT_MESSAGE_BYTES;
    public static final int MAXIMUM_OUTSTANDING_DELIVERIES =
        MAXIMUM_OUTSTANDING_MEMORY_BUDGET_BYTES
            / ACCOUNTED_BYTES_PER_OUTSTANDING_DELIVERY;

    public SmppProtocolLimits {
        if (maximumPduBytes < 16 || maximumPduBytes > MAXIMUM_PDU_BYTES) {
            throw new IllegalArgumentException(
                "maximumPduBytes must be between 16 and " + MAXIMUM_PDU_BYTES
            );
        }
        if (maximumOutstandingDeliveries < 1
            || maximumOutstandingDeliveries > MAXIMUM_OUTSTANDING_DELIVERIES) {
            throw new IllegalArgumentException(
                "maximumOutstandingDeliveries must be between 1 and "
                    + MAXIMUM_OUTSTANDING_DELIVERIES
            );
        }
        if (maximumShortMessageBytes < 1
            || maximumShortMessageBytes > MAXIMUM_SHORT_MESSAGE_BYTES) {
            throw new IllegalArgumentException(
                "maximumShortMessageBytes must be between 1 and "
                    + MAXIMUM_SHORT_MESSAGE_BYTES
            );
        }
    }

    /** Returns the deliberately narrow defaults for the characterized reference flow. */
    public static SmppProtocolLimits defaults() {
        return new SmppProtocolLimits(
            PDU_HEADER_BYTES
                + MAXIMUM_DELIVER_SM_METADATA_BYTES
                + DEFAULT_SHORT_MESSAGE_BYTES,
            DEFAULT_OUTSTANDING_MEMORY_BUDGET_BYTES
                / ACCOUNTED_BYTES_PER_OUTSTANDING_DELIVERY,
            DEFAULT_SHORT_MESSAGE_BYTES
        );
    }
}
