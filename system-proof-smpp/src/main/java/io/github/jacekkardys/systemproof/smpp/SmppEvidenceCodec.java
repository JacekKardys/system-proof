package io.github.jacekkardys.systemproof.smpp;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindOutcome;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindRequested;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindResponded;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.Command;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DataCoding;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmResponseCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.SessionControl;

final class SmppEvidenceCodec implements EvidenceCodec<SmppEvidence> {
    static final SmppEvidenceCodec INSTANCE = new SmppEvidenceCodec();

    private static final EvidenceSchemaId SCHEMA = new EvidenceSchemaId(
        "system-proof.smpp",
        "wire-evidence",
        1
    );
    private static final byte BIND_REQUESTED = 1;
    private static final byte BIND_RESPONDED = 2;
    private static final byte SESSION_CONTROL = 3;
    private static final byte DELIVER_SM_COMPLETED = 4;
    private static final byte DELIVER_SM_RESPONSE_COMPLETED = 5;
    private static final int MAXIMUM_ENCODED_BYTES = 42;

    private SmppEvidenceCodec() {}

    @Override
    public EvidenceSchemaId schemaId() {
        return SCHEMA;
    }

    @Override
    public byte[] encode(SmppEvidence evidence) {
        if (evidence == null) {
            throw new NullPointerException("evidence must not be null");
        }
        return switch (evidence) {
            case BindRequested bind -> ByteBuffer.allocate(13)
                .order(ByteOrder.BIG_ENDIAN)
                .put(BIND_REQUESTED)
                .putLong(bind.wireSequenceNumber())
                .putInt(bind.pduByteCount())
                .array();
            case BindResponded bind -> ByteBuffer.allocate(22)
                .order(ByteOrder.BIG_ENDIAN)
                .put(BIND_RESPONDED)
                .putLong(bind.wireSequenceNumber())
                .putLong(bind.commandStatus())
                .put(bindOutcomeCode(bind.outcome()))
                .putInt(bind.pduByteCount())
                .array();
            case SessionControl control -> ByteBuffer.allocate(22)
                .order(ByteOrder.BIG_ENDIAN)
                .put(SESSION_CONTROL)
                .put(commandCode(control.command()))
                .putLong(control.wireSequenceNumber())
                .putLong(control.commandStatus())
                .putInt(control.pduByteCount())
                .array();
            case DeliverSmCompleted deliver -> ByteBuffer.allocate(MAXIMUM_ENCODED_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .put(DELIVER_SM_COMPLETED)
                .putLong(deliver.exchange().adapterSessionOrdinal())
                .putLong(deliver.exchange().exchangeOrdinal())
                .putLong(deliver.exchange().wireSequenceNumber())
                .putInt(deliver.pduByteCount())
                .putInt(deliver.bodyByteCount())
                .putInt(deliver.messageByteCount())
                .put(dataCodingCode(deliver.dataCoding()))
                .putInt(deliver.esmClass())
                .array();
            case DeliverSmResponseCompleted response -> ByteBuffer.allocate(38)
                .order(ByteOrder.BIG_ENDIAN)
                .put(DELIVER_SM_RESPONSE_COMPLETED)
                .putLong(response.exchange().adapterSessionOrdinal())
                .putLong(response.exchange().exchangeOrdinal())
                .putLong(response.exchange().wireSequenceNumber())
                .putLong(response.commandStatus())
                .put(acknowledgementCode(response.acknowledgement()))
                .putInt(response.pduByteCount())
                .array();
        };
    }

    @Override
    public SmppEvidence decode(byte[] encodedEvidence) {
        if (encodedEvidence == null) {
            throw new NullPointerException("encodedEvidence must not be null");
        }
        if (encodedEvidence.length < 2 || encodedEvidence.length > MAXIMUM_ENCODED_BYTES) {
            throw new IllegalArgumentException("Invalid encoded SMPP evidence");
        }
        try {
            ByteBuffer encoded = ByteBuffer.wrap(encodedEvidence).order(ByteOrder.BIG_ENDIAN);
            SmppEvidence evidence = switch (encoded.get()) {
                case BIND_REQUESTED -> new BindRequested(
                    encoded.getLong(),
                    encoded.getInt()
                );
                case BIND_RESPONDED -> new BindResponded(
                    encoded.getLong(),
                    encoded.getLong(),
                    bindOutcome(encoded.get()),
                    encoded.getInt()
                );
                case SESSION_CONTROL -> new SessionControl(
                    command(encoded.get()),
                    encoded.getLong(),
                    encoded.getLong(),
                    encoded.getInt()
                );
                case DELIVER_SM_COMPLETED -> new DeliverSmCompleted(
                    reference(encoded),
                    encoded.getInt(),
                    encoded.getInt(),
                    encoded.getInt(),
                    dataCoding(encoded.get()),
                    encoded.getInt()
                );
                case DELIVER_SM_RESPONSE_COMPLETED -> new DeliverSmResponseCompleted(
                    reference(encoded),
                    encoded.getLong(),
                    acknowledgement(encoded.get()),
                    encoded.getInt()
                );
                default -> throw new IllegalArgumentException(
                    "Unsupported encoded SMPP evidence type"
                );
            };
            if (encoded.hasRemaining()) {
                throw new IllegalArgumentException("Trailing encoded SMPP evidence bytes");
            }
            return evidence;
        } catch (BufferUnderflowException failure) {
            throw new IllegalArgumentException("Truncated encoded SMPP evidence");
        }
    }

    private static SmppExchangeRef reference(ByteBuffer encoded) {
        return new SmppExchangeRef(
            encoded.getLong(),
            encoded.getLong(),
            encoded.getLong()
        );
    }

    private static byte commandCode(Command command) {
        return (byte) (command.ordinal() + 1);
    }

    private static Command command(byte code) {
        int index = Byte.toUnsignedInt(code) - 1;
        if (index < 0 || index >= Command.values().length) {
            throw new IllegalArgumentException("Invalid encoded SMPP command");
        }
        return Command.values()[index];
    }

    private static byte bindOutcomeCode(BindOutcome outcome) {
        return (byte) (outcome == BindOutcome.ACCEPTED ? 1 : 2);
    }

    private static BindOutcome bindOutcome(byte code) {
        return switch (code) {
            case 1 -> BindOutcome.ACCEPTED;
            case 2 -> BindOutcome.REJECTED;
            default -> throw new IllegalArgumentException("Invalid encoded SMPP bind outcome");
        };
    }

    private static byte acknowledgementCode(Acknowledgement acknowledgement) {
        return (byte) (acknowledgement == Acknowledgement.POSITIVE ? 1 : 2);
    }

    private static Acknowledgement acknowledgement(byte code) {
        return switch (code) {
            case 1 -> Acknowledgement.POSITIVE;
            case 2 -> Acknowledgement.NEGATIVE;
            default -> throw new IllegalArgumentException(
                "Invalid encoded SMPP acknowledgement"
            );
        };
    }

    private static byte dataCodingCode(DataCoding dataCoding) {
        return (byte) (dataCoding == DataCoding.UCS2 ? 1 : 0);
    }

    private static DataCoding dataCoding(byte code) {
        if (code != 1) {
            throw new IllegalArgumentException("Invalid encoded SMPP data coding");
        }
        return DataCoding.UCS2;
    }
}
