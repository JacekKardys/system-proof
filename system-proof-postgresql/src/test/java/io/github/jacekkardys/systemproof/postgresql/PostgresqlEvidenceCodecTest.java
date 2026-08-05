package io.github.jacekkardys.systemproof.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
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

class PostgresqlEvidenceCodecTest {
    private static final TransactionRef TRANSACTION = new TransactionRef(7, 11);

    @Test
    void shouldRoundTripEveryEvidenceVariantThroughVersionOneSchema() {
        EvidenceCodec<PostgresqlEvidence> codec = new PostgresqlProtocolAdapter().evidenceCodec();
        List<PostgresqlEvidence> evidence = List.of(
            new ProtocolMessage(ProtocolMessageKind.STARTUP_MESSAGE),
            new TransactionStarted(TRANSACTION),
            new StatementExecuted(TRANSACTION, StatementKind.INSERT),
            new AutocommitWrite(StatementKind.INSERT),
            new CommitAttempt(TRANSACTION),
            new Rollback(TRANSACTION),
            new BackendError(Optional.of(TRANSACTION)),
            new BackendError(Optional.empty()),
            new CommandComplete(Optional.of(TRANSACTION), CommandTag.COMMIT),
            new ReadyForQuery(TransactionStatus.FAILED, Optional.of(TRANSACTION)),
            new ReadyForQuery(TransactionStatus.IDLE, Optional.empty()),
            new CommitSucceeded(TRANSACTION)
        );

        assertThat(codec.schemaId().namespace()).isEqualTo("system-proof.postgresql");
        assertThat(codec.schemaId().name()).isEqualTo("wire-evidence");
        assertThat(codec.schemaId().version()).isEqualTo(1);
        assertThat(evidence).allSatisfy(value ->
            assertThat(codec.decode(codec.encode(value))).isEqualTo(value)
        );
    }

    @Test
    void shouldRoundTripTransactionReferenceAndRejectMalformedEncoding() {
        EvidenceCodec<TransactionRef> codec = TransactionRef.codec();

        assertThat(codec.schemaId().version()).isEqualTo(1);
        assertThat(codec.decode(codec.encode(TRANSACTION))).isEqualTo(TRANSACTION);
        assertThatThrownBy(() -> codec.decode(new byte[3]))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PostgresqlProtocolAdapter().evidenceCodec()
            .decode(new byte[] {99, 0}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldKeepVersionOneWireCodesIndependentOfEnumDeclarationOrder() {
        EvidenceCodec<PostgresqlEvidence> codec = new PostgresqlProtocolAdapter().evidenceCodec();
        byte[] commitSucceeded = ByteBuffer.allocate(18)
            .order(ByteOrder.BIG_ENDIAN)
            .put((byte) 10)
            .putLong(7)
            .putLong(11)
            .put((byte) 1)
            .array();

        assertThat(codec.encode(new ProtocolMessage(ProtocolMessageKind.STARTUP_MESSAGE)))
            .containsExactly(1, 3);
        assertThat(codec.encode(new CommitSucceeded(TRANSACTION)))
            .containsExactly(commitSucceeded);
        assertThat(codec.decode(commitSucceeded))
            .isEqualTo(new CommitSucceeded(TRANSACTION));
        assertThatThrownBy(() -> codec.decode(new byte[] {1, 99}))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldRenderOnlyTypedSafeFacts() {
        assertThat(new PostgresqlStatementShape(
            PostgresqlStatementShape.Kind.INSERT,
            Optional.of("private_schema"),
            "secret_table",
            List.of("secret_column")
        ).toString())
            .doesNotContain("private_schema", "secret_table", "secret_column");
        assertThat(new CommitSucceeded(TRANSACTION).toString())
            .doesNotContain("sql", "bind", "password");
    }
}
