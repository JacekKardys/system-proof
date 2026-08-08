package io.github.jacekkardys.systemproof.driver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;
import io.github.jacekkardys.systemproof.journal.RedactedDiagnosticText;

/** A classified lazily captured component-specific diagnostic source. */
public final class DiagnosticSource {
    private static final int MAX_NAME_CHARACTERS = 128;

    private final String sourceId;
    private final SafetyClassification classification;
    private final Supplier<String> content;
    private final RedactedDiagnosticText.Sanitizer sanitizer;

    private DiagnosticSource(
        String name,
        SafetyClassification classification,
        Supplier<String> content,
        RedactedDiagnosticText.Sanitizer sanitizer
    ) {
        sourceId = sourceId(name);
        this.classification = Objects.requireNonNull(
            classification,
            "classification must not be null"
        );
        this.content = Objects.requireNonNull(content, "content must not be null");
        this.sanitizer = sanitizer;
    }

    /**
     * Creates a source whose returned output must pass the supplied bounded sanitizer before
     * export. The framework applies bounds after {@link Supplier#get()} returns; the driver is
     * responsible for bounding acquisition performed by the supplier itself.
     */
    public static DiagnosticSource redacted(
        String name,
        Supplier<String> content,
        RedactedDiagnosticText.Sanitizer sanitizer
    ) {
        return new DiagnosticSource(
            name,
            SafetyClassification.REDACTED_TEXT,
            content,
            Objects.requireNonNull(sanitizer, "sanitizer must not be null")
        );
    }

    /** Classifies raw troubleshooting output that System Proof never invokes or exports. */
    public static DiagnosticSource sensitive(String name, Supplier<String> content) {
        return new DiagnosticSource(
            name,
            SafetyClassification.OPT_IN_SENSITIVE,
            content,
            null
        );
    }

    /** Creates a source that no framework export path may invoke. */
    public static DiagnosticSource unsupported(String name, Supplier<String> content) {
        return new DiagnosticSource(
            name,
            SafetyClassification.UNSUPPORTED_FOR_EXPORT,
            content,
            null
        );
    }

    /** Returns a bounded digest identity; the caller-supplied name is never retained. */
    public String sourceId() {
        return sourceId;
    }

    public SafetyClassification classification() {
        return classification;
    }

    public Supplier<String> content() {
        return content;
    }

    public RedactedDiagnosticText.Sanitizer sanitizer() {
        if (sanitizer == null) {
            throw new IllegalStateException(
                "Only REDACTED_TEXT diagnostic sources have a sanitizer"
            );
        }
        return sanitizer;
    }

    @Override
    public String toString() {
        return "DiagnosticSource[sourceId=" + sourceId
            + ", classification=" + classification + "]";
    }

    private static String sourceId(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.length() > MAX_NAME_CHARACTERS) {
            throw new IllegalArgumentException(
                "Diagnostic source name exceeds " + MAX_NAME_CHARACTERS + " characters"
            );
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("Diagnostic source name must not be blank");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(name.strip().getBytes(StandardCharsets.UTF_8));
            return "source-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }

    /** Trust classification governing whether and how a source may be exported. */
    public enum SafetyClassification {
        REDACTED_TEXT,
        OPT_IN_SENSITIVE,
        UNSUPPORTED_FOR_EXPORT
    }
}
