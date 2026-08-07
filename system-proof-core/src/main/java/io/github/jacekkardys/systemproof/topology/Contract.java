package io.github.jacekkardys.systemproof.topology;

import java.util.Objects;

/** Semantic contract with a bounded ASCII identity and runtime type shared by compatible ports. */
public record Contract<C>(String id, Class<C> contractType) {
    public Contract {
        Objects.requireNonNull(id, "contract id must not be null");
        if (id.length() > 128 || !id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException(
                "contract id must be 1-128 ASCII identifier characters"
            );
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
