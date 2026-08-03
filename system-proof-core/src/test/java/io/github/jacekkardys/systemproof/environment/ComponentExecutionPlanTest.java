package io.github.jacekkardys.systemproof.environment;

import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.provides;
import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.requires;
import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.requiresAtStartup;
import static io.github.jacekkardys.systemproof.topology.Contract.contract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.Component;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.topology.Contract;
import io.github.jacekkardys.systemproof.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.topology.RequiredPort;

class ComponentExecutionPlanTest {
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

        ComponentExecutionPlan plan = ComponentExecutionPlan.create(
            List.of(client, service, database),
            environment::connectionFrom
        );

        assertThat(plan.components()).containsExactly(client, service, database);
        assertThat(plan.startOrder())
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

        ComponentExecutionPlan plan = ComponentExecutionPlan.create(
            List.of(first, second),
            environment::connectionFrom
        );

        assertThat(environment.connections()).hasSize(2);
        assertThat(plan.startOrder()).containsExactly(first, second);
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

    @Test
    void shouldRejectNullCollectionsAndNullComponents() {
        Node component = new Node("component", false, false, false);

        assertThatNullPointerException()
            .isThrownBy(() -> new ComponentExecutionPlan(null, List.of()))
            .withMessage("components must not be null");
        assertThatNullPointerException()
            .isThrownBy(() -> new ComponentExecutionPlan(List.of(), null))
            .withMessage("startOrder must not be null");
        assertThatNullPointerException()
            .isThrownBy(() -> new ComponentExecutionPlan(
                Arrays.asList(component, null),
                List.of(component)
            ))
            .withMessage("components must not contain null components");
        assertThatNullPointerException()
            .isThrownBy(() -> new ComponentExecutionPlan(
                List.of(component),
                Arrays.asList(component, null)
            ))
            .withMessage("startOrder must not contain null components");
    }

    @Test
    void shouldRejectDuplicatesIncompleteOrdersAndComponentsOutsideThePlan() {
        Node first = new Node("first", false, false, false);
        Node second = new Node("second", false, false, false);
        Node outside = new Node("outside", false, false, false);

        assertThatThrownBy(() -> new ComponentExecutionPlan(
            List.of(first, first),
            List.of(first, first)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("components must not contain duplicate component 'node-first'");
        assertThatThrownBy(() -> new ComponentExecutionPlan(
            List.of(first, second),
            List.of(first, first)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("startOrder must not contain duplicate component 'node-first'");
        assertThatThrownBy(() -> new ComponentExecutionPlan(
            List.of(first, second),
            List.of(first)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("startOrder must contain every component exactly once");
        assertThatThrownBy(() -> new ComponentExecutionPlan(
            List.of(first, second),
            List.of(first, outside)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(
                "startOrder contains component 'node-outside' outside the execution plan"
            );
    }

    @Test
    void shouldDetachItsCollectionsFromCallersAndExposeImmutableViews() {
        Node first = new Node("first", false, false, false);
        Node second = new Node("second", false, false, false);
        List<AbstractComponent<?, ?>> components = new ArrayList<>(List.of(first, second));
        List<AbstractComponent<?, ?>> startOrder = new ArrayList<>(List.of(second, first));
        ComponentExecutionPlan plan = new ComponentExecutionPlan(components, startOrder);

        components.clear();
        startOrder.clear();

        assertThat(plan.components()).containsExactly(first, second);
        assertThat(plan.startOrder()).containsExactly(second, first);
        assertThatThrownBy(() -> plan.components().add(first))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.startOrder().clear())
            .isInstanceOf(UnsupportedOperationException.class);
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
                    ? requiresAtStartup(
                        this,
                        "required",
                        API,
                        Invocation.INSTANCE,
                        Http.INSTANCE
                    )
                    : requires(
                        this,
                        "required",
                        API,
                        Invocation.INSTANCE,
                        Http.INSTANCE
                    )
                : null;
            provided = provides
                ? provides(this, "provided", API, Invocation.INSTANCE, Http.INSTANCE)
                : null;
        }
    }
}
