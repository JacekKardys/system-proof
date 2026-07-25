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

## Runtime model

Each `AbstractComponent<C, O>` owns:

- a `ComponentType` and instance `ComponentId`;
- one immutable typed component configuration `C`;
- typed required and provided ports;
- one `ComponentDriver<C, O>`;
- an attached `ComponentRuntime<O>` while running.

Connections are directional `RequiredPort<C> -> ProvidedPort<C>` relationships. They validate
contract, interaction, and protocol compatibility. Host names, mapped ports, URIs, and JDBC URLs
exist only in runtime bindings created by drivers.

External values enter through immutable `EnvironmentConfiguration`. One `ComponentFactory` owns
that snapshot and binds annotated component and driver configuration interfaces. Secrets use
`Secret<T>` and are redacted from diagnostics.

## Diagnostics

Every environment owns exactly one append-only `ScenarioJournal`. It is the authoritative
structured history. The sealed event hierarchy contains core-owned immutable envelopes for:

- framework environment and component lifecycle transitions, failures, and diagnostics;
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
component. `Environment.journalSnapshot()` returns a detached immutable snapshot for typed
assertions.

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
