package io.github.jacekkardys.systemproof.examples.sms.environment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlStatementShape;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlWriteInteraction;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlWriteInteraction.ParameterFormat;

class SmsMessageFingerprintTest {
    private static final List<String> RAW_COLUMNS = List.of(
        "id",
        "external_message_id",
        "source_address",
        "destination_address",
        "content"
    );

    @Test
    void shouldMatchOnlyTheSemanticFieldsOfTheExactReferenceRawWrite() {
        String discriminator = UUID.randomUUID().toString();
        TestSms message = TestSms.forProof(discriminator);
        FakeWrite first = rawWrite(
            "jasmin-generated-one",
            message.sourceAddress(),
            message.destinationAddress(),
            message.content()
        );
        FakeWrite second = rawWrite(
            "jasmin-generated-two",
            "  " + message.sourceAddress() + "  ",
            message.destinationAddress(),
            message.content()
        );

        assertThat(SmsMessageFingerprint.rawWriteCorrelation().correlate(first))
            .contains(SmsMessageFingerprint.of(message));
        assertThat(SmsMessageFingerprint.rawWriteCorrelation().correlate(second))
            .contains(SmsMessageFingerprint.of(message));
        assertThat(SmsMessageFingerprint.of(message).toString())
            .doesNotContain(discriminator)
            .doesNotContain(message.content());
    }

    @Test
    void shouldRejectAParallelTableOrColumnModel() {
        FakeWrite wrongTable = new FakeWrite(
            new PostgresqlStatementShape(
                PostgresqlStatementShape.Kind.INSERT,
                Optional.empty(),
                "outbox_event",
                RAW_COLUMNS
            ),
            values("external", "source", "destination", "content")
        );
        FakeWrite wrongColumns = new FakeWrite(
            new PostgresqlStatementShape(
                PostgresqlStatementShape.Kind.INSERT,
                Optional.empty(),
                "raw_sms_event",
                List.of(
                    "id",
                    "source_address",
                    "external_message_id",
                    "destination_address",
                    "content"
                )
            ),
            values("external", "source", "destination", "content")
        );

        assertThat(SmsMessageFingerprint.rawWriteCorrelation().correlate(wrongTable))
            .isEmpty();
        assertThat(SmsMessageFingerprint.rawWriteCorrelation().correlate(wrongColumns))
            .isEmpty();
    }

    private static FakeWrite rawWrite(
        String externalMessageId,
        String source,
        String destination,
        String content
    ) {
        return new FakeWrite(
            new PostgresqlStatementShape(
                PostgresqlStatementShape.Kind.INSERT,
                Optional.empty(),
                "raw_sms_event",
                RAW_COLUMNS
            ),
            values(externalMessageId, source, destination, content)
        );
    }

    private static List<byte[]> values(
        String externalMessageId,
        String source,
        String destination,
        String content
    ) {
        return List.of(
            UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
            externalMessageId.getBytes(StandardCharsets.UTF_8),
            source.getBytes(StandardCharsets.UTF_8),
            destination.getBytes(StandardCharsets.UTF_8),
            content.getBytes(StandardCharsets.UTF_8)
        );
    }

    private record FakeWrite(
        PostgresqlStatementShape shape,
        List<byte[]> parameters
    ) implements PostgresqlWriteInteraction {
        private FakeWrite {
            parameters = parameters.stream().map(value -> value.clone()).toList();
        }

        @Override
        public int parameterCount() {
            return parameters.size();
        }

        @Override
        public boolean parameterIsNull(int zeroBasedIndex) {
            parameter(zeroBasedIndex);
            return false;
        }

        @Override
        public int parameterSize(int zeroBasedIndex) {
            return parameter(zeroBasedIndex).length;
        }

        @Override
        public ParameterFormat parameterFormat(int zeroBasedIndex) {
            parameter(zeroBasedIndex);
            return ParameterFormat.TEXT;
        }

        @Override
        public OptionalLong parameterTypeOid(int zeroBasedIndex) {
            parameter(zeroBasedIndex);
            return OptionalLong.empty();
        }

        @Override
        public ByteBuffer parameterBytes(int zeroBasedIndex) {
            return ByteBuffer.wrap(parameter(zeroBasedIndex)).asReadOnlyBuffer();
        }

        private byte[] parameter(int index) {
            return parameters.get(index);
        }
    }
}
