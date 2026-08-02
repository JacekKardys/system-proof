package io.github.jacekkardys.systemproof.model.topology;

import java.util.Objects;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.component.Component;

/** Logical port without host, mapped port, URI, or Testcontainers state. */
public abstract class Port<C> implements PortRef {
    private final AbstractComponent<?, ?> owner;
    private final String name;
    private final Contract<C> contract;
    private final InteractionSpec interaction;
    private final ProtocolSpec protocol;

    Port(
        AbstractComponent<?, ?> owner,
        String name,
        Contract<C> contract,
        InteractionSpec interaction,
        ProtocolSpec protocol
    ) {
        this.owner = Objects.requireNonNull(owner, "owner must not be null");
        this.name = requireText(name, "port name");
        this.contract = Objects.requireNonNull(contract, "contract must not be null");
        this.interaction = Objects.requireNonNull(interaction, "interaction must not be null");
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null");
    }

    @Override
    public final String name() {
        return name;
    }

    @Override
    public final Component owner() {
        return owner;
    }

    public final Contract<C> contract() {
        return contract;
    }

    @Override
    public final String contractId() {
        return contract.id();
    }

    @Override
    public final Class<C> contractType() {
        return contract.contractType();
    }

    @Override
    public final InteractionSpec interaction() {
        return interaction;
    }

    @Override
    public final ProtocolSpec protocol() {
        return protocol;
    }

    @Override
    public final String qualifiedName() {
        return owner.id() + "." + name;
    }

    private static String requireText(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }
}
