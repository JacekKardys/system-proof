package pl.gov.il.test.harness.model;

import java.util.Objects;

/** Internal and test-host runtime values for one provided endpoint contract. */
public record EndpointBinding<T>(T internal, T external) {
    public EndpointBinding {
        Objects.requireNonNull(internal, "internal must not be null");
        Objects.requireNonNull(external, "external must not be null");
    }

    public static <T> EndpointBinding<T> binding(T internal, T external) {
        return new EndpointBinding<>(internal, external);
    }
}
