package io.github.jacekkardys.systemproof.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.journal.CheckpointId;
import io.github.jacekkardys.systemproof.journal.DisruptionId;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.proof.CorrelationKeySchema;
import io.github.jacekkardys.systemproof.topology.Contract;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.DeclaredInteraction;
import io.github.jacekkardys.systemproof.topology.DeclaredProtocol;

class PublicDiagnosticMetadataTest {
    private static final String CANARY = "public-metadata-canary";

    @Test
    void shouldRejectMultilineUnicodeAndOversizedPublicMetadataWithoutEchoingIt() {
        String hostile = CANARY + "\nza\u017C\u00F3\u0142\u0107-\u79D8\u5BC6";
        ComponentType validType = ComponentType.of("component");
        ComponentId validId = ComponentId.component(validType);
        List<Runnable> hostileConstructors = List.of(
            () -> ComponentType.of(hostile),
            () -> ComponentId.component(validType, hostile),
            () -> new CheckpointId(hostile),
            () -> new DisruptionId(hostile),
            () -> new EvidenceSchemaId(hostile, "name", 1),
            () -> new CorrelationKeySchema("namespace", hostile, 1),
            () -> new Contract<>(hostile, String.class),
            () -> new DeclaredInteraction(hostile),
            () -> new DeclaredProtocol(hostile, "http"),
            () -> new DeclaredProtocol("http", hostile),
            () -> ConnectionId.between(validId, hostile, validId, "api"),
            () -> ConnectionId.between(validId, "a".repeat(65), validId, "api"),
            () -> ComponentType.of("a".repeat(65))
        );

        hostileConstructors.forEach(constructor ->
            assertThatThrownBy(constructor::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(CANARY)
                .hasMessageNotContaining("za\u017C\u00F3\u0142\u0107")
                .hasMessageNotContaining("\u79D8\u5BC6")
        );
    }

    @Test
    void shouldPreserveTheBoundedJdbcSubprotocolScheme() {
        assertThat(new DeclaredProtocol("jdbc-postgresql", "jdbc:postgresql").scheme())
            .isEqualTo("jdbc:postgresql");
    }
}
