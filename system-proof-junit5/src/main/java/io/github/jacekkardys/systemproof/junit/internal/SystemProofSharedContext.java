package io.github.jacekkardys.systemproof.junit.internal;

import io.github.jacekkardys.systemproof.model.Environment;
import java.util.Objects;
import org.junit.jupiter.api.extension.ExtensionContext;

final class SystemProofSharedContext {

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(SystemProofSharedContext.class);
    private static final String ENVIRONMENT = "environment";

    private final ExtensionContext context;

    private SystemProofSharedContext(ExtensionContext context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    static SystemProofSharedContext of(ExtensionContext context) {
        return new SystemProofSharedContext(context);
    }

    Environment getEnvironment() {
        return store().get(ENVIRONMENT, Environment.class);
    }

    void putEnvironment(Environment environment) {
        store().put(ENVIRONMENT, environment);
    }

    Environment removeEnvironment() {
        return store().remove(ENVIRONMENT, Environment.class);
    }

    private ExtensionContext.Store store() {
        return context.getStore(NAMESPACE);
    }
}
