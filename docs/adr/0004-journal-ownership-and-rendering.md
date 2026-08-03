# ADR 0004: Journal ownership, publication, and rendering boundaries

- Status: Accepted
- Date: 2026-08-03
- Issue: [#42](https://github.com/JacekKardys/system-proof/issues/42)

## Context

The original public `ScenarioJournal` exposed generic mutation, while `EnvironmentEventLog`
combined event construction, append policy, route-failure redaction, logging thresholds, SLF4J
emission, component filtering, and text rendering. That made ownership unclear, allowed isolated
histories outside an environment execution, and rendered large histories through repeated growing
string concatenation.

## Decision

One environment execution owns one package-private `ScenarioJournal`. It alone owns the mutable
entry list, one-based sequence allocation, diagnostic elapsed-time capture, append synchronization,
storage order, and detached immutable snapshot copying. No public generic append or publication
path exists.

`EnvironmentEventPublisher` is the only append client. It exposes only package-private domain
operations used by execution collaborators and the restricted driver/observation capabilities. It
validates scope and identity and publishes each non-idempotent fact exactly once at its established
pipeline point. `JournalContributions` remains limited to component-scoped checkpoint and
disruption facts.

`FailureRedactor` owns identity-based protection state for route preparation and cleanup failures.
It creates detached `FailureDetails` before append. Stored events and snapshots never retain a
`Throwable`; renderers and loggers consume only the frozen details.

`EnvironmentLogging` and its builder belong to `environment`, where topology membership is known
and validation can stay package-private. `LogLevel` belongs to `journal` because severity is part
of a retained `DiagnosticEvent`. `JournalSlf4jEmitter` applies the validated thresholds and owns
SLF4J emission. It receives the exact immutable `JournalEntry` returned after append. A threshold,
including `OFF`, can suppress emission but cannot suppress storage or change `JournalSequence`.

`JournalRenderer` is a stateless public diagnostics view over `JournalEntry` and
`ScenarioJournalSnapshot`. It handles every framework event explicitly, performs structured
component filtering, repeats the complete prefix on every multiline output line, and builds a
complete history with one `StringBuilder`. An unknown event uses a type-only fallback that does not
inspect or render its payload. Its complexity is linear in the total number of generated
characters.

`ScenarioEvent` is a public open inspection contract so adding a framework fact does not break
client source through an exhaustive switch over a sealed root. Implementing it grants no append,
publication, contribution, or environment injection capability. Public framework record
constructors and client implementations create detached values only.
`Environment.journalSnapshot()` is the authoritative supported read path; constructing a detached
read-model value does not publish it into an execution.

Dependencies point from `environment` to `diagnostics`, `journal`, `proof`, `observation`, and the
stable component, topology, endpoint, configuration, and environment-state contracts.
`diagnostics` depends on journal read models. `journal` does not depend on diagnostics and depends
only on stable domain/read values, observation, and proof. Diagnostics depends on detached
`environment.state`, not environment execution. Neither `journal` nor `diagnostics` depends on
mutable execution types.

## Consequences

- There is exactly one mutable event history per environment execution.
- Storage tests need no logging or renderer configuration.
- Logging and rendering cannot append or mutate journal state.
- Route-failure redaction happens before durable storage.
- Large-history rendering no longer performs quadratic growing-string reduction.
- Existing storage order, event semantics, diagnostic formatting, evidence copying, correlation,
  driver SPI, JUnit artifact persistence, gateway behavior, and container integrations remain
  unchanged.
- New control or proof events extend the framework vocabulary without changing storage, redaction,
  SLF4J ownership, or the source compatibility of client switches over the open root.

## Rejected alternatives

- A public `EventBus`, `EventPublisher`, or generic `publish(ScenarioEvent)`: exposes framework fact
  creation as an extension point.
- Separate logging or proof histories: competes with the authoritative journal.
- A repository/service/manager layer: adds indirection without a distinct responsibility.
- Compatibility wrappers for the former public mutable journal or monolithic event log: preserve
  the ownership problem before 1.0.
- A timing microbenchmark: unstable on CI and weaker than a large exact-output regression plus a
  structurally linear `StringBuilder` implementation.
