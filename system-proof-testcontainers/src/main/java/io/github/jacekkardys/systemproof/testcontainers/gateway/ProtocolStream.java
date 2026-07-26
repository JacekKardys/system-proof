package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.nio.ByteBuffer;

/**
 * Stateful decoder for one physical session direction.
 *
 * <p>The supplied buffer is read-only and contains only bounded, not-yet-forwarded original bytes.
 * A decoder must not retain it. Returning a complete unit does not itself record, decide, or
 * forward anything.
 */
public interface ProtocolStream<E> {
    ProtocolDecodeResult<E> decode(ByteBuffer bufferedBytes)
        throws ProtocolAdapterException;

    /**
     * Validates EOF after all complete units have been removed.
     *
     * <p>Protocols with additional clean-shutdown semantics may override this method.
     */
    default void endOfInput(ByteBuffer bufferedBytes) throws ProtocolAdapterException {
        if (bufferedBytes.hasRemaining()) {
            throw new ProtocolAdapterException(
                ProtocolFailureKind.DESYNCHRONIZATION,
                "Input ended inside a protocol unit"
            );
        }
    }
}
