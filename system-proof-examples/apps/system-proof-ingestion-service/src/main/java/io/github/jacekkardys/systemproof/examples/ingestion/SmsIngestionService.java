package io.github.jacekkardys.systemproof.examples.ingestion;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SmsIngestionService {
    private final JdbcTemplate jdbc;

    public SmsIngestionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public UUID ingest(SmsIngestionCommand command) {
        UUID rawEventId = UUID.randomUUID();
        jdbc.update(
            """
            INSERT INTO raw_sms_event (
                id, external_message_id, source_address, destination_address, content
            ) VALUES (?, ?, ?, ?, ?)
            """,
            rawEventId,
            command.externalMessageId(),
            command.sourceAddress(),
            command.destinationAddress(),
            command.content()
        );
        jdbc.update(
            "INSERT INTO outbox_event (id, aggregate_id, event_type) VALUES (?, ?, ?)",
            UUID.randomUUID(),
            rawEventId,
            "SMS_RECEIVED"
        );
        return rawEventId;
    }
}
