package io.github.jacekkardys.systemproof.construction;

import io.github.jacekkardys.systemproof.model.topology.InteractionSpec;

/** Validated interaction semantics materialized from a port declaration. */
record DeclaredInteraction(String id) implements InteractionSpec {
    DeclaredInteraction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("interaction id must not be blank");
        }
    }
}
