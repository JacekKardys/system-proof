package io.github.jacekkardys.systemproof.testcontainers.gateway;

import java.io.OutputStream;
import io.github.jacekkardys.systemproof.observation.FlowDirection;

@FunctionalInterface
interface ForwardingOutputDecorator {
    OutputStream decorate(FlowDirection direction, OutputStream destination);

    static ForwardingOutputDecorator passthrough() {
        return (direction, destination) -> destination;
    }
}
