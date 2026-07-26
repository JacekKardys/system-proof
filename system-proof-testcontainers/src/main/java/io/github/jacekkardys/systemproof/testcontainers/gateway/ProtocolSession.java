package io.github.jacekkardys.systemproof.testcontainers.gateway;

import io.github.jacekkardys.systemproof.journal.FlowDirection;

/**
 * Protocol state for one physical socket pair.
 *
 * <p>It must create independent stream state for each topological direction and does not receive
 * connection, session, ordinal, or interaction identities.
 */
public interface ProtocolSession<E> {
    ProtocolStream<E> openStream(FlowDirection direction);
}
