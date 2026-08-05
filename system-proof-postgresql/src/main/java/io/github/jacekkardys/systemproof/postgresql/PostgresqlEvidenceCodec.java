package io.github.jacekkardys.systemproof.postgresql;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.AutocommitWrite;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.BackendError;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommandComplete;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommitAttempt;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommitSucceeded;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.ProtocolMessage;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.ProtocolMessageKind;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.ReadyForQuery;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.Rollback;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.StatementExecuted;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.StatementKind;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommandTag;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.TransactionStarted;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.TransactionStatus;

final class PostgresqlEvidenceCodec implements EvidenceCodec<PostgresqlEvidence> {
    static final PostgresqlEvidenceCodec INSTANCE = new PostgresqlEvidenceCodec();

    private static final EvidenceSchemaId SCHEMA = new EvidenceSchemaId(
        "system-proof.postgresql",
        "wire-evidence",
        1
    );

    private static final byte PROTOCOL_MESSAGE = 1;
    private static final byte TRANSACTION_STARTED = 2;
    private static final byte STATEMENT_EXECUTED = 3;
    private static final byte AUTOCOMMIT_WRITE = 4;
    private static final byte COMMIT_ATTEMPT = 5;
    private static final byte ROLLBACK = 6;
    private static final byte BACKEND_ERROR = 7;
    private static final byte COMMAND_COMPLETE = 8;
    private static final byte READY_FOR_QUERY = 9;
    private static final byte COMMIT_SUCCEEDED = 10;

    private PostgresqlEvidenceCodec() {}

    @Override
    public EvidenceSchemaId schemaId() {
        return SCHEMA;
    }

    @Override
    public byte[] encode(PostgresqlEvidence evidence) {
        if (evidence == null) {
            throw new NullPointerException("evidence must not be null");
        }
        ByteBuffer encoded = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN);
        switch (evidence) {
            case ProtocolMessage message -> encoded
                .put(PROTOCOL_MESSAGE)
                .put(protocolMessageKindCode(message.kind()));
            case TransactionStarted started -> {
                encoded.put(TRANSACTION_STARTED);
                putRef(encoded, started.transaction());
                encoded.put(transactionStatusCode(started.readyForQueryStatus()));
            }
            case StatementExecuted statement -> {
                encoded.put(STATEMENT_EXECUTED);
                putRef(encoded, statement.transaction());
                encoded.put(statementKindCode(statement.statementKind()));
            }
            case AutocommitWrite write -> encoded
                .put(AUTOCOMMIT_WRITE)
                .put(statementKindCode(write.statementKind()));
            case CommitAttempt attempt -> {
                encoded.put(COMMIT_ATTEMPT);
                putRef(encoded, attempt.transaction());
            }
            case Rollback rollback -> {
                encoded.put(ROLLBACK);
                putRef(encoded, rollback.transaction());
            }
            case BackendError error -> {
                encoded.put(BACKEND_ERROR);
                putOptionalRef(encoded, error.transaction());
            }
            case CommandComplete complete -> {
                encoded.put(COMMAND_COMPLETE);
                putOptionalRef(encoded, complete.transaction());
                encoded.put(commandTagCode(complete.commandTag()));
            }
            case ReadyForQuery ready -> {
                encoded.put(READY_FOR_QUERY).put(transactionStatusCode(ready.status()));
                putOptionalRef(encoded, ready.transaction());
            }
            case CommitSucceeded succeeded -> {
                encoded.put(COMMIT_SUCCEEDED);
                putRef(encoded, succeeded.transaction());
                encoded.put(transactionStatusCode(succeeded.readyForQueryStatus()));
            }
        }
        byte[] result = new byte[encoded.position()];
        encoded.flip();
        encoded.get(result);
        return result;
    }

    @Override
    public PostgresqlEvidence decode(byte[] encodedEvidence) {
        if (encodedEvidence == null) {
            throw new NullPointerException("encodedEvidence must not be null");
        }
        if (encodedEvidence.length < 2 || encodedEvidence.length > 32) {
            throw new IllegalArgumentException("Invalid encoded PostgreSQL evidence");
        }
        try {
            ByteBuffer encoded = ByteBuffer.wrap(encodedEvidence).order(ByteOrder.BIG_ENDIAN);
            byte type = encoded.get();
            PostgresqlEvidence evidence = switch (type) {
                case PROTOCOL_MESSAGE -> new ProtocolMessage(
                    protocolMessageKind(encoded.get())
                );
                case TRANSACTION_STARTED -> new TransactionStarted(
                    getRef(encoded),
                    transactionStatus(encoded.get())
                );
                case STATEMENT_EXECUTED -> new StatementExecuted(
                    getRef(encoded),
                    statementKind(encoded.get())
                );
                case AUTOCOMMIT_WRITE -> new AutocommitWrite(
                    statementKind(encoded.get())
                );
                case COMMIT_ATTEMPT -> new CommitAttempt(getRef(encoded));
                case ROLLBACK -> new Rollback(getRef(encoded));
                case BACKEND_ERROR -> new BackendError(getOptionalRef(encoded));
                case COMMAND_COMPLETE -> new CommandComplete(
                    getOptionalRef(encoded),
                    commandTag(encoded.get())
                );
                case READY_FOR_QUERY -> new ReadyForQuery(
                    transactionStatus(encoded.get()),
                    getOptionalRef(encoded)
                );
                case COMMIT_SUCCEEDED -> new CommitSucceeded(
                    getRef(encoded),
                    transactionStatus(encoded.get())
                );
                default -> throw new IllegalArgumentException(
                    "Unsupported encoded PostgreSQL evidence type"
                );
            };
            if (encoded.hasRemaining()) {
                throw new IllegalArgumentException("Trailing encoded PostgreSQL evidence bytes");
            }
            return evidence;
        } catch (java.nio.BufferUnderflowException failure) {
            throw new IllegalArgumentException("Truncated encoded PostgreSQL evidence");
        }
    }

    private static void putOptionalRef(
        ByteBuffer target,
        Optional<TransactionRef> reference
    ) {
        target.put((byte) (reference.isPresent() ? 1 : 0));
        reference.ifPresent(value -> putRef(target, value));
    }

    private static Optional<TransactionRef> getOptionalRef(ByteBuffer source) {
        return switch (source.get()) {
            case 0 -> Optional.empty();
            case 1 -> Optional.of(getRef(source));
            default -> throw new IllegalArgumentException(
                "Invalid optional transaction reference marker"
            );
        };
    }

    private static void putRef(ByteBuffer target, TransactionRef reference) {
        target.putLong(reference.sessionOrdinal()).putLong(reference.transactionOrdinal());
    }

    private static TransactionRef getRef(ByteBuffer source) {
        return new TransactionRef(source.getLong(), source.getLong());
    }

    private static byte protocolMessageKindCode(ProtocolMessageKind kind) {
        return switch (kind) {
            case SSL_REQUEST -> 1;
            case SSL_REJECTED -> 2;
            case STARTUP_MESSAGE -> 3;
            case AUTHENTICATION -> 4;
            case AUTHENTICATION_PAYLOAD -> 5;
            case PARAMETER_STATUS -> 6;
            case BACKEND_KEY_DATA -> 7;
            case PARSE_COMPLETE -> 8;
            case BIND_COMPLETE -> 9;
            case CLOSE_COMPLETE -> 10;
            case NO_DATA -> 11;
            case ROW_DESCRIPTION -> 12;
            case DATA_ROW -> 13;
            case NOTICE_RESPONSE -> 14;
            case TERMINATE -> 15;
            case OTHER -> 16;
        };
    }

    private static ProtocolMessageKind protocolMessageKind(byte code) {
        return switch (code) {
            case 1 -> ProtocolMessageKind.SSL_REQUEST;
            case 2 -> ProtocolMessageKind.SSL_REJECTED;
            case 3 -> ProtocolMessageKind.STARTUP_MESSAGE;
            case 4 -> ProtocolMessageKind.AUTHENTICATION;
            case 5 -> ProtocolMessageKind.AUTHENTICATION_PAYLOAD;
            case 6 -> ProtocolMessageKind.PARAMETER_STATUS;
            case 7 -> ProtocolMessageKind.BACKEND_KEY_DATA;
            case 8 -> ProtocolMessageKind.PARSE_COMPLETE;
            case 9 -> ProtocolMessageKind.BIND_COMPLETE;
            case 10 -> ProtocolMessageKind.CLOSE_COMPLETE;
            case 11 -> ProtocolMessageKind.NO_DATA;
            case 12 -> ProtocolMessageKind.ROW_DESCRIPTION;
            case 13 -> ProtocolMessageKind.DATA_ROW;
            case 14 -> ProtocolMessageKind.NOTICE_RESPONSE;
            case 15 -> ProtocolMessageKind.TERMINATE;
            case 16 -> ProtocolMessageKind.OTHER;
            default -> throw invalidEnum();
        };
    }

    private static byte statementKindCode(StatementKind kind) {
        return switch (kind) {
            case INSERT -> 1;
        };
    }

    private static StatementKind statementKind(byte code) {
        return switch (code) {
            case 1 -> StatementKind.INSERT;
            default -> throw invalidEnum();
        };
    }

    private static byte commandTagCode(CommandTag tag) {
        return switch (tag) {
            case BEGIN -> 1;
            case INSERT -> 2;
            case COMMIT -> 3;
            case ROLLBACK -> 4;
            case SELECT -> 5;
            case SHOW -> 6;
            case CREATE -> 7;
            case DROP -> 8;
            case OTHER -> 9;
        };
    }

    private static CommandTag commandTag(byte code) {
        return switch (code) {
            case 1 -> CommandTag.BEGIN;
            case 2 -> CommandTag.INSERT;
            case 3 -> CommandTag.COMMIT;
            case 4 -> CommandTag.ROLLBACK;
            case 5 -> CommandTag.SELECT;
            case 6 -> CommandTag.SHOW;
            case 7 -> CommandTag.CREATE;
            case 8 -> CommandTag.DROP;
            case 9 -> CommandTag.OTHER;
            default -> throw invalidEnum();
        };
    }

    private static byte transactionStatusCode(TransactionStatus status) {
        return switch (status) {
            case IDLE -> 1;
            case TRANSACTION -> 2;
            case FAILED -> 3;
        };
    }

    private static TransactionStatus transactionStatus(byte code) {
        return switch (code) {
            case 1 -> TransactionStatus.IDLE;
            case 2 -> TransactionStatus.TRANSACTION;
            case 3 -> TransactionStatus.FAILED;
            default -> throw invalidEnum();
        };
    }

    private static IllegalArgumentException invalidEnum() {
        return new IllegalArgumentException("Invalid encoded PostgreSQL evidence enum");
    }
}
