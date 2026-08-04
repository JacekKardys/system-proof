# ADR 0005: PostgreSQL wire evidence for explicit commit

- Status: Accepted
- Date: 2026-08-04
- Issue: [#11](https://github.com/JacekKardys/system-proof/issues/11)

## Context

System Proof needs one real protocol adapter that can stop the exact commit of a correlated explicit
transaction before PostgreSQL receives any byte, then distinguish that attempt from credible
backend success. This ADR records only protocol states and message classes. It intentionally omits
credentials, SQL text, bind values, message payloads, cancellation keys, database endpoints, and
raw frames.

The controlled characterization used the repository's real stack without query-mode shortcuts:

- PostgreSQL `17.6-alpine`;
- pgJDBC `42.7.7` with its default extended mode and `prepareThreshold=5`;
- Spring Boot `3.5.9`, `JdbcTemplate`, `@Transactional`, Flyway, and the default datasource
  configuration from `application.yaml`;
- the complete reference ingestion service plus its readiness endpoint.

The protocol interpretation follows the PostgreSQL 17
[message flow](https://www.postgresql.org/docs/17/protocol-flow.html), PostgreSQL 17
[message formats](https://www.postgresql.org/docs/17/protocol-message-formats.html), and the pgJDBC
[connection documentation](https://jdbc.postgresql.org/documentation/use/).

## Sanitized observed transcript

Connection startup was:

```text
frontend: SSLRequest
backend:  N
frontend: StartupMessage(version 3)
backend:  Authentication/SASL challenge
frontend: authentication payload
backend:  Authentication/SASL continuation and completion
backend:  ParameterStatus*
backend:  BackendKeyData
backend:  ReadyForQuery(idle)
```

Authentication payloads were forwarded unchanged but reduced to a payload-free evidence class.
The `N` response preserved plaintext observation. No datasource option was changed to suppress SSL
negotiation. A server response of `S` therefore has a distinct fail-closed outcome before opaque TLS
traffic starts.

Explicit transaction traffic was:

```text
frontend: simple transaction-start Query
frontend: optional bounded lookahead of one extended operation
backend:  transaction-start completion, ReadyForQuery(transaction)

frontend: Parse, Bind, optional Describe, Execute, Sync
backend:  parse/bind completions, optional NoData, write completion, ReadyForQuery(transaction)

frontend: complete extended commit unit ending in Sync
backend:  parse/bind completions, commit completion, ReadyForQuery(idle)
```

pgJDBC may send transaction start followed by the first operation before receiving the
transaction-start result. The
adapter admits exactly that two-cycle lookahead and keeps backend outcomes in causal FIFO order. It
does not admit general pipelining. pgJDBC also used a positive Execute row limit for an update; this
is accepted only for recognized non-row-returning commands. Flyway used canonical lowercase quoted
identifiers in a prepared write.

Before the prepare threshold, prepared operations used unnamed Parse/Bind/Execute/Sync units. At
the default threshold, a named statement was parsed and bound. Later operations reused that named
statement with Bind/Execute/Sync and no new Parse. The physical connection remained the same when
the pool reused it, while each terminal transaction received the next transaction ordinal.

Rollback characterization included backend ErrorResponse, failed transaction status, a rollback
unit, rollback completion, and idle transaction status. Flyway startup opened multiple physical
connections and completed its migration traffic. The service then completed datasource health and
readiness traffic through the same observed route.

## Decision

Add `system-proof-postgresql` with dependency direction:

```text
system-proof-postgresql -> system-proof-testcontainers -> system-proof-core
```

One `ProtocolSession` owns one synchronized bidirectional state model. A `TransactionRef` is
allocated when a recognized explicit transaction-start query enters that physical adapter session.
It becomes active when the causally matching transaction-start completion and transactional
ReadyForQuery status arrive. The reference is
retired on terminal idle, rollback, or session abandonment. The next transaction on a pooled
session advances the transaction ordinal; reconnect creates a new session ordinal.

The complete supported commit unit is one gateway control unit. `CommitAttempt` is recorded before
the forwarding permit, so a semantic hold stops every byte of the unit. Release authorizes one
write/flush attempt of the exact original bytes. `CommitSucceeded` is emitted only for the same
transaction after its commit unit, matching commit completion, and following idle ReadyForQuery
status with no intervening error, disconnect, reconnect, or desynchronization.
The success evidence is additionally gated by a satisfied durability result bound to the exact
logical route, physical protocol session, and active transaction. The verifier sends a one-shot
opaque challenge through the SUT JDBC connection; only the session opened for the requested
`ConnectionId` may claim it. Backend PID is retained only as a diagnostic fact. Reconnect starts
unauthorized, and authorization is never inherited across a physical session boundary.

A synchronous correlation callback sees only the structured supported write shape and temporary
read-only bind slices. Its view is invalidated on return. Only a safe `CorrelationKey` and typed
`TransactionRef` contribution survive. AML/SMS attribution remains future work for #24.
Supported INSERT placeholders are exactly `$1..$N` in column order. The callback sees each Bind
parameter's text/binary format and optional Parse type OID; reordered placeholders and unknown
formats fail closed.

Durable-commit claims additionally require an independent JDBC verification connection,
`synchronous_commit=on` and `fsync=on` on the exact SUT session, a different backend PID from the
verification connection, agreement between both connections on server/relation facts, permanent
logged required tables, and no enabled non-internal trigger on those tables. Failure of any check
prevents declaring durable commit proof.

The route-bound authorization is applied after the checks and must be followed immediately by the
matching commit. Every intervening SQL statement revokes it. This includes configuration functions
such as `set_config(...)` and arbitrary function/procedure calls, without relying on SQL substring
recognition. Direct changes to `synchronous_commit` remain unsupported. Because server-side code
can change transaction-local settings during deferred execution, enabled user triggers on required
relations are rejected conservatively rather than interpreted.

## Limits and consequences

This is a plaintext, fail-closed, bounded adapter for the observed stack, not a general PostgreSQL
proxy. TLS termination, general pipelining, multi-statement units, partial row-returning portals,
COPY/replication flows, general SQL parsing, CDC, HA/failover, and AML attribution are outside the
supported subset. Autocommit writes are explicit negative evidence and cannot become
`CommitSucceeded`.

Protocol buffers remain bounded by `ProtocolLimits`; statement, portal, object-name, bind-count,
and causal-lookahead limits are also fixed. Raw SQL and bind bytes exist only at the active protocol
decode boundary and are neither retained nor rendered. This adapter establishes PostgreSQL commit
evidence and a precise control point; it does not by itself establish the final T1 invariant.
