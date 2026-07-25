package io.github.jacekkardys.systemproof.model;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import io.github.jacekkardys.systemproof.configuration.ComponentConfig;
import io.github.jacekkardys.systemproof.driver.ComponentDriver;

/** Central validated reflection boundary for declarative component materialization. */
final class ComponentMetadata<
    C extends RuntimeConfig,
    D extends DriverConfig,
    O,
    T extends AbstractComponent<C, O>
> {
    private final Class<T> componentClass;
    private final ComponentType componentType;
    private final Class<C> configurationType;
    private final Class<D> driverConfigurationType;
    private final Class<O> operationsType;
    private final Class<? extends ComponentDriver<C, O>> driverClass;
    private final Constructor<T> componentConstructor;
    private final Constructor<? extends ComponentDriver<C, O>> driverConstructor;

    private ComponentMetadata(
        Class<T> componentClass,
        ComponentType componentType,
        Class<C> configurationType,
        Class<D> driverConfigurationType,
        Class<O> operationsType,
        Class<? extends ComponentDriver<C, O>> driverClass,
        Constructor<T> componentConstructor,
        Constructor<? extends ComponentDriver<C, O>> driverConstructor
    ) {
        this.componentClass = componentClass;
        this.componentType = componentType;
        this.configurationType = configurationType;
        this.driverConfigurationType = driverConfigurationType;
        this.operationsType = operationsType;
        this.driverClass = driverClass;
        this.componentConstructor = componentConstructor;
        this.driverConstructor = driverConstructor;
    }

    static <
        C extends RuntimeConfig,
        O,
        T extends AbstractComponent<C, O>
    > ComponentMetadata<C, ?, O, T> analyze(Class<T> componentClass) {
        Objects.requireNonNull(componentClass, "componentClass must not be null");
        SystemComponent declaration = componentClass.getAnnotation(SystemComponent.class);
        if (declaration == null) {
            throw invalid(componentClass, "must declare @SystemComponent");
        }
        if (declaration.type().isBlank()) {
            throw invalid(componentClass, "declares a blank @SystemComponent type");
        }

        ComponentType componentType;
        try {
            componentType = ComponentType.of(declaration.type());
        } catch (IllegalArgumentException exception) {
            throw invalid(
                componentClass,
                "declares invalid @SystemComponent type '" + declaration.type() + "'"
            );
        }

        Type[] componentArguments = directTypeArguments(
            componentClass,
            componentClass.getGenericSuperclass(),
            AbstractComponent.class,
            "must directly declare concrete AbstractComponent<C, O> types"
        );
        Class<?> configurationType = concreteType(
            componentClass,
            "component configuration",
            componentArguments[0]
        );
        Class<?> operationsType = concreteType(
            componentClass,
            "runtime operations",
            componentArguments[1]
        );
        if (!RuntimeConfig.class.isAssignableFrom(configurationType)) {
            throw invalid(
                componentClass,
                "declares component configuration " + configurationType.getName()
                    + " which does not implement " + RuntimeConfig.class.getName()
            );
        }

        Type configurationDeclaration = Arrays.stream(configurationType.getGenericInterfaces())
            .filter(ParameterizedType.class::isInstance)
            .map(ParameterizedType.class::cast)
            .filter(candidate -> candidate.getRawType() == ComponentConfig.class)
            .findFirst()
            .orElseThrow(() -> invalid(
                componentClass,
                "declares component configuration " + configurationType.getName()
                    + " which must directly implement ComponentConfig<D>"
            ));
        Type[] configurationArguments =
            ((ParameterizedType) configurationDeclaration).getActualTypeArguments();
        Class<?> driverConfigurationType = concreteType(
            componentClass,
            "driver configuration of " + configurationType.getName(),
            configurationArguments[0]
        );
        if (!DriverConfig.class.isAssignableFrom(driverConfigurationType)) {
            throw invalid(
                componentClass,
                "declares driver configuration " + driverConfigurationType.getName()
                    + " which does not implement " + DriverConfig.class.getName()
            );
        }

        Class<? extends ComponentDriver<?, ?>> driverClass = declaration.driver();
        Type[] driverArguments = componentDriverArguments(componentClass, driverClass);
        requireSameType(
            componentClass,
            driverClass,
            "component configuration",
            configurationType,
            driverArguments[0]
        );
        requireSameType(
            componentClass,
            driverClass,
            "runtime operations",
            operationsType,
            driverArguments[1]
        );
        validateBoundComponent(componentClass, driverClass);

        Constructor<?> componentConstructor = noArgumentComponentConstructor(componentClass);
        Constructor<?> driverConstructor = driverConstructor(
            componentClass,
            driverClass,
            driverConfigurationType
        );
        return typed(
            componentClass,
            componentType,
            configurationType,
            driverConfigurationType,
            operationsType,
            driverClass,
            componentConstructor,
            driverConstructor
        );
    }

    static <
        C extends RuntimeConfig,
        O,
        T extends AbstractComponent<C, O>
    > T materialize(
        Class<T> componentClass,
        ComponentType componentType,
        String qualifier,
        C configuration,
        ComponentDriver<C, O> driver
    ) {
        Objects.requireNonNull(componentClass, "componentClass must not be null");
        Objects.requireNonNull(componentType, "componentType must not be null");
        Objects.requireNonNull(configuration, "configuration must not be null");
        Objects.requireNonNull(driver, "driver must not be null");

        Type[] componentArguments = directTypeArguments(
            componentClass,
            componentClass.getGenericSuperclass(),
            AbstractComponent.class,
            "must directly declare concrete AbstractComponent<C, O> types"
        );
        Class<?> configurationType = concreteType(
            componentClass,
            "component configuration",
            componentArguments[0]
        );
        if (!configurationType.isInstance(configuration)) {
            throw invalid(
                componentClass,
                "requires component configuration " + configurationType.getName()
                    + " but received " + configuration.getClass().getName()
            );
        }
        Class<?> operationsType = concreteType(
            componentClass,
            "runtime operations",
            componentArguments[1]
        );
        ComponentMetadata<C, ?, O, T> metadata = typed(
            componentClass,
            componentType,
            configurationType,
            DriverConfig.class,
            operationsType,
            driver.getClass(),
            noArgumentComponentConstructor(componentClass),
            null
        );
        return metadata.materialize(qualifier, configuration, driver);
    }

    T materialize(String qualifier, EnvironmentConfiguration values) {
        Objects.requireNonNull(values, "values must not be null");
        C configuration = values.bind(configurationType);
        D driverConfiguration = values.bind(driverConfigurationType);
        ComponentDriver<C, O> driver = instantiateDriver(driverConfiguration);
        return materialize(qualifier, configuration, driver);
    }

    T materialize(
        String qualifier,
        C configuration,
        ComponentDriver<C, O> driver
    ) {
        T component = instantiateComponent();
        component.initialize(
            ComponentId.component(componentType, qualifier),
            configuration,
            operationsType,
            driver,
            true
        );
        return component;
    }

    private ComponentDriver<C, O> instantiateDriver(D configuration) {
        try {
            return driverConstructor.newInstance(configuration);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            throw new IllegalArgumentException(
                "Cannot create driver " + driverClass.getName() + " for component "
                    + componentClass.getName() + ": constructor threw "
                    + cause.getClass().getName()
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalArgumentException(
                "Cannot create driver " + driverClass.getName() + " for component "
                    + componentClass.getName()
            );
        }
    }

    private T instantiateComponent() {
        try {
            return componentConstructor.newInstance();
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            throw new IllegalArgumentException(
                "Cannot create component " + componentClass.getName()
                    + ": constructor threw " + cause.getClass().getName()
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new IllegalArgumentException(
                "Cannot create component " + componentClass.getName()
            );
        }
    }

    private static Type[] componentDriverArguments(
        Class<?> componentClass,
        Class<?> driverClass
    ) {
        for (Type candidate : driverClass.getGenericInterfaces()) {
            if (candidate instanceof ParameterizedType parameterized
                && parameterized.getRawType() == ComponentDriver.class) {
                return parameterized.getActualTypeArguments();
            }
        }

        Type superclass = driverClass.getGenericSuperclass();
        if (superclass instanceof ParameterizedType parameterized
            && parameterized.getRawType() instanceof Class<?> rawType
            && ComponentDriver.class.isAssignableFrom(rawType)) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length >= 2) {
                return new Type[] { arguments[0], arguments[1] };
            }
        }
        throw invalid(
            componentClass,
            "declares driver " + driverClass.getName()
                + " without concrete ComponentDriver<C, O> types"
        );
    }

    private static void validateBoundComponent(
        Class<?> componentClass,
        Class<?> driverClass
    ) {
        Type superclass = driverClass.getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType parameterized)
            || !(parameterized.getRawType() instanceof Class<?> rawType)
            || !ComponentDriver.class.isAssignableFrom(rawType)
            || parameterized.getActualTypeArguments().length < 3) {
            return;
        }

        Type boundType = parameterized.getActualTypeArguments()[2];
        if (!(boundType instanceof Class<?> boundClass)
            || !AbstractComponent.class.isAssignableFrom(boundClass)) {
            throw invalid(
                componentClass,
                "declares driver " + driverClass.getName()
                    + " with non-concrete component type " + boundType.getTypeName()
            );
        }
        if (boundClass != componentClass) {
            throw invalid(
                componentClass,
                "declares driver " + driverClass.getName() + " bound to component "
                    + boundClass.getName()
            );
        }
    }

    private static Constructor<?> noArgumentComponentConstructor(Class<?> componentClass) {
        if (Modifier.isAbstract(componentClass.getModifiers())) {
            throw invalid(componentClass, "must be concrete");
        }
        try {
            Constructor<?> constructor = componentClass.getDeclaredConstructor();
            if (!constructor.trySetAccessible()) {
                throw invalid(componentClass, "has an inaccessible no-argument constructor");
            }
            return constructor;
        } catch (NoSuchMethodException exception) {
            throw invalid(componentClass, "must declare a no-argument constructor");
        }
    }

    private static Constructor<?> driverConstructor(
        Class<?> componentClass,
        Class<?> driverClass,
        Class<?> driverConfigurationType
    ) {
        if (Modifier.isAbstract(driverClass.getModifiers())) {
            throw invalid(
                componentClass,
                "declares abstract driver " + driverClass.getName()
            );
        }
        List<Constructor<?>> candidates = Arrays.stream(driverClass.getDeclaredConstructors())
            .filter(constructor -> constructor.getParameterCount() == 1)
            .filter(constructor ->
                constructor.getParameterTypes()[0].isAssignableFrom(driverConfigurationType)
            )
            .toList();
        if (candidates.size() != 1
            || candidates.getFirst().getParameterTypes()[0] != driverConfigurationType) {
            throw invalid(
                componentClass,
                "declares driver " + driverClass.getName()
                    + " which must have exactly one unambiguous constructor accepting "
                    + driverConfigurationType.getName()
            );
        }
        Constructor<?> constructor = candidates.getFirst();
        if (!constructor.trySetAccessible()) {
            throw invalid(
                componentClass,
                "cannot access driver constructor " + driverClass.getName()
                    + "(" + driverConfigurationType.getName() + ")"
            );
        }
        return constructor;
    }

    private static void requireSameType(
        Class<?> componentClass,
        Class<?> driverClass,
        String role,
        Class<?> expected,
        Type actual
    ) {
        if (!(actual instanceof Class<?> actualClass) || actualClass != expected) {
            throw invalid(
                componentClass,
                "declares driver " + driverClass.getName() + " with " + role + " "
                    + actual.getTypeName() + " instead of " + expected.getName()
            );
        }
    }

    private static Type[] directTypeArguments(
        Class<?> componentClass,
        Type declaration,
        Class<?> expectedRawType,
        String reason
    ) {
        if (!(declaration instanceof ParameterizedType parameterized)
            || parameterized.getRawType() != expectedRawType) {
            throw invalid(componentClass, reason);
        }
        return parameterized.getActualTypeArguments();
    }

    private static Class<?> concreteType(
        Class<?> componentClass,
        String role,
        Type type
    ) {
        if (type instanceof Class<?> concreteType) {
            return concreteType;
        }
        throw invalid(
            componentClass,
            "must declare a concrete " + role + " type instead of " + type.getTypeName()
        );
    }

    private static IllegalArgumentException invalid(
        Class<?> componentClass,
        String reason
    ) {
        return new IllegalArgumentException(
            "Component " + componentClass.getName() + " " + reason
        );
    }

    @SuppressWarnings("unchecked")
    private static <
        C extends RuntimeConfig,
        D extends DriverConfig,
        O,
        T extends AbstractComponent<C, O>
    > ComponentMetadata<C, D, O, T> typed(
        Class<T> componentClass,
        ComponentType componentType,
        Class<?> configurationType,
        Class<?> driverConfigurationType,
        Class<?> operationsType,
        Class<?> driverClass,
        Constructor<?> componentConstructor,
        Constructor<?> driverConstructor
    ) {
        return new ComponentMetadata<>(
            componentClass,
            componentType,
            (Class<C>) configurationType,
            (Class<D>) driverConfigurationType,
            (Class<O>) operationsType,
            (Class<? extends ComponentDriver<C, O>>) driverClass,
            (Constructor<T>) componentConstructor,
            (Constructor<? extends ComponentDriver<C, O>>) driverConstructor
        );
    }
}
