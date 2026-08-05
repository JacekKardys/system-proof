package io.github.jacekkardys.systemproof.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class HttpPublicSurfaceTest {
    private static final Path CLASSES = Path.of("target/classes");
    private static final String BASE_PATH = "io/github/jacekkardys/systemproof/http/";
    private static final String BASE_PACKAGE = "io.github.jacekkardys.systemproof.http.";
    private static final Set<String> EXPECTED_TYPES = types("""
        HttpEvidence
        HttpEvidence$Acknowledgement
        HttpEvidence$RequestMethod
        HttpEvidence$RequestTarget
        HttpEvidence$RequestCompleted
        HttpEvidence$ResponseCompleted
        HttpExchangeRef
        HttpProtocolAdapter
        HttpProtocolLimits
        HttpRequestCorrelation
        HttpRequestInteraction
        HttpRequestInteraction$Body
        """);

    @Test
    void shouldExposeOnlyThePinnedHttpApiSpiAndReadModels() throws IOException {
        assertThat(externallyVisibleTypes()).containsExactlyElementsOf(EXPECTED_TYPES);
    }

    @Test
    void shouldKeepParserSessionHeadersAndBuffersInternal() throws IOException {
        assertThat(externallyVisibleTypes())
            .noneMatch(type -> type.toLowerCase().contains("parser"))
            .noneMatch(type -> type.toLowerCase().contains("session"))
            .noneMatch(type -> type.toLowerCase().contains("header"))
            .noneMatch(type -> type.toLowerCase().contains("buffer"));
    }

    @Test
    void shouldKeepMainBytecodeIndependentOfFrameworkAndTestImplementations()
        throws IOException {
        try (Stream<Path> classes = Files.walk(CLASSES.resolve(BASE_PATH))) {
            assertThat(classes.filter(path -> path.toString().endsWith(".class")))
                .allSatisfy(path -> assertThat(readBytecode(path))
                    .doesNotContain("org/junit/")
                    .doesNotContain("org/springframework/")
                    .doesNotContain("org/testcontainers/"));
        }
    }

    private static Set<String> externallyVisibleTypes() throws IOException {
        try (Stream<Path> classes = Files.walk(CLASSES.resolve(BASE_PATH))) {
            return classes
                .filter(path -> path.toString().endsWith(".class"))
                .filter(path -> !path.getFileName().toString().equals("package-info.class"))
                .map(HttpPublicSurfaceTest::loadType)
                .filter(type -> Modifier.isPublic(type.getModifiers())
                    || Modifier.isProtected(type.getModifiers()))
                .map(type -> type.getName().substring(BASE_PACKAGE.length()))
                .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Set<String> types(String names) {
        return Arrays.stream(names.strip().split("\\s+"))
            .filter(name -> !name.isBlank())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Class<?> loadType(Path path) {
        String binaryName = CLASSES.relativize(path).toString()
            .replace('/', '.')
            .replace('\\', '.');
        binaryName = binaryName.substring(0, binaryName.length() - ".class".length());
        try {
            return Class.forName(binaryName);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Cannot load type " + binaryName, exception);
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
