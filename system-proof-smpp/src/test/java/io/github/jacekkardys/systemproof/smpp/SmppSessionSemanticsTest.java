package io.github.jacekkardys.systemproof.smpp;

import static io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.assertFailure;
import static io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.boundHarness;
import static io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.complete;
import static io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.openHarness;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.Acknowledgement;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindOutcome;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.BindResponded;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppEvidence.DeliverSmResponseCompleted;
import io.github.jacekkardys.systemproof.smpp.SmppProtocolFramingTest.Harness;
import io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolFailureKind;

class SmppSessionSemanticsTest {

    @Test
    void shouldModelSuccessfulAndRejectedBindOutcomes() throws Exception {
        Harness accepted = openHarness(new SmppProtocolAdapter());
        complete(accepted.consumer(), SmppPdus.bindRequest(7));
        BindResponded success = (BindResponded) complete(
            accepted.provider(),
            SmppPdus.bindResponse(7, 0)
        ).evidence();
        assertThat(success.outcome()).isEqualTo(BindOutcome.ACCEPTED);

        Harness rejected = openHarness(new SmppProtocolAdapter());
        complete(rejected.consumer(), SmppPdus.bindRequest(8));
        BindResponded failure = (BindResponded) complete(
            rejected.provider(),
            SmppPdus.bindResponse(8, 0x0000000eL)
        ).evidence();
        assertThat(failure.outcome()).isEqualTo(BindOutcome.REJECTED);
        assertThat(failure.commandStatus()).isEqualTo(0x0000000eL);

        assertFailure(
            openHarness(new SmppProtocolAdapter()).provider(),
            SmppPdus.deliver(10, "not-bound"),
            ProtocolFailureKind.DESYNCHRONIZATION
        );
    }

    @Test
    void shouldTrackSeveralOutstandingDeliveriesAndResponsesInAnyOrder() throws Exception {
        Harness harness = boundHarness(new SmppProtocolAdapter());
        DeliverSmCompleted first = deliver(complete(
            harness.provider(),
            SmppPdus.deliver(101, "first")
        ));
        DeliverSmCompleted second = deliver(complete(
            harness.provider(),
            SmppPdus.deliver(202, "second")
        ));

        DeliverSmResponseCompleted secondResponse = response(complete(
            harness.consumer(),
            SmppPdus.deliverResponse(202, 0)
        ));
        DeliverSmResponseCompleted firstResponse = response(complete(
            harness.consumer(),
            SmppPdus.deliverResponse(101, 0x00000008L)
        ));

        assertThat(secondResponse.exchange()).isEqualTo(second.exchange());
        assertThat(firstResponse.exchange()).isEqualTo(first.exchange());
        assertThat(secondResponse.acknowledgement()).isEqualTo(Acknowledgement.POSITIVE);
        assertThat(firstResponse.acknowledgement()).isEqualTo(Acknowledgement.NEGATIVE);
    }

    @Test
    void shouldRejectDuplicateOutstandingSequenceAndAllowReuseAfterCompletion()
        throws Exception {
        Harness duplicate = boundHarness(new SmppProtocolAdapter());
        complete(duplicate.provider(), SmppPdus.deliver(77, "first"));
        assertFailure(
            duplicate.provider(),
            SmppPdus.deliver(77, "duplicate"),
            ProtocolFailureKind.DESYNCHRONIZATION
        );

        Harness reused = boundHarness(new SmppProtocolAdapter());
        DeliverSmCompleted first = deliver(complete(
            reused.provider(),
            SmppPdus.deliver(77, "first")
        ));
        complete(reused.consumer(), SmppPdus.deliverResponse(77, 0));
        DeliverSmCompleted second = deliver(complete(
            reused.provider(),
            SmppPdus.deliver(77, "second")
        ));

        assertThat(second.exchange().wireSequenceNumber()).isEqualTo(77);
        assertThat(second.exchange().exchangeOrdinal())
            .isEqualTo(first.exchange().exchangeOrdinal() + 1);
        assertThat(second.exchange()).isNotEqualTo(first.exchange());
    }

    @Test
    void shouldResetAllStateAndIdentityAcrossPhysicalReconnects() throws Exception {
        SmppProtocolAdapter adapter = new SmppProtocolAdapter();
        DeliverSmCompleted first = deliver(complete(
            boundHarness(adapter).provider(),
            SmppPdus.deliver(55, "first-session")
        ));
        DeliverSmCompleted reconnected = deliver(complete(
            boundHarness(adapter).provider(),
            SmppPdus.deliver(55, "second-session")
        ));

        assertThat(reconnected.exchange().wireSequenceNumber()).isEqualTo(55);
        assertThat(reconnected.exchange().exchangeOrdinal()).isEqualTo(1);
        assertThat(reconnected.exchange().adapterSessionOrdinal())
            .isNotEqualTo(first.exchange().adapterSessionOrdinal());
    }

    @Test
    void shouldRejectUnmatchedWrongDirectionWrongResponseAndGenericNack()
        throws Exception {
        Harness unmatched = boundHarness(new SmppProtocolAdapter());
        assertFailure(
            unmatched.consumer(),
            SmppPdus.deliverResponse(99, 0),
            ProtocolFailureKind.DESYNCHRONIZATION
        );

        Harness wrongDirection = boundHarness(new SmppProtocolAdapter());
        assertFailure(
            wrongDirection.consumer(),
            SmppPdus.deliver(12, "wrong-direction"),
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION
        );

        Harness wrongResponse = boundHarness(new SmppProtocolAdapter());
        complete(wrongResponse.provider(), SmppPdus.deliver(12, "pending"));
        assertFailure(
            wrongResponse.provider(),
            SmppPdus.enquireLinkResponse(12),
            ProtocolFailureKind.DESYNCHRONIZATION
        );

        Harness nack = boundHarness(new SmppProtocolAdapter());
        assertFailure(
            nack.consumer(),
            SmppPdus.pdu(SmppPdus.GENERIC_NACK, 3, 1, new byte[0]),
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION
        );
    }

    @Test
    void shouldFailClosedForAMismatchedHighBitResponse() throws Exception {
        Harness harness = boundHarness(new SmppProtocolAdapter());
        complete(harness.provider(), SmppPdus.deliver(0x8000_0000L, "pending"));

        assertFailure(
            harness.consumer(),
            SmppPdus.deliverResponse(0x8000_0001L, 0),
            ProtocolFailureKind.DESYNCHRONIZATION
        );
    }

    @Test
    void shouldRejectZeroSequenceForRequestsAndResponses() throws Exception {
        assertFailure(
            openHarness(new SmppProtocolAdapter()).consumer(),
            SmppPdus.bindRequest(0),
            ProtocolFailureKind.MALFORMED_INPUT
        );

        Harness zeroDeliver = boundHarness(new SmppProtocolAdapter());
        assertFailure(
            zeroDeliver.provider(),
            SmppPdus.deliver(0, "zero-request"),
            ProtocolFailureKind.MALFORMED_INPUT
        );

        Harness zeroResponse = boundHarness(new SmppProtocolAdapter());
        complete(zeroResponse.provider(), SmppPdus.deliver(1, "pending"));
        assertFailure(
            zeroResponse.consumer(),
            SmppPdus.deliverResponse(0, 0),
            ProtocolFailureKind.MALFORMED_INPUT
        );
    }

    @Test
    void shouldClassifyZeroAsPositiveAndEveryDecodedNonZeroStatusAsNegative()
        throws Exception {
        for (long status : List.of(0L, 1L, 8L, 0x00000400L, 0xffff_ffffL)) {
            Harness harness = boundHarness(new SmppProtocolAdapter());
            complete(harness.provider(), SmppPdus.deliver(70, "status"));
            DeliverSmResponseCompleted response = response(complete(
                harness.consumer(),
                SmppPdus.deliverResponse(70, status)
            ));
            assertThat(response.acknowledgement()).isEqualTo(
                status == 0 ? Acknowledgement.POSITIVE : Acknowledgement.NEGATIVE
            );
        }
    }

    @Test
    void shouldModelEnquireLinkAndGracefulUnbindStateTransitions() throws Exception {
        Harness harness = boundHarness(new SmppProtocolAdapter());
        complete(harness.consumer(), SmppPdus.enquireLink(2));
        complete(harness.provider(), SmppPdus.enquireLinkResponse(2));
        complete(harness.consumer(), SmppPdus.unbind(3));
        complete(harness.provider(), SmppPdus.unbindResponse(3));

        assertFailure(
            harness.provider(),
            SmppPdus.deliver(80, "after-unbind"),
            ProtocolFailureKind.DESYNCHRONIZATION
        );
    }

    @Test
    void shouldEnforceTheOutstandingExchangeLimit() throws Exception {
        SmppProtocolAdapter adapter = new SmppProtocolAdapter(
            new SmppProtocolLimits(SmppProtocolLimits.MAXIMUM_PDU_BYTES, 1, 140),
            SmppDeliverCorrelation.none()
        );
        Harness harness = boundHarness(adapter);
        complete(harness.provider(), SmppPdus.deliver(1_001, "first"));

        assertFailure(
            harness.provider(),
            SmppPdus.deliver(1_002, "over-limit"),
            ProtocolFailureKind.EXCESSIVE_BUFFERED_BYTES
        );
    }

    private static DeliverSmCompleted deliver(
        io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit<SmppEvidence> unit
    ) {
        return (DeliverSmCompleted) unit.evidence();
    }

    private static DeliverSmResponseCompleted response(
        io.github.jacekkardys.systemproof.testcontainers.gateway.ProtocolUnit<SmppEvidence> unit
    ) {
        return (DeliverSmResponseCompleted) unit.evidence();
    }
}
