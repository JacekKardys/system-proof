package io.github.jacekkardys.systemproof.topology;

import io.github.jacekkardys.systemproof.component.AbstractComponent;

/** Provider boundary materialized by a driver at runtime. */
public final class ProvidedPort<C> extends Port<C> {
    public ProvidedPort(
        AbstractComponent<?, ?> owner,
        String name,
        Contract<C> contract,
        InteractionSpec interaction,
        ProtocolSpec protocol
    ) {
        super(owner, name, contract, interaction, protocol);
    }

    @Override
    public PortDirection direction() {
        return PortDirection.PROVIDED;
    }
}
