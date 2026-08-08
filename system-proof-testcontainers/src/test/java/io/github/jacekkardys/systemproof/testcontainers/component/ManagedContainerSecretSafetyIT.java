package io.github.jacekkardys.systemproof.testcontainers.component;

import static io.github.jacekkardys.systemproof.environment.ComponentPortFactory.provides;
import static io.github.jacekkardys.systemproof.testcontainers.component.PortBinding.port;
import static io.github.jacekkardys.systemproof.topology.Contract.contract;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.component.ComponentId;
import io.github.jacekkardys.systemproof.component.ComponentType;
import io.github.jacekkardys.systemproof.configuration.RuntimeConfig;
import io.github.jacekkardys.systemproof.driver.DriverContext;
import io.github.jacekkardys.systemproof.endpoint.EndpointAddress;
import io.github.jacekkardys.systemproof.environment.Environment;
import io.github.jacekkardys.systemproof.environment.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.environment.EnvironmentStartException;
import io.github.jacekkardys.systemproof.topology.Contract;
import io.github.jacekkardys.systemproof.topology.InteractionSpec;
import io.github.jacekkardys.systemproof.topology.ProtocolSpec;
import io.github.jacekkardys.systemproof.topology.ProvidedPort;

class ManagedContainerSecretSafetyIT {
    private static final String STDOUT_CANARY =
        "container-stdout-canary-b8b46a3a0d7c43e9";
    private static final String STDERR_CANARY =
        "container-stderr-canary-b91d1c5740714dd2";
    private static final String UNTERMINATED_CANARY =
        "unterminated-container-output-canary-9af84c3e";
    private static final PortBinding UNUSED_PORT = port(6543);

    @TempDir
    Path artifacts;

    @Test
    void shouldDenyUpstreamFullLogRetrievalWhenTheRealContainerExits() throws Exception {
        assertSecretSafeFailure(
            "printf '" + STDOUT_CANARY + "'; printf '" + STDERR_CANARY
                + "' >&2; exit 17",
            true,
            false,
            STDOUT_CANARY,
            STDERR_CANARY
        );
    }

    @Test
    void shouldKeepRealReadinessFailureOutputOutOfLogsAndEnvironmentArtifact()
        throws Exception {
        assertSecretSafeFailure(
            "printf '" + STDOUT_CANARY + "'; printf '" + STDERR_CANARY
                + "' >&2; sleep 30",
            false,
            true,
            STDOUT_CANARY,
            STDERR_CANARY
        );
    }

    @Test
    void shouldNotMaterializeMultimegabyteUnterminatedOutputBeforeReadinessFails()
        throws Exception {
        assertSecretSafeFailure(
            "head -c 8388608 /dev/zero | tr '\\000' 'x'; printf '"
                + UNTERMINATED_CANARY + "'; sleep 30",
            false,
            false,
            UNTERMINATED_CANARY
        );
    }

    private void assertSecretSafeFailure(
        String command,
        boolean expectsDeniedFullLogRead,
        boolean tcpReadiness,
        String... canaries
    ) throws Exception {
        FailingDriver driver = new FailingDriver(command, tcpReadiness);
        FailingComponent component = new FailingComponent(driver);
        Environment environment = new EnvironmentBuilder()
            .components(component)
            .build();

        EnvironmentStartException failure;
        String capturedLogs;
        try (LogCapture capture = new LogCapture()) {
            try {
                failure = catchThrowableOfType(
                    environment::start,
                    EnvironmentStartException.class
                );
            } finally {
                environment.close();
            }
            capturedLogs = capture.rendered();
        }

        Path environmentLog = artifacts.resolve("environment.log");
        Files.writeString(environmentLog, failure.diagnostics().content());
        String artifact = Files.readString(environmentLog);
        assertThat(driver.plan.container().getClass().getMethod("start").getDeclaringClass())
            .isEqualTo(GenericContainer.class);
        if (expectsDeniedFullLogRead) {
            assertThat(driver.plan.container().deniedFullLogReads()).isPositive();
        } else {
            assertThat(driver.plan.container().deniedFullLogReads()).isZero();
        }
        assertThat(driver.plan.container().getLogConsumers()).isEmpty();
        assertThat(failure.diagnostics().content())
            .contains("Container")
            .doesNotContain(canaries);
        assertThat(capturedLogs).doesNotContain(canaries);
        assertThat(artifact).doesNotContain(canaries);
    }

    private enum Invocation implements InteractionSpec {
        INSTANCE;

        @Override
        public String id() {
            return "invocation";
        }
    }

    private enum Tcp implements ProtocolSpec {
        INSTANCE;

        @Override
        public String id() {
            return "tcp";
        }

        @Override
        public String scheme() {
            return "tcp";
        }
    }

    private record EmptyConfig() implements RuntimeConfig {}

    private static final class FailingComponent extends AbstractComponent<EmptyConfig, Void> {
        private static final Contract<EndpointAddress> ENDPOINT = contract(
            "managed-container-endpoint",
            EndpointAddress.class
        );

        private final ProvidedPort<EndpointAddress> endpoint;

        private FailingComponent(FailingDriver driver) {
            super(
                ComponentId.component(ComponentType.of("managed-container-failure")),
                new EmptyConfig(),
                Void.class,
                driver
            );
            endpoint = provides(
                this,
                "endpoint",
                ENDPOINT,
                Invocation.INSTANCE,
                Tcp.INSTANCE
            );
        }
    }

    private static final class FailingDriver
        extends TestcontainersDriver<EmptyConfig, Void, FailingComponent> {
        private final String command;
        private final boolean tcpReadiness;
        private ContainerPlan plan;

        private FailingDriver(String command, boolean tcpReadiness) {
            super(FailingComponent.class);
            this.command = command;
            this.tcpReadiness = tcpReadiness;
        }

        @Override
        protected ContainerPlan create(FailingComponent component, DriverContext context) {
            ContainerPlan.Builder builder = ContainerPlan.container("alpine:3.20")
                .command("sh", "-c", command);
            builder = tcpReadiness
                ? builder.waitForListeningPorts(UNUSED_PORT)
                : builder.waitForHttp(UNUSED_PORT, "/ready", 204);
            plan = builder.readinessTimeout(Duration.ofSeconds(2))
                .provides(component.endpoint, UNUSED_PORT)
                .build();
            return plan;
        }
    }

    private static final class LogCapture implements AutoCloseable {
        private static final String JOURNAL_LOGGER =
            "io.github.jacekkardys.systemproof.environment.JournalSlf4jEmitter";

        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final List<Logger> loggers = new ArrayList<>();

        private LogCapture() {
            appender.start();
            attach(Logger.ROOT_LOGGER_NAME);
            attach(JOURNAL_LOGGER);
            attach("org.testcontainers");
            attach("com.github.dockerjava");
            attach("tc");
        }

        private void attach(String name) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            logger.addAppender(appender);
            loggers.add(logger);
        }

        private String rendered() {
            return appender.list.stream()
                .map(event -> event.getFormattedMessage()
                    + (event.getThrowableProxy() == null
                        ? ""
                        : ThrowableProxyUtil.asString(event.getThrowableProxy())))
                .collect(Collectors.joining(System.lineSeparator()));
        }

        @Override
        public void close() {
            loggers.forEach(logger -> logger.detachAppender(appender));
            appender.stop();
        }
    }
}
