package io.github.jacekkardys.systemproof.examples.postgres;

import io.github.jacekkardys.systemproof.junit.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.Environment;

final class PostgresExampleEnvironment extends Environment {
    private final PostgresComponent database;

    private PostgresExampleEnvironment(Builder topology, PostgresComponent database) {
        super(topology);
        this.database = database;
    }

    @EnvironmentDefinition
    static PostgresExampleEnvironment define() {
        Environment.Builder environment = Environment.environment();
        PostgresComponent database = environment.component(PostgresComponent.class);

        return new PostgresExampleEnvironment(
            environment,
            database
        );
    }

    DatabaseOperations database() {
        return operations(database);
    }
}
