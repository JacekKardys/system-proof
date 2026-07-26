# System Proof Testcontainers

This module supplies reusable container-backed `ComponentDriver` support. It is not an environment
engine and performs no classpath discovery.

Public composition API:

- `TestcontainersDriver<C, O, T>`: typed base driver for component `T`, configuration `C`, and
  operations `O`.
- `ContainerPlan`: prepared container plus provided-port bindings.
- `PortBinding.port(port)`: known internal container port selection.
- `StartedContainer`: restricted mapped-address view for operations and bootstrap hooks.
- `InteractionGateway` and `TcpEndpointAdapter<C>`: connection-owned transparent TCP routes from
  consumer containers through the test JVM to mapped provider endpoints.
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
The context also carries core's capability bound to that exact connection, but the gateway does not
use it yet: it remains a transparent `transferTo` relay with no TCP framing or evidence
contribution. One gateway can therefore serve different contract types concurrently without a
gateway registry or global protocol selector. Consumer containers that resolve a routed endpoint
must enable Testcontainers host access with `withAccessToHost(true)`.

The executable Docker proof and supported host-routing contract are recorded in
[`docs/adr/0002-test-jvm-interaction-gateway.md`](../docs/adr/0002-test-jvm-interaction-gateway.md).
