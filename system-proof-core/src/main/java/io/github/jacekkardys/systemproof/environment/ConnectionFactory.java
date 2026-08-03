package io.github.jacekkardys.systemproof.environment;

import java.util.Objects;
import io.github.jacekkardys.systemproof.topology.CompatibilityResult;
import io.github.jacekkardys.systemproof.topology.Connection;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.PortRef;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

/** Creates validated logical connection models during environment construction. */
final class ConnectionFactory {
    private ConnectionFactory() {}

    static <C> Connection<C> create(RequiredPort<C> from, ProvidedPort<C> to) {
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        validateCompatibility(from, to);
        return new Connection<>(from, to, ConnectionId.between(from, to));
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
            throw new IllegalArgumentException("Cannot connect " + TopologyValidator.describePort("required", from)
                + " to " + TopologyValidator.describePort("provided", to) + ": " + reason);
        }
    }
}
