package io.github.jacekkardys.systemproof.model.topology;

/** Protocol semantics captured from a declarative component port. */
public record DeclaredProtocol(String id, String scheme) implements ProtocolSpec {}
