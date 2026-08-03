package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/** Internal ownership boundary around blocking accept and listener close. */
interface GatewayListener extends AutoCloseable {
    Socket accept() throws IOException;

    int port();

    @Override
    void close() throws IOException;
}

@FunctionalInterface
interface GatewayListenerFactory {
    GatewayListener open() throws IOException;
}

/** Production listener backed by one loopback-bound {@link ServerSocket}. */
final class ServerSocketGatewayListener implements GatewayListener {
    private final ServerSocket socket;

    private ServerSocketGatewayListener(ServerSocket socket) {
        this.socket = socket;
    }

    static GatewayListener open() throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(
                InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                0
            ));
            return new ServerSocketGatewayListener(socket);
        } catch (IOException | RuntimeException | Error failure) {
            try {
                socket.close();
            } catch (IOException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    @Override
    public Socket accept() throws IOException {
        return socket.accept();
    }

    @Override
    public int port() {
        return socket.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
