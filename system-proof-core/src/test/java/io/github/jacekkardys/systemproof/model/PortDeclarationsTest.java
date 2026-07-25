package io.github.jacekkardys.systemproof.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static io.github.jacekkardys.systemproof.model.AbstractComponent.component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;

class PortDeclarationsTest {
    private static final ComponentType SERVICE = ComponentType.of("service");
    private static final ComponentDriver<EmptyConfig, Void> UNUSED = (component, context) -> {
        throw new AssertionError("Driver should not run");
    };

    @Test
    void shouldConnectDifferentLocalNamesWithTheSameExplicitContract() {
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

        Connection<Api> connection = Connection.connect(client.outboundApi, server.inboundApi);

        assertThat(client.ports()).containsExactly(client.outboundApi);
        assertThat(server.ports()).containsExactly(server.inboundApi);
        assertThat(client.outboundApi.direction()).isEqualTo(PortDirection.REQUIRED);
        assertThat(client.outboundApi.requiredAtStartup()).isTrue();
        assertThat(server.inboundApi.direction()).isEqualTo(PortDirection.PROVIDED);
        assertThat(client.outboundApi.name()).isEqualTo("outboundApi");
        assertThat(server.inboundApi.name()).isEqualTo("inboundApi");
        assertThat(client.outboundApi.contractId()).isEqualTo("api");
        assertThat(client.outboundApi.contract()).isEqualTo(server.inboundApi.contract());
        assertThat(client.outboundApi.interaction().id()).isEqualTo("invocation");
        assertThat(client.outboundApi.protocol().id()).isEqualTo("http");
        assertThat(client.outboundApi.protocol().scheme()).isEqualTo("http");
        assertThat(connection.from()).isSameAs(client.outboundApi);
        assertThat(connection.to()).isSameAs(server.inboundApi);
    }

    @Test
    void shouldRejectEqualLocalNamesWithDifferentExplicitContracts() {
        DifferentContractClient client = component(
            DifferentContractClient.class,
            null,
            new EmptyConfig(),
            UNUSED
        );
        DifferentContractServer server = component(
            DifferentContractServer.class,
            null,
            new EmptyConfig(),
            UNUSED
        );

        assertThat(client.api.name()).isEqualTo(server.api.name());
        assertThatThrownBy(() -> Connection.connect(client.api, server.api))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("component='service'")
            .hasMessageContaining("localName='api'")
            .hasMessageContaining("contractId='client-api'")
            .hasMessageContaining("contractId='server-api'")
            .hasMessageContaining("contract id mismatch");
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
            (ProvidedPort) server.inboundApi
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("contract type mismatch")
            .hasMessageContaining("component='service'")
            .hasMessageContaining("localName='api'")
            .hasMessageContaining("localName='inboundApi'")
            .hasMessageContaining("contractId='api'")
            .hasMessageContaining(OtherApi.class.getName())
            .hasMessageContaining(Api.class.getName());
    }

    @Test
    void shouldRejectMismatchedInteractionWithCompletePortDiagnostics() {
        MismatchedInteractionComponent client = component(
            MismatchedInteractionComponent.class,
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

        assertThatThrownBy(() -> Connection.connect(client.api, server.inboundApi))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "component='service'",
                "localName='api'",
                "localName='inboundApi'",
                "contractId='api'",
                "contractType='" + Api.class.getName() + "'",
                "interaction='messaging'",
                "interaction='invocation'",
                "protocol='http'"
            )
            .hasMessageContaining("required interaction 'messaging'");
    }

    @Test
    void shouldRejectMismatchedProtocolWithCompletePortDiagnostics() {
        MismatchedProtocolComponent client = component(
            MismatchedProtocolComponent.class,
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

        assertThatThrownBy(() -> Connection.connect(client.api, server.inboundApi))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "component='service'",
                "localName='api'",
                "localName='inboundApi'",
                "contractId='api'",
                "contractType='" + Api.class.getName() + "'",
                "interaction='invocation'",
                "protocol='grpc'",
                "protocol='http'"
            )
            .hasMessageContaining("required protocol 'grpc'");
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
    void shouldRejectPortWithoutExplicitContract() {
        assertThatThrownBy(() -> component(
            MissingContractComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Port field '")
            .hasMessageContaining("api")
            .hasMessageContaining("must declare @PortContract");
    }

    @Test
    void shouldRejectBlankExplicitContract() {
        assertThatThrownBy(() -> component(
            BlankContractComponent.class,
            null,
            new EmptyConfig(),
            UNUSED
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Port field '")
            .hasMessageContaining("api")
            .hasMessageContaining("must declare a non-blank @PortContract value");
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
        @PortContract("api")
        @Communication.Http
        private RequiredPort<Api> outboundApi;

        private ClientComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class ServerComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @PortContract("api")
        @Communication.Http
        private ProvidedPort<Api> inboundApi;

        private ServerComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class MismatchedContractComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @PortContract("api")
        @Communication(interaction = "invocation", protocol = "http")
        private RequiredPort<OtherApi> api;

        private MismatchedContractComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class MismatchedInteractionComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @PortContract("api")
        @Communication(interaction = "messaging", protocol = "http")
        private RequiredPort<Api> api;

        private MismatchedInteractionComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class MismatchedProtocolComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @PortContract("api")
        @Communication(interaction = "invocation", protocol = "grpc")
        private RequiredPort<Api> api;

        private MismatchedProtocolComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class DifferentContractClient
        extends AbstractComponent<EmptyConfig, Void> {
        @PortContract("client-api")
        @Communication.Http
        private RequiredPort<Api> api;

        private DifferentContractClient() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class DifferentContractServer
        extends AbstractComponent<EmptyConfig, Void> {
        @PortContract("server-api")
        @Communication.Http
        private ProvidedPort<Api> api;

        private DifferentContractServer() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class InvalidStartupPrerequisiteComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @StartupPrerequisite
        @PortContract("api")
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
        @PortContract("api")
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
        @PortContract("api")
        private RequiredPort<Api> api;

        private MissingCommunicationComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class MissingContractComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @Communication.Http
        private RequiredPort<Api> api;

        private MissingContractComponent() {}

        @Override
        protected ComponentType componentType() {
            return SERVICE;
        }
    }

    private static final class BlankContractComponent
        extends AbstractComponent<EmptyConfig, Void> {
        @PortContract("   ")
        @Communication.Http
        private RequiredPort<Api> api;

        private BlankContractComponent() {}

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
        @PortContract("api")
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
        @PortContract("api")
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
