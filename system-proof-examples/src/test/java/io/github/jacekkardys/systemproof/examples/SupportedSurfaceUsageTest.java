package io.github.jacekkardys.systemproof.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SupportedSurfaceUsageTest {
    @Test
    void shouldUseOnlySupportedFrameworkPackagesAndTypes() throws IOException {
        String root = "io.github.jacekkardys.systemproof.";
        List<String> forbidden = List.of(
            root + "model.",
            root + "api.",
            root + "construction.",
            root + "engine." + "execution.",
            root + "junit.internal.",
            root + "configuration.ConfigurationBinder",
            root + "configuration.ConfigurationValidator",
            root + "configuration.ConfigurationValues",
            root + "environment.EnvironmentRuntime",
            root + "environment.RuntimeEndpointBindings"
        );

        try (Stream<Path> sources = Files.walk(Path.of("src/test/java"))) {
            assertThat(sources.filter(path -> path.toString().endsWith(".java")))
                .allSatisfy(path -> {
                    String source = read(path);
                    assertThat(forbidden).allSatisfy(name ->
                        assertThat(source).doesNotContain(name)
                    );
                });
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot inspect " + path, exception);
        }
    }
}
