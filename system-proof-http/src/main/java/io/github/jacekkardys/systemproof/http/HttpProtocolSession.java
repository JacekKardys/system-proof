package io.github.jacekkardys.systemproof.http;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import io.github.jacekkardys.systemproof.environment.CorrelationContribution;
import io.github.jacekkardys.systemproof.http.HttpEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestCompleted;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestContentType;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestMethod;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestTarget;
import io.github.jacekkardys.systemproof.http.HttpEvidence.ResponseCompleted;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolDecodeResult;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolStream;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit;

final class HttpProtocolSession implements ProtocolSession<HttpEvidence> {
    private static final String POSITIVE_ACKNOWLEDGEMENT = "ACK/Jasmin";

    private final HttpMessageFramer framer;
    private final HttpRequestCorrelation requestCorrelation;
    private final SessionModel model;
    private boolean requestOpened;
    private boolean responseOpened;

    HttpProtocolSession(
        long sessionOrdinal,
        ProtocolLimits gatewayLimits,
        HttpProtocolLimits httpLimits,
        HttpRequestCorrelation requestCorrelation
    ) {
        framer = new HttpMessageFramer(gatewayLimits, httpLimits);
        this.requestCorrelation = Objects.requireNonNull(
            requestCorrelation,
            "requestCorrelation must not be null"
        );
        model = new SessionModel(sessionOrdinal);
    }

    @Override
    public synchronized ProtocolStream<HttpEvidence> openStream(FlowDirection direction) {
        Objects.requireNonNull(direction, "direction must not be null");
        return switch (direction) {
            case CONSUMER_TO_PROVIDER -> {
                if (requestOpened) {
                    throw new IllegalStateException("HTTP request stream was already opened");
                }
                requestOpened = true;
                yield new RequestStream();
            }
            case PROVIDER_TO_CONSUMER -> {
                if (responseOpened) {
                    throw new IllegalStateException("HTTP response stream was already opened");
                }
                responseOpened = true;
                yield new ResponseStream();
            }
        };
    }

    private final class RequestStream implements ProtocolStream<HttpEvidence> {
        @Override
        public ProtocolDecodeResult<HttpEvidence> decode(ByteBuffer bufferedBytes)
            throws ProtocolAdapterException {
            Objects.requireNonNull(bufferedBytes, "bufferedBytes must not be null");
            try {
                model.requireRequestDecodeAllowed(bufferedBytes.hasRemaining());
                if (!bufferedBytes.hasRemaining()) {
                    return ProtocolDecodeResult.needMoreData();
                }
                if (model.hasPendingRequest()) {
                    throw failure(
                        ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                        "HTTP request pipelining is unsupported"
                    );
                }
                HttpMessageFramer.RequestFrame frame = framer.decodeRequest(bufferedBytes);
                if (frame == null) {
                    return ProtocolDecodeResult.needMoreData();
                }
                HttpExchangeRef exchange = model.beginRequest(frame.closesConnection());
                byte[] originalBytes = framer.copyOriginal(bufferedBytes, frame);
                EphemeralRequest interaction = new EphemeralRequest(frame, originalBytes);
                Optional<CorrelationKey> key;
                try {
                    key = Objects.requireNonNull(
                        requestCorrelation.correlate(interaction),
                        "HTTP request correlation returned null"
                    );
                } finally {
                    interaction.invalidate();
                }
                List<CorrelationContribution<?>> contributions = key
                    .<List<CorrelationContribution<?>>>map(value -> List.of(
                        CorrelationContribution.capture(
                            value,
                            HttpExchangeRef.codec(),
                            exchange
                        )
                    ))
                    .orElseGet(List::of);
                return ProtocolDecodeResult.complete(new ProtocolUnit<>(
                    originalBytes,
                    new RequestCompleted(
                        exchange,
                        RequestMethod.fromWire(frame.method()),
                        RequestTarget.ofPath(frame.path()),
                        RequestContentType.fromWire(frame.contentType()),
                        frame.bodyByteCount()
                    ),
                    contributions
                ));
            } catch (ProtocolAdapterException | RuntimeException | Error failure) {
                model.terminal();
                throw failure;
            }
        }

        @Override
        public void endOfInput(ByteBuffer bufferedBytes) throws ProtocolAdapterException {
            try {
                ProtocolStream.super.endOfInput(bufferedBytes);
                model.requestInputEnded();
            } catch (ProtocolAdapterException failure) {
                model.terminal();
                throw failure;
            }
        }
    }

    private final class ResponseStream implements ProtocolStream<HttpEvidence> {
        @Override
        public ProtocolDecodeResult<HttpEvidence> decode(ByteBuffer bufferedBytes)
            throws ProtocolAdapterException {
            Objects.requireNonNull(bufferedBytes, "bufferedBytes must not be null");
            try {
                model.requireResponseInputOpen();
                if (!bufferedBytes.hasRemaining()) {
                    return ProtocolDecodeResult.needMoreData();
                }
                if (!model.hasPendingRequest()) {
                    throw failure(
                        ProtocolFailureKind.DESYNCHRONIZATION,
                        "HTTP response has no pending request"
                    );
                }
                HttpMessageFramer.ResponseFrame frame = framer.decodeResponse(bufferedBytes);
                if (frame == null) {
                    return ProtocolDecodeResult.needMoreData();
                }
                byte[] originalBytes = framer.copyOriginal(bufferedBytes, frame);
                Acknowledgement acknowledgement = classifyAcknowledgement(
                    frame.statusCode(),
                    originalBytes,
                    frame
                );
                HttpExchangeRef exchange = model.completeResponse(
                    frame.closesConnection()
                );
                return ProtocolDecodeResult.complete(new ProtocolUnit<>(
                    originalBytes,
                    new ResponseCompleted(
                        exchange,
                        frame.statusCode(),
                        acknowledgement,
                        frame.bodyByteCount()
                    )
                ));
            } catch (ProtocolAdapterException | RuntimeException | Error failure) {
                model.terminal();
                throw failure;
            }
        }

        @Override
        public void endOfInput(ByteBuffer bufferedBytes) throws ProtocolAdapterException {
            try {
                ProtocolStream.super.endOfInput(bufferedBytes);
                model.responseInputEnded();
            } catch (ProtocolAdapterException failure) {
                model.terminal();
                throw failure;
            }
        }
    }

    private Acknowledgement classifyAcknowledgement(
        int statusCode,
        byte[] response,
        HttpMessageFramer.ResponseFrame frame
    ) throws ProtocolAdapterException {
        String body = decodeResponseBody(response, frame);
        if (statusCode >= 400) {
            return Acknowledgement.NEGATIVE;
        }
        if (body.equals(POSITIVE_ACKNOWLEDGEMENT)) {
            return statusCode == 200
                ? Acknowledgement.POSITIVE
                : Acknowledgement.INDETERMINATE;
        }
        return jasminAcceptsAfterStrip(body)
            ? Acknowledgement.INDETERMINATE
            : Acknowledgement.NEGATIVE;
    }

    private static String decodeResponseBody(
        byte[] response,
        HttpMessageFramer.ResponseFrame frame
    ) throws ProtocolAdapterException {
        ByteBuffer body = ByteBuffer.wrap(
            response,
            frame.headerByteCount(),
            frame.bodyByteCount()
        ).slice();
        try {
            return (switch (frame.textEncoding()) {
                case UTF_8 -> StandardCharsets.UTF_8;
                case ISO_8859_1 -> StandardCharsets.ISO_8859_1;
            }).newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(body)
                .toString();
        } catch (CharacterCodingException failure) {
            throw HttpProtocolSession.failure(
                ProtocolFailureKind.MALFORMED_INPUT,
                "HTTP response body is invalid for the characterized charset"
            );
        }
    }

    private static boolean jasminAcceptsAfterStrip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!pythonWhitespace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = value.codePointBefore(end);
            if (!pythonWhitespace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end).equals("ACK/Jasmin");
    }

    private static boolean pythonWhitespace(int codePoint) {
        return codePoint >= 0x0009 && codePoint <= 0x000d
            || codePoint >= 0x001c && codePoint <= 0x0020
            || codePoint == 0x0085
            || codePoint == 0x00a0
            || codePoint == 0x1680
            || codePoint >= 0x2000 && codePoint <= 0x200a
            || codePoint == 0x2028
            || codePoint == 0x2029
            || codePoint == 0x202f
            || codePoint == 0x205f
            || codePoint == 0x3000;
    }

    private static ProtocolAdapterException failure(
        ProtocolFailureKind kind,
        String message
    ) {
        return new ProtocolAdapterException(kind, message);
    }

    private static final class SessionModel {
        private final long sessionOrdinal;
        private long nextRequestOrdinal = 1;
        private HttpExchangeRef pending;
        private boolean terminal;
        private boolean requestInputEnded;
        private boolean responseInputEnded;
        private boolean furtherRequestsForbidden;

        private SessionModel(long sessionOrdinal) {
            this.sessionOrdinal = sessionOrdinal;
        }

        private synchronized boolean hasPendingRequest() throws ProtocolAdapterException {
            requireActive();
            return pending != null;
        }

        private synchronized HttpExchangeRef beginRequest(boolean closesConnection)
            throws ProtocolAdapterException {
            requireActive();
            if (requestInputEnded || responseInputEnded || furtherRequestsForbidden) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "HTTP request cannot begin after input ended or Connection: close"
                );
            }
            if (pending != null) {
                throw failure(
                    ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
                    "HTTP request pipelining is unsupported"
                );
            }
            long ordinal = nextRequestOrdinal;
            if (ordinal < 1) {
                throw new IllegalStateException("HTTP request identity space exhausted");
            }
            nextRequestOrdinal = ordinal == Long.MAX_VALUE ? Long.MIN_VALUE : ordinal + 1;
            pending = new HttpExchangeRef(sessionOrdinal, ordinal);
            furtherRequestsForbidden = closesConnection;
            return pending;
        }

        private synchronized HttpExchangeRef completeResponse(boolean closesConnection)
            throws ProtocolAdapterException {
            requireActive();
            if (responseInputEnded) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "HTTP response input has ended"
                );
            }
            if (pending == null) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "HTTP response has no pending request"
                );
            }
            HttpExchangeRef exchange = pending;
            pending = null;
            furtherRequestsForbidden |= closesConnection;
            return exchange;
        }

        private synchronized void requestInputEnded() throws ProtocolAdapterException {
            requireActive();
            requestInputEnded = true;
        }

        private synchronized void responseInputEnded() throws ProtocolAdapterException {
            requireActive();
            responseInputEnded = true;
            if (pending != null) {
                terminal = true;
                pending = null;
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "HTTP response input ended while a request was pending"
                );
            }
        }

        private synchronized void requireRequestDecodeAllowed(boolean hasBufferedBytes)
            throws ProtocolAdapterException {
            requireActive();
            if (requestInputEnded
                || responseInputEnded
                || (furtherRequestsForbidden && hasBufferedBytes)) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "HTTP request input is closed"
                );
            }
        }

        private synchronized void requireResponseInputOpen()
            throws ProtocolAdapterException {
            requireActive();
            if (responseInputEnded) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "HTTP response input is closed"
                );
            }
        }

        private synchronized void terminal() {
            terminal = true;
            pending = null;
        }

        private void requireActive() throws ProtocolAdapterException {
            if (terminal) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "HTTP session is terminal"
                );
            }
        }
    }

    private static final class EphemeralRequest implements HttpRequestInteraction {
        private HttpMessageFramer.RequestFrame frame;
        private byte[] originalBytes;
        private final Body body = new EphemeralBody(this);

        private EphemeralRequest(
            HttpMessageFramer.RequestFrame frame,
            byte[] originalBytes
        ) {
            this.frame = frame;
            this.originalBytes = originalBytes;
        }

        @Override
        public String method() {
            requireActive();
            return frame.method();
        }

        @Override
        public String path() {
            requireActive();
            return frame.path();
        }

        @Override
        public Optional<String> contentType() {
            requireActive();
            return frame.contentType();
        }

        @Override
        public Body body() {
            requireActive();
            return body;
        }

        private void invalidate() {
            frame = null;
            originalBytes = null;
        }

        private void requireActive() {
            if (frame == null || originalBytes == null) {
                throw new IllegalStateException("HTTP request interaction is no longer available");
            }
        }

        @Override
        public String toString() {
            return frame == null
                ? "HttpRequestInteraction[expired]"
                : "HttpRequestInteraction[method=" + frame.method()
                    + ", pathLength=" + frame.path().length()
                    + ", bodyByteCount=" + frame.bodyByteCount() + "]";
        }
    }

    private static final class EphemeralBody implements HttpRequestInteraction.Body {
        private final EphemeralRequest owner;

        private EphemeralBody(EphemeralRequest owner) {
            this.owner = owner;
        }

        @Override
        public int size() {
            owner.requireActive();
            return owner.frame.bodyByteCount();
        }

        @Override
        public byte byteAt(int index) {
            owner.requireActive();
            Objects.checkIndex(index, owner.frame.bodyByteCount());
            return owner.originalBytes[owner.frame.headerByteCount() + index];
        }

        @Override
        public void copyTo(
            int sourceOffset,
            byte[] destination,
            int destinationOffset,
            int length
        ) {
            owner.requireActive();
            Objects.requireNonNull(destination, "destination must not be null");
            Objects.checkFromIndexSize(sourceOffset, length, owner.frame.bodyByteCount());
            Objects.checkFromIndexSize(destinationOffset, length, destination.length);
            System.arraycopy(
                owner.originalBytes,
                owner.frame.headerByteCount() + sourceOffset,
                destination,
                destinationOffset,
                length
            );
        }
    }
}
