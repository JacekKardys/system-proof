package io.github.jacekkardys.systemproof.construction;

import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import io.github.jacekkardys.systemproof.model.environment.EnvironmentTopology;

/** Default runtime facade returned when no specialized creator is supplied. */
final class DefaultEnvironment extends Environment {
    DefaultEnvironment(EnvironmentTopology topology, EnvironmentLogging logging) {
        super(topology, logging);
    }
}
