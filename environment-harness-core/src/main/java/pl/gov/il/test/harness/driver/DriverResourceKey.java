package pl.gov.il.test.harness.driver;

import java.util.Objects;

/** Identity key for an environment-scoped resource shared by compatible drivers. */
public final class DriverResourceKey<R extends AutoCloseable> {
    private final String name;
    private final Class<R> type;

    private DriverResourceKey(String name, Class<R> type) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
    }

    public static <R extends AutoCloseable> DriverResourceKey<R> resourceKey(String name, Class<R> type) {
        return new DriverResourceKey<>(name, type);
    }

    public String name() {
        return name;
    }

    public R cast(Object resource) {
        return type.cast(resource);
    }
}
