package io.github.jacekkardys.systemproof.model.component;

import java.util.List;
import io.github.jacekkardys.systemproof.model.topology.PortRef;

/** Immutable public declaration of one logical component instance. */
public interface Component {
    ComponentId id();

    ComponentType type();

    RuntimeConfig configuration();

    List<PortRef> ports();
}
