package io.github.jacekkardys.systemproof.http;

import java.nio.ByteBuffer;
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

    int bodySize();

    /** Returns a read-only body view valid only for the current callback. */
    ByteBuffer bodyBytes();
}
