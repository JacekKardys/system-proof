package io.github.jacekkardys.systemproof.junit.internal.spi;

import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofSharedContext;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.val;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Creates exactly one named JUnit invocation for a System Proof test method.
 */
public final class SystemProofInvocationContextProvider implements TestTemplateInvocationContextProvider {

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        return SystemProofSharedContext.of(context).testMethod()
            .flatMap(SystemProofInvocationContextProvider::findSystemProof)
            .isPresent();
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
        ExtensionContext context
    ) {
        val testMethod = SystemProofSharedContext.of(context).requiredTestMethod();
        val declaration = findSystemProof(testMethod).orElseThrow(() ->
            new ExtensionConfigurationException(
                "SystemProofInvocationContextProvider requires @SystemProof on method '"
                    + testMethod.toGenericString() + "'"
            )
        );
        val title = declaration.title().strip();
        val displayName = title.isEmpty() ? testMethod.getName() : title;
        return Stream.of(new InvocationContext(displayName));
    }

    private static Optional<SystemProof> findSystemProof(Method method) {
        return AnnotationSupport.findAnnotation(method, SystemProof.class);
    }

    private record InvocationContext(String displayName) implements TestTemplateInvocationContext {

        @Override
        public String getDisplayName(int invocationIndex) {
            return displayName;
        }
    }
}
