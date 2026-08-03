package io.github.jacekkardys.systemproof.junit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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

class Junit5ModuleBoundaryTest {
    private static final Path CLASSES = Path.of("target/classes");
    private static final String BASE_PATH = "io/github/jacekkardys/systemproof/junit/";
    private static final String BASE_PACKAGE = "io.github.jacekkardys.systemproof.junit.";

    private static final Set<String> SUPPORTED_API = Set.of(
        "annotation.EnvironmentDefinition",
        "annotation.SystemProof"
    );
    private static final Set<String> SUPPORTED_SPI = Set.of();
    private static final Set<String> READ_ONLY_MODEL = Set.of();
    private static final Set<String> JAVA_PUBLIC_INTERNAL = Set.of(
        "internal.EnvironmentLifecycleExtension",
        "internal.EnvironmentParameterResolver",
        "internal.SystemProofInvocationProvider"
    );

    @Test
    void shouldExposeOnlyAnnotationsAsSupportedApiAndThreeReflectiveExtensions()
        throws IOException {
        assertPairwiseDisjoint(SUPPORTED_API, SUPPORTED_SPI, READ_ONLY_MODEL, JAVA_PUBLIC_INTERNAL);
        Set<String> classified = new TreeSet<>();
        classified.addAll(SUPPORTED_API);
        classified.addAll(SUPPORTED_SPI);
        classified.addAll(READ_ONLY_MODEL);
        classified.addAll(JAVA_PUBLIC_INTERNAL);

        assertThat(externallyVisibleTypes()).containsExactlyElementsOf(classified);
        assertThat(SUPPORTED_SPI).isEmpty();
        assertThat(READ_ONLY_MODEL).isEmpty();
        assertThat(externallyVisibleFields()).isEmpty();
    }

    @Test
    void shouldPinTheUnsupportedReflectiveExtensionSurface() {
        assertThat(methodKeys(loadType("internal.EnvironmentLifecycleExtension")))
            .containsExactly(
                "afterEach(org.junit.jupiter.api.extension.ExtensionContext):void",
                "beforeEach(org.junit.jupiter.api.extension.ExtensionContext):void"
            );
        assertThat(methodKeys(loadType("internal.EnvironmentParameterResolver")))
            .containsExactly(
                "resolveParameter(org.junit.jupiter.api.extension.ParameterContext,org.junit.jupiter.api.extension.ExtensionContext):java.lang.Object",
                "supportsParameter(org.junit.jupiter.api.extension.ParameterContext,org.junit.jupiter.api.extension.ExtensionContext):boolean"
            );
        assertThat(methodKeys(loadType("internal.SystemProofInvocationProvider")))
            .containsExactly(
                "provideTestTemplateInvocationContexts(org.junit.jupiter.api.extension.ExtensionContext):java.util.stream.Stream",
                "supportsTestTemplate(org.junit.jupiter.api.extension.ExtensionContext):boolean"
            );

        assertThat(Set.of(
            "internal.EnvironmentLifecycleExtension",
            "internal.EnvironmentParameterResolver",
            "internal.SystemProofInvocationProvider"
        )).allSatisfy(name -> {
            Class<?> type = loadType(name);
            assertThat(type.getDeclaredConstructors()).hasSize(1);
            assertThat(type.getDeclaredConstructors()[0].getParameterCount()).isZero();
            assertThat(Modifier.isPublic(type.getDeclaredConstructors()[0].getModifiers()))
                .isTrue();
        });
    }

    @Test
    void shouldKeepMainBytecodeIndependentOfTestcontainers() throws IOException {
        try (Stream<Path> classes = Files.walk(CLASSES)) {
            assertThat(classes.filter(path -> path.toString().endsWith(".class")))
                .allSatisfy(path -> assertThat(readBytecode(path))
                    .doesNotContain("org/testcontainers/")
                    .doesNotContain("io/github/jacekkardys/systemproof/testcontainers/"));
        }
    }

    private static Set<String> externallyVisibleTypes() throws IOException {
        try (Stream<Path> classes = Files.walk(CLASSES.resolve(BASE_PATH))) {
            return classes
                .filter(path -> path.toString().endsWith(".class"))
                .filter(path -> !path.getFileName().toString().equals("package-info.class"))
                .map(Junit5ModuleBoundaryTest::loadType)
                .filter(type -> Modifier.isPublic(type.getModifiers())
                    || Modifier.isProtected(type.getModifiers()))
                .map(type -> type.getName().substring(BASE_PACKAGE.length()))
                .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Set<String> externallyVisibleFields() throws IOException {
        return externallyVisibleTypes().stream()
            .map(Junit5ModuleBoundaryTest::loadType)
            .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
            .filter(field -> Modifier.isPublic(field.getModifiers())
                || Modifier.isProtected(field.getModifiers()))
            .map(field -> field.getDeclaringClass().getName() + "#" + field.getName())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    @SafeVarargs
    private static void assertPairwiseDisjoint(Set<String>... categories) {
        Set<String> seen = new HashSet<>();
        for (Set<String> category : categories) {
            assertThat(category).allSatisfy(type -> assertThat(seen.add(type)).isTrue());
        }
    }

    private static Set<String> methodKeys(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers())
                || Modifier.isProtected(method.getModifiers()))
            .map(Junit5ModuleBoundaryTest::methodKey)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String methodKey(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
            .map(Class::getName)
            .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + "):" + method.getReturnType().getName();
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
