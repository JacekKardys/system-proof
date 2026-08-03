package io.github.jacekkardys.systemproof.environment;

import static io.github.jacekkardys.systemproof.topology.Contract.contract;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.topology.CompatibilityResult;
import io.github.jacekkardys.systemproof.topology.Connection;
import io.github.jacekkardys.systemproof.topology.ConnectionId;
import io.github.jacekkardys.systemproof.topology.Contract;
import io.github.jacekkardys.systemproof.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

class ComponentDeclarationBoundaryTest {
    private static final ComponentType COMPONENT = ComponentType.of("component");
    private static final Contract<Api> API = contract("api", Api.class);
    private static final ComponentDriver<EmptyConfig, Void> DRIVER = (component, context) ->
        ComponentRuntime.<Void>runtime().build();

    @Test
    void shouldRejectAComponentWhoseNoArgumentInitializationNeverCompleted() {
        UninitializedComponent component = new UninitializedComponent();

        assertThatThrownBy(() -> topology(component))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                UninitializedComponent.class.getName(),
                "has not completed declaration initialization"
            );
    }

    @Test
    void shouldRejectAProgrammaticComponentWithNullId() {
        ProgrammaticComponent component = new ProgrammaticComponent(
            null,
            new EmptyConfig(),
            Void.class,
            DRIVER
        );

        assertThatThrownBy(() -> topology(component))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(ProgrammaticComponent.class.getName(), "has null ComponentId");
    }

    @Test
    void shouldRejectAProgrammaticComponentWithNullConfiguration() {
        ProgrammaticComponent component = new ProgrammaticComponent(
            componentId("null-configuration"),
            null,
            Void.class,
            DRIVER
        );

        assertThatThrownBy(() -> topology(component))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Component 'component-null-configuration' has null configuration");
    }

    @Test
    void shouldRejectAProgrammaticComponentWithNullDriver() {
        ProgrammaticComponent component = new ProgrammaticComponent(
            componentId("null-driver"),
            new EmptyConfig(),
            Void.class,
            null
        );

        assertThatThrownBy(() -> topology(component))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Component 'component-null-driver' has null driver");
    }

    @Test
    void shouldRejectAProgrammaticComponentWithNullOperationsType() {
        ProgrammaticComponent component = new ProgrammaticComponent(
            componentId("null-operations"),
            new EmptyConfig(),
            null,
            DRIVER
        );

        assertThatThrownBy(() -> topology(component))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Component 'component-null-operations' has null operations type");
    }

    @Test
    void shouldAcceptACompleteProgrammaticComponentAndPreserveVoidOperationsSemantics() {
        ProgrammaticComponent component = new ProgrammaticComponent("valid");
        ProvidedPort<Api> port = component.provides("api", Invocation.INSTANCE);

        EnvironmentTopology topology = topology(component);

        assertThat(topology.components()).containsExactly(component);
        assertThat(component.ports()).containsExactly(port);
        assertThatThrownBy(() -> component.castOperations(new Object()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("declares no runtime operations");
    }

    @Test
    void shouldRejectARequiredPortRegisteredAfterTopologyConstruction() {
        ProgrammaticComponent component = new ProgrammaticComponent("late-required");
        topology(component);

        assertThatThrownBy(() -> component.requires("late", Invocation.INSTANCE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Component 'component-late-required' port declarations are frozen");
        assertThat(component.ports()).isEmpty();
    }

    @Test
    void shouldRejectAProvidedPortRegisteredAfterTopologyConstruction() {
        ProgrammaticComponent component = new ProgrammaticComponent("late-provided");
        topology(component);

        assertThatThrownBy(() -> component.provides("late", Invocation.INSTANCE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Component 'component-late-provided' port declarations are frozen");
        assertThat(component.ports()).isEmpty();
    }

    @Test
    void shouldNotAllowLatePortsToInvalidateConnectionCompleteness() {
        ProgrammaticComponent client = new ProgrammaticComponent("client");
        ProgrammaticComponent server = new ProgrammaticComponent("server");
        RequiredPort<Api> required = client.requires("api", Invocation.INSTANCE);
        ProvidedPort<Api> provided = server.provides("api", Invocation.INSTANCE);
        Connection<Api> connection = ConnectionFactory.create(required, provided);

        EnvironmentTopology topology = EnvironmentTopology.of(
            List.of(client, server),
            List.of(connection)
        );

        assertThatThrownBy(() -> client.requires("late", Invocation.INSTANCE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Component 'component-client' port declarations are frozen");
        assertThat(client.ports()).containsExactly(required);
        assertThat(topology.connectionFrom(required)).isSameAs(connection);
    }

    @Test
    void shouldLeaveDeclarationsMutableWhenTopologyValidationFails() {
        ProgrammaticComponent component = new ProgrammaticComponent("invalid-topology");
        component.requires("required", Invocation.INSTANCE);

        assertThatThrownBy(() -> topology(component))
            .hasMessageContaining("is not connected");

        ProvidedPort<Api> port = component.provides("still-mutable", Invocation.INSTANCE);
        assertThat(component.ports()).contains(port);
    }

    @Test
    void shouldSerializeConcurrentRegistrationWithValidationAndFreeze() throws Exception {
        CountDownLatch validationEntered = new CountDownLatch(1);
        CountDownLatch allowValidation = new CountDownLatch(1);
        BlockingInteraction interaction = new BlockingInteraction(
            validationEntered,
            allowValidation
        );
        ProgrammaticComponent client = new ProgrammaticComponent("concurrent-client");
        ProgrammaticComponent server = new ProgrammaticComponent("concurrent-server");
        RequiredPort<Api> required = client.requires("api", interaction);
        ProvidedPort<Api> provided = server.provides("api", Invocation.INSTANCE);
        Connection<Api> connection = new Connection<>(
            required,
            provided,
            ConnectionId.between(required, provided)
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<EnvironmentTopology> topology = executor.submit(() -> EnvironmentTopology.of(
                List.of(client, server),
                List.of(connection)
            ));
            assertThat(validationEntered.await(5, SECONDS)).isTrue();
            Future<RequiredPort<Api>> registration = executor.submit(() ->
                client.requires("late", Invocation.INSTANCE)
            );

            allowValidation.countDown();

            assertThat(topology.get(5, SECONDS).connectionFrom(required)).isSameAs(connection);
            assertThatThrownBy(() -> registration.get(5, SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage(
                    "Component 'component-concurrent-client' port declarations are frozen"
                );
            assertThat(client.ports()).containsExactly(required);
        } finally {
            allowValidation.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, SECONDS)).isTrue();
        }
    }

    private static EnvironmentTopology topology(AbstractComponent<?, ?> component) {
        return EnvironmentTopology.of(List.of(component), List.of());
    }

    private static ComponentId componentId(String qualifier) {
        return ComponentId.component(COMPONENT, qualifier);
    }

    private enum Invocation implements InteractionSpec {
        INSTANCE;

        @Override
        public String id() {
            return "invocation";
        }
    }

    private enum Http implements ProtocolSpec {
        INSTANCE;

        @Override
        public String id() {
            return "http";
        }

        @Override
        public String scheme() {
            return "http";
        }
    }

    private interface Api {}

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class UninitializedComponent
        extends AbstractComponent<EmptyConfig, Void> {
        private UninitializedComponent() {}
    }

    private static final class ProgrammaticComponent
        extends AbstractComponent<EmptyConfig, Void> {

        private ProgrammaticComponent(String qualifier) {
            this(componentId(qualifier), new EmptyConfig(), Void.class, DRIVER);
        }

        private ProgrammaticComponent(
            ComponentId id,
            EmptyConfig configuration,
            Class<Void> operationsType,
            ComponentDriver<EmptyConfig, Void> driver
        ) {
            super(id, configuration, operationsType, driver);
        }

        private RequiredPort<Api> requires(String name, InteractionSpec interaction) {
            return ComponentPortFactory.requires(
                this,
                name,
                API,
                interaction,
                Http.INSTANCE
            );
        }

        private ProvidedPort<Api> provides(String name, InteractionSpec interaction) {
            return ComponentPortFactory.provides(
                this,
                name,
                API,
                interaction,
                Http.INSTANCE
            );
        }
    }

    private record BlockingInteraction(
        CountDownLatch validationEntered,
        CountDownLatch allowValidation
    ) implements InteractionSpec {

        @Override
        public String id() {
            return "invocation";
        }

        @Override
        public CompatibilityResult isSatisfiedBy(InteractionSpec provided) {
            validationEntered.countDown();
            try {
                if (!allowValidation.await(5, SECONDS)) {
                    return CompatibilityResult.incompatible("validation release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return CompatibilityResult.incompatible("validation was interrupted");
            }
            return CompatibilityResult.accepted();
        }
    }
}
