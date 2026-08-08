package io.github.jacekkardys.systemproof.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentState;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.driver.DiagnosticSource;
import io.github.jacekkardys.systemproof.environment.state.EnvironmentState;
import io.github.jacekkardys.systemproof.environment.state.ConnectionState;
import io.github.jacekkardys.systemproof.environment.state.RoutingMode;
import io.github.jacekkardys.systemproof.environment.state.RuntimeConnectionSnapshot;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.observation.ObservationRequirement;
import io.github.jacekkardys.systemproof.topology.ConnectionDescriptor;

class RuntimeDiagnosticsSecretSafetyTest {
    private static final ComponentType TYPE = ComponentType.of("diagnostic-component");
    private static final List<String> CANARIES = List.of(
        "password=diagnostics-password-canary",
        "Bearer diagnostics.jwt.canary",
        "postgresql://db-user:db-password@db.example/aml",
        "SELECT secret FROM sms WHERE id = ? bind=sms-bind-canary",
        "SMS body canary to +48123123123",
        "https://http-user:http-password@example.test/path?token=query-canary",
        "pdu=00ff11aa-frame-canary",
        "exception-message-canary",
        "cause-message-canary",
        "suppressed-message-canary",
        "configuration-to-string-canary",
        "driver-diagnostic-canary",
        "diagnostic-source-name-canary",
        "diagnostic-source-content-canary"
    );

    @Test
    void shouldNeverInspectComponentConfigurationForStartupLoggingOrDiagnostics() {
        AtomicInteger configurationToStringCalls = new AtomicInteger();
        TestComponent component = new TestComponent(configurationToStringCalls);
        Environment environment = new EnvironmentBuilder()
            .components(component)
            .build()
            .start();

        try {
            assertThat(environment.diagnostics().content())
                .doesNotContain(CANARIES.get(10));
            assertThat(configurationToStringCalls).hasValue(0);
        } finally {
            environment.close();
        }
    }

    @Test
    void shouldCaptureOnlyBoundedRedactedSourcesByDefault() {
        AtomicInteger redactedCalls = new AtomicInteger();
        AtomicInteger sensitiveCalls = new AtomicInteger();
        AtomicInteger unsupportedCalls = new AtomicInteger();
        AtomicInteger configurationToStringCalls = new AtomicInteger();
        TestComponent component = new TestComponent(configurationToStringCalls);
        RuntimeDiagnostics runtime = runtime();
        String hostileText = String.join(System.lineSeparator(), CANARIES);
        DiagnosticSource redacted = DiagnosticSource.redacted(
            CANARIES.get(12),
            () -> {
                redactedCalls.incrementAndGet();
                return hostileText;
            },
            ignored -> "sanitized diagnostic"
        );
        DiagnosticSource sensitive = DiagnosticSource.sensitive(
            "sensitive-source",
            () -> {
                sensitiveCalls.incrementAndGet();
                return hostileText;
            }
        );
        DiagnosticSource unsupported = DiagnosticSource.unsupported(
            "unsupported-source",
            () -> {
                unsupportedCalls.incrementAndGet();
                return hostileText;
            }
        );
        runtime.add(component, List.of(redacted, sensitive, unsupported));

        EnvironmentDiagnostics captured = capture(runtime, component);

        assertThat(redactedCalls).hasValue(1);
        assertThat(sensitiveCalls).hasValue(0);
        assertThat(unsupportedCalls).hasValue(0);
        assertThat(configurationToStringCalls).hasValue(0);
        assertThat(captured.content())
            .contains("sanitized diagnostic", redacted.sourceId())
            .doesNotContain(CANARIES.toArray(String[]::new));
        assertThat(captured.toString()).doesNotContain(CANARIES.toArray(String[]::new));
        assertThat(redacted.toString()).doesNotContain(CANARIES.toArray(String[]::new));
    }

    @Test
    void shouldReportSourceFailureByTypeOnlyWithoutInspectingItsThrowableGraph() {
        TestComponent component = new TestComponent(new AtomicInteger());
        RuntimeDiagnostics runtime = runtime();
        IllegalStateException failure = new IllegalStateException(CANARIES.get(7));
        failure.initCause(new IllegalArgumentException(CANARIES.get(8)));
        failure.addSuppressed(new IllegalArgumentException(CANARIES.get(9)));
        runtime.add(
            component,
            List.of(DiagnosticSource.redacted(
                "failing-source",
                () -> { throw failure; },
                input -> input
            ))
        );

        EnvironmentDiagnostics captured = capture(runtime, component);

        assertThat(captured.content())
            .contains("[DIAGNOSTIC SOURCE CAPTURE FAILED type=IllegalStateException]")
            .doesNotContain(CANARIES.toArray(String[]::new));
    }

    @Test
    void shouldNeverInvokeSensitiveOrUnsupportedSourcesDuringFrameworkCapture() {
        AtomicInteger sensitiveCalls = new AtomicInteger();
        AtomicInteger unsupportedCalls = new AtomicInteger();
        TestComponent component = new TestComponent(new AtomicInteger());
        RuntimeDiagnostics runtime = runtime();
        runtime.add(component, List.of(
            DiagnosticSource.sensitive("sensitive-source", () -> {
                sensitiveCalls.incrementAndGet();
                return CANARIES.getFirst();
            }),
            DiagnosticSource.unsupported("unsupported-source", () -> {
                unsupportedCalls.incrementAndGet();
                return CANARIES.get(1);
            })
        ));

        assertThat(capture(runtime, component).content())
            .doesNotContain(CANARIES.toArray(String[]::new));
        assertThat(capture(runtime, component).content())
            .doesNotContain(CANARIES.toArray(String[]::new));
        assertThat(sensitiveCalls).hasValue(0);
        assertThat(unsupportedCalls).hasValue(0);
    }

    @Test
    void shouldLimitDefaultSourceCountAndInvokeEachCapturedSupplierOnce() {
        TestComponent component = new TestComponent(new AtomicInteger());
        RuntimeDiagnostics runtime = runtime();
        List<AtomicInteger> calls = new ArrayList<>();
        List<DiagnosticSource> sources = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            AtomicInteger sourceCalls = new AtomicInteger();
            calls.add(sourceCalls);
            int sourceIndex = index;
            sources.add(DiagnosticSource.redacted(
                "source-" + index,
                () -> {
                    sourceCalls.incrementAndGet();
                    return "content-" + sourceIndex;
                },
                input -> input
            ));
        }
        runtime.add(component, sources);

        String content = capture(runtime, component).content();

        assertThat(calls.subList(0, 32)).allSatisfy(counter -> assertThat(counter).hasValue(1));
        assertThat(calls.subList(32, 40)).allSatisfy(counter -> assertThat(counter).hasValue(0));
        assertThat(content).contains("[DIAGNOSTICS TRUNCATED]").doesNotContain("content-32");
    }

    @Test
    void shouldNotInvokeSourceCallbackWhileHoldingTheDiagnosticsMonitor() throws Exception {
        TestComponent component = new TestComponent(new AtomicInteger());
        RuntimeDiagnostics runtime = runtime();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            runtime.add(component, List.of(DiagnosticSource.redacted(
                "lock-probe",
                () -> {
                    try {
                        return executor.submit(() -> {
                            synchronized (runtime) {
                                return "callback completed";
                            }
                        }).get(2, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new AssertionError("diagnostics monitor retained during callback", failure);
                    }
                },
                input -> input
            )));

            assertThat(capture(runtime, component).content()).contains("callback completed");
        }
    }

    @Test
    void shouldRenderOneLifecycleSnapshotWithoutBlockingCloseOnTheSupplier()
        throws Exception {
        CountDownLatch supplierEntered = new CountDownLatch(1);
        CountDownLatch releaseSupplier = new CountDownLatch(1);
        AtomicInteger redactedCalls = new AtomicInteger();
        AtomicInteger sensitiveCalls = new AtomicInteger();
        AtomicInteger unsupportedCalls = new AtomicInteger();
        SnapshotComponent component = new SnapshotComponent(
            supplierEntered,
            releaseSupplier,
            redactedCalls,
            sensitiveCalls,
            unsupportedCalls
        );
        Environment environment = new EnvironmentBuilder()
            .components(component)
            .build()
            .start();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            CompletableFuture<EnvironmentDiagnostics> firstCapture =
                CompletableFuture.supplyAsync(environment::diagnostics, executor);
            assertThat(supplierEntered.await(2, TimeUnit.SECONDS))
                .as("redacted supplier entered after the base snapshot")
                .isTrue();

            CompletableFuture<Void> close = CompletableFuture.runAsync(
                environment::close,
                executor
            );
            try {
                close.get(2, TimeUnit.SECONDS);
                assertThat(environment.state()).isEqualTo(EnvironmentState.STOPPED);
                assertThat(firstCapture.isDone()).isFalse();
            } finally {
                releaseSupplier.countDown();
            }

            EnvironmentDiagnostics running = firstCapture.get(2, TimeUnit.SECONDS);
            assertThat(running.content())
                .contains(
                    "[STATE] environment=RUNNING",
                    "[STATE] component=diagnostic-snapshot type=diagnostic-snapshot state=RUNNING",
                    "snapshot-source-1"
                )
                .doesNotContain(
                    "[STATE] environment=STOPPED",
                    "Stopping environment",
                    "Environment stopped"
                );

            EnvironmentDiagnostics stopped = environment.diagnostics();
            assertThat(stopped.content())
                .contains(
                    "[STATE] environment=STOPPED",
                    "[STATE] component=diagnostic-snapshot type=diagnostic-snapshot state=STOPPED",
                    "Stopping environment",
                    "Environment stopped",
                    "snapshot-source-2"
                );
            assertThat(redactedCalls).hasValue(2);
            assertThat(sensitiveCalls).hasValue(0);
            assertThat(unsupportedCalls).hasValue(0);
        } finally {
            releaseSupplier.countDown();
            environment.close();
        }
    }

    @Test
    void shouldBoundComponentAndConnectionStateSectionsBeforeCallingTheirProviders() {
        RuntimeDiagnostics runtime = runtime();
        AtomicInteger componentStateCalls = new AtomicInteger();
        List<AbstractComponent<?, ?>> components = IntStream.range(0, 130)
            .<AbstractComponent<?, ?>>mapToObj(index -> new TestComponent(
                new AtomicInteger(),
                "component-" + index
            ))
            .toList();
        ConnectionDescriptor descriptor = ConnectionDescriptor.of(
            ComponentId.component(ComponentType.of("client")),
            "api",
            ComponentId.component(ComponentType.of("server")),
            "api",
            "api",
            String.class.getName(),
            "invocation",
            "http",
            "http"
        );
        RuntimeConnectionSnapshot connection = new RuntimeConnectionSnapshot(
            descriptor,
            ConnectionState.RUNNING,
            RoutingMode.DIRECT,
            ObservationRequirement.OPTIONAL,
            EffectiveObservationStatus.DISABLED,
            true,
            true
        );

        EnvironmentDiagnostics captured = runtime.render(runtime.snapshot(
            EnvironmentState.RUNNING,
            components,
            ignored -> {
                componentStateCalls.incrementAndGet();
                return ComponentState.RUNNING;
            },
            java.util.Collections.nCopies(257, connection)
        ));

        assertThat(componentStateCalls).hasValue(128);
        assertThat(captured.content())
            .contains("[COMPONENT STATE OMITTED]", "[CONNECTION STATE OMITTED]")
            .doesNotContain("component-128", "component-129");
    }

    @Test
    void shouldDeterministicallyBoundTheCompleteEnvironmentDiagnostics() {
        RuntimeDiagnostics runtime = runtime();
        ComponentType longType = ComponentType.of("t".repeat(64));
        ComponentId longId = ComponentId.component(longType, "q".repeat(64));
        String longPort = "\u00E9".repeat(64);
        ConnectionDescriptor descriptor = ConnectionDescriptor.of(
            longId,
            longPort,
            longId,
            longPort,
            "c".repeat(128),
            "a".repeat(512),
            "i".repeat(128),
            "p".repeat(128),
            "s".repeat(64)
        );
        RuntimeConnectionSnapshot connection = new RuntimeConnectionSnapshot(
            descriptor,
            ConnectionState.RUNNING,
            RoutingMode.DIRECT,
            ObservationRequirement.OPTIONAL,
            EffectiveObservationStatus.DISABLED,
            true,
            true
        );
        List<RuntimeConnectionSnapshot> connections = IntStream.range(0, 256)
            .mapToObj(ignored -> connection)
            .toList();

        String first = runtime.render(runtime.snapshot(
            EnvironmentState.RUNNING,
            List.of(),
            ignored -> { throw new AssertionError("No component state expected"); },
            connections
        )).content();
        String second = runtime.render(runtime.snapshot(
            EnvironmentState.RUNNING,
            List.of(),
            ignored -> { throw new AssertionError("No component state expected"); },
            connections
        )).content();

        assertThat(first)
            .hasSize(256 * 1024)
            .endsWith("[DIAGNOSTICS TRUNCATED]");
        assertThat(second).isEqualTo(first);
    }

    private static RuntimeDiagnostics runtime() {
        return new RuntimeDiagnostics(ScenarioJournal.withoutDiagnosticTime(), new JournalRenderer());
    }

    private static EnvironmentDiagnostics capture(
        RuntimeDiagnostics runtime,
        TestComponent component
    ) {
        return runtime.render(runtime.snapshot(
            EnvironmentState.DECLARED,
            List.of(component),
            ignored -> ComponentState.DECLARED,
            List.of()
        ));
    }

    private static final class SecretConfig implements RuntimeConfig {
        private final AtomicInteger toStringCalls;

        private SecretConfig(AtomicInteger toStringCalls) {
            this.toStringCalls = toStringCalls;
        }

        @Override
        public String toString() {
            toStringCalls.incrementAndGet();
            return CANARIES.get(10);
        }
    }

    private static final class TestComponent extends AbstractComponent<SecretConfig, Void> {
        private TestComponent(AtomicInteger configurationToStringCalls) {
            this(configurationToStringCalls, null);
        }

        private TestComponent(
            AtomicInteger configurationToStringCalls,
            String qualifier
        ) {
            super(
                qualifier == null
                    ? ComponentId.component(TYPE)
                    : ComponentId.component(TYPE, qualifier),
                new SecretConfig(configurationToStringCalls),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime().build()
            );
        }
    }

    private record SnapshotConfig() implements RuntimeConfig {}

    private static final class SnapshotComponent
        extends AbstractComponent<SnapshotConfig, Void> {
        private SnapshotComponent(
            CountDownLatch supplierEntered,
            CountDownLatch releaseSupplier,
            AtomicInteger redactedCalls,
            AtomicInteger sensitiveCalls,
            AtomicInteger unsupportedCalls
        ) {
            super(
                ComponentId.component(ComponentType.of("diagnostic-snapshot")),
                new SnapshotConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime()
                    .diagnostics(DiagnosticSource.redacted(
                        "snapshot-source",
                        () -> {
                            int capture = redactedCalls.incrementAndGet();
                            supplierEntered.countDown();
                            await(releaseSupplier);
                            return "snapshot-source-" + capture;
                        },
                        input -> input
                    ))
                    .diagnostics(DiagnosticSource.sensitive(
                        "snapshot-sensitive",
                        () -> {
                            sensitiveCalls.incrementAndGet();
                            return CANARIES.getFirst();
                        }
                    ))
                    .diagnostics(DiagnosticSource.unsupported(
                        "snapshot-unsupported",
                        () -> {
                            unsupportedCalls.incrementAndGet();
                            return CANARIES.get(1);
                        }
                    ))
                    .build()
            );
        }

        private static void await(CountDownLatch release) {
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("diagnostic supplier was not released");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("diagnostic supplier was interrupted", interrupted);
            }
        }
    }
}
