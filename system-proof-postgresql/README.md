# System Proof PostgreSQL

`system-proof-postgresql` is the first concrete System Proof protocol adapter. It observes a
strictly characterized plaintext PostgreSQL v3 subset and supplies a complete semantic control
unit for an explicit transaction commit.

## Dependency boundary

```text
system-proof-postgresql
    -> system-proof-testcontainers
    -> system-proof-core
```

The module does not move protocol parsing, session state, or transport buffers into core. The
examples module consumes it only in test scope.

## Public contract

- `PostgresqlProtocolAdapter` implements the existing gateway `ProtocolAdapter` SPI.
- `TransactionRef` identifies one explicit transaction by physical adapter-session ordinal and
  transaction ordinal. Its versioned codec is also the native-reference codec used by correlation.
- `PostgresqlEvidence` is a closed, secret-safe evidence hierarchy. `CommitAttempt` marks the
  complete frontend control unit; `CommitSucceeded` is emitted only after the matching backend
  `CommandComplete(COMMIT)`, following `ReadyForQuery(IDLE)`, and exact-session durability
  authorization.
- `PostgresqlWriteCorrelation` receives an ephemeral `PostgresqlWriteInteraction` during decode.
  It may return one digest-based `CorrelationKey`; the adapter then publishes a typed
  `CorrelationContribution<TransactionRef>`. The view becomes invalid when the callback returns.
- `PostgresqlDurabilityVerifier` checks server settings, backend-session independence, and required
  table/trigger facts through both the exact SUT connection and a separately opened JDBC
  connection. Its route-aware overload authorizes durable success only for the exact observed SUT
  transaction on the required logical connection.

The adapter never retains or renders SQL text, bind values, credentials, database URLs, usernames,
authentication payloads, cancellation keys, or raw frames. Statement evidence contains only an
enum and transaction reference. The structured write shape renders only kind, schema-presence, and
column count.

## Commit semantics

The relevant sequence is:

```text
frontend complete commit unit
    -> CommitAttempt recorded and semantic permit decided
    -> exact original unit forwarded once
    -> matching backend CommandComplete(COMMIT)
    -> matching backend ReadyForQuery(IDLE)
    -> exact SUT transaction has one-shot route-bound durability authorization
    -> CommitSucceeded
```

`CommitAttempt` alone does not prove forwarding. `CommandComplete(COMMIT)` alone does not prove the
terminal idle state. `ReadyForQuery(IDLE)` without the matching attempt and command completion does
not prove commit. Backend errors, failed status, rollback, EOF, reconnect, or lost synchronization
cannot produce `CommitSucceeded`.

The gateway holds the entire supported extended commit unit. It does not forward a `Parse`, `Bind`,
or other prefix before the semantic permit. Release causes one write/flush attempt of the adapter's
exact original bytes; the gateway does not retry a failed write.

## Durability prerequisites

Open an independent verification connection. A diagnostic preflight may run before proof traffic,
but it never authorizes success. After the proof write and immediately before `commit()`, run the
route-aware check on the exact SUT connection:

```java
PostgresqlDurabilityResult durability = PostgresqlDurabilityVerifier.verify(
    sutConnection,
    verificationConnection,
    new PostgresqlDurabilityRequirements(Set.of(
        new PostgresqlDurabilityRequirements.Table("public", "proof_entry")
    )),
    postgresqlProtocolAdapter,
    connectionId
);
durability.requireSatisfied();
sutConnection.commit();
```

Success requires all of the following:

- `SHOW synchronous_commit` on the exact SUT connection is exactly `on`;
- `SHOW fsync` is exactly `on`;
- SUT and verification connections have different PostgreSQL backend PIDs;
- SUT and verification checks agree on server and relation facts;
- every required relation has `pg_class.relpersistence = 'p'`;
- no required relation has an enabled non-internal trigger.

Backend PID remains diagnostic only. The verifier sends a bounded, one-shot opaque challenge
through the exact SUT JDBC connection. Only a protocol session opened for the requested
`ConnectionId` can claim it, and the claim records the active `TransactionRef`. A different route,
an unobserved connection, a reconnect, a completed transaction, or a reused challenge cannot
authorize `CommitSucceeded`.

Authorization is deliberately narrow: after it is applied, the next SQL command must be the
matching commit. Every intervening statement revokes it, including `SELECT set_config(...)`, a
function call, or a procedure call; direct `SET [LOCAL|SESSION] synchronous_commit` remains
unsupported. Server-side trigger execution is bounded by rejecting every enabled user trigger on
the required relations. This is conservative: scenarios needing user triggers must supply a
different, stronger durability characterization before they can claim durable success. An
unsatisfied or incomplete check is a failure, not a warning. These checks establish required server
facts; they do not turn PostgreSQL wire evidence into a complete business invariant.

## Supported MVP

- PostgreSQL `17.6-alpine`, pgJDBC `42.7.7`, and the repository's Spring Boot `3.5.9` reference
  ingestion startup;
- SSLRequest followed by server `N`, StartupMessage v3, SCRAM authentication messages,
  ParameterStatus, BackendKeyData, ReadyForQuery, and Terminate;
- exact simple transaction-control commands observed from pgJDBC;
- extended Parse, Bind, Describe, Execute, Close, Flush, and Sync framing with one execution per
  Sync;
- ordered `$1..$N` INSERT placeholders, per-parameter text/binary Bind formats, and optional Parse
  type OIDs exposed to the ephemeral correlation policy;
- named and unnamed statements and portals, including default `prepareThreshold=5` statement
  reuse;
- canonical lowercase quoted identifiers used by Flyway;
- the bounded pgJDBC `BEGIN` plus one-operation lookahead used when autocommit is disabled;
- explicit transaction activation, writes, failure, rollback, commit attempt, and commit result;
- autocommit writes classified separately and never promoted to explicit-transaction success.

Memory is bounded by the route's `ProtocolLimits`, at most 256 named statements, 256 portals,
128 UTF-8 bytes per protocol object name, 256 bind parameters per unit, and at most two causally
ordered frontend cycles for the characterized `BEGIN` lookahead. At most 64 durability challenges
may be pending per adapter, and each is one-shot. There is no global transaction registry.

## Explicit limitations

- TLS response `S` fails closed as unsupported; the module does not terminate or inspect TLS.
- General PostgreSQL pipelining, multiple executions per Sync, multi-statement simple units,
  partial row-returning portals, COPY/replication sub-protocols, reused write portals, and commit
  units containing Flush are unsupported.
- The SQL tokenizer recognizes only the characterized control commands and parameterized INSERT
  shape. Reordered placeholders are rejected rather than guessed. It is not a general SQL parser.
- The module does not implement AML/SMS attribution, cross-protocol correlation, CDC/logical
  decoding, HA/failover semantics, or the final T1 proof.

The sanitized characterization is recorded in
[`../docs/adr/0005-postgresql-wire-evidence.md`](../docs/adr/0005-postgresql-wire-evidence.md).
