package io.github.jacekkardys.systemproof.model;

import java.util.Objects;

/** Detached immutable semantic metadata for one logical or runtime connection. */
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
        sourceRequiredPortName = requireText(
            sourceRequiredPortName,
            "sourceRequiredPortName"
        );
        targetComponentId = Objects.requireNonNull(
            targetComponentId,
            "targetComponentId must not be null"
        );
        targetProvidedPortName = requireText(
            targetProvidedPortName,
            "targetProvidedPortName"
        );
        contractId = requireText(contractId, "contractId");
        contractTypeName = requireText(contractTypeName, "contractTypeName");
        interactionId = requireText(interactionId, "interactionId");
        protocolId = requireText(protocolId, "protocolId");
        protocolScheme = requireText(protocolScheme, "protocolScheme");
    }

    public static ConnectionDescriptor from(ConnectionRef connection) {
        Objects.requireNonNull(connection, "connection must not be null");
        return new ConnectionDescriptor(
            connection.id(),
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

    public String sourcePortQualifiedName() {
        return sourceComponentId + "." + sourceRequiredPortName;
    }

    public String targetPortQualifiedName() {
        return targetComponentId + "." + targetProvidedPortName;
    }

    private static String requireText(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }
}
