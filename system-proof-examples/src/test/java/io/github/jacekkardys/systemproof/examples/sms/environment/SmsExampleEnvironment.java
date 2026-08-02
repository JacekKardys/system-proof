package io.github.jacekkardys.systemproof.examples.sms.environment;

import lombok.Getter;
import lombok.experimental.Accessors;
import io.github.jacekkardys.systemproof.api.EnvironmentLogging;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.ingestion.SmsIngestionComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.jasmin.JasminComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres.SmsDatabaseOperations;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.postgres.PostgresComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.rabbitmq.RabbitMqComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.redis.RedisComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc.SmscComponent;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc.UkarimSmscOperations;
import io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition;
import io.github.jacekkardys.systemproof.model.Environment;
import io.github.jacekkardys.systemproof.construction.EnvironmentBuilder;
import io.github.jacekkardys.systemproof.construction.EnvironmentTopology;

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

    private SmsExampleEnvironment(EnvironmentTopology topology, EnvironmentLogging logging, SmscComponent smsc,
        JasminComponent jasmin, SmsIngestionComponent ingestion, PostgresComponent database) {
        super(topology, logging);
        this.smsc = smsc;
        this.jasmin = jasmin;
        this.ingestion = ingestion;
        this.database = database;
    }

    @EnvironmentDefinition
    public static SmsExampleEnvironment define() {
        EnvironmentBuilder builder = new EnvironmentBuilder();
        SmscComponent smsc = builder.component(SmscComponent.class);
        JasminComponent jasmin = builder.component(JasminComponent.class);
        SmsIngestionComponent ingestion = builder.component(SmsIngestionComponent.class);
        PostgresComponent database = builder.component(PostgresComponent.class);
        RabbitMqComponent broker = builder.component(RabbitMqComponent.class);
        RedisComponent state = builder.component(RedisComponent.class);

        builder
            .connect(jasmin.smpp(), smsc.smpp())
            .connect(jasmin.sms(), ingestion.sms())
            .connect(ingestion.jdbc(), database.jdbc())
            .connect(jasmin.amqp(), broker.amqp())
            .connect(jasmin.redis(), state.redis());

        return builder.build((topology, logging) ->
            new SmsExampleEnvironment(topology, logging, smsc, jasmin, ingestion, database));
    }

    public UkarimSmscOperations smsc() {
        return operations(smsc);
    }

    public SmsDatabaseOperations database() {
        return operations(database);
    }
}
