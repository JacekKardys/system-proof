# System Proof Core

This module contains the core domain contracts, extension SPI, read models, and environment
execution implementation. The canonical compatibility and dependency policy is
[Package and API architecture](../docs/architecture/package-api-architecture.md).

Public contracts:

- `environment.Environment`: lifecycle, diagnostics, and reverse-order cleanup over an
  immutable topology.
- `environment.EnvironmentBuilder` and `environment.EnvironmentCreator<E>`: the mutable component,
  connection, configuration, and logging boundary plus the typed facade creation callback.
- `environment.ComponentPortFactory`: low-level helpers for programmatic port declarations; annotated
  fields remain the normal declarative path.
- `environment.EnvironmentTopology`: one concrete immutable model of components and logical
  connections, consumed by environment facades and runtime code.
- `component.Component` and `component.AbstractComponent<C, O>`: one component identity,
  typed configuration, owned ports, driver, lifecycle state, and optional typed operations.
- `@SystemComponent` and `ComponentConfig<D>`: declarative component type, driver, flattened
  component configuration, and separate driver-only configuration.
- `RequiredPort<C>`, `ProvidedPort<C>`, `Contract<C>`, `ConnectionId`, and `Connection<C>`:
  directional typed topology without runtime addresses.
- detached `RuntimeConnectionSnapshot` values: inspection of the one authoritative internal
  runtime materialization per logical connection, without exposing endpoint values.
- `ConnectionRouting`, `ConnectionRouteProvider<C>`, `ConnectionRouteContext<C>`, and
  `ConnectionRoute<C>`: typed runtime selection, orthogonal observation policy, connection-scoped
  observation access, and a connection-owned effective endpoint/resource seam without topology
  proxy DSL.
- `ObservationRequirement`, `EffectiveObservationStatus`, `InteractionDecisionCoordinator`, and
  `ForwardingDecision`: explicit observation intent, immutable effective state, and one
  environment-scoped decision boundary.
- `ComponentDriver<C, O>`, `ComponentBoundDriver<C, O, T>`, component-scoped `DriverContext`,
  restricted `JournalContributions`, and `ComponentRuntime<O>`: runtime materialization SPI.
- `configuration.EnvironmentConfiguration` and `Secret<T>`: immutable external values and redacted
  secrets.
- sealed core-owned `ScenarioEvent` envelopes, `JournalEntry`, `JournalSequence`,
  `ScenarioJournalSnapshot`, `JournalRenderer`, `EvidenceCodec<T>`, and `EvidenceSnapshot`:
  detached inspection/rendering contracts and the external typed-evidence copy boundary. Mutable
  journal storage is not public.
- `ConnectionObservations`, `InteractionSession`, `SessionId`, `FlowDirection`, and
  `InteractionRef`: protocol-neutral, connection-bound traffic identity and contribution boundary.
- `ProofSubjects`, `ProofSubjectRef`, `CorrelationKey`, `CorrelationContribution<T>`, and
  `CorrelationResult<T>`: environment-scoped opaque subject identity, secret-safe semantic keys,
  detached typed native references, and explicit cardinality.
- `EnvironmentLogging`, top-level `EnvironmentLoggingBuilder`, `EnvironmentDiagnostics`, and
  `EnvironmentStartException`: logging configuration, rendered journal views, and failure reporting.

Packages are grouped by domain owner, not by Java shape. The exact supported API/SPI and read-only
surface is maintained in the canonical architecture document and enforced from compiled bytecode,
including nested types.

Core validates component ID uniqueness, port ownership and direction, contract/interaction/protocol
compatibility, exactly one provider per required port, logging references, dependency cycles, and
complete provided-port materialization.

## Declarative component model

Concrete component classes declare their stable `ComponentType` and driver with
`@SystemComponent`. Core derives the component configuration and operations types from the direct
`AbstractComponent<C, O>` superclass, then derives `D` from `C extends ComponentConfig<D>`. A single
metadata boundary resolves these contracts through the driver hierarchy and validates all four
types, the target declared by `ComponentBoundDriver<C, O, T>` when present, the component
no-argument constructor, and the unique driver constructor accepting `D`. Testcontainers drivers
implement this explicit component-bound SPI; unrelated generic base-driver parameters have no
component-target meaning.

`new EnvironmentBuilder()` binds from a snapshot of system properties and environment variables.
`new EnvironmentBuilder(EnvironmentConfiguration)` accepts an explicit snapshot. Both expose
`component(ComponentClass.class)` and `component("qualifier", ComponentClass.class)`. Materialization
binds `C` and `D`, constructs the driver and component, initializes annotated ports, and only then
adds the exact returned component instance to the builder. `Environment` contains no component
factory, mutable declaration collections, or construction DSL. The builder validates and freezes
the topology and logging configuration before creating the runtime facade.

Construction ends at `build(...)`: it passes immutable `EnvironmentTopology` and
`EnvironmentLogging` results to the selected facade constructor. Runtime execution never retains
the builder, mutable declaration lists, configuration binder, component materializer, or validator.
`EnvironmentTopology` is one concrete immutable snapshot, not an interface paired with a
construction-only implementation. Its static `of(...)` factory is the low-level snapshot boundary;
`EnvironmentBuilder` is the normal entry point and validates before calling it. The driver-bearing
runtime component view is package-private; public inspection returns `List<Component>`.
`EnvironmentCreator<E>` is a separate functional interface so facade creation remains an explicit,
documented extension point rather than a nested builder implementation detail.

The lower-level `EnvironmentBuilder.component(...)` overloads accept an already materialized
configuration and `ComponentDriver<C, O>`. The explicit-`ComponentType` overload supports isolated
tests and programmatically built configurations without adding factory methods or constructor DSLs
to concrete component classes. Programmatically constructed component fixtures use `ComponentPortFactory`;
the component model itself exposes no port factory methods.

`ComponentFactory`, `ConnectionFactory`, reflection-backed `ComponentMetadata`, `ComponentInitializer`,
`PortDeclarations`, and `TopologyValidator` are package-private construction implementation details.
They are not retained by `Environment`, `EnvironmentTopology`, or `EnvironmentRuntime`.

`Connection<C>` is the immutable logical declaration. Its typed `ConnectionId` is derived
deterministically from both component and local port identities. Each canonical endpoint uses
`component-type[qualifier].local-port`, with empty brackets representing an absent qualifier.
Component type and qualifier are separate semantic fields; construction deliberately does not use
the flattened `ComponentId.toString()` or `ComponentId.value()` display form. Port names use
delimiter-safe percent encoding. The ID does not depend on contract identity alone, endpoint
values, mapped Docker ports, startup order, object identity, or hash codes. Several required ports
may target one provided port while retaining distinct IDs. `ConnectionDescriptor` derives or
validates the same canonical ID against its structured endpoint metadata.

`EnvironmentRuntime` creates one ordered runtime-connection registry from the validated topology.
The registry materializes each declaration exactly once, rejects duplicate IDs or required-port
materialization, and indexes by ID, required port, provider, and provided port. A
`RuntimeConnection<C>` owns its immutable descriptor, `DECLARED -> STARTING -> RUNNING -> STOPPING
-> STOPPED` lifecycle or terminal `FAILED` state, routing mode, observation requirement, effective
observation status, one-shot direct `EndpointBinding<C>`, and a separate effective consumer
binding. State transitions are centrally checked. A connection cannot become `RUNNING` until both
targets are available.

Drivers still publish both internal and external endpoint values as the direct binding.
`DriverContext.resolve(...)` reaches the required port's runtime connection and returns only the
internal value of its consumer binding. `ComponentRuntime` has no public binding or provided-port
resolution method. It only transfers its published bindings into a non-publicly-constructible,
environment-owned typed boundary used by `RuntimeConnectionRegistry`. The external direct form is
retained for JVM gateway routing. Public inspection returns detached immutable snapshots
containing semantic metadata, state, mode, observation requirement, effective observation status,
and separate direct/consumer availability; it never returns endpoint values, route implementations,
closeable resources, Testcontainers objects, mapped ports, aliases, or credentials.

`DIRECT` aliases the consumer target to the direct binding and allocates no route resource.
`ROUTED` selects a typed `ConnectionRouteProvider<C>` using immutable rules keyed by semantic
`Contract<C>` or one stable structured connection identity. A policy can contain several rules,
connection-specific rules take precedence, and unmatched connections remain `DIRECT`. This keeps
distinct contracts using the same Java class separate. The provider is invoked once per
`RuntimeConnection`, receives one immutable context containing its stable descriptor, typed direct
binding, `ObservationRequirement`, exact connection-scoped observation capability, and the one
environment-scoped `InteractionDecisionCoordinator`, and returns a typed consumer binding plus an
optional connection-owned resource. The context exposes no journal, mutable runtime state,
topology mutation, socket, or container details. The sole unchecked conversion is confined to the
private contract-and-connection-validated routing boundary.

Provider fan-out preparation is atomic: the runtime prepares every route before publishing any
targeted connection as `RUNNING`. A later preparation failure closes prior routes in reverse order,
keeps the startup failure primary, and suppresses cleanup failures. Cleanup first removes consumer
availability for the full provider set, closes route resources in reverse order exactly once, then
invalidates direct targets before closing the provider. Route cleanup failure makes the affected
connection terminally `FAILED` without preventing remaining provider cleanup.

Route preparation and cleanup exceptions remain unchanged for the caller, including suppressed
failure ordering. Before those failures enter the environment journal, their endpoint-bearing messages are
replaced with safe metadata containing the failure type, route stage, and structured connection
identity. Connection, component, and environment rendering therefore share the same redacted
details without creating a second history.

`ROUTED` is not `OBSERVED`: access to a connection-bound capability records nothing by itself.
`ConnectionRouting` keeps `RoutingMode` at `DIRECT | ROUTED` and attaches the separate
`ObservationRequirement.DISABLED | OPTIONAL | REQUIRED` to a route rule. A route must report a
compatible `EffectiveObservationStatus`; required observation cannot bind a transparent route.
Observation is `PENDING` before route preparation and `INACTIVE` after clean shutdown of a formerly
active route. Snapshots expose this state without exposing transport internals. The environment
constructs one thread-safe coordinator shared by all route contexts; its current serialized
decision is `FORWARD`. `ConnectionRouting` enters through the protected runtime construction seam
rather than the public topology DSL. Protocol framing, buffers, and sockets remain in the
Testcontainers adapter module.

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
External modules define a typed `EvidenceCodec<T>` for their observation value. A route provider
opens an `InteractionSession` from the `ConnectionObservations` capability bound to its exact
runtime connection. The session accepts only flow direction, codec, and evidence; it allocates the
connection-bound `SessionId`, direction-local ordinal, and complete `InteractionRef`.

Every physical session receives a new connection-local session value. Ordinals begin at one and
increase independently for `CONSUMER_TO_PROVIDER` and `PROVIDER_TO_CONSUMER` within each session.
Identity allocation and submission to the journal are serialized per session direction.
The package-private environment journal separately serializes sequence allocation and insertion
through one synchronization boundary. The resulting global journal sequence remains
storage/rendering order only, not causal
order. Values from different connections, sessions, or directions are not comparable evidence of
ordering or causality. Explicit causal relations are outside this layer.

`Environment.proofSubjects()` exposes one narrow facade over the environment-owned correlation
state. `create()` allocates an opaque reference whose owner token and local value have no public
constructor or accessor. `arm(...)` accepts only a `CorrelationKey`: a namespaced/versioned schema
plus 16-64 bytes of domain-produced digest material copied on input and never returned or rendered.
Domains normalize and digest their source values before core sees the key. Core therefore contains
no protocol fields, raw selector strings, maps, unchecked casts, phone numbers, message content,
tokens, SQL parameters, or credentials.

An adapter or domain captures its immutable native reference as a
`CorrelationContribution<T>` through its own `EvidenceCodec<T>`. Capture retains only a detached
`EvidenceSnapshot`. After `InteractionSession.observe(...)` returns, the same session validates
that the reference belongs to a previously recorded interaction and publishes each contribution.
A typed facade lookup validates the requested schema before decoding a fresh copy. Native HTTP,
SMPP, or PostgreSQL reference types and schemas remain defined by their adapter modules; core never
flattens or interprets them.

Current correlation state is linearized by one environment-owned synchronization boundary:

- no distinct candidate is `MISSING`;
- exactly one distinct candidate is `UNIQUE`;
- a second distinct candidate or a key shared by subjects is terminal `AMBIGUOUS`;
- an exact duplicate is idempotent only while the same subject, key, `InteractionRef`, native
  schema, and encoded native reference match;
- retries and reconnects have distinct interaction/session identity and therefore cannot silently
  rebind a unique result;
- unmatched candidates are journaled as unassigned and are never retroactively selected after
  later arming;
- completion, rollback, and teardown do not erase or reclassify recorded facts; teardown rejects
  new creation, arming, and publication while preserving typed lookup.

Only `CorrelationResult.Unique<T>` exposes the recorded `InteractionRef` and decoded native
reference. Missing and ambiguous result types expose no candidate. No path selects first, last,
latest, earliest, next, or arrival-order candidates.

Core encodes and copies evidence before append; the caller-owned value, codec, and returned array
are not retained. Decoding is typed, schema-checked, and receives another copy, so snapshot access
cannot mutate storage. The core renderer handles each envelope explicitly and renders only
connection, session, flow, ordinal, interaction reference, schema identity, and encoded size; it
never renders the payload or calls an arbitrary payload `toString()`.

Component and connection contributions are deliberately separate. `DriverContext` does not expose
mutable journal storage; its `JournalContributions` sink contains only component-owned checkpoint and
disruption operations and cannot publish traffic. A component-scoped context resolves only
required ports owned by that component. Route providers receive neither mutable journal storage nor
runtime connection mutators. All observations still append to the same environment-owned history;
proof-subject creation, arming, and non-idempotent correlation publications use additional
core-owned immutable envelopes in that same history. The runtime keeps only a
thread-safe current-cardinality index, not a second event history. Journal sequence, diagnostic
time, wall-clock time, rendered order, sleeps, and unrelated stream ordinals never infer
correlation or causality.

The environment execution owns one package-private mutable `ScenarioJournal`. Its append method is
package-private and only `EnvironmentEventPublisher` receives it. The publisher constructs narrow
framework facts, validates contribution scope, applies identity-based route-failure redaction, and
appends exactly once at the existing pipeline point. `JournalSlf4jEmitter` consumes the returned
immutable stored entry only after append, owns logging thresholds, and treats `OFF` as no emission
rather than no history. Neither collaborator owns a second event list.

`ScenarioEvent` remains a public sealed inspection vocabulary; public record constructors create
detached values but cannot append them to a runtime. Before 1.0, every new permitted variant is an
explicit compatibility change for exhaustive pattern matching. `Environment.journalSnapshot()` is
the supported authoritative read path. `JournalRenderer` consumes only detached snapshots, handles
every permitted event explicitly, supports full and structured component filtering, repeats the
same prefix across multiline messages, and appends into one `StringBuilder` so construction is
linear in total output size.

The module contains no JUnit, Testcontainers, Docker image, or wait strategy dependency.
