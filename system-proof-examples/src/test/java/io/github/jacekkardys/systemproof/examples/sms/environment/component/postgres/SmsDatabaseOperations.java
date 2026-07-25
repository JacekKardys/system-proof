package io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.SmsPersistence;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.TestSms;

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

    private SmsPersistence observe(TestSms message) {
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

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, username, password);
    }

    private String diagnostics() {
        return "last database result=" + lastObservedState
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
                        () -> database.observe(message),
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
}
