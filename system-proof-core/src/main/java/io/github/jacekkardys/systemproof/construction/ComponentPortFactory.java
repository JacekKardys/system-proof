package io.github.jacekkardys.systemproof.construction;

import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.topology.Contract;
import io.github.jacekkardys.systemproof.model.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.model.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;

/**
 * Creates model ports and registers them in a component's mutable construction state.
 * The factory is not retained by the component, topology, or runtime.
 */
public final class ComponentPortFactory {
    private ComponentPortFactory() {}

    /** Creates and registers a required port without a startup dependency. */
    public static <C> RequiredPort<C> requires(AbstractComponent<?, ?> owner, String name, Contract<C> contract,
        InteractionSpec interaction, ProtocolSpec protocol) {
        return ComponentInitializer.register(owner,
            new RequiredPort<>(owner, name, contract, interaction, protocol, false));
    }

    /** Creates and registers a required port whose provider must be ready before its owner starts. */
    public static <C> RequiredPort<C> requiresAtStartup(AbstractComponent<?, ?> owner, String name,
        Contract<C> contract, InteractionSpec interaction, ProtocolSpec protocol) {
        return ComponentInitializer.register(owner,
            new RequiredPort<>(owner, name, contract, interaction, protocol, true));
    }

    /** Creates and registers a provided port. */
    public static <C> ProvidedPort<C> provides(AbstractComponent<?, ?> owner, String name, Contract<C> contract,
        InteractionSpec interaction, ProtocolSpec protocol) {
        return ComponentInitializer.register(owner, new ProvidedPort<>(owner, name, contract, interaction, protocol));
    }
}
