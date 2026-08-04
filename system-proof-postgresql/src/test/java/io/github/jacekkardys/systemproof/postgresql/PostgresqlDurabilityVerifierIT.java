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
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityResult.Setting;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityResult.TablePersistence;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityResult.TableTriggers;

class PostgresqlDurabilityVerifierIT {
    @Test
    void shouldRequireOnSettingsPermanentTablesAndAnIndependentBackend() throws Exception {
        try (PostgreSQLContainer<?> postgres = durablePostgres()) {
            postgres.start();
            try (Connection sut = connection(postgres);
                 Connection verification = connection(postgres);
                 Statement statement = verification.createStatement()) {
                statement.execute("CREATE TABLE proof_entry (id bigint primary key)");
                PostgresqlDurabilityRequirements requirements =
                    new PostgresqlDurabilityRequirements(Set.of(
                        new Table("public", "proof_entry")
                    ));

                PostgresqlDurabilityResult result = PostgresqlDurabilityVerifier.verify(
                    sut,
                    verification,
                    requirements
                );

                assertThat(result.synchronousCommit()).isEqualTo(Setting.ON);
                assertThat(result.fsync()).isEqualTo(Setting.ON);
                assertThat(result.independentSession()).isTrue();
                assertThat(result.verificationConsistent()).isTrue();
                assertThat(result.tables()).containsEntry(
                    new Table("public", "proof_entry"),
                    TablePersistence.PERMANENT
                );
                assertThat(result.tableTriggers()).containsEntry(
                    new Table("public", "proof_entry"),
                    TableTriggers.NONE_ENABLED
                );
                assertThat(result.requireSatisfied()).isSameAs(result);
            }
        }
    }

    @Test
    void shouldFailClosedForOffSettingUnloggedTableAndSameBackend() throws Exception {
        try (PostgreSQLContainer<?> postgres = durablePostgres()) {
            postgres.start();
            try (Connection connection = connection(postgres);
                 Statement statement = connection.createStatement()) {
                statement.execute("SET synchronous_commit = off");
                statement.execute("CREATE UNLOGGED TABLE transient_entry (id bigint)");
                Table table = new Table("public", "transient_entry");

                PostgresqlDurabilityResult result = PostgresqlDurabilityVerifier.verify(
                    connection,
                    connection,
                    new PostgresqlDurabilityRequirements(Set.of(table))
                );

                assertThat(result.synchronousCommit()).isEqualTo(Setting.OFF);
                assertThat(result.independentSession()).isFalse();
                assertThat(result.tables()).containsEntry(table, TablePersistence.UNLOGGED);
                assertThat(result.satisfied()).isFalse();
                assertThatThrownBy(result::requireSatisfied)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("PostgreSQL durability prerequisites are not satisfied");
            }
        }
    }

    @Test
    void shouldReadSynchronousCommitFromTheExactSutSession() throws Exception {
        try (PostgreSQLContainer<?> postgres = durablePostgres()) {
            postgres.start();
            try (Connection sut = connection(postgres);
                 Connection verification = connection(postgres);
                 Statement sutStatement = sut.createStatement();
                 Statement setup = verification.createStatement()) {
                sutStatement.execute("SET synchronous_commit = off");
                setup.execute("CREATE TABLE proof_entry (id bigint primary key)");

                PostgresqlDurabilityResult result = PostgresqlDurabilityVerifier.verify(
                    sut,
                    verification,
                    new PostgresqlDurabilityRequirements(Set.of(
                        new Table("public", "proof_entry")
                    ))
                );

                assertThat(result.synchronousCommit()).isEqualTo(Setting.OFF);
                assertThat(result.fsync()).isEqualTo(Setting.ON);
                assertThat(result.satisfied()).isFalse();
            }
        }
    }

    @Test
    void shouldRejectEnabledUserTriggersThatCanChangeCommitSettings()
        throws Exception {
        try (PostgreSQLContainer<?> postgres = durablePostgres()) {
            postgres.start();
            try (Connection sut = connection(postgres);
                 Connection verification = connection(postgres);
                 Statement setup = verification.createStatement()) {
                setup.execute("CREATE TABLE proof_entry (id bigint primary key)");
                setup.execute("""
                    CREATE FUNCTION change_commit_setting() RETURNS trigger
                    LANGUAGE plpgsql AS $$
                    BEGIN
                        PERFORM set_config('synchronous_commit', 'off', true);
                        RETURN NEW;
                    END
                    $$
                    """);
                setup.execute("""
                    CREATE CONSTRAINT TRIGGER proof_entry_commit_setting
                    AFTER INSERT ON proof_entry
                    DEFERRABLE INITIALLY DEFERRED
                    FOR EACH ROW EXECUTE FUNCTION change_commit_setting()
                    """);
                Table table = new Table("public", "proof_entry");

                PostgresqlDurabilityResult result = PostgresqlDurabilityVerifier.verify(
                    sut,
                    verification,
                    new PostgresqlDurabilityRequirements(Set.of(table))
                );

                assertThat(result.tableTriggers()).containsEntry(
                    table,
                    TableTriggers.ENABLED
                );
                assertThat(result.satisfied()).isFalse();
            }
        }
    }

    private static Connection connection(PostgreSQLContainer<?> postgres) throws Exception {
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
