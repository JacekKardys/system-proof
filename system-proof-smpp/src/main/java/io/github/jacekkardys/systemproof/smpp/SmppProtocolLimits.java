package io.github.jacekkardys.systemproof.smpp;

/** SMPP-specific hard limits applied inside the gateway's directional buffer limits. */
public record SmppProtocolLimits(
    int maximumPduBytes,
    int maximumOutstandingDeliveries,
    int maximumShortMessageBytes
) {
    public static final int MAXIMUM_PDU_BYTES = 16 * 1024 * 1024;
    public static final int MAXIMUM_OUTSTANDING_DELIVERIES = 1_000_000;
    public static final int MAXIMUM_SHORT_MESSAGE_BYTES = 254;

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
        return new SmppProtocolLimits(64 * 1024, 1024, 140);
    }
}
