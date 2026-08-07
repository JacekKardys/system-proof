package io.github.jacekkardys.systemproof.topology;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;
import io.github.jacekkardys.systemproof.component.ComponentId;

/**
 * Stable semantic identity of one directional logical and runtime connection.
 *
 * <p>Each endpoint is encoded as {@code component-type[qualifier].local-port}. Empty qualifier
 * brackets mean that the component has no qualifier. Component type and qualifier are read from
 * their structured fields and never from {@link ComponentId#toString()} or
 * {@link ComponentId#value()}.
 */
public record ConnectionId(String value) {
    private static final String IDENTIFIER = "[a-z0-9][a-z0-9_-]*";
    private static final String PORT = "(?:[a-zA-Z0-9_-]|%[0-9A-F]{2})+";
    private static final String ENDPOINT =
        IDENTIFIER + "\\[(?:" + IDENTIFIER + ")?\\]\\." + PORT;
    private static final Pattern CANONICAL =
        Pattern.compile(ENDPOINT + "->" + ENDPOINT);

    public ConnectionId {
        Objects.requireNonNull(value, "connection id must not be null");
        if (value.length() > 2_048 || !CANONICAL.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "connection id must be a canonical value of at most 2048 characters"
            );
        }
    }

    public static ConnectionId of(String value) {
        return new ConnectionId(value);
    }

    public static ConnectionId between(RequiredPort<?> source, ProvidedPort<?> target) {
        Objects.requireNonNull(source, "source must not be null");
        Objects.requireNonNull(target, "target must not be null");
        return between(
            source.owner().id(),
            source.name(),
            target.owner().id(),
            target.name()
        );
    }

    public static ConnectionId between(
        ComponentId sourceComponentId,
        String sourceRequiredPortName,
        ComponentId targetComponentId,
        String targetProvidedPortName
    ) {
        return new ConnectionId(
            canonicalEndpoint(
                sourceComponentId,
                sourceRequiredPortName,
                "sourceComponentId",
                "sourceRequiredPortName"
            )
                + "->"
                + canonicalEndpoint(
                    targetComponentId,
                    targetProvidedPortName,
                    "targetComponentId",
                    "targetProvidedPortName"
                )
        );
    }

    @Override
    public String toString() {
        return value;
    }

    static String canonicalEndpoint(ComponentId componentId, String portName) {
        return canonicalEndpoint(componentId, portName, "componentId", "portName");
    }

    private static String canonicalEndpoint(
        ComponentId componentId,
        String portName,
        String componentDescription,
        String portDescription
    ) {
        Objects.requireNonNull(componentId, componentDescription + " must not be null");
        Objects.requireNonNull(portName, portDescription + " must not be null");
        if (portName.length() > 64 || portName.isBlank()
            || portName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                portDescription + " must be 1-64 non-control characters"
            );
        }
        return componentId.type().value()
            + componentId.qualifier().map(value -> "[" + value + "]").orElse("[]")
            + "."
            + encodePortName(portName);
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
