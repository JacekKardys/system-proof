package pl.gov.il.test.aml.ingestion.environment.component.jasmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import pl.gov.il.test.aml.ingestion.environment.component.jasmin.JasminComponent.SmppBindMode;
import pl.gov.il.test.harness.configuration.ComponentConfig;
import pl.gov.il.test.harness.configuration.ConfigurationSource;
import pl.gov.il.test.harness.configuration.EnvironmentVariable;
import pl.gov.il.test.harness.configuration.Literal;
import pl.gov.il.test.harness.model.DriverConfig;
import pl.gov.il.test.harness.model.RuntimeConfig;
import pl.gov.il.test.harness.model.Secret;

public interface JasminConfig
    extends ComponentConfig<JasminConfig.Runtime, JasminConfig.Driver> {

    interface Runtime extends RuntimeConfig {

        @NotNull(message = "SMPP bind mode must not be null")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_SMPP_BIND_MODE",
            defaultValue = "transceiver"
        )
        SmppBindMode bindMode();

        @NotBlank(message = "Jasmin admin username must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_JASMIN_JCLI_USERNAME",
            defaultValue = "jcliadmin"
        )
        String adminUsername();

        @NotNull(message = "Jasmin admin password must not be null")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_JASMIN_JCLI_PASSWORD",
            defaultValue = "jclipwd"
        )
        Secret<String> adminPassword();
    }

    interface Driver extends DriverConfig {

        @NotBlank(message = "Jasmin image must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "AML_JASMIN_IMAGE",
            defaultValue = "jookies/jasmin:0.11.0"
        )
        String image();

        @Positive(message = "Jasmin administration port must be positive")
        @ConfigurationSource(provider = Literal.class, value = "8990")
        int administrationPort();

        @NotBlank(message = "Jasmin configuration path must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "/opt/aml-regression/jasmin.cfg")
        String configurationPath();

        @NotBlank(message = "Jasmin executable must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "jasmind.py")
        String executable();

        @NotBlank(message = "Jasmin configuration option must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "-c")
        String configurationOption();

        @NotNull(message = "Jasmin startup timeout must not be null")
        @ConfigurationSource(provider = Literal.class, value = "PT2M")
        Duration startupTimeout();
    }
}
