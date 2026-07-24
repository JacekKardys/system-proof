package io.github.jacekkardys.systemproof.model;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import io.github.jacekkardys.systemproof.configuration.ComponentConfig;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;

/** Creates typed components from already bound or externally sourced configuration. */
@RequiredArgsConstructor(staticName = "from")
public final class ComponentFactory {
    @NonNull
    private final EnvironmentConfiguration values;

    public static <
        C extends RuntimeConfig,
        O,
        T extends AbstractComponent<C, O>
    > T create(
        Class<T> componentClass,
        String qualifier,
        C configuration,
        ComponentDriver<C, O> driver
    ) {
        return AbstractComponent.component(
            componentClass,
            qualifier,
            configuration,
            driver
        );
    }

    public static ComponentFactory system() {
        return from(EnvironmentConfiguration.system());
    }

    public <
        C extends RuntimeConfig,
        D extends DriverConfig,
        O,
        T extends AbstractComponent<C, O>,
        F extends ComponentConfig<C, D>
    > T create(
        Class<T> componentClass,
        Class<F> configurationDefinition,
        Function<? super D, ? extends ComponentDriver<C, O>> driverFactory
    ) {
        return create(
            componentClass,
            null,
            configurationDefinition,
            driverFactory
        );
    }

    public <
        C extends RuntimeConfig,
        D extends DriverConfig,
        O,
        T extends AbstractComponent<C, O>,
        F extends ComponentConfig<C, D>
    > T create(
        Class<T> componentClass,
        String qualifier,
        Class<F> configurationDefinition,
        Function<? super D, ? extends ComponentDriver<C, O>> driverFactory
    ) {
        Objects.requireNonNull(componentClass, "componentClass must not be null");
        Objects.requireNonNull(configurationDefinition, "configurationDefinition must not be null");
        Objects.requireNonNull(driverFactory, "driverFactory must not be null");

        ConfigurationTypes configurationTypes = configurationTypes(configurationDefinition);
        C componentConfiguration = values.bind(cast(configurationTypes.component()));
        D driverConfiguration = values.bind(cast(configurationTypes.driver()));
        ComponentDriver<C, O> driver = Objects.requireNonNull(
            driverFactory.apply(driverConfiguration),
            "driverFactory must not return null"
        );
        return create(componentClass, qualifier, componentConfiguration, driver);
    }

    private static ConfigurationTypes configurationTypes(Class<?> definitionType) {
        ParameterizedType definition = Arrays.stream(definitionType.getGenericInterfaces())
            .filter(ParameterizedType.class::isInstance)
            .map(ParameterizedType.class::cast)
            .filter(candidate -> candidate.getRawType() == ComponentConfig.class)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Configuration definition " + definitionType.getName()
                    + " must directly implement ComponentConfig<Runtime, Driver>"
            ));
        Type[] arguments = definition.getActualTypeArguments();
        return new ConfigurationTypes(
            configurationType(definitionType, "component", arguments[0]),
            configurationType(definitionType, "driver", arguments[1])
        );
    }

    private static Class<?> configurationType(
        Class<?> definitionType,
        String role,
        Type type
    ) {
        if (type instanceof Class<?> configurationType) {
            return configurationType;
        }
        throw new IllegalArgumentException(
            "Configuration definition " + definitionType.getName()
                + " must declare a concrete " + role + " configuration interface"
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> cast(Class<?> type) {
        return (Class<T>) type;
    }

    private record ConfigurationTypes(Class<?> component, Class<?> driver) {}
}
