package io.github.jacekkardys.systemproof.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.model.Contract.contract;

import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.model.AbstractComponent;
import io.github.jacekkardys.systemproof.model.Component;
import io.github.jacekkardys.systemproof.model.RuntimeConfig;
import io.github.jacekkardys.systemproof.model.ComponentId;
import io.github.jacekkardys.systemproof.model.ComponentType;
import io.github.jacekkardys.systemproof.model.Contract;
import io.github.jacekkardys.systemproof.model.Environment;
import io.github.jacekkardys.systemproof.construction.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.model.InteractionSpec;
import io.github.jacekkardys.systemproof.model.ProtocolSpec;
import io.github.jacekkardys.systemproof.model.ProvidedPort;
import io.github.jacekkardys.systemproof.model.RequiredPort;

class ComponentStartPlanTest {
    private static final ComponentType NODE = ComponentType.of("node");
    private static final Contract<Api> API = contract("api", Api.class);
    private static final ComponentDriver<EmptyConfig, Void> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldOrderRuntimeBindingDependenciesBeforeConsumers() {
        Node database = new Node("database", false, true, false);
        Node service = new Node("service", true, true, true);
        Node client = new Node("client", true, false, true);
        Environment environment = new EnvironmentBuilder()
            .components(client, service, database)
            .connect(client.required, service.provided)
            .connect(service.required, database.provided)
            .build();

        assertThat(ComponentStartPlan.order(
            java.util.List.of(client, service, database),
            environment::connectionFrom
        ))
            .extracting(Component::id)
            .extracting(ComponentId::toString)
            .containsExactly("node-database", "node-service", "node-client");
    }

    @Test
    void shouldAllowCommunicationCyclesWithoutStartupCycles() {
        Node first = new Node("first", true, true, false);
        Node second = new Node("second", true, true, false);

        Environment environment = new EnvironmentBuilder()
            .components(first, second)
            .connect(first.required, second.provided)
            .connect(second.required, first.provided)
            .build();

        assertThat(environment.connections()).hasSize(2);
        assertThat(ComponentStartPlan.order(
            java.util.List.of(first, second),
            environment::connectionFrom
        )).containsExactly(first, second);
    }

    @Test
    void shouldDetectOnlyRealStartupDependencyCycles() {
        Node first = new Node("first", true, true, true);
        Node second = new Node("second", true, true, true);

        assertThatThrownBy(() -> new EnvironmentBuilder()
            .components(first, second)
            .connect(first.required, second.provided)
            .connect(second.required, first.provided)
            .build())
            .hasMessageContaining("startup dependency cycle");
    }

    private enum Invocation implements InteractionSpec {
        INSTANCE;
        public String id() { return "invocation"; }
    }

    private enum Http implements ProtocolSpec {
        INSTANCE;
        public String id() { return "http"; }
        public String scheme() { return "http"; }
    }

    private interface Api {}
    private record EmptyConfig() implements RuntimeConfig {}

    private static final class Node extends AbstractComponent<EmptyConfig, Void> {
        private final RequiredPort<Api> required;
        private final ProvidedPort<Api> provided;

        private Node(
            String qualifier,
            boolean requires,
            boolean provides,
            boolean requiredAtStartup
        ) {
            super(ComponentId.component(NODE, qualifier), new EmptyConfig(), Void.class, UNUSED);
            required = requires
                ? requiredAtStartup
                    ? requiresAtStartup("required", API, Invocation.INSTANCE, Http.INSTANCE)
                    : requires("required", API, Invocation.INSTANCE, Http.INSTANCE)
                : null;
            provided = provides ? provides("provided", API, Invocation.INSTANCE, Http.INSTANCE) : null;
        }

    }
}
