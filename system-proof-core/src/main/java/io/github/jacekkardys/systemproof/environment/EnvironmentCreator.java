package io.github.jacekkardys.systemproof.environment;

import io.github.jacekkardys.systemproof.diagnostics.EnvironmentLogging;

/**
 * Extension point for returning a domain-specific {@link Environment} facade from
 * {@link EnvironmentBuilder#build(EnvironmentCreator)}.
 *
 * <p>The builder invokes the creator exactly once, after component declarations, connections, and
 * logging references have been validated and frozen. A creator should only call the facade
 * constructor and pass it the supplied construction results; it must not start the environment or
 * build another topology.</p>
 *
 * <p>A typical definition captures its typed components and supplies a private constructor:</p>
 * <pre>{@code
 * return builder.build((topology, logging) ->
 *     new SmsEnvironment(topology, logging, smsc, ingestion, database)
 * );
 * }</pre>
 *
 * @param <E> concrete environment facade returned to the caller and injected into tests
 */
@FunctionalInterface
public interface EnvironmentCreator<E extends Environment> {

    /**
     * Creates the facade returned by {@link EnvironmentBuilder#build(EnvironmentCreator)}.
     *
     * @param topology validated immutable topology created by the builder
     * @param logging validated immutable logging configuration
     * @return a new facade owning the supplied topology and logging configuration; never {@code null}
     */
    E create(EnvironmentTopology topology, EnvironmentLogging logging);
}
