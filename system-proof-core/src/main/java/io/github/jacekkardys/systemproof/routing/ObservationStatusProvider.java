package io.github.jacekkardys.systemproof.routing;

import io.github.jacekkardys.systemproof.model.runtime.EffectiveObservationStatus;

/** Supplies the current effective observation state of one connection-owned route. */
@FunctionalInterface
public interface ObservationStatusProvider {
    EffectiveObservationStatus observationStatus();
}
