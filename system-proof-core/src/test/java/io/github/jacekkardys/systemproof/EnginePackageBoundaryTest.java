package io.github.jacekkardys.systemproof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.engine.execution.ConnectionRoute;
import io.github.jacekkardys.systemproof.engine.execution.ConnectionRouteContext;
import io.github.jacekkardys.systemproof.engine.execution.ConnectionRouting;
import io.github.jacekkardys.systemproof.engine.execution.CorrelationContribution;
import io.github.jacekkardys.systemproof.engine.execution.EnvironmentRuntime;
import io.github.jacekkardys.systemproof.engine.execution.RuntimeEndpointBindings;
import io.github.jacekkardys.systemproof.diagnostics.JournalRenderer;
import io.github.jacekkardys.systemproof.journal.JournalEntry;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;
import io.github.jacekkardys.systemproof.model.endpoint.EndpointBinding;

class EnginePackageBoundaryTest {
    private static final Path CLASSES = Path.of("target/classes");
    private static final String BASE_PATH = "io/github/jacekkardys/systemproof/";
    private static final String BASE_PACKAGE = "io.github.jacekkardys.systemproof.";

    @Test
    void shouldKeepTheEngineRootEmptyAndExposeOnlyDeliberateExecutionApiAndSpi()
        throws IOException {
        Path engine = CLASSES.resolve(BASE_PATH + "engine");
        try (Stream<Path> files = Files.list(engine)) {
            assertThat(files.filter(Files::isRegularFile))
                .noneMatch(path -> path.toString().endsWith(".class"));
        }

        assertThat(publicTypes("engine/execution", "engine.execution"))
            .containsExactly(
                "engine.execution.ConnectionObservations",
                "engine.execution.ConnectionRoute",
                "engine.execution.ConnectionRouteContext",
                "engine.execution.ConnectionRouteProvider",
                "engine.execution.ConnectionRouting",
                "engine.execution.CorrelationContribution",
                "engine.execution.EnvironmentRuntime",
                "engine.execution.EnvironmentStartException",
                "engine.execution.InteractionSession",
                "engine.execution.ObservationStatusProvider",
                "engine.execution.RuntimeEndpointBindings"
            );
    }

    @Test
    void shouldIncludeDeliberateNestedProofResultsInThePublicSurface() throws IOException {
        assertThat(publicTypes("proof", "proof"))
            .containsExactly(
                "proof.CorrelationCardinality",
                "proof.CorrelationKey",
                "proof.CorrelationKeySchema",
                "proof.CorrelationResult",
                "proof.CorrelationResult$Ambiguous",
                "proof.CorrelationResult$Missing",
                "proof.CorrelationResult$Unique",
                "proof.ProofSubjectRef",
                "proof.ProofSubjects"
            );
        assertThat(publicTypes("observation", "observation"))
            .containsExactly(
                "observation.EvidenceCodec",
                "observation.EvidenceSchemaId",
                "observation.EvidenceSnapshot",
                "observation.FlowDirection",
                "observation.ForwardingDecision",
                "observation.InteractionDecisionCoordinator",
                "observation.InteractionRef",
                "observation.SessionId"
            );
    }

    @Test
    void shouldExposeOnlyJournalVocabularyReadModelsAndTheRenderer() throws Exception {
        assertThat(publicTypes("journal", "journal"))
            .containsExactly(
                "journal.CheckpointEvent",
                "journal.CheckpointEvent$Kind",
                "journal.CheckpointEvent$Stage",
                "journal.CheckpointId",
                "journal.ComponentLifecycleEvent",
                "journal.ConnectionLifecycleEvent",
                "journal.CorrelationCandidateEvent",
                "journal.DiagnosticEvent",
                "journal.DiagnosticEvent$ComponentSubject",
                "journal.DiagnosticEvent$ConnectionSubject",
                "journal.DiagnosticEvent$EnvironmentSubject",
                "journal.DiagnosticEvent$Subject",
                "journal.DisruptionId",
                "journal.DisruptionLifecycleEvent",
                "journal.DisruptionLifecycleEvent$Stage",
                "journal.EnvironmentLifecycleEvent",
                "journal.FailureDetails",
                "journal.FailureEvent",
                "journal.FailureEvent$ComponentCleanup",
                "journal.FailureEvent$ComponentStartup",
                "journal.FailureEvent$ConnectionCleanup",
                "journal.FailureEvent$ConnectionMaterialization",
                "journal.FailureEvent$DriverResourceCleanup",
                "journal.FailureEvent$EnvironmentStartup",
                "journal.InteractionObservationEvent",
                "journal.JournalEntry",
                "journal.JournalSequence",
                "journal.ProofSubjectArmedEvent",
                "journal.ProofSubjectCreatedEvent",
                "journal.ScenarioEvent",
                "journal.ScenarioJournalSnapshot"
            );
        assertThat(publicTypes("diagnostics", "diagnostics"))
            .containsExactly(
                "diagnostics.EnvironmentDiagnostics",
                "diagnostics.JournalRenderer"
            );

        assertThatThrownBy(() -> Class.forName(BASE_PACKAGE + "journal.ScenarioJournal"))
            .isInstanceOf(ClassNotFoundException.class);
        Class<?> storage = Class.forName(
            BASE_PACKAGE + "engine.execution.ScenarioJournal"
        );
        assertThat(Modifier.isPublic(storage.getModifiers())).isFalse();
        assertThat(storage.getDeclaredConstructors())
            .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers()));
        assertThat(storage.getDeclaredMethods())
            .filteredOn(method -> method.getName().equals("append"))
            .noneMatch(method -> Modifier.isPublic(method.getModifiers()));

        assertThat(JournalRenderer.class.getDeclaredMethods())
            .filteredOn(method -> Modifier.isPublic(method.getModifiers()))
            .noneMatch(method ->
                method.getName().equals("append")
                    || method.getName().equals("publish")
                    || Arrays.asList(method.getParameterTypes()).contains(ScenarioEvent.class)
            );
    }

    @Test
    void shouldKeepOneStorageOwnerIndependentOfRenderingAndSlf4j() throws Exception {
        Path storage = CLASSES.resolve(
            BASE_PATH + "engine/execution/ScenarioJournal.class"
        );
        assertThat(readBytecode(storage))
            .doesNotContain(
                BASE_PATH + "diagnostics/",
                "org/slf4j/",
                "EnvironmentLogging"
            );

        assertThat(classFiles("engine/execution").stream()
            .map(path -> loadType("engine.execution", path))
            .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
            .filter(field -> Collection.class.isAssignableFrom(field.getType())
                || Map.class.isAssignableFrom(field.getType()))
            .filter(field -> {
                String fieldType = field.getGenericType().getTypeName();
                return fieldType.contains("journal.JournalEntry")
                    || fieldType.contains("journal.ScenarioEvent");
            }))
            .extracting(field -> field.getDeclaringClass().getName())
            .containsExactly(BASE_PACKAGE + "engine.execution.ScenarioJournal");
    }

    @Test
    void shouldExposeNoGenericJournalMutationEntryPoint() throws IOException {
        assertThat(classFiles("").stream()
            .map(EnginePackageBoundaryTest::loadType)
            .filter(type -> Modifier.isPublic(type.getModifiers()))
            .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> method.getName().equals("append")
                || method.getName().equals("publish"))
            .filter(method -> Arrays.stream(method.getParameterTypes())
                .anyMatch(parameter -> parameter == ScenarioEvent.class
                    || parameter == JournalEntry.class)))
            .isEmpty();
    }

    @Test
    void shouldKeepRuntimeConstructionAndRouteExecutionInternal() throws Exception {
        assertThat(EnvironmentRuntime.class.getDeclaredConstructors())
            .allSatisfy(constructor ->
                assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue()
            );
        assertThat(List.of(
            ConnectionRouting.class,
            ConnectionRoute.class,
            ConnectionRouteContext.class,
            CorrelationContribution.class,
            RuntimeEndpointBindings.class
        )).allSatisfy(type -> assertThat(type.getConstructors()).isEmpty());

        Class<?> selection = Class.forName(
            BASE_PACKAGE + "engine.execution.ConnectionRouting$Selection"
        );
        assertThat(Modifier.isPublic(selection.getModifiers())).isFalse();
        assertThat(selection.getDeclaredConstructors())
            .noneMatch(constructor -> Modifier.isPublic(constructor.getModifiers()));
        assertThat(selection.getDeclaredMethods())
            .noneMatch(method -> Modifier.isPublic(method.getModifiers()));

        assertThat(declaredPublicMethodNames(ConnectionRouting.class))
            .containsExactlyInAnyOrder("direct", "routed", "withRoute");
        assertThat(declaredPublicMethodNames(ConnectionRoute.class))
            .containsExactly("routed");
        assertThat(declaredPublicMethodNames(CorrelationContribution.class))
            .containsExactlyInAnyOrder(
                "capture",
                "key",
                "nativeReferenceSchema",
                "encodedSize",
                "equals",
                "hashCode",
                "toString"
            );
        assertThat(declaredPublicMethodNames(ConnectionRouteContext.class))
            .containsExactlyInAnyOrder(
                "connection",
                "observations",
                "observationRequirement",
                "coordinator",
                "directTarget"
            );
    }

    @Test
    void shouldNotExposeProofAllocationSnapshotsOrProviderEndpointLookup() throws Exception {
        assertThatThrownBy(() -> Class.forName(BASE_PACKAGE + "proof.ProofSubjectScope"))
            .isInstanceOf(ClassNotFoundException.class);
        Method snapshot = CorrelationContribution.class.getDeclaredMethod(
            "nativeReferenceSnapshot"
        );
        assertThat(Modifier.isPublic(snapshot.getModifiers())).isFalse();

        assertThat(RuntimeEndpointBindings.class.getConstructors()).isEmpty();
        assertThat(publicMethods(RuntimeEndpointBindings.class, ConnectionRouting.class,
            ConnectionRoute.class))
            .noneMatch(method ->
                method.getName().equals("select")
                    || method.getName().equals("prepare")
                    || method.getName().equals("consumerTarget")
                    || method.getReturnType().equals(EndpointBinding.class)
            );
    }

    @Test
    void shouldKeepObservationValuesUpstreamOfProofContracts() throws IOException {
        List<Path> observationClasses = classFiles("observation");
        assertThat(observationClasses)
            .allSatisfy(path -> assertThat(readBytecode(path))
                .doesNotContain(BASE_PATH + "proof/"));

        assertThat(classFiles("proof"))
            .anySatisfy(path -> assertThat(readBytecode(path))
                .contains(BASE_PATH + "observation/"));
    }

    @Test
    void shouldKeepJournalAndDiagnosticsIndependentOfExecutionImplementations()
        throws IOException {
        for (String packageName : List.of("journal", "diagnostics")) {
            assertThat(classFiles(packageName))
                .allSatisfy(path -> assertThat(readBytecode(path))
                    .doesNotContain(BASE_PATH + "engine/execution/"));
        }
    }

    private static List<String> publicTypes(String packagePath, String packageName)
        throws IOException {
        try (Stream<Path> files = Files.list(CLASSES.resolve(BASE_PATH + packagePath))) {
            return files
                .filter(path -> path.toString().endsWith(".class"))
                .filter(path -> !path.getFileName().toString().equals("package-info.class"))
                .map(path -> loadType(packageName, path))
                .filter(type -> Modifier.isPublic(type.getModifiers()))
                .map(type -> type.getName().substring(BASE_PACKAGE.length()))
                .sorted()
                .toList();
        }
    }

    private static Class<?> loadType(String packageName, Path path) {
        String fileName = path.getFileName().toString();
        String binaryName = fileName.substring(0, fileName.length() - ".class".length());
        try {
            return Class.forName(BASE_PACKAGE + packageName + "." + binaryName);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException("Cannot load type " + binaryName, exception);
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

    private static Set<String> declaredPublicMethodNames(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .map(Method::getName)
            .collect(java.util.stream.Collectors.toSet());
    }

    private static Stream<Method> publicMethods(Class<?>... types) {
        return Arrays.stream(types)
            .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .filter(method -> Modifier.isPublic(method.getModifiers()));
    }

    private static List<Path> classFiles(String packageName) throws IOException {
        try (Stream<Path> classes = Files.walk(CLASSES.resolve(BASE_PATH + packageName))) {
            return classes
                .filter(path -> path.toString().endsWith(".class"))
                .toList();
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
