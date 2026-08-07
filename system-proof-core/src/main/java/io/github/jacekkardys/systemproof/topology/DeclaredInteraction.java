package io.github.jacekkardys.systemproof.topology;

import java.util.Objects;

/** Interaction semantics with a bounded ASCII identity captured from a component port. */
public record DeclaredInteraction(String id) implements InteractionSpec {
    public DeclaredInteraction {
        Objects.requireNonNull(id, "interaction id must not be null");
        if (id.length() > 128 || !id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException(
                "interaction id must be 1-128 ASCII identifier characters"
            );
        }
    }
}
