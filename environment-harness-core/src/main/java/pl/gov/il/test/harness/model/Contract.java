package pl.gov.il.test.harness.model;

import java.util.Objects;

/** Semantic contract and runtime value type shared by compatible ports. */
public record Contract<C>(String id, Class<C> contractType) {
    public Contract {
        Objects.requireNonNull(id, "contract id must not be null");
        if (id.isBlank()) {
            throw new IllegalArgumentException("contract id must not be blank");
        }
        Objects.requireNonNull(contractType, "contract type must not be null");
    }

    public static <C> Contract<C> contract(String id, Class<C> contractType) {
        return new Contract<>(id, contractType);
    }

    public C cast(Object value) {
        return contractType.cast(value);
    }
}
