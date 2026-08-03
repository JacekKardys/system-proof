package io.github.jacekkardys.systemproof.component;

import java.util.List;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.topology.PortRef;

/** Immutable public declaration of one logical component instance. */
public interface Component {
    ComponentId id();

    ComponentType type();

    RuntimeConfig configuration();

    List<PortRef> ports();
}
