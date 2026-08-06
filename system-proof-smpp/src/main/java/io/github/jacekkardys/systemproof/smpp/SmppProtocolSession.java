package io.github.jacekkardys.systemproof.smpp;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import io.github.jacekkardys.systemproof.environment.CorrelationContribution;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.smpp.SmppBodyDecoder.DeliverBody;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindOutcome;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindRequested;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindResponded;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.Command;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DataCoding;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmResponseCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.SessionControl;
import io.github.jacekkardys.systemproof.smpp.SmppPduFramer.Frame;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolDecodeResult;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolStream;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit;

final class SmppProtocolSession implements ProtocolSession<SmppEvidence> {
    private static final long BIND_TRANSCEIVER = 0x00000009L;
    private static final long BIND_TRANSCEIVER_RESP = 0x80000009L;
    private static final long DELIVER_SM = 0x00000005L;
    private static final long DELIVER_SM_RESP = 0x80000005L;
    private static final long ENQUIRE_LINK = 0x00000015L;
    private static final long ENQUIRE_LINK_RESP = 0x80000015L;
    private static final long UNBIND = 0x00000006L;
    private static final long UNBIND_RESP = 0x80000006L;

    private final SmppPduFramer framer;
    private final SmppBodyDecoder bodyDecoder;
    private final SmppDeliverCorrelation deliverCorrelation;
    private final SessionModel model;
    private boolean consumerStreamOpened;
    private boolean providerStreamOpened;

    SmppProtocolSession(
        long sessionOrdinal,
        ProtocolLimits gatewayLimits,
        SmppProtocolLimits smppLimits,
        SmppDeliverCorrelation deliverCorrelation
    ) {
        framer = new SmppPduFramer(gatewayLimits, smppLimits);
        bodyDecoder = new SmppBodyDecoder(smppLimits);
        this.deliverCorrelation = Objects.requireNonNull(
            deliverCorrelation,
            "deliverCorrelation must not be null"
        );
        model = new SessionModel(
            sessionOrdinal,
            smppLimits.maximumOutstandingDeliveries()
        );
    }

    @Override
    public synchronized ProtocolStream<SmppEvidence> openStream(FlowDirection direction) {
        Objects.requireNonNull(direction, "direction must not be null");
        return switch (direction) {
            case CONSUMER_TO_PROVIDER -> {
                if (consumerStreamOpened) {
                    throw new IllegalStateException("SMPP consumer stream was already opened");
                }
                consumerStreamOpened = true;
                yield new DirectionalStream(direction);
            }
            case PROVIDER_TO_CONSUMER -> {
                if (providerStreamOpened) {
                    throw new IllegalStateException("SMPP provider stream was already opened");
                }
                providerStreamOpened = true;
                yield new DirectionalStream(direction);
            }
        };
    }

    private final class DirectionalStream implements ProtocolStream<SmppEvidence> {
        private final FlowDirection direction;

        private DirectionalStream(FlowDirection direction) {
            this.direction = direction;
        }

        @Override
        public ProtocolDecodeResult<SmppEvidence> decode(ByteBuffer bufferedBytes)
            throws ProtocolAdapterException {
            Objects.requireNonNull(bufferedBytes, "bufferedBytes must not be null");
            try {
                model.requireInputOpen(direction);
                Frame frame = framer.decode(bufferedBytes);
                if (frame == null) {
                    return ProtocolDecodeResult.needMoreData();
                }
                requireWireSequence(frame.sequenceNumber());
                return ProtocolDecodeResult.complete(decodeComplete(direction, frame));
            } catch (ProtocolAdapterException | RuntimeException | Error failure) {
                model.terminal();
                throw failure;
            }
        }

        @Override
        public void endOfInput(ByteBuffer bufferedBytes) throws ProtocolAdapterException {
            try {
                ProtocolStream.super.endOfInput(bufferedBytes);
                model.endOfInput(direction);
            } catch (ProtocolAdapterException failure) {
                model.terminal();
                throw failure;
            }
        }
    }

    private ProtocolUnit<SmppEvidence> decodeComplete(
        FlowDirection direction,
        Frame frame
    ) throws ProtocolAdapterException {
        return switch (direction) {
            case CONSUMER_TO_PROVIDER -> decodeConsumerPdu(frame);
            case PROVIDER_TO_CONSUMER -> decodeProviderPdu(frame);
        };
    }

    private ProtocolUnit<SmppEvidence> decodeConsumerPdu(Frame frame)
        throws ProtocolAdapterException {
        if (frame.commandId() == BIND_TRANSCEIVER) {
            requireRequestStatus(frame);
            bodyDecoder.decodeBindRequest(frame.body());
            model.bindRequested(frame.sequenceNumber());
            return unit(frame, new BindRequested(
                frame.sequenceNumber(),
                frame.pduByteCount()
            ));
        }
        if (frame.commandId() == DELIVER_SM_RESP) {
            bodyDecoder.decodeDeliverResponse(frame.body());
            SmppExchangeRef exchange = model.deliverResponded(frame.sequenceNumber());
            Acknowledgement acknowledgement = frame.commandStatus() == 0
                ? Acknowledgement.POSITIVE
                : Acknowledgement.NEGATIVE;
            return unit(frame, new DeliverSmResponseCompleted(
                exchange,
                frame.commandStatus(),
                acknowledgement,
                frame.pduByteCount()
            ));
        }
        if (frame.commandId() == ENQUIRE_LINK) {
            requireRequestStatus(frame);
            bodyDecoder.requireEmptyBody(frame.body());
            model.enquireLinkRequested(frame.sequenceNumber());
            return unit(frame, new SessionControl(
                Command.ENQUIRE_LINK,
                frame.sequenceNumber(),
                0,
                frame.pduByteCount()
            ));
        }
        if (frame.commandId() == UNBIND) {
            requireRequestStatus(frame);
            bodyDecoder.requireEmptyBody(frame.body());
            model.unbindRequested(frame.sequenceNumber());
            return unit(frame, new SessionControl(
                Command.UNBIND,
                frame.sequenceNumber(),
                0,
                frame.pduByteCount()
            ));
        }
        throw unsupported("SMPP command is unsupported in the consumer direction");
    }

    private ProtocolUnit<SmppEvidence> decodeProviderPdu(Frame frame)
        throws ProtocolAdapterException {
        if (frame.commandId() == BIND_TRANSCEIVER_RESP) {
            bodyDecoder.decodeBindResponse(frame.body(), frame.commandStatus());
            BindOutcome outcome = frame.commandStatus() == 0
                ? BindOutcome.ACCEPTED
                : BindOutcome.REJECTED;
            model.bindResponded(frame.sequenceNumber(), outcome);
            return unit(frame, new BindResponded(
                frame.sequenceNumber(),
                frame.commandStatus(),
                outcome,
                frame.pduByteCount()
            ));
        }
        if (frame.commandId() == DELIVER_SM) {
            requireRequestStatus(frame);
            DeliverBody body = bodyDecoder.decodeDeliver(frame.body());
            SmppExchangeRef exchange = model.deliverStarted(frame.sequenceNumber());
            EphemeralDeliver interaction = new EphemeralDeliver(body);
            Optional<CorrelationKey> key;
            try {
                key = Objects.requireNonNull(
                    deliverCorrelation.correlate(interaction),
                    "SMPP deliver correlation returned null"
                );
            } finally {
                interaction.invalidate();
            }
            List<CorrelationContribution<?>> contributions = key
                .<List<CorrelationContribution<?>>>map(value -> List.of(
                    CorrelationContribution.capture(
                        value,
                        SmppExchangeRef.codec(),
                        exchange
                    )
                ))
                .orElseGet(List::of);
            return new ProtocolUnit<>(
                frame.originalBytes(),
                new DeliverSmCompleted(
                    exchange,
                    frame.pduByteCount(),
                    frame.pduByteCount() - SmppPduFramer.HEADER_BYTES,
                    body.messageByteCount(),
                    DataCoding.UCS2,
                    body.esmClass()
                ),
                contributions
            );
        }
        if (frame.commandId() == ENQUIRE_LINK_RESP) {
            requireResponseSuccess(frame, "SMPP enquire_link_resp status is unsupported");
            bodyDecoder.requireEmptyBody(frame.body());
            model.enquireLinkResponded(frame.sequenceNumber());
            return unit(frame, new SessionControl(
                Command.ENQUIRE_LINK_RESP,
                frame.sequenceNumber(),
                frame.commandStatus(),
                frame.pduByteCount()
            ));
        }
        if (frame.commandId() == UNBIND_RESP) {
            requireResponseSuccess(frame, "SMPP unbind_resp status is unsupported");
            bodyDecoder.requireEmptyBody(frame.body());
            model.unbindResponded(frame.sequenceNumber());
            return unit(frame, new SessionControl(
                Command.UNBIND_RESP,
                frame.sequenceNumber(),
                frame.commandStatus(),
                frame.pduByteCount()
            ));
        }
        throw unsupported("SMPP command is unsupported in the provider direction");
    }

    private static ProtocolUnit<SmppEvidence> unit(Frame frame, SmppEvidence evidence) {
        return new ProtocolUnit<>(frame.originalBytes(), evidence);
    }

    private static void requireWireSequence(long sequence)
        throws ProtocolAdapterException {
        if (sequence == 0) {
            throw failure(
                ProtocolFailureKind.MALFORMED_INPUT,
                "SMPP sequence_number must be non-zero"
            );
        }
    }

    private static void requireRequestStatus(Frame frame)
        throws ProtocolAdapterException {
        if (frame.commandStatus() != 0) {
            throw failure(
                ProtocolFailureKind.MALFORMED_INPUT,
                "SMPP request command_status must be zero"
            );
        }
    }

    private static void requireResponseSuccess(Frame frame, String message)
        throws ProtocolAdapterException {
        if (frame.commandStatus() != 0) {
            throw failure(ProtocolFailureKind.UNSUPPORTED_NEGOTIATION, message);
        }
    }

    private static ProtocolAdapterException unsupported(String message) {
        return failure(ProtocolFailureKind.UNSUPPORTED_NEGOTIATION, message);
    }

    private static ProtocolAdapterException failure(
        ProtocolFailureKind kind,
        String message
    ) {
        return new ProtocolAdapterException(kind, message);
    }

    private enum State {
        OPEN,
        BIND_PENDING,
        BOUND,
        UNBIND_PENDING,
        UNBOUND,
        TERMINAL
    }

    private static final class SessionModel {
        private final long sessionOrdinal;
        private final int maximumOutstandingDeliveries;
        private final Map<Long, SmppExchangeRef> outstandingDeliveries = new HashMap<>();
        private long nextExchangeOrdinal = 1;
        private State state = State.OPEN;
        private Long pendingBindSequence;
        private Long pendingEnquireLinkSequence;
        private Long pendingUnbindSequence;
        private boolean consumerInputEnded;
        private boolean providerInputEnded;

        private SessionModel(long sessionOrdinal, int maximumOutstandingDeliveries) {
            this.sessionOrdinal = sessionOrdinal;
            this.maximumOutstandingDeliveries = maximumOutstandingDeliveries;
        }

        private synchronized void bindRequested(long sequence)
            throws ProtocolAdapterException {
            requireState(State.OPEN, "SMPP bind request is not allowed in the current state");
            pendingBindSequence = sequence;
            state = State.BIND_PENDING;
        }

        private synchronized void bindResponded(long sequence, BindOutcome outcome)
            throws ProtocolAdapterException {
            requireState(State.BIND_PENDING, "SMPP bind response has no pending request");
            if (!Objects.equals(pendingBindSequence, sequence)) {
                throw desynchronization("SMPP bind response sequence does not match its request");
            }
            pendingBindSequence = null;
            state = outcome == BindOutcome.ACCEPTED ? State.BOUND : State.UNBOUND;
        }

        private synchronized SmppExchangeRef deliverStarted(long sequence)
            throws ProtocolAdapterException {
            requireState(State.BOUND, "SMPP deliver_sm requires a bound session");
            if (outstandingDeliveries.containsKey(sequence)) {
                throw desynchronization(
                    "SMPP deliver_sm reused an outstanding sequence_number"
                );
            }
            if (outstandingDeliveries.size() >= maximumOutstandingDeliveries) {
                throw failure(
                    ProtocolFailureKind.EXCESSIVE_BUFFERED_BYTES,
                    "SMPP outstanding deliver_sm limit was exceeded"
                );
            }
            long exchangeOrdinal = nextExchangeOrdinal;
            if (exchangeOrdinal < 1) {
                throw new IllegalStateException("SMPP exchange identity space exhausted");
            }
            nextExchangeOrdinal = exchangeOrdinal == Long.MAX_VALUE
                ? Long.MIN_VALUE
                : exchangeOrdinal + 1;
            SmppExchangeRef exchange = new SmppExchangeRef(
                sessionOrdinal,
                exchangeOrdinal,
                sequence
            );
            outstandingDeliveries.put(sequence, exchange);
            return exchange;
        }

        private synchronized SmppExchangeRef deliverResponded(long sequence)
            throws ProtocolAdapterException {
            requireState(State.BOUND, "SMPP deliver_sm_resp requires a bound session");
            SmppExchangeRef exchange = outstandingDeliveries.remove(sequence);
            if (exchange == null) {
                throw desynchronization(
                    "SMPP deliver_sm_resp has no matching deliver_sm in this session"
                );
            }
            return exchange;
        }

        private synchronized void enquireLinkRequested(long sequence)
            throws ProtocolAdapterException {
            requireState(State.BOUND, "SMPP enquire_link requires a bound session");
            if (pendingEnquireLinkSequence != null) {
                throw unsupported("Multiple outstanding enquire_link requests are unsupported");
            }
            pendingEnquireLinkSequence = sequence;
        }

        private synchronized void enquireLinkResponded(long sequence)
            throws ProtocolAdapterException {
            requireState(State.BOUND, "SMPP enquire_link_resp requires a bound session");
            if (!Objects.equals(pendingEnquireLinkSequence, sequence)) {
                throw desynchronization(
                    "SMPP enquire_link_resp sequence does not match its request"
                );
            }
            pendingEnquireLinkSequence = null;
        }

        private synchronized void unbindRequested(long sequence)
            throws ProtocolAdapterException {
            requireState(State.BOUND, "SMPP unbind requires a bound session");
            if (!outstandingDeliveries.isEmpty() || pendingEnquireLinkSequence != null) {
                throw desynchronization(
                    "SMPP unbind cannot begin while exchanges are outstanding"
                );
            }
            pendingUnbindSequence = sequence;
            state = State.UNBIND_PENDING;
        }

        private synchronized void unbindResponded(long sequence)
            throws ProtocolAdapterException {
            requireState(State.UNBIND_PENDING, "SMPP unbind_resp has no pending request");
            if (!Objects.equals(pendingUnbindSequence, sequence)) {
                throw desynchronization(
                    "SMPP unbind_resp sequence does not match its request"
                );
            }
            pendingUnbindSequence = null;
            state = State.UNBOUND;
        }

        private synchronized void requireInputOpen(FlowDirection direction)
            throws ProtocolAdapterException {
            if (state == State.TERMINAL
                || consumerInputEnded
                || providerInputEnded) {
                throw desynchronization("SMPP session input is closed");
            }
        }

        private synchronized void endOfInput(FlowDirection direction)
            throws ProtocolAdapterException {
            if (state == State.TERMINAL
                || direction == FlowDirection.CONSUMER_TO_PROVIDER && consumerInputEnded
                || direction == FlowDirection.PROVIDER_TO_CONSUMER && providerInputEnded) {
                throw desynchronization("SMPP session input is closed");
            }
            if (pendingBindSequence != null
                || pendingEnquireLinkSequence != null
                || pendingUnbindSequence != null
                || !outstandingDeliveries.isEmpty()) {
                throw desynchronization("SMPP input ended with outstanding exchanges");
            }
            if (direction == FlowDirection.CONSUMER_TO_PROVIDER) {
                consumerInputEnded = true;
            } else {
                providerInputEnded = true;
            }
            if (consumerInputEnded && providerInputEnded) {
                state = State.TERMINAL;
            }
        }

        private synchronized void terminal() {
            state = State.TERMINAL;
            pendingBindSequence = null;
            pendingEnquireLinkSequence = null;
            pendingUnbindSequence = null;
            outstandingDeliveries.clear();
        }

        private void requireState(State expected, String message)
            throws ProtocolAdapterException {
            if (state != expected) {
                throw desynchronization(message);
            }
        }

        private static ProtocolAdapterException desynchronization(String message) {
            return failure(ProtocolFailureKind.DESYNCHRONIZATION, message);
        }
    }

    private static final class EphemeralDeliver implements SmppDeliverInteraction {
        private char[] sourceAddress;
        private char[] destinationAddress;
        private char[] message;
        private final int esmClass;
        private final int dataCoding;
        private final Characters source = new EphemeralCharacters(this, Field.SOURCE);
        private final Characters destination = new EphemeralCharacters(this, Field.DESTINATION);
        private final Characters content = new EphemeralCharacters(this, Field.MESSAGE);

        private EphemeralDeliver(DeliverBody body) {
            sourceAddress = body.sourceAddress();
            destinationAddress = body.destinationAddress();
            message = body.message();
            esmClass = body.esmClass();
            dataCoding = body.dataCoding();
        }

        @Override
        public int esmClass() {
            requireActive();
            return esmClass;
        }

        @Override
        public int dataCoding() {
            requireActive();
            return dataCoding;
        }

        @Override
        public Characters sourceAddress() {
            requireActive();
            return source;
        }

        @Override
        public Characters destinationAddress() {
            requireActive();
            return destination;
        }

        @Override
        public Characters message() {
            requireActive();
            return content;
        }

        private void invalidate() {
            java.util.Arrays.fill(sourceAddress, '\0');
            java.util.Arrays.fill(destinationAddress, '\0');
            java.util.Arrays.fill(message, '\0');
            sourceAddress = null;
            destinationAddress = null;
            message = null;
        }

        private char[] characters(Field field) {
            requireActive();
            return switch (field) {
                case SOURCE -> sourceAddress;
                case DESTINATION -> destinationAddress;
                case MESSAGE -> message;
            };
        }

        private void requireActive() {
            if (sourceAddress == null || destinationAddress == null || message == null) {
                throw new IllegalStateException(
                    "SMPP deliver interaction is no longer available"
                );
            }
        }

        @Override
        public String toString() {
            return sourceAddress == null
                ? "SmppDeliverInteraction[expired]"
                : "SmppDeliverInteraction[sourceLength=" + sourceAddress.length
                    + ", destinationLength=" + destinationAddress.length
                    + ", messageLength=" + message.length
                    + ", dataCoding=" + dataCoding + "]";
        }
    }

    private enum Field {
        SOURCE,
        DESTINATION,
        MESSAGE
    }

    private static final class EphemeralCharacters
        implements SmppDeliverInteraction.Characters {
        private final EphemeralDeliver owner;
        private final Field field;

        private EphemeralCharacters(EphemeralDeliver owner, Field field) {
            this.owner = owner;
            this.field = field;
        }

        @Override
        public int length() {
            return owner.characters(field).length;
        }

        @Override
        public char charAt(int index) {
            char[] characters = owner.characters(field);
            return characters[Objects.checkIndex(index, characters.length)];
        }

        @Override
        public void copyTo(
            int sourceOffset,
            char[] destination,
            int destinationOffset,
            int length
        ) {
            char[] characters = owner.characters(field);
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.checkFromIndexSize(sourceOffset, length, characters.length);
            Objects.checkFromIndexSize(destinationOffset, length, destination.length);
            System.arraycopy(
                characters,
                sourceOffset,
                destination,
                destinationOffset,
                length
            );
        }
    }
}
