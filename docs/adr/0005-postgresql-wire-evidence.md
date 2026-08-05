# ADR 0005: PostgreSQL wire evidence for explicit commit

- Status: Accepted
- Date: 2026-08-04
- Updated: 2026-08-05
- Issue: [#11](https://github.com/JacekKardys/system-proof/issues/11)

## Context

System Proof needs a real protocol adapter that can stop the exact commit of a semantically
correlated explicit transaction before PostgreSQL receives any byte, then distinguish that attempt
from PostgreSQL's successful confirmation. Environmental durability prerequisites are a separate
test concern; the adapter must not require the SUT's internal JDBC connection or inject
administrative queries into its session.

This ADR records only protocol states and message classes. It omits credentials, SQL text, bind
values, message payloads, cancellation keys, database endpoints, and raw frames.

The controlled characterization uses:

- PostgreSQL `17.6-alpine`, explicitly configured with `fsync=on` and
  `synchronous_commit=on`;
- pgJDBC `42.7.7` with default extended mode and `prepareThreshold=5`;
- Spring Boot `3.5.9`, `JdbcTemplate`, `@Transactional`, Flyway, and the default datasource;
- the complete reference SMSC, Jasmin, ingestion, PostgreSQL, RabbitMQ, and Redis topology.

## Sanitized observed flow

```text
frontend: SSLRequest
backend:  N
frontend: StartupMessage(version 3)
backend:  Authentication/SASL exchanges
backend:  ParameterStatus*, BackendKeyData, ReadyForQuery(idle)

frontend: explicit transaction start
frontend: optional bounded lookahead of one extended operation
backend:  transaction-start completion, ReadyForQuery(transaction)

frontend: Parse, Bind, optional Describe, Execute, Sync
backend:  parse/bind completions, optional NoData, write completion,
          ReadyForQuery(transaction)

frontend: complete extended commit unit ending in Sync
backend:  parse/bind completions, CommandComplete(COMMIT), ReadyForQuery(idle)
```

pgJDBC may send transaction start and its first operation before the start result. The adapter
accepts exactly that two-cycle lookahead and keeps backend outcomes in causal FIFO order. It does
not accept general pipelining. Named statement reuse after the prepare threshold and pooled
physical-session reuse preserve the physical session ordinal while each new transaction advances
its transaction ordinal.

## Decision

Add `system-proof-postgresql` with dependency direction:

```text
system-proof-postgresql -> system-proof-testcontainers -> system-proof-core
```

One `ProtocolSession` owns one synchronized bidirectional state model. It allocates a
`TransactionRef` for a recognized explicit transaction start. Terminal idle, rollback, error,
desynchronization, disconnect, or session abandonment retires that reference. Reconnect creates a
new physical session ordinal.

The complete supported commit unit is one gateway control unit. `CommitAttempt` is recorded before
the forwarding permit, so a semantic hold stops every byte. Release authorizes one write/flush of
the exact original bytes. `CommitSucceeded` is emitted only for the same `TransactionRef` after the
complete unit was forwarded, matching `CommandComplete(COMMIT)` arrived, and the same physical
session returned terminal `ReadyForQuery(I)` without an invalidating condition.

`CommitSucceeded` proves PostgreSQL protocol confirmation. It does not independently prove
physical storage durability and does not inspect application rows.

A synchronous correlation callback sees only the structured supported write shape and temporary
read-only bind slices. It expires on return. Only a digest-based `CorrelationKey` and typed
`TransactionRef` contribution survive. Supported INSERT placeholders are exactly `$1..$N` in
column order.

The reference example owns the RAW-write policy. It recognizes only the exact unqualified
`raw_sms_event` INSERT with columns `id`, `external_message_id`, `source_address`,
`destination_address`, and `content`. Source, destination, and content are mapped explicitly. A
length-delimited SHA-256 message fingerprint binds the test workload to the observed write without
retaining the high-entropy discriminator, message content, SQL, or bind values. Jasmin's generated
external message ID is not used for correlation.

Durability is a pre-proof environmental verification on an independent test-owned connection. It
checks `fsync=on`, configured/default `synchronous_commit=on`, and each required schema-qualified
relation. Only ordinary permanent logged tables (`relkind='r'`, `relpersistence='p'`) satisfy the
result. Missing, unlogged, temporary, view, materialized-view, foreign-table, sequence,
partitioned-table, and other relation kinds are typed unsatisfied results. `requireSatisfied()`
fails closed.

The adapter rejects directly visible changes to `synchronous_commit` through `SET`, `SET LOCAL`,
`SET SESSION`, `RESET`, and the safely recognized direct `set_config` form. It does not parse
stored procedures, functions, triggers, extensions, or startup options and does not inject a
same-session probe.

The durable interpretation of `CommitSucceeded` requires a successful durability preflight and
assumes that the controlled SUT does not alter `synchronous_commit` through unobservable session
startup options, server-side functions, procedures, extensions, or triggers after preflight
validation.

## Consequences and limits

The reference integration test starts the unchanged containerized SUT, observes unrelated Flyway
transaction traffic, arms a proof subject before each external SMS, derives a semantic RAW-write
correlation, holds the subject-bound commit before its first byte, proves RAW and Outbox absence
through an independent connection, releases exactly once, observes matching `CommitSucceeded`, and
then proves exactly one linked RAW/Outbox result. The scenario repeats five times. Completion of
the SMS submission is used only as a liveness check.

This remains a bounded plaintext adapter, not a general PostgreSQL proxy or SQL engine. TLS
termination, general pipelining, multi-statement units, partial row-returning portals,
COPY/replication, arbitrary SQL analysis, CDC, HA/failover, HTTP/SMPP ACK correlation, and the final
cross-connection T1 proof remain outside this decision. Cross-connection order cannot be inferred
from timestamps, journal append order, socket order, sleeps, or test await order.
