package io.github.jacekkardys.systemproof.testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Test
    void shouldExposeOnlyTheSupportedContainerAndGatewayApiSpiAndReadModels()
        throws IOException {
        assertThat(externallyVisibleTypes())
            .containsExactly(
                "component.ContainerDriver",
                "component.ContainerDriver$OperationsFactory",
                "component.ContainerDriver$PlanFactory",
                "component.ContainerPlan",
                "component.ContainerPlan$Builder",
                "component.PortBinding",
                "component.RuntimeEndpointFactory",
                "component.StartedContainer",
                "component.TestcontainersDriver",
                "diagnostics.ContainerLogConsumer",
                "gateway.InteractionGateway",
                "gateway.ProtocolAdapter",
                "gateway.ProtocolAdapterException",
                "gateway.ProtocolDecodeResult",
                "gateway.ProtocolDecodeResult$Complete",
                "gateway.ProtocolDecodeResult$NeedMoreData",
                "gateway.ProtocolFailureKind",
                "gateway.ProtocolLimits",
                "gateway.ProtocolSession",
                "gateway.ProtocolStream",
                "gateway.ProtocolUnit",
                "gateway.TcpEndpointAdapter",
                "gateway.TcpEndpointAdapter$AddressReplacement"
            );
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
