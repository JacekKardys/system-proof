package io.github.jacekkardys.systemproof.topology;

import java.util.Objects;
import io.github.jacekkardys.systemproof.component.ComponentId;

/**
 * Detached immutable semantic metadata for one logical or runtime connection.
 *
 * <p>The ID is derived from, and validated against, the structured source and target fields.
 */
public record ConnectionDescriptor(
    ConnectionId id,
    ComponentId sourceComponentId,
    String sourceRequiredPortName,
    ComponentId targetComponentId,
    String targetProvidedPortName,
    String contractId,
    String contractTypeName,
    String interactionId,
    String protocolId,
    String protocolScheme
) {
    public ConnectionDescriptor {
        id = Objects.requireNonNull(id, "id must not be null");
        sourceComponentId = Objects.requireNonNull(
            sourceComponentId,
            "sourceComponentId must not be null"
        );
        sourceRequiredPortName = requirePortName(
            sourceRequiredPortName,
            "sourceRequiredPortName"
        );
        targetComponentId = Objects.requireNonNull(
            targetComponentId,
            "targetComponentId must not be null"
        );
        targetProvidedPortName = requirePortName(
            targetProvidedPortName,
            "targetProvidedPortName"
        );
        contractId = requireIdentifier(contractId, "contractId", 128);
        contractTypeName = requireTypeName(contractTypeName);
        interactionId = requireIdentifier(interactionId, "interactionId", 128);
        protocolId = requireIdentifier(protocolId, "protocolId", 128);
        protocolScheme = requireScheme(protocolScheme);
        ConnectionId expectedId = ConnectionId.between(
            sourceComponentId,
            sourceRequiredPortName,
            targetComponentId,
            targetProvidedPortName
        );
        if (!id.equals(expectedId)) {
            throw new IllegalArgumentException(
                "Connection descriptor ID '" + id
                    + "' does not match its structured endpoints; expected '" + expectedId + "'"
            );
        }
    }

    public static ConnectionDescriptor from(ConnectionRef connection) {
        Objects.requireNonNull(connection, "connection must not be null");
        return of(
            connection.from().owner().id(),
            connection.from().name(),
            connection.to().owner().id(),
            connection.to().name(),
            connection.from().contractId(),
            connection.from().contractType().getName(),
            connection.from().interaction().id(),
            connection.from().protocol().id(),
            connection.from().protocol().scheme()
        );
    }

    public static ConnectionDescriptor of(
        ComponentId sourceComponentId,
        String sourceRequiredPortName,
        ComponentId targetComponentId,
        String targetProvidedPortName,
        String contractId,
        String contractTypeName,
        String interactionId,
        String protocolId,
        String protocolScheme
    ) {
        return new ConnectionDescriptor(
            ConnectionId.between(
                sourceComponentId,
                sourceRequiredPortName,
                targetComponentId,
                targetProvidedPortName
            ),
            sourceComponentId,
            sourceRequiredPortName,
            targetComponentId,
            targetProvidedPortName,
            contractId,
            contractTypeName,
            interactionId,
            protocolId,
            protocolScheme
        );
    }

    public String sourcePortQualifiedName() {
        return ConnectionId.canonicalEndpoint(sourceComponentId, sourceRequiredPortName);
    }

    public String targetPortQualifiedName() {
        return ConnectionId.canonicalEndpoint(targetComponentId, targetProvidedPortName);
    }

    private static String requireIdentifier(String value, String description, int maximum) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.length() > maximum
            || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException(
                description + " must be 1-" + maximum
                    + " ASCII identifier characters"
            );
        }
        return value;
    }

    private static String requirePortName(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.length() > 64 || value.isBlank()
            || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                description + " must be 1-64 non-control characters"
            );
        }
        return value;
    }

    private static String requireTypeName(String value) {
        Objects.requireNonNull(value, "contractTypeName must not be null");
        if (value.length() > 512 || !value.matches("[a-zA-Z0-9_.$;\\[\\]]+")) {
            throw new IllegalArgumentException(
                "contractTypeName must be 1-512 JVM type-name characters"
            );
        }
        return value;
    }

    private static String requireScheme(String value) {
        Objects.requireNonNull(value, "protocolScheme must not be null");
        if (value.length() > 64
            || !value.matches("[a-zA-Z][a-zA-Z0-9+.-]*(?::[a-zA-Z0-9][a-zA-Z0-9+.-]*)*")) {
            throw new IllegalArgumentException(
                "protocolScheme must be 1-64 ASCII scheme characters"
            );
        }
        return value;
    }
}
