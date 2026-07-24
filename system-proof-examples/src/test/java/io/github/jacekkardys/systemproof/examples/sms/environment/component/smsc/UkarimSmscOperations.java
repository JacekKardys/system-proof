package io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc;

import static java.net.http.HttpClient.Redirect.NEVER;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import io.github.jacekkardys.systemproof.examples.sms.environment.domain.TestSms;

/** Thin control-plane adapter for the upstream ukarim/smscsim web form. */
public final class UkarimSmscOperations {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String SUCCESS_LOCATION_MESSAGE = "message=MO+message+was+successfully+sent";

    private final URI controlEndpoint;
    private final String expectedSystemId;
    private final HttpClient httpClient;
    private final Supplier<String> componentState;
    private final Supplier<String> environmentEvents;
    private volatile String lastControlPage = "<not requested>";
    private volatile String lastPostResult = "<not sent>";

    public UkarimSmscOperations(
        URI controlEndpoint,
        String expectedSystemId,
        Supplier<String> componentState,
        Supplier<String> environmentEvents
    ) {
        this.controlEndpoint = Objects.requireNonNull(controlEndpoint, "controlEndpoint must not be null");
        this.expectedSystemId = requireText(expectedSystemId, "expectedSystemId");
        this.componentState = Objects.requireNonNull(componentState, "componentState must not be null");
        this.environmentEvents = Objects.requireNonNull(environmentEvents, "environmentEvents must not be null");
        httpClient = HttpClient.newBuilder()
            .connectTimeout(REQUEST_TIMEOUT)
            .followRedirects(NEVER)
            .build();
    }

    public ScenarioControlId send(TestSms message) {
        Objects.requireNonNull(message, "message must not be null");
        awaitBoundSession();
        String body = formField("sender", message.sourceAddress())
            + "&" + formField("recipient", message.destinationAddress())
            + "&" + formField("system_id", expectedSystemId)
            + "&" + formField("message", message.content());
        HttpRequest request = HttpRequest.newBuilder(controlEndpoint)
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = exchange(request, "send MO through the SMSC control plane");
        String location = response.headers().firstValue("Location").orElse("<missing>");
        lastPostResult = "HTTP " + response.statusCode() + ", Location=" + location;
        if (response.statusCode() != 303 || !location.contains(SUCCESS_LOCATION_MESSAGE)) {
            throw new IllegalStateException(
                "SMSC control plane did not confirm MO submission: " + lastPostResult + "; " + diagnostics()
            );
        }
        return new ScenarioControlId("smsc-control-" + UUID.randomUUID());
    }

    private void awaitBoundSession() {
        try {
            Awaitility.await("SMSC control plane to expose bound system ID " + expectedSystemId)
                .atMost(DEFAULT_TIMEOUT)
                .pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    HttpRequest request = HttpRequest.newBuilder(controlEndpoint)
                        .timeout(REQUEST_TIMEOUT)
                        .GET()
                        .build();
                    HttpResponse<String> response = exchange(
                        request,
                        "read bound SMPP sessions from the SMSC control plane"
                    );
                    lastControlPage = response.body();
                    return response.statusCode() == 200
                        && response.body().contains("value=\"" + expectedSystemId + "\"");
                });
        } catch (ConditionTimeoutException timeout) {
            throw new IllegalStateException(
                "Timed out waiting for SMSC system ID '" + expectedSystemId + "' to bind; " + diagnostics(),
                timeout
            );
        }
    }

    private HttpResponse<String> exchange(HttpRequest request, String operation) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while attempting to " + operation, exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot " + operation + "; " + diagnostics(), exception);
        }
    }

    private String diagnostics() {
        return "component state=" + componentState.get()
            + ", last POST=" + lastPostResult
            + ", last control page=" + lastControlPage
            + System.lineSeparator() + "Environment events:"
            + System.lineSeparator() + environmentEvents.get();
    }

    private static String formField(String name, String value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8)
            + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    /**
     * Local test-control correlation only. This value is not an SMPP sequence number or SMSC message ID.
     */
    public record ScenarioControlId(String value) {
        public ScenarioControlId {
            requireText(value, "scenario control ID");
        }
    }
}
