package io.github.jacekkardys.systemproof;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.engine.execution.RuntimeEndpointBindings;
import io.github.jacekkardys.systemproof.model.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.model.topology.ProvidedPort;

class CoreModuleBoundaryTest {
    @Test
    void shouldKeepMainBytecodeIndependentOfJunitTestcontainersAndServiceDiscovery() throws IOException {
        try (Stream<Path> classes = Files.walk(Path.of("target/classes"))) {
            assertThat(classes.filter(path -> path.toString().endsWith(".class")))
                .allSatisfy(path -> assertThat(readBytecode(path))
                    .doesNotContain("org/junit/")
                    .doesNotContain("org/testcontainers/")
                    .doesNotContain("java/util/ServiceLoader"));
        }
    }

    @Test
    void shouldNotExposeProviderEndpointLookupThroughComponentRuntimePublicApi() {
        assertThat(ComponentRuntime.class.getMethods())
            .noneMatch(method ->
                method.getName().equals("binding")
                    || method.getName().equals("resolve")
                    || method.getReturnType().equals(EndpointBinding.class)
                    || (Arrays.asList(method.getParameterTypes()).contains(ProvidedPort.class)
                        && !method.getReturnType().equals(boolean.class))
            );
        assertThat(RuntimeEndpointBindings.class.getConstructors()).isEmpty();
        assertThat(RuntimeEndpointBindings.class.getMethods())
            .noneMatch(method -> method.getReturnType().equals(EndpointBinding.class));
    }

    private static String readBytecode(Path path) {
        try {
            return new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect " + path, exception);
        }
    }
}
