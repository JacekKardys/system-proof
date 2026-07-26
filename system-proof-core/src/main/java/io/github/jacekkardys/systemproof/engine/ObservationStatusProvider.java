package io.github.jacekkardys.systemproof.engine;

import io.github.jacekkardys.systemproof.model.EffectiveObservationStatus;

/** Supplies the current effective observation state of one connection-owned route. */
@FunctionalInterface
public interface ObservationStatusProvider {
    EffectiveObservationStatus observationStatus();
}
