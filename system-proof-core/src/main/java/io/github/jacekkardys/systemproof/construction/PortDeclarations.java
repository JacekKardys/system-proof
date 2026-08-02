package io.github.jacekkardys.systemproof.construction;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import io.github.jacekkardys.systemproof.model.component.AbstractComponent;
import io.github.jacekkardys.systemproof.model.communication.Communication;
import io.github.jacekkardys.systemproof.model.topology.Contract;
import io.github.jacekkardys.systemproof.model.topology.DeclaredInteraction;
import io.github.jacekkardys.systemproof.model.topology.DeclaredProtocol;
import io.github.jacekkardys.systemproof.model.topology.Port;
import io.github.jacekkardys.systemproof.model.topology.PortContract;
import io.github.jacekkardys.systemproof.model.topology.PortRef;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;
import io.github.jacekkardys.systemproof.model.topology.RequiredPort;
import io.github.jacekkardys.systemproof.model.topology.StartupPrerequisite;

/** Materializes annotation-based port declarations during component construction. */
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
        String name = field.getName();
        Contract<?> contract = Contract.contract(contractId(field), contractType(field));
        Communication communication = communication(field);
        var interaction = new DeclaredInteraction(requireText(field, communication.interaction(), "interaction id"));
        String protocolId = requireText(field, communication.protocol(), "protocol id");
        String scheme = communication.scheme().isBlank() ? protocolId : communication.scheme();
        var protocol = new DeclaredProtocol(
            protocolId,
            requireText(field, scheme, "protocol scheme")
        );

        PortRef port;
        if (field.getType() == RequiredPort.class) {
            port = new RequiredPort<>(
                component, name, contract, interaction, protocol,
                field.isAnnotationPresent(StartupPrerequisite.class)
            );
        } else if (field.getType() == ProvidedPort.class) {
            if (field.isAnnotationPresent(StartupPrerequisite.class)) {
                throw invalid(field, "@StartupPrerequisite requires field type RequiredPort");
            }
            port = new ProvidedPort<>(component, name, contract, interaction, protocol);
        } else {
            throw invalid(field, "must use RequiredPort or ProvidedPort as its field type");
        }

        set(component, field, ComponentInitializer.register(component, port));
    }

    private static String contractId(Field field) {
        PortContract declaration = field.getAnnotation(PortContract.class);
        if (declaration == null) {
            throw invalid(field, "must declare @PortContract");
        }
        if (declaration.value().isBlank()) {
            throw invalid(field, "must declare a non-blank @PortContract value");
        }
        return declaration.value();
    }

    private static Communication communication(Field field) {
        Communication direct = field.getAnnotation(Communication.class);
        List<Communication> composed = new ArrayList<>();
        for (Annotation annotation : field.getDeclaredAnnotations()) {
            Communication declaration = annotation.annotationType().getAnnotation(Communication.class);
            if (declaration != null) {
                composed.add(declaration);
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
            throw new IllegalArgumentException("Cannot initialize port field '" + qualifiedName(field) + "'", exception);
        }
    }

    private static IllegalArgumentException invalid(Field field, String reason) {
        return new IllegalArgumentException("Port field '" + qualifiedName(field) + "' " + reason);
    }

    private static String requireText(Field field, String value, String description) {
        if (value == null || value.isBlank()) {
            throw invalid(field, "must declare a non-blank " + description);
        }
        return value;
    }

    private static String qualifiedName(Field field) {
        return field.getDeclaringClass().getName() + "." + field.getName();
    }

}
