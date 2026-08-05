package io.github.jacekkardys.systemproof.examples.sms.environment.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import io.github.jacekkardys.systemproof.http.HttpRequestCorrelation;
import io.github.jacekkardys.systemproof.http.HttpRequestInteraction;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlStatementShape;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlWriteCorrelation;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlWriteInteraction;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlWriteInteraction.ParameterFormat;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;

/** Reference-domain message identity shared by workload creation and RAW-write observation. */
public final class SmsMessageFingerprint {
    private static final CorrelationKeySchema SCHEMA = new CorrelationKeySchema(
        "system-proof.examples.sms",
        "message-fingerprint",
        1
    );
    private static final List<String> RAW_COLUMNS = List.of(
        "id",
        "external_message_id",
        "source_address",
        "destination_address",
        "content"
    );
    private static final String CALLBACK_PATH = "/v1/ingestion/sms";
    private static final String FORM_CONTENT_TYPE =
        "application/x-www-form-urlencoded";
    private static final Set<String> CALLBACK_FIELDS = Set.of(
        "id",
        "from",
        "to",
        "origin-connector",
        "content",
        "binary",
        "priority",
        "coding",
        "validity"
    );
    private static final Set<String> REQUIRED_CALLBACK_FIELDS = Set.of(
        "id",
        "from",
        "to",
        "origin-connector",
        "content",
        "binary"
    );

    private SmsMessageFingerprint() {}

    public static CorrelationKey of(TestSms message) {
        Objects.requireNonNull(message, "message must not be null");
        return key(
            normalizeAddress(message.sourceAddress()),
            normalizeAddress(message.destinationAddress()),
            message.content()
        );
    }

    /** Recognizes only the reference application's exact RAW INSERT and semantic field mapping. */
    public static PostgresqlWriteCorrelation rawWriteCorrelation() {
        return SmsMessageFingerprint::fromRawWrite;
    }

    /** Recognizes only the characterized Jasmin callback representation. */
    public static HttpRequestCorrelation httpCallbackCorrelation() {
        return SmsMessageFingerprint::fromHttpCallback;
    }

    private static Optional<CorrelationKey> fromHttpCallback(
        HttpRequestInteraction interaction
    ) {
        if (!interaction.method().equals("POST")
            || !interaction.path().equals(CALLBACK_PATH)
            || interaction.contentType().filter(
                FORM_CONTENT_TYPE::equalsIgnoreCase
            ).isEmpty()) {
            return Optional.empty();
        }
        Optional<Map<String, byte[]>> decoded = decodeForm(interaction.body());
        if (decoded.isEmpty()) {
            return Optional.empty();
        }
        Map<String, byte[]> fields = decoded.orElseThrow();
        if (!fields.keySet().containsAll(REQUIRED_CALLBACK_FIELDS)
            || utf8(fields.get("id")).filter(value -> !value.isBlank()).isEmpty()
            || utf8(fields.get("origin-connector"))
                .filter(value -> !value.isBlank()).isEmpty()) {
            return Optional.empty();
        }
        Optional<String> source = utf8(fields.get("from"))
            .filter(value -> !value.isBlank());
        Optional<String> destination = utf8(fields.get("to"))
            .filter(value -> !value.isBlank());
        Optional<String> content = callbackContent(fields);
        if (source.isEmpty() || destination.isEmpty() || content.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(key(
            normalizeAddress(source.orElseThrow()),
            normalizeAddress(destination.orElseThrow()),
            content.orElseThrow()
        ));
    }

    private static Optional<String> callbackContent(Map<String, byte[]> fields) {
        byte[] coding = fields.get("coding");
        if (coding != null && isCoding(coding, 8)) {
            Optional<byte[]> binary = hex(fields.get("binary"));
            if (binary.isEmpty() || !MessageDigest.isEqual(
                fields.get("content"),
                binary.orElseThrow()
            )) {
                return Optional.empty();
            }
            return binary.flatMap(bytes -> decode(bytes, StandardCharsets.UTF_16BE));
        }
        if (coding != null && !isCoding(coding, 0)) {
            return Optional.empty();
        }
        Optional<String> content = utf8(fields.get("content"));
        Optional<byte[]> binary = hex(fields.get("binary"));
        if (content.isEmpty() || binary.isEmpty()
            || !MessageDigest.isEqual(
                content.orElseThrow().getBytes(StandardCharsets.UTF_8),
                binary.orElseThrow()
            )) {
            return Optional.empty();
        }
        return content;
    }

    private static boolean isCoding(byte[] actual, int expected) {
        return actual.length == 1
            && (Byte.toUnsignedInt(actual[0]) == expected
                || actual[0] == Character.forDigit(expected, 10));
    }

    private static Optional<Map<String, byte[]>> decodeForm(
        HttpRequestInteraction.Body body
    ) {
        byte[] bytes = new byte[body.size()];
        body.copyTo(0, bytes, 0, bytes.length);
        Map<String, byte[]> fields = new LinkedHashMap<>();
        int offset = 0;
        while (offset <= bytes.length) {
            int end = indexOf(bytes, (byte) '&', offset);
            if (end < 0) {
                end = bytes.length;
            }
            int equals = indexOf(bytes, (byte) '=', offset, end);
            if (equals <= offset) {
                return Optional.empty();
            }
            Optional<byte[]> nameBytes = percentDecode(bytes, offset, equals);
            Optional<byte[]> value = percentDecode(bytes, equals + 1, end);
            Optional<String> name = nameBytes.flatMap(SmsMessageFingerprint::ascii);
            if (name.isEmpty() || value.isEmpty()
                || !CALLBACK_FIELDS.contains(name.orElseThrow())
                || fields.putIfAbsent(name.orElseThrow(), value.orElseThrow()) != null) {
                return Optional.empty();
            }
            if (end == bytes.length) {
                break;
            }
            offset = end + 1;
        }
        return Optional.of(Map.copyOf(fields));
    }

    private static Optional<byte[]> percentDecode(
        byte[] encoded,
        int start,
        int end
    ) {
        ByteBuffer decoded = ByteBuffer.allocate(end - start);
        for (int index = start; index < end; index++) {
            int current = Byte.toUnsignedInt(encoded[index]);
            if (current == '+') {
                decoded.put((byte) ' ');
            } else if (current == '%') {
                if (index + 2 >= end) {
                    return Optional.empty();
                }
                int high = Character.digit((char) encoded[++index], 16);
                int low = Character.digit((char) encoded[++index], 16);
                if (high < 0 || low < 0) {
                    return Optional.empty();
                }
                decoded.put((byte) ((high << 4) | low));
            } else if (current > 0x7f) {
                return Optional.empty();
            } else {
                decoded.put((byte) current);
            }
        }
        decoded.flip();
        byte[] result = new byte[decoded.remaining()];
        decoded.get(result);
        return Optional.of(result);
    }

    private static Optional<byte[]> hex(byte[] value) {
        if (value == null || (value.length & 1) != 0) {
            return Optional.empty();
        }
        byte[] decoded = new byte[value.length / 2];
        for (int index = 0; index < value.length; index += 2) {
            int high = Character.digit((char) value[index], 16);
            int low = Character.digit((char) value[index + 1], 16);
            if (high < 0 || low < 0) {
                return Optional.empty();
            }
            decoded[index / 2] = (byte) ((high << 4) | low);
        }
        return Optional.of(decoded);
    }

    private static Optional<String> utf8(byte[] value) {
        return value == null
            ? Optional.empty()
            : decode(value, StandardCharsets.UTF_8);
    }

    private static Optional<String> ascii(byte[] value) {
        return decode(value, StandardCharsets.US_ASCII);
    }

    private static Optional<String> decode(
        byte[] value,
        java.nio.charset.Charset charset
    ) {
        try {
            return Optional.of(charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(value))
                .toString());
        } catch (CharacterCodingException failure) {
            return Optional.empty();
        }
    }

    private static int indexOf(byte[] bytes, byte sought, int start) {
        return indexOf(bytes, sought, start, bytes.length);
    }

    private static int indexOf(byte[] bytes, byte sought, int start, int end) {
        for (int index = start; index < end; index++) {
            if (bytes[index] == sought) {
                return index;
            }
        }
        return -1;
    }

    private static Optional<CorrelationKey> fromRawWrite(
        PostgresqlWriteInteraction interaction
    ) {
        PostgresqlStatementShape shape = interaction.shape();
        if (shape.kind() != PostgresqlStatementShape.Kind.INSERT
            || shape.schema().isPresent()
            || !shape.table().equals("raw_sms_event")
            || !shape.columns().equals(RAW_COLUMNS)
            || interaction.parameterCount() != RAW_COLUMNS.size()) {
            return Optional.empty();
        }
        Optional<String> source = textParameter(interaction, 2);
        Optional<String> destination = textParameter(interaction, 3);
        Optional<String> content = textParameter(interaction, 4);
        if (source.isEmpty() || destination.isEmpty() || content.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(key(
            normalizeAddress(source.orElseThrow()),
            normalizeAddress(destination.orElseThrow()),
            content.orElseThrow()
        ));
    }

    private static Optional<String> textParameter(
        PostgresqlWriteInteraction interaction,
        int index
    ) {
        if (interaction.parameterIsNull(index)
            || interaction.parameterFormat(index) != ParameterFormat.TEXT) {
            return Optional.empty();
        }
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(interaction.parameterBytes(index))
                .toString());
        } catch (CharacterCodingException failure) {
            return Optional.empty();
        }
    }

    private static CorrelationKey key(
        String sourceAddress,
        String destinationAddress,
        String content
    ) {
        MessageDigest digest = sha256();
        addLengthDelimited(digest, sourceAddress.getBytes(StandardCharsets.UTF_8));
        addLengthDelimited(digest, destinationAddress.getBytes(StandardCharsets.UTF_8));
        addLengthDelimited(
            digest,
            sha256().digest(content.getBytes(StandardCharsets.UTF_8))
        );
        return CorrelationKey.ofDigest(SCHEMA, digest.digest());
    }

    private static String normalizeAddress(String address) {
        return address.strip().toLowerCase(Locale.ROOT);
    }

    private static void addLengthDelimited(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }
}
