package io.github.jacekkardys.systemproof.junit;

import io.github.jacekkardys.systemproof.model.Environment;
import lombok.Value;
import org.junit.jupiter.api.extension.ExtensionContext;

@Value(staticConstructor = "of")
public class SystemProofSharedContext {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(SystemProofSharedContext.class);
    private static final String ENVIRONMENT = "environment";
    private static final String TEST_FAILURE = "test-failure";

    ExtensionContext context;

    public Environment getEnvironment() {
        return store().get(ENVIRONMENT, Environment.class);
    }

    public void putEnvironment(Environment environment) {
        store().put(ENVIRONMENT, environment);
    }

    public Environment removeEnvironment() {
        return store().remove(ENVIRONMENT, Environment.class);
    }

    public void putTestFailure(Throwable testFailure) {
        store().put(TEST_FAILURE, testFailure);
    }

    public Throwable removeTestFailure() {
        return store().remove(TEST_FAILURE, Throwable.class);
    }

    private ExtensionContext.Store store() {
        return context.getStore(NAMESPACE);
    }
}
