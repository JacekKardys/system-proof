# ADR 0001: T1 proof contract and baseline boundaries

- Status: Accepted
- Date: 2026-07-24
- Issue: [#2](https://github.com/JacekKardys/system-proof/issues/2)

## Context

System Proof must eventually prove the AML T1 invariant:

> A positive SMPP `deliver_sm_resp` must not be emitted before PostgreSQL confirms successful
> commit of RAW and Outbox, with `synchronous_commit=on`.

The current `SmsIngestionSmokeIT` demonstrates end-to-end reachability, a successful positive
response, and persisted RAW and Outbox rows. It does not control or observe the PostgreSQL commit
boundary. Consequently, its waits and observations cannot establish the cross-component ordering
required by T1.

## Decision

### Transaction and success boundary

RAW and Outbox must be written by one PostgreSQL transaction. For the SMS under test:

1. both writes belong to the same transaction;
2. PostgreSQL confirms that transaction's commit succeeded;
3. only after that confirmation may the system emit the correlated positive `deliver_sm_resp`.

A positive response means a correlated `deliver_sm_resp` whose SMPP `command_status` is zero.
Negative or error responses do not satisfy T1.

T1 succeeds only when structured evidence establishes all of the following:

- the transaction contains the matching RAW and Outbox writes;
- PostgreSQL reports successful commit of that transaction;
- the correlated positive response occurs after the commit-success evidence;
- an independent database connection observes RAW and Outbox atomically after commit.

The proof scenario must first verify `SHOW synchronous_commit` returns `on`. This is an explicit
test prerequisite, not an assumed server default. A different value invalidates the proof.

### Allowed evidence

The proof may rely on:

- a semantic barrier that holds the matching PostgreSQL commit at a controlled boundary;
- structured PostgreSQL protocol evidence correlated to the held transaction;
- structured SMPP protocol evidence correlated to the submitted SMS;
- independent database reads showing neither row before release and both rows after successful
  commit;
- a single structured scenario journal that preserves the barrier and protocol evidence needed to
  evaluate the invariant.

### Evidence that is not sufficient

None of the following proves cross-component ordering:

- wall-clock or monotonic timestamp comparison;
- application, container, or database log-line order;
- `sleep` calls or elapsed-time assumptions;
- polling or test-await order;
- the order in which the test reads database and SMSC results;
- SMSC-local counters or event indexes.

These signals may remain diagnostic, but the T1 assertion must not depend on them.

## Baseline module boundaries

| Module | Responsibility | Boundary |
| --- | --- | --- |
| `system-proof-core` | Typed topology, configuration, lifecycle, runtime bindings, and diagnostics | No JUnit, Testcontainers, or service discovery |
| `system-proof-junit5` | JUnit 5 environment discovery, lifecycle integration, injection, and failure artifacts | Depends on core; no Testcontainers dependency |
| `system-proof-testcontainers` | Container-backed drivers, mapped runtime endpoints, and container diagnostics | Depends on core; no JUnit integration responsibility |
| `system-proof-examples` | Executable PostgreSQL example and AML SMS baseline smoke scenario | Test-only consumer of the three framework modules |

`CoreModuleBoundaryTest` and `Junit5ModuleBoundaryTest` enforce the dependency restrictions listed
above. The Maven reactor order records the intended dependency direction:

```text
system-proof-examples -> system-proof-junit5        -> system-proof-core
        |------------> system-proof-testcontainers -> system-proof-core
        `------------------------------------------> system-proof-core
```

## Consequences

- `SmsIngestionSmokeIT` remains a smoke/baseline test and must not be presented as proof of T1.
- This baseline task changes no production behavior and no public framework API.
- `ScenarioJournal`, runtime connections, protocol gateways, decoding, barriers, and fault
  injection remain separate roadmap tasks.
- A future T1 test that lacks any required evidence above is incomplete even if it is repeatably
  green.
