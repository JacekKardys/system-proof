package pl.gov.il.test.aml.ingestion.environment;

import lombok.Getter;
import lombok.experimental.Accessors;
import pl.gov.il.test.aml.ingestion.environment.component.ingestion.SmsIngestionComponent;
import pl.gov.il.test.aml.ingestion.environment.component.jasmin.JasminComponent;
import pl.gov.il.test.aml.ingestion.environment.component.postgres.AmlDatabaseOperations;
import pl.gov.il.test.aml.ingestion.environment.component.postgres.PostgresComponent;
import pl.gov.il.test.aml.ingestion.environment.component.rabbitmq.RabbitMqComponent;
import pl.gov.il.test.aml.ingestion.environment.component.redis.RedisComponent;
import pl.gov.il.test.aml.ingestion.environment.component.smsc.SmscComponent;
import pl.gov.il.test.aml.ingestion.environment.component.smsc.SmscOperations;
import pl.gov.il.test.harness.junit.EnvironmentDefinition;
import pl.gov.il.test.harness.model.ComponentFactory;
import pl.gov.il.test.harness.model.Environment;

/** AML topology with domain operations on the exact component instances started by the harness. */
public final class AmlSmsEnvironment extends Environment {
    private final SmscComponent smsc;

    @Getter
    @Accessors(fluent = true)
    private final JasminComponent jasmin;

    @Getter
    @Accessors(fluent = true)
    private final SmsIngestionComponent ingestion;
    private final PostgresComponent database;

    private AmlSmsEnvironment(
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
    public static AmlSmsEnvironment define() {
        ComponentFactory components = ComponentFactory.system();
        SmscComponent smsc = SmscComponent.define(components);
        JasminComponent jasmin = JasminComponent.define(components);
        SmsIngestionComponent ingestion = SmsIngestionComponent.define(components);
        PostgresComponent database = PostgresComponent.define(components);
        RabbitMqComponent broker = RabbitMqComponent.define(components);
        RedisComponent state = RedisComponent.define(components);

        return new AmlSmsEnvironment(
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

    public SmscOperations smsc() {
        return operations(smsc);
    }

    public AmlDatabaseOperations database() {
        return operations(database);
    }
}
