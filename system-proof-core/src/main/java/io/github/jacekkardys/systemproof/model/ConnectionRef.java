package io.github.jacekkardys.systemproof.model;

/** Non-generic environment registry view of one directional logical connection. */
public sealed interface ConnectionRef permits Connection {
    ConnectionId id();

    PortRef from();

    PortRef to();
}
