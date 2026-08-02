package io.github.jacekkardys.systemproof.junit.internal.execution;

import io.github.jacekkardys.systemproof.diagnostics.EnvironmentDiagnostics;
import io.github.jacekkardys.systemproof.model.environment.Environment;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.extension.ExtensionContext;

/** The only facade through which System Proof reads or mutates a JUnit extension context. */
public final class SystemProofSharedContext {

    private static final ExtensionContext.Namespace NAMESPACE =
        ExtensionContext.Namespace.create(SystemProofSharedContext.class);
    private static final String ENVIRONMENT = "environment";
    private static final String PENDING_DIAGNOSTICS = "pending-diagnostics";
    private static final String LIFECYCLE_FAILURE = "lifecycle-failure";

    private final ExtensionContext context;

    private SystemProofSharedContext(ExtensionContext context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    public static SystemProofSharedContext of(ExtensionContext context) {
        return new SystemProofSharedContext(context);
    }

    public RunningEnvironment getRunningEnvironment() {
        return store().get(ENVIRONMENT, RunningEnvironment.class);
    }

    public void putRunningEnvironment(
        Class<? extends Environment> declaredType,
        Environment instance
    ) {
        store().put(ENVIRONMENT, new RunningEnvironment(declaredType, instance));
    }

    public RunningEnvironment removeRunningEnvironment() {
        return store().remove(ENVIRONMENT, RunningEnvironment.class);
    }

    public Optional<Method> testMethod() {
        return context.getTestMethod();
    }

    public Method requiredTestMethod() {
        return context.getRequiredTestMethod();
    }

    public Optional<Throwable> executionException() {
        return context.getExecutionException();
    }

    public void putPendingDiagnostics(EnvironmentDiagnostics diagnostics) {
        store().put(PENDING_DIAGNOSTICS, diagnostics);
    }

    public EnvironmentDiagnostics removePendingDiagnostics() {
        return store().remove(PENDING_DIAGNOSTICS, EnvironmentDiagnostics.class);
    }

    public void putLifecycleFailure(Throwable failure) {
        store().put(LIFECYCLE_FAILURE, failure);
    }

    public Throwable removeLifecycleFailure() {
        return store().remove(LIFECYCLE_FAILURE, Throwable.class);
    }

    public void publishReportEntry(String key, String value) {
        context.publishReportEntry(key, value);
    }

    public void publishReportEntry(Map<String, String> entries) {
        context.publishReportEntry(entries);
    }

    private ExtensionContext.Store store() {
        return context.getStore(NAMESPACE);
    }
}
