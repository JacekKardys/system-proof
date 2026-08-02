package io.github.jacekkardys.systemproof.examples.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;
import org.junit.jupiter.api.Tag;

@Tag("docker")
final class PostgresExampleIT {

    @SystemProof(
        value = PostgresExampleEnvironment.class,
        title = "PostgreSQL storage round trip",
        description = "Verifies that values written through the environment can be read back"
    )
    void storesAndReadsValues(PostgresExampleEnvironment environment) {
        environment.database().initialize();
        environment.database().insert("first");
        environment.database().insert("second");

        assertThat(environment.database().values()).containsExactly("first", "second");
    }
}
