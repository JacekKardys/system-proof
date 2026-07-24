package pl.gov.il.test.aml.ingestion.environment.component.jasmin;

import static pl.gov.il.test.harness.testcontainers.component.PortBinding.port;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.NonNull;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import pl.gov.il.test.aml.ingestion.environment.component.jasmin.JasminConfig.Driver;
import pl.gov.il.test.aml.ingestion.environment.component.jasmin.JasminConfig.Runtime;
import pl.gov.il.test.harness.model.endpoint.AmqpEndpoint;
import pl.gov.il.test.harness.model.endpoint.RedisEndpoint;
import pl.gov.il.test.harness.model.endpoint.SmppEndpoint;
import pl.gov.il.test.harness.driver.DriverContext;
import pl.gov.il.test.harness.model.LogLevel;
import pl.gov.il.test.harness.testcontainers.component.ContainerPlan;
import pl.gov.il.test.harness.testcontainers.component.PortBinding;
import pl.gov.il.test.harness.testcontainers.component.StartedContainer;
import pl.gov.il.test.harness.testcontainers.component.TestcontainersDriver;

public final class JasminTestcontainersDriver
    extends TestcontainersDriver<Runtime, Void, JasminComponent> {
    private final Driver configuration;

    public JasminTestcontainersDriver(@NonNull Driver configuration) {
        super(JasminComponent.class);
        this.configuration = configuration;
    }

    @Override
    protected ContainerPlan create(JasminComponent component, DriverContext context) {
        AmqpEndpoint rabbitMq = context.resolve(component.amqp());
        RedisEndpoint redis = context.resolve(component.redis());
        String jasminConfiguration = jasminConfiguration(component, rabbitMq, redis);
        PortBinding administrationPort = port(configuration.administrationPort());
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(configuration.image()))
            .withCopyToContainer(
                Transferable.of(jasminConfiguration.getBytes(StandardCharsets.UTF_8), 0644),
                configuration.configurationPath()
            )
            .withCommand(
                configuration.executable(),
                configuration.configurationOption(),
                configuration.configurationPath()
            )
            .waitingFor(Wait.forListeningPort().withStartupTimeout(configuration.startupTimeout()));
        return ContainerPlan.container(container)
            .provides(
                component.administration(),
                administrationPort,
                address -> InetSocketAddress.createUnresolved(address.host(), address.port())
            )
            .build();
    }

    @Override
    protected void afterStart(
        JasminComponent component,
        Void operations,
        StartedContainer container,
        DriverContext context
    ) {
        SmppEndpoint smsc = context.resolve(component.smpp());
        java.net.URI callback = context.resolve(component.sms());
        String result = new JasminBootstrap(
            container.host(),
            container.mappedPort(configuration.administrationPort()),
            smsc.host(),
            smsc.port(),
            callback.toString(),
            smsc.systemId(),
            smsc.password().reveal(),
            component.configuration().bindMode(),
            component.configuration().adminUsername(),
            component.configuration().adminPassword().reveal()
        ).configure();
        context.log(component, LogLevel.INFO, "Jasmin bootstrap completed\n" + result);
    }

    private String jasminConfiguration(
        JasminComponent component,
        AmqpEndpoint rabbitMq,
        RedisEndpoint redis
    ) {
        return """
            [amqp-broker]
            host=%s
            port=%d
            vhost=%s
            username=%s
            password=%s
            heartbeat=0
            connection_loss_retry=True
            connection_failure_retry=True
            connection_loss_retry_delay=2
            connection_loss_failure_delay=2

            [redis-client]
            host=%s
            port=%d
            dbid=%d
            password=None
            poolsize=10

            [jcli]
            bind=0.0.0.0
            port=%d
            authentication=True
            admin_username=%s
            admin_password=%s

            [deliversm-thrower]
            http_timeout=5
            retry_delay=2
            max_retries=10
            log_level=INFO
            log_file=/var/log/jasmin/deliversm-thrower.log

            [client-management]
            store_path=/etc/jasmin/store

            [router]
            store_path=/etc/jasmin/store
            """.formatted(
                rabbitMq.host(),
                rabbitMq.port(),
                rabbitMq.virtualHost(),
                rabbitMq.username(),
                rabbitMq.password().reveal(),
                redis.host(),
                redis.port(),
                redis.databaseId(),
                configuration.administrationPort(),
                component.configuration().adminUsername(),
                md5(component.configuration().adminPassword().reveal())
            );
    }

    private static String md5(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 is unavailable", exception);
        }
    }
}
