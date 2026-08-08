package io.github.jacekkardys.systemproof.environment;

import io.github.jacekkardys.systemproof.journal.ScenarioEvent;

/** Internal typed current-state sink; it owns no event history. */
interface ProofFactObserver {
    ProofFactObserver NONE = new ProofFactObserver() {};

    default void fact(ScenarioEvent event) {}

    default void journalFailure(Throwable failure) {}
}
