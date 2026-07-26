package io.github.jacekkardys.systemproof.engine;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.diagnostics.EnvironmentEventLog;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.journal.CorrelationCandidateEvent;
import io.github.jacekkardys.systemproof.journal.EvidenceCodec;
import io.github.jacekkardys.systemproof.journal.EvidenceSchemaId;
import io.github.jacekkardys.systemproof.journal.FlowDirection;
import io.github.jacekkardys.systemproof.journal.InteractionObservationEvent;
import io.github.jacekkardys.systemproof.journal.InteractionRef;
import io.github.jacekkardys.systemproof.journal.ProofSubjectCreatedEvent;
import io.github.jacekkardys.systemproof.journal.ScenarioJournal;
import io.github.jacekkardys.systemproof.journal.SessionId;
import io.github.jacekkardys.systemproof.model.ConnectionId;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.Environment;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;

class ProofSubjectCorrelationTest {
    private static final CorrelationKeySchema KEY_SCHEMA =
        new CorrelationKeySchema("system-proof-test", "operation", 1);
    private static final EvidenceCodec<byte[]> NATIVE_REFERENCE_CODEC =
        binaryCodec("native-reference");
    private static final EvidenceCodec<byte[]> OTHER_NATIVE_REFERENCE_CODEC =
        binaryCodec("other-native-reference");
    private static final ConnectionId CONNECTION =
        ConnectionId.of("client[].api->server[].api");

    @Test
    void shouldAllocateBeforeTrafficRejectCrossEnvironmentUseAndKeepLookupAfterTeardown() {
        Environment first = Environment.environment()
            .components(new TestComponent("first-environment"))
            .build();
        Environment second = Environment.environment()
            .components(new TestComponent("second-environment"))
            .build();
        CorrelationKey key = key("scenario-operation");

        try {
            ProofSubjectRef subject = first.proofSubjects().create();
            first.proofSubjects().arm(subject, key);

            assertThat(first.journalSnapshot().entries())
                .extracting(entry -> entry.event().getClass().getSimpleName())
                .startsWith(
                    ProofSubjectCreatedEvent.class.getSimpleName(),
                    io.github.jacekkardys.systemproof.journal.ProofSubjectArmedEvent.class
                        .getSimpleName()
                )
                .doesNotContain(InteractionObservationEvent.class.getSimpleName());
            assertThat(ProofSubjectRef.class.getConstructors()).isEmpty();
            assertThat(Modifier.isPublic(ProofSubjectRegistry.class.getModifiers()))
                .isFalse();
            assertThat(ProofSubjects.class.getMethods())
                .extracting(method -> method.getName())
                .containsExactlyInAnyOrder("create", "arm", "correlation");
            assertThat(CorrelationKey.class.getMethods())
                .noneMatch(method -> method.getReturnType().isArray());
            assertThatThrownBy(() -> second.proofSubjects().arm(subject, key))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Proof subject belongs to a different environment execution");
            assertThatThrownBy(() -> second.proofSubjects().correlation(
                subject,
                key,
                NATIVE_REFERENCE_CODEC
            ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Proof subject belongs to a different environment execution");

            first.close();

            assertThat(first.proofSubjects().correlation(
                subject,
                key,
                NATIVE_REFERENCE_CODEC
            )).isInstanceOf(CorrelationResult.Missing.class);
            assertThatThrownBy(first.proofSubjects()::create)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Environment execution is complete");
            assertThatThrownBy(() -> first.proofSubjects().arm(subject, key("late")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Environment execution is complete");
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    void shouldProgressFromMissingToUniqueAndTerminalAmbiguous() {
        Fixture fixture = fixture();
        ProofSubjectRef subject = fixture.registry.create();
        CorrelationKey key = key("one-operation");
        fixture.registry.arm(subject, key);
        CorrelationContribution<byte[]> contribution = CorrelationContribution.capture(
            key,
            NATIVE_REFERENCE_CODEC,
            new byte[] {1, 2, 3}
        );
        EvidenceCodec<byte[]> unusedCodec = codecThatMustNotBeCalled();

        assertThat(fixture.registry.correlation(
            subject,
            key,
            unusedCodec
        )).isInstanceOf(CorrelationResult.Missing.class);

        InteractionRef first = interaction(1, 1);
        fixture.registry.publish(first, contribution);
        CorrelationResult<byte[]> unique = fixture.registry.correlation(
            subject,
            key,
            NATIVE_REFERENCE_CODEC
        );
        assertThat(unique).isInstanceOfSatisfying(
            CorrelationResult.Unique.class,
            result -> {
                assertThat(result.interactionRef()).isEqualTo(first);
                assertThat((byte[]) result.nativeReference())
                    .containsExactly(1, 2, 3);
            }
        );

        fixture.registry.publish(interaction(1, 2), contribution);
        assertThat(fixture.registry.correlation(
            subject,
            key,
            unusedCodec
        )).isInstanceOf(CorrelationResult.Ambiguous.class);

        fixture.registry.publish(first, contribution);
        assertThat(fixture.registry.correlation(
            subject,
            key,
            unusedCodec
        )).isInstanceOf(CorrelationResult.Ambiguous.class);
    }

    @Test
    void shouldKeepDistinctSubjectsIsolatedAndRejectSharedKeyOwnership() {
        Fixture fixture = fixture();
        ProofSubjectRef first = fixture.registry.create();
        ProofSubjectRef second = fixture.registry.create();
        ProofSubjectRef duplicate = fixture.registry.create();
        CorrelationKey firstKey = key("first-operation");
        CorrelationKey secondKey = key("second-operation");
        fixture.registry.arm(first, firstKey);
        fixture.registry.arm(second, secondKey);

        fixture.registry.publish(
            interaction(1, 1),
            contribution(firstKey, 1)
        );
        fixture.registry.publish(
            interaction(1, 2),
            contribution(secondKey, 2)
        );

        assertThat(fixture.registry.correlation(
            first,
            firstKey,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Unique.class);
        assertThat(fixture.registry.correlation(
            second,
            secondKey,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Unique.class);

        fixture.registry.arm(duplicate, firstKey);

        assertThat(fixture.registry.correlation(
            first,
            firstKey,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Ambiguous.class);
        assertThat(fixture.registry.correlation(
            duplicate,
            firstKey,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Ambiguous.class);
        assertThat(fixture.registry.correlation(
            second,
            secondKey,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Unique.class);

        fixture.registry.publish(
            interaction(1, 3),
            contribution(firstKey, 3)
        );
        CorrelationCandidateEvent sharedCandidate =
            fixture.correlationEvents().getLast();
        assertThat(sharedCandidate.proofSubject()).isEmpty();
        assertThat(sharedCandidate.cardinality())
            .isEqualTo(CorrelationCardinality.AMBIGUOUS);
    }

    @Test
    void shouldTreatOnlyEveryIdentityComponentMatchingAsAnExactDuplicate() {
        Fixture fixture = fixture();
        ProofSubjectRef exactSubject = fixture.registry.create();
        CorrelationKey exactKey = key("exact-duplicate");
        fixture.registry.arm(exactSubject, exactKey);
        InteractionRef exactInteraction = interaction(1, 1);
        CorrelationContribution<byte[]> exactContribution =
            contribution(exactKey, 7);

        fixture.registry.publish(exactInteraction, exactContribution);
        fixture.registry.publish(exactInteraction, exactContribution);

        assertThat(fixture.correlationEvents()).hasSize(1);
        assertThat(fixture.registry.correlation(
            exactSubject,
            exactKey,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Unique.class);

        ProofSubjectRef retrySubject = fixture.registry.create();
        CorrelationKey retryKey = key("retry");
        fixture.registry.arm(retrySubject, retryKey);
        CorrelationContribution<byte[]> retryContribution =
            contribution(retryKey, 8);
        fixture.registry.publish(interaction(2, 1), retryContribution);
        fixture.registry.publish(interaction(2, 2), retryContribution);
        assertThat(fixture.registry.correlation(
            retrySubject,
            retryKey,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Ambiguous.class);

        ProofSubjectRef reconnectSubject = fixture.registry.create();
        CorrelationKey reconnectKey = key("reconnect");
        fixture.registry.arm(reconnectSubject, reconnectKey);
        CorrelationContribution<byte[]> reconnectContribution =
            contribution(reconnectKey, 9);
        fixture.registry.publish(interaction(3, 1), reconnectContribution);
        fixture.registry.publish(interaction(4, 1), reconnectContribution);
        assertThat(fixture.registry.correlation(
            reconnectSubject,
            reconnectKey,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Ambiguous.class);
    }

    @Test
    void shouldNotRetroactivelyBindAnUnmatchedCandidate() {
        Fixture fixture = fixture();
        CorrelationKey key = key("late-arm");
        InteractionRef interaction = interaction(1, 1);
        CorrelationContribution<byte[]> contribution = contribution(key, 5);

        fixture.registry.publish(interaction, contribution);
        ProofSubjectRef subject = fixture.registry.create();
        fixture.registry.arm(subject, key);

        assertThat(fixture.registry.correlation(
            subject,
            key,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Missing.class);
        assertThat(fixture.correlationEvents().getFirst().proofSubject()).isEmpty();

        fixture.registry.publish(interaction, contribution);
        assertThat(fixture.registry.correlation(
            subject,
            key,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Unique.class);
    }

    @Test
    void shouldDetachKeyAndNativeReferenceMaterialAndRenderOnlySafeMetadata() {
        Fixture fixture = fixture();
        byte[] digest = sha256("raw-phone-number".getBytes(UTF_8));
        byte[] originalDigest = digest.clone();
        CorrelationKey key = CorrelationKey.ofDigest(KEY_SCHEMA, digest);
        digest[0] ^= 0x7f;
        assertThat(key).isEqualTo(CorrelationKey.ofDigest(KEY_SCHEMA, originalDigest));

        ProofSubjectRef subject = fixture.registry.create();
        fixture.registry.arm(subject, key);
        byte[] source = "native-secret-reference".getBytes(UTF_8);
        byte[] expected = source.clone();
        CorrelationContribution<byte[]> contribution = CorrelationContribution.capture(
            key,
            NATIVE_REFERENCE_CODEC,
            source
        );
        source[0] ^= 0x7f;
        fixture.registry.publish(interaction(1, 1), contribution);

        CorrelationResult.Unique<byte[]> first = (CorrelationResult.Unique<byte[]>)
            fixture.registry.correlation(subject, key, NATIVE_REFERENCE_CODEC);
        assertThat(first.nativeReference()).containsExactly(expected);
        first.nativeReference()[0] ^= 0x7f;
        CorrelationResult.Unique<byte[]> second = (CorrelationResult.Unique<byte[]>)
            fixture.registry.correlation(subject, key, NATIVE_REFERENCE_CODEC);
        assertThat(second.nativeReference()).containsExactly(expected);

        assertThatThrownBy(() -> fixture.registry.correlation(
            subject,
            key,
            OTHER_NATIVE_REFERENCE_CODEC
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Evidence schema mismatch");

        String diagnostics = fixture.eventLog.snapshot().content();
        assertThat(key.toString())
            .contains(KEY_SCHEMA.toString())
            .doesNotContain(Arrays.toString(originalDigest), "raw-phone-number");
        assertThat(contribution.toString())
            .doesNotContain("native-secret-reference");
        assertThat(second.toString())
            .doesNotContain("native-secret-reference");
        assertThat(diagnostics)
            .doesNotContain(
                "raw-phone-number",
                "native-secret-reference",
                Arrays.toString(originalDigest)
            );
    }

    @Test
    void shouldPublishConcurrentlyWithOneLinearizableTerminalResult() throws Exception {
        Fixture fixture = fixture();
        ProofSubjectRef subject = fixture.registry.create();
        CorrelationKey key = key("concurrent");
        fixture.registry.arm(subject, key);
        CorrelationContribution<byte[]> contribution = contribution(key, 4);
        InteractionRef first = interaction(1, 1);
        InteractionRef second = interaction(1, 2);
        int workers = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> publications = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(workers)) {
            for (int worker = 0; worker < workers; worker++) {
                InteractionRef candidate = worker % 2 == 0 ? first : second;
                publications.add(executor.submit(() -> {
                    start.await();
                    fixture.registry.publish(candidate, contribution);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> publication : publications) {
                publication.get(5, TimeUnit.SECONDS);
            }
        }

        assertThat(fixture.registry.correlation(
            subject,
            key,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Ambiguous.class);
        assertThat(fixture.correlationEvents())
            .extracting(CorrelationCandidateEvent::cardinality)
            .contains(CorrelationCardinality.UNIQUE, CorrelationCardinality.AMBIGUOUS);
    }

    @Test
    void shouldRunCodecCallbacksOutsideTheRegistryLockAndKeepTheCapturedResolution()
        throws Exception {
        Fixture fixture = fixture();
        ProofSubjectRef subject = fixture.registry.create();
        CorrelationKey key = key("blocking-codec");
        fixture.registry.arm(subject, key);
        CorrelationContribution<byte[]> contribution = contribution(key, 11);
        InteractionRef first = interaction(1, 1);
        fixture.registry.publish(first, contribution);

        CountDownLatch schemaEntered = new CountDownLatch(1);
        CountDownLatch releaseSchema = new CountDownLatch(1);
        CountDownLatch decodeEntered = new CountDownLatch(1);
        CountDownLatch releaseDecode = new CountDownLatch(1);
        EvidenceCodec<byte[]> blockingCodec = blockingCodec(
            schemaEntered,
            releaseSchema,
            decodeEntered,
            releaseDecode
        );

        try (var executor = Executors.newFixedThreadPool(4)) {
            Future<CorrelationResult<byte[]>> lookup = executor.submit(() ->
                fixture.registry.correlation(subject, key, blockingCodec)
            );
            try {
                await(schemaEntered, "schema lookup did not reach the codec");
                Future<?> schemaPhaseOperations = executor.submit(() -> {
                    ProofSubjectRef concurrent = fixture.registry.create();
                    fixture.registry.arm(concurrent, key("schema-phase"));
                    return null;
                });
                schemaPhaseOperations.get(5, TimeUnit.SECONDS);

                releaseSchema.countDown();
                await(decodeEntered, "lookup did not reach native-reference decoding");
                Future<?> decodePhaseOperations = executor.submit(() -> {
                    ProofSubjectRef concurrent = fixture.registry.create();
                    fixture.registry.arm(concurrent, key("decode-phase"));
                    return null;
                });
                Future<?> publication = executor.submit(() -> {
                    fixture.registry.publish(interaction(1, 2), contribution);
                    return null;
                });
                decodePhaseOperations.get(5, TimeUnit.SECONDS);
                publication.get(5, TimeUnit.SECONDS);

                releaseDecode.countDown();
                assertThat(lookup.get(5, TimeUnit.SECONDS))
                    .isInstanceOfSatisfying(
                        CorrelationResult.Unique.class,
                        result -> {
                            assertThat(result.interactionRef()).isEqualTo(first);
                            assertThat((byte[]) result.nativeReference())
                                .containsExactly(11);
                        }
                    );
            } finally {
                releaseSchema.countDown();
                releaseDecode.countDown();
            }
        }

        assertThat(fixture.registry.correlation(
            subject,
            key,
            NATIVE_REFERENCE_CODEC
        )).isInstanceOf(CorrelationResult.Ambiguous.class);
    }

    @Test
    void shouldPropagateCodecFailuresWithoutBlockingLaterRegistryOperations()
        throws Exception {
        Fixture fixture = fixture();
        ProofSubjectRef subject = fixture.registry.create();
        CorrelationKey key = key("codec-failures");
        fixture.registry.arm(subject, key);
        fixture.registry.publish(interaction(1, 1), contribution(key, 12));

        List<CodecFailure> failures = List.of(
            new CodecFailure(
                "schema-runtime",
                new IllegalStateException("schema runtime failure"),
                null
            ),
            new CodecFailure(
                "schema-error",
                new AssertionError("schema error"),
                null
            ),
            new CodecFailure(
                "decode-runtime",
                null,
                new IllegalArgumentException("decode runtime failure")
            ),
            new CodecFailure(
                "decode-error",
                null,
                new AssertionError("decode error")
            )
        );

        try (var executor = Executors.newSingleThreadExecutor()) {
            for (CodecFailure failure : failures) {
                assertThatThrownBy(() -> fixture.registry.correlation(
                    subject,
                    key,
                    failingCodec(failure.schemaFailure(), failure.decodeFailure())
                )).isSameAs(failure.expected());

                Future<CorrelationResult<byte[]>> recovery = executor.submit(() -> {
                    CorrelationKey recoveryKey = key(failure.name());
                    ProofSubjectRef recoverySubject = fixture.registry.create();
                    fixture.registry.arm(recoverySubject, recoveryKey);
                    fixture.registry.publish(
                        interaction(2, fixture.correlationEvents().size() + 1L),
                        contribution(recoveryKey, 13)
                    );
                    return fixture.registry.correlation(
                        recoverySubject,
                        recoveryKey,
                        NATIVE_REFERENCE_CODEC
                    );
                });
                assertThat(recovery.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(CorrelationResult.Unique.class);
            }
        }
    }

    private static Fixture fixture() {
        ScenarioJournal journal = new ScenarioJournal(() -> 0L);
        EnvironmentEventLog eventLog = new EnvironmentEventLog(
            journal,
            EnvironmentLogging.defaults()
        );
        return new Fixture(new ProofSubjectRegistry(eventLog), journal, eventLog);
    }

    private static CorrelationContribution<byte[]> contribution(
        CorrelationKey key,
        int value
    ) {
        return CorrelationContribution.capture(
            key,
            NATIVE_REFERENCE_CODEC,
            new byte[] {(byte) value}
        );
    }

    private static InteractionRef interaction(long session, long ordinal) {
        return new InteractionRef(
            new SessionId(CONNECTION, session),
            FlowDirection.CONSUMER_TO_PROVIDER,
            ordinal
        );
    }

    private static CorrelationKey key(String normalizedValue) {
        return CorrelationKey.ofDigest(
            KEY_SCHEMA,
            sha256(normalizedValue.getBytes(UTF_8))
        );
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static EvidenceCodec<byte[]> blockingCodec(
        CountDownLatch schemaEntered,
        CountDownLatch releaseSchema,
        CountDownLatch decodeEntered,
        CountDownLatch releaseDecode
    ) {
        return new EvidenceCodec<>() {
            @Override
            public EvidenceSchemaId schemaId() {
                schemaEntered.countDown();
                await(releaseSchema, "schema callback was not released");
                return NATIVE_REFERENCE_CODEC.schemaId();
            }

            @Override
            public byte[] encode(byte[] evidence) {
                return evidence;
            }

            @Override
            public byte[] decode(byte[] encodedEvidence) {
                decodeEntered.countDown();
                await(releaseDecode, "decode callback was not released");
                return encodedEvidence;
            }
        };
    }

    private static EvidenceCodec<byte[]> failingCodec(
        Throwable schemaFailure,
        Throwable decodeFailure
    ) {
        return new EvidenceCodec<>() {
            @Override
            public EvidenceSchemaId schemaId() {
                if (schemaFailure != null) {
                    throw propagate(schemaFailure);
                }
                return NATIVE_REFERENCE_CODEC.schemaId();
            }

            @Override
            public byte[] encode(byte[] evidence) {
                return evidence;
            }

            @Override
            public byte[] decode(byte[] encodedEvidence) {
                if (decodeFailure != null) {
                    throw propagate(decodeFailure);
                }
                return encodedEvidence;
            }
        };
    }

    private static EvidenceCodec<byte[]> codecThatMustNotBeCalled() {
        return new EvidenceCodec<>() {
            @Override
            public EvidenceSchemaId schemaId() {
                throw new AssertionError(
                    "Missing or ambiguous lookup invoked codec schema validation"
                );
            }

            @Override
            public byte[] encode(byte[] evidence) {
                throw new AssertionError(
                    "Missing or ambiguous lookup invoked codec encoding"
                );
            }

            @Override
            public byte[] decode(byte[] encodedEvidence) {
                throw new AssertionError(
                    "Missing or ambiguous lookup invoked codec decoding"
                );
            }
        };
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        throw (Error) failure;
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting: " + message, failure);
        }
    }

    private static EvidenceCodec<byte[]> binaryCodec(String name) {
        return new EvidenceCodec<>() {
            private final EvidenceSchemaId schema =
                new EvidenceSchemaId("system-proof-test", name, 1);

            @Override
            public EvidenceSchemaId schemaId() {
                return schema;
            }

            @Override
            public byte[] encode(byte[] evidence) {
                return evidence;
            }

            @Override
            public byte[] decode(byte[] encodedEvidence) {
                return encodedEvidence;
            }
        };
    }

    private record CodecFailure(
        String name,
        Throwable schemaFailure,
        Throwable decodeFailure
    ) {
        private Throwable expected() {
            return schemaFailure != null ? schemaFailure : decodeFailure;
        }
    }

    private record Fixture(
        ProofSubjectRegistry registry,
        ScenarioJournal journal,
        EnvironmentEventLog eventLog
    ) {
        private List<CorrelationCandidateEvent> correlationEvents() {
            return journal.snapshot().entries().stream()
                .map(entry -> entry.event())
                .filter(CorrelationCandidateEvent.class::isInstance)
                .map(CorrelationCandidateEvent.class::cast)
                .toList();
        }
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class TestComponent
        extends AbstractComponent<EmptyConfig, Void> {

        private TestComponent(String type) {
            super(
                ComponentId.component(ComponentType.of(type)),
                new EmptyConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime().build()
            );
        }
    }
}
