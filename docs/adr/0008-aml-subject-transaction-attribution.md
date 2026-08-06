# ADR 0008: AML proof-subject transaction attribution

- Status: Accepted
- Date: 2026-08-06
- Issue: [#24](https://github.com/JacekKardys/system-proof/issues/24)
- Prerequisites: [#9](https://github.com/JacekKardys/system-proof/issues/9),
  [#10](https://github.com/JacekKardys/system-proof/issues/10),
  [#11](https://github.com/JacekKardys/system-proof/issues/11), and
  [#27](https://github.com/JacekKardys/system-proof/issues/27)

## Context

The bounded HTTP, SMPP, and PostgreSQL adapters can contribute protocol-native references for one
canonical SMS fingerprint. The proof scenario still needs to attribute the matching PostgreSQL
transaction before selecting its commit control point. Selecting the first, next, earliest, or
latest commit would silently misattribute background work, concurrent subjects, retries, pooled
sessions, and reconnects.

The characterized SUT uses Spring `JdbcTemplate`, pgJDBC 42.7.7 extended-query flow, and an
explicit Spring transaction. The existing decoded RAW INSERT is a stable wire-visible carrier; no
application transaction marker is required.

## Characterized carrier

The examples-owned `SmsMessageFingerprint.rawWriteCorrelation()` policy accepts only all of these
facts from one supported decoded write interaction:

- statement kind `INSERT`;
- no schema qualifier;
- table `raw_sms_event`;
- exact ordered columns `id`, `external_message_id`, `source_address`,
  `destination_address`, `content`;
- exactly five parameters;
- text, non-null parameter 2 for `source_address`;
- text, non-null parameter 3 for `destination_address`;
- text, non-null parameter 4 for `content`;
- one active explicit `TransactionRef` owned by that physical PostgreSQL adapter session.

Parameter indexes are zero-based. The generated `id` and Jasmin `external_message_id` parameters
do not participate in correlation. The policy never searches raw TCP bytes, arbitrary bind
parameters, SQL substrings, logs, or timing.

The source and destination addresses are stripped and lower-cased. The canonical digest
length-delimits their UTF-8 bytes and a SHA-256 digest of the UTF-8 content, then emits the same
versioned digest-only `CorrelationKey` used by `httpCallbackCorrelation()` and
`smppDeliverCorrelation()`. Raw values exist only in the synchronous, expiring policy view.

## Decision

The scenario creates a high-entropy proof discriminator, `TestSms`, canonical `CorrelationKey`,
and `ProofSubjectRef`, then arms every subject and semantic hold before external traffic. The
attribution chain is:

```text
ProofSubjectRef
  -> canonical SmsMessageFingerprint CorrelationKey
  -> SmppExchangeRef
  -> HttpExchangeRef
  -> TransactionRef
  -> CommitAttempt
  -> CommitSucceeded
```

The arrows between native references mean that independently decoded facts resolve under the same
subject and key. They do not assert an order between connections. The PostgreSQL adapter assigns
the `TransactionRef` when the recognized RAW INSERT is decoded inside its active explicit
transaction, before the later commit control unit reaches the coordinator.

One subject/key can legitimately resolve one reference in each protocol-native codec namespace.
The existing `ProofSubjectRegistry` therefore maintains cardinality by
`CorrelationKey + EvidenceSchemaId`. The generic semantic hold asks for the namespace declared by
its native-flow selector. This is the minimum protocol-neutral contract required to compose the
three existing adapters; it introduces neither an AML API nor another correlation registry.

Within each codec namespace, cardinality remains fail-closed:

- no trustworthy candidate is `MISSING`;
- exactly one distinct candidate is `UNIQUE`;
- a second distinct candidate is terminal `AMBIGUOUS`;
- sharing one key between subjects is `AMBIGUOUS`;
- `MISSING` and `AMBIGUOUS` never select a subject-bound hold.

The commit selector resolves only
`ProofSubjectRef -> CorrelationKey -> TransactionRef -> CommitAttempt`. It never falls back to
transaction, socket, await, journal, or wall-clock arrival order.

## Isolation semantics

- **Unrelated commit:** a different subject/key can commit first without reaching the target hold;
  the target remains armed until its own `TransactionRef` is unique.
- **Concurrent subjects:** explicit barriers admit two already armed subjects. Each codec namespace
  resolves independently, the transaction references differ, and one subject's hold cannot select
  the other's commit.
- **Pool reuse:** consecutive transactions may share the same physical adapter session, but their
  transaction ordinals must differ. The integration asserts both equal session ordinals and
  consecutive transaction ordinals before claiming reuse.
- **Rollback:** a recognized RAW write can attribute a transaction that later rolls back. It emits
  no `CommitSucceeded` and cannot satisfy the commit hold. A retry with the same fingerprint is a
  second candidate and makes that native namespace `AMBIGUOUS`.
- **Reconnect:** a new physical session has a new session ordinal. No old `TransactionRef` or
  connection-wide attribution state is reused.

## Secret-safety boundary

Durable evidence, correlation state, journal events, diagnostics, exceptions, and default
rendering may contain only digest-based `CorrelationKey` metadata, detached protocol-native
references, typed evidence, and safe topology identity. They must not contain the proof
discriminator, SMS content, addresses, SQL or bind values, raw protocol bytes, database URLs, or
credentials.

The pinned SMSCsim includes sender and recipient values in its ordinary container output. The
examples-owned SMSC driver removes that suffix before output enters `DriverContext` and the
environment journal. Sanitization therefore happens at the durable boundary, not only in the
diagnostic renderer. A policy exception fails REQUIRED observation closed without publishing its
message or source values.

## Consequences and limits

The real container scenario observes REQUIRED SMPP, HTTP, and PostgreSQL routes together. It proves
one native reference per namespace, one subject-attributed commit attempt, matching commit success
after release, and atomic RAW/Outbox visibility. It also exercises unrelated work, concurrent
subjects, verified physical-session reuse, rollback, retry ambiguity, reconnect, and REQUIRED
policy failure without sleeps or order-based selection.

The carrier remains intentionally narrow. A schema-qualified or differently ordered INSERT,
different parameter count or format, absent explicit transaction, unsupported PostgreSQL flow,
unsupported HTTP/SMPP representation, missing REQUIRED observation, duplicate candidate, or shared
key fails closed as missing, ambiguous, or observation failure.

This decision establishes subject-safe attribution and cardinality. It does not establish a
PostgreSQL-to-HTTP or HTTP-to-SMPP predecessor relation, evaluate proof outcomes, or claim the final
T1 proof. Those remain issues #25, #26, and #13 respectively.
