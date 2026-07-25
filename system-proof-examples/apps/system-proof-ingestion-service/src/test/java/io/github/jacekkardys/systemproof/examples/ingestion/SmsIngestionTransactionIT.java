package io.github.jacekkardys.systemproof.examples.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class SmsIngestionTransactionIT {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    SmsIngestionService service;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void commitsRawAndOutboxRowsTogether() {
        var rawId = service.ingest(new SmsIngestionCommand("message-1", "48111", "48222", "hello"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM raw_sms_event WHERE id = ?", Integer.class, rawId))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE aggregate_id = ?",
            Integer.class,
            rawId
        )).isEqualTo(1);
    }

    @Test
    void rollsBackRawRowWhenOutboxInsertFails() {
        jdbc.execute("""
            CREATE OR REPLACE FUNCTION reject_outbox_insert() RETURNS trigger AS $$
            BEGIN
                RAISE EXCEPTION 'outbox rejected by test';
            END;
            $$ LANGUAGE plpgsql
            """);
        jdbc.execute("""
            CREATE TRIGGER reject_outbox
            BEFORE INSERT ON outbox_event
            FOR EACH ROW EXECUTE FUNCTION reject_outbox_insert()
            """);

        try {
            assertThatThrownBy(() ->
                service.ingest(new SmsIngestionCommand("message-rollback", "48111", "48222", "hello"))
            ).isInstanceOf(RuntimeException.class);
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM raw_sms_event WHERE external_message_id = 'message-rollback'",
                Integer.class
            )).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER reject_outbox ON outbox_event");
            jdbc.execute("DROP FUNCTION reject_outbox_insert()");
        }
    }
}
