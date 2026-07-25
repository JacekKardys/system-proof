package io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.SmsPersistence;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.TestSms;

@Testcontainers
class SmsDatabaseOperationsIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @BeforeEach
    void createSchema() throws SQLException {
        try (Connection connection = connection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS outbox_event");
            statement.execute("DROP TABLE IF EXISTS raw_sms_event");
            statement.execute("""
                CREATE TABLE raw_sms_event (
                    id UUID PRIMARY KEY,
                    external_message_id TEXT NOT NULL,
                    source_address TEXT NOT NULL,
                    destination_address TEXT NOT NULL,
                    content TEXT NOT NULL
                )
                """);
            statement.execute("""
                CREATE TABLE outbox_event (
                    id UUID PRIMARY KEY,
                    aggregate_id UUID NOT NULL,
                    event_type TEXT NOT NULL
                )
                """);
        }
    }

    @Test
    void treatsPercentAndUnderscoreAsLiteralCorrelationCharacters() throws SQLException {
        TestSms expected = new TestSms(
            "literal-wildcards",
            "48111000111",
            "99001",
            "literal%_content"
        );
        UUID expectedRawId = insertPersistedSms(expected, "expected-message");
        insertPersistedSms(
            new TestSms(
                "like-decoy",
                expected.sourceAddress(),
                expected.destinationAddress(),
                "literal-anyXcontent"
            ),
            "decoy-message"
        );
        var database = new SmsDatabaseOperations(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword(),
            () -> "<no environment events>"
        );

        SmsPersistence persisted = database.await().rawAndOutboxVisible(expected);

        assertThat(persisted.rawCount()).isEqualTo(1);
        assertThat(persisted.outboxCount()).isEqualTo(1);
        assertThat(persisted.rawId()).isEqualTo(expectedRawId.toString());
        assertThat(persisted.outboxAggregateId()).isEqualTo(expectedRawId.toString());
        assertThat(persisted.content()).isEqualTo("literal%_content");
    }

    private UUID insertPersistedSms(TestSms sms, String externalMessageId) throws SQLException {
        UUID rawId = UUID.randomUUID();
        try (Connection connection = connection();
             PreparedStatement raw = connection.prepareStatement("""
                 INSERT INTO raw_sms_event (
                     id, external_message_id, source_address, destination_address, content
                 ) VALUES (?, ?, ?, ?, ?)
                 """);
             PreparedStatement outbox = connection.prepareStatement("""
                 INSERT INTO outbox_event (id, aggregate_id, event_type)
                 VALUES (?, ?, ?)
                 """)) {
            raw.setObject(1, rawId);
            raw.setString(2, externalMessageId);
            raw.setString(3, sms.sourceAddress());
            raw.setString(4, sms.destinationAddress());
            raw.setString(5, sms.content());
            raw.executeUpdate();

            outbox.setObject(1, UUID.randomUUID());
            outbox.setObject(2, rawId);
            outbox.setString(3, "SMS_RECEIVED");
            outbox.executeUpdate();
        }
        return rawId;
    }

    private Connection connection() throws SQLException {
        return java.sql.DriverManager.getConnection(
            POSTGRES.getJdbcUrl(),
            POSTGRES.getUsername(),
            POSTGRES.getPassword()
        );
    }
}
