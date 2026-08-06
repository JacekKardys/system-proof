package io.github.jacekkardys.systemproof.smpp;

import java.util.Objects;

/** Secret-safe typed evidence for complete PDUs in the characterized SMPP subset. */
public sealed interface SmppEvidence
    permits SmppEvidence.BindRequested, SmppEvidence.BindResponded,
        SmppEvidence.SessionControl, SmppEvidence.DeliverSmCompleted,
        SmppEvidence.DeliverSmResponseCompleted {

    /** Closed command set accepted by this adapter. */
    enum Command {
        BIND_TRANSCEIVER(0x00000009L),
        BIND_TRANSCEIVER_RESP(0x80000009L),
        DELIVER_SM(0x00000005L),
        DELIVER_SM_RESP(0x80000005L),
        ENQUIRE_LINK(0x00000015L),
        ENQUIRE_LINK_RESP(0x80000015L),
        UNBIND(0x00000006L),
        UNBIND_RESP(0x80000006L);

        private final long commandId;

        Command(long commandId) {
            this.commandId = commandId;
        }

        public long commandId() {
            return commandId;
        }
    }

    enum BindOutcome {
        ACCEPTED,
        REJECTED
    }

    enum Acknowledgement {
        POSITIVE,
        NEGATIVE
    }

    enum DataCoding {
        UCS2(8);

        private final int wireValue;

        DataCoding(int wireValue) {
            this.wireValue = wireValue;
        }

        public int wireValue() {
            return wireValue;
        }
    }

    Command command();

    long wireSequenceNumber();

    default long commandStatus() {
        return 0;
    }

    int pduByteCount();

    record BindRequested(
        long wireSequenceNumber,
        int pduByteCount
    ) implements SmppEvidence {
        public BindRequested {
            SmppEvidenceValidation.wireSequence(wireSequenceNumber);
            SmppEvidenceValidation.pduByteCount(pduByteCount);
        }

        @Override
        public Command command() {
            return Command.BIND_TRANSCEIVER;
        }
    }

    record BindResponded(
        long wireSequenceNumber,
        long commandStatus,
        BindOutcome outcome,
        int pduByteCount
    ) implements SmppEvidence {
        public BindResponded {
            SmppEvidenceValidation.wireSequence(wireSequenceNumber);
            SmppEvidenceValidation.commandStatus(commandStatus);
            outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            SmppEvidenceValidation.pduByteCount(pduByteCount);
            if ((commandStatus == 0) != (outcome == BindOutcome.ACCEPTED)) {
                throw new IllegalArgumentException(
                    "Bind outcome must agree with commandStatus"
                );
            }
        }

        @Override
        public Command command() {
            return Command.BIND_TRANSCEIVER_RESP;
        }
    }

    record SessionControl(
        Command command,
        long wireSequenceNumber,
        long commandStatus,
        int pduByteCount
    ) implements SmppEvidence {
        public SessionControl {
            command = Objects.requireNonNull(command, "command must not be null");
            if (command != Command.ENQUIRE_LINK
                && command != Command.ENQUIRE_LINK_RESP
                && command != Command.UNBIND
                && command != Command.UNBIND_RESP) {
                throw new IllegalArgumentException("Command is not an SMPP session control PDU");
            }
            SmppEvidenceValidation.wireSequence(wireSequenceNumber);
            SmppEvidenceValidation.commandStatus(commandStatus);
            SmppEvidenceValidation.pduByteCount(pduByteCount);
        }
    }

    record DeliverSmCompleted(
        SmppExchangeRef exchange,
        int pduByteCount,
        int bodyByteCount,
        int messageByteCount,
        DataCoding dataCoding,
        int esmClass
    ) implements SmppEvidence {
        public DeliverSmCompleted {
            exchange = Objects.requireNonNull(exchange, "exchange must not be null");
            SmppEvidenceValidation.pduByteCount(pduByteCount);
            if (bodyByteCount < 1 || bodyByteCount != pduByteCount - 16) {
                throw new IllegalArgumentException("bodyByteCount must match the PDU body");
            }
            if (messageByteCount < 1
                || messageByteCount > SmppProtocolLimits.MAXIMUM_SHORT_MESSAGE_BYTES) {
                throw new IllegalArgumentException("messageByteCount is outside SMPP limits");
            }
            dataCoding = Objects.requireNonNull(dataCoding, "dataCoding must not be null");
            if (esmClass < 0 || esmClass > 0xff) {
                throw new IllegalArgumentException("esmClass must be an unsigned byte");
            }
        }

        @Override
        public Command command() {
            return Command.DELIVER_SM;
        }

        @Override
        public long wireSequenceNumber() {
            return exchange.wireSequenceNumber();
        }
    }

    record DeliverSmResponseCompleted(
        SmppExchangeRef exchange,
        long commandStatus,
        Acknowledgement acknowledgement,
        int pduByteCount
    ) implements SmppEvidence {
        public DeliverSmResponseCompleted {
            exchange = Objects.requireNonNull(exchange, "exchange must not be null");
            SmppEvidenceValidation.commandStatus(commandStatus);
            acknowledgement = Objects.requireNonNull(
                acknowledgement,
                "acknowledgement must not be null"
            );
            SmppEvidenceValidation.pduByteCount(pduByteCount);
            if ((commandStatus == 0) != (acknowledgement == Acknowledgement.POSITIVE)) {
                throw new IllegalArgumentException(
                    "Acknowledgement must agree with commandStatus"
                );
            }
        }

        @Override
        public Command command() {
            return Command.DELIVER_SM_RESP;
        }

        @Override
        public long wireSequenceNumber() {
            return exchange.wireSequenceNumber();
        }
    }
}

final class SmppEvidenceValidation {
    private SmppEvidenceValidation() {}

    static void wireSequence(long value) {
        if (value < 1 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException(
                "wireSequenceNumber must be an unsigned non-zero 32-bit value"
            );
        }
    }

    static void commandStatus(long value) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException("commandStatus must be an unsigned 32-bit value");
        }
    }

    static void pduByteCount(int value) {
        if (value < 16 || value > SmppProtocolLimits.MAXIMUM_PDU_BYTES) {
            throw new IllegalArgumentException("pduByteCount is outside SMPP limits");
        }
    }
}
