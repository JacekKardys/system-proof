package io.github.jacekkardys.systemproof.junit.internal.spi;

import io.github.jacekkardys.systemproof.engine.EnvironmentStartException;
import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;
import io.github.jacekkardys.systemproof.junit.internal.execution.EnvironmentDiagnosticsReporter;
import io.github.jacekkardys.systemproof.junit.internal.execution.EnvironmentDefinitionResolver;
import io.github.jacekkardys.systemproof.junit.internal.execution.EnvironmentParameterValidator;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofLifecycleFailureAdapter;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofMetadataReporter;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofSharedContext;
import lombok.val;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;

/** Internal callback owning one System Proof environment lifecycle per JUnit test invocation. */
public final class SystemProofLifecycleExtension implements BeforeEachCallback, AfterEachCallback {

    private final EnvironmentDefinitionResolver environmentDefinitionResolver =
        new EnvironmentDefinitionResolver();
    private final EnvironmentParameterValidator parameterValidator = new EnvironmentParameterValidator();
    private final SystemProofMetadataReporter metadataReporter = new SystemProofMetadataReporter();
    private final EnvironmentDiagnosticsReporter diagnostics = new EnvironmentDiagnosticsReporter();
    private final SystemProofLifecycleFailureAdapter failures = new SystemProofLifecycleFailureAdapter();

    @Override
    public void beforeEach(ExtensionContext context) {
        val sharedContext = SystemProofSharedContext.of(context);
        val declaration = findSystemProof(sharedContext);

        metadataReporter.report(sharedContext, declaration);
        val environmentType = declaration.value();
        parameterValidator.validateConfiguration(
            sharedContext.requiredTestMethod(),
            environmentType
        );

        try {
            val environment = environmentDefinitionResolver.resolve(environmentType);
            environment.start();
            sharedContext.putRunningEnvironment(environmentType, environment);
        } catch (EnvironmentStartException failure) {
            diagnostics.onStartFailure(sharedContext, failure);
            throw failure;
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        val sharedContext = SystemProofSharedContext.of(context);
        val runningEnvironment = sharedContext.removeRunningEnvironment();
        if (runningEnvironment == null) {
            return;
        }
        val environment = runningEnvironment.instance();

        diagnostics.beforeClose(sharedContext, environment);
        val close = failures.execute(sharedContext, environment::close);
        diagnostics.afterClose(sharedContext, environment);
        close.propagateIfPrimary();
    }

    private static SystemProof findSystemProof(SystemProofSharedContext context) {
        val testMethod = context.requiredTestMethod();
        return AnnotationSupport
            .findAnnotation(testMethod, SystemProof.class)
            .orElseThrow(() -> new ExtensionConfigurationException(
                "SystemProofLifecycleExtension requires @SystemProof on method '"
                    + testMethod.toGenericString() + "'"
            ));
    }
}
