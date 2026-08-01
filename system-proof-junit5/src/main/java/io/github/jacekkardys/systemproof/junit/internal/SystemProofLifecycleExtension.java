package io.github.jacekkardys.systemproof.junit.internal;

import io.github.jacekkardys.systemproof.engine.EnvironmentStartException;
import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;
import lombok.val;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/** Internal callback owning one System Proof environment lifecycle per JUnit test invocation. */
public final class SystemProofLifecycleExtension implements BeforeEachCallback, AfterEachCallback {

    private final EnvironmentDefinitionLocator definitionLocator = new EnvironmentDefinitionLocator();
    private final SystemProofTestParameterValidator parameterValidator =
        new SystemProofTestParameterValidator();
    private final SystemProofDiagnostics diagnostics = new SystemProofDiagnostics();
    private final SystemProofLifecycleFailureAdapter failures =
        new SystemProofLifecycleFailureAdapter();

    @Override
    public void beforeEach(ExtensionContext context) {
        val declaration = findSystemProof(context);
        val definition = definitionLocator.locate(declaration.environment());
        parameterValidator.validate(
            context.getRequiredTestMethod(),
            definition.environmentType()
        );

        try {
            val environment = definition.invoke().start();
            SystemProofSharedContext.of(context).putEnvironment(environment);
        } catch (EnvironmentStartException failure) {
            diagnostics.onEnvironmentStartFailure(context, failure);
            throw failure;
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        val environment = SystemProofSharedContext.of(context).removeEnvironment();
        if (environment == null) {
            return;
        }

        diagnostics.beforeEnvironmentClose(context, environment);
        val close = failures.execute(context, environment::close);
        diagnostics.afterEnvironmentClose(context, environment, close.failure());
        close.propagateIfPrimary();
    }

    private static SystemProof findSystemProof(ExtensionContext context) {
        return AnnotationSupport
            .findAnnotation(context.getRequiredTestClass(), SystemProof.class)
            .orElseThrow(() -> new ExtensionConfigurationException(
                "SystemProofLifecycleExtension requires @SystemProof on class '"
                    + context.getRequiredTestClass().getName() + "'"
            ));
    }
}
