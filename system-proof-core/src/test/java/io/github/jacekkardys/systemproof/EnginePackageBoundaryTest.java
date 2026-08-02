package io.github.jacekkardys.systemproof;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class EnginePackageBoundaryTest {
    private static final Path CLASSES = Path.of("target/classes");
    private static final String BASE_PATH = "io/github/jacekkardys/systemproof/";
    private static final String BASE_PACKAGE = "io.github.jacekkardys.systemproof.";

    @Test
    void shouldKeepTheEngineRootEmptyAndExposeOnlyDeliberateExecutionBoundaries()
        throws IOException {
        Path engine = CLASSES.resolve(BASE_PATH + "engine");
        try (Stream<Path> files = Files.list(engine)) {
            assertThat(files.filter(Files::isRegularFile))
                .noneMatch(path -> path.toString().endsWith(".class"));
        }

        Path execution = engine.resolve("execution");
        List<String> publicTypes;
        try (Stream<Path> files = Files.list(execution)) {
            publicTypes = files
                .filter(path -> path.toString().endsWith(".class"))
                .filter(path -> !path.getFileName().toString().contains("$"))
                .filter(path -> !path.getFileName().toString().equals("package-info.class"))
                .map(EnginePackageBoundaryTest::loadExecutionType)
                .filter(type -> Modifier.isPublic(type.getModifiers()))
                .map(Class::getSimpleName)
                .sorted()
                .toList();
        }

        assertThat(publicTypes).containsExactly(
            "EnvironmentRuntime",
            "EnvironmentStartException",
            "RuntimeEndpointBindings"
        );
    }

    @Test
    void shouldKeepJournalAndDiagnosticsIndependentOfExecutionImplementations()
        throws IOException {
        for (String packageName : List.of("journal", "diagnostics")) {
            try (Stream<Path> classes = Files.walk(CLASSES.resolve(BASE_PATH + packageName))) {
                assertThat(classes.filter(path -> path.toString().endsWith(".class")))
                    .allSatisfy(path -> assertThat(readBytecode(path))
                        .doesNotContain(BASE_PATH + "engine/execution/"));
            }
        }
    }

    private static Class<?> loadExecutionType(Path path) {
        String fileName = path.getFileName().toString();
        String simpleName = fileName.substring(0, fileName.length() - ".class".length());
        try {
            return Class.forName(BASE_PACKAGE + "engine.execution." + simpleName);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Cannot load execution type " + simpleName, exception);
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
