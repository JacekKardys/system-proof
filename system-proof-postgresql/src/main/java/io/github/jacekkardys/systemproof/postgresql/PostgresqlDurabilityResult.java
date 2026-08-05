package io.github.jacekkardys.systemproof.postgresql;

import java.util.Map;
import java.util.Objects;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlDurabilityRequirements.Table;

/** Immutable secret-safe PostgreSQL durability preflight result. */
public record PostgresqlDurabilityResult(
    Setting synchronousCommit,
    Setting fsync,
    Map<Table, RelationStatus> relations
) {
    public enum Setting {
        /** The setting is exactly {@code on}. */
        ON,
        /** The setting is exactly {@code off}. */
        OFF,
        /** The setting returned another value. */
        OTHER
    }

    /** Typed, fail-closed classification of one requested relation. */
    public enum RelationStatus {
        /** An ordinary permanent WAL-logged table. */
        PERMANENT_TABLE,
        /** An ordinary unlogged table. */
        UNLOGGED_TABLE,
        /** An ordinary temporary table. */
        TEMPORARY_TABLE,
        VIEW,
        MATERIALIZED_VIEW,
        FOREIGN_TABLE,
        SEQUENCE,
        PARTITIONED_TABLE,
        /** A relation kind outside the explicitly classified set. */
        OTHER_RELATION_KIND,
        /** No matching relation exists. */
        MISSING
    }

    public PostgresqlDurabilityResult {
        synchronousCommit = Objects.requireNonNull(
            synchronousCommit,
            "synchronousCommit must not be null"
        );
        fsync = Objects.requireNonNull(fsync, "fsync must not be null");
        Objects.requireNonNull(relations, "relations must not be null");
        if (relations.entrySet().stream().anyMatch(entry ->
            entry.getKey() == null || entry.getValue() == null
        )) {
            throw new NullPointerException(
                "relations must not contain null keys or values"
            );
        }
        relations = Map.copyOf(relations);
    }

    /** Returns whether every mandatory preflight fact is satisfied. */
    public boolean satisfied() {
        return synchronousCommit == Setting.ON
            && fsync == Setting.ON
            && !relations.isEmpty()
            && relations.values().stream().allMatch(
                status -> status == RelationStatus.PERMANENT_TABLE
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
