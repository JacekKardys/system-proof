package io.github.jacekkardys.systemproof.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class TestcontainersPublicSurfaceTest {
    private static final Path CLASSES = Path.of("target/classes");
    private static final String BASE_PATH =
        "io/github/jacekkardys/systemproof/testcontainers/";
    private static final String BASE_PACKAGE =
        "io.github.jacekkardys.systemproof.testcontainers.";

    private static final Set<String> SUPPORTED_API = types("""
        component.ContainerDriver
        component.ContainerPlan
        component.ContainerPlan$Builder
        component.PortBinding
        gateway.InteractionGateway
        gateway.TcpEndpointAdapter
        """);

    private static final Set<String> SUPPORTED_SPI = types("""
        component.ContainerDriver$OperationsFactory
        component.ContainerDriver$PlanFactory
        component.RuntimeEndpointFactory
        component.StartedContainer
        component.TestcontainersDriver
        gateway.ProtocolAdapter
        gateway.ProtocolAdapterException
        gateway.ProtocolObservationContract
        gateway.ProtocolSession
        gateway.ProtocolStream
        gateway.TcpEndpointAdapter$AddressReplacement
        """);

    private static final Set<String> READ_ONLY_MODEL = types("""
        gateway.ProtocolDecodeResult
        gateway.ProtocolDecodeResult$Complete
        gateway.ProtocolDecodeResult$NeedMoreData
        gateway.ProtocolFailureKind
        gateway.ProtocolLimits
        gateway.ProtocolUnit
        """);

    private static final Set<String> JAVA_PUBLIC_INTERNAL = Set.of();

    private static final Set<String> PUBLIC_FIELDS = types("""
        gateway.ProtocolFailureKind#AMBIGUOUS_FRAMING:gateway.ProtocolFailureKind
        gateway.ProtocolFailureKind#DESYNCHRONIZATION:gateway.ProtocolFailureKind
        gateway.ProtocolFailureKind#EXCESSIVE_BUFFERED_BYTES:gateway.ProtocolFailureKind
        gateway.ProtocolFailureKind#EXCESSIVE_FRAME_SIZE:gateway.ProtocolFailureKind
        gateway.ProtocolFailureKind#MALFORMED_INPUT:gateway.ProtocolFailureKind
        gateway.ProtocolFailureKind#UNSUPPORTED_ENCRYPTION:gateway.ProtocolFailureKind
        gateway.ProtocolFailureKind#UNSUPPORTED_NEGOTIATION:gateway.ProtocolFailureKind
        """);

    @Test
    void shouldExposeOnlyTheSupportedContainerAndGatewayApiSpiAndReadModels()
        throws IOException {
        assertPairwiseDisjoint(SUPPORTED_API, SUPPORTED_SPI, READ_ONLY_MODEL, JAVA_PUBLIC_INTERNAL);
        Set<String> classified = new TreeSet<>();
        classified.addAll(SUPPORTED_API);
        classified.addAll(SUPPORTED_SPI);
        classified.addAll(READ_ONLY_MODEL);
        classified.addAll(JAVA_PUBLIC_INTERNAL);

        assertThat(externallyVisibleTypes()).containsExactlyElementsOf(classified);
        assertThat(JAVA_PUBLIC_INTERNAL).isEmpty();
    }

    @Test
    void shouldPinPublicFieldsAndSensitiveContainerSurface() throws IOException {
        assertThat(externallyVisibleFields()).containsExactlyInAnyOrderElementsOf(PUBLIC_FIELDS);
        assertThat(methodNames(loadType("component.ContainerPlan")))
            .containsExactly("container");
        assertThat(methodNames(loadType("component.ContainerPlan$Builder")))
            .containsExactly("build", "provides");
        assertThat(methodNames(loadType("component.TestcontainersDriver")))
            .containsExactly(
                "afterStart",
                "create",
                "createOperations",
                "sanitizeContainerOutput",
                "start"
            )
            .doesNotContain("networkAlias");
    }

    @Test
    void shouldKeepTestcontainersIndependentOfJunitImplementation() throws IOException {
        try (Stream<Path> classes = Files.walk(CLASSES)) {
            assertThat(classes.filter(path -> path.toString().endsWith(".class")))
                .allSatisfy(path -> assertThat(readBytecode(path))
                    .doesNotContain("org/junit/")
                    .doesNotContain("io/github/jacekkardys/systemproof/junit/"));
        }
    }

    private static Set<String> externallyVisibleTypes() throws IOException {
        try (Stream<Path> classes = Files.walk(CLASSES.resolve(BASE_PATH))) {
            return classes
                .filter(path -> path.toString().endsWith(".class"))
                .filter(path -> !path.getFileName().toString().equals("package-info.class"))
                .map(TestcontainersPublicSurfaceTest::loadType)
                .filter(type -> Modifier.isPublic(type.getModifiers())
                    || Modifier.isProtected(type.getModifiers()))
                .map(type -> type.getName().substring(BASE_PACKAGE.length()))
                .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Set<String> externallyVisibleFields() throws IOException {
        return externallyVisibleTypes().stream()
            .map(TestcontainersPublicSurfaceTest::loadType)
            .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
            .filter(field -> Modifier.isPublic(field.getModifiers())
                || Modifier.isProtected(field.getModifiers()))
            .map(TestcontainersPublicSurfaceTest::fieldKey)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String fieldKey(Field field) {
        return shortTypeName(field.getDeclaringClass()) + "#" + field.getName() + ":"
            + shortTypeName(field.getType());
    }

    private static Set<String> methodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers())
                || Modifier.isProtected(method.getModifiers()))
            .map(Method::getName)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String shortTypeName(Class<?> type) {
        String name = type.getName();
        return name.startsWith(BASE_PACKAGE) ? name.substring(BASE_PACKAGE.length()) : name;
    }

    @SafeVarargs
    private static void assertPairwiseDisjoint(Set<String>... categories) {
        Set<String> seen = new HashSet<>();
        for (Set<String> category : categories) {
            assertThat(category).allSatisfy(type -> assertThat(seen.add(type)).isTrue());
        }
    }

    private static Set<String> types(String names) {
        return Arrays.stream(names.strip().split("\\s+"))
            .filter(name -> !name.isBlank())
            .collect(Collectors.toUnmodifiableSet());
    }

    private static Class<?> loadType(String relativeName) {
        try {
            return Class.forName(BASE_PACKAGE + relativeName);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Cannot load type " + relativeName, exception);
        }
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
