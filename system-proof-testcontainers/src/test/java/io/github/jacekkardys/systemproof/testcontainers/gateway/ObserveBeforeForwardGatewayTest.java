package io.github.jacekkardys.systemproof.testcontainers.gateway;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.observation.ConnectionObservations;
import io.github.jacekkardys.systemproof.proof.CorrelationCardinality;
import io.github.jacekkardys.systemproof.proof.CorrelationContribution;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.observation.ForwardingDecision;
import io.github.jacekkardys.systemproof.observation.InteractionDecisionCoordinator;
import io.github.jacekkardys.systemproof.observation.InteractionSession;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.observation.FlowDirection;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.observation.InteractionRef;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.observation.SessionId;
import io.github.jacekkardys.systemproof.model.topology.ConnectionId;
import io.github.jacekkardys.systemproof.model.runtime.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.model.runtime.ObservationRequirement;

class ObserveBeforeForwardGatewayTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private static final ProtocolLimits LIMITS = new ProtocolLimits(128, 256);
    private static final ConnectionId FIRST_CONNECTION =
        ConnectionId.of("client[].api->server[].api");
    private static final ConnectionId SECOND_CONNECTION =
        ConnectionId.of("client[second].api->server[].api");

    @Test
    void shouldRecordAndDecideBeforeForwardingExactOrderedBytesInBothDirections()
        throws Exception {
        RecordingObservations observations = new RecordingObservations(FIRST_CONNECTION);
        CountDownLatch decisionEntered = new CountDownLatch(1);
        CountDownLatch allowDecision = new CountDownLatch(1);
        List<InteractionRef> decisions = new ArrayList<>();
        InteractionDecisionCoordinator coordinator = interactionRef -> {
            assertThat(observations.contains(interactionRef)).isTrue();
            synchronized (decisions) {
                decisions.add(interactionRef);
            }
            if (decisions.size() == 1) {
                decisionEntered.countDown();
                await(allowDecision, "first forwarding decision was not released");
            }
            return ForwardingDecision.FORWARD;
        };
        byte[] first = LengthPrefixedProtocolAdapter.frame("alpha");
        byte[] second = LengthPrefixedProtocolAdapter.frame("beta");
        byte[] coalesced = concat(first, second);

        try (RouteFixture fixture = RouteFixture.required(
            FIRST_CONNECTION,
            observations,
            coordinator,
            new LengthPrefixedProtocolAdapter(),
            LIMITS
        )) {
            fixture.client().getOutputStream().write(coalesced);
            fixture.client().getOutputStream().flush();

            assertThat(decisionEntered.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                .as("coordinator was reached")
                .isTrue();
            assertNoVisibleBytes(fixture.targetPeer());
            allowDecision.countDown();
            assertThat(readExactly(fixture.targetPeer(), coalesced.length))
                .isEqualTo(coalesced);

            byte[] response = LengthPrefixedProtocolAdapter.frame("response");
            fixture.targetPeer().getOutputStream().write(response);
            fixture.targetPeer().getOutputStream().flush();
            assertThat(readExactly(fixture.client(), response.length)).isEqualTo(response);

            List<InteractionObservationEvent> events = observations.events();
            assertThat(events).hasSize(3);
            assertThat(events)
                .extracting(event -> event.interactionRef().direction())
                .containsExactly(
                    FlowDirection.CONSUMER_TO_PROVIDER,
                    FlowDirection.CONSUMER_TO_PROVIDER,
                    FlowDirection.PROVIDER_TO_CONSUMER
                );
            assertThat(events)
                .extracting(event -> event.interactionRef().ordinal())
                .containsExactly(1L, 2L, 1L);
            assertThat(events)
                .extracting(event -> event.interactionRef().sessionId())
                .containsOnly(events.getFirst().interactionRef().sessionId());
            assertThat(events)
                .extracting(event -> event.evidence().decode(
                    LengthPrefixedProtocolAdapter.CODEC
                ).payloadBytes())
                .containsExactly(5, 4, 8);
            assertThat(events)
                .extracting(event -> event.evidence().decode(
                    LengthPrefixedProtocolAdapter.CODEC
                ).payloadSha256())
                .containsExactly(
                    LengthPrefixedProtocolAdapter.sha256("alpha".getBytes(UTF_8)),
                    LengthPrefixedProtocolAdapter.sha256("beta".getBytes(UTF_8)),
                    LengthPrefixedProtocolAdapter.sha256("response".getBytes(UTF_8))
                );
            assertThat(decisions).hasSize(3);
            assertThat(fixture.route().observationStatus())
                .isEqualTo(EffectiveObservationStatus.ACTIVE);

            SessionId firstSession = events.getFirst().interactionRef().sessionId();
            fixture.reconnect();
            byte[] reconnect = LengthPrefixedProtocolAdapter.frame("reconnect");
            fixture.client().getOutputStream().write(reconnect);
            fixture.client().getOutputStream().flush();
            assertThat(readExactly(fixture.targetPeer(), reconnect.length))
                .isEqualTo(reconnect);
            assertThat(observations.events().getLast().interactionRef().sessionId())
                .isNotEqualTo(firstSession);
        }
    }

    @Test
    void shouldPropagateHalfCloseOnlyAtACompleteFrameBoundary() throws Exception {
        RecordingObservations observations = new RecordingObservations(FIRST_CONNECTION);
        try (RouteFixture fixture = RouteFixture.required(
            FIRST_CONNECTION,
            observations,
            interactionRef -> ForwardingDecision.FORWARD,
            new LengthPrefixedProtocolAdapter(),
            LIMITS
        )) {
            byte[] request = LengthPrefixedProtocolAdapter.frame("request");
            fixture.client().getOutputStream().write(request);
            fixture.client().shutdownOutput();

            assertThat(readExactly(fixture.targetPeer(), request.length)).isEqualTo(request);
            assertThat(fixture.targetPeer().getInputStream().read()).isEqualTo(-1);

            byte[] response = LengthPrefixedProtocolAdapter.frame("response");
            fixture.targetPeer().getOutputStream().write(response);
            fixture.targetPeer().shutdownOutput();
            assertThat(readExactly(fixture.client(), response.length)).isEqualTo(response);
            assertThat(fixture.client().getInputStream().read()).isEqualTo(-1);
            assertThat(observations.events()).hasSize(2);
        }
    }

    @Test
    void shouldFailRequiredSessionsClosedForEveryProtocolFailureAndLimit()
        throws Exception {
        Map<Integer, ProtocolFailureKind> controls = Map.of(
            LengthPrefixedProtocolAdapter.MALFORMED,
            ProtocolFailureKind.MALFORMED_INPUT,
            LengthPrefixedProtocolAdapter.UNSUPPORTED_ENCRYPTION,
            ProtocolFailureKind.UNSUPPORTED_ENCRYPTION,
            LengthPrefixedProtocolAdapter.UNSUPPORTED_NEGOTIATION,
            ProtocolFailureKind.UNSUPPORTED_NEGOTIATION,
            LengthPrefixedProtocolAdapter.DESYNCHRONIZED,
            ProtocolFailureKind.DESYNCHRONIZATION,
            LengthPrefixedProtocolAdapter.AMBIGUOUS,
            ProtocolFailureKind.AMBIGUOUS_FRAMING
        );
        for (int control : controls.keySet()) {
            assertRequiredFailure(
                new LengthPrefixedProtocolAdapter(),
                LIMITS,
                LengthPrefixedProtocolAdapter.control(control),
                false
            );
        }

        byte[] excessive = java.nio.ByteBuffer.allocate(Integer.BYTES)
            .putInt(LIMITS.maximumFrameBytes())
            .array();
        assertRequiredFailure(
            new LengthPrefixedProtocolAdapter(),
            LIMITS,
            excessive,
            false
        );

        AtomicInteger maximumBuffered = new AtomicInteger();
        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> neverCompletes =
            delegatingAdapter(
                LengthPrefixedProtocolAdapter.CODEC,
                limits -> direction ->
                    new ProtocolStream<>() {
                        @Override
                        public ProtocolDecodeResult<
                            LengthPrefixedProtocolAdapter.FrameEvidence
                        > decode(java.nio.ByteBuffer buffered) {
                            maximumBuffered.accumulateAndGet(
                                buffered.remaining(),
                                Math::max
                            );
                            return ProtocolDecodeResult.needMoreData();
                        }
                    }
            );
        ProtocolLimits smallBuffer = new ProtocolLimits(8, 8);
        assertRequiredFailure(
            neverCompletes,
            smallBuffer,
            new byte[smallBuffer.maximumBufferedBytes()],
            false
        );
        assertThat(maximumBuffered).hasValue(smallBuffer.maximumBufferedBytes());

        byte[] incomplete = Arrays.copyOf(
            LengthPrefixedProtocolAdapter.frame("incomplete"),
            Integer.BYTES + 1
        );
        assertRequiredFailure(
            new LengthPrefixedProtocolAdapter(),
            LIMITS,
            incomplete,
            true
        );
    }

    @Test
    void shouldFailClosedOnCodecJournalAndCoordinatorFailure() throws Exception {
        EvidenceCodec<LengthPrefixedProtocolAdapter.FrameEvidence> failingCodec =
            new EvidenceCodec<>() {
                @Override
                public io.github.jacekkardys.systemproof.observation.EvidenceSchemaId schemaId() {
                    return LengthPrefixedProtocolAdapter.CODEC.schemaId();
                }

                @Override
                public byte[] encode(
                    LengthPrefixedProtocolAdapter.FrameEvidence evidence
                ) {
                    throw new IllegalStateException("codec failure with secret payload");
                }

                @Override
                public LengthPrefixedProtocolAdapter.FrameEvidence decode(byte[] encodedEvidence) {
                    return LengthPrefixedProtocolAdapter.CODEC.decode(encodedEvidence);
                }
            };
        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> codecFailure =
            delegatingAdapter(
                failingCodec,
                new LengthPrefixedProtocolAdapter()::openSession
            );
        RecordingObservations codecObservations =
            new RecordingObservations(FIRST_CONNECTION);
        assertPipelineFailure(
            codecObservations,
            interactionRef -> ForwardingDecision.FORWARD,
            codecFailure
        );
        assertThat(codecObservations.events()).isEmpty();

        RecordingObservations journalFailure =
            new RecordingObservations(FIRST_CONNECTION, true);
        assertPipelineFailure(
            journalFailure,
            interactionRef -> ForwardingDecision.FORWARD,
            new LengthPrefixedProtocolAdapter()
        );
        assertThat(journalFailure.events()).isEmpty();

        RecordingObservations coordinatorObservations =
            new RecordingObservations(FIRST_CONNECTION);
        assertPipelineFailure(
            coordinatorObservations,
            interactionRef -> {
                throw new IllegalStateException("coordinator failure with secret payload");
            },
            new LengthPrefixedProtocolAdapter()
        );
        assertThat(coordinatorObservations.events()).hasSize(1);
    }

    @Test
    void shouldFailRequiredSocketPairClosedWhenInitializationCallbackThrowsError()
        throws Exception {
        assertCallbackError(
            ObservationRequirement.REQUIRED,
            () -> {
                throw new AssertionError("observation session initialization secret");
            },
            interactionRef -> ForwardingDecision.FORWARD,
            new LengthPrefixedProtocolAdapter(),
            new byte[0],
            false,
            EffectiveObservationStatus.FAILED
        );

        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> codecInitializationError =
            new ProtocolAdapter<>() {
                @Override
                public EvidenceCodec<
                    LengthPrefixedProtocolAdapter.FrameEvidence
                > evidenceCodec() {
                    throw new AssertionError("codec initialization secret");
                }

                @Override
                public ProtocolSession<
                    LengthPrefixedProtocolAdapter.FrameEvidence
                > openSession(ProtocolLimits limits) {
                    return new LengthPrefixedProtocolAdapter().openSession(limits);
                }
            };
        assertCallbackError(
            ObservationRequirement.REQUIRED,
            new RecordingObservations(FIRST_CONNECTION),
            interactionRef -> ForwardingDecision.FORWARD,
            codecInitializationError,
            new byte[0],
            false,
            EffectiveObservationStatus.FAILED
        );

        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> adapterSessionError =
            delegatingAdapter(
                LengthPrefixedProtocolAdapter.CODEC,
                limits -> {
                    throw new AssertionError("adapter session initialization secret");
                }
            );
        assertCallbackError(
            ObservationRequirement.REQUIRED,
            new RecordingObservations(FIRST_CONNECTION),
            interactionRef -> ForwardingDecision.FORWARD,
            adapterSessionError,
            new byte[0],
            false,
            EffectiveObservationStatus.FAILED
        );

        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> streamError =
            delegatingAdapter(
                LengthPrefixedProtocolAdapter.CODEC,
                limits -> direction -> {
                    throw new AssertionError("protocol stream initialization secret");
                }
            );
        assertCallbackError(
            ObservationRequirement.REQUIRED,
            new RecordingObservations(FIRST_CONNECTION),
            interactionRef -> ForwardingDecision.FORWARD,
            streamError,
            new byte[0],
            false,
            EffectiveObservationStatus.FAILED
        );
    }

    @Test
    void shouldFailRequiredSocketPairClosedWhenStreamCallbackThrowsError()
        throws Exception {
        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> decodeError =
            delegatingAdapter(
                LengthPrefixedProtocolAdapter.CODEC,
                limits -> direction -> new ProtocolStream<>() {
                    @Override
                    public ProtocolDecodeResult<
                        LengthPrefixedProtocolAdapter.FrameEvidence
                    > decode(java.nio.ByteBuffer bufferedBytes) {
                        throw new AssertionError("decode secret");
                    }
                }
            );
        assertCallbackError(
            ObservationRequirement.REQUIRED,
            new RecordingObservations(FIRST_CONNECTION),
            interactionRef -> ForwardingDecision.FORWARD,
            decodeError,
            LengthPrefixedProtocolAdapter.frame("undecided"),
            false,
            EffectiveObservationStatus.FAILED
        );

        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> endOfInputError =
            delegatingAdapter(
                LengthPrefixedProtocolAdapter.CODEC,
                limits -> direction -> new ProtocolStream<>() {
                    @Override
                    public ProtocolDecodeResult<
                        LengthPrefixedProtocolAdapter.FrameEvidence
                    > decode(java.nio.ByteBuffer bufferedBytes) {
                        return ProtocolDecodeResult.needMoreData();
                    }

                    @Override
                    public void endOfInput(java.nio.ByteBuffer bufferedBytes) {
                        throw new AssertionError("end-of-input secret");
                    }
                }
            );
        assertCallbackError(
            ObservationRequirement.REQUIRED,
            new RecordingObservations(FIRST_CONNECTION),
            interactionRef -> ForwardingDecision.FORWARD,
            endOfInputError,
            new byte[0],
            true,
            EffectiveObservationStatus.FAILED
        );
    }

    @Test
    void shouldFailClosedWhenObservationOrDecisionCallbackThrowsError()
        throws Exception {
        ConnectionObservations observationError = () -> new InteractionSession() {
            @Override
            public <T> InteractionRef observe(
                FlowDirection direction,
                EvidenceCodec<T> codec,
                T evidence
            ) {
                throw new AssertionError("observation callback secret");
            }
        };
        assertCallbackError(
            ObservationRequirement.REQUIRED,
            observationError,
            interactionRef -> ForwardingDecision.FORWARD,
            new LengthPrefixedProtocolAdapter(),
            LengthPrefixedProtocolAdapter.frame("undecided"),
            false,
            EffectiveObservationStatus.FAILED
        );

        assertCallbackError(
            ObservationRequirement.OPTIONAL,
            new RecordingObservations(FIRST_CONNECTION),
            interactionRef -> {
                throw new AssertionError("decision callback secret");
            },
            new LengthPrefixedProtocolAdapter(),
            LengthPrefixedProtocolAdapter.frame("undecided"),
            false,
            EffectiveObservationStatus.DEGRADED
        );
    }

    @Test
    void shouldKeepDisabledAndUnsupportedOptionalRoutesExplicitlyTransparent()
        throws Exception {
        byte[] arbitraryTcpBytes = "not-a-frame".getBytes(UTF_8);
        for (ObservationRequirement requirement : List.of(
            ObservationRequirement.DISABLED,
            ObservationRequirement.OPTIONAL
        )) {
            RecordingObservations observations =
                new RecordingObservations(FIRST_CONNECTION);
            try (RouteFixture fixture = RouteFixture.open(
                FIRST_CONNECTION,
                requirement,
                observations,
                interactionRef -> {
                    throw new AssertionError("Transparent traffic must not be decided");
                },
                null,
                null
            )) {
                fixture.client().getOutputStream().write(arbitraryTcpBytes);
                fixture.client().getOutputStream().flush();
                assertThat(readExactly(fixture.targetPeer(), arbitraryTcpBytes.length))
                    .isEqualTo(arbitraryTcpBytes);
                assertThat(observations.events()).isEmpty();
                assertThat(fixture.route().observationStatus()).isEqualTo(
                    requirement == ObservationRequirement.DISABLED
                        ? EffectiveObservationStatus.DISABLED
                        : EffectiveObservationStatus.UNSUPPORTED
                );
            }
        }
    }

    @Test
    void shouldDegradeOptionalObservationWithoutTransparentRetry() throws Exception {
        RecordingObservations observations = new RecordingObservations(FIRST_CONNECTION);
        try (RouteFixture fixture = RouteFixture.open(
            FIRST_CONNECTION,
            ObservationRequirement.OPTIONAL,
            observations,
            interactionRef -> ForwardingDecision.FORWARD,
            new LengthPrefixedProtocolAdapter(),
            LIMITS
        )) {
            fixture.client().getOutputStream().write(
                LengthPrefixedProtocolAdapter.control(
                    LengthPrefixedProtocolAdapter.UNSUPPORTED_ENCRYPTION
                )
            );
            fixture.client().getOutputStream().flush();
            assertNoForwardedBytesAfterClose(fixture.targetPeer());
            assertThat(fixture.route().observationStatus())
                .isEqualTo(EffectiveObservationStatus.DEGRADED);

            fixture.reconnect();
            byte[] valid = LengthPrefixedProtocolAdapter.frame("must-not-bypass");
            fixture.client().getOutputStream().write(valid);
            fixture.client().getOutputStream().flush();
            assertNoForwardedBytesAfterClose(fixture.targetPeer());
            assertThat(observations.events()).isEmpty();
        }
    }

    @Test
    void shouldShareOneCoordinatorAcrossConnectionsWhileKeepingIdentityIsolated()
        throws Exception {
        List<InteractionRef> decisions = java.util.Collections.synchronizedList(
            new ArrayList<>()
        );
        InteractionDecisionCoordinator coordinator = interactionRef -> {
            decisions.add(interactionRef);
            return ForwardingDecision.FORWARD;
        };
        RecordingObservations firstObservations =
            new RecordingObservations(FIRST_CONNECTION);
        RecordingObservations secondObservations =
            new RecordingObservations(SECOND_CONNECTION);

        try (
            RouteFixture first = RouteFixture.required(
                FIRST_CONNECTION,
                firstObservations,
                coordinator,
                new LengthPrefixedProtocolAdapter(),
                LIMITS
            );
            RouteFixture second = RouteFixture.required(
                SECOND_CONNECTION,
                secondObservations,
                coordinator,
                new LengthPrefixedProtocolAdapter(),
                LIMITS
            )
        ) {
            byte[] firstFrame = LengthPrefixedProtocolAdapter.frame("first");
            byte[] secondFrame = LengthPrefixedProtocolAdapter.frame("second");
            first.client().getOutputStream().write(firstFrame);
            first.client().getOutputStream().flush();
            second.client().getOutputStream().write(secondFrame);
            second.client().getOutputStream().flush();
            assertThat(readExactly(first.targetPeer(), firstFrame.length))
                .isEqualTo(firstFrame);
            assertThat(readExactly(second.targetPeer(), secondFrame.length))
                .isEqualTo(secondFrame);
            assertThat(decisions)
                .extracting(InteractionRef::connectionId)
                .containsExactlyInAnyOrder(FIRST_CONNECTION, SECOND_CONNECTION);
            assertThat(decisions)
                .extracting(InteractionRef::ordinal)
                .containsOnly(1L);
        }
    }

    @Test
    void shouldPublishSemanticCorrelationBeforeDecisionWithoutArrivalFallback()
        throws Exception {
        RecordingObservations observations = new RecordingObservations(FIRST_CONNECTION);
        CorrelationKey firstKey =
            LengthPrefixedProtocolAdapter.correlationKey("first-subject");
        CorrelationKey secondKey =
            LengthPrefixedProtocolAdapter.correlationKey("second-subject");
        CorrelationKey missingKey =
            LengthPrefixedProtocolAdapter.correlationKey("missing-subject");
        observations.arm("first", firstKey);
        observations.arm("second", secondKey);
        observations.arm("missing", missingKey);
        List<InteractionRef> decisions = new ArrayList<>();
        AtomicInteger firstKeyDecisions = new AtomicInteger();
        InteractionDecisionCoordinator coordinator = interactionRef -> {
            CorrelationKey publishedKey = observations.publishedKey(interactionRef);
            assertThat(publishedKey)
                .as("correlation must be published before decision")
                .isNotNull();
            if (publishedKey.equals(firstKey)) {
                int matchingDecision = firstKeyDecisions.getAndIncrement();
                assertThat(observations.cardinality("first", firstKey))
                    .as("first matching decision is unique; retries are ambiguous")
                    .isEqualTo(
                        matchingDecision == 0
                            ? CorrelationCardinality.UNIQUE
                            : CorrelationCardinality.AMBIGUOUS
                    );
            } else if (publishedKey.equals(secondKey)) {
                assertThat(observations.cardinality("second", secondKey))
                    .isEqualTo(CorrelationCardinality.UNIQUE);
            }
            assertThat(observations.cardinality("missing", missingKey))
                .isEqualTo(CorrelationCardinality.MISSING);
            decisions.add(interactionRef);
            return ForwardingDecision.FORWARD;
        };
        byte[] first = LengthPrefixedProtocolAdapter.frame("first-subject");
        byte[] second = LengthPrefixedProtocolAdapter.frame("second-subject");
        byte[] coalesced = concat(first, second);

        try (RouteFixture fixture = RouteFixture.required(
            FIRST_CONNECTION,
            observations,
            coordinator,
            LengthPrefixedProtocolAdapter.correlating(),
            LIMITS
        )) {
            for (byte value : coalesced) {
                fixture.client().getOutputStream().write(value);
            }
            fixture.client().getOutputStream().flush();
            assertThat(readExactly(fixture.targetPeer(), coalesced.length))
                .isEqualTo(coalesced);

            assertThat(observations.cardinality("first", firstKey))
                .isEqualTo(CorrelationCardinality.UNIQUE);
            assertThat(observations.cardinality("second", secondKey))
                .isEqualTo(CorrelationCardinality.UNIQUE);
            assertThat(observations.cardinality("missing", missingKey))
                .isEqualTo(CorrelationCardinality.MISSING);
            assertThat(observations.uniqueInteraction("first", firstKey))
                .isEqualTo(decisions.get(0));
            assertThat(observations.uniqueInteraction("second", secondKey))
                .isEqualTo(decisions.get(1));

            fixture.client().getOutputStream().write(first);
            fixture.client().getOutputStream().flush();
            assertThat(readExactly(fixture.targetPeer(), first.length)).isEqualTo(first);
            assertThat(observations.cardinality("first", firstKey))
                .isEqualTo(CorrelationCardinality.AMBIGUOUS);

            fixture.reconnect();
            fixture.client().getOutputStream().write(first);
            fixture.client().getOutputStream().flush();
            assertThat(readExactly(fixture.targetPeer(), first.length)).isEqualTo(first);
            assertThat(observations.cardinality("first", firstKey))
                .isEqualTo(CorrelationCardinality.AMBIGUOUS);
            assertThat(observations.cardinality("missing", missingKey))
                .isEqualTo(CorrelationCardinality.MISSING);
        }
        assertThat(firstKeyDecisions).hasValue(3);
    }

    @Test
    void shouldFailClosedWhenCorrelationPublicationThrowsRuntimeExceptionOrError()
        throws Exception {
        byte[] undecided = LengthPrefixedProtocolAdapter.frame("correlation-secret");
        assertCallbackError(
            ObservationRequirement.REQUIRED,
            failingCorrelation(new IllegalStateException("correlation runtime secret")),
            interactionRef -> ForwardingDecision.FORWARD,
            LengthPrefixedProtocolAdapter.correlating(),
            undecided,
            false,
            EffectiveObservationStatus.FAILED
        );
        assertCallbackError(
            ObservationRequirement.OPTIONAL,
            failingCorrelation(new AssertionError("correlation error secret")),
            interactionRef -> ForwardingDecision.FORWARD,
            LengthPrefixedProtocolAdapter.correlating(),
            undecided,
            false,
            EffectiveObservationStatus.DEGRADED
        );
    }

    private static void assertRequiredFailure(
        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> adapter,
        ProtocolLimits limits,
        byte[] bytes,
        boolean halfClose
    ) throws Exception {
        RecordingObservations observations = new RecordingObservations(FIRST_CONNECTION);
        try (RouteFixture fixture = RouteFixture.required(
            FIRST_CONNECTION,
            observations,
            interactionRef -> ForwardingDecision.FORWARD,
            adapter,
            limits
        )) {
            fixture.client().getOutputStream().write(bytes);
            fixture.client().getOutputStream().flush();
            if (halfClose) {
                fixture.client().shutdownOutput();
            }
            assertNoForwardedBytesAfterClose(fixture.targetPeer());
            assertThat(observations.events()).isEmpty();
            assertThat(fixture.route().observationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED);
        }
    }

    private static void assertPipelineFailure(
        RecordingObservations observations,
        InteractionDecisionCoordinator coordinator,
        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> adapter
    ) throws Exception {
        try (RouteFixture fixture = RouteFixture.required(
            FIRST_CONNECTION,
            observations,
            coordinator,
            adapter,
            LIMITS
        )) {
            fixture.client().getOutputStream().write(
                LengthPrefixedProtocolAdapter.frame("undecided-secret")
            );
            fixture.client().getOutputStream().flush();
            assertNoForwardedBytesAfterClose(fixture.targetPeer());
            assertThat(fixture.route().observationStatus())
                .isEqualTo(EffectiveObservationStatus.FAILED);
        }
    }

    private static void assertCallbackError(
        ObservationRequirement requirement,
        ConnectionObservations observations,
        InteractionDecisionCoordinator coordinator,
        ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> adapter,
        byte[] trigger,
        boolean halfClose,
        EffectiveObservationStatus expectedStatus
    ) throws Exception {
        try (RouteFixture fixture = RouteFixture.open(
            FIRST_CONNECTION,
            requirement,
            observations,
            coordinator,
            adapter,
            LIMITS
        )) {
            fixture.client().getOutputStream().write(trigger);
            fixture.client().getOutputStream().flush();
            if (halfClose) {
                fixture.client().shutdownOutput();
            }
            assertSocketPairClosedWithoutForwarding(fixture);
            assertThat(fixture.route().observationStatus()).isEqualTo(expectedStatus);
        }
    }

    private static void assertSocketPairClosedWithoutForwarding(RouteFixture fixture)
        throws IOException {
        assertNoForwardedBytesAfterClose(fixture.targetPeer());
        try {
            fixture.targetPeer().getOutputStream().write(
                LengthPrefixedProtocolAdapter.frame("opposite-direction")
            );
            fixture.targetPeer().getOutputStream().flush();
        } catch (IOException ignored) {
            // A full peer close may reject the write before the no-forwarding assertion.
        }
        assertNoForwardedBytesAfterClose(fixture.client());
    }

    private static ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence>
        delegatingAdapter(
            EvidenceCodec<LengthPrefixedProtocolAdapter.FrameEvidence> codec,
            SessionFactory sessions
        ) {
        return new ProtocolAdapter<>() {
            @Override
            public EvidenceCodec<LengthPrefixedProtocolAdapter.FrameEvidence> evidenceCodec() {
                return codec;
            }

            @Override
            public ProtocolSession<LengthPrefixedProtocolAdapter.FrameEvidence> openSession(
                ProtocolLimits limits
            ) {
                return sessions.open(limits);
            }
        };
    }

    private static ConnectionObservations failingCorrelation(Throwable failure) {
        return () -> new InteractionSession() {
            private final AtomicLong ordinal =
                new AtomicLong(InteractionRef.FIRST_ORDINAL);

            @Override
            public <T> InteractionRef observe(
                FlowDirection direction,
                EvidenceCodec<T> codec,
                T evidence
            ) {
                return new InteractionRef(
                    new SessionId(FIRST_CONNECTION, SessionId.FIRST_VALUE),
                    direction,
                    ordinal.getAndIncrement()
                );
            }

            @Override
            public void correlate(
                InteractionRef interactionRef,
                CorrelationContribution<?> contribution
            ) {
                if (failure instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw (Error) failure;
            }
        };
    }

    private static void assertNoVisibleBytes(Socket socket) throws IOException {
        int originalTimeout = socket.getSoTimeout();
        socket.setSoTimeout(150);
        try {
            assertThatThrownBy(() -> socket.getInputStream().read())
                .isInstanceOf(SocketTimeoutException.class);
        } finally {
            socket.setSoTimeout(originalTimeout);
        }
    }

    private static void assertNoForwardedBytesAfterClose(Socket socket) throws IOException {
        socket.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
        try {
            assertThat(socket.getInputStream().read()).isEqualTo(-1);
        } catch (SocketTimeoutException timeout) {
            throw new AssertionError("Failed session did not close within the timeout", timeout);
        }
    }

    private static byte[] readExactly(Socket socket, int expectedBytes) throws IOException {
        socket.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
        byte[] value = socket.getInputStream().readNBytes(expectedBytes);
        assertThat(value).hasSize(expectedBytes);
        return value;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] combined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, combined, first.length, second.length);
        return combined;
    }

    private static void await(CountDownLatch latch, String failureMessage) {
        try {
            if (!latch.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException(failureMessage);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failureMessage, interrupted);
        }
    }

    @FunctionalInterface
    private interface SessionFactory {
        ProtocolSession<LengthPrefixedProtocolAdapter.FrameEvidence> open(
            ProtocolLimits limits
        );
    }

    private static final class RecordingObservations implements ConnectionObservations {
        private final ConnectionId connectionId;
        private final ScenarioJournal journal = new ScenarioJournal();
        private final AtomicLong nextSession = new AtomicLong(SessionId.FIRST_VALUE);
        private final boolean failJournal;
        private final TestCorrelations correlations = new TestCorrelations();

        private RecordingObservations(ConnectionId connectionId) {
            this(connectionId, false);
        }

        private RecordingObservations(ConnectionId connectionId, boolean failJournal) {
            this.connectionId = connectionId;
            this.failJournal = failJournal;
        }

        @Override
        public InteractionSession openSession() {
            return new RecordingSession(new SessionId(
                connectionId,
                nextSession.getAndIncrement()
            ));
        }

        private boolean contains(InteractionRef interactionRef) {
            return events().stream()
                .anyMatch(event -> event.interactionRef().equals(interactionRef));
        }

        private List<InteractionObservationEvent> events() {
            return journal.snapshot().entries().stream()
                .map(entry -> entry.event())
                .filter(InteractionObservationEvent.class::isInstance)
                .map(InteractionObservationEvent.class::cast)
                .toList();
        }

        private void arm(String subject, CorrelationKey key) {
            correlations.arm(subject, key);
        }

        private CorrelationCardinality cardinality(
            String subject,
            CorrelationKey key
        ) {
            return correlations.cardinality(subject, key);
        }

        private InteractionRef uniqueInteraction(
            String subject,
            CorrelationKey key
        ) {
            return correlations.uniqueInteraction(subject, key);
        }

        private CorrelationKey publishedKey(InteractionRef interactionRef) {
            return correlations.publishedKey(interactionRef);
        }

        private final class RecordingSession implements InteractionSession {
            private final SessionId sessionId;
            private final EnumMap<FlowDirection, AtomicLong> ordinals =
                new EnumMap<>(FlowDirection.class);

            private RecordingSession(SessionId sessionId) {
                this.sessionId = sessionId;
                for (FlowDirection direction : FlowDirection.values()) {
                    ordinals.put(direction, new AtomicLong(InteractionRef.FIRST_ORDINAL));
                }
            }

            @Override
            public <T> InteractionRef observe(
                FlowDirection direction,
                EvidenceCodec<T> codec,
                T evidence
            ) {
                if (failJournal) {
                    throw new IllegalStateException("journal failure with secret payload");
                }
                InteractionRef interactionRef = new InteractionRef(
                    sessionId,
                    direction,
                    ordinals.get(direction).getAndIncrement()
                );
                journal.append(new InteractionObservationEvent(
                    interactionRef,
                    EvidenceSnapshot.capture(codec, evidence)
                ));
                return interactionRef;
            }

            @Override
            public void correlate(
                InteractionRef interactionRef,
                CorrelationContribution<?> contribution
            ) {
                if (!contains(interactionRef)) {
                    throw new IllegalStateException(
                        "Correlation publication preceded interaction recording"
                    );
                }
                correlations.publish(interactionRef, contribution);
            }
        }
    }

    private static final class TestCorrelations {
        private final Map<String, Map<CorrelationKey, TestResolution>> subjects =
            new HashMap<>();
        private final Map<CorrelationKey, Set<String>> subjectsByKey =
            new HashMap<>();
        private final Map<InteractionRef, CorrelationKey> published =
            new HashMap<>();

        private synchronized void arm(String subject, CorrelationKey key) {
            Map<CorrelationKey, TestResolution> resolutions =
                subjects.computeIfAbsent(subject, ignored -> new HashMap<>());
            if (resolutions.containsKey(key)) {
                return;
            }
            Set<String> owners = subjectsByKey.computeIfAbsent(
                key,
                ignored -> new LinkedHashSet<>()
            );
            if (owners.isEmpty()) {
                resolutions.put(key, TestMissing.INSTANCE);
            } else {
                resolutions.put(key, TestAmbiguous.INSTANCE);
                owners.forEach(owner ->
                    subjects.get(owner).put(key, TestAmbiguous.INSTANCE)
                );
            }
            owners.add(subject);
        }

        private synchronized void publish(
            InteractionRef interactionRef,
            CorrelationContribution<?> contribution
        ) {
            CorrelationKey key = contribution.key();
            published.put(interactionRef, key);
            Set<String> owners = subjectsByKey.getOrDefault(key, Set.of());
            if (owners.size() != 1) {
                owners.forEach(owner ->
                    subjects.get(owner).put(key, TestAmbiguous.INSTANCE)
                );
                return;
            }
            String subject = owners.iterator().next();
            TestResolution current = subjects.get(subject).get(key);
            if (current == TestMissing.INSTANCE) {
                subjects.get(subject).put(key, new TestUnique(interactionRef));
            } else if (current instanceof TestUnique unique
                && !unique.interactionRef().equals(interactionRef)) {
                subjects.get(subject).put(key, TestAmbiguous.INSTANCE);
            }
        }

        private synchronized CorrelationCardinality cardinality(
            String subject,
            CorrelationKey key
        ) {
            TestResolution resolution = subjects.getOrDefault(subject, Map.of())
                .get(key);
            if (resolution == TestMissing.INSTANCE) {
                return CorrelationCardinality.MISSING;
            }
            if (resolution instanceof TestUnique) {
                return CorrelationCardinality.UNIQUE;
            }
            if (resolution == TestAmbiguous.INSTANCE) {
                return CorrelationCardinality.AMBIGUOUS;
            }
            throw new IllegalArgumentException("Subject/key is not armed");
        }

        private synchronized InteractionRef uniqueInteraction(
            String subject,
            CorrelationKey key
        ) {
            TestResolution resolution = subjects.get(subject).get(key);
            if (!(resolution instanceof TestUnique unique)) {
                throw new IllegalStateException("Correlation is not unique");
            }
            return unique.interactionRef();
        }

        private synchronized CorrelationKey publishedKey(
            InteractionRef interactionRef
        ) {
            return published.get(interactionRef);
        }
    }

    private sealed interface TestResolution
        permits TestMissing, TestUnique, TestAmbiguous {}

    private enum TestMissing implements TestResolution {
        INSTANCE
    }

    private record TestUnique(InteractionRef interactionRef)
        implements TestResolution {}

    private enum TestAmbiguous implements TestResolution {
        INSTANCE
    }

    private static final class RouteFixture implements AutoCloseable {
        private final ServerSocket targetListener;
        private final GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route;
        private Socket client;
        private Socket targetPeer;

        private RouteFixture(
            ServerSocket targetListener,
            GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route
        ) throws IOException {
            this.targetListener = targetListener;
            this.route = route;
            route.start();
            connectPair();
        }

        private static RouteFixture required(
            ConnectionId connectionId,
            ConnectionObservations observations,
            InteractionDecisionCoordinator coordinator,
            ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> adapter,
            ProtocolLimits limits
        ) throws IOException {
            return open(
                connectionId,
                ObservationRequirement.REQUIRED,
                observations,
                coordinator,
                adapter,
                limits
            );
        }

        private static RouteFixture open(
            ConnectionId connectionId,
            ObservationRequirement requirement,
            ConnectionObservations observations,
            InteractionDecisionCoordinator coordinator,
            ProtocolAdapter<LengthPrefixedProtocolAdapter.FrameEvidence> adapter,
            ProtocolLimits limits
        ) throws IOException {
            ServerSocket targetListener = new ServerSocket();
            targetListener.bind(new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                0
            ));
            targetListener.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
            GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route =
                GatewayRoute.open(
                    connectionId,
                    new InetSocketAddress("127.0.0.1", targetListener.getLocalPort()),
                    TIMEOUT,
                    TIMEOUT,
                    requirement,
                    observations,
                    coordinator,
                    adapter,
                    limits
                );
            try {
                return new RouteFixture(targetListener, route);
            } catch (IOException | RuntimeException failure) {
                try {
                    route.close();
                } catch (Exception cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                try {
                    targetListener.close();
                } catch (IOException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        private GatewayRoute<LengthPrefixedProtocolAdapter.FrameEvidence> route() {
            return route;
        }

        private Socket client() {
            return client;
        }

        private Socket targetPeer() {
            return targetPeer;
        }

        private void reconnect() throws IOException {
            client.close();
            targetPeer.close();
            connectPair();
        }

        private void connectPair() throws IOException {
            client = new Socket();
            client.connect(
                new InetSocketAddress("127.0.0.1", route.listenerPort()),
                Math.toIntExact(TIMEOUT.toMillis())
            );
            client.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
            targetPeer = targetListener.accept();
            targetPeer.setSoTimeout(Math.toIntExact(TIMEOUT.toMillis()));
        }

        @Override
        public void close() throws Exception {
            Throwable failure = null;
            failure = close(client, failure);
            failure = close(targetPeer, failure);
            failure = close(route, failure);
            failure = close(targetListener, failure);
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }

        private static Throwable close(AutoCloseable resource, Throwable first) {
            try {
                resource.close();
                return first;
            } catch (Exception | Error failure) {
                if (first == null) {
                    return failure;
                }
                first.addSuppressed(failure);
                return first;
            }
        }
    }
}
