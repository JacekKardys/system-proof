package io.github.jacekkardys.systemproof.junit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.jacekkardys.systemproof.junit.internal.execution.EnvironmentDefinitionLocator;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofDiagnostics;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofLifecycleFailureAdapter;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofMetadataReporter;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofParameterValidator;
import io.github.jacekkardys.systemproof.junit.internal.execution.SystemProofSharedContext;
import io.github.jacekkardys.systemproof.junit.internal.spi.SystemProofInvocationContextProvider;
import io.github.jacekkardys.systemproof.junit.internal.spi.SystemProofLifecycleExtension;
import io.github.jacekkardys.systemproof.junit.internal.spi.SystemProofParameterResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.Extension;

class Junit5ModuleBoundaryTest {
    @Test
    void shouldSeparateJunitSpiAdaptersFromExecutionBehavior() {
        assertThat(List.of(
            SystemProofInvocationContextProvider.class,
            SystemProofLifecycleExtension.class,
            SystemProofParameterResolver.class
        )).allSatisfy(type -> {
            assertThat(type.getPackageName()).endsWith(".internal.spi");
            assertThat(Extension.class.isAssignableFrom(type)).isTrue();
        });

        assertThat(List.of(
            EnvironmentDefinitionLocator.class,
            SystemProofDiagnostics.class,
            SystemProofLifecycleFailureAdapter.class,
            SystemProofMetadataReporter.class,
            SystemProofParameterValidator.class,
            SystemProofSharedContext.class
        )).allSatisfy(type -> {
            assertThat(type.getPackageName()).endsWith(".internal.execution");
            assertThat(Extension.class.isAssignableFrom(type)).isFalse();
        });
    }

    @Test
    void shouldKeepMainBytecodeIndependentOfTestcontainers() throws IOException {
        try (Stream<Path> classes = Files.walk(Path.of("target/classes"))) {
            assertThat(classes.filter(path -> path.toString().endsWith(".class")))
                .allSatisfy(path -> assertThat(readBytecode(path))
                    .doesNotContain("org/testcontainers/")
                    .doesNotContain("io/github/jacekkardys/systemproof/testcontainers/"));
        }
    }

    private static String readBytecode(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect " + path, exception);
        }
    }
}
