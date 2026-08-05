package io.github.jacekkardys.systemproof.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.ProtocolMessage;
import io.github.jacekkardys.systemproof.postgresql.PostgresqlEvidence.ProtocolMessageKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolDecodeResult;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolStream;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit;

class PostgresqlProtocolFramingTest {
    private static final ProtocolLimits LIMITS = new ProtocolLimits(4096, 8192);

    @Test
    void shouldDecodeStartupPacketAtEveryFragmentationPoint() throws Exception {
        byte[] startup = PostgresqlFrames.startup();
        for (int split = 0; split <= startup.length; split++) {
            ProtocolStream<PostgresqlEvidence> frontend = frontend();
            ProtocolDecodeResult<PostgresqlEvidence> partial = frontend.decode(
                ByteBuffer.wrap(startup, 0, split)
            );
            if (split < startup.length) {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.NeedMoreData.class);
                ProtocolUnit<PostgresqlEvidence> unit = complete(frontend, startup);
                assertThat(unit.originalBytes()).containsExactly(startup);
                assertThat(unit.evidence()).isEqualTo(
                    new ProtocolMessage(ProtocolMessageKind.STARTUP_MESSAGE)
                );
            } else {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.Complete.class);
            }
        }
    }

    @Test
    void shouldDecodeSimpleQueryAtEveryFragmentationPoint() throws Exception {
        byte[] query = PostgresqlFrames.query("BEGIN");
        for (int split = 0; split <= query.length; split++) {
            Harness harness = started();
            ProtocolDecodeResult<PostgresqlEvidence> partial = harness.frontend.decode(
                ByteBuffer.wrap(query, 0, split)
            );
            if (split < query.length) {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.NeedMoreData.class);
                assertThat(complete(harness.frontend, query).originalBytes())
                    .containsExactly(query);
            } else {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.Complete.class);
            }
        }
    }

    @Test
    void shouldDecodeExtendedQueryUnitAtEveryFragmentationPoint() throws Exception {
        byte[] query = PostgresqlFrames.concat(
            PostgresqlFrames.parse("", "SELECT 1"),
            PostgresqlFrames.bind("", ""),
            PostgresqlFrames.execute(""),
            PostgresqlFrames.sync()
        );
        for (int split = 0; split <= query.length; split++) {
            Harness harness = started();
            ProtocolDecodeResult<PostgresqlEvidence> partial = harness.frontend.decode(
                ByteBuffer.wrap(query, 0, split)
            );
            if (split < query.length) {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.NeedMoreData.class);
                assertThat(complete(harness.frontend, query).originalBytes())
                    .containsExactly(query);
            } else {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.Complete.class);
            }
        }
    }

    @Test
    void shouldReturnOnlyTheFirstCoalescedUnit() throws Exception {
        ProtocolStream<PostgresqlEvidence> frontend = frontend();
        byte[] ssl = PostgresqlFrames.sslRequest();
        byte[] coalesced = PostgresqlFrames.concat(ssl, PostgresqlFrames.startup());

        ProtocolUnit<PostgresqlEvidence> first = complete(frontend, coalesced);

        assertThat(first.originalBytes()).containsExactly(ssl);
        assertThat(first.evidence()).isEqualTo(
            new ProtocolMessage(ProtocolMessageKind.SSL_REQUEST)
        );
    }

    @Test
    void shouldRejectInvalidNegativeAndExcessiveLengthsBeforeAllocation() throws Exception {
        ProtocolStream<PostgresqlEvidence> frontend = frontend();
        byte[] negativeStartup = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
            .putInt(-1).putInt(196608).array();
        assertFailure(frontend, negativeStartup, ProtocolFailureKind.MALFORMED_INPUT);

        Harness harness = started();
        byte[] negativeMessage = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put((byte) 'Q').putInt(-1).array();
        assertFailure(harness.frontend, negativeMessage, ProtocolFailureKind.MALFORMED_INPUT);

        byte[] excessive = ByteBuffer.allocate(5).order(ByteOrder.BIG_ENDIAN)
            .put((byte) 'Q').putInt(5000).array();
        assertFailure(harness.frontend, excessive, ProtocolFailureKind.EXCESSIVE_FRAME_SIZE);
    }

    @Test
    void shouldRejectEofInsideAUnit() throws Exception {
        Harness harness = started();
        byte[] partial = PostgresqlFrames.query("BEGIN");

        assertThatThrownBy(() -> harness.frontend.endOfInput(
            ByteBuffer.wrap(partial, 0, partial.length - 1)
        )).isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
            assertThat(failure.kind()).isEqualTo(ProtocolFailureKind.DESYNCHRONIZATION)
        );
    }

    @Test
    void shouldForwardSslNAndFailClosedBeforeTlsPayloadAfterSslS() throws Exception {
        ProtocolSession<PostgresqlEvidence> plainSession = session();
        ProtocolUnit<PostgresqlEvidence> rejected = complete(
            plainSession.openStream(FlowDirection.PROVIDER_TO_CONSUMER),
            new byte[] {'N'}
        );
        assertThat(rejected.originalBytes()).containsExactly((byte) 'N');
        assertThat(rejected.evidence()).isEqualTo(
            new ProtocolMessage(ProtocolMessageKind.SSL_REJECTED)
        );

        ProtocolStream<PostgresqlEvidence> encrypted = session().openStream(
            FlowDirection.PROVIDER_TO_CONSUMER
        );
        assertFailure(
            encrypted,
            new byte[] {'S'},
            ProtocolFailureKind.UNSUPPORTED_ENCRYPTION
        );
    }

    @Test
    void shouldForwardAuthenticationPayloadExactlyWithoutRenderingIt() throws Exception {
        Harness harness = started();
        byte[] password = PostgresqlFrames.password("not-for-diagnostics");

        ProtocolUnit<PostgresqlEvidence> unit = complete(harness.frontend, password);

        assertThat(unit.originalBytes()).containsExactly(password);
        assertThat(unit.evidence()).isEqualTo(
            new ProtocolMessage(ProtocolMessageKind.AUTHENTICATION_PAYLOAD)
        );
        assertThat(unit.toString()).doesNotContain("not-for-diagnostics");
    }

    private static Harness started() throws Exception {
        ProtocolSession<PostgresqlEvidence> session = session();
        ProtocolStream<PostgresqlEvidence> frontend = session.openStream(
            FlowDirection.CONSUMER_TO_PROVIDER
        );
        ProtocolStream<PostgresqlEvidence> backend = session.openStream(
            FlowDirection.PROVIDER_TO_CONSUMER
        );
        complete(frontend, PostgresqlFrames.sslRequest());
        complete(backend, new byte[] {'N'});
        complete(frontend, PostgresqlFrames.startup());
        complete(backend, PostgresqlFrames.ready('I'));
        return new Harness(frontend, backend);
    }

    private static ProtocolStream<PostgresqlEvidence> frontend() {
        return session().openStream(FlowDirection.CONSUMER_TO_PROVIDER);
    }

    private static ProtocolSession<PostgresqlEvidence> session() {
        return new PostgresqlProtocolAdapter().openSession(LIMITS);
    }

    @SuppressWarnings("unchecked")
    private static ProtocolUnit<PostgresqlEvidence> complete(
        ProtocolStream<PostgresqlEvidence> stream,
        byte[] bytes
    ) throws Exception {
        ProtocolDecodeResult<PostgresqlEvidence> decoded = stream.decode(ByteBuffer.wrap(bytes));
        assertThat(decoded).isInstanceOf(ProtocolDecodeResult.Complete.class);
        return ((ProtocolDecodeResult.Complete<PostgresqlEvidence>) decoded).unit();
    }

    private static void assertFailure(
        ProtocolStream<PostgresqlEvidence> stream,
        byte[] bytes,
        ProtocolFailureKind kind
    ) {
        assertThatThrownBy(() -> stream.decode(ByteBuffer.wrap(bytes)))
            .isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
                assertThat(failure.kind()).isEqualTo(kind)
            );
    }

    private record Harness(
        ProtocolStream<PostgresqlEvidence> frontend,
        ProtocolStream<PostgresqlEvidence> backend
    ) {}
}
