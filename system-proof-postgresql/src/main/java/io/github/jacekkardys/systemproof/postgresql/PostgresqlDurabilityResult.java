package io.github.jacekkardys.systemproof.postgresql;

import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityRequirements.Table;

/**
 * Immutable secret-safe PostgreSQL durability prerequisite result.
 *
 * @param synchronousCommit typed value of {@code synchronous_commit}
 * @param fsync typed value of {@code fsync}
 * @param independentSession whether SUT and verification connections use different backends
 * @param verificationConsistent whether exact-SUT and independent checks returned the same facts
 * @param tables persistence classification for every required table
 * @param tableTriggers enabled user-trigger classification for every required table
 */
public record PostgresqlDurabilityResult(
    Setting synchronousCommit,
    Setting fsync,
    boolean independentSession,
    boolean verificationConsistent,
    Map<Table, TablePersistence> tables,
    Map<Table, TableTriggers> tableTriggers
) {
    public enum Setting {
        /** The setting is exactly {@code on}. */
        ON,
        /** The setting is exactly {@code off}. */
        OFF,
        /** The setting returned another value. */
        OTHER
    }

    public enum TablePersistence {
        /** A permanent, WAL-logged relation. */
        PERMANENT,
        /** An unlogged relation. */
        UNLOGGED,
        /** A temporary relation. */
        TEMPORARY,
        /** No matching relation exists. */
        MISSING,
        /** PostgreSQL returned an unknown persistence class. */
        OTHER
    }

    public enum TableTriggers {
        /** No enabled non-internal trigger can execute during a proof write or deferred commit. */
        NONE_ENABLED,
        /** At least one enabled non-internal trigger exists. */
        ENABLED
    }

    public PostgresqlDurabilityResult {
        synchronousCommit = Objects.requireNonNull(
            synchronousCommit,
            "synchronousCommit must not be null"
        );
        fsync = Objects.requireNonNull(fsync, "fsync must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        if (tables.entrySet().stream().anyMatch(entry ->
            entry.getKey() == null || entry.getValue() == null
        )) {
            throw new NullPointerException("tables must not contain null keys or values");
        }
        tables = Map.copyOf(tables);
        Objects.requireNonNull(tableTriggers, "tableTriggers must not be null");
        if (tableTriggers.entrySet().stream().anyMatch(entry ->
            entry.getKey() == null || entry.getValue() == null
        )) {
            throw new NullPointerException(
                "tableTriggers must not contain null keys or values"
            );
        }
        tableTriggers = Map.copyOf(tableTriggers);
        if (!tables.keySet().equals(tableTriggers.keySet())) {
            throw new IllegalArgumentException(
                "tables and tableTriggers must describe the same relations"
            );
        }
    }

    /** Returns whether every mandatory prerequisite is satisfied. */
    public boolean satisfied() {
        return synchronousCommit == Setting.ON
            && fsync == Setting.ON
            && independentSession
            && verificationConsistent
            && !tables.isEmpty()
            && tables.values().stream().allMatch(
                persistence -> persistence == TablePersistence.PERMANENT
            )
            && tableTriggers.values().stream().allMatch(
                triggers -> triggers == TableTriggers.NONE_ENABLED
            );
    }

    /** Fails closed when any durability prerequisite is not satisfied. */
    public PostgresqlDurabilityResult requireSatisfied() {
        if (!satisfied()) {
            throw new IllegalStateException(
                "PostgreSQL durability prerequisites are not satisfied"
            );
        }
        return this;
    }
}
