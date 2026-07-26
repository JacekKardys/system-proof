package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.Function;

/**
 * Extracts and replaces the TCP address carried by one typed endpoint contract.
 *
 * <p>The gateway uses the external provider value as its JVM-reachable target and creates routed
 * copies of both endpoint forms without knowing protocol-specific fields such as credentials or
 * paths.
 */
public final class TcpEndpointAdapter<C> {
    private final Function<? super C, InetSocketAddress> address;
    private final AddressReplacement<C> replacement;

    private TcpEndpointAdapter(
        Function<? super C, InetSocketAddress> address,
        AddressReplacement<C> replacement
    ) {
        this.address = Objects.requireNonNull(address, "address must not be null");
        this.replacement = Objects.requireNonNull(replacement, "replacement must not be null");
    }

    public static <C> TcpEndpointAdapter<C> endpoint(
        Function<? super C, InetSocketAddress> address,
        AddressReplacement<C> replacement
    ) {
        return new TcpEndpointAdapter<>(address, replacement);
    }

    InetSocketAddress address(C endpoint) {
        InetSocketAddress value = Objects.requireNonNull(
            address.apply(Objects.requireNonNull(endpoint, "endpoint must not be null")),
            "endpoint address must not be null"
        );
        if (value.getPort() < 1 || value.getPort() > 65_535) {
            throw new IllegalArgumentException(
                "Endpoint TCP port must be between 1 and 65535"
            );
        }
        return value;
    }

    C replaceAddress(C endpoint, String host, int port) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        Objects.requireNonNull(host, "host must not be null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException(
                "Replacement TCP port must be between 1 and 65535"
            );
        }
        C replaced = Objects.requireNonNull(
            replacement.replace(endpoint, host, port),
            "replacement endpoint must not be null"
        );
        InetSocketAddress replacedAddress = address(replaced);
        if (!host.equals(replacedAddress.getHostString())
            || port != replacedAddress.getPort()) {
            throw new IllegalArgumentException(
                "Replacement endpoint must use the requested TCP address"
            );
        }
        return replaced;
    }

    /** Creates a copy of an endpoint value with a different host and port. */
    @FunctionalInterface
    public interface AddressReplacement<C> {
        C replace(C endpoint, String host, int port);
    }
}
