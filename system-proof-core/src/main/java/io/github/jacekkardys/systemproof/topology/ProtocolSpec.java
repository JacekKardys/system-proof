package io.github.jacekkardys.systemproof.topology;

/** Extensible transport protocol description. */
public interface ProtocolSpec {
    String id();

    String scheme();

    default CompatibilityResult isSatisfiedBy(ProtocolSpec provided) {
        return id().equals(provided.id())
            ? CompatibilityResult.accepted()
            : CompatibilityResult.incompatible("required protocol '" + id()
                + "' is not satisfied by provided protocol '" + provided.id() + "'");
    }
}
