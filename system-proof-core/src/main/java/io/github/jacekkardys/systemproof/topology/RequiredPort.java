package io.github.jacekkardys.systemproof.topology;

import io.github.jacekkardys.systemproof.component.AbstractComponent;

/** Consumer communication endpoint that must be connected exactly once. */
public final class RequiredPort<C> extends Port<C> {
    private final boolean requiredAtStartup;

    public RequiredPort(
        AbstractComponent<?, ?> owner,
        String name,
        Contract<C> contract,
        InteractionSpec interaction,
        ProtocolSpec protocol,
        boolean requiredAtStartup
    ) {
        super(owner, name, contract, interaction, protocol);
        this.requiredAtStartup = requiredAtStartup;
    }

    @Override
    public PortDirection direction() {
        return PortDirection.REQUIRED;
    }

    public boolean requiredAtStartup() {
        return requiredAtStartup;
    }
}
