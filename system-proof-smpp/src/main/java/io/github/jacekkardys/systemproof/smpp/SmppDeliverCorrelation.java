package io.github.jacekkardys.systemproof.smpp;

import java.util.Optional;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;

/** Synchronous policy that derives one safe key from a characterized deliver_sm. */
@FunctionalInterface
public interface SmppDeliverCorrelation {
    /**
     * Derives at most one safe key while the deliver view is valid.
     * Implementations must be pure, bounded, non-blocking, and side-effect free.
     */
    Optional<CorrelationKey> correlate(SmppDeliverInteraction interaction);

    /** Returns a policy that publishes no deliver_sm correlation. */
    static SmppDeliverCorrelation none() {
        return interaction -> Optional.empty();
    }
}
