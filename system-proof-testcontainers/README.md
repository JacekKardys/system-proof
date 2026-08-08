# System Proof Testcontainers

This module supplies reusable container-backed `ComponentDriver` support. It is not an environment
engine and performs no classpath discovery.

Public composition API:

- `TestcontainersDriver<C, O, T>`: typed base driver for component `T`, configuration `C`, and
  operations `O`.
- `ContainerPlan`: prepared container plus provided-port bindings.
- `PortBinding.port(port)`: known internal container port selection.
- `StartedContainer`: restricted mapped-address view for operations and bootstrap hooks.
- `InteractionGateway` and `TcpEndpointAdapter<C>`: connection-owned TCP routes from consumer
  containers through the test JVM to mapped provider endpoints.
- `ProtocolAdapter<E>`, `ProtocolSession<E>`, `ProtocolStream<E>`, `ProtocolUnit<E>`, and
  `ProtocolLimits`: protocol-neutral, bounded framing, typed-evidence, and immutable declarative
  correlation-contribution SPI.
- `ProtocolObservationContract`: the adapter-provided profile for protocol ID/scheme, endpoint
  value type, evidence and native-reference schemas, capabilities, and positively declared
  supported features. Observed route preparation compares it with both the logical port contract and the
  scenario-owned core `RequiredObservationProfile` for the exact `ConnectionId` before opening a
  listener; required observation fails closed when any declaration is missing or mismatched.
- component-scoped `DriverContext`: typed dependency resolution, journal-backed diagnostics, and
  restricted checkpoint/disruption contributions without exposing `ScenarioJournal`.

The base driver obtains one environment-scoped network, applies aliases and wait strategies,
does not subscribe to container output, starts the container, materializes runtime bindings,
creates optional operations, runs component bootstrap, and returns the cleanup handle.

## Container diagnostics

System Proof does not call `GenericContainer.withLogConsumer(...)` and exposes no container-log
capture hook. It therefore does not subscribe to, buffer, split into lines, materialize, journal,
or attach container stdout/stderr. This avoids Testcontainers' line-oriented consumer path, which
may accumulate a complete unterminated line before delivering an `OutputFrame` to an application
callback. Drivers that require container logs must acquire and govern them through external
tooling outside System Proof diagnostics. The complete contract is in
[`ADR 0010`](../docs/adr/0010-secret-safe-diagnostics.md).

Testcontainers maps host ports dynamically. Ports and container objects remain inside this adapter;
logical components and connections contain no runtime address data. Core owns lifecycle ordering
and partial-start cleanup.

`InteractionGateway` integrates through core's `ConnectionRouting` seam. A typed endpoint adapter
extracts the provider's external TCP address and creates routed endpoint copies from one immutable
`ConnectionRouteContext`, while the existing `RuntimeConnection` owns the listener and its cleanup.
Routing and observation remain independent. `DISABLED` uses the transparent `transferTo` path.
`OPTIONAL` with no protocol adapter is explicitly `UNSUPPORTED`; with an adapter it is `ACTIVE` and
becomes `DEGRADED` after a framing, codec, journal, correlation, or decision failure. `REQUIRED`
must establish an active adapter path and becomes `FAILED` while closing the affected socket pair
if trustworthy observation is lost. Failed or degraded routes never admit later sessions as
transparent retries.

Unexpected accept-loop termination is also terminal: an active `REQUIRED` route becomes `FAILED`
and an active `OPTIONAL` route becomes `DEGRADED` through the same dynamic runtime status. The
configured `DISABLED` and `UNSUPPORTED` statuses retain their observation-capability meaning even
though the failed listener cannot accept transport sessions. The first listener cause is retained
for the later connection-owned cleanup, whose failures are
suppressed without replacing it. Normal shutdown records its expected transition before closing
the listener and changes a healthy `ACTIVE` route to `INACTIVE`. Sessions established before a
listener failure remain route-owned and may finish normally; the failed listener accepts no new
sessions, and the eventual route close still releases every remaining socket and task. A socket
close failure observed by a finishing session is buffered by the route; after session tasks stop,
the sole route close appends all socket failures to the listener cause in socket-registration
order. Session cleanup never mutates the shared listener cause directly.

For every observed physical socket pair the gateway opens one core `InteractionSession` and one
adapter `ProtocolSession`. The two flow directions have independent `ProtocolStream` state, bounded
buffers, and ordinals while sharing the same core `SessionId`. A stream decodes arbitrary
fragmentation and coalescing into complete `ProtocolUnit<E>` values. Each unit preserves its exact
original bytes and then executes:

```text
frame -> record immutable evidence -> publish correlation -> decide FORWARD -> write exact original bytes
```

`ProtocolUnit<E>` may carry an immutable list of `CorrelationContribution<?>` values alongside its
evidence and exact bytes. The adapter creates each contribution from a safe semantic
`CorrelationKey` and its own typed native-reference codec. It receives no proof-subject registry,
journal, environment runtime, coordinator, connection/session identity, socket, or lifecycle
object. After recording supplies the `InteractionRef`, the gateway publishes every contribution
through that same core session. Units with an empty list remain fully compatible.

The gateway validates that the emitted bytes are the exact current buffer prefix. It never
re-encodes from evidence and never writes a controlled unit prefix before journal append and the
environment correlation/coordinator boundaries. The coordinator therefore observes the final
`MISSING`, `UNIQUE`, or `AMBIGUOUS` result for that interaction. Earlier complete units may advance,
but later bytes cannot overtake them. Maximum frame bytes and aggregate buffered bytes are explicit
`ProtocolLimits`.
Malformed, unsupported negotiation/encryption, ambiguous, desynchronized, excessive-frame, and
buffer-overflow conditions are typed `ProtocolFailureKind` values. Diagnostics report only
connection identity, failure stage/classification, and never payloads, frames, addresses, ports, or
secrets.

The gateway owns sockets, listeners, route lifecycle, `SessionId`, ordinals, and `InteractionRef`.
It passes the exact logical `ConnectionId` when opening an adapter session so route-scoped protocol
authorization cannot fall back to endpoint-local identifiers. Native reference types remain
adapter-local and cross the existing
schema-checked `EvidenceSnapshot` copy boundary. Real bounded PostgreSQL, HTTP, and SMPP adapters
are provided by their own downstream modules. Semantic hold and
one-shot release use the generic forwarding-permit boundary described above. TLS termination,
fault mutation, cross-connection causal proof, and the final verdict remain outside this module. One
gateway can serve different contract types concurrently without a gateway registry or global
protocol selector. Consumer containers that resolve a routed endpoint must enable Testcontainers
host access with `withAccessToHost(true)`.

The executable Docker proof and supported host-routing contract are recorded in
[`docs/adr/0002-test-jvm-interaction-gateway.md`](../docs/adr/0002-test-jvm-interaction-gateway.md).
The proof-subject, key-safety, native-reference, and cardinality contracts are recorded in
[`docs/adr/0003-proof-subject-correlation-contracts.md`](../docs/adr/0003-proof-subject-correlation-contracts.md).
