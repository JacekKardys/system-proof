package io.github.jacekkardys.systemproof.postgresql;

import java.util.Optional;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;

/** Synchronous policy that derives one safe correlation key without retaining SQL or bind data. */
@FunctionalInterface
public interface PostgresqlWriteCorrelation {
    /**
     * Derives at most one safe key while the interaction view is valid.
     *
     * @param interaction ephemeral statement shape and bind-parameter view
     * @return an optional digest-based correlation key; never {@code null}
     */
    Optional<CorrelationKey> correlate(PostgresqlWriteInteraction interaction);

    /** Returns a policy that publishes no write correlation. */
    static PostgresqlWriteCorrelation none() {
        return interaction -> Optional.empty();
    }
}
