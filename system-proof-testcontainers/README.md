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
- component-scoped `DriverContext`: typed dependency resolution, journal-backed diagnostics, and
  restricted checkpoint/disruption contributions without exposing `ScenarioJournal`.

The base driver obtains one environment-scoped network, applies aliases and wait strategies,
forwards container logs, starts the container, materializes runtime bindings, creates optional
operations, runs component bootstrap, and returns the cleanup handle.

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

The SPI owns no sockets, listeners, route lifecycle, `ConnectionId`, `SessionId`, ordinal, or
`InteractionRef`. Native reference types remain adapter-local and cross the existing
schema-checked `EvidenceSnapshot` copy boundary. Real HTTP, SMPP, and PostgreSQL adapters are not
included. Semantic holds,
releases, TLS termination, fault mutation, and causal proof are also outside this milestone. One
gateway can serve different contract types concurrently without a gateway registry or global
protocol selector. Consumer containers that resolve a routed endpoint must enable Testcontainers
host access with `withAccessToHost(true)`.

The executable Docker proof and supported host-routing contract are recorded in
[`docs/adr/0002-test-jvm-interaction-gateway.md`](../docs/adr/0002-test-jvm-interaction-gateway.md).
The proof-subject, key-safety, native-reference, and cardinality contracts are recorded in
[`docs/adr/0003-proof-subject-correlation-contracts.md`](../docs/adr/0003-proof-subject-correlation-contracts.md).
