package io.github.jacekkardys.systemproof.postgresql;

import java.nio.ByteBuffer;
import java.util.OptionalLong;

/**
 * Ephemeral read-only view supplied only during synchronous decoding of one supported write.
 * Implementations become invalid immediately after the correlation callback returns.
 */
public interface PostgresqlWriteInteraction {
    enum ParameterFormat {
        TEXT,
        BINARY
    }

    /** Returns the normalized supported statement shape. */
    PostgresqlStatementShape shape();

    /** Returns the bind parameter count. */
    int parameterCount();

    /** Returns whether the zero-based bind parameter is SQL {@code NULL}. */
    boolean parameterIsNull(int zeroBasedIndex);

    /** Returns the encoded byte count, or zero for SQL {@code NULL}. */
    int parameterSize(int zeroBasedIndex);

    /** Returns the wire format selected for the zero-based bind parameter. */
    ParameterFormat parameterFormat(int zeroBasedIndex);

    /** Returns the Parse type OID when the client supplied one. */
    OptionalLong parameterTypeOid(int zeroBasedIndex);

    /** Returns a read-only view valid only for the duration of the current callback. */
    ByteBuffer parameterBytes(int zeroBasedIndex);
}
