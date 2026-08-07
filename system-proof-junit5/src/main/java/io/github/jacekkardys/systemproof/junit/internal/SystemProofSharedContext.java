package io.github.jacekkardys.systemproof.junit.internal;

import io.github.jacekkardys.systemproof.environment.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.environment.Environment;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;

/** The only facade through which System Proof reads or mutates a JUnit extension context. */
final class SystemProofSharedContext {

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(SystemProofSharedContext.class);
    private static final String ENVIRONMENT = "environment";
    private static final String PENDING_DIAGNOSTICS = "pending-diagnostics";
    private static final String LIFECYCLE_FAILURE = "lifecycle-failure";

    private final ExtensionContext context;

    private SystemProofSharedContext(ExtensionContext context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    static SystemProofSharedContext of(ExtensionContext context) {
        return new SystemProofSharedContext(context);
    }

    RunningEnvironment getRunningEnvironment() {
        return store().get(ENVIRONMENT, RunningEnvironment.class);
    }

    void putRunningEnvironment(
        Class<? extends Environment> declaredType,
        Environment instance
    ) {
        store().put(ENVIRONMENT, new RunningEnvironment(declaredType, instance));
    }

    RunningEnvironment removeRunningEnvironment() {
        return store().remove(ENVIRONMENT, RunningEnvironment.class);
    }

    Optional<Method> testMethod() {
        return context.getTestMethod();
    }

    Method requiredTestMethod() {
        return context.getRequiredTestMethod();
    }

    Optional<Throwable> executionException() {
        return context.getExecutionException();
    }

    void putPendingDiagnostics(EnvironmentDiagnostics diagnostics) {
        store().put(PENDING_DIAGNOSTICS, diagnostics);
    }

    EnvironmentDiagnostics removePendingDiagnostics() {
        return store().remove(PENDING_DIAGNOSTICS, EnvironmentDiagnostics.class);
    }

    void putLifecycleFailure(Throwable failure) {
        store().put(LIFECYCLE_FAILURE, failure);
    }

    Throwable removeLifecycleFailure() {
        return store().remove(LIFECYCLE_FAILURE, Throwable.class);
    }

    void publishReportEntry(String key, String value) {
        context.publishReportEntry(key, value);
    }

    void publishReportEntry(Map<String, String> entries) {
        context.publishReportEntry(entries);
    }

    private ExtensionContext.Store store() {
        return context.getStore(NAMESPACE);
    }
}
