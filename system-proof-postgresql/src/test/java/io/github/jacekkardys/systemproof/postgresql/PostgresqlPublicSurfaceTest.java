package io.github.jacekkardys.systemproof.postgresql;

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

class PostgresqlPublicSurfaceTest {
    private static final Path CLASSES = Path.of("target/classes");
    private static final String BASE_PATH =
        "io/github/jacekkardys/systemproof/postgresql/";
    private static final String BASE_PACKAGE =
        "io.github.jacekkardys.systemproof.postgresql.";

    private static final Set<String> EXPECTED_TYPES = types("""
        PostgresqlDurabilityRequirements
        PostgresqlDurabilityRequirements$Table
        PostgresqlDurabilityResult
        PostgresqlDurabilityResult$Setting
        PostgresqlDurabilityResult$TablePersistence
        PostgresqlDurabilityResult$TableTriggers
        PostgresqlDurabilityVerifier
        PostgresqlEvidence
        PostgresqlEvidence$AutocommitWrite
        PostgresqlEvidence$BackendError
        PostgresqlEvidence$CommandComplete
        PostgresqlEvidence$CommandTag
        PostgresqlEvidence$CommitAttempt
        PostgresqlEvidence$CommitSucceeded
        PostgresqlEvidence$ProtocolMessage
        PostgresqlEvidence$ProtocolMessageKind
        PostgresqlEvidence$ReadyForQuery
        PostgresqlEvidence$Rollback
        PostgresqlEvidence$StatementExecuted
        PostgresqlEvidence$StatementKind
        PostgresqlEvidence$TransactionStarted
        PostgresqlEvidence$TransactionStatus
        PostgresqlProtocolAdapter
        PostgresqlStatementShape
        PostgresqlStatementShape$Kind
        PostgresqlWriteCorrelation
        PostgresqlWriteInteraction
        PostgresqlWriteInteraction$ParameterFormat
        TransactionRef
        """);

    @Test
    void shouldExposeOnlyThePinnedPostgresqlApiSpiAndReadModels() throws IOException {
        assertThat(externallyVisibleTypes()).containsExactlyElementsOf(EXPECTED_TYPES);
    }

    @Test
    void shouldKeepParserStateBuffersAndTransportInternalsNonPublic() throws IOException {
        assertThat(externallyVisibleTypes())
            .noneMatch(type -> type.toLowerCase().contains("parser"))
            .noneMatch(type -> type.toLowerCase().contains("buffer"))
            .noneMatch(type -> type.toLowerCase().contains("session"))
            .noneMatch(type -> type.toLowerCase().contains("portal"));
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
                .map(PostgresqlPublicSurfaceTest::loadType)
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
