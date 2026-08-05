package io.github.jacekkardys.systemproof.http;

import java.util.Objects;
import java.util.Optional;

/** Secret-safe typed HTTP evidence for complete units in the supported plaintext subset. */
public sealed interface HttpEvidence
    permits HttpEvidence.RequestCompleted, HttpEvidence.ResponseCompleted {

    /** Conservative classification of a complete response in the characterized flow. */
    enum Acknowledgement {
        POSITIVE,
        NEGATIVE
    }

    /** A complete HTTP/1.1 request with payload content deliberately omitted. */
    record RequestCompleted(
        HttpExchangeRef exchange,
        String method,
        String path,
        Optional<String> contentType,
        int bodyByteCount
    ) implements HttpEvidence {
        public RequestCompleted {
            exchange = Objects.requireNonNull(exchange, "exchange must not be null");
            method = requireText(method, "method");
            path = requirePath(path);
            contentType = Objects.requireNonNull(
                contentType,
                "contentType must not be null"
            ).map(value -> requireText(value, "contentType"));
            if (bodyByteCount < 0) {
                throw new IllegalArgumentException("bodyByteCount must not be negative");
            }
        }

        @Override
        public String toString() {
            return "RequestCompleted[exchange=" + exchange
                + ", method=" + method
                + ", pathLength=" + path.length()
                + ", contentTypePresent=" + contentType.isPresent()
                + ", bodyByteCount=" + bodyByteCount + "]";
        }
    }

    /** A complete HTTP/1.1 response with payload content deliberately omitted. */
    record ResponseCompleted(
        HttpExchangeRef exchange,
        int statusCode,
        Acknowledgement acknowledgement,
        int bodyByteCount
    ) implements HttpEvidence {
        public ResponseCompleted {
            exchange = Objects.requireNonNull(exchange, "exchange must not be null");
            if (statusCode < 100 || statusCode > 999) {
                throw new IllegalArgumentException("statusCode must contain three digits");
            }
            acknowledgement = Objects.requireNonNull(
                acknowledgement,
                "acknowledgement must not be null"
            );
            if (bodyByteCount < 0) {
                throw new IllegalArgumentException("bodyByteCount must not be negative");
            }
        }
    }

    private static String requireText(String value, String description) {
        Objects.requireNonNull(value, description + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }

    private static String requirePath(String path) {
        Objects.requireNonNull(path, "path must not be null");
        if (!path.startsWith("/") || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
            throw new IllegalArgumentException("path must be an origin-form path without query");
        }
        return path;
    }
}
