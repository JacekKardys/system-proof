# ADR 0002: Test-JVM interaction gateway across container boundaries

- Status: Accepted
- Date: 2026-07-26
- Issues: [#7](https://github.com/JacekKardys/system-proof/issues/7),
  [#8](https://github.com/JacekKardys/system-proof/issues/8)

## Context

System Proof must eventually interpose protocol-aware evidence and disruption capabilities between
real components. The highest transport risk is a consumer container reaching a listener in the test
JVM, with that listener reaching a provider container's mapped endpoint. The route must work with a
local Linux Docker Engine and with Docker Desktop on Windows/WSL2 without placing Docker addresses,
mapped ports, proxy objects, or protocol selection in the logical topology.

The preceding runtime work already materializes every logical connection as one
`RuntimeConnection`. It owns a direct provider target, an effective consumer target, and an
optional routed resource. `ConnectionRouting` selects providers by semantic contract or structured
connection identity. Replacing that mechanism with gateway-owned route lookup would create a second
source of truth and would make concurrent HTTP, PostgreSQL, and SMPP contracts ambiguous.

## Decision

### Ownership and concurrent contracts

`InteractionGateway` is a protocol-neutral Testcontainers adapter. One instance supplies typed
`ConnectionRouteProvider<C>` values through `gateway.tcp(adapter)`. Each invocation receives one
immutable route context for the exact materialized connection. The context contains the separate
observation requirement, connection-bound observation capability, and the one environment-scoped
decision coordinator. An immutable `TcpEndpointAdapter<C>` knows only how to:

1. extract the JVM-reachable TCP address from endpoint value `C`;
2. copy `C` with a different host and port while preserving its other fields.

`ConnectionRouting` attaches each provider to a semantic contract or one connection. The gateway
does not contain a connection map, route registry, protocol registry, or global contract switch.
For every matching logical connection it creates a separate listener and returns that listener as
the resource in the connection's `ConnectionRoute`. The existing `RuntimeConnection` therefore
remains the structurally unavoidable owner of selection, effective target, lifecycle, and cleanup.
The gateway re-extracts every adapter-produced endpoint and rejects it unless it carries the exact
listener host and port requested by the route, so an incorrect adapter cannot silently preserve the
direct provider address and bypass the gateway.

Different endpoint types can coexist because each routing rule carries its own typed adapter. The
executable spike uses `EndpointAddress` for HTTP and `SmppEndpoint` for an SMPP-representative
long-lived session under one gateway instance.

### Routing, observation, and framing

`RoutingMode` remains limited to `DIRECT | ROUTED`. Observation is an orthogonal
`ObservationRequirement`:

- `DISABLED` keeps the simple transparent relay and reports `DISABLED`;
- `OPTIONAL` reports `ACTIVE` when a protocol adapter is configured, otherwise it explicitly
  reports `UNSUPPORTED`;
- `REQUIRED` must prepare an active protocol adapter path or route preparation fails.

`RuntimeConnectionSnapshot` exposes both the requirement and immutable effective status. Routed
traffic therefore never implies observed traffic. If an active optional path loses trustworthy
observation it becomes `DEGRADED`; a required path becomes `FAILED`. Neither state silently admits
later sessions as transparent relays.

The protocol SPI is independent of endpoint adaptation. `ProtocolAdapter<E>` supplies an
`EvidenceCodec<E>` and opens one `ProtocolSession<E>` per physical socket pair. That session creates
different `ProtocolStream<E>` instances for `CONSUMER_TO_PROVIDER` and
`PROVIDER_TO_CONSUMER`. A stream receives a read-only view of bounded, not-yet-forwarded bytes and
emits complete `ProtocolUnit<E>` values. Each unit contains typed evidence and a defensive copy of
its exact original bytes. The SPI receives no socket, listener, route lifecycle, `ConnectionId`,
`SessionId`, ordinal, or `InteractionRef`.

The gateway owns the byte buffer and enforces independent maximum-frame and aggregate-buffer limits
per session direction. For every complete unit it unconditionally executes:

```text
frame -> record -> decide -> forward exact original bytes
```

`InteractionSession.observe(...)` first copies typed evidence into the single environment journal
and returns the stable `InteractionRef`. ADR 0003 extends this boundary with
immutable correlation contributions: they are published after observation and before the one
thread-safe environment coordinator returns the current milestone's only decision, `FORWARD`. Only
then does the gateway write the adapter-preserved bytes. It validates that they equal the current
buffered prefix, never reconstructs them from evidence, and processes coalesced units serially so
later bytes cannot overtake earlier complete units. No prefix of an incomplete or undecided unit
crosses the downstream socket.

### Address selection and host exposure

For each route:

- the gateway binds an ephemeral TCP listener to IPv4 loopback `127.0.0.1` in the test JVM;
- the upstream target is extracted from the provider binding's external form, normally the
  Testcontainers host plus its dynamically mapped port;
- the internal consumer endpoint is copied with
  `host.testcontainers.internal:<listener-port>`;
- the external routed endpoint is copied with `127.0.0.1:<listener-port>`;
- `Testcontainers.exposeHostPorts(listenerPort)` installs Testcontainers' required host-forwarding
  transport;
- every consumer container using a routed endpoint opts in with `withAccessToHost(true)` before
  container startup.

The Testcontainers forwarding helper and its exposed-port bookkeeping are infrastructure supplied
by the selected container runtime. They do not select logical routes and do not hold provider
endpoint values. Route selection and ownership remain connection-local.

### Lifecycle ordering

The runtime order is:

1. start the provider container;
2. publish its direct internal and external bindings;
3. bind and expose every connection listener targeting that provider;
4. atomically commit all direct and consumer targets as `RUNNING`;
5. start consumers whose required ports are startup prerequisites;
6. let each consumer resolve only its routed internal endpoint.

Normal cleanup stops consumers first. Provider detachment then makes all consumer targets
unavailable, closes connection routes in reverse declaration order, invalidates direct targets, and
finally stops the provider container. Closing a route closes its listener and active downstream and
upstream sockets before terminating its virtual-thread tasks.

If consumer startup fails after route creation, environment rollback follows the same reverse
cleanup. If preparation of a later route fails, the runtime closes already prepared routes in
reverse order before any target is committed.

### Supported environments

The supported setups are:

- a local Docker Engine on Linux as used by the GitHub-hosted Ubuntu CI runner;
- Docker Desktop with Linux containers on Windows, including the WSL2 backend.

Both require a Docker daemon reachable by Testcontainers and Testcontainers'
`host.testcontainers.internal` forwarding mechanism. A remote daemon, a containerized/sidecar test
runner, a non-Docker runtime, or a restricted daemon is unsupported unless that setup independently
provides the same Testcontainers host-access contract.

The gateway does not guess Docker bridge addresses, use host networking, derive aliases from mapped
ports, or fall back to an unverified hostname.

### Failure modes

- Listener bind failure aborts route preparation before consumer startup.
- Failure of `Testcontainers.exposeHostPorts` aborts preparation with a diagnostic naming the
  semantic connection and the required Docker/Testcontainers host-routing contract. The newly
  opened listener is closed before the failure escapes.
- A null, invalid, or incompatible endpoint replacement aborts preparation and closes the listener.
- Failure to connect to the provider ends only that accepted session and is logged using connection
  identity and failure type, not endpoint values.
- Malformed input, unsupported negotiation or encryption, ambiguous framing, desynchronization,
  excessive frame size, or excessive buffered bytes closes the affected observed socket pair before
  forwarding the unresolved unit.
- Codec, journal, correlation, or coordinator failure applies the same fail-closed policy. Optional
  observation becomes `DEGRADED`; required observation becomes `FAILED`. Already decided and
  written units are not rolled back or reordered.
- EOF on an empty directional buffer propagates half-close. EOF with an incomplete required unit is
  desynchronization and closes the socket pair without forwarding that unit.
- Route cleanup closes all sockets and reports a connection cleanup failure if its tasks cannot
  terminate within the bounded shutdown interval.
- Testcontainers' forwarding helper is suite-scoped and may retain an exposed-port registration
  until JVM shutdown. The connection-owned JVM listener and all accepted connections are still
  released at environment cleanup; the retained registration owns no logical route or endpoint.

Diagnostics never render hosts, mapped ports, credentials, endpoint objects, payload bytes, raw
frames, or exception messages from adapter/codec/journal/coordinator failures.

## Executable proof

`InteractionGatewayIT` runs one provider container exposing two independent ports and one consumer
container using two startup-required connections. The HTTP path sends a real request and response.
The SMPP endpoint path keeps one bidirectional TCP connection open for `bind`, `submit-1`, and
`submit-2` request/response exchanges. Distinct response prefixes detect cross-wiring.

The normal scenario verifies both routes are simultaneously `ROUTED`, then closes the environment
and rebinds both listener ports. The injected-failure scenario fails consumer startup only after
both routed endpoints resolve, then verifies that the provider container stopped, both runtime
connections lost their targets, and both listener ports can be rebound.

Docker-free tests additionally cover active-session closure and fail-fast diagnostics when host
exposure is unavailable. A test-only four-byte length-prefixed adapter separately covers
byte-by-byte fragmentation, every header/payload split, coalesced frames, explicit failure
classifications, frame limits, and incomplete EOF without relying on socket read boundaries.
Socket tests use latches and bounded timeouts to prove journal visibility before downstream
visibility, exact ordered bytes in both directions, shared physical `SessionId`, reconnect identity,
multiple connections under one coordinator, fail-closed faults, half-close, and active-session
shutdown.

## Consequences

- The container-boundary transport risk is isolated in `system-proof-testcontainers`; core remains
  protocol- and Testcontainers-neutral.
- The same gateway instance can serve HTTP, PostgreSQL, SMPP, or other TCP endpoint contracts
  without a gateway-side selector.
- Each route currently has one listener and a virtual thread per transfer direction. This is
  appropriate for deterministic system tests and avoids a Netty dependency.
- The framework now establishes protocol-aware observe-before-forward ordering, exact-byte
  forwarding, explicit observation status, and fail-closed required semantics.
- No real HTTP, SMPP, or PostgreSQL codec is included. ADR 0003 supplies only the protocol-neutral
  proof-subject and correlation contracts. Semantic `HOLD`/`RELEASE`, predecessor guards, causal
  proof, TLS termination, fault injection, and Toxiproxy remain later work.
