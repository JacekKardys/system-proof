package io.github.jacekkardys.systemproof.examples.sms.environment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.http.HttpRequestInteraction;
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

    @Test
    void shouldShareIdentityWithTheExactJasminHttpCallback() {
        TestSms message = TestSms.forProof(UUID.randomUUID().toString());
        String body = form(message, "0");
        FakeHttpRequest callback = new FakeHttpRequest(
            "POST",
            "/v1/ingestion/sms",
            Optional.of("application/x-www-form-urlencoded"),
            body.getBytes(StandardCharsets.US_ASCII)
        );

        assertThat(SmsMessageFingerprint.httpCallbackCorrelation().correlate(callback))
            .contains(SmsMessageFingerprint.of(message));
        assertThat(SmsMessageFingerprint.httpCallbackCorrelation().correlate(
            request(form(TestSms.unique(), "0"))
        )).isPresent().get().isNotEqualTo(SmsMessageFingerprint.of(message));
        assertThat(callback.toString())
            .doesNotContain(message.content())
            .doesNotContain(body);
    }

    @Test
    void shouldDecodeTheCharacterizedUcs2CallbackRepresentation() {
        TestSms message = TestSms.forProof(UUID.randomUUID().toString());
        byte[] utf16 = message.content().getBytes(StandardCharsets.UTF_16BE);
        String body = "id=one&from=" + encode(message.sourceAddress())
            + "&to=" + encode(message.destinationAddress())
            + "&origin-connector=smpp-client&content=" + encode(utf16)
            + "&binary=" + java.util.HexFormat.of().formatHex(utf16)
            + "&coding=%08";

        assertThat(SmsMessageFingerprint.httpCallbackCorrelation().correlate(
            request(body)
        )).contains(SmsMessageFingerprint.of(message));
    }

    @Test
    void shouldRejectCallbacksOutsideTheExactRepresentation() {
        TestSms message = TestSms.forProof(UUID.randomUUID().toString());
        String valid = form(message, "0");

        assertThat(correlate(new FakeHttpRequest(
            "GET",
            "/v1/ingestion/sms",
            Optional.of("application/x-www-form-urlencoded"),
            valid.getBytes(StandardCharsets.US_ASCII)
        ))).isEmpty();
        assertThat(correlate(new FakeHttpRequest(
            "POST",
            "/other",
            Optional.of("application/x-www-form-urlencoded"),
            valid.getBytes(StandardCharsets.US_ASCII)
        ))).isEmpty();
        assertThat(correlate(new FakeHttpRequest(
            "POST",
            "/v1/ingestion/sms",
            Optional.of("application/json"),
            valid.getBytes(StandardCharsets.US_ASCII)
        ))).isEmpty();
        assertThat(correlate(request(valid.replace("&to=", "&from=duplicate&to="))))
            .isEmpty();
        assertThat(correlate(request(valid.replace("&binary=", "&unknown=x&binary="))))
            .isEmpty();
        assertThat(correlate(request(valid.replace("id=one&", "")))).isEmpty();
        assertThat(correlate(request(valid.replace("&binary=", "&binary=00"))))
            .isEmpty();
        assertThat(correlate(request(valid.replace("id=one", "id=%GG")))).isEmpty();
    }

    private static Optional<?> correlate(HttpRequestInteraction request) {
        return SmsMessageFingerprint.httpCallbackCorrelation().correlate(request);
    }

    private static FakeHttpRequest request(String body) {
        return new FakeHttpRequest(
            "POST",
            "/v1/ingestion/sms",
            Optional.of("application/x-www-form-urlencoded"),
            body.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static String form(TestSms message, String coding) {
        byte[] content = message.content().getBytes(StandardCharsets.UTF_8);
        return "id=one&from=" + encode(message.sourceAddress())
            + "&to=" + encode(message.destinationAddress())
            + "&origin-connector=smpp-client&content=" + encode(message.content())
            + "&binary=" + java.util.HexFormat.of().formatHex(content)
            + "&coding=" + coding;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String encode(byte[] value) {
        StringBuilder encoded = new StringBuilder(value.length * 3);
        for (byte current : value) {
            encoded.append('%').append(String.format(
                java.util.Locale.ROOT,
                "%02X",
                Byte.toUnsignedInt(current)
            ));
        }
        return encoded.toString();
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

    private record FakeHttpRequest(
        String method,
        String path,
        Optional<String> contentType,
        byte[] bodyBytes
    ) implements HttpRequestInteraction {
        private FakeHttpRequest {
            bodyBytes = bodyBytes.clone();
        }

        @Override
        public Body body() {
            return new Body() {
                @Override
                public int size() {
                    return bodyBytes.length;
                }

                @Override
                public byte byteAt(int index) {
                    return bodyBytes[index];
                }

                @Override
                public void copyTo(
                    int sourceOffset,
                    byte[] destination,
                    int destinationOffset,
                    int length
                ) {
                    System.arraycopy(
                        bodyBytes,
                        sourceOffset,
                        destination,
                        destinationOffset,
                        length
                    );
                }
            };
        }

        @Override
        public String toString() {
            return "FakeHttpRequest[method=" + method
                + ", pathLength=" + path.length()
                + ", bodyByteCount=" + bodyBytes.length + "]";
        }
    }
}
