package io.github.jacekkardys.systemproof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.component.AbstractComponent;
import io.github.jacekkardys.systemproof.configuration.ConfigurationBinder;
import io.github.jacekkardys.systemproof.configuration.ConfigurationValidator;
import io.github.jacekkardys.systemproof.configuration.ConfigurationValues;
import io.github.jacekkardys.systemproof.driver.ComponentRuntime;
import io.github.jacekkardys.systemproof.endpoint.EndpointBinding;
import io.github.jacekkardys.systemproof.environment.ComponentLifecycleException;
import io.github.jacekkardys.systemproof.environment.ConnectionRoute;
import io.github.jacekkardys.systemproof.environment.ConnectionRouteContext;
import io.github.jacekkardys.systemproof.environment.ConnectionRouting;
import io.github.jacekkardys.systemproof.environment.CorrelationContribution;
import io.github.jacekkardys.systemproof.environment.EnvironmentTopology;
import io.github.jacekkardys.systemproof.environment.RuntimeEndpointBindings;
import io.github.jacekkardys.systemproof.journal.JournalEntry;
import io.github.jacekkardys.systemproof.journal.ScenarioEvent;

class CoreArchitectureTest {
    private static final Path CLASSES = Path.of("target/classes");
    private static final String BASE_PATH = "io/github/jacekkardys/systemproof/";
    private static final String BASE_PACKAGE = "io.github.jacekkardys.systemproof.";

    private static final Set<String> SUPPORTED_API = types("""
        communication.Communication
        communication.Communication$Amqp
        communication.Communication$Http
        communication.Communication$JdbcPostgresql
        communication.Communication$Redis
        communication.Communication$Smpp
        communication.Communication$Tcp
        component.Component
        component.SystemComponent
        configuration.ConfigurationSource
        configuration.EnvironmentConfiguration
        configuration.EnvironmentVariable
        configuration.Literal
        configuration.Secret
        diagnostics.EnvironmentLogging
        diagnostics.EnvironmentLoggingBuilder
        diagnostics.LogLevel
        environment.ComponentLifecycleException
        environment.ComponentPortFactory
        environment.ConnectionRouting
        environment.Environment
        environment.EnvironmentBuilder
        environment.EnvironmentCreator
        environment.EnvironmentStartException
        environment.EnvironmentTopology
        proof.ProofSubjects
        topology.Connection
        topology.Contract
        topology.DeclaredInteraction
        topology.DeclaredProtocol
        topology.InteractionSpec
        topology.Port
        topology.PortContract
        topology.ProtocolSpec
        topology.ProvidedPort
        topology.RequiredPort
        topology.StartupPrerequisite
        """);

    private static final Set<String> SUPPORTED_SPI = types("""
        component.AbstractComponent
        configuration.DriverConfig
        configuration.RuntimeConfig
        configuration.ComponentConfig
        configuration.ConfigurationProvider
        driver.ComponentBoundDriver
        driver.ComponentDriver
        driver.ComponentRuntime
        driver.ComponentRuntime$Builder
        driver.DiagnosticSource
        driver.DriverContext
        driver.DriverResourceKey
        driver.JournalContributions
        environment.ConnectionObservations
        environment.ConnectionRoute
        environment.ConnectionRouteContext
        environment.ConnectionRouteProvider
        environment.CorrelationContribution
        environment.InteractionSession
        environment.ObservationStatusProvider
        observation.EvidenceCodec
        observation.InteractionDecisionCoordinator
        """);

    private static final Set<String> READ_ONLY_MODEL = types("""
        component.ComponentId
        component.ComponentState
        component.ComponentType
        diagnostics.EnvironmentDiagnostics
        diagnostics.JournalRenderer
        endpoint.AmqpEndpoint
        endpoint.EndpointAddress
        endpoint.EndpointBinding
        endpoint.JdbcEndpoint
        endpoint.RedisEndpoint
        endpoint.SmppEndpoint
        environment.state.ConnectionState
        environment.state.EnvironmentState
        environment.state.RoutingMode
        environment.state.RuntimeConnectionSnapshot
        journal.CheckpointEvent
        journal.CheckpointEvent$Kind
        journal.CheckpointEvent$Stage
        journal.CheckpointId
        journal.ComponentLifecycleEvent
        journal.ConnectionLifecycleEvent
        journal.CorrelationCandidateEvent
        journal.DiagnosticEvent
        journal.DiagnosticEvent$ComponentSubject
        journal.DiagnosticEvent$ConnectionSubject
        journal.DiagnosticEvent$EnvironmentSubject
        journal.DiagnosticEvent$Subject
        journal.DisruptionId
        journal.DisruptionLifecycleEvent
        journal.DisruptionLifecycleEvent$Stage
        journal.EnvironmentLifecycleEvent
        journal.FailureDetails
        journal.FailureEvent
        journal.FailureEvent$ComponentCleanup
        journal.FailureEvent$ComponentStartup
        journal.FailureEvent$ConnectionCleanup
        journal.FailureEvent$ConnectionMaterialization
        journal.FailureEvent$DriverResourceCleanup
        journal.FailureEvent$EnvironmentStartup
        journal.InteractionObservationEvent
        journal.JournalEntry
        journal.JournalSequence
        journal.ProofSubjectArmedEvent
        journal.ProofSubjectCreatedEvent
        journal.ScenarioEvent
        journal.ScenarioJournalSnapshot
        observation.EffectiveObservationStatus
        observation.EvidenceSchemaId
        observation.EvidenceSnapshot
        observation.FlowDirection
        observation.ForwardingDecision
        observation.InteractionRef
        observation.ObservationRequirement
        observation.SessionId
        proof.CorrelationCardinality
        proof.CorrelationKey
        proof.CorrelationKeySchema
        proof.CorrelationResult
        proof.CorrelationResult$Ambiguous
        proof.CorrelationResult$Missing
        proof.CorrelationResult$Unique
        proof.ProofSubjectRef
        topology.CompatibilityResult
        topology.ConnectionDescriptor
        topology.ConnectionId
        topology.ConnectionRef
        topology.PortDirection
        topology.PortRef
        """);

    private static final Set<String> JAVA_PUBLIC_INTERNAL = types("""
        configuration.ConfigurationBinder
        configuration.ConfigurationValidator
        configuration.ConfigurationValues
        environment.RuntimeEndpointBindings
        """);

    @Test
    void shouldClassifyEveryExternallyVisibleTypeIncludingNestedTypes() throws IOException {
        assertPairwiseDisjoint(
            SUPPORTED_API,
            SUPPORTED_SPI,
            READ_ONLY_MODEL,
            JAVA_PUBLIC_INTERNAL
        );

        Set<String> classified = new TreeSet<>();
        classified.addAll(SUPPORTED_API);
        classified.addAll(SUPPORTED_SPI);
        classified.addAll(READ_ONLY_MODEL);
        classified.addAll(JAVA_PUBLIC_INTERNAL);

        Set<String> actual = externallyVisibleTypes();
        assertThat(actual).containsExactlyElementsOf(classified);
        assertThat(actual).anyMatch(name -> name.contains("$"));
        assertThat(JAVA_PUBLIC_INTERNAL).noneMatch(name -> name.contains("$"));
    }

    @Test
    void shouldUseOnlyDomainOwnedTopLevelPackages() throws IOException {
        assertThat(topLevelPackageDirectories())
            .containsExactlyInAnyOrder(
                "communication",
                "component",
                "configuration",
                "diagnostics",
                "driver",
                "endpoint",
                "environment",
                "journal",
                "observation",
                "proof",
                "topology"
            );
    }

    @Test
    void shouldKeepEnvironmentExecutionAndJournalMutationInternal() throws Exception {
        Class<?> runtime = loadType("environment.EnvironmentRuntime");
        assertThat(Modifier.isPublic(runtime.getModifiers())).isFalse();
        assertThat(runtime.getDeclaredConstructors())
            .allSatisfy(constructor ->
                assertThat(Modifier.isPrivate(constructor.getModifiers())).isTrue()
            );
        assertThat(externallyVisibleMethods(runtime)).isEmpty();

        Class<?> storage = loadType("environment.ScenarioJournal");
        assertThat(Modifier.isPublic(storage.getModifiers())).isFalse();
        assertThat(externallyVisibleConstructors(storage)).isEmpty();
        assertThat(externallyVisibleMethods(storage)).isEmpty();

        assertThatThrownBy(() -> Class.forName(BASE_PACKAGE + "journal.ScenarioJournal"))
            .isInstanceOf(ClassNotFoundException.class);
        assertThat(classFiles("").stream()
            .map(CoreArchitectureTest::loadType)
            .filter(CoreArchitectureTest::isExternallyVisible)
            .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .filter(CoreArchitectureTest::isExternallyVisible)
            .filter(method -> method.getName().equals("append")
                || method.getName().equals("publish"))
            .filter(method -> Arrays.stream(method.getParameterTypes())
                .anyMatch(parameter -> parameter == ScenarioEvent.class
                    || parameter == JournalEntry.class)))
            .isEmpty();
    }

    @Test
    void shouldPinJavaPublicTechnicalBridgesAndSensitiveSupportedMembers() {
        assertThat(methodKeys(ConfigurationBinder.class))
            .containsExactly("bind(java.lang.Class,configuration.EnvironmentConfiguration):java.lang.Object");
        assertThat(methodKeys(ConfigurationValidator.class))
            .containsExactly("validate(java.lang.Object):java.lang.Object");
        assertThat(methodKeys(ConfigurationValues.class))
            .containsExactly(
                "requireNonNull(java.lang.Object,java.lang.String):java.lang.Object",
                "requireText(java.lang.String,java.lang.String):java.lang.String"
            );
        assertThat(methodKeys(RuntimeEndpointBindings.class))
            .containsExactly("publish(topology.ProvidedPort,endpoint.EndpointBinding):void");
        assertThat(List.of(
            ConfigurationBinder.class,
            ConfigurationValidator.class,
            ConfigurationValues.class,
            RuntimeEndpointBindings.class
        )).allSatisfy(type -> assertThat(externallyVisibleConstructors(type)).isEmpty());

        assertThat(methodKeys(AbstractComponent.class))
            .containsExactly(
                "castOperations(java.lang.Object):java.lang.Object",
                "configuration():configuration.RuntimeConfig",
                "driver():driver.ComponentDriver",
                "id():component.ComponentId",
                "ports():java.util.List",
                "type():component.ComponentType"
            );
        assertThat(methodKeys(ComponentRuntime.class))
            .containsExactly(
                "close():void",
                "diagnostics():java.util.List",
                "materializes(topology.ProvidedPort):boolean",
                "operations():java.lang.Object",
                "publishBindingsTo(environment.RuntimeEndpointBindings):void",
                "runtime():driver.ComponentRuntime$Builder",
                "runtime(java.lang.AutoCloseable):driver.ComponentRuntime$Builder"
            );
        assertThat(methodKeys(EnvironmentTopology.class))
            .doesNotContain("runtimeComponents():java.util.List")
            .contains(
                "components():java.util.List",
                "connections():java.util.List"
            );
        assertThat(externallyVisibleConstructors(ComponentLifecycleException.class)).isEmpty();
    }

    @Test
    void shouldKeepRouteExecutionProofAllocationAndProviderLookupInternal()
        throws Exception {
        Class<?> selection = loadType("environment.ConnectionRouting$Selection");
        assertThat(isExternallyVisible(selection)).isFalse();
        assertThat(externallyVisibleConstructors(selection)).isEmpty();
        assertThat(externallyVisibleMethods(selection)).isEmpty();

        assertThat(methodKeys(ConnectionRouting.class))
            .allMatch(key -> key.startsWith("direct(")
                || key.startsWith("routed(")
                || key.startsWith("withRoute("));
        assertThat(methodKeys(ConnectionRoute.class))
            .allMatch(key -> key.startsWith("routed("));
        assertThat(methodKeys(ConnectionRouteContext.class))
            .extracting(key -> key.substring(0, key.indexOf('(')))
            .containsExactlyInAnyOrder(
                "connection",
                "observations",
                "observationRequirement",
                "coordinator",
                "directTarget"
            );
        assertThat(methodKeys(CorrelationContribution.class))
            .extracting(key -> key.substring(0, key.indexOf('(')))
            .containsExactlyInAnyOrder(
                "capture",
                "key",
                "nativeReferenceSchema",
                "encodedSize",
                "equals",
                "hashCode",
                "toString"
            );

        assertThatThrownBy(() -> Class.forName(BASE_PACKAGE + "proof.ProofSubjectScope"))
            .isInstanceOf(ClassNotFoundException.class);
        assertThat(externallyVisibleMethods(
            RuntimeEndpointBindings.class,
            ConnectionRouting.class,
            ConnectionRoute.class
        )).noneMatch(method ->
            method.getName().equals("select")
                || method.getName().equals("prepare")
                || method.getName().equals("consumerTarget")
                || method.getReturnType().equals(EndpointBinding.class)
        );
    }

    @Test
    void shouldKeepOneJournalStorageOwnerIndependentOfRenderingAndSlf4j()
        throws IOException {
        Path storage = CLASSES.resolve(BASE_PATH + "environment/ScenarioJournal.class");
        assertThat(readBytecode(storage))
            .doesNotContain(
                BASE_PATH + "diagnostics/",
                "org/slf4j/",
                "EnvironmentLogging"
            );

        assertThat(classFiles("environment").stream()
            .map(CoreArchitectureTest::loadType)
            .flatMap(type -> Arrays.stream(type.getDeclaredFields()))
            .filter(field -> Collection.class.isAssignableFrom(field.getType())
                || Map.class.isAssignableFrom(field.getType()))
            .filter(field -> {
                String fieldType = field.getGenericType().getTypeName();
                return fieldType.contains("journal.JournalEntry")
                    || fieldType.contains("journal.ScenarioEvent");
            }))
            .extracting(field -> field.getDeclaringClass().getName())
            .containsExactly(BASE_PACKAGE + "environment.ScenarioJournal");
    }

    @Test
    void shouldEnforceDependencyDirection() throws IOException {
        assertThat(classFiles(""))
            .allSatisfy(path -> assertThat(readBytecode(path))
                .doesNotContain(
                    "org/junit/",
                    "org/testcontainers/",
                    BASE_PATH + "junit/",
                    BASE_PATH + "testcontainers/"
                ));

        assertThat(classFiles("configuration"))
            .allSatisfy(path -> assertThat(readBytecode(path))
                .doesNotContain(
                    BASE_PATH + "component/",
                    BASE_PATH + "driver/",
                    BASE_PATH + "environment/"
                ));
        assertThat(classFiles("endpoint"))
            .allSatisfy(path -> assertThat(readBytecode(path))
                .doesNotContain(
                    BASE_PATH + "driver/",
                    BASE_PATH + "environment/"
                ));

        assertThat(classFiles("observation"))
            .allSatisfy(path -> assertThat(readBytecode(path))
                .doesNotContain(BASE_PATH + "proof/"));
        assertThat(classFiles("proof"))
            .anySatisfy(path -> assertThat(readBytecode(path))
                .contains(BASE_PATH + "observation/"));

        for (String packageName : List.of("journal", "diagnostics")) {
            assertThat(classFiles(packageName))
                .allSatisfy(path -> assertThat(readBytecode(path))
                    .doesNotContain(
                        BASE_PATH + "environment/EnvironmentExecution",
                        BASE_PATH + "environment/EnvironmentRuntime",
                        BASE_PATH + "environment/ScenarioJournal",
                        BASE_PATH + "environment/RuntimeConnectionRegistry",
                        BASE_PATH + "environment/ProofSubjectRegistry",
                        BASE_PATH + "engine/execution/"
                    ));
        }

        assertThat(classFiles("driver"))
            .allSatisfy(path -> assertThat(readBytecode(path))
                .doesNotContain(
                    "org/testcontainers/",
                    BASE_PATH + "testcontainers/"
                ));
    }

    private static Set<String> externallyVisibleTypes() throws IOException {
        return classFiles("").stream()
            .map(CoreArchitectureTest::loadType)
            .filter(CoreArchitectureTest::isExternallyVisible)
            .map(type -> type.getName().substring(BASE_PACKAGE.length()))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> topLevelPackageDirectories() throws IOException {
        Path root = CLASSES.resolve(BASE_PATH);
        try (Stream<Path> paths = Files.list(root)) {
            return paths
                .filter(Files::isDirectory)
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    private static Set<String> methodKeys(Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .filter(CoreArchitectureTest::isExternallyVisible)
            .map(CoreArchitectureTest::methodKey)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    private static String methodKey(Method method) {
        String parameters = Arrays.stream(method.getParameterTypes())
            .map(CoreArchitectureTest::shortTypeName)
            .collect(Collectors.joining(","));
        return method.getName() + "(" + parameters + "):"
            + shortTypeName(method.getReturnType());
    }

    private static String shortTypeName(Class<?> type) {
        String name = type.getName();
        return name.startsWith(BASE_PACKAGE)
            ? name.substring(BASE_PACKAGE.length())
            : name;
    }

    private static List<Constructor<?>> externallyVisibleConstructors(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
            .filter(CoreArchitectureTest::isExternallyVisible)
            .toList();
    }

    private static List<Method> externallyVisibleMethods(Class<?>... types) {
        return Arrays.stream(types)
            .flatMap(type -> Arrays.stream(type.getDeclaredMethods()))
            .filter(CoreArchitectureTest::isExternallyVisible)
            .toList();
    }

    private static boolean isExternallyVisible(java.lang.reflect.Member member) {
        return isExternallyVisible(member.getModifiers());
    }

    private static boolean isExternallyVisible(Class<?> type) {
        if (!isExternallyVisible(type.getModifiers())) {
            return false;
        }
        Class<?> enclosing = type.getEnclosingClass();
        return enclosing == null || isExternallyVisible(enclosing);
    }

    private static boolean isExternallyVisible(int modifiers) {
        return Modifier.isPublic(modifiers) || Modifier.isProtected(modifiers);
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

    private static List<Path> classFiles(String packageName) throws IOException {
        Path root = CLASSES.resolve(BASE_PATH + packageName.replace('.', '/'));
        try (Stream<Path> classes = Files.walk(root)) {
            return classes
                .filter(path -> path.toString().endsWith(".class"))
                .filter(path -> !path.getFileName().toString().equals("package-info.class"))
                .toList();
        }
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
