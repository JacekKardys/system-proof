package io.github.jacekkardys.systemproof.model;

import java.util.Objects;
import lombok.Getter;
import lombok.experimental.Accessors;

/** A typed, directional required-to-provided relationship without runtime addresses. */
@Getter
@Accessors(fluent = true)
public final class Connection<C> implements ConnectionRef {
    private final RequiredPort<C> from;
    private final ProvidedPort<C> to;
    private final String id;

    private Connection(RequiredPort<C> from, ProvidedPort<C> to) {
        this.from = Objects.requireNonNull(from, "from must not be null");
        this.to = Objects.requireNonNull(to, "to must not be null");
        validateCompatibility(from, to);
        this.id = from.qualifiedName() + "->" + to.qualifiedName();
    }

    public static <C> Connection<C> connect(
        RequiredPort<C> from,
        ProvidedPort<C> to
    ) {
        return new Connection<>(from, to);
    }

    private static void validateCompatibility(PortRef from, PortRef to) {
        reject(!from.contractId().equals(to.contractId()), from, to,
            "contract id mismatch: required=" + from.contractId() + ", provided=" + to.contractId());
        reject(!from.contractType().equals(to.contractType()), from, to,
            "contract type mismatch: required=" + from.contractType().getName()
                + ", provided=" + to.contractType().getName());
        CompatibilityResult interaction = from.interaction().isSatisfiedBy(to.interaction());
        reject(!interaction.compatible(), from, to, interaction.reason());
        CompatibilityResult protocol = from.protocol().isSatisfiedBy(to.protocol());
        reject(!protocol.compatible(), from, to, protocol.reason());
    }

    private static void reject(boolean rejected, PortRef from, PortRef to, String reason) {
        if (rejected) {
            throw new IllegalArgumentException(
                "Cannot connect " + describePort("required", from)
                    + " to " + describePort("provided", to) + ": " + reason
            );
        }
    }

    static String describe(ConnectionRef connection) {
        return describePort("required", connection.from())
            + " to " + describePort("provided", connection.to());
    }

    static String describePort(String role, PortRef port) {
        return role + " port [component='" + port.owner().id()
            + "', localName='" + port.name()
            + "', contractId='" + port.contractId()
            + "', contractType='" + port.contractType().getName()
            + "', interaction='" + port.interaction().id()
            + "', protocol='" + port.protocol().id() + "']";
    }
}
