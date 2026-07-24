# System Proof

System Proof is a typed Java framework for defining system-test topologies, starting their runtime
components, injecting a concrete environment into JUnit 5 tests, and retaining failure diagnostics.

## Modules

```text
system-proof-examples -> system-proof-junit5        -> system-proof-core
        |------------> system-proof-testcontainers -> system-proof-core
        `------------------------------------------> system-proof-core

system-proof-examples/apps
        |-> system-proof-ingestion-service
        `-> system-proof-smsc-simulator
```

- `system-proof-core`: typed components, ports, connections, lifecycle, logging, and diagnostics.
- `system-proof-junit5`: `@EnvironmentTest`, `@EnvironmentDefinition`, environment injection, and
  failure artifacts.
- `system-proof-testcontainers`: container-backed drivers and runtime port bindings.
- `system-proof-examples`: executable PostgreSQL and complete SMS-ingestion examples.
- `system-proof-examples/apps`: small reference applications used only by the complete example.

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

Every environment owns one monotonic `T+HH:mm:ss.SSS` timeline containing framework events,
connections, component lifecycle, container output, bootstrap messages, and cleanup failures.
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
The reference ingestion and SMSC images are built from the current checkout during verification;
no prebuilt application images are required.

## Continuous integration

The `Verify` workflow runs `./mvnw clean verify` with Java 21 and Docker on a GitHub-hosted Ubuntu
runner. The same job executes unit and architecture tests, both Docker integration tests, builds
both reference application images from source, and runs the complete topology smoke.
