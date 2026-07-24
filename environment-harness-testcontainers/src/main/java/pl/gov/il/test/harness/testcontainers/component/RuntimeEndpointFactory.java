package pl.gov.il.test.harness.testcontainers.component;

import pl.gov.il.test.harness.model.EndpointAddress;
/** Maps one Testcontainers address to the value declared by an endpoint contract. */
@FunctionalInterface
public interface RuntimeEndpointFactory<T> {
    T create(EndpointAddress address);
}
