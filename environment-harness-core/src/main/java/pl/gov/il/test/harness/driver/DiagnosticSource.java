package pl.gov.il.test.harness.driver;

import java.util.Objects;
import java.util.function.Supplier;

/** A lazily captured component-specific diagnostic source. */
public record DiagnosticSource(String name, Supplier<String> content) {
    public DiagnosticSource {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(content, "content must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Diagnostic source name must not be blank");
        }
    }
}
