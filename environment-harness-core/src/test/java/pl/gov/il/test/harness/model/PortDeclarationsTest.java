package pl.gov.il.test.harness.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static pl.gov.il.test.harness.model.AbstractComponent.component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;
import pl.gov.il.test.harness.driver.ComponentDriver;

class PortDeclarationsTest {
    private static final ComponentType SERVICE = ComponentType.of("service");
    private static final ComponentDriver<EmptyConfig, Void> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldMaterializeAnnotatedRequiredAndProvidedPorts() {
        ClientComponent client = component(
            ClientComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        );
        ServerComponent server = component(
            ServerComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        );

        assertThat(client.ports()).containsExactly(client.api);
        assertThat(server.ports()).containsExactly(server.api);
        assertThat(client.api.direction()).isEqualTo(PortDirection.REQUIRED);
        assertThat(client.api.requiredAtStartup()).isTrue();
        assertThat(server.api.direction()).isEqualTo(PortDirection.PROVIDED);
        assertThat(server.api.name()).isEqualTo("api");
        assertThat(client.api.contract()).isEqualTo(server.api.contract());
        assertThat(client.api.interaction().id()).isEqualTo("invocation");
        assertThat(client.api.protocol().id()).isEqualTo("http");
        assertThat(client.api.protocol().scheme()).isEqualTo("http");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldRejectConnectionsWhoseGenericTypesDoNotMatch() {
        MismatchedContractComponent client = component(
            MismatchedContractComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        );
        ServerComponent server = component(
            ServerComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        );

        assertThatThrownBy(() -> Connection.connect(
            (RequiredPort) client.api,
            (ProvidedPort) server.api
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("contract type mismatch")
            .hasMessageContaining(OtherApi.class.getName())
            .hasMessageContaining(Api.class.getName());
    }

    @Test
    void shouldRejectAFieldWithoutConcreteGenericType() {
        assertThatThrownBy(() -> component(
            RawContractComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Port field '")
            .hasMessageContaining("api")
            .hasMessageContaining("must declare one concrete port contract type");
    }

    @Test
    void shouldRejectPortWithoutCommunication() {
        assertThatThrownBy(() -> component(
            MissingCommunicationComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must declare @Communication");
    }

    @Test
    void shouldRejectStartupPrerequisiteOnAProvidedPortField() {
        assertThatThrownBy(() -> component(
            InvalidStartupPrerequisiteComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("@StartupPrerequisite requires field type RequiredPort");
    }

    @Test
    void shouldMaterializeCustomComposedCommunication() {
        CustomCommunicationComponent component = component(
            CustomCommunicationComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        );

        assertThat(component.api.interaction().id()).isEqualTo("invocation");
        assertThat(component.api.protocol().id()).isEqualTo("grpc");
        assertThat(component.api.protocol().scheme()).isEqualTo("grpc");
    }

    @Test
    void shouldRejectMultipleCommunicationAnnotations() {
        assertThatThrownBy(() -> component(
            AmbiguousCommunicationComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must declare exactly one communication annotation");
    }

    private interface Api {}

    private interface OtherApi {}

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class ClientComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @StartupPrerequisite
        @Communication.Http
        private RequiredPort<Api> api;

        private ClientComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class ServerComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @Communication.Http
        private ProvidedPort<Api> api;

        private ServerComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class MismatchedContractComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @Communication(interaction = "invocation", protocol = "http")
        private RequiredPort<OtherApi> api;

        private MismatchedContractComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class InvalidStartupPrerequisiteComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @StartupPrerequisite
        @Communication(interaction = "invocation", protocol = "http")
        private ProvidedPort<Api> input;

        private InvalidStartupPrerequisiteComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    @SuppressWarnings("rawtypes")
    private static final class RawContractComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @Communication(interaction = "invocation", protocol = "http")
        private RequiredPort api;

        private RawContractComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class MissingCommunicationComponent
        extends AbstractComponent<EmptyConfig, Void> {
        private RequiredPort<Api> api;

        private MissingCommunicationComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    @Communication(interaction = "invocation", protocol = "grpc")
    private @interface Grpc {}

    private static final class CustomCommunicationComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @Grpc
        private ProvidedPort<Api> api;

        private CustomCommunicationComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class AmbiguousCommunicationComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @Communication.Http
        @Communication(interaction = "invocation", protocol = "custom-http")
        private ProvidedPort<Api> api;

        private AmbiguousCommunicationComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }
}
