package io.github.jacekkardys.systemproof.examples.sms.environment.component.jasmin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class JasminCliClientTest {
    private static final byte IAC = (byte) 255;
    private static final byte SB = (byte) 250;
    private static final byte WILL = (byte) 251;
    private static final byte DO = (byte) 253;
    private static final byte SE = (byte) 240;
    private static final byte ECHO = 1;
    private static final byte SUPPRESS_GO_AHEAD = 3;
    private static final byte WINDOW_SIZE = 31;
    private static final byte LINE_MODE = 34;

    @Test
    void acceptsTheTelnetOptionsRequiredByJasminJcli() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                Future<byte[]> exchange = executor.submit(() -> serveJasminLogin(server));

                try (var ignored = new JasminCliClient(
                    "127.0.0.1",
                    server.getLocalPort(),
                    Duration.ofSeconds(5),
                    "jcliadmin",
                    "jclipwd"
                )) {
                    // Constructor authentication completes the protocol exchange under test.
                }

                assertThat(exchange.get(5, TimeUnit.SECONDS)).containsSubsequence(
                    IAC, WILL, LINE_MODE,
                    IAC, WILL, WINDOW_SIZE,
                    IAC, SB, WINDOW_SIZE, (byte) 0, (byte) 80, (byte) 0, (byte) 24, IAC, SE,
                    IAC, WILL, SUPPRESS_GO_AHEAD,
                    IAC, DO, ECHO
                );
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private static byte[] serveJasminLogin(ServerSocket server) throws IOException {
        try (Socket client = server.accept()) {
            client.setSoTimeout(5_000);
            client.getOutputStream().write(new byte[]{
                IAC, DO, LINE_MODE,
                IAC, DO, WINDOW_SIZE,
                IAC, DO, SUPPRESS_GO_AHEAD,
                IAC, WILL, ECHO
            });
            client.getOutputStream().write("Username:".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();

            ByteArrayOutputStream exchange = new ByteArrayOutputStream();
            readUntil(client, exchange, "jcliadmin\r\n");
            client.getOutputStream().write("Password:".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            readUntil(client, exchange, "jclipwd\r\n");
            client.getOutputStream().write("Welcome to Jasmin\njcli :".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            return exchange.toByteArray();
        }
    }

    private static void readUntil(Socket client, ByteArrayOutputStream exchange, String marker)
        throws IOException {
        byte[] expected = marker.getBytes(StandardCharsets.UTF_8);
        while (!endsWith(exchange.toByteArray(), expected)) {
            int value = client.getInputStream().read();
            if (value < 0) {
                throw new IOException("Client closed before sending " + marker.trim());
            }
            exchange.write(value);
        }
    }

    private static boolean endsWith(byte[] value, byte[] suffix) {
        if (value.length < suffix.length) {
            return false;
        }
        for (int index = 1; index <= suffix.length; index++) {
            if (value[value.length - index] != suffix[suffix.length - index]) {
                return false;
            }
        }
        return true;
    }
}
