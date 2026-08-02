package io.github.jacekkardys.systemproof.examples.sms.environment.component.smsc;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import io.github.jacekkardys.systemproof.configuration.ComponentConfig;
import io.github.jacekkardys.systemproof.configuration.ConfigurationSource;
import io.github.jacekkardys.systemproof.configuration.EnvironmentVariable;
import io.github.jacekkardys.systemproof.configuration.Literal;
import io.github.jacekkardys.systemproof.model.component.DriverConfig;
import io.github.jacekkardys.systemproof.model.value.Secret;

public interface SmscConfig
    extends ComponentConfig<SmscConfig.Driver> {

    @NotBlank(message = "SMPP system ID must not be blank")
    @ConfigurationSource(
        provider = EnvironmentVariable.class,
        key = "SYSTEM_PROOF_EXAMPLE_SMSC_SYSTEM_ID",
        defaultValue = "sp-test"
    )
    String systemId();

    @NotNull(message = "SMPP password must not be null")
    @ConfigurationSource(
        provider = EnvironmentVariable.class,
        key = "SYSTEM_PROOF_EXAMPLE_SMSC_PASSWORD",
        defaultValue = "password"
    )
    Secret<String> password();

    @NotBlank(message = "SMSC control path must not be blank")
    @Pattern(regexp = "^/.*", message = "SMSC control path must start with '/'")
    @ConfigurationSource(provider = Literal.class, value = "/")
    String controlPath();

    public interface Driver extends DriverConfig {
        @NotBlank(message = "SMSC simulator image must not be blank")
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "SYSTEM_PROOF_SMSC_SIMULATOR_IMAGE",
            defaultValue = "system-proof-ukarim-smscsim:local"
        )
        String image();

        @Positive(message = "SMSC SMPP port must be positive")
        @ConfigurationSource(provider = Literal.class, value = "2775")
        int smppPort();

        @Positive(message = "SMSC control port must be positive")
        @ConfigurationSource(provider = Literal.class, value = "12775")
        int controlPort();

        @NotNull(message = "SMSC startup timeout must not be null")
        @ConfigurationSource(provider = Literal.class, value = "PT2M")
        Duration startupTimeout();
    }
}
