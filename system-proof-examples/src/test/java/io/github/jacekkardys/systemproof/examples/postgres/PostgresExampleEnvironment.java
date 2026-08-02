package io.github.jacekkardys.systemproof.examples.postgres;

import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.Environment;
import io.github.jacekkardys.systemproof.construction.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.construction.EnvironmentTopology;

final class PostgresExampleEnvironment extends Environment {
    private final PostgresComponent database;

    private PostgresExampleEnvironment(EnvironmentTopology topology, EnvironmentLogging logging, PostgresComponent database) {
        super(topology, logging);
        this.database = database;
    }

    @EnvironmentDefinition
    static PostgresExampleEnvironment define() {
        EnvironmentBuilder builder = new EnvironmentBuilder();
        PostgresComponent database = builder.component(PostgresComponent.class);

        return builder.build((topology, logging) ->
            new PostgresExampleEnvironment(topology, logging, database)
        );
    }

    DatabaseOperations database() {
        return operations(database);
    }
}
