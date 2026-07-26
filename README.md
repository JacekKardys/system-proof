# System Proof

System Proof is a typed Java framework for defining system-test topologies, starting their runtime
components, injecting a concrete environment into JUnit 5 tests, and retaining failure diagnostics.

## Modules

```text
system-proof-examples -> system-proof-junit5        -> system-proof-core
        |------------> system-proof-testcontainers -> system-proof-core
        `------------------------------------------> system-proof-core

system-proof-examples/apps
        `-> system-proof-ingestion-service

system-proof-examples/fixtures
        `-> ukarim-smscsim
```

- `system-proof-core`: typed components, ports, connections, lifecycle, logging, and diagnostics.
- `system-proof-junit5`: `@EnvironmentTest`, `@EnvironmentDefinition`, environment injection, and
  failure artifacts.
- `system-proof-testcontainers`: container-backed drivers and runtime port bindings.
- `system-proof-examples`: executable PostgreSQL and complete SMS-ingestion examples.
- `system-proof-examples/apps`: the reference ingestion SUT used only by the complete example.
- `system-proof-examples/fixtures`: reproducible third-party fixture adaptations used by examples.

Core is independent of JUnit and Testcontainers. The JUnit 5 module is independent of
Testcontainers. These boundaries are enforced by `CoreModuleBoundaryTest` and
`Junit5ModuleBoundaryTest` and recorded with the T1 baseline in
[`docs/adr/0001-t1-proof-contract.md`](docs/adr/0001-t1-proof-contract.md).

## Minimal test

```java
@EnvironmentTest(environment = ExampleEnvironment.class)
final class ExampleIT {

    @Test
    void exercisesBehavior(ExampleEnvironment environment) {
        environment.database().insert("value");

        assertThat(environment.database().values()).containsExactly("value");
    }
}
```

`@EnvironmentTest` points to a concrete environment facade. That facade declares exactly one
static, zero-argument `@EnvironmentDefinition` method returning its own type. System Proof validates
the closed topology before startup, starts dependencies in order, injects the exact returned object,
and closes partial or complete startup in reverse order.

## Component declarations

A component is a concrete class. `@SystemComponent` owns its static kind and driver metadata, while
the `AbstractComponent<C, O>` type arguments declare its component configuration and optional
runtime operations:

```java
@SystemComponent(
    type = "system-proof-smsc-simulator",
    driver = SmscTestcontainersDriver.class
)
@Getter
@Accessors(fluent = true)
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SmscComponent
    extends AbstractComponent<SmscConfig, UkarimSmscOperations> {

    @PortContract("smpp")
    @Communication.Smpp
    private ProvidedPort<SmppEndpoint> smpp;
}
```

Component configuration appears once and contains service-facing runtime values. Its type parameter
associates a separate driver-only configuration:

```java
public interface SmscConfig extends ComponentConfig<SmscConfig.Driver> {
    @ConfigurationSource(
        provider = EnvironmentVariable.class,
        key = "SYSTEM_PROOF_EXAMPLE_SMSC_SYSTEM_ID",
        defaultValue = "sp-test"
    )
    String systemId();

    @ConfigurationSource(
        provider = EnvironmentVariable.class,
        key = "SYSTEM_PROOF_EXAMPLE_SMSC_PASSWORD",
        defaultValue = "password"
    )
    Secret<String> password();

    interface Driver extends DriverConfig {
        @ConfigurationSource(
            provider = EnvironmentVariable.class,
            key = "SYSTEM_PROOF_SMSC_SIMULATOR_IMAGE",
            defaultValue = "system-proof-ukarim-smscsim:local"
        )
        String image();

        @ConfigurationSource(provider = Literal.class, value = "2775")
        int smppPort();
    }
}
```

Every configuration method retains its `@ConfigurationSource` and validation annotations. The
environment builder binds both interfaces from one immutable `EnvironmentConfiguration`, constructs
the declared driver, creates the component, initializes annotated ports, and registers the component
only after all steps succeed:

```java
Environment.Builder environment = Environment.environment();
SmscComponent smsc = environment.component(SmscComponent.class);
JasminComponent jasmin = environment.component(JasminComponent.class);

Environment.Builder isolated = Environment.environment(configuration);
SmscComponent primary = isolated.component("primary", SmscComponent.class);
SmscComponent secondary = isolated.component("secondary", SmscComponent.class);
```

The builder returns the exact instance retained by the topology and later runtime. Component classes
do not know `ComponentFactory` or `Environment.Builder`. Core derives and validates the component
configuration, operations, driver configuration, and driver through its generic hierarchy before
binding values. Runtime technologies that bind to one concrete component declare that target
through `ComponentBoundDriver<C, O, T>`; Testcontainers uses this explicit SPI. Tests and
programmatic configuration can use the typed low-level
`AbstractComponent.component(...)` path with an already prepared configuration and driver; this does
not add a factory method or constructor DSL to the concrete component.

## Runtime model

Each `AbstractComponent<C, O>` owns:

- a `ComponentType` and instance `ComponentId`;
- one immutable typed component configuration `C`;
- typed required and provided ports;
- one `ComponentDriver<C, O>`;
- an attached `ComponentRuntime<O>` while running.

Each logical `Connection<C>` is an immutable directional
`RequiredPort<C> -> ProvidedPort<C>` declaration. It validates contract, interaction, and protocol
compatibility and owns a deterministic `ConnectionId` derived from both component and local port
identities. Its canonical endpoint form is `component-type[qualifier].local-port`; empty brackets
mean that the component has no qualifier. Component type and qualifier are encoded as separate
semantic fields, so identity never depends on the flattened display form from
`ComponentId.toString()`. Port-name delimiters are percent-encoded, so distinct required ports
connected to one provided port remain distinct without using endpoint values, startup order,
object identity, hash codes, or mapped ports. For example, `client-a[].api` and
`client[a].api` are distinct endpoints even though both component IDs display as `client-a`.

Each environment runtime materializes every logical declaration exactly once as a typed
`RuntimeConnection<C>`. One environment-owned registry preserves topology declaration order,
indexes runtime connections by `ConnectionId`, required port, provider, and provided port, and owns
their lifecycle, routing mode, direct provider target, and effective consumer target. A provider
still publishes one typed `EndpointBinding<C>`. The runtime retains it as `directTarget`, prepares
the connection's `consumerTarget`, and commits both only after every connection targeting that
provider is ready. Consumer resolution follows the required port to its `RuntimeConnection` and
returns only the consumer target's internal typed endpoint; it never resolves independently through
the provider runtime. `ComponentRuntime` exposes no public provider-binding lookup; it can only
transfer published bindings into an engine-owned boundary that external callers cannot construct.

The complete `EndpointBinding<C>` retains both the internal endpoint used for component-to-component
communication and the external test-host endpoint needed by later gateway work. Endpoint values
remain internal because they may contain credentials, aliases, or other secrets.
`Environment.runtimeConnections()` and `Environment.runtimeConnection(ConnectionId)` expose only
detached immutable snapshots with semantic metadata, lifecycle state, routing mode, and separate
direct/consumer target availability.

`DIRECT` makes the consumer target the direct provider binding and creates no routing resource.
`ROUTED` invokes a typed `ConnectionRouteProvider<C>` independently for every matching connection.
An immutable routing policy can contain multiple rules keyed by semantic `Contract<C>` or a stable
structured `ConnectionId`; connection-specific rules take precedence and unmatched connections stay
direct. The provider receives that connection's stable descriptor and direct binding, then returns
the effective binding and an optional connection-owned resource. All routes for one provider are
prepared before any targeted connection becomes `RUNNING`. Partial creation closes already prepared
resources in reverse order and retains cleanup failures as suppressed. Normal cleanup first makes
consumer targets unavailable, then closes routes in reverse order, and invalidates direct targets
before provider cleanup completes. Route failure diagnostics retain only failure type, lifecycle
stage, and connection identity; the original throwable and suppressed ordering returned to the
caller remain unchanged.

`ROUTED` means only that the consumer receives an interposed endpoint; it does not claim that
traffic was observed. The protected environment runtime seam accepts `ConnectionRouting` without
adding route or proxy declarations to the topology DSL. Issue #7 will use this seam to prove one
real JVM `InteractionGateway`, container-to-JVM routing, and Testcontainers host exposure. Those
transport mechanics, and the later `OBSERVED` semantics from issue #8, are not implemented here.

External values enter through immutable `EnvironmentConfiguration`. Each environment builder owns
that snapshot and binds the component and driver configuration interfaces declared by component
metadata. Secrets use `Secret<T>` and are redacted from diagnostics.

## Diagnostics

Every environment owns exactly one append-only `ScenarioJournal`. It is the authoritative
structured history. The sealed event hierarchy contains core-owned immutable envelopes for:

- framework environment, component, and runtime-connection lifecycle transitions, failures, and
  diagnostics;
- externally contributed interaction observations;
- checkpoint or barrier records;
- disruption lifecycle records.

Protocol modules do not implement `ScenarioEvent` and do not place protocol fields in core.
Instead, a module supplies a typed `EvidenceCodec<T>` and its value through the component-scoped
`JournalContributions` capability on `DriverContext`. Core invokes the codec synchronously, copies
the encoded bytes into a private `EvidenceSnapshot`, and retains neither the source value, codec,
nor codec-produced array. Typed inspection uses the same codec against a fresh byte-array copy.
Mutable source arrays, collections, decoded values, and adapters therefore cannot mutate stored
history. No reflection, Java serialization, event registry, or parallel evidence store is used.

The driver capability supplies the observing component identity and exposes only interaction,
checkpoint, and disruption contributions. It cannot append environment/component lifecycle,
framework failure, or free-form diagnostic events, and it never exposes the mutable journal.
Existing `DriverContext.log(...)` remains journal-backed and is restricted to the driver-owned
component. A scoped driver can resolve only required ports owned by that component, and contributed
interaction metadata may name only a typed `ConnectionId` present in the current environment.
`Environment.journalSnapshot()` returns a detached immutable snapshot for typed assertions.

Textual environment logs are rendered views of one captured journal snapshot. They retain the
readable monotonic `T+HH:mm:ss.SSS` diagnostic timeline for framework events, connections,
components, container output, bootstrap messages, and cleanup failures, but own no independent
event history. Logging thresholds control only SLF4J emission; lower-level events remain in the
journal and therefore remain available to failure diagnostics.

`journalSequence` is a one-based position local to one journal. It provides unique storage order
and deterministic rendering only. It is not a wall-clock or distributed sequence, an
`EvidencePosition`, or proof that one external event caused or happened before another.
Diagnostic timestamps and rendered or container log-line order likewise establish no causal
relationship. A checkpoint/barrier record and its position in the journal are also reported facts,
not proof of barrier evaluation, external ordering, or causality. Future protocol modules can add
their own typed codecs and values through the existing contribution boundary; causal proof still
requires explicit stream-local positions and semantic evaluation in the later roadmap tasks.
Runtime-connection lifecycle order and elapsed time have the same limitation: target binding before
another stored event is not proof of external protocol ordering or causality.

Failed JUnit tests write:

```text
target/system-proof-artifacts/<test-class>-<test-method>/environment.log
```

Set `system.proof.artifacts` to override the artifact root.

## Build

Java 21 and the Maven Wrapper are required:

```bash
./mvnw clean test
./mvnw clean verify
```

`clean test` runs unit tests without Docker. `clean verify` also runs the transactional ingestion
test, PostgreSQL example, and complete SMS-ingestion topology through Failsafe. It requires Docker.
The reference ingestion image and adapted `ukarim/smscsim` fixture image are built during
verification. The SMSC build fetches an exact upstream commit and applies the reviewed patch stored
in this repository. No prebuilt application image or manually provisioned local tag is required.

## Continuous integration

The `Verify` workflow runs `./mvnw clean verify` with Java 21 and Docker on a GitHub-hosted Ubuntu
runner. The same job executes unit and architecture tests, both Docker integration tests, builds
the reference ingestion application and adapted SMSC fixture, and runs the complete topology
smoke. Third-party source, license, pin, and patch details are recorded in
[`docs/third-party.md`](docs/third-party.md).
