package io.github.jacekkardys.systemproof.http;

import java.util.Optional;

/**
 * Ephemeral read-only view supplied only during synchronous decoding of one complete request.
 * The view becomes invalid immediately after the correlation callback returns.
 */
public interface HttpRequestInteraction {
    String method();

    /** Returns the origin-form path without a query component. */
    String path();

    /** Returns the normalized Content-Type field value, when present. */
    Optional<String> contentType();

    /** Returns scoped indexed access that expires with this interaction. */
    Body body();

    /** Read-only body access whose every operation checks callback activity. */
    interface Body {
        int size();

        byte byteAt(int index);

        void copyTo(int sourceOffset, byte[] destination, int destinationOffset, int length);
    }
}
