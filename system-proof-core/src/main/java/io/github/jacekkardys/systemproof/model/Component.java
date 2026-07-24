package io.github.jacekkardys.systemproof.model;

import java.util.List;

/** Immutable public declaration of one logical component instance. */
public interface Component {
    ComponentId id();

    ComponentType type();

    RuntimeConfig configuration();

    List<PortRef> ports();
}
