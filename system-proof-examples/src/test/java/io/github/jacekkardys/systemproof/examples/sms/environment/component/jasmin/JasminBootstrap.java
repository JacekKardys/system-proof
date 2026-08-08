package io.github.jacekkardys.systemproof.examples.sms.environment.component.jasmin;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.awaitility.Awaitility;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.jasmin.JasminComponent.SmppBindMode;

@RequiredArgsConstructor
final class JasminBootstrap {
    private static final String SMPP_CONNECTOR_ID = "sp_test_smsc";
    private static final String HTTP_CONNECTOR_ID = "sp_ingestion";

    private final String host;
    private final int port;
    private final String smppHost;
    private final int smppPort;
    private final String callbackUrl;
    private final String smppSystemId;
    private final String smppPassword;
    private final SmppBindMode bindMode;
    private final String jcliUsername;
    private final String jcliPassword;
    private final Duration readinessTimeout;

    public String configure() {
        try {
            awaitJcliReady();
            withClient(client -> {
                client.commandToleratingFailure("httpccm -r " + HTTP_CONNECTOR_ID);
                client.interactive("httpccm -a", List.of(
                    "cid " + HTTP_CONNECTOR_ID,
                    "url " + callbackUrl,
                    "method POST",
                    "ok"
                ));
                client.commandToleratingFailure("morouter -f");
                client.interactive("morouter -a", List.of(
                    "type DefaultRoute",
                    "connector http(" + HTTP_CONNECTOR_ID + ")",
                    "ok"
                ));
                client.commandToleratingFailure("smppccm -0 " + SMPP_CONNECTOR_ID);
                client.commandToleratingFailure("smppccm -r " + SMPP_CONNECTOR_ID);
                client.interactive("smppccm -a", List.of(
                    "cid " + SMPP_CONNECTOR_ID,
                    "host " + smppHost,
                    "port " + smppPort,
                    "username " + smppSystemId,
                    "password " + smppPassword,
                    "bind " + bindMode.jasminValue(),
                    "bind_to 10",
                    "elink_interval 10",
                    "res_to 5",
                    "con_loss_retry yes",
                    "con_loss_delay 2",
                    "con_fail_retry yes",
                    "con_fail_delay 2",
                    "requeue_delay 2",
                    "ok"
                ));
                client.command("smppccm -1 " + SMPP_CONNECTOR_ID);
                client.command("persist");
            });
            awaitBound();
            return diagnosticSummary();
        } catch (RuntimeException failure) {
            throw new IllegalStateException("Jasmin bootstrap failed", failure);
        }
    }

    String diagnosticSummary() {
        return "httpConnector=" + HTTP_CONNECTOR_ID
            + " method=POST callbackConfigured=true"
            + " smppConnector=" + SMPP_CONNECTOR_ID
            + " smppState=" + bindMode.boundState();
    }

    private void awaitBound() {
        Awaitility.await("Jasmin SMPP connector bound")
            .atMost(readinessTimeout)
            .pollInterval(Duration.ofMillis(500))
            .ignoreExceptions()
            .until(() -> {
                String connectors = queryWithClient(client -> client.command("smppccm -l"));
                return connectors.contains(SMPP_CONNECTOR_ID)
                    && connectors.contains(bindMode.boundState());
            });
    }

    private void awaitJcliReady() {
        Awaitility.await("Jasmin jCli ready")
            .atMost(readinessTimeout)
            .pollInterval(Duration.ofMillis(250))
            .ignoreExceptions()
            .until(() -> {
                withClient(client -> {});
                return true;
            });
    }

    private void withClient(ClientAction action) {
        try (JasminCliClient client = new JasminCliClient(
            host,
            port,
            Duration.ofSeconds(15),
            jcliUsername,
            jcliPassword
        )) {
            action.execute(client);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot close the Jasmin jCli client", exception);
        }
    }

    private String queryWithClient(ClientQuery query) {
        try (JasminCliClient client = new JasminCliClient(
            host,
            port,
            Duration.ofSeconds(15),
            jcliUsername,
            jcliPassword
        )) {
            return query.execute(client);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot close the Jasmin jCli client", exception);
        }
    }

    @FunctionalInterface
    private interface ClientAction {
        void execute(JasminCliClient client);
    }

    @FunctionalInterface
    private interface ClientQuery {
        String execute(JasminCliClient client);
    }
}
