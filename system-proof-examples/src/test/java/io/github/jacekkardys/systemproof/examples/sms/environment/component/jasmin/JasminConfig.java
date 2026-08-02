package io.github.jacekkardys.systemproof.examples.sms.environment.component.jasmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import io.github.jacekkardys.systemproof.examples.sms.environment.component.jasmin.JasminComponent.SmppBindMode;
import io.github.jacekkardys.systemproof.configuration.ComponentConfig;
import io.github.jacekkardys.systemproof.configuration.ConfigurationSource;
import io.github.jacekkardys.systemproof.configuration.EnvironmentVariable;
import io.github.jacekkardys.systemproof.configuration.Literal;
import io.github.jacekkardys.systemproof.model.component.DriverConfig;
import io.github.jacekkardys.systemproof.model.value.Secret;

public interface JasminConfig
    extends ComponentConfig<JasminConfig.Driver> {

    @NotNull(message = "SMPP bind mode must not be null")
    @ConfigurationSource(
        provider = EnvironmentVariable.class,
        key = "SYSTEM_PROOF_EXAMPLE_SMPP_BIND_MODE",
        defaultValue = "transceiver"
    )
    SmppBindMode bindMode();

    @NotBlank(message = "Jasmin admin username must not be blank")
    @ConfigurationSource(
        provider = EnvironmentVariable.class,
        key = "SYSTEM_PROOF_EXAMPLE_JASMIN_JCLI_USERNAME",
        defaultValue = "jcliadmin"
    )
    String adminUsername();

    @NotNull(message = "Jasmin admin password must not be null")
    @ConfigurationSource(
        provider = EnvironmentVariable.class,
        key = "SYSTEM_PROOF_EXAMPLE_JASMIN_JCLI_PASSWORD",
        defaultValue = "jclipwd"
    )
    Secret<String> adminPassword();

    interface Driver extends DriverConfig {

        @NotBlank(message = "Jasmin image must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "SYSTEM_PROOF_EXAMPLE_JASMIN_IMAGE",
            defaultValue = "jookies/jasmin:0.11.0"
        )
        String image();

        @Positive(message = "Jasmin administration port must be positive")
        @ConfigurationSource(provider = Literal.class, value = "8990")
        int administrationPort();

        @NotBlank(message = "Jasmin configuration path must not be blank")
        @ConfigurationSource(provider = Literal.class, value = "/opt/system-proof/jasmin.cfg")
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
