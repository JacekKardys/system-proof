package io.github.jacekkardys.systemproof.testcontainers.gateway;

import io.github.jacekkardys.systemproof.observation.EvidenceCodec;

/**
 * Protocol-neutral framing and typed-evidence SPI.
 *
 * <p>An adapter owns no sockets, listeners, route lifecycle, journal, or framework identity. The
 * gateway opens one adapter session per physical socket pair and retains exact bytes until each
 * complete unit has crossed the record-and-decision boundary.
 */
public interface ProtocolAdapter<E> {
    EvidenceCodec<E> evidenceCodec();

    ProtocolSession<E> openSession(ProtocolLimits limits);
}
