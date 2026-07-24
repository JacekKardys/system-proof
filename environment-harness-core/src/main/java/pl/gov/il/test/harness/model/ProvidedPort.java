package pl.gov.il.test.harness.model;

/** Provider boundary materialized by a driver at runtime. */
public final class ProvidedPort<C> extends Port<C> {
    ProvidedPort(
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
