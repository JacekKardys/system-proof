package io.github.jacekkardys.systemproof.http;

import static io.github.jacekkardys.systemproof.http.HttpProtocolFramingTest.complete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.http.HttpEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.http.HttpEvidence.RequestCompleted;
import io.github.jacekkardys.systemproof.http.HttpEvidence.ResponseCompleted;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolStream;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit;

class HttpSessionSemanticsTest {
    private static final ProtocolLimits LIMITS = new ProtocolLimits(4096, 8192);

    @Test
    void shouldAssociateSequentialKeepAliveResponsesWithTheirOwnRequests() throws Exception {
        Harness harness = harness(new HttpProtocolAdapter());

        RequestCompleted firstRequest = request(complete(
            harness.requests(),
            HttpMessages.request("id=one")
        ));
        ResponseCompleted firstResponse = response(complete(
            harness.responses(),
            HttpMessages.response(200, "ACK/Jasmin")
        ));
        RequestCompleted secondRequest = request(complete(
            harness.requests(),
            HttpMessages.request("id=two")
        ));
        ResponseCompleted secondResponse = response(complete(
            harness.responses(),
            HttpMessages.response(500, "failure")
        ));

        assertThat(firstResponse.exchange()).isEqualTo(firstRequest.exchange());
        assertThat(secondResponse.exchange()).isEqualTo(secondRequest.exchange());
        assertThat(secondRequest.exchange().requestOrdinal()).isEqualTo(2);
        assertThat(secondRequest.exchange()).isNotEqualTo(firstRequest.exchange());
    }

    @Test
    void shouldUseNewPhysicalIdentityAfterReconnect() throws Exception {
        HttpProtocolAdapter adapter = new HttpProtocolAdapter();

        RequestCompleted first = request(complete(
            harness(adapter).requests(),
            HttpMessages.request("id=one")
        ));
        RequestCompleted reconnected = request(complete(
            harness(adapter).requests(),
            HttpMessages.request("id=two")
        ));

        assertThat(first.exchange().requestOrdinal()).isEqualTo(1);
        assertThat(reconnected.exchange().requestOrdinal()).isEqualTo(1);
        assertThat(reconnected.exchange().sessionOrdinal())
            .isNotEqualTo(first.exchange().sessionOrdinal());
    }

    @Test
    void shouldKeepEqualLocalOrdinalsFromSeparateAdaptersNonGlobal() throws Exception {
        RequestCompleted first = request(complete(
            harness(new HttpProtocolAdapter()).requests(),
            HttpMessages.request("id=one")
        ));
        RequestCompleted second = request(complete(
            harness(new HttpProtocolAdapter()).requests(),
            HttpMessages.request("id=two")
        ));

        assertThat(first.exchange()).isEqualTo(second.exchange());
        assertThat(first.exchange().toString()).contains("session=1", "request=1");
    }

    @Test
    void shouldFailClosedForPipeliningAndAResponseWithoutPendingRequest() throws Exception {
        Harness pipelined = harness(new HttpProtocolAdapter());
        complete(pipelined.requests(), HttpMessages.request("id=one"));
        assertFailure(
            pipelined.requests(),
            HttpMessages.request("id=two"),
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION
        );

        Harness unsolicited = harness(new HttpProtocolAdapter());
        assertFailure(
            unsolicited.responses(),
            HttpMessages.response(200, "ACK/Jasmin"),
            ProtocolFailureKind.DESYNCHRONIZATION
        );
    }

    @Test
    void shouldClassifyTheCharacterizedJasminAcknowledgementAsTriState() throws Exception {
        assertClassification(200, "ACK/Jasmin", Acknowledgement.POSITIVE);
        for (ResponseCase indeterminate : List.of(
            new ResponseCase(201, "ACK/Jasmin"),
            new ResponseCase(299, "ACK/Jasmin"),
            new ResponseCase(302, " ACK/Jasmin\t") ,
            new ResponseCase(200, "\r\nACK/Jasmin ")
        )) {
            assertClassification(
                indeterminate.status(),
                indeterminate.body(),
                Acknowledgement.INDETERMINATE
            );
        }
        for (ResponseCase negative : List.of(
            new ResponseCase(204, ""),
            new ResponseCase(500, "ACK/Jasmin"),
            new ResponseCase(200, ""),
            new ResponseCase(200, "ack/Jasmin"),
            new ResponseCase(200, "ACK /Jasmin"),
            new ResponseCase(200, "xACK/Jasmin")
        )) {
            assertClassification(
                negative.status(),
                negative.body(),
                Acknowledgement.NEGATIVE
            );
        }
    }

    @Test
    void shouldRejectMalformedAmbiguousEncryptedOrUnsupportedTraffic() throws Exception {
        assertRequestFailure(
            HttpMessages.bytes("\u0016\u0003\u0001\u0000\u0020"),
            ProtocolFailureKind.UNSUPPORTED_ENCRYPTION
        );
        assertRequestFailure(
            HttpMessages.bytes("CONNECT upstream:443 HTTP/1.1\r\nHost: upstream\r\n\r\n"),
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION
        );
        assertRequestFailure(
            HttpMessages.bytes(
                "GET / HTTP/1.1\r\nHost: test\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n"
            ),
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION
        );
        assertRequestFailure(
            HttpMessages.request("POST", "/v1/ingestion/sms?token=secret", "id=one"),
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION
        );
        assertRequestFailure(
            HttpMessages.bytes(
                "POST / HTTP/1.1\r\nHost: test\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
            ),
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION
        );
        assertRequestFailure(
            HttpMessages.bytes(
                "POST / HTTP/1.1\r\nHost: test\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nx"
            ),
            ProtocolFailureKind.AMBIGUOUS_FRAMING
        );
        assertRequestFailure(
            HttpMessages.bytes(
                "POST / HTTP/1.1\r\nHost: test\r\nContent-Length: -1\r\n\r\n"
            ),
            ProtocolFailureKind.MALFORMED_INPUT
        );
        assertRequestFailure(
            HttpMessages.bytes("GET / HTTP/1.1\nHost: test\n\n"),
            ProtocolFailureKind.MALFORMED_INPUT
        );

        Harness invalidStatus = harness(new HttpProtocolAdapter());
        complete(invalidStatus.requests(), HttpMessages.request("id=one"));
        assertFailure(
            invalidStatus.responses(),
            HttpMessages.response(600, "ACK/Jasmin"),
            ProtocolFailureKind.MALFORMED_INPUT
        );

        Harness closeDelimited = harness(new HttpProtocolAdapter());
        complete(closeDelimited.requests(), HttpMessages.request("id=one"));
        assertFailure(
            closeDelimited.responses(),
            HttpMessages.bytes("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\nACK/Jasmin"),
            ProtocolFailureKind.AMBIGUOUS_FRAMING
        );
    }

    @Test
    void shouldRejectTruncatedResponseWithoutEmittingPositiveEvidence() throws Exception {
        Harness harness = harness(new HttpProtocolAdapter());
        complete(harness.requests(), HttpMessages.request("id=one"));
        byte[] response = HttpMessages.response(200, "ACK/Jasmin");
        ByteBuffer truncated = ByteBuffer.wrap(response, 0, response.length - 1);

        assertThat(harness.responses().decode(truncated))
            .isInstanceOf(io.github.jacekkardys.systemproof.testcontainers.gateway
                .ProtocolDecodeResult.NeedMoreData.class);
        assertThatThrownBy(() -> harness.responses().endOfInput(truncated))
            .isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
                assertThat(failure.kind()).isEqualTo(ProtocolFailureKind.DESYNCHRONIZATION)
            );
    }

    @Test
    void shouldPreventRequestsAfterEitherInputHasEnded() throws Exception {
        Harness responseEndedFirst = harness(new HttpProtocolAdapter());
        responseEndedFirst.responses().endOfInput(ByteBuffer.allocate(0));
        assertFailure(
            responseEndedFirst.requests(),
            HttpMessages.request("id=one"),
            ProtocolFailureKind.DESYNCHRONIZATION
        );

        Harness completed = harness(new HttpProtocolAdapter());
        complete(completed.requests(), HttpMessages.request("id=one"));
        complete(completed.responses(), HttpMessages.response(200, "ACK/Jasmin"));
        completed.responses().endOfInput(ByteBuffer.allocate(0));
        assertFailure(
            completed.requests(),
            HttpMessages.request("id=two"),
            ProtocolFailureKind.DESYNCHRONIZATION
        );
    }

    @Test
    void shouldAllowExactlyThePendingResponseAfterRequestInputEnds() throws Exception {
        Harness harness = harness(new HttpProtocolAdapter());
        complete(harness.requests(), HttpMessages.request("id=one"));
        harness.requests().endOfInput(ByteBuffer.allocate(0));

        complete(harness.responses(), HttpMessages.response(200, "ACK/Jasmin"));
        assertFailure(
            harness.requests(),
            HttpMessages.request("id=two"),
            ProtocolFailureKind.DESYNCHRONIZATION
        );
        assertFailure(
            harness.responses(),
            HttpMessages.response(200, "ACK/Jasmin"),
            ProtocolFailureKind.DESYNCHRONIZATION
        );
    }

    private static void assertClassification(
        int status,
        String body,
        Acknowledgement expected
    ) throws Exception {
        Harness harness = harness(new HttpProtocolAdapter());
        complete(harness.requests(), HttpMessages.request("id=one"));
        ProtocolUnit<HttpEvidence> unit = complete(
            harness.responses(),
            HttpMessages.response(status, body)
        );

        assertThat(response(unit).acknowledgement()).isEqualTo(expected);
        if (!body.isEmpty()) {
            assertThat(unit.toString()).doesNotContain(body);
        }
    }

    private static void assertRequestFailure(byte[] request, ProtocolFailureKind kind) {
        assertFailure(harness(new HttpProtocolAdapter()).requests(), request, kind);
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

    private static Harness harness(HttpProtocolAdapter adapter) {
        ProtocolSession<HttpEvidence> session = adapter.openSession(LIMITS);
        return new Harness(
            session.openStream(FlowDirection.CONSUMER_TO_PROVIDER),
            session.openStream(FlowDirection.PROVIDER_TO_CONSUMER)
        );
    }

    private static RequestCompleted request(ProtocolUnit<HttpEvidence> unit) {
        return (RequestCompleted) unit.evidence();
    }

    private static ResponseCompleted response(ProtocolUnit<HttpEvidence> unit) {
        return (ResponseCompleted) unit.evidence();
    }

    private record Harness(
        ProtocolStream<HttpEvidence> requests,
        ProtocolStream<HttpEvidence> responses
    ) {}

    private record ResponseCase(int status, String body) {}
}
