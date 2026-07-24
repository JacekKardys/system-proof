package io.github.jacekkardys.systemproof.examples.postgres;

import io.github.jacekkardys.systemproof.junit.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.ComponentFactory;
import io.github.jacekkardys.systemproof.model.Environment;

final class PostgresExampleEnvironment extends Environment {
    private final PostgresComponent database;

    private PostgresExampleEnvironment(Builder topology, PostgresComponent database) {
        super(topology);
        this.database = database;
    }

    @EnvironmentDefinition
    static PostgresExampleEnvironment define() {
        ComponentFactory components = ComponentFactory.system();
        PostgresComponent database = PostgresComponent.define(components);

        return new PostgresExampleEnvironment(
            Environment.environment().components(database),
            database
        );
    }

    DatabaseOperations database() {
        return operations(database);
    }
}
