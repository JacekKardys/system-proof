package io.github.jacekkardys.systemproof.examples.sms.environment;

import lombok.Getter;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.ingestion.SmsIngestionComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.jasmin.JasminComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres.SmsDatabaseOperations;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres.PostgresComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.rabbitmq.RabbitMqComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.redis.RedisComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc.SmscComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc.UkarimSmscOperations;
import io.github.jacekkardys.systemproof.junit.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.ComponentFactory;
import io.github.jacekkardys.systemproof.model.Environment;

/** Complete SMS ingestion topology with operations on the exact component instances started by System Proof. */
public final class SmsExampleEnvironment extends Environment {
    private final SmscComponent smsc;

    @Getter
    @Accessors(fluent = true)
    private final JasminComponent jasmin;

    @Getter
    @Accessors(fluent = true)
    private final SmsIngestionComponent ingestion;
    private final PostgresComponent database;

    private SmsExampleEnvironment(
        Environment.Builder topology,
        SmscComponent smsc,
        JasminComponent jasmin,
        SmsIngestionComponent ingestion,
        PostgresComponent database
    ) {
        super(topology);
        this.smsc = smsc;
        this.jasmin = jasmin;
        this.ingestion = ingestion;
        this.database = database;
    }

    @EnvironmentDefinition
    public static SmsExampleEnvironment define() {
        ComponentFactory components = ComponentFactory.system();
        SmscComponent smsc = SmscComponent.define(components);
        JasminComponent jasmin = JasminComponent.define(components);
        SmsIngestionComponent ingestion = SmsIngestionComponent.define(components);
        PostgresComponent database = PostgresComponent.define(components);
        RabbitMqComponent broker = RabbitMqComponent.define(components);
        RedisComponent state = RedisComponent.define(components);

        return new SmsExampleEnvironment(
            Environment.environment()
                .components(smsc, jasmin, ingestion, database, broker, state)
                .connect(jasmin.smpp(), smsc.smpp())
                .connect(jasmin.sms(), ingestion.sms())
                .connect(ingestion.jdbc(), database.jdbc())
                .connect(jasmin.amqp(), broker.amqp())
                .connect(jasmin.redis(), state.redis()),
            smsc,
            jasmin,
            ingestion,
            database
        );
    }

    public UkarimSmscOperations smsc() {
        return operations(smsc);
    }

    public SmsDatabaseOperations database() {
        return operations(database);
    }
}
