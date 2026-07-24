package pl.gov.il.test.harness.configuration;

import static pl.gov.il.test.harness.configuration.ConfigurationValidator.validate;
import static pl.gov.il.test.harness.configuration.ConfigurationValidator.validateReturnValues;
import static pl.gov.il.test.harness.model.Secret.secret;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import pl.gov.il.test.harness.model.EnvironmentConfiguration;
import pl.gov.il.test.harness.model.Secret;

/** Binds annotated configuration interfaces to immutable typed values. */
public final class ConfigurationBinder {
    private ConfigurationBinder() {}

    public static <T> T bind(Class<T> configurationType, EnvironmentConfiguration environment) {
        Objects.requireNonNull(configurationType, "configurationType must not be null");
        Objects.requireNonNull(environment, "environment must not be null");
        if (!configurationType.isInterface()) {
            throw new IllegalArgumentException(
                "Configuration type " + configurationType.getName() + " must be an interface"
            );
        }

        Map<Method, Object> values = new LinkedHashMap<>();
        Arrays.stream(configurationType.getMethods())
            .filter(method -> Modifier.isAbstract(method.getModifiers()))
            .sorted(Comparator.comparing(Method::toGenericString))
            .forEach(method -> values.put(method, resolve(method, environment)));

        InvocationHandler handler = new ConfigurationInvocationHandler(configurationType, Map.copyOf(values));
        Object proxy = Proxy.newProxyInstance(
            configurationType.getClassLoader(),
            new Class<?>[] { configurationType },
            handler
        );
        T configuration = validate(configurationType.cast(proxy));
        return validateReturnValues(configuration, configurationType);
    }

    private static Object resolve(Method method, EnvironmentConfiguration environment) {
        if (method.getParameterCount() != 0) {
            throw invalidMethod(method, "must not declare parameters");
        }
        if (method.getReturnType() == Void.TYPE) {
            throw invalidMethod(method, "must return a value");
        }

        ConfigurationSource source = method.getAnnotation(ConfigurationSource.class);
        if (source == null) {
            throw invalidMethod(method, "must declare @ConfigurationSource");
        }

        String value = provider(source.provider()).resolve(source, environment);
        return convert(method, Objects.requireNonNull(
            value,
            source.provider().getName() + " returned null for " + method.toGenericString()
        ));
    }

    private static ConfigurationProvider provider(
        Class<? extends ConfigurationProvider> providerType
    ) {
        try {
            Constructor<? extends ConfigurationProvider> constructor =
                providerType.getDeclaredConstructor();
            if (!constructor.trySetAccessible()) {
                throw new IllegalArgumentException(
                    "Cannot access configuration provider constructor " + providerType.getName() + "()"
                );
            }
            return constructor.newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException(
                "Configuration provider " + providerType.getName()
                    + " must declare a no-argument constructor",
                exception
            );
        }
    }

    private static Object convert(Method method, String value) {
        Class<?> returnType = method.getReturnType();
        try {
            if (returnType == String.class) {
                return value;
            }
            if (returnType == Integer.TYPE || returnType == Integer.class) {
                return Integer.parseInt(value);
            }
            if (returnType == Duration.class) {
                return Duration.parse(value);
            }
            if (returnType.isEnum()) {
                return enumValue(returnType, value);
            }
            if (returnType == Secret.class) {
                requireStringSecret(method);
                return secret(value);
            }
        } catch (NumberFormatException exception) {
            throw conversionFailure(method, "an integer", value, exception);
        } catch (DateTimeParseException exception) {
            throw conversionFailure(method, "an ISO-8601 duration", value, exception);
        } catch (IllegalArgumentException exception) {
            if (returnType.isEnum()) {
                throw conversionFailure(method, returnType.getSimpleName(), value, exception);
            }
            throw exception;
        }

        throw invalidMethod(
            method,
            "uses unsupported return type " + method.getGenericReturnType().getTypeName()
        );
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static Enum<?> enumValue(Class<?> returnType, String value) {
        return Enum.valueOf(
            (Class<? extends Enum>) returnType.asSubclass(Enum.class),
            value.toUpperCase(Locale.ROOT)
        );
    }

    private static void requireStringSecret(Method method) {
        Type returnType = method.getGenericReturnType();
        if (!(returnType instanceof ParameterizedType parameterized)
            || parameterized.getActualTypeArguments().length != 1
            || parameterized.getActualTypeArguments()[0] != String.class) {
            throw invalidMethod(method, "must return Secret<String>");
        }
    }

    private static IllegalArgumentException conversionFailure(
        Method method,
        String expectedType,
        String value,
        RuntimeException cause
    ) {
        return new IllegalArgumentException(
            "Configuration value for " + method.toGenericString()
                + " must be " + expectedType + " but was '" + value + "'",
            cause
        );
    }

    private static IllegalArgumentException invalidMethod(Method method, String reason) {
        return new IllegalArgumentException(
            "Configuration method " + method.toGenericString() + " " + reason
        );
    }

    private record ConfigurationInvocationHandler(
        Class<?> configurationType,
        Map<Method, Object> values
    ) implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return invokeObjectMethod(proxy, method, arguments);
            }
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, arguments);
            }
            if (!values.containsKey(method)) {
                throw new IllegalStateException(
                    "No bound value for configuration method " + method.toGenericString()
                );
            }
            return values.get(method);
        }

        private Object invokeObjectMethod(Object proxy, Method method, Object[] arguments) {
            return switch (method.getName()) {
                case "equals" -> proxy == arguments[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> configurationType.getSimpleName() + "[redacted]";
                default -> throw new IllegalStateException(
                    "Unsupported Object method " + method.toGenericString()
                );
            };
        }
    }
}
