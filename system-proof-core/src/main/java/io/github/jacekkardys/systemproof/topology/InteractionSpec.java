package io.github.jacekkardys.systemproof.topology;

/** Extensible interaction semantics such as invocation, session, or resource access. */
public interface InteractionSpec {
    String id();

    default CompatibilityResult isSatisfiedBy(InteractionSpec provided) {
        return id().equals(provided.id())
            ? CompatibilityResult.accepted()
            : CompatibilityResult.incompatible("required interaction '" + id()
                + "' is not satisfied by provided interaction '" + provided.id() + "'");
    }
}
