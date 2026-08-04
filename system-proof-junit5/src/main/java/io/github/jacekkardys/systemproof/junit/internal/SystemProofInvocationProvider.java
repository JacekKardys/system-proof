package io.github.jacekkardys.systemproof.junit.internal;

import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.val;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * Validates exclusive test-template ownership and creates one named System Proof invocation.
 *
 * <p>{@code @SystemProof} is a complete test template and cannot be combined with another direct
 * or meta-annotated {@link TestTemplate} declaration. Conflicts fail before any invocation context
 * or environment lifecycle is created.
 */
public final class SystemProofInvocationProvider implements TestTemplateInvocationContextProvider {

    @Override
    public boolean supportsTestTemplate(ExtensionContext context) {
        val testMethod = SystemProofSharedContext.of(context).testMethod();
        if (testMethod.isEmpty() || findSystemProof(testMethod.get()).isEmpty()) {
            return false;
        }

        validateExclusiveTemplateOwnership(testMethod.get());
        return true;
    }

    @Override
    public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
        ExtensionContext context
    ) {
        val testMethod = SystemProofSharedContext.of(context).requiredTestMethod();
        val declaration = findSystemProof(testMethod).orElseThrow(() ->
            new ExtensionConfigurationException(
                "SystemProofInvocationProvider requires @SystemProof on method '"
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

    private static void validateExclusiveTemplateOwnership(Method method) {
        val competingAnnotations = competingTestTemplateAnnotations(method);
        if (competingAnnotations.isEmpty()) {
            return;
        }

        val annotationNames = competingAnnotations.stream()
            .map(annotationType -> "@" + annotationType.getSimpleName())
            .toList();
        val ownership = annotationNames.size() == 1
            ? "both annotations define"
            : "all annotations define";
        throw new ExtensionConfigurationException(
            "@SystemProof cannot be combined with " + String.join(", ", annotationNames)
                + " because " + ownership + " test-template invocations. "
                + "Combining @SystemProof with another @TestTemplate-based annotation "
                + "is not supported."
        );
    }

    private static List<Class<? extends Annotation>> competingTestTemplateAnnotations(
        Method method
    ) {
        return Arrays.stream(method.getDeclaredAnnotations())
            .map(Annotation::annotationType)
            .filter(annotationType -> annotationType != SystemProof.class)
            .filter(annotationType -> annotationType == TestTemplate.class
                || AnnotationSupport.isAnnotated(annotationType, TestTemplate.class))
            .sorted(Comparator
                .comparing((Class<? extends Annotation> type) -> type.getSimpleName())
                .thenComparing(Class::getName))
            .toList();
    }

    private record InvocationContext(String displayName) implements TestTemplateInvocationContext {

        @Override
        public String getDisplayName(int invocationIndex) {
            return displayName;
        }
    }
}
