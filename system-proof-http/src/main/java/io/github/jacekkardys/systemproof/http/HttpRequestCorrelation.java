package io.github.jacekkardys.systemproof.http;

import java.util.Optional;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;

/** Synchronous policy that derives one safe key from a complete HTTP request. */
@FunctionalInterface
public interface HttpRequestCorrelation {
    /**
     * Derives at most one safe key while the request view is valid.
     * Implementations must be pure, bounded, non-blocking, and side-effect free.
     */
    Optional<CorrelationKey> correlate(HttpRequestInteraction interaction);

    /** Returns a policy that publishes no request correlation. */
    static HttpRequestCorrelation none() {
        return interaction -> Optional.empty();
    }
}
