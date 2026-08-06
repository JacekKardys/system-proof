package io.github.jacekkardys.systemproof.smpp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolAdapterException;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolDecodeResult;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolLimits;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolSession;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolStream;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit;

class SmppProtocolFramingTest {
    static final ProtocolLimits LIMITS = new ProtocolLimits(4096, 8192);

    @Test
    void shouldDecodeTheHeaderAndReferenceDeliverAtEveryTcpSplitPoint() throws Exception {
        byte[] deliver = SmppPdus.deliver(77, "reference-message");
        for (int split = 0; split <= deliver.length; split++) {
            Harness harness = boundHarness(adapter());
            ProtocolDecodeResult<SmppEvidence> partial = harness.provider().decode(
                ByteBuffer.wrap(deliver, 0, split)
            );
            if (split < deliver.length) {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.NeedMoreData.class);
                assertThat(complete(harness.provider(), deliver).originalBytes())
                    .containsExactly(deliver);
            } else {
                assertThat(partial).isInstanceOf(ProtocolDecodeResult.Complete.class);
            }
        }
    }

    @Test
    void shouldReturnExactlyOneCompleteOriginalPduFromCoalescedReads() throws Exception {
        Harness harness = boundHarness(adapter());
        byte[] first = SmppPdus.deliver(11, "first");
        byte[] second = SmppPdus.deliver(12, "second");

        ProtocolUnit<SmppEvidence> decoded = complete(
            harness.provider(),
            SmppPdus.concat(first, second)
        );

        assertThat(decoded.originalBytes()).containsExactly(first);
        assertThat(complete(harness.provider(), second).originalBytes())
            .containsExactly(second);
    }

    @Test
    void shouldRejectBelowHeaderExcessiveAndOverflowLikeCommandLengths() {
        assertFailure(
            openHarness(adapter()).consumer(),
            SmppPdus.header(15, SmppPdus.BIND_TRANSCEIVER, 0, 1),
            ProtocolFailureKind.MALFORMED_INPUT
        );
        assertFailure(
            openHarness(adapter(new SmppProtocolLimits(64, 4, 32))).consumer(),
            SmppPdus.header(65, SmppPdus.BIND_TRANSCEIVER, 0, 1),
            ProtocolFailureKind.EXCESSIVE_FRAME_SIZE
        );
        assertFailure(
            openHarness(adapter()).consumer(),
            SmppPdus.header(0xffff_ffffL, SmppPdus.BIND_TRANSCEIVER, 0, 1),
            ProtocolFailureKind.EXCESSIVE_FRAME_SIZE
        );
    }

    @Test
    void shouldRejectEofInsideEveryHeaderRegionAndTheBody() {
        byte[] pdu = SmppPdus.bindRequest(1);
        for (int length = 1; length < pdu.length; length++) {
            ProtocolStream<SmppEvidence> stream = openHarness(adapter()).consumer();
            ByteBuffer truncated = ByteBuffer.wrap(pdu, 0, length);
            assertThatThrownBy(() -> stream.endOfInput(truncated))
                .isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
                    assertThat(failure.kind()).isEqualTo(
                        ProtocolFailureKind.DESYNCHRONIZATION
                    )
                );
        }
    }

    @Test
    void shouldAcceptCleanEofAndRejectEofWithAnOutstandingDeliver() throws Exception {
        Harness clean = openHarness(adapter());
        clean.consumer().endOfInput(ByteBuffer.allocate(0));
        clean.provider().endOfInput(ByteBuffer.allocate(0));

        Harness pending = boundHarness(adapter());
        complete(pending.provider(), SmppPdus.deliver(21, "pending"));
        assertThatThrownBy(() -> pending.provider().endOfInput(ByteBuffer.allocate(0)))
            .isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
                assertThat(failure.kind()).isEqualTo(
                    ProtocolFailureKind.DESYNCHRONIZATION
                )
            );
    }

    @Test
    void shouldEnforceGatewayAndSmppFrameLimits() {
        SmppProtocolAdapter adapter = adapter(new SmppProtocolLimits(4096, 4, 140));
        ProtocolSession<SmppEvidence> session = adapter.openSession(
            new ProtocolLimits(32, 64)
        );
        assertFailure(
            session.openStream(FlowDirection.CONSUMER_TO_PROVIDER),
            SmppPdus.bindRequest(1),
            ProtocolFailureKind.EXCESSIVE_FRAME_SIZE
        );
    }

    @Test
    void shouldAcceptHardLimitBoundariesAndRejectEveryMaximumPlusOne() {
        assertThatCode(() -> new SmppProtocolLimits(
            SmppProtocolLimits.MAXIMUM_PDU_BYTES,
            SmppProtocolLimits.MAXIMUM_OUTSTANDING_DELIVERIES,
            SmppProtocolLimits.MAXIMUM_SHORT_MESSAGE_BYTES
        )).doesNotThrowAnyException();

        assertThatThrownBy(() -> new SmppProtocolLimits(
            SmppProtocolLimits.MAXIMUM_PDU_BYTES + 1,
            1,
            1
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmppProtocolLimits(
            16,
            SmppProtocolLimits.MAXIMUM_OUTSTANDING_DELIVERIES + 1,
            1
        )).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SmppProtocolLimits(
            16,
            1,
            SmppProtocolLimits.MAXIMUM_SHORT_MESSAGE_BYTES + 1
        )).isInstanceOf(IllegalArgumentException.class);
    }

    static Harness openHarness(SmppProtocolAdapter adapter) {
        ProtocolSession<SmppEvidence> session = adapter.openSession(LIMITS);
        return new Harness(
            session.openStream(FlowDirection.CONSUMER_TO_PROVIDER),
            session.openStream(FlowDirection.PROVIDER_TO_CONSUMER)
        );
    }

    static Harness boundHarness(SmppProtocolAdapter adapter) throws Exception {
        Harness harness = openHarness(adapter);
        complete(harness.consumer(), SmppPdus.bindRequest(1));
        complete(harness.provider(), SmppPdus.bindResponse(1, 0));
        return harness;
    }

    @SuppressWarnings("unchecked")
    static ProtocolUnit<SmppEvidence> complete(
        ProtocolStream<SmppEvidence> stream,
        byte[] bytes
    ) throws Exception {
        ProtocolDecodeResult<SmppEvidence> result = stream.decode(ByteBuffer.wrap(bytes));
        assertThat(result).isInstanceOf(ProtocolDecodeResult.Complete.class);
        return ((ProtocolDecodeResult.Complete<SmppEvidence>) result).unit();
    }

    static void assertFailure(
        ProtocolStream<SmppEvidence> stream,
        byte[] bytes,
        ProtocolFailureKind kind
    ) {
        assertThatThrownBy(() -> stream.decode(ByteBuffer.wrap(bytes)))
            .isInstanceOfSatisfying(ProtocolAdapterException.class, failure ->
                assertThat(failure.kind()).isEqualTo(kind)
            );
    }

    private static SmppProtocolAdapter adapter() {
        return new SmppProtocolAdapter();
    }

    private static SmppProtocolAdapter adapter(SmppProtocolLimits limits) {
        return new SmppProtocolAdapter(limits, SmppDeliverCorrelation.none());
    }

    record Harness(
        ProtocolStream<SmppEvidence> consumer,
        ProtocolStream<SmppEvidence> provider
    ) {}
}
