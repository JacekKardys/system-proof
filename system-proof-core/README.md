# System Proof Core

This module contains the complete runtime-neutral environment model.

Public contracts:

- `Environment`: immutable topology, lifecycle, diagnostics, and reverse-order cleanup.
- `Component` and `AbstractComponent<C, O>`: one component identity, typed immutable configuration,
  owned ports, driver, lifecycle state, and optional typed operations.
- `RequiredPort<C>`, `ProvidedPort<C>`, `Contract<C>`, and `Connection<C>`: directional typed
  topology without runtime addresses.
- `ComponentDriver<C, O>`, `DriverContext`, and `ComponentRuntime<O>`: runtime materialization SPI.
- `EnvironmentConfiguration` and `Secret<T>`: immutable external values and redacted secrets.
- `ScenarioJournal`, its sealed framework-owned `ScenarioEvent` hierarchy, and immutable
  snapshots: the single authoritative structured scenario history.
- `EnvironmentLogging`, `EnvironmentDiagnostics`, and `EnvironmentStartException`: rendered
  journal views and failure reporting.

Core validates component ID uniqueness, port ownership and direction, contract/interaction/protocol
compatibility, exactly one provider per required port, logging references, dependency cycles, and
complete provided-port materialization.

`Environment.start()` starts providers before consumers when a consumer needs the provider's
runtime binding to materialize its driver. It attaches each runtime to the same component object.
`Environment.close()` closes component resources in reverse order and then closes shared driver
resources. A partial startup failure keeps the original failure primary and adds cleanup failures
as suppressed exceptions. Operations outside `RUNNING` fail with component identity, type, actual
state, and expected state.

`journalSequence` is local storage/rendering order only. Diagnostic elapsed time and rendered log
order are not causal evidence.

The journal accepts only concrete immutable event value types owned by core. Future evidence work
may deliberately extend the sealed hierarchy when its real contracts are known; arbitrary adapter
objects are not appendable and must not create a second history.

The module contains no JUnit, Testcontainers, Docker image, or wait strategy dependency.
