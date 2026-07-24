# Environment Harness Testcontainers

This module supplies reusable container-backed `ComponentDriver` support. It is not an environment
engine and performs no classpath discovery.

Public composition API:

- `TestcontainersDriver<C, O, T>`: typed base driver for component `T`, configuration `C`, and
  operations `O`.
- `ContainerPlan`: prepared container plus provided-port bindings.
- `PortBinding.port(port)`: known internal container port selection.
- `StartedContainer`: restricted mapped-address view for operations and bootstrap hooks.
- `DriverContext`: typed dependency resolution and component event logging.

The base driver obtains one environment-scoped network, applies aliases and wait strategies,
forwards container logs, starts the container, materializes runtime bindings, creates optional
operations, runs component bootstrap, and returns the cleanup handle.

Testcontainers maps host ports dynamically. Ports and container objects remain inside this adapter;
logical components and connections contain no runtime address data. Core owns lifecycle ordering
and partial-start cleanup.
