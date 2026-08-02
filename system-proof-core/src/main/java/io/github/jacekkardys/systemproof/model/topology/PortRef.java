package io.github.jacekkardys.systemproof.model.topology;

import io.github.jacekkardys.systemproof.model.component.Component;

/** Non-generic registry view; typed access remains on concrete components and connect(...). */
public interface PortRef {
    String name();

    Component owner();

    PortDirection direction();

    String contractId();

    Class<?> contractType();

    InteractionSpec interaction();

    ProtocolSpec protocol();

    String qualifiedName();
}
