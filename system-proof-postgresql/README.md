# System Proof PostgreSQL

`system-proof-postgresql` observes a strictly characterized plaintext PostgreSQL v3 subset. It
identifies explicit transactions on physical protocol sessions, contributes semantic write
correlation, and supplies a complete control unit for an explicit commit.

## Dependency boundary

```text
system-proof-postgresql
    -> system-proof-testcontainers
    -> system-proof-core
```

Protocol parsing, session state, PostgreSQL types, and transport buffers remain outside core. SMS
fingerprinting and reference-table policy remain in the examples module.

## Public contract

- `PostgresqlProtocolAdapter` implements the gateway `ProtocolAdapter` SPI.
- `TransactionRef` identifies one explicit transaction by physical adapter-session ordinal and
  transaction ordinal. Its versioned codec is the native-reference codec used by correlation.
- `PostgresqlEvidence` is a closed, secret-safe evidence hierarchy. `CommitAttempt` marks the
  complete frontend commit unit before forwarding. `CommitSucceeded` proves that PostgreSQL
  successfully confirmed that transaction on the observed physical protocol session.
- `PostgresqlWriteCorrelation` receives an ephemeral `PostgresqlWriteInteraction` during one
  supported write decode. It may return one digest-based `CorrelationKey`; the adapter contributes
  that key to the active `TransactionRef`. The interaction view expires when the callback returns.
- `PostgresqlDurabilityVerifier` runs a pre-proof environmental preflight on a test-owned JDBC
  connection. It does not access or administer a SUT JDBC connection.

The adapter never retains or renders SQL text, bind values, credentials, database URLs, usernames,
authentication payloads, cancellation keys, or raw frames. Statement evidence contains only typed
classes and transaction references. The structured write shape renders only kind,
schema-qualification presence, and column count.

## Commit semantics

```text
recognized write
    -> CorrelationKey -> active TransactionRef
complete frontend commit unit
    -> CommitAttempt recorded and semantic permit decided
    -> exact original bytes forwarded once
matching backend CommandComplete(COMMIT)
    -> matching terminal ReadyForQuery(IDLE)
    -> CommitSucceeded for that TransactionRef
```

`CommitSucceeded` is emitted only when the complete commit unit for the active transaction was
forwarded and the same physical session returned the matching `CommandComplete(COMMIT)` followed
by terminal `ReadyForQuery(I)`. Backend errors, rollback, failed status, EOF, reconnect, malformed
flow, or desynchronization prevent that result. Session and transaction ordinals prevent a result
from being reassigned across physical sessions or transactions.

`CommitSucceeded` does not independently prove physical storage durability and does not inspect
application data. The proof-specific RAW/Outbox visibility assertion belongs to the test.

The gateway holds the entire supported extended commit unit. It never forwards a Parse, Bind,
Execute, or Sync prefix before release. Release causes one write/flush attempt of the adapter's
exact original bytes; a failed write is not retried.

## Durability preflight

Before proof traffic, open an independent test-owned connection using the reference database and
role and run:

```java
PostgresqlDurabilityResult durability = PostgresqlDurabilityVerifier.verify(
    verificationConnection,
    new PostgresqlDurabilityRequirements(Set.of(
        new PostgresqlDurabilityRequirements.Table("public", "raw_sms_event"),
        new PostgresqlDurabilityRequirements.Table("public", "outbox_event")
    ))
);
durability.requireSatisfied();
```

The preflight requires:

- `fsync=on`;
- `synchronous_commit=on` for the configured database/role represented by that connection;
- every requested schema-qualified relation to exist;
- every requested relation to be an ordinary permanent WAL-logged table
  (`relkind='r'`, `relpersistence='p'`).

Views, materialized views, foreign tables, sequences, partitioned tables, temporary tables,
unlogged tables, missing relations, and other relation kinds have explicit unsatisfied
`RelationStatus` values. Partitioned tables are rejected rather than recursively interpreted.
`requireSatisfied()` fails closed for every non-satisfied result.

The reference PostgreSQL container explicitly starts with `fsync=on` and
`synchronous_commit=on`; the test does not depend only on image defaults.

## Trust boundary

The wire parser rejects directly observed `SET synchronous_commit`, `SET LOCAL
synchronous_commit`, `SET SESSION synchronous_commit`, and `RESET synchronous_commit`. It also
rejects the safely recognizable direct `SELECT [pg_catalog.]set_config('synchronous_commit', ...)`
form inside the characterized parser boundary. It does not parse stored procedure bodies,
arbitrary functions, triggers, extensions, or startup options and does not inject same-session
queries.

The durable interpretation of `CommitSucceeded` requires a successful durability preflight and
assumes that the controlled SUT does not alter `synchronous_commit` through unobservable session
startup options, server-side functions, procedures, extensions, or triggers after preflight
validation.

This is an explicit characterized-driver assumption. A separate connection does not validate
transaction-local state at the instant of commit.

## Proof-subject composition

The reference example owns the SMS-specific policy:

```text
ProofSubjectRef + high-entropy proof discriminator
    -> digest-only SMS CorrelationKey
    -> recognized raw_sms_event INSERT
    -> active TransactionRef
    -> held CommitAttempt
    -> CommitSucceeded
    -> independent RAW + Outbox visibility assertion
```

The reference fingerprint uses length-delimited UTF-8 source and destination fields plus a
SHA-256 content digest. The policy accepts only the documented `raw_sms_event` table, exact column
list, and explicit parameter-to-column mapping. It does not search TCP chunks or all bind values,
and it does not use Jasmin's generated `external_message_id` as the discriminator. Only the
digest-based `CorrelationKey` crosses into generic correlation contracts.

Future HTTP and SMPP adapters can bind their native request/response identities to the same
`ProofSubjectRef`. Cross-connection order must come from explicit predecessor guards, never from
timestamps, journal append order, socket order, sleeps, or "the next response". HTTP/SMPP ACK
decoding and the final cross-connection T1 proof are outside this module.

## Supported MVP

- PostgreSQL `17.6-alpine`, pgJDBC `42.7.7`, Spring Boot `3.5.9`, `JdbcTemplate`,
  `@Transactional`, Flyway, and the repository's reference ingestion service;
- SSLRequest followed by server `N`, StartupMessage v3, SCRAM messages, ParameterStatus,
  BackendKeyData, ReadyForQuery, and Terminate;
- simple transaction-control commands and extended Parse, Bind, Describe, Execute, Close, Flush,
  and Sync framing with one execution per Sync;
- ordered `$1..$N` INSERT placeholders, per-parameter text/binary formats, and optional Parse type
  OIDs exposed only through the ephemeral correlation view;
- named and unnamed statements and portals, including default `prepareThreshold=5` reuse;
- the bounded pgJDBC `BEGIN` plus one-operation lookahead;
- explicit activation, write, failure, rollback, commit attempt, and commit result;
- autocommit writes classified separately and never promoted to explicit-transaction success.

Memory is bounded by `ProtocolLimits`, at most 256 named statements, 256 portals, 128 UTF-8 bytes
per object name, 256 bind parameters per unit, and two causally ordered frontend cycles for the
characterized `BEGIN` lookahead. There is no global transaction or payload registry.

Protocol feature negotiation is positive. The PostgreSQL adapter declares neither encrypted
transport nor general pipelining as supported, so either required feature is rejected before
traffic.

## Explicit limitations

- TLS response `S` fails closed; the module does not terminate or inspect TLS.
- General pipelining, multiple executions per Sync, multi-statement simple units, partial
  row-returning portals, COPY/replication, reused write portals, and commit units containing Flush
  are unsupported.
- The SQL tokenizer recognizes only characterized transaction controls, direct setting changes,
  and the supported parameterized INSERT shape. It is not a general SQL parser.
- The module does not implement SMS attribution, HTTP/SMPP decoding, cross-connection predecessor
  guards, CDC/logical decoding, HA/failover semantics, or the final T1 proof.

The sanitized characterization is recorded in
[`../docs/adr/0005-postgresql-wire-evidence.md`](../docs/adr/0005-postgresql-wire-evidence.md).
