package io.github.jacekkardys.systemproof.postgresql;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import io.github.jacekkardys.systemproof.environment.CorrelationContribution;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.AutocommitWrite;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.BackendError;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommandComplete;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommandTag;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommitAttempt;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommitSucceeded;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.ProtocolMessage;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.ProtocolMessageKind;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.ReadyForQuery;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.Rollback;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.StatementExecuted;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.StatementKind;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.TransactionStarted;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.TransactionStatus;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlWriteInteraction.ParameterFormat;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolDecodeResult;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolStream;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit;

final class PostgresqlProtocolSession implements ProtocolSession<PostgresqlEvidence> {
    private static final int SSL_REQUEST_CODE = 80877103;
    private static final int PROTOCOL_V3 = 196608;
    private static final int MAXIMUM_NAMED_OBJECTS = 256;
    private static final int MAXIMUM_NAME_BYTES = 128;
    private static final int MAXIMUM_BIND_PARAMETERS = 256;

    private final ProtocolLimits limits;
    private final PostgresqlWriteCorrelation writeCorrelation;
    private final SessionModel model;
    private Integer backendPid;
    private boolean frontendOpened;
    private boolean backendOpened;

    PostgresqlProtocolSession(
        long sessionOrdinal,
        ProtocolLimits limits,
        PostgresqlWriteCorrelation writeCorrelation
    ) {
        this.limits = Objects.requireNonNull(limits, "limits must not be null");
        this.writeCorrelation = Objects.requireNonNull(
            writeCorrelation,
            "writeCorrelation must not be null"
        );
        model = new SessionModel(sessionOrdinal);
    }

    @Override
    public synchronized ProtocolStream<PostgresqlEvidence> openStream(
        FlowDirection direction
    ) {
        Objects.requireNonNull(direction, "direction must not be null");
        return switch (direction) {
            case CONSUMER_TO_PROVIDER -> {
                if (frontendOpened) {
                    throw new IllegalStateException("PostgreSQL frontend stream was already opened");
                }
                frontendOpened = true;
                yield new FrontendStream();
            }
            case PROVIDER_TO_CONSUMER -> {
                if (backendOpened) {
                    throw new IllegalStateException("PostgreSQL backend stream was already opened");
                }
                backendOpened = true;
                yield new BackendStream();
            }
        };
    }

    private abstract class StreamSupport implements ProtocolStream<PostgresqlEvidence> {
        @Override
        public void endOfInput(ByteBuffer bufferedBytes) throws ProtocolAdapterException {
            ProtocolStream.super.endOfInput(bufferedBytes);
            model.terminal();
        }

        final ProtocolDecodeResult<PostgresqlEvidence> complete(
            byte[] originalBytes,
            PostgresqlEvidence evidence
        ) {
            return ProtocolDecodeResult.complete(new ProtocolUnit<>(originalBytes, evidence));
        }

        final ProtocolDecodeResult<PostgresqlEvidence> complete(
            byte[] originalBytes,
            PostgresqlEvidence evidence,
            List<CorrelationContribution<?>> contributions
        ) {
            return ProtocolDecodeResult.complete(
                new ProtocolUnit<>(originalBytes, evidence, contributions)
            );
        }

        final int regularFrameSize(ByteBuffer source, int offset)
            throws ProtocolAdapterException {
            if (source.limit() - offset < 5) {
                return -1;
            }
            int payloadLength = source.getInt(offset + 1);
            if (payloadLength < 4) {
                throw failure(
                    ProtocolFailureKind.MALFORMED_INPUT,
                    "PostgreSQL message length is smaller than its header"
                );
            }
            long frameSize = 1L + payloadLength;
            if (frameSize > limits.maximumFrameBytes()) {
                throw failure(
                    ProtocolFailureKind.EXCESSIVE_FRAME_SIZE,
                    "PostgreSQL message exceeds the configured frame limit"
                );
            }
            return (int) frameSize;
        }

        final byte[] copy(ByteBuffer source, int size) {
            ByteBuffer view = source.asReadOnlyBuffer();
            view.limit(view.position() + size);
            byte[] copy = new byte[size];
            view.get(copy);
            return copy;
        }
    }

    private final class FrontendStream extends StreamSupport {
        private final Map<String, StatementDefinition> namedStatements = new LinkedHashMap<>();
        private final Map<String, StatementDefinition> portals = new LinkedHashMap<>();
        private StatementDefinition unnamedStatement;
        private long observedTransactionGeneration;
        private boolean startup = true;

        @Override
        public ProtocolDecodeResult<PostgresqlEvidence> decode(ByteBuffer bufferedBytes)
            throws ProtocolAdapterException {
            Objects.requireNonNull(bufferedBytes, "bufferedBytes must not be null");
            ByteBuffer source = bufferedBytes.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
            if (startup) {
                return decodeStartup(source);
            }
            refreshTransactionBoundary();
            if (!source.hasRemaining()) {
                return ProtocolDecodeResult.needMoreData();
            }
            char type = (char) source.get(source.position());
            if (type == 'Q' || type == 'p' || type == 'X') {
                int size = regularFrameSize(source, source.position());
                if (size < 0 || source.remaining() < size) {
                    return ProtocolDecodeResult.needMoreData();
                }
                byte[] frame = copy(source, size);
                return switch (type) {
                    case 'Q' -> simpleQuery(frame);
                    case 'p' -> complete(
                        frame,
                        new ProtocolMessage(ProtocolMessageKind.AUTHENTICATION_PAYLOAD)
                    );
                    case 'X' -> {
                        requireFrameLength(frame, 5);
                        model.terminal();
                        yield complete(
                            frame,
                            new ProtocolMessage(ProtocolMessageKind.TERMINATE)
                        );
                    }
                    default -> throw new AssertionError("Unexpected frontend message type");
                };
            }
            if (isExtendedType(type)) {
                return extendedQuery(source);
            }
            throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "Unsupported PostgreSQL frontend message in the characterized subset"
            );
        }

        private ProtocolDecodeResult<PostgresqlEvidence> decodeStartup(ByteBuffer source)
            throws ProtocolAdapterException {
            if (source.remaining() < Integer.BYTES) {
                return ProtocolDecodeResult.needMoreData();
            }
            int length = source.getInt(source.position());
            if (length < 8) {
                throw failure(
                    ProtocolFailureKind.MALFORMED_INPUT,
                    "PostgreSQL startup packet is smaller than its header"
                );
            }
            if (length > limits.maximumFrameBytes()) {
                throw failure(
                    ProtocolFailureKind.EXCESSIVE_FRAME_SIZE,
                    "PostgreSQL startup packet exceeds the configured frame limit"
                );
            }
            if (source.remaining() < length) {
                return ProtocolDecodeResult.needMoreData();
            }
            byte[] packet = copy(source, length);
            int code = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN).getInt(4);
            if (code == SSL_REQUEST_CODE) {
                requireFrameLength(packet, 8);
                return complete(
                    packet,
                    new ProtocolMessage(ProtocolMessageKind.SSL_REQUEST)
                );
            }
            if (code != PROTOCOL_V3) {
                throw failure(
                    ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                    "Unsupported PostgreSQL startup request"
                );
            }
            validateStartupTerminator(packet);
            startup = false;
            return complete(
                packet,
                new ProtocolMessage(ProtocolMessageKind.STARTUP_MESSAGE)
            );
        }

        private ProtocolDecodeResult<PostgresqlEvidence> simpleQuery(byte[] frame)
            throws ProtocolAdapterException {
            String sql = readText(frame, 5, frame.length);
            StatementDefinition statement = SqlStatement.parse(sql);
            unnamedStatement = null;
            portals.clear();
            FrontendOutcome outcome = model.frontend(statement.command());
            return complete(frame, outcome.evidence());
        }

        private ProtocolDecodeResult<PostgresqlEvidence> extendedQuery(ByteBuffer source)
            throws ProtocolAdapterException {
            int start = source.position();
            int offset = start;
            int size = 0;
            boolean sync = false;
            while (!sync) {
                int frameSize = regularFrameSize(source, offset);
                if (frameSize < 0 || source.limit() - offset < frameSize) {
                    return ProtocolDecodeResult.needMoreData();
                }
                char type = (char) source.get(offset);
                if (!isExtendedType(type)) {
                    throw failure(
                        ProtocolFailureKind.AMBIGUOUS_FRAMING,
                        "PostgreSQL extended unit contains an unsupported message"
                    );
                }
                size += frameSize;
                if (size > limits.maximumFrameBytes()) {
                    throw failure(
                        ProtocolFailureKind.EXCESSIVE_FRAME_SIZE,
                        "PostgreSQL extended unit exceeds the configured frame limit"
                    );
                }
                sync = type == 'S';
                offset += frameSize;
            }
            byte[] unit = copy(source, size);
            return decodeExtendedUnit(unit);
        }

        private ProtocolDecodeResult<PostgresqlEvidence> decodeExtendedUnit(byte[] unit)
            throws ProtocolAdapterException {
            Map<String, BindParameters> currentBinds = new LinkedHashMap<>();
            StatementDefinition executed = null;
            BindParameters executedBind = null;
            int offset = 0;
            int parseCount = 0;
            int executeCount = 0;
            boolean flush = false;
            while (offset < unit.length) {
                int frameSize = frameSize(unit, offset);
                char type = (char) unit[offset];
                int end = offset + frameSize;
                switch (type) {
                    case 'P' -> {
                        if (++parseCount > 1) {
                            throw unsupportedPipeline();
                        }
                        CString statementName = readName(unit, offset + 5, end);
                        CString sql = readCString(unit, statementName.nextOffset(), end, false);
                        StatementDefinition definition = SqlStatement.parse(sql.value())
                            .withParameterTypeOids(
                                decodeParameterTypeOids(unit, sql.nextOffset(), end)
                            );
                        putStatement(statementName.value(), definition);
                    }
                    case 'B' -> {
                        BindDecoded bind = decodeBind(unit, offset, end);
                        StatementDefinition statement = statement(bind.statementName());
                        if (statement.parameterCount() >= 0
                            && bind.parameters().parameters().size()
                                != statement.parameterCount()) {
                            throw failure(
                                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                                "PostgreSQL Bind parameter count does not match Parse"
                            );
                        }
                        putPortal(bind.portalName(), statement);
                        currentBinds.put(bind.portalName(), bind.parameters());
                    }
                    case 'D' -> validateDescribeOrClose(unit, offset, end, false);
                    case 'E' -> {
                        if (++executeCount > 1) {
                            throw unsupportedPipeline();
                        }
                        CString portal = readName(unit, offset + 5, end);
                        if (portal.nextOffset() + Integer.BYTES != end) {
                            throw malformed("Invalid PostgreSQL Execute message");
                        }
                        int maximumRows = int32(unit, portal.nextOffset(), end);
                        if (maximumRows < 0) {
                            throw malformed("Invalid PostgreSQL Execute row limit");
                        }
                        executed = portal(portal.value());
                        if (maximumRows != 0 && returnsRowsOrIsUnknown(executed.command())) {
                            throw failure(
                                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                                "Partial PostgreSQL portal execution is unsupported"
                            );
                        }
                        executedBind = currentBinds.get(portal.value());
                    }
                    case 'C' -> closeObject(unit, offset, end);
                    case 'H' -> {
                        requireFrameLength(unit, offset, frameSize, 5);
                        flush = true;
                    }
                    case 'S' -> requireFrameLength(unit, offset, frameSize, 5);
                    default -> throw new AssertionError("Unexpected extended message type");
                }
                offset = end;
            }
            if (unit[unit.length - 5] != 'S') {
                throw malformed("PostgreSQL extended unit does not end with Sync");
            }
            CommandKind command = executed == null ? CommandKind.OTHER : executed.command();
            if (flush && command == CommandKind.COMMIT) {
                throw failure(
                    ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                    "PostgreSQL commit units containing Flush are unsupported"
                );
            }
            FrontendOutcome outcome = model.frontend(command);
            List<CorrelationContribution<?>> contributions = List.of();
            if (command == CommandKind.INSERT
                && outcome.transaction().isPresent()
                && executed != null) {
                if (executedBind == null) {
                    throw failure(
                        ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                        "Reused PostgreSQL write portals are unsupported"
                    );
                }
                contributions = correlate(
                    executed,
                    executedBind,
                    outcome.transaction().orElseThrow()
                );
            }
            return complete(unit, outcome.evidence(), contributions);
        }

        private boolean returnsRowsOrIsUnknown(CommandKind command) {
            return command == CommandKind.SELECT
                || command == CommandKind.SHOW
                || command == CommandKind.OTHER;
        }

        private List<CorrelationContribution<?>> correlate(
            StatementDefinition statement,
            BindParameters parameters,
            TransactionRef transaction
        ) {
            EphemeralWrite interaction = new EphemeralWrite(statement, parameters);
            Optional<CorrelationKey> key;
            try {
                key = Objects.requireNonNull(
                    writeCorrelation.correlate(interaction),
                    "PostgreSQL write correlation returned null"
                );
            } finally {
                interaction.invalidate();
            }
            return key.<List<CorrelationContribution<?>>>map(value -> List.of(
                CorrelationContribution.capture(value, TransactionRef.codec(), transaction)
            )).orElseGet(List::of);
        }

        private void putStatement(String name, StatementDefinition definition)
            throws ProtocolAdapterException {
            if (name.isEmpty()) {
                unnamedStatement = definition;
                return;
            }
            putBounded(namedStatements, name, definition, "prepared statements");
        }

        private StatementDefinition statement(String name) throws ProtocolAdapterException {
            StatementDefinition statement = name.isEmpty()
                ? unnamedStatement
                : namedStatements.get(name);
            if (statement == null) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "PostgreSQL Bind references an unknown statement"
                );
            }
            return statement;
        }

        private void putPortal(String name, StatementDefinition definition)
            throws ProtocolAdapterException {
            putBounded(portals, name, definition, "portals");
        }

        private StatementDefinition portal(String name) throws ProtocolAdapterException {
            StatementDefinition statement = portals.get(name);
            if (statement == null) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "PostgreSQL Execute references an unknown portal"
                );
            }
            return statement;
        }

        private void closeObject(byte[] unit, int offset, int end)
            throws ProtocolAdapterException {
            validateDescribeOrClose(unit, offset, end, true);
            char target = (char) unit[offset + 5];
            CString name = readName(unit, offset + 6, end);
            if (target == 'S') {
                StatementDefinition removed;
                if (name.value().isEmpty()) {
                    removed = unnamedStatement;
                    unnamedStatement = null;
                } else {
                    removed = namedStatements.remove(name.value());
                }
                if (removed != null) {
                    portals.entrySet().removeIf(entry -> entry.getValue() == removed);
                }
            } else {
                portals.remove(name.value());
            }
        }

        private void validateDescribeOrClose(
            byte[] unit,
            int offset,
            int end,
            boolean close
        ) throws ProtocolAdapterException {
            if (end - offset < 7) {
                throw malformed("Truncated PostgreSQL object message");
            }
            char target = (char) unit[offset + 5];
            if (target != 'S' && target != 'P') {
                throw malformed("Invalid PostgreSQL object target");
            }
            CString name = readName(unit, offset + 6, end);
            if (name.nextOffset() != end) {
                throw malformed("Trailing PostgreSQL object message bytes");
            }
            if (!close && target == 'P' && !portals.containsKey(name.value())) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "PostgreSQL Describe references an unknown portal"
                );
            }
        }

        private void refreshTransactionBoundary() {
            long generation = model.transactionGeneration();
            if (generation != observedTransactionGeneration) {
                portals.clear();
                observedTransactionGeneration = generation;
            }
        }

        private <T> void putBounded(
            Map<String, T> target,
            String name,
            T value,
            String description
        ) throws ProtocolAdapterException {
            if (!target.containsKey(name) && target.size() == MAXIMUM_NAMED_OBJECTS) {
                throw failure(
                    ProtocolFailureKind.EXCESSIVE_BUFFERED_BYTES,
                    "PostgreSQL " + description + " limit was reached"
                );
            }
            target.put(name, value);
        }
    }

    private final class BackendStream extends StreamSupport {
        private boolean sslResponse = true;

        @Override
        public ProtocolDecodeResult<PostgresqlEvidence> decode(ByteBuffer bufferedBytes)
            throws ProtocolAdapterException {
            Objects.requireNonNull(bufferedBytes, "bufferedBytes must not be null");
            ByteBuffer source = bufferedBytes.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
            if (sslResponse) {
                if (!source.hasRemaining()) {
                    return ProtocolDecodeResult.needMoreData();
                }
                char response = (char) source.get(source.position());
                if (response == 'S') {
                    model.terminal();
                    throw failure(
                        ProtocolFailureKind.UNSUPPORTED_ENCRYPTION,
                        "PostgreSQL server selected TLS; plaintext observation is unavailable"
                    );
                }
                if (response != 'N') {
                    throw malformed("Invalid PostgreSQL SSL negotiation response");
                }
                sslResponse = false;
                return complete(
                    new byte[] {(byte) response},
                    new ProtocolMessage(ProtocolMessageKind.SSL_REJECTED)
                );
            }
            if (!source.hasRemaining()) {
                return ProtocolDecodeResult.needMoreData();
            }
            int size = regularFrameSize(source, source.position());
            if (size < 0 || source.remaining() < size) {
                return ProtocolDecodeResult.needMoreData();
            }
            byte[] frame = copy(source, size);
            char type = (char) frame[0];
            PostgresqlEvidence evidence = switch (type) {
                case 'R' -> new ProtocolMessage(ProtocolMessageKind.AUTHENTICATION);
                case 'S' -> new ProtocolMessage(ProtocolMessageKind.PARAMETER_STATUS);
                case 'K' -> backendKeyData(frame);
                case '1' -> new ProtocolMessage(ProtocolMessageKind.PARSE_COMPLETE);
                case '2' -> new ProtocolMessage(ProtocolMessageKind.BIND_COMPLETE);
                case '3' -> new ProtocolMessage(ProtocolMessageKind.CLOSE_COMPLETE);
                case 'n' -> new ProtocolMessage(ProtocolMessageKind.NO_DATA);
                case 'T' -> new ProtocolMessage(ProtocolMessageKind.ROW_DESCRIPTION);
                case 'D' -> new ProtocolMessage(ProtocolMessageKind.DATA_ROW);
                case 'N' -> new ProtocolMessage(ProtocolMessageKind.NOTICE_RESPONSE);
                case 'C' -> model.commandComplete(commandTag(readText(frame, 5, frame.length)));
                case 'E' -> model.backendError();
                case 'Z' -> readyForQuery(frame);
                case 'I', 't' -> new ProtocolMessage(ProtocolMessageKind.OTHER);
                case 'G', 'H', 'W', 's' -> throw failure(
                    ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                    "Unsupported PostgreSQL backend sub-protocol"
                );
                default -> new ProtocolMessage(ProtocolMessageKind.OTHER);
            };
            return complete(frame, evidence);
        }

        private PostgresqlEvidence backendKeyData(byte[] frame)
            throws ProtocolAdapterException {
            requireFrameLength(frame, 13);
            int backendPid = ByteBuffer.wrap(frame)
                .order(ByteOrder.BIG_ENDIAN)
                .getInt(5);
            if (backendPid <= 0) {
                throw malformed("Invalid PostgreSQL backend identity");
            }
            if (PostgresqlProtocolSession.this.backendPid != null
                && PostgresqlProtocolSession.this.backendPid != backendPid) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "PostgreSQL backend identity changed within one session"
                );
            }
            PostgresqlProtocolSession.this.backendPid = backendPid;
            return new ProtocolMessage(ProtocolMessageKind.BACKEND_KEY_DATA);
        }

        private PostgresqlEvidence readyForQuery(byte[] frame)
            throws ProtocolAdapterException {
            requireFrameLength(frame, 6);
            TransactionStatus status = switch ((char) frame[5]) {
                case 'I' -> TransactionStatus.IDLE;
                case 'T' -> TransactionStatus.TRANSACTION;
                case 'E' -> TransactionStatus.FAILED;
                default -> throw malformed("Invalid PostgreSQL ReadyForQuery status");
            };
            return model.readyForQuery(status);
        }
    }

    private static final class SessionModel {
        private final long sessionOrdinal;
        private long nextTransactionOrdinal = 1;
        private long transactionGeneration;
        private TransactionRef transaction;
        private TransactionPhase phase = TransactionPhase.IDLE;
        private final ArrayDeque<ExpectedCycle> expected = new ArrayDeque<>(2);
        private boolean startupReady;

        private SessionModel(long sessionOrdinal) {
            this.sessionOrdinal = sessionOrdinal;
        }

        private synchronized FrontendOutcome frontend(CommandKind command)
            throws ProtocolAdapterException {
            if (phase == TransactionPhase.TERMINAL) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "PostgreSQL session is terminal"
                );
            }
            if (!expected.isEmpty() && !acceptsPgjdbcBeginLookahead(command)) {
                throw new ProtocolAdapterException(
                    ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                    "PostgreSQL pipelining is unsupported for this adapter"
                );
            }
            expected.addLast(new ExpectedCycle(command));
            PostgresqlEvidence evidence;
            switch (command) {
                case BEGIN -> {
                    if (transaction != null || phase != TransactionPhase.IDLE) {
                        throw new ProtocolAdapterException(
                            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                            "Nested PostgreSQL transaction start is unsupported"
                        );
                    }
                    transaction = allocateTransaction();
                    phase = TransactionPhase.BEGIN_PENDING;
                    evidence = new ProtocolMessage(ProtocolMessageKind.OTHER);
                }
                case INSERT -> evidence = transaction == null
                    ? new AutocommitWrite(StatementKind.INSERT)
                    : new StatementExecuted(transaction, StatementKind.INSERT);
                case COMMIT -> {
                    if (transaction == null) {
                        evidence = new ProtocolMessage(ProtocolMessageKind.OTHER);
                    } else {
                        phase = TransactionPhase.COMMIT_PENDING;
                        evidence = new CommitAttempt(transaction);
                    }
                }
                case ROLLBACK -> {
                    if (transaction == null) {
                        evidence = new ProtocolMessage(ProtocolMessageKind.OTHER);
                    } else {
                        phase = TransactionPhase.ROLLBACK_PENDING;
                        evidence = new Rollback(transaction);
                    }
                }
                case OTHER, SELECT, SHOW, CREATE, DROP ->
                    evidence = new ProtocolMessage(ProtocolMessageKind.OTHER);
                default -> throw new AssertionError("Unsupported command kind");
            }
            return new FrontendOutcome(evidence, Optional.ofNullable(transaction));
        }

        private boolean acceptsPgjdbcBeginLookahead(CommandKind command) {
            return expected.size() == 1
                && expected.getFirst().command == CommandKind.BEGIN
                && phase == TransactionPhase.BEGIN_PENDING
                && command != CommandKind.BEGIN
                && command != CommandKind.COMMIT
                && command != CommandKind.ROLLBACK;
        }

        private synchronized PostgresqlEvidence commandComplete(CommandTag tag)
            throws ProtocolAdapterException {
            ExpectedCycle current = expected.peekFirst();
            if (current == null) {
                throw desynchronize(
                    "Unsolicited PostgreSQL CommandComplete"
                );
            }
            if (current.commandComplete != null) {
                throw desynchronize("Duplicate PostgreSQL CommandComplete");
            }
            CommandTag expectedTag = expectedTag(current.command);
            if (expectedTag != null && tag != expectedTag) {
                throw desynchronize(
                    "PostgreSQL CommandComplete does not match its frontend command"
                );
            }
            current.commandComplete = tag;
            return new CommandComplete(Optional.ofNullable(transaction), tag);
        }

        private synchronized PostgresqlEvidence backendError() {
            ExpectedCycle current = expected.peekFirst();
            if (current != null) {
                current.error = true;
                if (transaction != null) {
                    phase = TransactionPhase.FAILED;
                } else if (phase == TransactionPhase.BEGIN_PENDING) {
                    phase = TransactionPhase.IDLE;
                }
            }
            return new BackendError(Optional.ofNullable(transaction));
        }

        private synchronized PostgresqlEvidence readyForQuery(TransactionStatus status)
            throws ProtocolAdapterException {
            if (expected.isEmpty()) {
                if (!startupReady && status == TransactionStatus.IDLE) {
                    startupReady = true;
                    return new ReadyForQuery(status, Optional.empty());
                }
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "Unsolicited PostgreSQL ReadyForQuery"
                );
            }
            ExpectedCycle completed = expected.removeFirst();
            return switch (status) {
                case TRANSACTION -> readyInTransaction(completed);
                case FAILED -> readyFailed(completed);
                case IDLE -> readyIdle(completed);
            };
        }

        private PostgresqlEvidence readyInTransaction(ExpectedCycle completed)
            throws ProtocolAdapterException {
            if (completed.command == CommandKind.BEGIN) {
                if (transaction == null
                    || completed.error
                    || completed.commandComplete != CommandTag.BEGIN) {
                    throw failure(
                        ProtocolFailureKind.DESYNCHRONIZATION,
                        "ReadyForQuery(T) did not complete an explicit BEGIN"
                    );
                }
                phase = TransactionPhase.ACTIVE;
                return new TransactionStarted(transaction);
            }
            if (transaction == null) {
                throw desynchronize(
                    "ReadyForQuery(T) has no explicit transaction"
                );
            }
            if (completed.error) {
                throw desynchronize(
                    "ReadyForQuery(T) followed a PostgreSQL backend error"
                );
            }
            requireExpectedCompletion(completed);
            phase = completed.error ? TransactionPhase.FAILED : TransactionPhase.ACTIVE;
            return new ReadyForQuery(
                TransactionStatus.TRANSACTION,
                Optional.of(transaction)
            );
        }

        private PostgresqlEvidence readyFailed(ExpectedCycle completed)
            throws ProtocolAdapterException {
            if (transaction == null) {
                throw desynchronize(
                    "ReadyForQuery(E) has no explicit transaction"
                );
            }
            if (!completed.error) {
                throw desynchronize(
                    "ReadyForQuery(E) did not follow a PostgreSQL backend error"
                );
            }
            phase = TransactionPhase.FAILED;
            return new ReadyForQuery(TransactionStatus.FAILED, Optional.of(transaction));
        }

        private PostgresqlEvidence readyIdle(ExpectedCycle completed)
            throws ProtocolAdapterException {
            if (completed.command == CommandKind.BEGIN && !expected.isEmpty()) {
                throw desynchronize(
                    "PostgreSQL transaction-start lookahead lost synchronization"
                );
            }
            if (completed.command == CommandKind.BEGIN && !completed.error) {
                throw desynchronize(
                    "ReadyForQuery(I) followed a successful PostgreSQL transaction start"
                );
            }
            if (!completed.error) {
                requireExpectedCompletion(completed);
            }
            TransactionRef completedTransaction = transaction;
            boolean commitSucceeded = completedTransaction != null
                && phase == TransactionPhase.COMMIT_PENDING
                && completed.command == CommandKind.COMMIT
                && !completed.error
                && completed.commandComplete == CommandTag.COMMIT;
            phase = TransactionPhase.IDLE;
            transaction = null;
            if (completedTransaction != null) {
                transactionGeneration++;
            }
            return commitSucceeded
                ? new CommitSucceeded(completedTransaction)
                : new ReadyForQuery(
                    TransactionStatus.IDLE,
                    Optional.ofNullable(completedTransaction)
                );
        }

        private void requireExpectedCompletion(ExpectedCycle completed)
            throws ProtocolAdapterException {
            CommandTag expectedTag = expectedTag(completed.command);
            if (expectedTag != null && completed.commandComplete != expectedTag) {
                throw desynchronize(
                    "PostgreSQL ReadyForQuery arrived before the expected command completion"
                );
            }
        }

        private CommandTag expectedTag(CommandKind command) {
            return switch (command) {
                case BEGIN -> CommandTag.BEGIN;
                case INSERT -> CommandTag.INSERT;
                case COMMIT -> CommandTag.COMMIT;
                case ROLLBACK -> CommandTag.ROLLBACK;
                case SELECT -> CommandTag.SELECT;
                case SHOW -> CommandTag.SHOW;
                case CREATE -> CommandTag.CREATE;
                case DROP -> CommandTag.DROP;
                case OTHER -> null;
            };
        }

        private ProtocolAdapterException desynchronize(String message) {
            terminal();
            return failure(ProtocolFailureKind.DESYNCHRONIZATION, message);
        }

        private TransactionRef allocateTransaction() {
            long ordinal = nextTransactionOrdinal;
            if (ordinal < 1) {
                throw new IllegalStateException(
                    "PostgreSQL transaction identity space exhausted"
                );
            }
            nextTransactionOrdinal = ordinal == Long.MAX_VALUE
                ? Long.MIN_VALUE
                : ordinal + 1;
            return new TransactionRef(sessionOrdinal, ordinal);
        }

        private synchronized long transactionGeneration() {
            return transactionGeneration;
        }

        private synchronized void terminal() {
            phase = TransactionPhase.TERMINAL;
            expected.clear();
            transaction = null;
            transactionGeneration++;
        }
    }

    private static final class ExpectedCycle {
        private final CommandKind command;
        private CommandTag commandComplete;
        private boolean error;

        private ExpectedCycle(CommandKind command) {
            this.command = command;
        }
    }

    private enum TransactionPhase {
        IDLE,
        BEGIN_PENDING,
        ACTIVE,
        FAILED,
        COMMIT_PENDING,
        ROLLBACK_PENDING,
        TERMINAL
    }

    private enum CommandKind {
        BEGIN,
        INSERT,
        COMMIT,
        ROLLBACK,
        SELECT,
        SHOW,
        CREATE,
        DROP,
        OTHER
    }

    private record FrontendOutcome(
        PostgresqlEvidence evidence,
        Optional<TransactionRef> transaction
    ) {}

    private record StatementDefinition(
        CommandKind command,
        PostgresqlStatementShape shape,
        int parameterCount,
        List<Long> parameterTypeOids
    ) {
        private StatementDefinition(CommandKind command, PostgresqlStatementShape shape) {
            this(
                command,
                shape,
                shape == null ? knownParameterCount(command) : shape.columns().size(),
                List.of()
            );
        }

        private StatementDefinition {
            parameterTypeOids = List.copyOf(parameterTypeOids);
        }

        private StatementDefinition withParameterTypeOids(List<Long> typeOids)
            throws ProtocolAdapterException {
            if (parameterCount >= 0
                && !typeOids.isEmpty()
                && typeOids.size() != parameterCount) {
                throw failure(
                    ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                    "PostgreSQL Parse type count does not match statement parameters"
                );
            }
            return new StatementDefinition(command, shape, parameterCount, typeOids);
        }

        private static int knownParameterCount(CommandKind command) {
            return switch (command) {
                case BEGIN, COMMIT, ROLLBACK -> 0;
                case INSERT -> throw new IllegalArgumentException(
                    "PostgreSQL INSERT parameter count requires a statement shape"
                );
                case SELECT, SHOW, CREATE, DROP, OTHER -> -1;
            };
        }

        private OptionalLong parameterTypeOid(int index) {
            if (parameterTypeOids.isEmpty() || parameterTypeOids.get(index) == 0L) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(parameterTypeOids.get(index));
        }
    }

    private record CString(String value, int nextOffset) {}

    private record BindDecoded(
        String portalName,
        String statementName,
        BindParameters parameters
    ) {}

    private record BindParameters(byte[] unit, List<ParameterSlice> parameters) {}

    private record ParameterSlice(int offset, int length, ParameterFormat format) {
        private ParameterSlice {
            format = Objects.requireNonNull(format, "format must not be null");
        }

        private boolean isNull() {
            return length < 0;
        }
    }

    private static final class EphemeralWrite implements PostgresqlWriteInteraction {
        private PostgresqlStatementShape shape;
        private StatementDefinition statement;
        private BindParameters parameters;

        private EphemeralWrite(
            StatementDefinition statement,
            BindParameters parameters
        ) {
            this.statement = Objects.requireNonNull(
                statement,
                "statement must not be null"
            );
            shape = Objects.requireNonNull(statement.shape(), "shape must not be null");
            this.parameters = Objects.requireNonNull(parameters, "parameters must not be null");
        }

        @Override
        public PostgresqlStatementShape shape() {
            requireActive();
            return shape;
        }

        @Override
        public int parameterCount() {
            requireActive();
            return parameters.parameters().size();
        }

        @Override
        public boolean parameterIsNull(int zeroBasedIndex) {
            return parameter(zeroBasedIndex).isNull();
        }

        @Override
        public int parameterSize(int zeroBasedIndex) {
            ParameterSlice parameter = parameter(zeroBasedIndex);
            return parameter.isNull() ? 0 : parameter.length();
        }

        @Override
        public ParameterFormat parameterFormat(int zeroBasedIndex) {
            return parameter(zeroBasedIndex).format();
        }

        @Override
        public OptionalLong parameterTypeOid(int zeroBasedIndex) {
            parameter(zeroBasedIndex);
            return statement.parameterTypeOid(zeroBasedIndex);
        }

        @Override
        public ByteBuffer parameterBytes(int zeroBasedIndex) {
            ParameterSlice parameter = parameter(zeroBasedIndex);
            if (parameter.isNull()) {
                throw new IllegalStateException("PostgreSQL bind parameter is null");
            }
            return ByteBuffer.wrap(
                parameters.unit(),
                parameter.offset(),
                parameter.length()
            ).slice().asReadOnlyBuffer();
        }

        private ParameterSlice parameter(int index) {
            requireActive();
            if (index < 0 || index >= parameters.parameters().size()) {
                throw new IndexOutOfBoundsException("PostgreSQL bind parameter index is invalid");
            }
            return parameters.parameters().get(index);
        }

        private void invalidate() {
            shape = null;
            statement = null;
            parameters = null;
        }

        private void requireActive() {
            if (shape == null || statement == null || parameters == null) {
                throw new IllegalStateException(
                    "PostgreSQL write interaction is no longer available"
                );
            }
        }

        @Override
        public String toString() {
            return shape == null
                ? "PostgresqlWriteInteraction[expired]"
                : "PostgresqlWriteInteraction[kind=" + shape.kind()
                    + ", parameterCount=" + parameters.parameters().size() + "]";
        }
    }

    private static final class SqlStatement {
        private SqlStatement() {}

        private static StatementDefinition parse(String rawSql)
            throws ProtocolAdapterException {
            String sql = requireSingleStatement(rawSql);
            if (changesSynchronousCommitThroughSetConfig(sql)) {
                throw unsupportedSynchronousCommitChange();
            }
            Tokenizer tokenizer = new Tokenizer(sql);
            String first = tokenizer.word();
            if (first == null) {
                return new StatementDefinition(CommandKind.OTHER, null);
            }
            return switch (first.toUpperCase(Locale.ROOT)) {
                case "BEGIN" -> noArguments(tokenizer, CommandKind.BEGIN);
                case "START" -> {
                    tokenizer.requireWord("TRANSACTION");
                    tokenizer.requireEnd();
                    yield new StatementDefinition(CommandKind.BEGIN, null);
                }
                case "COMMIT" -> noArguments(tokenizer, CommandKind.COMMIT);
                case "ROLLBACK" -> noArguments(tokenizer, CommandKind.ROLLBACK);
                case "INSERT" -> insert(tokenizer);
                case "SELECT" -> new StatementDefinition(CommandKind.SELECT, null);
                case "SHOW" -> new StatementDefinition(CommandKind.SHOW, null);
                case "CREATE" -> new StatementDefinition(CommandKind.CREATE, null);
                case "DROP" -> new StatementDefinition(CommandKind.DROP, null);
                case "SET" -> setting(tokenizer);
                case "RESET" -> reset(tokenizer);
                default -> new StatementDefinition(CommandKind.OTHER, null);
            };
        }

        private static boolean changesSynchronousCommitThroughSetConfig(String sql) {
            return sql.matches(
                "(?is)SELECT\\s+(?:pg_catalog\\.)?set_config\\s*\\(\\s*"
                    + "'synchronous_commit'\\s*,.*"
            );
        }

        private static StatementDefinition setting(Tokenizer tokenizer)
            throws ProtocolAdapterException {
            String setting = tokenizer.requireIdentifier();
            if (setting.equalsIgnoreCase("LOCAL")
                || setting.equalsIgnoreCase("SESSION")) {
                setting = tokenizer.requireIdentifier();
            }
            if (setting.equalsIgnoreCase("synchronous_commit")) {
                throw unsupportedSynchronousCommitChange();
            }
            return new StatementDefinition(CommandKind.OTHER, null);
        }

        private static StatementDefinition reset(Tokenizer tokenizer)
            throws ProtocolAdapterException {
            String setting = tokenizer.requireIdentifier();
            if (setting.equalsIgnoreCase("synchronous_commit")) {
                throw unsupportedSynchronousCommitChange();
            }
            return new StatementDefinition(CommandKind.OTHER, null);
        }

        private static StatementDefinition noArguments(
            Tokenizer tokenizer,
            CommandKind command
        ) throws ProtocolAdapterException {
            tokenizer.requireEnd();
            return new StatementDefinition(command, null);
        }

        private static StatementDefinition insert(Tokenizer tokenizer)
            throws ProtocolAdapterException {
            tokenizer.requireWord("INTO");
            String firstIdentifier = tokenizer.requireIdentifier();
            Optional<String> schema = Optional.empty();
            String table = firstIdentifier;
            if (tokenizer.consume('.')) {
                schema = Optional.of(firstIdentifier);
                table = tokenizer.requireIdentifier();
            }
            tokenizer.require('(');
            List<String> columns = new ArrayList<>();
            do {
                columns.add(tokenizer.requireIdentifier());
            } while (tokenizer.consume(','));
            tokenizer.require(')');
            tokenizer.requireWord("VALUES");
            tokenizer.require('(');
            int parameterCount = 0;
            do {
                int parameter = tokenizer.requireParameter();
                if (parameter != parameterCount + 1) {
                    throw unsupportedSqlShape();
                }
                parameterCount++;
            } while (tokenizer.consume(','));
            tokenizer.require(')');
            tokenizer.requireEnd();
            if (parameterCount != columns.size()) {
                throw unsupportedSqlShape();
            }
            return new StatementDefinition(
                CommandKind.INSERT,
                new PostgresqlStatementShape(
                    PostgresqlStatementShape.Kind.INSERT,
                    schema,
                    table,
                    columns
                )
            );
        }

        private static String requireSingleStatement(String rawSql)
            throws ProtocolAdapterException {
            String sql = rawSql.strip();
            int semicolon = sql.indexOf(';');
            if (semicolon >= 0) {
                if (!sql.substring(semicolon + 1).isBlank()
                    || sql.indexOf(';', semicolon + 1) >= 0) {
                    throw failure(
                        ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                        "PostgreSQL multi-statement units are unsupported"
                    );
                }
                sql = sql.substring(0, semicolon).stripTrailing();
            }
            return sql;
        }
    }

    private static final class Tokenizer {
        private final String source;
        private int offset;

        private Tokenizer(String source) {
            this.source = source;
        }

        private String word() {
            skipWhitespace();
            int start = offset;
            while (offset < source.length()
                && Character.isLetter(source.charAt(offset))) {
                offset++;
            }
            return start == offset ? null : source.substring(start, offset);
        }

        private void requireWord(String expected) throws ProtocolAdapterException {
            String actual = word();
            if (actual == null || !actual.equalsIgnoreCase(expected)) {
                throw unsupportedSqlShape();
            }
        }

        private String requireIdentifier() throws ProtocolAdapterException {
            skipWhitespace();
            if (offset < source.length() && source.charAt(offset) == '"') {
                return requireCanonicalQuotedIdentifier();
            }
            int start = offset;
            if (offset >= source.length()
                || !(Character.isLetter(source.charAt(offset))
                    || source.charAt(offset) == '_')) {
                throw unsupportedSqlShape();
            }
            offset++;
            while (offset < source.length()) {
                char current = source.charAt(offset);
                if (!(Character.isLetterOrDigit(current)
                    || current == '_'
                    || current == '$')) {
                    break;
                }
                offset++;
            }
            return source.substring(start, offset);
        }

        private String requireCanonicalQuotedIdentifier()
            throws ProtocolAdapterException {
            int start = ++offset;
            while (offset < source.length() && source.charAt(offset) != '"') {
                offset++;
            }
            if (offset == source.length()) {
                throw unsupportedSqlShape();
            }
            String identifier = source.substring(start, offset++);
            if (!identifier.matches("[a-z_][a-z0-9_$]*")) {
                throw unsupportedSqlShape();
            }
            return identifier;
        }

        private int requireParameter() throws ProtocolAdapterException {
            skipWhitespace();
            if (offset >= source.length() || source.charAt(offset++) != '$') {
                throw unsupportedSqlShape();
            }
            int digits = offset;
            while (offset < source.length() && Character.isDigit(source.charAt(offset))) {
                offset++;
            }
            if (digits == offset) {
                throw unsupportedSqlShape();
            }
            try {
                return Integer.parseInt(source.substring(digits, offset));
            } catch (NumberFormatException failure) {
                throw unsupportedSqlShape();
            }
        }

        private boolean consume(char expected) {
            skipWhitespace();
            if (offset < source.length() && source.charAt(offset) == expected) {
                offset++;
                return true;
            }
            return false;
        }

        private void require(char expected) throws ProtocolAdapterException {
            if (!consume(expected)) {
                throw unsupportedSqlShape();
            }
        }

        private void requireEnd() throws ProtocolAdapterException {
            skipWhitespace();
            if (offset != source.length()) {
                throw unsupportedSqlShape();
            }
        }

        private void skipWhitespace() {
            while (offset < source.length()
                && Character.isWhitespace(source.charAt(offset))) {
                offset++;
            }
        }
    }

    private static BindDecoded decodeBind(byte[] unit, int offset, int end)
        throws ProtocolAdapterException {
        CString portal = readName(unit, offset + 5, end);
        CString statement = readName(unit, portal.nextOffset(), end);
        int cursor = statement.nextOffset();
        int formatCount = uint16(unit, cursor, end);
        cursor += Short.BYTES;
        List<ParameterFormat> formats = new ArrayList<>(formatCount);
        for (int index = 0; index < formatCount; index++) {
            int formatCode = uint16(unit, cursor, end);
            cursor += Short.BYTES;
            formats.add(parameterFormat(formatCode));
        }
        int parameterCount = uint16(unit, cursor, end);
        cursor += Short.BYTES;
        if (parameterCount > MAXIMUM_BIND_PARAMETERS) {
            throw failure(
                ProtocolFailureKind.EXCESSIVE_BUFFERED_BYTES,
                "PostgreSQL bind parameter limit was reached"
            );
        }
        if (formatCount != 0 && formatCount != 1 && formatCount != parameterCount) {
            throw malformed("Invalid PostgreSQL Bind parameter format count");
        }
        List<ParameterSlice> parameters = new ArrayList<>(parameterCount);
        for (int index = 0; index < parameterCount; index++) {
            ParameterFormat format = formatCount == 0
                ? ParameterFormat.TEXT
                : formats.get(formatCount == 1 ? 0 : index);
            int length = int32(unit, cursor, end);
            cursor += Integer.BYTES;
            if (length < -1) {
                throw malformed("Invalid PostgreSQL bind parameter length");
            }
            if (length == -1) {
                parameters.add(new ParameterSlice(-1, -1, format));
            } else {
                int parameterOffset = cursor;
                cursor = advance(cursor, length, end);
                parameters.add(new ParameterSlice(parameterOffset, length, format));
            }
        }
        int resultFormatCount = uint16(unit, cursor, end);
        cursor += Short.BYTES;
        cursor = advance(cursor, resultFormatCount * Short.BYTES, end);
        if (cursor != end) {
            throw malformed("Trailing PostgreSQL Bind bytes");
        }
        return new BindDecoded(
            portal.value(),
            statement.value(),
            new BindParameters(unit, List.copyOf(parameters))
        );
    }

    private static List<Long> decodeParameterTypeOids(byte[] unit, int offset, int end)
        throws ProtocolAdapterException {
        int count = uint16(unit, offset, end);
        int cursor = offset + Short.BYTES;
        if (count > MAXIMUM_BIND_PARAMETERS) {
            throw failure(
                ProtocolFailureKind.EXCESSIVE_BUFFERED_BYTES,
                "PostgreSQL Parse parameter type limit was reached"
            );
        }
        List<Long> typeOids = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            typeOids.add(Integer.toUnsignedLong(int32(unit, cursor, end)));
            cursor += Integer.BYTES;
        }
        if (cursor != end) {
            throw malformed("Trailing PostgreSQL Parse bytes");
        }
        return List.copyOf(typeOids);
    }

    private static ParameterFormat parameterFormat(int code)
        throws ProtocolAdapterException {
        return switch (code) {
            case 0 -> ParameterFormat.TEXT;
            case 1 -> ParameterFormat.BINARY;
            default -> throw failure(
                ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                "Unsupported PostgreSQL Bind parameter format"
            );
        };
    }

    private static CommandTag commandTag(String tag) {
        String normalized = tag.strip().toUpperCase(Locale.ROOT);
        if (normalized.equals("BEGIN")) {
            return CommandTag.BEGIN;
        }
        if (normalized.equals("INSERT") || normalized.startsWith("INSERT ")) {
            return CommandTag.INSERT;
        }
        if (normalized.equals("COMMIT")) {
            return CommandTag.COMMIT;
        }
        if (normalized.equals("ROLLBACK")) {
            return CommandTag.ROLLBACK;
        }
        if (normalized.equals("SELECT") || normalized.startsWith("SELECT ")) {
            return CommandTag.SELECT;
        }
        if (normalized.equals("SHOW") || normalized.startsWith("SHOW ")) {
            return CommandTag.SHOW;
        }
        if (normalized.equals("CREATE") || normalized.startsWith("CREATE ")) {
            return CommandTag.CREATE;
        }
        if (normalized.equals("DROP") || normalized.startsWith("DROP ")) {
            return CommandTag.DROP;
        }
        return CommandTag.OTHER;
    }

    private static boolean isExtendedType(char type) {
        return type == 'P'
            || type == 'B'
            || type == 'D'
            || type == 'E'
            || type == 'C'
            || type == 'H'
            || type == 'S';
    }

    private static int frameSize(byte[] unit, int offset) throws ProtocolAdapterException {
        if (unit.length - offset < 5) {
            throw malformed("Truncated PostgreSQL message header");
        }
        int length = ByteBuffer.wrap(unit, offset + 1, Integer.BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .getInt();
        if (length < 4 || (long) offset + 1L + length > unit.length) {
            throw malformed("Invalid PostgreSQL message length");
        }
        return 1 + length;
    }

    private static CString readName(byte[] source, int offset, int end)
        throws ProtocolAdapterException {
        CString name = readCString(source, offset, end, true);
        if (name.nextOffset() - offset - 1 > MAXIMUM_NAME_BYTES) {
            throw failure(
                ProtocolFailureKind.EXCESSIVE_BUFFERED_BYTES,
                "PostgreSQL object name exceeds the configured adapter limit"
            );
        }
        return name;
    }

    private static CString readCString(
        byte[] source,
        int offset,
        int end,
        boolean name
    ) throws ProtocolAdapterException {
        int terminator = offset;
        while (terminator < end && source[terminator] != 0) {
            terminator++;
        }
        if (terminator == end) {
            throw malformed("PostgreSQL string terminator is missing");
        }
        String value = decodeUtf8(source, offset, terminator - offset);
        if (name && value.indexOf('\u0000') >= 0) {
            throw malformed("Invalid PostgreSQL object name");
        }
        return new CString(value, terminator + 1);
    }

    private static String readText(byte[] source, int offset, int end)
        throws ProtocolAdapterException {
        CString text = readCString(source, offset, end, false);
        if (text.nextOffset() != end) {
            throw malformed("Trailing PostgreSQL string payload bytes");
        }
        return text.value();
    }

    private static String decodeUtf8(byte[] source, int offset, int length)
        throws ProtocolAdapterException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source, offset, length))
                .toString();
        } catch (CharacterCodingException failure) {
            throw malformed("Invalid UTF-8 in PostgreSQL message");
        }
    }

    private static int uint16(byte[] source, int offset, int end)
        throws ProtocolAdapterException {
        if (offset < 0 || end - offset < Short.BYTES) {
            throw malformed("Truncated PostgreSQL int16 field");
        }
        return Short.toUnsignedInt(ByteBuffer.wrap(source, offset, Short.BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .getShort());
    }

    private static int int32(byte[] source, int offset, int end)
        throws ProtocolAdapterException {
        if (offset < 0 || end - offset < Integer.BYTES) {
            throw malformed("Truncated PostgreSQL int32 field");
        }
        return ByteBuffer.wrap(source, offset, Integer.BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .getInt();
    }

    private static int advance(int offset, int count, int end)
        throws ProtocolAdapterException {
        if (count < 0 || (long) offset + count > end) {
            throw malformed("Truncated PostgreSQL message body");
        }
        return offset + count;
    }

    private static void validateStartupTerminator(byte[] packet)
        throws ProtocolAdapterException {
        if (packet[packet.length - 1] != 0) {
            throw malformed("PostgreSQL StartupMessage terminator is missing");
        }
    }

    private static void requireFrameLength(byte[] frame, int expected)
        throws ProtocolAdapterException {
        if (frame.length != expected) {
            throw malformed("Invalid PostgreSQL fixed message length");
        }
    }

    private static void requireFrameLength(
        byte[] unit,
        int offset,
        int actual,
        int expected
    ) throws ProtocolAdapterException {
        if (actual != expected || offset < 0 || offset + actual > unit.length) {
            throw malformed("Invalid PostgreSQL fixed message length");
        }
    }

    private static ProtocolAdapterException unsupportedPipeline() {
        return new ProtocolAdapterException(
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
            "PostgreSQL pipelining with multiple executions is unsupported"
        );
    }

    private static ProtocolAdapterException unsupportedSqlShape() {
        return new ProtocolAdapterException(
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
            "PostgreSQL SQL shape is outside the characterized subset"
        );
    }

    private static ProtocolAdapterException unsupportedSynchronousCommitChange() {
        return new ProtocolAdapterException(
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
            "Changing PostgreSQL synchronous_commit is unsupported"
        );
    }

    private static ProtocolAdapterException malformed(String message) {
        return failure(ProtocolFailureKind.MALFORMED_INPUT, message);
    }

    private static ProtocolAdapterException failure(
        ProtocolFailureKind kind,
        String message
    ) {
        return new ProtocolAdapterException(kind, message);
    }
}
