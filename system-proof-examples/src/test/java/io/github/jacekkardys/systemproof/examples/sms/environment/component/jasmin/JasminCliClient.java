package io.github.jacekkardys.systemproof.examples.sms.environment.component.jasmin;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

final class JasminCliClient implements Closeable {
    private static final int IAC = 255;
    private static final int SE = 240;
    private static final int SB = 250;
    private static final int WILL = 251;
    private static final int WONT = 252;
    private static final int DO = 253;
    private static final int DONT = 254;
    private static final int ECHO = 1;
    private static final int SUPPRESS_GO_AHEAD = 3;
    private static final int WINDOW_SIZE = 31;
    private static final int LINE_MODE = 34;
    private static final int DEFAULT_WINDOW_WIDTH = 80;
    private static final int DEFAULT_WINDOW_HEIGHT = 24;

    private final Socket socket = new Socket();
    private final Duration timeout;

    JasminCliClient(String host, int port, Duration timeout, String username, String password) {
        this.timeout = timeout;
        try {
            socket.connect(new InetSocketAddress(host, port), Math.toIntExact(timeout.toMillis()));
            socket.setSoTimeout(500);
            readUntil(List.of("username:", "authentication required"));
            sendLine(username);
            readUntil(List.of("password:"));
            sendLine(password);
            String output = readUntil(List.of("jcli :", "authentication failure", "invalid"));
            if (!output.toLowerCase(Locale.ROOT).contains("jcli :")) {
                throw new IllegalStateException("Jasmin jCli authentication failed");
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot connect to Jasmin jCli at " + host + ":" + port, exception);
        }
    }

    String command(String command) {
        sendLine(command);
        String output = readUntil(List.of("jcli :"));
        requireNoFailure(command, output);
        return output;
    }

    String commandToleratingFailure(String command) {
        sendLine(command);
        return readCommandResponse();
    }

    void interactive(String command, List<String> values) {
        sendLine(command);
        String entered = readUntil(List.of(">", "jcli :"));
        if (entered.toLowerCase(Locale.ROOT).contains("jcli :")) {
            throw new IllegalStateException("jCli did not enter interactive mode for " + command);
        }
        String output = entered;
        for (int index = 0; index < values.size(); index++) {
            sendLine(values.get(index));
            output += readUntil(List.of(index == values.size() - 1 ? "jcli :" : ">"));
        }
        requireNoFailure(command, output);
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    private String readCommandResponse() {
        Instant deadline = Instant.now().plus(timeout);
        StringBuilder text = new StringBuilder();
        boolean confirmed = false;
        while (Instant.now().isBefore(deadline)) {
            text.append(readAvailable());
            String lowered = text.toString().toLowerCase(Locale.ROOT);
            if (!confirmed && (lowered.contains("are you sure") || lowered.contains("[y/n]")
                || lowered.contains("(y/n)"))) {
                sendLine("y");
                confirmed = true;
            }
            if (lowered.contains("jcli :")) {
                return text.toString();
            }
        }
        throw new IllegalStateException("Timed out waiting for the Jasmin jCli prompt");
    }

    private String readUntil(List<String> markers) {
        Instant deadline = Instant.now().plus(timeout);
        StringBuilder text = new StringBuilder();
        while (Instant.now().isBefore(deadline)) {
            text.append(readAvailable());
            String lowered = text.toString().toLowerCase(Locale.ROOT);
            if (markers.stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(lowered::contains)) {
                return text.toString();
            }
        }
        throw new IllegalStateException(
            "Timed out waiting for Jasmin jCli markers " + markers + ". Last output: " + sanitize(text.toString())
        );
    }

    private String readAvailable() {
        byte[] buffer = new byte[4096];
        try {
            int read = socket.getInputStream().read(buffer);
            if (read < 0) {
                throw new IllegalStateException("Jasmin jCli closed the connection");
            }
            return stripTelnet(buffer, read);
        } catch (SocketTimeoutException ignored) {
            return "";
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read a Jasmin jCli response", exception);
        }
    }

    private String stripTelnet(byte[] data, int length) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int index = 0; index < length;) {
            int value = Byte.toUnsignedInt(data[index++]);
            if (value != IAC) {
                output.write(value);
                continue;
            }
            if (index >= length) {
                break;
            }
            int command = Byte.toUnsignedInt(data[index++]);
            if (command == IAC) {
                output.write(IAC);
            } else if (command == DO || command == DONT || command == WILL || command == WONT) {
                if (index < length) {
                    int option = Byte.toUnsignedInt(data[index++]);
                    negotiate(command, option);
                }
            } else if (command == SB) {
                while (index + 1 < length) {
                    if (Byte.toUnsignedInt(data[index]) == IAC && Byte.toUnsignedInt(data[index + 1]) == SE) {
                        index += 2;
                        break;
                    }
                    index++;
                }
            }
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    private void negotiate(int command, int option) throws IOException {
        int response = switch (command) {
            case DO -> supportsLocal(option) ? WILL : WONT;
            case DONT -> WONT;
            case WILL -> supportsRemote(option) ? DO : DONT;
            case WONT -> DONT;
            default -> throw new IllegalArgumentException("Unsupported Telnet negotiation command: " + command);
        };
        socket.getOutputStream().write(new byte[]{(byte) IAC, (byte) response, (byte) option});
        if (command == DO && option == WINDOW_SIZE && response == WILL) {
            sendWindowSize();
        }
        socket.getOutputStream().flush();
    }

    private void sendWindowSize() throws IOException {
        socket.getOutputStream().write(new byte[]{
            (byte) IAC,
            (byte) SB,
            (byte) WINDOW_SIZE,
            0,
            (byte) DEFAULT_WINDOW_WIDTH,
            0,
            (byte) DEFAULT_WINDOW_HEIGHT,
            (byte) IAC,
            (byte) SE
        });
    }

    private static boolean supportsLocal(int option) {
        return option == LINE_MODE || option == WINDOW_SIZE || option == SUPPRESS_GO_AHEAD;
    }

    private static boolean supportsRemote(int option) {
        return option == ECHO;
    }

    private void sendLine(String line) {
        try {
            socket.getOutputStream().write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot send a Jasmin jCli command", exception);
        }
    }

    private static void requireNoFailure(String command, String output) {
        String lowered = output.toLowerCase(Locale.ROOT);
        if (lowered.contains("unknown command") || lowered.contains("error:") || lowered.contains("failed")) {
            throw new IllegalStateException("Jasmin jCli command failed: " + command);
        }
    }

    private static String sanitize(String output) {
        return output.replaceAll("(?i)(password\\s+)\\S+", "$1********");
    }
}
