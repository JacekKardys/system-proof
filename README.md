# AML regression tests

This repository contains a runtime-neutral environment harness and AML SMS system tests.

## Modules

```text
environment-harness-core <- environment-harness-junit5
        ^
        |
environment-harness-testcontainers
        ^
        |
aml-system-tests (test-local AML components, drivers, operations, and scenarios)
```

- `environment-harness-core`: typed components, ports, connections, lifecycle, logging, and diagnostics.
- `environment-harness-junit5`: `@EnvironmentTest`, `@EnvironmentDefinition`, concrete environment injection, and failure artifacts.
- `environment-harness-testcontainers`: container-backed driver support and runtime port bindings.
- `aml-system-tests`: test-local AML topology support and behavioral scenarios.

Framework modules do not import AML code. Core does not depend on JUnit or Testcontainers.

## Minimal test

```java
@EnvironmentTest(environment = ExampleEnvironment.class)
final class ExampleIT {

    @Test
    void exercisesBehavior(ExampleEnvironment environment) {
        environment.client().send(...);
        environment.service().await().result(...);
    }
}
```

`@EnvironmentTest` points to a concrete environment facade. That facade declares exactly one
static, zero-argument `@EnvironmentDefinition` method returning its own type. The harness validates
the closed topology before starting a runtime, starts dependencies in order, injects the exact
returned object, and closes partial or complete startup in reverse order.

## Runtime model

Each `AbstractComponent<C, O>` owns:

- a `ComponentType` and instance `ComponentId`;
- one immutable typed component configuration `C`;
- typed required/provided ports;
- one `ComponentDriver<C, O>`;
- an attached `ComponentRuntime<O>` while running.

Connections are directional `RequiredPort<C> -> ProvidedPort<C>` relationships. They validate
contract, interaction, and protocol compatibility. Host names, mapped ports, URIs, and JDBC URLs
exist only in runtime bindings created by drivers.

External values enter through immutable `EnvironmentConfiguration`. One `ComponentFactory` owns
that snapshot and binds the annotated component/runtime configuration interfaces. Secrets use
`Secret<T>` and are redacted from diagnostics.

## Diagnostics

Every environment owns one monotonic `T+HH:mm:ss.SSS` timeline containing framework events,
connections, component lifecycle, container output, bootstrap messages, and cleanup failures.
Failed JUnit tests write:

```text
target/regression-artifacts/<test-class>-<test-method>/environment.log
```

## Build

Java 21 and the Maven Wrapper are required:

```bash
./mvnw clean test
./mvnw clean verify
```

`clean test` does not start Docker scenarios. `clean verify` runs the AML smoke scenario through
Failsafe and requires Docker plus the local AML images documented in
`aml-system-tests/README.md`.

The smoke test proves end-to-end ingestion and persistence. It intentionally does not claim or
test any `deliver_sm_resp` versus database-commit ordering. That T1 acknowledgement invariant is
deferred until it can be expressed as a supported system contract.
