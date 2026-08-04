package io.github.jacekkardys.systemproof.postgresql;

import java.util.Objects;
import java.util.Optional;

/** Secret-safe typed PostgreSQL wire evidence for the supported plaintext MVP. */
public sealed interface PostgresqlEvidence
    permits PostgresqlEvidence.ProtocolMessage,
        PostgresqlEvidence.TransactionStarted,
        PostgresqlEvidence.StatementExecuted,
        PostgresqlEvidence.AutocommitWrite,
        PostgresqlEvidence.CommitAttempt,
        PostgresqlEvidence.Rollback,
        PostgresqlEvidence.BackendError,
        PostgresqlEvidence.CommandComplete,
        PostgresqlEvidence.ReadyForQuery,
        PostgresqlEvidence.CommitSucceeded {

    /** Safe message classes that carry no payload, SQL, credentials, keys, or values. */
    enum ProtocolMessageKind {
        SSL_REQUEST,
        SSL_REJECTED,
        STARTUP_MESSAGE,
        AUTHENTICATION,
        AUTHENTICATION_PAYLOAD,
        PARAMETER_STATUS,
        BACKEND_KEY_DATA,
        PARSE_COMPLETE,
        BIND_COMPLETE,
        CLOSE_COMPLETE,
        NO_DATA,
        ROW_DESCRIPTION,
        DATA_ROW,
        NOTICE_RESPONSE,
        TERMINATE,
        OTHER
    }

    /** Supported semantic statement classes. */
    enum StatementKind {
        INSERT
    }

    /** Sanitized backend command tags. */
    enum CommandTag {
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

    /** Backend transaction status carried by ReadyForQuery. */
    enum TransactionStatus {
        IDLE,
        TRANSACTION,
        FAILED
    }

    /** A secret-safe protocol classification with every wire payload discarded. */
    record ProtocolMessage(ProtocolMessageKind kind) implements PostgresqlEvidence {
        public ProtocolMessage {
            kind = Objects.requireNonNull(kind, "kind must not be null");
        }
    }

    /** Activation of a new explicit transaction at ReadyForQuery(T). */
    record TransactionStarted(
        TransactionRef transaction,
        TransactionStatus readyForQueryStatus
    ) implements PostgresqlEvidence {
        public TransactionStarted(TransactionRef transaction) {
            this(transaction, TransactionStatus.TRANSACTION);
        }

        public TransactionStarted {
            transaction = Objects.requireNonNull(transaction, "transaction must not be null");
            readyForQueryStatus = Objects.requireNonNull(
                readyForQueryStatus,
                "readyForQueryStatus must not be null"
            );
            if (readyForQueryStatus != TransactionStatus.TRANSACTION) {
                throw new IllegalArgumentException(
                    "TransactionStarted requires ReadyForQuery(TRANSACTION)"
                );
            }
        }
    }

    /** A supported statement execution associated with one explicit transaction. */
    record StatementExecuted(
        TransactionRef transaction,
        StatementKind statementKind
    ) implements PostgresqlEvidence {
        public StatementExecuted {
            transaction = Objects.requireNonNull(transaction, "transaction must not be null");
            statementKind = Objects.requireNonNull(
                statementKind,
                "statementKind must not be null"
            );
        }
    }

    /** A write observed outside an explicit transaction; never commit-success evidence. */
    record AutocommitWrite(StatementKind statementKind) implements PostgresqlEvidence {
        public AutocommitWrite {
            statementKind = Objects.requireNonNull(
                statementKind,
                "statementKind must not be null"
            );
        }
    }

    /**
     * A complete supported frontend commit unit at the observe-before-forward control point.
     * This is an attempt, not evidence that any byte was forwarded or that commit succeeded.
     */
    record CommitAttempt(TransactionRef transaction) implements PostgresqlEvidence {
        public CommitAttempt {
            transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        }
    }

    /** A complete supported frontend rollback unit for one explicit transaction. */
    record Rollback(TransactionRef transaction) implements PostgresqlEvidence {
        public Rollback {
            transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        }
    }

    /** A backend ErrorResponse classified without retaining its fields or message text. */
    record BackendError(Optional<TransactionRef> transaction) implements PostgresqlEvidence {
        public BackendError {
            transaction = Objects.requireNonNull(transaction, "transaction must not be null");
        }
    }

    /** A sanitized backend CommandComplete causally assigned to the current frontend cycle. */
    record CommandComplete(
        Optional<TransactionRef> transaction,
        CommandTag commandTag
    ) implements PostgresqlEvidence {
        public CommandComplete {
            transaction = Objects.requireNonNull(transaction, "transaction must not be null");
            commandTag = Objects.requireNonNull(commandTag, "commandTag must not be null");
        }
    }

    /** Backend ReadyForQuery status causally assigned to the current frontend cycle. */
    record ReadyForQuery(
        TransactionStatus status,
        Optional<TransactionRef> transaction
    ) implements PostgresqlEvidence {
        public ReadyForQuery {
            status = Objects.requireNonNull(status, "status must not be null");
            transaction = Objects.requireNonNull(transaction, "transaction must not be null");
            if (status != TransactionStatus.IDLE && transaction.isEmpty()) {
                throw new IllegalArgumentException(
                    "Non-idle ReadyForQuery must identify an explicit transaction"
                );
            }
        }
    }

    /**
     * Final success for one commit after its forwarded unit, CommandComplete(COMMIT), and the
     * immediately following ReadyForQuery(I) without an intervening terminal condition.
     */
    record CommitSucceeded(
        TransactionRef transaction,
        TransactionStatus readyForQueryStatus
    ) implements PostgresqlEvidence {
        public CommitSucceeded(TransactionRef transaction) {
            this(transaction, TransactionStatus.IDLE);
        }

        public CommitSucceeded {
            transaction = Objects.requireNonNull(transaction, "transaction must not be null");
            readyForQueryStatus = Objects.requireNonNull(
                readyForQueryStatus,
                "readyForQueryStatus must not be null"
            );
            if (readyForQueryStatus != TransactionStatus.IDLE) {
                throw new IllegalArgumentException(
                    "CommitSucceeded requires ReadyForQuery(IDLE)"
                );
            }
        }
    }
}
