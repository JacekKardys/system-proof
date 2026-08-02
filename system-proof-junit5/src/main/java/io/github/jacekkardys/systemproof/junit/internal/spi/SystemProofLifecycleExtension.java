package io.github.jacekkardys.systemproof.junit.internal.spi;

import io.github.jacekkardys.systemproof.engine.EnvironmentStartException;
import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;
import io.github.jacekkardys.systemproof.junit.internal.execution.EnvironmentDefinitionLocator;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofDiagnostics;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofLifecycleFailureAdapter;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofMetadataReporter;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofParameterValidator;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofSharedContext;
import lombok.val;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/** Internal callback owning one System Proof environment lifecycle per JUnit test invocation. */
public final class SystemProofLifecycleExtension implements BeforeEachCallback, AfterEachCallback {

    private final EnvironmentDefinitionLocator definitionLocator = new EnvironmentDefinitionLocator();
    private final SystemProofParameterValidator parameterValidator =
        new SystemProofParameterValidator();
    private final SystemProofMetadataReporter metadataReporter = new SystemProofMetadataReporter();
    private final SystemProofDiagnostics diagnostics = new SystemProofDiagnostics();
    private final SystemProofLifecycleFailureAdapter failures =
        new SystemProofLifecycleFailureAdapter();

    @Override
    public void beforeEach(ExtensionContext context) {
        val declaration = findSystemProof(context);
        metadataReporter.report(context, declaration);
        val definition = definitionLocator.locate(declaration.value());
        parameterValidator.validateConfiguration(
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
        val testMethod = context.getRequiredTestMethod();
        return AnnotationSupport
            .findAnnotation(testMethod, SystemProof.class)
            .orElseThrow(() -> new ExtensionConfigurationException(
                "SystemProofLifecycleExtension requires @SystemProof on method '"
                    + testMethod.toGenericString() + "'"
            ));
    }
}
