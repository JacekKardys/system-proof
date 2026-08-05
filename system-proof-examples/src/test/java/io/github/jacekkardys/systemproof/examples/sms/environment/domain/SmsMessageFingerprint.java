package io.github.jacekkardys.systemproof.examples.sms.environment.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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
