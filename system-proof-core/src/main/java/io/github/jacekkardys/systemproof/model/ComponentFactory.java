package io.github.jacekkardys.systemproof.model;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Internal materializer backed by one immutable environment-configuration snapshot. */
@RequiredArgsConstructor(staticName = "from")
final class ComponentFactory {
    @NonNull
    private final EnvironmentConfiguration values;

    public <
        C extends RuntimeConfig,
        O,
        T extends AbstractComponent<C, O>
    > T create(
        Class<T> componentClass
    ) {
        return create(componentClass, null);
    }

    public <
        C extends RuntimeConfig,
        O,
        T extends AbstractComponent<C, O>
    > T create(
        Class<T> componentClass,
        String qualifier
    ) {
        return ComponentMetadata.<C, O, T>analyze(componentClass)
            .materialize(qualifier, values);
    }
}
