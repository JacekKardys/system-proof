package pl.gov.il.test.harness.junit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class Junit5ModuleBoundaryTest {
    @Test
    void shouldKeepMainBytecodeIndependentOfTestcontainers() throws IOException {
        try (Stream<Path> classes = Files.walk(Path.of("target/classes"))) {
            assertThat(classes.filter(path -> path.toString().endsWith(".class")))
                .allSatisfy(path -> assertThat(readBytecode(path))
                    .doesNotContain("org/testcontainers/")
                    .doesNotContain("pl/gov/il/test/harness/testcontainers/"));
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
