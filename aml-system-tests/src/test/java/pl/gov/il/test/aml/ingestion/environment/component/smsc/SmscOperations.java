package pl.gov.il.test.aml.ingestion.environment.component.smsc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.awaitility.core.ConditionTimeoutException;
import pl.gov.il.test.aml.ingestion.environment.domain.TestSms;

/** Domain controls backed by the SMSC simulator's typed SMPP event journal. */
public final class SmscOperations {
    private static final int REQUESTED_SEQUENCE_NUMBER = 101;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final TypeReference<List<SmscEvent>> EVENT_LIST = new TypeReference<>() {};

    private final URI sendEndpoint;
    private final URI eventsEndpoint;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Supplier<String> componentState;
    private final Supplier<String> environmentEvents;
    private volatile TestSms lastAttemptedMessage;
    private volatile List<SmscEvent> lastJournal = List.of();

    public SmscOperations(
        URI sendEndpoint,
        Supplier<String> componentState,
        Supplier<String> environmentEvents
    ) {
        this.sendEndpoint = Objects.requireNonNull(sendEndpoint, "sendEndpoint must not be null");
        eventsEndpoint = sendEndpoint.resolve("/test/events");
        this.componentState = Objects.requireNonNull(componentState, "componentState must not be null");
        this.environmentEvents = Objects.requireNonNull(environmentEvents, "environmentEvents must not be null");
        httpClient = HttpClient.newHttpClient();
        objectMapper = new ObjectMapper();
    }

    public void send(TestSms message) {
        lastAttemptedMessage = Objects.requireNonNull(message, "message must not be null");
        String payload = Base64.getEncoder().encodeToString(
            message.content().getBytes(StandardCharsets.US_ASCII)
        );
        String body;
        try {
            body = objectMapper.writeValueAsString(new SendRequest(
                message.id(),
                message.sourceAddress(),
                message.destinationAddress(),
                payload,
                1,
                0,
                0,
                REQUESTED_SEQUENCE_NUMBER
            ));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize SMS '" + message.id() + "'", exception);
        }

        HttpRequest request = HttpRequest.newBuilder(sendEndpoint)
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            requireSuccess(response, "send SMS '" + message.id() + "'");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending SMS '" + message.id() + "'", exception);
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Cannot send SMS '" + message.id() + "'; " + diagnostics(),
                exception
            );
        }
    }

    public SmscAwait await() {
        return new SmscAwait(this);
    }

    public Optional<TestSms> lastAttemptedMessage() {
        return Optional.ofNullable(lastAttemptedMessage);
    }

    private List<SmscEvent> journal(TestSms message) {
        URI uri = URI.create(
            eventsEndpoint + "?testMessageId="
                + URLEncoder.encode(message.id(), StandardCharsets.UTF_8)
        );
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            requireSuccess(response, "read the SMPP journal for SMS '" + message.id() + "'");
            List<SmscEvent> journal = List.copyOf(objectMapper.readValue(response.body(), EVENT_LIST));
            lastJournal = journal;
            return journal;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                "Interrupted while reading the SMPP journal for SMS '" + message.id() + "'",
                exception
            );
        } catch (IOException exception) {
            throw new IllegalStateException(
                "Cannot read the SMPP journal for SMS '" + message.id() + "'; " + diagnostics(),
                exception
            );
        }
    }

    private String diagnostics() {
        return "component state=" + componentState.get()
            + ", last SMPP journal=" + lastJournal
            + System.lineSeparator() + "Environment events:"
            + System.lineSeparator() + environmentEvents.get();
    }

    private static void requireSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                "SMSC simulator could not " + operation + ": HTTP "
                    + response.statusCode() + " " + response.body()
            );
        }
    }

    public static final class SmscAwait {
        private final SmscOperations smsc;

        private SmscAwait(SmscOperations smsc) {
            this.smsc = smsc;
        }

        public SmscResponse responseReceived(TestSms message) {
            Objects.requireNonNull(message, "message must not be null");
            try {
                return Awaitility.await("a correlated deliver_sm_resp for SMS " + message.id())
                    .atMost(DEFAULT_TIMEOUT)
                    .pollInterval(Duration.ofMillis(100))
                    .until(
                        () -> response(smsc.journal(message)),
                        Optional::isPresent
                    )
                    .orElseThrow();
            } catch (ConditionTimeoutException timeoutFailure) {
                throw new IllegalStateException(
                    "Timed out waiting for deliver_sm_resp for SMS '" + message.id() + "'; "
                        + smsc.diagnostics(),
                    timeoutFailure
                );
            }
        }

        private static Optional<SmscResponse> response(List<SmscEvent> journal) {
            List<SmscEvent> sent = journal.stream().filter(SmscEvent::isDeliverSm).toList();
            List<SmscEvent> responses = journal.stream().filter(SmscEvent::isResponse).toList();
            if (sent.isEmpty() || responses.isEmpty()) {
                return Optional.empty();
            }
            SmscEvent deliverSm = sent.getFirst();
            SmscEvent response = responses.getFirst();
            return Optional.of(new SmscResponse(
                sent.size(),
                responses.size(),
                deliverSm.sequenceNumber(),
                deliverSm.sessionId(),
                deliverSm.eventIndex(),
                response.sequenceNumber(),
                response.sessionId(),
                response.commandStatus(),
                response.eventIndex()
            ));
        }
    }

    private record SendRequest(
        String testMessageId,
        String sourceAddress,
        String destinationAddress,
        String payloadBase64,
        int dataCoding,
        int esmClass,
        int priorityFlag,
        int requestedSequenceNumber
    ) {}

    private record SmscEvent(
        long eventIndex,
        String eventType,
        String sessionId,
        String testMessageId,
        Integer sequenceNumber,
        Integer commandStatus,
        String occurredAt,
        Object details
    ) {
        private boolean isDeliverSm() {
            return "DELIVER_SM_SENT".equals(eventType);
        }

        private boolean isResponse() {
            return "DELIVER_SM_RESP_RECEIVED".equals(eventType);
        }
    }
}
