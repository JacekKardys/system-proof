package io.github.jacekkardys.systemproof.construction;

import io.github.jacekkardys.systemproof.model.topology.ProtocolSpec;

/** Validated protocol semantics materialized from a port declaration. */
record DeclaredProtocol(String id, String scheme) implements ProtocolSpec {
    DeclaredProtocol {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("protocol id must not be blank");
        }
        if (scheme == null || scheme.isBlank()) {
            throw new IllegalArgumentException("protocol scheme must not be blank");
        }
    }
}
