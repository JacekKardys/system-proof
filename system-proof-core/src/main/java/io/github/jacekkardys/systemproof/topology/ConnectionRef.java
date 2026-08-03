package io.github.jacekkardys.systemproof.topology;

/** Non-generic environment registry view of one directional logical connection. */
public sealed interface ConnectionRef permits Connection {
    ConnectionId id();

    PortRef from();

    PortRef to();
}
