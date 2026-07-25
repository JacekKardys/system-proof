# System Proof Core

This module contains the complete runtime-neutral environment model.

Public contracts:

- `Environment`: immutable topology, lifecycle, diagnostics, and reverse-order cleanup.
- `Component` and `AbstractComponent<C, O>`: one component identity, typed immutable configuration,
  owned ports, driver, lifecycle state, and optional typed operations.
- `RequiredPort<C>`, `ProvidedPort<C>`, `Contract<C>`, `ConnectionId`, and `Connection<C>`:
  directional typed topology without runtime addresses.
- `RuntimeConnection<C>` and detached `RuntimeConnectionSnapshot` values: one authoritative
  runtime materialization per logical connection, without exposing endpoint values.
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

`Connection<C>` is the immutable logical declaration. Its typed `ConnectionId` is derived
deterministically from both component and local port identities, with delimiter-safe port-name
encoding. It does not depend on contract identity alone, endpoint values, mapped Docker ports,
startup order, or object identity. Several required ports may target one provided port while
retaining distinct IDs.

`EnvironmentRuntime` creates one ordered runtime-connection registry from the validated topology.
The registry materializes each declaration exactly once, rejects duplicate IDs or required-port
materialization, and indexes by ID, required port, provider, and provided port. A
`RuntimeConnection<C>` owns its immutable descriptor, `DECLARED -> STARTING -> RUNNING -> STOPPING
-> STOPPED` lifecycle or terminal `FAILED` state, `DIRECT` routing mode, and one-shot direct
`EndpointBinding<C>`. State transitions are centrally checked. A connection cannot become
`RUNNING` without a target, and cleanup removes target availability before closing the provider.

Drivers still publish both internal and external endpoint values. The runtime connection retains
both forms internally, but direct consumer resolution returns only the internal typed value. The
external form is retained for later JVM gateway routing. Public inspection returns detached
immutable snapshots containing semantic metadata, state, mode, and target availability; it never
returns endpoint values, Testcontainers objects, mapped ports, aliases, or credentials.

`DIRECT` is the only implemented routing mode and means that the consumer receives the provider's
internal endpoint. It provides no traffic-observation guarantee. A later reroutable/effective
consumer endpoint can be added to the existing runtime connection for issue #6 without creating a
parallel registry or moving ownership away from `RuntimeConnection`.

`Environment.start()` starts providers before consumers when a consumer needs the provider's
runtime binding to materialize its driver. It attaches each runtime to the same component object.
`Environment.close()` closes component resources in reverse order and then closes shared driver
resources. A partial startup failure keeps the original failure primary and adds cleanup failures
as suppressed exceptions. Operations outside `RUNNING` fail with component identity, type, actual
state, and expected state.

Runtime-connection lifecycle and materialization failures are immutable typed `ScenarioEvent`
values. They retain the connection descriptor and frozen failure details without rendering
endpoint values. Logging thresholds affect only SLF4J emission; every connection event remains in
the journal. Topology failures occur before a runtime journal exists and therefore remain immediate
construction exceptions.

`journalSequence` is local storage/rendering order only. Diagnostic elapsed time and rendered log
order are not causal evidence. Checkpoint/barrier records likewise do not establish barrier
evaluation, cross-stream ordering, or happens-before relationships.
Connection lifecycle order, direct-target binding, and elapsed time likewise do not prove external
protocol ordering or causality.

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

A component-scoped `DriverContext` resolves only required ports owned by that component.
`InteractionMetadata` uses `ConnectionId`, and the journal contribution boundary rejects IDs not
present in the current environment. Drivers cannot obtain the mutable registry or runtime
connection mutators.

The module contains no JUnit, Testcontainers, Docker image, or wait strategy dependency.
