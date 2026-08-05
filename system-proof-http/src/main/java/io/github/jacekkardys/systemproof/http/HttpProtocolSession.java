package io.github.jacekkardys.systemproof.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import io.github.jacekkardys.systemproof.environment.CorrelationContribution;
import io.github.jacekkardys.systemproof.http.HttpEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestCompleted;
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
    private static final byte[] POSITIVE_ACKNOWLEDGEMENT =
        "ACK/Jasmin".getBytes(StandardCharsets.US_ASCII);

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
            if (!bufferedBytes.hasRemaining()) {
                return ProtocolDecodeResult.needMoreData();
            }
            try {
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
                HttpExchangeRef exchange = model.beginRequest();
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
                        frame.method(),
                        frame.path(),
                        frame.contentType(),
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
            if (!bufferedBytes.hasRemaining()) {
                return ProtocolDecodeResult.needMoreData();
            }
            try {
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
                HttpExchangeRef exchange = model.completeResponse();
                Acknowledgement acknowledgement = frame.statusCode() == 200
                    && framer.bodyEquals(
                        originalBytes,
                        frame,
                        POSITIVE_ACKNOWLEDGEMENT
                    )
                    ? Acknowledgement.POSITIVE
                    : Acknowledgement.NEGATIVE;
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

        private SessionModel(long sessionOrdinal) {
            this.sessionOrdinal = sessionOrdinal;
        }

        private synchronized boolean hasPendingRequest() throws ProtocolAdapterException {
            requireActive();
            return pending != null;
        }

        private synchronized HttpExchangeRef beginRequest()
            throws ProtocolAdapterException {
            requireActive();
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
            return pending;
        }

        private synchronized HttpExchangeRef completeResponse()
            throws ProtocolAdapterException {
            requireActive();
            if (pending == null) {
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "HTTP response has no pending request"
                );
            }
            HttpExchangeRef exchange = pending;
            pending = null;
            return exchange;
        }

        private synchronized void requestInputEnded() throws ProtocolAdapterException {
            requireActive();
        }

        private synchronized void responseInputEnded() throws ProtocolAdapterException {
            requireActive();
            if (pending != null) {
                terminal = true;
                pending = null;
                throw failure(
                    ProtocolFailureKind.DESYNCHRONIZATION,
                    "HTTP response input ended while a request was pending"
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
        public int bodySize() {
            requireActive();
            return frame.bodyByteCount();
        }

        @Override
        public ByteBuffer bodyBytes() {
            requireActive();
            return ByteBuffer.wrap(
                originalBytes,
                frame.headerByteCount(),
                frame.bodyByteCount()
            ).slice().asReadOnlyBuffer();
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
}
