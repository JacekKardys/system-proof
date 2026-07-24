package pl.gov.il.test.harness.model;

import java.util.Objects;

/** Value wrapper whose diagnostic representation never exposes the wrapped secret. */
public final class Secret<T> {
    private final T value;

    private Secret(T value) {
        this.value = Objects.requireNonNull(value, "secret value must not be null");
    }

    public static <T> Secret<T> secret(T value) {
        return new Secret<>(value);
    }

    public T reveal() {
        return value;
    }

    @Override
    public String toString() {
        return "<redacted>";
    }
}
