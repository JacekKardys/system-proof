package io.github.jacekkardys.systemproof.examples.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jacekkardys.systemproof.junit.SystemProof;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@SystemProof(environment = PostgresExampleEnvironment.class)
@Tag("docker")
final class PostgresExampleIT {

    @Test
    void storesAndReadsValues(PostgresExampleEnvironment environment) {
        environment.database().initialize();
        environment.database().insert("first");
        environment.database().insert("second");

        assertThat(environment.database().values()).containsExactly("first", "second");
    }
}
