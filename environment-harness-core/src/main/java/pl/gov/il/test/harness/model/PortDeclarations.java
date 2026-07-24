package pl.gov.il.test.harness.model;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Materializes annotation-based port declarations before a component becomes visible. */
final class PortDeclarations {
    private PortDeclarations() {}

    static void initialize(AbstractComponent<?, ?> component) {
        for (Field field : portFields(component.getClass())) {
            initialize(component, field);
        }
    }

    private static List<Field> portFields(Class<?> componentType) {
        Deque<Class<?>> hierarchy = new ArrayDeque<>();
        Class<?> current = componentType;
        while (current != AbstractComponent.class) {
            hierarchy.push(current);
            current = current.getSuperclass();
        }

        List<Field> fields = new ArrayList<>();
        hierarchy.forEach(type -> {
            for (Field field : type.getDeclaredFields()) {
                if (Port.class.isAssignableFrom(field.getType())) {
                    fields.add(field);
                }
            }
        });
        return fields;
    }

    private static void initialize(AbstractComponent<?, ?> component, Field field) {
        Communication communication = communication(field);
        Class<?> contractType = contractType(field);
        String name = field.getName();
        Contract<?> contract = Contract.contract(
            name,
            contractType
        );
        InteractionSpec interaction = new DeclaredInteraction(communication.interaction());
        ProtocolSpec protocol = new DeclaredProtocol(
            communication.protocol(),
            communication.scheme().isBlank()
                ? communication.protocol()
                : communication.scheme()
        );

        PortRef port;
        if (field.getType() == RequiredPort.class) {
            port = new RequiredPort<>(
                component,
                name,
                contract,
                interaction,
                protocol,
                field.isAnnotationPresent(StartupPrerequisite.class)
            );
        } else if (field.getType() == ProvidedPort.class) {
            if (field.isAnnotationPresent(StartupPrerequisite.class)) {
                throw invalid(field, "@StartupPrerequisite requires field type RequiredPort");
            }
            port = new ProvidedPort<>(
                component,
                name,
                contract,
                interaction,
                protocol
            );
        } else {
            throw invalid(field, "must use RequiredPort or ProvidedPort as its field type");
        }

        set(component, field, component.register(port));
    }

    private static Communication communication(Field field) {
        Communication direct = field.getAnnotation(Communication.class);
        List<Communication> composed = new ArrayList<>();
        for (Annotation annotation : field.getDeclaredAnnotations()) {
            Communication communication = annotation.annotationType()
                .getAnnotation(Communication.class);
            if (communication != null) {
                composed.add(communication);
            }
        }
        if ((direct != null && !composed.isEmpty()) || composed.size() > 1) {
            throw invalid(field, "must declare exactly one communication annotation");
        }
        if (direct != null) {
            return direct;
        }
        if (composed.isEmpty()) {
            throw invalid(field, "must declare @Communication or one composed communication annotation");
        }
        return composed.getFirst();
    }

    private static Class<?> contractType(Field field) {
        Type genericType = field.getGenericType();
        if (!(genericType instanceof ParameterizedType parameterized)
            || parameterized.getActualTypeArguments().length != 1
            || !(parameterized.getActualTypeArguments()[0] instanceof Class<?> fieldContractType)) {
            throw invalid(field, "must declare one concrete port contract type");
        }
        return fieldContractType;
    }

    private static void set(AbstractComponent<?, ?> component, Field field, PortRef port) {
        try {
            if (!field.trySetAccessible()) {
                throw invalid(field, "is not accessible");
            }
            field.set(component, port);
        } catch (IllegalAccessException exception) {
            throw new IllegalArgumentException(
                "Cannot initialize port field '" + qualifiedName(field) + "'",
                exception
            );
        }
    }

    private static IllegalArgumentException invalid(Field field, String reason) {
        return new IllegalArgumentException(
            "Port field '" + qualifiedName(field) + "' " + reason
        );
    }

    private static String qualifiedName(Field field) {
        return field.getDeclaringClass().getName() + "." + field.getName();
    }

    private record DeclaredInteraction(String id) implements InteractionSpec {
        private DeclaredInteraction {
            id = requireText(id, "interaction id");
        }
    }

    private record DeclaredProtocol(String id, String scheme) implements ProtocolSpec {
        private DeclaredProtocol {
            id = requireText(id, "protocol id");
            scheme = requireText(scheme, "protocol scheme");
        }
    }

    private static String requireText(String value, String description) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(description + " must not be blank");
        }
        return value;
    }
}
