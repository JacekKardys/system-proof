package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.util.Objects;
import java.util.Optional;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.topology.ConnectionId;

/**
 * Protocol-neutral framing and typed-evidence SPI.
 *
 * <p>An adapter owns no sockets, listeners, route lifecycle, journal, or framework identity
 * allocation. The gateway supplies the exact logical connection, opens one adapter session per
 * physical socket pair, and retains exact bytes until each complete unit has crossed the
 * record-and-decision boundary.
 */
public interface ProtocolAdapter<E> {
    /** Returns the adapter compatibility declaration, when this adapter can be routed. */
    default Optional<ProtocolObservationContract> observationContract() {
        return Optional.empty();
    }

    EvidenceCodec<E> evidenceCodec();

    /** Opens one physical protocol session bound to its exact logical route. */
    default ProtocolSession<E> openSession(
        ConnectionId connectionId,
        ProtocolLimits limits
    ) {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        return openSession(limits);
    }

    ProtocolSession<E> openSession(ProtocolLimits limits);
}
