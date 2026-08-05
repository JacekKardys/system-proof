package io.github.jacekkardys.systemproof.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Capability;
import io.github.jacekkardys.systemproof.observation.RequiredObservationProfile.Feature;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.AutocommitWrite;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.BackendError;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommandComplete;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommandTag;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommitAttempt;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.CommitSucceeded;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.ReadyForQuery;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.Rollback;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.StatementExecuted;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.StatementKind;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.TransactionStarted;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.TransactionStatus;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlWriteInteraction.ParameterFormat;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolDecodeResult;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolStream;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit;

class PostgresqlTransactionStateTest {
    private static final AtomicInteger NEXT_BACKEND_PID = new AtomicInteger(1000);
    private static final io.github.jacekkardys.systemproof.topology.ConnectionId CONNECTION =
        io.github.jacekkardys.systemproof.topology.ConnectionId.of(
            "client[].jdbc->postgres[].jdbc"
        );
    private static final ProtocolLimits LIMITS = new ProtocolLimits(16 * 1024, 32 * 1024);
    private static final CorrelationKeySchema KEY_SCHEMA = new CorrelationKeySchema(
        "system-proof.test",
        "marker",
        1
    );

    @Test
    void shouldDeclareCorrelationOnlyWhenTheAdapterHasACorrelationPolicy() {
        var plain = new PostgresqlProtocolAdapter().observationContract().orElseThrow();
        var correlating = new PostgresqlProtocolAdapter(
            ignored -> Optional.empty()
        ).observationContract().orElseThrow();

        assertThat(plain.capabilities())
            .doesNotContain(Capability.CORRELATION_CONTRIBUTIONS);
        assertThat(plain.nativeFlowReferenceSchema()).isEmpty();
        assertThat(correlating.capabilities())
            .contains(Capability.CORRELATION_CONTRIBUTIONS);
        assertThat(correlating.nativeFlowReferenceSchema())
            .contains(TransactionRef.codec().schemaId());
        assertThat(plain.supportedFeatures())
            .doesNotContain(
                Feature.ENCRYPTED_TRANSPORT,
                Feature.GENERAL_PIPELINING
            );
    }

    @Test
    void shouldCorrelateOneExplicitWriteAndRequireCommitCompleteThenReadyIdle()
        throws Exception {
        List<PostgresqlWriteInteraction> expiredViews = new ArrayList<>();
        PostgresqlProtocolAdapter adapter = new PostgresqlProtocolAdapter(interaction -> {
            expiredViews.add(interaction);
            assertThat(interaction.shape().table()).isEqualTo("proof_entry");
            assertThat(interaction.parameterCount()).isEqualTo(2);
            return Optional.of(key(interaction.parameterBytes(1)));
        });
        Harness harness = started(adapter);
        TransactionRef transaction = begin(harness);

        ProtocolUnit<PostgresqlEvidence> write = complete(
            harness.frontend,
            insertUnit("", "", "marker", "token")
        );
        assertThat(write.evidence()).isEqualTo(
            new StatementExecuted(transaction, StatementKind.INSERT)
        );
        assertThat(write.correlationContributions()).hasSize(1);
        assertThatThrownBy(expiredViews.getFirst()::shape)
            .isInstanceOf(IllegalStateException.class);
        finishInsertInTransaction(harness);
        byte[] commitBytes = commitUnit("commit_1");
        ProtocolUnit<PostgresqlEvidence> attempt = complete(harness.frontend, commitBytes);
        assertThat(attempt.originalBytes()).containsExactly(commitBytes);
        assertThat(attempt.evidence()).isEqualTo(new CommitAttempt(transaction));

        PostgresqlEvidence commandComplete = complete(
            harness.backend,
            PostgresqlFrames.commandComplete("COMMIT")
        ).evidence();
        assertThat(commandComplete).isEqualTo(
            new CommandComplete(Optional.of(transaction), CommandTag.COMMIT)
        );
        assertThat(commandComplete).isNotInstanceOf(CommitSucceeded.class);

        assertThat(complete(harness.backend, PostgresqlFrames.notice()).evidence())
            .isNotInstanceOf(CommitSucceeded.class);
        assertThat(complete(harness.backend, PostgresqlFrames.ready('I')).evidence())
            .isEqualTo(new CommitSucceeded(transaction));
    }

    @Test
    void shouldEmitCommitSuccessFromTheMatchingProtocolConfirmationAlone()
        throws Exception {
        PostgresqlProtocolAdapter adapter = new PostgresqlProtocolAdapter();
        Harness harness = started(adapter);
        TransactionRef transaction = begin(harness);
        complete(harness.frontend, commitUnit("commit_unverified"));
        complete(harness.backend, PostgresqlFrames.commandComplete("COMMIT"));

        assertThat(complete(harness.backend, PostgresqlFrames.ready('I')).evidence())
            .isEqualTo(new CommitSucceeded(transaction));
    }

    @Test
    void shouldRejectDirectSynchronousCommitChanges()
        throws Exception {
        for (String statement : List.of(
            "SET synchronous_commit = off",
            "SET LOCAL synchronous_commit = off",
            "SET SESSION synchronous_commit = off",
            "RESET synchronous_commit"
        )) {
            Harness harness = started(new PostgresqlProtocolAdapter());
            begin(harness);
            assertUnsupported(harness.frontend, PostgresqlFrames.query(statement));
        }
    }

    @Test
    void shouldRejectSafelyRecognizableSetConfigChanges() throws Exception {
        for (String statement : List.of(
            "SELECT set_config('synchronous_commit', 'off', false)",
            "SELECT pg_catalog.set_config('synchronous_commit', $1, true)"
        )) {
            Harness harness = started(new PostgresqlProtocolAdapter());
            begin(harness);
            assertUnsupported(harness.frontend, PostgresqlFrames.query(statement));
        }
    }

    @Test
    void shouldNotClaimToAnalyzeProcedureBodies() throws Exception {
        Harness harness = started(new PostgresqlProtocolAdapter());
        TransactionRef transaction = begin(harness);

        complete(
            harness.frontend,
            PostgresqlFrames.query("CALL change_commit_setting()")
        );
        complete(harness.backend, PostgresqlFrames.commandComplete("CALL"));
        complete(harness.backend, PostgresqlFrames.ready('T'));
        complete(harness.frontend, commitUnit("commit_after_call"));
        complete(harness.backend, PostgresqlFrames.parseComplete());
        complete(harness.backend, PostgresqlFrames.bindComplete());
        complete(harness.backend, PostgresqlFrames.commandComplete("COMMIT"));

        assertThat(complete(harness.backend, PostgresqlFrames.ready('I')).evidence())
            .isEqualTo(new CommitSucceeded(transaction));
    }

    @Test
    void shouldUseNamedStatementsAfterThresholdAndAdvanceOrdinalOnPoolReuse()
        throws Exception {
        Harness harness = started(new PostgresqlProtocolAdapter());
        TransactionRef first = begin(harness);
        complete(harness.frontend, insertUnit("insert_1", "", "one", "first"));
        finishInsertInTransaction(harness);
        complete(harness.frontend, commitUnit("commit_1"));
        complete(harness.backend, PostgresqlFrames.parseComplete());
        complete(harness.backend, PostgresqlFrames.bindComplete());
        complete(harness.backend, PostgresqlFrames.commandComplete("COMMIT"));
        complete(harness.backend, PostgresqlFrames.ready('I'));

        TransactionRef second = begin(harness);
        ProtocolUnit<PostgresqlEvidence> reused = complete(
            harness.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.bind("", "insert_1", "two", "second"),
                PostgresqlFrames.execute(""),
                PostgresqlFrames.sync()
            )
        );

        assertThat(second.sessionOrdinal()).isEqualTo(first.sessionOrdinal());
        assertThat(second.transactionOrdinal()).isEqualTo(first.transactionOrdinal() + 1);
        assertThat(reused.evidence()).isEqualTo(
            new StatementExecuted(second, StatementKind.INSERT)
        );
    }

    @Test
    void shouldSupportPgjdbcBeginLookaheadQuotedInsertAndPositiveUpdateRowLimit()
        throws Exception {
        Harness harness = started(new PostgresqlProtocolAdapter());
        complete(harness.frontend, PostgresqlFrames.query("BEGIN"));
        ProtocolUnit<PostgresqlEvidence> write = complete(
            harness.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.parse(
                    "flyway_insert",
                    "INSERT INTO \"flyway_schema_history\" "
                        + "(\"installed_rank\", \"success\") VALUES ($1, $2)"
                ),
                PostgresqlFrames.bind("", "flyway_insert", "1", "true"),
                PostgresqlFrames.execute("", 1),
                PostgresqlFrames.sync()
            )
        );
        StatementExecuted execution = (StatementExecuted) write.evidence();

        complete(harness.backend, PostgresqlFrames.commandComplete("BEGIN"));
        TransactionStarted started = (TransactionStarted) complete(
            harness.backend,
            PostgresqlFrames.ready('T')
        ).evidence();

        assertThat(execution.transaction()).isEqualTo(started.transaction());
        finishInsertInTransaction(harness);
    }

    @Test
    void shouldNotInferArityForUninterpretedParameterizedSelect() throws Exception {
        Harness harness = started(new PostgresqlProtocolAdapter());

        complete(
            harness.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.parse(
                    "flyway_schema_exists",
                    "SELECT 1 FROM pg_namespace WHERE nspname = $1",
                    25
                ),
                PostgresqlFrames.bind(
                    "",
                    "flyway_schema_exists",
                    "public"
                ),
                PostgresqlFrames.execute(""),
                PostgresqlFrames.sync()
            )
        );
    }

    @Test
    void shouldExposeBindFormatsAndParseTypeOidsToCorrelationPolicy()
        throws Exception {
        PostgresqlProtocolAdapter adapter = new PostgresqlProtocolAdapter(interaction -> {
            assertThat(interaction.parameterFormat(0)).isEqualTo(ParameterFormat.TEXT);
            assertThat(interaction.parameterFormat(1)).isEqualTo(ParameterFormat.BINARY);
            assertThat(interaction.parameterTypeOid(0)).hasValue(20L);
            assertThat(interaction.parameterTypeOid(1)).hasValue(17L);
            return Optional.of(key(interaction.parameterBytes(1)));
        });
        Harness harness = started(adapter);
        begin(harness);

        ProtocolUnit<PostgresqlEvidence> write = complete(
            harness.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.parse(
                    "insert_formats",
                    "INSERT INTO proof_entry (value, marker) VALUES ($1, $2)",
                    20,
                    17
                ),
                PostgresqlFrames.bind(
                    "",
                    "insert_formats",
                    new int[] {0, 1},
                    "text",
                    "binary"
                ),
                PostgresqlFrames.execute(""),
                PostgresqlFrames.sync()
            )
        );

        assertThat(write.correlationContributions()).hasSize(1);
    }

    @Test
    void shouldRejectReversedInsertPlaceholdersAndUnsupportedBindFormat()
        throws Exception {
        Harness reversed = started(new PostgresqlProtocolAdapter());
        begin(reversed);

        assertUnsupported(
            reversed.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.parse(
                    "reversed",
                    "INSERT INTO proof_entry (value, marker) VALUES ($2, $1)"
                ),
                PostgresqlFrames.sync()
            )
        );

        Harness format = started(new PostgresqlProtocolAdapter());
        begin(format);
        assertUnsupported(
            format.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.parse(
                    "unsupported_format",
                    "INSERT INTO proof_entry (value, marker) VALUES ($1, $2)"
                ),
                PostgresqlFrames.bind(
                    "",
                    "unsupported_format",
                    new int[] {2},
                    "one",
                    "two"
                ),
                PostgresqlFrames.execute(""),
                PostgresqlFrames.sync()
            )
        );
    }

    @Test
    void shouldFailClosedWhenPgjdbcBeginLookaheadCannotBeActivated() throws Exception {
        Harness harness = started(new PostgresqlProtocolAdapter());
        complete(harness.frontend, PostgresqlFrames.query("BEGIN"));
        complete(harness.frontend, insertUnit("insert_1", "", "one", "marker"));
        complete(harness.backend, PostgresqlFrames.error());

        assertThatThrownBy(() -> complete(
            harness.backend,
            PostgresqlFrames.ready('I')
        )).isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
            assertThat(failure.kind()).isEqualTo(ProtocolFailureKind.DESYNCHRONIZATION)
        );
    }

    @Test
    void shouldAssignDifferentSessionIdentityAfterReconnectAndAcrossConcurrentSessions()
        throws Exception {
        PostgresqlProtocolAdapter adapter = new PostgresqlProtocolAdapter();
        Harness firstHarness = started(adapter);
        Harness secondHarness = started(adapter);

        TransactionRef first = begin(firstHarness);
        TransactionRef second = begin(secondHarness);

        assertThat(first.sessionOrdinal()).isNotEqualTo(second.sessionOrdinal());
        assertThat(first.transactionOrdinal()).isEqualTo(1);
        assertThat(second.transactionOrdinal()).isEqualTo(1);
    }

    @Test
    void shouldNeverSucceedForRollbackBackendErrorOrFailedTransaction() throws Exception {
        Harness harness = started(new PostgresqlProtocolAdapter());
        TransactionRef transaction = begin(harness);
        complete(harness.frontend, insertUnit("insert_1", "", "one", "first"));
        complete(harness.backend, PostgresqlFrames.parseComplete());
        complete(harness.backend, PostgresqlFrames.bindComplete());
        PostgresqlEvidence error = complete(harness.backend, PostgresqlFrames.error()).evidence();
        assertThat(error).isEqualTo(new BackendError(Optional.of(transaction)));
        assertThat(complete(harness.backend, PostgresqlFrames.ready('E')).evidence())
            .isEqualTo(new ReadyForQuery(
                TransactionStatus.FAILED,
                Optional.of(transaction)
            ));

        ProtocolUnit<PostgresqlEvidence> rollback = complete(
            harness.frontend,
            rollbackUnit("rollback_1")
        );
        assertThat(rollback.evidence()).isEqualTo(new Rollback(transaction));
        complete(harness.backend, PostgresqlFrames.parseComplete());
        complete(harness.backend, PostgresqlFrames.bindComplete());
        assertThat(complete(
            harness.backend,
            PostgresqlFrames.commandComplete("ROLLBACK")
        ).evidence()).isNotInstanceOf(CommitSucceeded.class);
        assertThat(complete(harness.backend, PostgresqlFrames.ready('I')).evidence())
            .isEqualTo(new ReadyForQuery(
                TransactionStatus.IDLE,
                Optional.of(transaction)
            ));
    }

    @Test
    void shouldNotSucceedAfterDisconnectBetweenCommitCompleteAndReady() throws Exception {
        Harness harness = started(new PostgresqlProtocolAdapter());
        TransactionRef transaction = begin(harness);
        complete(harness.frontend, commitUnit("commit_1"));
        complete(harness.backend, PostgresqlFrames.parseComplete());
        complete(harness.backend, PostgresqlFrames.bindComplete());
        assertThat(complete(
            harness.backend,
            PostgresqlFrames.commandComplete("COMMIT")
        ).evidence()).isEqualTo(
            new CommandComplete(Optional.of(transaction), CommandTag.COMMIT)
        );

        harness.backend.endOfInput(ByteBuffer.allocate(0));

        assertThatThrownBy(() -> harness.backend.decode(
            ByteBuffer.wrap(PostgresqlFrames.ready('I'))
        )).isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
            assertThat(failure.kind()).isEqualTo(ProtocolFailureKind.DESYNCHRONIZATION)
        );
    }

    @Test
    void shouldFailClosedForMismatchedDuplicateOrMissingBackendCompletion()
        throws Exception {
        Harness mismatched = started(new PostgresqlProtocolAdapter());
        begin(mismatched);
        complete(mismatched.frontend, commitUnit("commit_1"));
        assertThatThrownBy(() -> complete(
            mismatched.backend,
            PostgresqlFrames.commandComplete("ROLLBACK")
        )).isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
            assertThat(failure.kind()).isEqualTo(ProtocolFailureKind.DESYNCHRONIZATION)
        );

        Harness duplicate = started(new PostgresqlProtocolAdapter());
        begin(duplicate);
        complete(duplicate.frontend, commitUnit("commit_1"));
        complete(duplicate.backend, PostgresqlFrames.commandComplete("COMMIT"));
        assertThatThrownBy(() -> complete(
            duplicate.backend,
            PostgresqlFrames.commandComplete("COMMIT")
        )).isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
            assertThat(failure.kind()).isEqualTo(ProtocolFailureKind.DESYNCHRONIZATION)
        );

        Harness missing = started(new PostgresqlProtocolAdapter());
        begin(missing);
        complete(missing.frontend, insertUnit("insert_1", "", "one", "marker"));
        assertThatThrownBy(() -> complete(
            missing.backend,
            PostgresqlFrames.ready('T')
        )).isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
            assertThat(failure.kind()).isEqualTo(ProtocolFailureKind.DESYNCHRONIZATION)
        );
    }

    @Test
    void shouldTreatAutocommitWriteAsNonTransactional() throws Exception {
        Harness harness = started(new PostgresqlProtocolAdapter());

        ProtocolUnit<PostgresqlEvidence> write = complete(
            harness.frontend,
            insertUnit("insert_1", "", "one", "first")
        );

        assertThat(write.evidence()).isEqualTo(new AutocommitWrite(StatementKind.INSERT));
        assertThat(write.correlationContributions()).isEmpty();
        finishInsertCommand(harness);
        assertThat(complete(harness.backend, PostgresqlFrames.ready('I')).evidence())
            .isNotInstanceOf(CommitSucceeded.class);
    }

    @Test
    void shouldRejectMultiStatementPipeliningUnknownPortalAndFlushCommit() throws Exception {
        Harness multi = started(new PostgresqlProtocolAdapter());
        assertUnsupported(multi.frontend, PostgresqlFrames.query("BEGIN; COMMIT"));

        Harness pipeline = started(new PostgresqlProtocolAdapter());
        assertUnsupported(
            pipeline.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.parse("a", "SELECT 1"),
                PostgresqlFrames.bind("a_portal", "a"),
                PostgresqlFrames.execute("a_portal"),
                PostgresqlFrames.parse("b", "SELECT 2"),
                PostgresqlFrames.bind("b_portal", "b"),
                PostgresqlFrames.execute("b_portal"),
                PostgresqlFrames.sync()
            )
        );

        Harness partialPortal = started(new PostgresqlProtocolAdapter());
        assertUnsupported(
            partialPortal.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.parse("select", "SELECT 1"),
                PostgresqlFrames.bind("", "select"),
                PostgresqlFrames.execute("", 1),
                PostgresqlFrames.sync()
            )
        );

        Harness preparedCommit = started(new PostgresqlProtocolAdapter());
        assertUnsupported(
            preparedCommit.frontend,
            PostgresqlFrames.query("COMMIT PREPARED")
        );

        Harness unknown = started(new PostgresqlProtocolAdapter());
        assertThatThrownBy(() -> complete(
            unknown.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.execute("missing"),
                PostgresqlFrames.sync()
            )
        )).isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
            assertThat(failure.kind()).isEqualTo(ProtocolFailureKind.DESYNCHRONIZATION)
        );

        Harness flush = started(new PostgresqlProtocolAdapter());
        begin(flush);
        assertUnsupported(
            flush.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.parse("commit", "COMMIT"),
                PostgresqlFrames.bind("", "commit"),
                PostgresqlFrames.execute(""),
                PostgresqlFrames.flush(),
                PostgresqlFrames.sync()
            )
        );
    }

    @Test
    void shouldInvalidatePortalsWhenTheirNamedStatementCloses() throws Exception {
        Harness harness = started(new PostgresqlProtocolAdapter());
        complete(
            harness.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.parse("statement", "SELECT 1"),
                PostgresqlFrames.bind("portal", "statement"),
                PostgresqlFrames.closeStatement("statement"),
                PostgresqlFrames.sync()
            )
        );

        assertThatThrownBy(() -> complete(
            harness.frontend,
            PostgresqlFrames.concat(
                PostgresqlFrames.execute("portal"),
                PostgresqlFrames.sync()
            )
        )).isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
            assertThat(failure.kind()).isEqualTo(ProtocolFailureKind.DESYNCHRONIZATION)
        );
    }

    @Test
    void shouldKeepDuplicateTokensAndSessionsIndependent() throws Exception {
        PostgresqlProtocolAdapter adapter = new PostgresqlProtocolAdapter(
            interaction -> Optional.of(key(interaction.parameterBytes(1)))
        );
        Harness left = started(adapter);
        Harness right = started(adapter);
        begin(left);
        begin(right);

        ProtocolUnit<PostgresqlEvidence> leftWrite = complete(
            left.frontend,
            insertUnit("left", "", "left", "duplicate")
        );
        ProtocolUnit<PostgresqlEvidence> rightWrite = complete(
            right.frontend,
            insertUnit("right", "", "right", "duplicate")
        );

        assertThat(leftWrite.correlationContributions()).hasSize(1);
        assertThat(rightWrite.correlationContributions()).hasSize(1);
        StatementExecuted leftEvidence = (StatementExecuted) leftWrite.evidence();
        StatementExecuted rightEvidence = (StatementExecuted) rightWrite.evidence();
        assertThat(leftEvidence.transaction().sessionOrdinal())
            .isNotEqualTo(rightEvidence.transaction().sessionOrdinal());
    }

    private static TransactionRef begin(Harness harness) throws Exception {
        complete(harness.frontend, PostgresqlFrames.query("BEGIN"));
        complete(harness.backend, PostgresqlFrames.commandComplete("BEGIN"));
        PostgresqlEvidence started = complete(
            harness.backend,
            PostgresqlFrames.ready('T')
        ).evidence();
        assertThat(started).isInstanceOf(TransactionStarted.class);
        return ((TransactionStarted) started).transaction();
    }

    private static void finishInsertInTransaction(Harness harness) throws Exception {
        finishInsertCommand(harness);
        complete(harness.backend, PostgresqlFrames.ready('T'));
    }

    private static void finishInsertCommand(Harness harness) throws Exception {
        complete(harness.backend, PostgresqlFrames.parseComplete());
        complete(harness.backend, PostgresqlFrames.bindComplete());
        complete(harness.backend, PostgresqlFrames.noData());
        complete(harness.backend, PostgresqlFrames.commandComplete("INSERT 0 1"));
    }

    private static byte[] insertUnit(
        String statement,
        String portal,
        String first,
        String marker
    ) {
        return PostgresqlFrames.concat(
            PostgresqlFrames.parse(
                statement,
                "INSERT INTO proof_entry (value, marker) VALUES ($1, $2)"
            ),
            PostgresqlFrames.bind(portal, statement, first, marker),
            PostgresqlFrames.describePortal(portal),
            PostgresqlFrames.execute(portal),
            PostgresqlFrames.sync()
        );
    }

    private static byte[] commitUnit(String statement) {
        return PostgresqlFrames.concat(
            PostgresqlFrames.parse(statement, "COMMIT"),
            PostgresqlFrames.bind("", statement),
            PostgresqlFrames.execute(""),
            PostgresqlFrames.sync()
        );
    }

    private static byte[] rollbackUnit(String statement) {
        return PostgresqlFrames.concat(
            PostgresqlFrames.parse(statement, "ROLLBACK"),
            PostgresqlFrames.bind("", statement),
            PostgresqlFrames.execute(""),
            PostgresqlFrames.sync()
        );
    }

    private static CorrelationKey key(ByteBuffer value) {
        try {
            byte[] bytes = new byte[value.remaining()];
            value.get(bytes);
            return CorrelationKey.ofDigest(
                KEY_SCHEMA,
                MessageDigest.getInstance("SHA-256").digest(bytes)
            );
        } catch (java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static Harness started(PostgresqlProtocolAdapter adapter) throws Exception {
        return started(adapter, CONNECTION, NEXT_BACKEND_PID.getAndIncrement());
    }

    private static Harness started(
        PostgresqlProtocolAdapter adapter,
        io.github.jacekkardys.systemproof.topology.ConnectionId connectionId,
        int backendPid
    ) throws Exception {
        ProtocolSession<PostgresqlEvidence> session = adapter.openSession(
            connectionId,
            LIMITS
        );
        ProtocolStream<PostgresqlEvidence> frontend = session.openStream(
            FlowDirection.CONSUMER_TO_PROVIDER
        );
        ProtocolStream<PostgresqlEvidence> backend = session.openStream(
            FlowDirection.PROVIDER_TO_CONSUMER
        );
        complete(frontend, PostgresqlFrames.sslRequest());
        complete(backend, new byte[] {'N'});
        complete(frontend, PostgresqlFrames.startup());
        complete(backend, PostgresqlFrames.backendKeyData(backendPid));
        complete(backend, PostgresqlFrames.ready('I'));
        return new Harness(frontend, backend);
    }

    @SuppressWarnings("unchecked")
    private static ProtocolUnit<PostgresqlEvidence> complete(
        ProtocolStream<PostgresqlEvidence> stream,
        byte[] bytes
    ) throws Exception {
        ProtocolDecodeResult<PostgresqlEvidence> decoded = stream.decode(ByteBuffer.wrap(bytes));
        assertThat(decoded).isInstanceOf(ProtocolDecodeResult.Complete.class);
        return ((ProtocolDecodeResult.Complete<PostgresqlEvidence>) decoded).unit();
    }

    private static void assertUnsupported(
        ProtocolStream<PostgresqlEvidence> stream,
        byte[] bytes
    ) {
        assertThatThrownBy(() -> stream.decode(ByteBuffer.wrap(bytes)))
            .isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
                assertThat(failure.kind())
                    .isEqualTo(ProtocolFailureKind.UNSUPPORTED_NEGOTIATION)
            );
    }

    private record Harness(
        ProtocolStream<PostgresqlEvidence> frontend,
        ProtocolStream<PostgresqlEvidence> backend
    ) {}
}
