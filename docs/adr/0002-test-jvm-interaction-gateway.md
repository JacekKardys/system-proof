# ADR 0002: Test-JVM interaction gateway across container boundaries

- Status: Accepted
- Date: 2026-07-26
- Issue: [#7](https://github.com/JacekKardys/system-proof/issues/7)

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
`ConnectionRouteProvider<C>` values through `gateway.tcp(adapter)`. An immutable
`TcpEndpointAdapter<C>` knows only how to:

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
- Route cleanup closes all sockets and reports a connection cleanup failure if its tasks cannot
  terminate within the bounded shutdown interval.
- Testcontainers' forwarding helper is suite-scoped and may retain an exposed-port registration
  until JVM shutdown. The connection-owned JVM listener and all accepted connections are still
  released at environment cleanup; the retained registration owns no logical route or endpoint.

Diagnostics never render hosts, mapped ports, credentials, endpoint objects, or payload bytes.

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
exposure is unavailable.

## Consequences

- The container-boundary transport risk is isolated in `system-proof-testcontainers`; core remains
  protocol- and Testcontainers-neutral.
- The same gateway instance can serve HTTP, PostgreSQL, SMPP, or other TCP endpoint contracts
  without a gateway-side selector.
- Each route currently has one listener and a virtual thread per transfer direction. This is
  appropriate for deterministic system tests and avoids a Netty dependency.
- This decision establishes reachability and lifecycle only. It does not claim observation,
  decoding, typed evidence, causality, TLS tunnelling, fault injection, or Toxiproxy support.
