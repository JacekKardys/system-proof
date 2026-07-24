package pl.gov.il.test.harness.diagnostics;

import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/** Ordered runtime-neutral environment event snapshot. */
@Value
@Accessors(fluent = true)
public class EnvironmentDiagnostics {
    String content;

    public EnvironmentDiagnostics(String content) {
        this.content = Objects.requireNonNull(content, "content must not be null");
    }

    public static EnvironmentDiagnostics diagnostics(String content) {
        return new EnvironmentDiagnostics(content);
    }
}
