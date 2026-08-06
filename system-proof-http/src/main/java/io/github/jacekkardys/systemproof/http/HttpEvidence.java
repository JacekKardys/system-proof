package io.github.jacekkardys.systemproof.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/** Secret-safe typed HTTP evidence for complete units in the supported plaintext subset. */
public sealed interface HttpEvidence
    permits HttpEvidence.RequestCompleted, HttpEvidence.ResponseCompleted {

    /** Conservative classification of a complete response in the characterized flow. */
    enum Acknowledgement {
        POSITIVE,
        INDETERMINATE,
        NEGATIVE
    }

    /** Bounded semantic category that cannot carry an arbitrary request-method token. */
    enum RequestMethod {
        POST,
        OTHER;

        static RequestMethod fromWire(String method) {
            return method.equals("POST") ? POST : OTHER;
        }
    }

    /** Secret-safe digest summary of an origin-form request target. */
    record RequestTarget(int byteCount, String sha256) {
        public RequestTarget {
            if (byteCount < 1 || byteCount > HttpProtocolLimits.MAXIMUM_START_LINE_BYTES) {
                throw new IllegalArgumentException("request target byteCount is outside limits");
            }
            sha256 = Objects.requireNonNull(sha256, "sha256 must not be null");
            if (!sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("sha256 must be lowercase hexadecimal");
            }
        }

        /** Returns a digest summary without retaining the supplied path. */
        public static RequestTarget ofPath(String path) {
            Objects.requireNonNull(path, "path must not be null");
            if (!path.startsWith("/") || path.indexOf('?') >= 0 || path.indexOf('#') >= 0) {
                throw new IllegalArgumentException(
                    "path must be an origin-form path without query"
                );
            }
            if (path.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
                throw new IllegalArgumentException("path must contain visible ASCII without spaces");
            }
            byte[] bytes = path.getBytes(StandardCharsets.US_ASCII);
            try {
                return new RequestTarget(
                    bytes.length,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                );
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 is unavailable", failure);
            }
        }
    }

    /** Closed category that cannot carry an arbitrary raw Content-Type value. */
    enum RequestContentType {
        ABSENT,
        FORM_URLENCODED,
        OTHER;

        static RequestContentType fromWire(Optional<String> contentType) {
            return contentType
                .filter("application/x-www-form-urlencoded"::equalsIgnoreCase)
                .map(ignored -> FORM_URLENCODED)
                .orElseGet(() -> contentType.isPresent() ? OTHER : ABSENT);
        }
    }

    /** A complete HTTP/1.1 request with payload content deliberately omitted. */
    record RequestCompleted(
        HttpExchangeRef exchange,
        RequestMethod method,
        RequestTarget target,
        RequestContentType contentType,
        int bodyByteCount
    ) implements HttpEvidence {
        public RequestCompleted {
            exchange = Objects.requireNonNull(exchange, "exchange must not be null");
            method = Objects.requireNonNull(method, "method must not be null");
            target = Objects.requireNonNull(target, "target must not be null");
            contentType = Objects.requireNonNull(contentType, "contentType must not be null");
            if (bodyByteCount < 0
                || bodyByteCount > HttpProtocolLimits.MAXIMUM_BODY_BYTES) {
                throw new IllegalArgumentException("bodyByteCount is outside HTTP limits");
            }
        }

        @Override
        public String toString() {
            return "RequestCompleted[exchange=" + exchange
                + ", method=" + method
                + ", target=" + target
                + ", contentType=" + contentType
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
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("statusCode must be a valid HTTP status");
            }
            acknowledgement = Objects.requireNonNull(
                acknowledgement,
                "acknowledgement must not be null"
            );
            if (bodyByteCount < 0
                || bodyByteCount > HttpProtocolLimits.MAXIMUM_BODY_BYTES) {
                throw new IllegalArgumentException("bodyByteCount is outside HTTP limits");
            }
        }
    }

}
