package io.github.jacekkardys.systemproof.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityRequirements.Table;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityResult.RelationStatus;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityResult.Setting;

class PostgresqlDurabilityVerifierIT {
    @Test
    void shouldRequireOnSettingsAndPermanentOrdinaryTables() throws Exception {
        try (PostgreSQLContainer<?> postgres = durablePostgres()) {
            postgres.start();
            try (Connection verification = connection(postgres);
                 Statement statement = verification.createStatement()) {
                statement.execute("CREATE TABLE proof_entry (id bigint primary key)");
                Table table = new Table("public", "proof_entry");

                PostgresqlDurabilityResult result = PostgresqlDurabilityVerifier.verify(
                    verification,
                    new PostgresqlDurabilityRequirements(Set.of(table))
                );

                assertThat(result.synchronousCommit()).isEqualTo(Setting.ON);
                assertThat(result.fsync()).isEqualTo(Setting.ON);
                assertThat(result.relations())
                    .containsEntry(table, RelationStatus.PERMANENT_TABLE);
                assertThat(result.requireSatisfied()).isSameAs(result);
            }
        }
    }

    @Test
    void shouldFailClosedForOffSettingAndUnloggedTable() throws Exception {
        try (PostgreSQLContainer<?> postgres = durablePostgres()) {
            postgres.start();
            try (Connection verification = connection(postgres);
                 Statement statement = verification.createStatement()) {
                statement.execute("SET synchronous_commit = off");
                statement.execute("CREATE UNLOGGED TABLE transient_entry (id bigint)");
                Table table = new Table("public", "transient_entry");

                PostgresqlDurabilityResult result = PostgresqlDurabilityVerifier.verify(
                    verification,
                    new PostgresqlDurabilityRequirements(Set.of(table))
                );

                assertThat(result.synchronousCommit()).isEqualTo(Setting.OFF);
                assertThat(result.relations())
                    .containsEntry(table, RelationStatus.UNLOGGED_TABLE);
                assertThat(result.satisfied()).isFalse();
                assertThatThrownBy(result::requireSatisfied)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PostgreSQL durability prerequisites are not satisfied");
            }
        }
    }

    @Test
    void shouldClassifyEveryUnsupportedRelationKindAndMissingRelation()
        throws Exception {
        try (PostgreSQLContainer<?> postgres = durablePostgres()) {
            postgres.start();
            try (Connection verification = connection(postgres);
                 Statement statement = verification.createStatement()) {
                statement.execute("CREATE TABLE base_entry (id bigint)");
                statement.execute("CREATE TEMP TABLE temporary_entry (id bigint)");
                statement.execute("CREATE VIEW proof_view AS SELECT 1 AS id");
                statement.execute("CREATE MATERIALIZED VIEW proof_materialized AS SELECT 1 AS id");
                statement.execute("CREATE SEQUENCE proof_sequence");
                statement.execute("""
                    CREATE TABLE proof_partitioned (id bigint)
                    PARTITION BY RANGE (id)
                    """);
                statement.execute("CREATE INDEX proof_index ON base_entry (id)");
                statement.execute("CREATE EXTENSION file_fdw");
                statement.execute("CREATE SERVER proof_file_server FOREIGN DATA WRAPPER file_fdw");
                statement.execute("""
                    CREATE FOREIGN TABLE proof_foreign (id bigint)
                    SERVER proof_file_server
                    OPTIONS (filename '/tmp/system-proof-unused.csv', format 'csv')
                    """);
                Set<Table> requested = Set.of(
                    new Table("pg_temp", "temporary_entry"),
                    table("proof_view"),
                    table("proof_materialized"),
                    table("proof_foreign"),
                    table("proof_sequence"),
                    table("proof_partitioned"),
                    table("proof_index"),
                    table("missing_entry")
                );

                PostgresqlDurabilityResult result = PostgresqlDurabilityVerifier.verify(
                    verification,
                    new PostgresqlDurabilityRequirements(requested)
                );

                assertThat(result.relations())
                    .containsEntry(
                        new Table("pg_temp", "temporary_entry"),
                        RelationStatus.TEMPORARY_TABLE
                    )
                    .containsEntry(table("proof_view"), RelationStatus.VIEW)
                    .containsEntry(
                        table("proof_materialized"),
                        RelationStatus.MATERIALIZED_VIEW
                    )
                    .containsEntry(table("proof_foreign"), RelationStatus.FOREIGN_TABLE)
                    .containsEntry(table("proof_sequence"), RelationStatus.SEQUENCE)
                    .containsEntry(
                        table("proof_partitioned"),
                        RelationStatus.PARTITIONED_TABLE
                    )
                    .containsEntry(
                        table("proof_index"),
                        RelationStatus.OTHER_RELATION_KIND
                    )
                    .containsEntry(table("missing_entry"), RelationStatus.MISSING);
                assertThat(result.satisfied()).isFalse();
            }
        }
    }

    private static Table table(String name) {
        return new Table("public", name);
    }

    private static Connection connection(PostgreSQLContainer<?> postgres)
        throws Exception {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(),
            postgres.getUsername(),
            postgres.getPassword()
        );
    }

    private static PostgreSQLContainer<?> durablePostgres() {
        return new PostgreSQLContainer<>("postgres:17.6-alpine")
            .withCommand(
                "postgres",
                "-c",
                "fsync=on",
                "-c",
                "synchronous_commit=on"
            );
    }
}
