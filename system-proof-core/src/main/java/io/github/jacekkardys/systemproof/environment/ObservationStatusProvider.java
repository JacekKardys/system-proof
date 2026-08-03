package io.github.jacekkardys.systemproof.environment;

import io.github.jacekkardys.systemproof.observation.EffectiveObservationStatus;

/** Supplies the current effective observation state of one connection-owned route. */
@FunctionalInterface
public interface ObservationStatusProvider {
    EffectiveObservationStatus observationStatus();
}
