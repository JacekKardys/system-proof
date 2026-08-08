package io.github.jacekkardys.systemproof.testcontainers.component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.wait.strategy.WaitStrategy;
import org.testcontainers.containers.wait.strategy.WaitStrategyTarget;
import org.testcontainers.utility.DockerImageName;

/**
 * System Proof-owned Testcontainers lifecycle that prevents the upstream startup failure path
 * from logging exceptions or retrieving complete container output.
 */
final class ManagedGenericContainer extends GenericContainer<ManagedGenericContainer> {
    private static final WaitStrategy NO_READINESS = new NoReadinessStrategy();

    private final AtomicInteger deniedFullLogReads = new AtomicInteger();

    ManagedGenericContainer(DockerImageName image) {
        super(image);
        waitStrategy = NO_READINESS;
    }

    ManagedGenericContainer(Future<String> image) {
        super(image);
        waitStrategy = NO_READINESS;
    }

    @Override
    protected Logger logger() {
        return NOPLogger.NOP_LOGGER;
    }

    @Override
    public String getLogs() {
        deniedFullLogReads.incrementAndGet();
        return "";
    }

    @Override
    public String getLogs(OutputFrame.OutputType... types) {
        deniedFullLogReads.incrementAndGet();
        return "";
    }

    int deniedFullLogReads() {
        return deniedFullLogReads.get();
    }

    private static final class NoReadinessStrategy implements WaitStrategy {
        @Override
        public void waitUntilReady(WaitStrategyTarget target) {}

        @Override
        public WaitStrategy withStartupTimeout(Duration startupTimeout) {
            return this;
        }
    }
}
