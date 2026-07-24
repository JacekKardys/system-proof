package io.github.jacekkardys.systemproof.examples.smsc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;

final class SmscControlServer implements Closeable {
    private final SmscSimulator simulator;
    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    SmscControlServer(int port, SmscSimulator simulator) throws IOException {
        this.simulator = simulator;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", this::health);
        server.createContext("/test/messages", this::messages);
        server.createContext("/test/events", this::events);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }

    void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        respond(exchange, simulator.isRunning() ? 200 : 503, Map.of("status", simulator.isRunning() ? "UP" : "DOWN"));
    }

    private void messages(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        try {
            SmsMessageRequest request = json.readValue(exchange.getRequestBody(), SmsMessageRequest.class);
            respond(exchange, 202, simulator.send(request.toMessage()));
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, Map.of("error", exception.getMessage()));
        } catch (IllegalStateException exception) {
            respond(exchange, 409, Map.of("error", exception.getMessage()));
        }
    }

    private void events(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "Method not allowed"));
            return;
        }
        String testMessageId = query(exchange.getRequestURI(), "testMessageId");
        if (testMessageId == null || testMessageId.isBlank()) {
            respond(exchange, 400, Map.of("error", "testMessageId is required"));
            return;
        }
        respond(exchange, 200, simulator.events(testMessageId));
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static String query(URI uri, String name) {
        if (uri.getRawQuery() == null) {
            return null;
        }
        return Arrays.stream(uri.getRawQuery().split("&"))
            .map(parameter -> parameter.split("=", 2))
            .filter(parts -> parts.length == 2 && parts[0].equals(name))
            .map(parts -> java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8))
            .findFirst()
            .orElse(null);
    }

    private record SmsMessageRequest(
        String testMessageId,
        String sourceAddress,
        String destinationAddress,
        String payloadBase64,
        int dataCoding,
        int esmClass,
        int priorityFlag,
        Integer requestedSequenceNumber
    ) {
        private SmsTestMessage toMessage() {
            return new SmsTestMessage(
                testMessageId,
                sourceAddress,
                destinationAddress,
                Base64.getDecoder().decode(payloadBase64),
                (byte) dataCoding,
                (byte) esmClass,
                (byte) priorityFlag,
                null,
                (byte) 0,
                Map.of(),
                requestedSequenceNumber
            );
        }
    }
}
