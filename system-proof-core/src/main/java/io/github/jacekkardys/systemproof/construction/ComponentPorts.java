package io.github.jacekkardys.systemproof.construction;

import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.topology.Contract;
import io.github.jacekkardys.systemproof.model.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.model.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;

/** Low-level construction helpers for components that declare ports programmatically. */
public final class ComponentPorts {
    private ComponentPorts() {}

    /** Declares a required port without a startup dependency. */
    public static <C> RequiredPort<C> requires(AbstractComponent<?, ?> owner, String name, Contract<C> contract,
        InteractionSpec interaction, ProtocolSpec protocol) {
        return ComponentInitializer.register(owner,
            new RequiredPort<>(owner, name, contract, interaction, protocol, false));
    }

    /** Declares a required port whose provider must be ready before its owner starts. */
    public static <C> RequiredPort<C> requiresAtStartup(AbstractComponent<?, ?> owner, String name,
        Contract<C> contract, InteractionSpec interaction, ProtocolSpec protocol) {
        return ComponentInitializer.register(owner,
            new RequiredPort<>(owner, name, contract, interaction, protocol, true));
    }

    /** Declares a provided port. */
    public static <C> ProvidedPort<C> provides(AbstractComponent<?, ?> owner, String name, Contract<C> contract,
        InteractionSpec interaction, ProtocolSpec protocol) {
        return ComponentInitializer.register(owner, new ProvidedPort<>(owner, name, contract, interaction, protocol));
    }
}
