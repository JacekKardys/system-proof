package io.github.jacekkardys.systemproof.testcontainers.component;

import io.github.jacekkardys.systemproof.model.EndpointAddress;
/** Maps one Testcontainers address to the value declared by an endpoint contract. */
@FunctionalInterface
public interface RuntimeEndpointFactory<T> {
    T create(EndpointAddress address);
}
