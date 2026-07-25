# System Proof Core

This module contains the complete runtime-neutral environment model.

Public contracts:

- `Environment`: immutable topology, lifecycle, diagnostics, and reverse-order cleanup.
- `Component` and `AbstractComponent<C, O>`: one component identity, typed immutable configuration,
  owned ports, driver, lifecycle state, and optional typed operations.
- `RequiredPort<C>`, `ProvidedPort<C>`, `Contract<C>`, and `Connection<C>`: directional typed
  topology without runtime addresses.
- `ComponentDriver<C, O>`, component-scoped `DriverContext`, restricted `JournalContributions`, and
  `ComponentRuntime<O>`: runtime materialization SPI.
- `EnvironmentConfiguration` and `Secret<T>`: immutable external values and redacted secrets.
- `ScenarioJournal`, its sealed core-owned `ScenarioEvent` envelopes, `EvidenceCodec<T>`,
  `EvidenceSnapshot`, and immutable journal snapshots: the single authoritative structured
  scenario history and external typed-evidence copy boundary.
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
order are not causal evidence. Checkpoint/barrier records likewise do not establish barrier
evaluation, cross-stream ordering, or happens-before relationships.

Framework lifecycle, failure, and diagnostic events are created only by the runtime. Interaction,
checkpoint/barrier, and disruption contributions use closed immutable envelopes owned by core.
External modules define a typed `EvidenceCodec<T>` for their observation value and contribute it
through the component-scoped `JournalContributions` sink. Core encodes and copies the value before
append; the caller-owned value, codec, and returned array are not retained. Decoding is typed,
schema-checked, and receives another copy, so snapshot access cannot mutate storage.

`DriverContext` does not expose `ScenarioJournal`. Its contribution sink has no operation for
framework lifecycle, framework failures, or arbitrary diagnostics, and the runtime binds the
observing component identity instead of trusting a supplied identity. Protocol modules can
therefore add typed evidence without changing core's sealed `permits` list or adding protocol
classes to core. The core renderer handles each envelope explicitly and renders only stable
metadata, schema identity, and encoded size for contributed observations; it never renders the
payload or calls an arbitrary payload `toString()`.

The module contains no JUnit, Testcontainers, Docker image, or wait strategy dependency.
