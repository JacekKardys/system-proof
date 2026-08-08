package io.github.jacekkardys.systemproof.environment;

import static io.github.jacekkardys.systemproof.endpoint.EndpointBinding.binding;
import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.provides;
import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.requiresAtStartup;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;
import io.github.jacekkardys.systemproof.proof.ProofExecution;
import io.github.jacekkardys.systemproof.proof.ProofOutcome;
import io.github.jacekkardys.systemproof.proof.ProofPlan;
import io.github.jacekkardys.systemproof.proof.ProofResult;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;
import org.junit.jupiter.api.Test;

class ProofObservationScopeTest {
    @Test
    void shouldNotInvokeAnUnrelatedFailingObservationProvider() {
        ScopeRouteProvider route = new ScopeRouteProvider();
        try (ScopeEnvironment environment = start(route)) {
            route.reset();
            route.failUnrelated.set(true);

            ProofResult result = proveRelevantConnection(environment);

            assertThat(result.outcome()).isEqualTo(ProofOutcome.PROVED);
            assertThat(route.relevantCalls).hasValue(2);
            assertThat(route.unrelatedCalls).hasValue(0);
        }
    }

    @Test
    void shouldNotBlockOnAnUnrelatedObservationProvider() throws Exception {
        ScopeRouteProvider route = new ScopeRouteProvider();
        try (ScopeEnvironment environment = start(route);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            route.reset();
            route.blockUnrelated.set(true);
            Future<ProofResult> proof = executor.submit(() ->
                proveRelevantConnection(environment)
            );
            try {
                assertThat(proof.get(5, TimeUnit.SECONDS).outcome())
                    .isEqualTo(ProofOutcome.PROVED);
                assertThat(route.unrelatedEntered.getCount()).isEqualTo(1L);
                assertThat(route.unrelatedCalls).hasValue(0);
            } finally {
                route.unrelatedRelease.countDown();
            }
        }
    }

    private static ProofResult proveRelevantConnection(ScopeEnvironment environment) {
        ProofSubjectRef subject = environment.proofSubjects().create();
        ConnectionId relevant = environment.connections().stream()
            .map(connection -> connection.id())
            .filter(id -> id.toString().contains(".required->"))
            .findFirst()
            .orElseThrow();
        ProofPlan plan = ProofPlan.builder(
            "scoped-observation-refresh",
            "Scoped observation refresh",
            subject,
            Duration.ofSeconds(5)
        ).prerequisite(
            "prerequisite",
            environment.proofs().satisfiedPrerequisite()
        ).observation(
            "relevant-observation",
            relevant,
            ProofTestFixture.PROFILE
        ).build();
        ProofExecution execution = environment.proofs().activate(plan);
        execution.runStimulus(() -> {});
        return execution.evaluate();
    }

    private static ScopeEnvironment start(ScopeRouteProvider route) {
        ScopeClient client = new ScopeClient();
        ScopeServer server = new ScopeServer();
        ScopeEnvironment environment = new EnvironmentBuilder()
            .components(client, server)
            .connect(client.required, server.required)
            .connect(client.unrelated, server.unrelated)
            .build((topology, logging) -> new ScopeEnvironment(
                topology,
                logging,
                ConnectionRouting.routed(
                    ProofTestFixture.API,
                    ProofTestFixture.PROFILE,
                    route
                )
            ));
        environment.start();
        return environment;
    }

    private record ScopeConfig() implements RuntimeConfig {}

    private static final class ScopeClient
        extends AbstractComponent<ScopeConfig, Void> {
        private final RequiredPort<String> required;
        private final RequiredPort<String> unrelated;

        private ScopeClient() {
            super(
                ComponentId.component(ComponentType.of("scope-client")),
                new ScopeConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime().build()
            );
            required = requiresAtStartup(
                this,
                "required",
                ProofTestFixture.API,
                ScopeInvocation.INSTANCE,
                ScopeHttp.INSTANCE
            );
            unrelated = requiresAtStartup(
                this,
                "unrelated",
                ProofTestFixture.API,
                ScopeInvocation.INSTANCE,
                ScopeHttp.INSTANCE
            );
        }
    }

    private static final class ScopeServer
        extends AbstractComponent<ScopeConfig, Void> {
        private final ProvidedPort<String> required;
        private final ProvidedPort<String> unrelated;

        private ScopeServer() {
            super(
                ComponentId.component(ComponentType.of("scope-server")),
                new ScopeConfig(),
                Void.class,
                (component, context) -> ComponentRuntime.<Void>runtime()
                    .provides(
                        ((ScopeServer) component).required,
                        binding("scope-required", "scope-required-external")
                    ).provides(
                        ((ScopeServer) component).unrelated,
                        binding("scope-unrelated", "scope-unrelated-external")
                    ).build()
            );
            required = provides(
                this,
                "required",
                ProofTestFixture.API,
                ScopeInvocation.INSTANCE,
                ScopeHttp.INSTANCE
            );
            unrelated = provides(
                this,
                "unrelated",
                ProofTestFixture.API,
                ScopeInvocation.INSTANCE,
                ScopeHttp.INSTANCE
            );
        }
    }

    private enum ScopeInvocation implements InteractionSpec {
        INSTANCE;

        @Override
        public String id() {
            return "scope-invocation";
        }
    }

    private enum ScopeHttp implements ProtocolSpec {
        INSTANCE;

        @Override
        public String id() {
            return "scope-http";
        }

        @Override
        public String scheme() {
            return "http";
        }
    }

    private static final class ScopeRouteProvider
        implements ConnectionRouteProvider<String>, SemanticControlRouteCapability {
        private final AtomicInteger relevantCalls = new AtomicInteger();
        private final AtomicInteger unrelatedCalls = new AtomicInteger();
        private final AtomicBoolean failUnrelated = new AtomicBoolean();
        private final AtomicBoolean blockUnrelated = new AtomicBoolean();
        private final CountDownLatch unrelatedEntered = new CountDownLatch(1);
        private final CountDownLatch unrelatedRelease = new CountDownLatch(1);

        @Override
        public ConnectionRoute<String> prepare(ConnectionRouteContext<String> context) {
            boolean relevant = context.connection().id().toString().contains(".required->");
            return ConnectionRoute.routed(
                binding(
                    relevant ? "scope-relevant-route" : "scope-unrelated-route",
                    relevant ? "scope-relevant-external" : "scope-unrelated-external"
                ),
                () -> sample(relevant),
                new ScopeRouteResource()
            );
        }

        private EffectiveObservationStatus sample(boolean relevant) {
            if (relevant) {
                relevantCalls.incrementAndGet();
                return EffectiveObservationStatus.ACTIVE;
            }
            unrelatedCalls.incrementAndGet();
            if (failUnrelated.get()) {
                throw new UnrelatedObservationFailure();
            }
            if (blockUnrelated.get()) {
                unrelatedEntered.countDown();
                try {
                    if (!unrelatedRelease.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Unrelated observation provider remained blocked");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(
                        "Interrupted while blocking unrelated observation provider",
                        interrupted
                    );
                }
            }
            return EffectiveObservationStatus.ACTIVE;
        }

        private void reset() {
            relevantCalls.set(0);
            unrelatedCalls.set(0);
        }
    }

    private static final class ScopeRouteResource
        implements AutoCloseable, SemanticControlRouteCapability {
        @Override
        public void close() {}
    }

    private static final class ScopeEnvironment extends Environment {
        private ScopeEnvironment(
            EnvironmentTopology topology,
            EnvironmentLogging logging,
            ConnectionRouting routing
        ) {
            super(topology, logging, routing);
        }
    }

    private static final class UnrelatedObservationFailure extends RuntimeException {}
}
