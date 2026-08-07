package io.github.jacekkardys.systemproof.driver;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Identity key for an environment-scoped resource shared by compatible drivers.
 *
 * <p>Names are public diagnostic metadata: 1-128 ASCII identifier characters, beginning with an
 * alphanumeric character.
 */
public final class DriverResourceKey<R extends AutoCloseable> {
    private static final int MAX_NAME_CHARACTERS = 128;
    private static final Pattern NAME = Pattern.compile(
        "[a-zA-Z0-9][a-zA-Z0-9._-]*"
    );
    private final String name;
    private final Class<R> type;

    private DriverResourceKey(String name, Class<R> type) {
        this.name = requireName(name);
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

    private static String requireName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        if (name.length() > MAX_NAME_CHARACTERS || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                "name must be 1-" + MAX_NAME_CHARACTERS
                    + " ASCII identifier characters"
            );
        }
        return name;
    }
}
