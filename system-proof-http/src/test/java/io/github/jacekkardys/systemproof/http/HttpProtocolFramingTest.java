package io.github.jacekkardys.systemproof.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestCompleted;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolDecodeResult;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolStream;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit;

class HttpProtocolFramingTest {
    private static final ProtocolLimits LIMITS = new ProtocolLimits(4096, 8192);

    @Test
    void shouldDecodeTheRequestAtEveryTcpSplitPoint() throws Exception {
        byte[] request = HttpMessages.request("id=one&from=source&to=target&content=hello");
        for (int split = 0; split <= request.length; split++) {
            ProtocolStream<HttpEvidence> stream = requestStream(adapter());
            ProtocolDecodeResult<HttpEvidence> partial = stream.decode(
                ByteBuffer.wrap(request, 0, split)
            );
            if (split < request.length) {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.NeedMoreData.class);
                assertThat(complete(stream, request).originalBytes()).containsExactly(request);
            } else {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.Complete.class);
            }
        }
    }

    @Test
    void shouldDecodeTheResponseAtEveryTcpSplitPoint() throws Exception {
        byte[] response = HttpMessages.response(200, "ACK/Jasmin");
        for (int split = 0; split <= response.length; split++) {
            Harness harness = harness(adapter());
            complete(harness.requests(), HttpMessages.request("id=one"));
            ProtocolDecodeResult<HttpEvidence> partial = harness.responses().decode(
                ByteBuffer.wrap(response, 0, split)
            );
            if (split < response.length) {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.NeedMoreData.class);
                assertThat(complete(harness.responses(), response).originalBytes())
                    .containsExactly(response);
            } else {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.Complete.class);
            }
        }
    }

    @Test
    void shouldReturnOnlyTheFirstUnitFromACoalescedPipelinedReadThenFailClosed()
        throws Exception {
        ProtocolStream<HttpEvidence> requests = requestStream(adapter());
        byte[] first = HttpMessages.request("id=one");
        byte[] second = HttpMessages.request("id=two");

        ProtocolUnit<HttpEvidence> decoded = complete(
            requests,
            HttpMessages.concat(first, second)
        );

        assertThat(decoded.originalBytes()).containsExactly(first);
        assertFailure(requests, second, ProtocolFailureKind.UNSUPPORTED_NEGOTIATION);
    }

    @Test
    void shouldTreatHeaderNamesAndMediaTypesCaseInsensitively() throws Exception {
        byte[] request = HttpMessages.bytes(
            "POST /v1/ingestion/sms HTTP/1.1\r\n"
                + "hOsT: ingestion:8080\r\n"
                + "cOnTeNt-TyPe: APPLICATION/X-WWW-FORM-URLENCODED\r\n"
                + "cOnTeNt-LeNgTh: 2\r\n\r\nid"
        );

        RequestCompleted evidence = (RequestCompleted) complete(
            requestStream(adapter()),
            request
        ).evidence();

        assertThat(evidence.contentType())
            .contains("application/x-www-form-urlencoded");
    }

    @Test
    void shouldAcceptCleanEofAndRejectEofInsideHeadersOrBody() throws Exception {
        ProtocolStream<HttpEvidence> clean = requestStream(adapter());
        clean.endOfInput(ByteBuffer.allocate(0));

        Harness completeExchange = harness(adapter());
        complete(completeExchange.requests(), HttpMessages.request("id=one"));
        complete(
            completeExchange.responses(),
            HttpMessages.response(200, "ACK/Jasmin")
        );
        completeExchange.responses().endOfInput(ByteBuffer.allocate(0));

        ProtocolStream<HttpEvidence> headers = requestStream(adapter());
        assertEofFailure(headers, HttpMessages.bytes("POST / HTTP/1.1\r\nHost: incomplete"));

        ProtocolStream<HttpEvidence> body = requestStream(adapter());
        assertEofFailure(body, HttpMessages.bytes(
            "POST / HTTP/1.1\r\nHost: test\r\nContent-Length: 5\r\n\r\n1234"
        ));
    }

    @Test
    void shouldEnforceStartLineHeaderCountHeaderBodyAndFrameLimits() {
        assertFailure(
            requestStream(adapter(new HttpProtocolLimits(16, 128, 10, 64))),
            HttpMessages.request("id=one"),
            ProtocolFailureKind.EXCESSIVE_FRAME_SIZE
        );
        assertFailure(
            requestStream(adapter(new HttpProtocolLimits(64, 80, 10, 64))),
            HttpMessages.request("id=one"),
            ProtocolFailureKind.EXCESSIVE_FRAME_SIZE
        );
        assertFailure(
            requestStream(adapter(new HttpProtocolLimits(64, 256, 1, 64))),
            HttpMessages.request("id=one"),
            ProtocolFailureKind.EXCESSIVE_FRAME_SIZE
        );
        assertFailure(
            requestStream(adapter(new HttpProtocolLimits(64, 256, 10, 2))),
            HttpMessages.request("body"),
            ProtocolFailureKind.EXCESSIVE_FRAME_SIZE
        );

        HttpProtocolAdapter adapter = adapter();
        ProtocolSession<HttpEvidence> session = adapter.openSession(new ProtocolLimits(64, 128));
        assertFailure(
            session.openStream(FlowDirection.CONSUMER_TO_PROVIDER),
            HttpMessages.request("id=one"),
            ProtocolFailureKind.EXCESSIVE_FRAME_SIZE
        );
    }

    @Test
    void shouldAcceptHardLimitBoundariesAndRejectEveryMaximumPlusOne() {
        assertThatCode(() -> new HttpProtocolLimits(
            HttpProtocolLimits.MAXIMUM_START_LINE_BYTES,
            HttpProtocolLimits.MAXIMUM_HEADER_SECTION_BYTES,
            HttpProtocolLimits.MAXIMUM_HEADER_COUNT,
            HttpProtocolLimits.MAXIMUM_BODY_BYTES
        )).doesNotThrowAnyException();

        assertThatThrownBy(() -> new HttpProtocolLimits(
            HttpProtocolLimits.MAXIMUM_START_LINE_BYTES + 1,
            HttpProtocolLimits.MAXIMUM_HEADER_SECTION_BYTES,
            1,
            0
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpProtocolLimits(
            1,
            HttpProtocolLimits.MAXIMUM_HEADER_SECTION_BYTES + 1,
            1,
            0
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpProtocolLimits(
            1,
            5,
            HttpProtocolLimits.MAXIMUM_HEADER_COUNT + 1,
            0
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpProtocolLimits(
            1,
            5,
            1,
            HttpProtocolLimits.MAXIMUM_BODY_BYTES + 1
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpProtocolLimits(
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE,
            Integer.MAX_VALUE
        )).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertEofFailure(ProtocolStream<HttpEvidence> stream, byte[] bytes) {
        assertThatThrownBy(() -> stream.endOfInput(ByteBuffer.wrap(bytes)))
            .isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
                assertThat(failure.kind()).isEqualTo(ProtocolFailureKind.DESYNCHRONIZATION)
            );
    }

    private static HttpProtocolAdapter adapter() {
        return new HttpProtocolAdapter();
    }

    private static HttpProtocolAdapter adapter(HttpProtocolLimits limits) {
        return new HttpProtocolAdapter(limits, HttpRequestCorrelation.none());
    }

    private static ProtocolStream<HttpEvidence> requestStream(HttpProtocolAdapter adapter) {
        return adapter.openSession(LIMITS).openStream(FlowDirection.CONSUMER_TO_PROVIDER);
    }

    private static Harness harness(HttpProtocolAdapter adapter) {
        ProtocolSession<HttpEvidence> session = adapter.openSession(LIMITS);
        return new Harness(
            session.openStream(FlowDirection.CONSUMER_TO_PROVIDER),
            session.openStream(FlowDirection.PROVIDER_TO_CONSUMER)
        );
    }

    @SuppressWarnings("unchecked")
    static ProtocolUnit<HttpEvidence> complete(
        ProtocolStream<HttpEvidence> stream,
        byte[] bytes
    ) throws Exception {
        ProtocolDecodeResult<HttpEvidence> result = stream.decode(ByteBuffer.wrap(bytes));
        assertThat(result).isInstanceOf(ProtocolDecodeResult.Complete.class);
        return ((ProtocolDecodeResult.Complete<HttpEvidence>) result).unit();
    }

    private static void assertFailure(
        ProtocolStream<HttpEvidence> stream,
        byte[] bytes,
        ProtocolFailureKind kind
    ) {
        assertThatThrownBy(() -> stream.decode(ByteBuffer.wrap(bytes)))
            .isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
                assertThat(failure.kind()).isEqualTo(kind)
            );
    }

    record Harness(
        ProtocolStream<HttpEvidence> requests,
        ProtocolStream<HttpEvidence> responses
    ) {}
}
