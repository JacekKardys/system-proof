package io.github.jacekkardys.systemproof.model.topology;

import java.util.Objects;

public record CompatibilityResult(boolean compatible, String reason) {
    public CompatibilityResult {
        reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public static CompatibilityResult accepted() {
        return new CompatibilityResult(true, "compatible");
    }

    public static CompatibilityResult incompatible(String reason) {
        return new CompatibilityResult(false, reason);
    }
}
