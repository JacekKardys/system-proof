package io.github.jacekkardys.systemproof.diagnostics;

import java.util.Objects;
import lombok.Value;
import lombok.experimental.Accessors;

/** Rendered runtime state, one journal snapshot, and capture-on-demand diagnostic sources. */
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
