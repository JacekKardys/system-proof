package io.github.jacekkardys.systemproof.model;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/** Stable semantic identity of one directional logical and runtime connection. */
public record ConnectionId(String value) {
    private static final String COMPONENT = "[a-z0-9][a-z0-9_-]*";
    private static final String PORT = "(?:[a-zA-Z0-9_-]|%[0-9A-F]{2})+";
    private static final Pattern CANONICAL =
        Pattern.compile(COMPONENT + "\\." + PORT + "->" + COMPONENT + "\\." + PORT);

    public ConnectionId {
        Objects.requireNonNull(value, "connection id must not be null");
        if (!CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid connection id: " + value);
        }
    }

    public static ConnectionId of(String value) {
        return new ConnectionId(value);
    }

    public static ConnectionId between(RequiredPort<?> source, ProvidedPort<?> target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        return new ConnectionId(
            source.owner().id() + "." + encodePortName(source.name())
                + "->"
                + target.owner().id() + "." + encodePortName(target.name())
        );
    }

    @Override
    public String toString() {
        return value;
    }

    private static String encodePortName(String name) {
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte current : bytes) {
            int value = Byte.toUnsignedInt(current);
            if (isUnreserved(value)) {
                encoded.append((char) value);
            } else {
                encoded.append('%');
                encoded.append(Character.toUpperCase(Character.forDigit(value >>> 4, 16)));
                encoded.append(Character.toUpperCase(Character.forDigit(value & 0x0f, 16)));
            }
        }
        return encoded.toString();
    }

    private static boolean isUnreserved(int value) {
        return value >= 'a' && value <= 'z'
            || value >= 'A' && value <= 'Z'
            || value >= '0' && value <= '9'
            || value == '_'
            || value == '-';
    }
}
