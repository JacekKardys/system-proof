package io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.SmsPersistence;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.TestSms;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityRequirements;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityResult;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityVerifier;

/** SMS persistence probes used by the system-test happy path. */
public final class SmsDatabaseOperations {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final Supplier<String> events;
    private volatile SmsPersistence lastObservedState;

    public SmsDatabaseOperations(String jdbcUrl, String username, String password, Supplier<String> events) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
        this.username = Objects.requireNonNull(username, "username must not be null");
        this.password = Objects.requireNonNull(password, "password must not be null");
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    public DatabaseAwait await() {
        return new DatabaseAwait(this);
    }

    /** Captures the current independent-connection persistence state without awaiting changes. */
    public SmsPersistence snapshot(TestSms message) {
        Objects.requireNonNull(message, "message must not be null");
        String sql = """
            WITH matching_raw AS (
                SELECT *
                FROM raw_sms_event
                WHERE source_address = ?
                  AND destination_address = ?
                  AND content = ?
            )
            SELECT
                (SELECT COUNT(*) FROM matching_raw) AS raw_count,
                (SELECT COUNT(*) FROM outbox_event o JOIN matching_raw r ON r.id = o.aggregate_id) AS outbox_count,
                (SELECT id::text FROM matching_raw LIMIT 1) AS raw_id,
                (SELECT o.aggregate_id::text FROM outbox_event o JOIN matching_raw r ON r.id = o.aggregate_id LIMIT 1)
                    AS outbox_aggregate_id,
                (SELECT external_message_id FROM matching_raw LIMIT 1) AS external_message_id,
                (SELECT source_address FROM matching_raw LIMIT 1) AS source_address,
                (SELECT destination_address FROM matching_raw LIMIT 1) AS destination_address,
                (SELECT content FROM matching_raw LIMIT 1) AS content
            """;
        try (Connection connection = connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, message.sourceAddress());
            statement.setString(2, message.destinationAddress());
            statement.setString(3, message.content());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                SmsPersistence observed = new SmsPersistence(
                    result.getLong("raw_count"),
                    result.getLong("outbox_count"),
                    result.getString("raw_id"),
                    result.getString("outbox_aggregate_id"),
                    result.getString("external_message_id"),
                    result.getString("source_address"),
                    result.getString("destination_address"),
                    result.getString("content")
                );
                lastObservedState = observed;
                return observed;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot inspect SMS persistence for '" + message.id() + "'", exception);
        }
    }

    /** Runs the pre-proof durability preflight through a new test-owned connection. */
    public PostgresqlDurabilityResult durabilityPreflight(
        PostgresqlDurabilityRequirements requirements
    ) {
        Objects.requireNonNull(requirements, "requirements must not be null");
        try (Connection connection = connect()) {
            return PostgresqlDurabilityVerifier.verify(connection, requirements);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Cannot run the PostgreSQL durability preflight",
                exception
            );
        }
    }

    /** Installs a test-owned database rule that rejects outbox writes until closed. */
    public OutboxRejection rejectOutboxInserts() {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute("""
                CREATE OR REPLACE FUNCTION reject_outbox_insert() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'outbox rejected by attribution test';
                END;
                $$ LANGUAGE plpgsql
                """);
            statement.execute("""
                CREATE TRIGGER reject_outbox
                BEFORE INSERT ON outbox_event
                FOR EACH ROW EXECUTE FUNCTION reject_outbox_insert()
                """);
            return new OutboxRejection(this);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Cannot install the controlled outbox rejection",
                exception
            );
        }
    }

    private void allowOutboxInserts() {
        try (Connection connection = connect();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TRIGGER IF EXISTS reject_outbox ON outbox_event");
            statement.execute("DROP FUNCTION IF EXISTS reject_outbox_insert()");
        } catch (SQLException exception) {
            throw new IllegalStateException(
                "Cannot remove the controlled outbox rejection",
                exception
            );
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private String diagnostics() {
        SmsPersistence observed = lastObservedState;
        String lastResult = observed == null
            ? "<not observed>"
            : "rawCount=" + observed.rawCount()
                + ", outboxCount=" + observed.outboxCount();
        return "last database result=" + lastResult
            + System.lineSeparator() + "Environment events:"
            + System.lineSeparator() + events.get();
    }

    public static final class DatabaseAwait {
        private final SmsDatabaseOperations database;

        private DatabaseAwait(SmsDatabaseOperations database) {
            this.database = database;
        }

        public SmsPersistence rawAndOutboxVisible(TestSms message) {
            Objects.requireNonNull(message, "message must not be null");
            try {
                return Awaitility.await("RAW and Outbox rows visible for SMS " + message.id())
                    .atMost(DEFAULT_TIMEOUT)
                    .pollInterval(Duration.ofMillis(250))
                    .until(
                        () -> database.snapshot(message),
                        observed -> observed.rawCount() > 0 && observed.outboxCount() > 0
                    );
            } catch (ConditionTimeoutException timeout) {
                throw new IllegalStateException(
                    "Timed out waiting for SMS '" + message.id() + "' to be persisted; "
                        + database.diagnostics(),
                    timeout
                );
            }
        }
    }

    /** One idempotent lease for the controlled outbox-rejection rule. */
    public static final class OutboxRejection implements AutoCloseable {
        private final SmsDatabaseOperations database;
        private final AtomicBoolean closed = new AtomicBoolean();

        private OutboxRejection(SmsDatabaseOperations database) {
            this.database = database;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                database.allowOutboxInserts();
            }
        }
    }
}
