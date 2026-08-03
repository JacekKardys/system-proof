# ADR 0003: Proof-subject and correlation contracts

- Status: Accepted
- Date: 2026-07-26
- Issue: [#27](https://github.com/JacekKardys/system-proof/issues/27)
- Prerequisite: [#8](https://github.com/JacekKardys/system-proof/issues/8)

## Context

The gateway can frame a complete protocol unit, record typed evidence, call one environment
coordinator, and forward the exact original bytes. Later semantic hold work must select an already
recorded interaction that belongs to the operation chosen by the scenario. HTTP, SMPP, and
PostgreSQL expose different native identities, lifecycles, retry rules, and failure modes. A global
string ID or arrival-order lookup would erase those semantics and could silently bind the wrong
interaction.

The correlation layer must therefore answer two separate questions:

1. which opaque scenario operation is being proved;
2. whether explicit safe facts identify exactly one recorded protocol interaction for that
   operation.

It must answer without retaining source secrets, exposing mutable runtime state, or treating journal
storage order as causality.

## Decision

### Scenario-scoped proof subjects

Each `Environment` execution owns one internal subject registry and exposes only
`Environment.proofSubjects()`. The `ProofSubjects` facade can:

- `create()` an opaque `ProofSubjectRef`;
- `arm(subject, key)` with a safe correlation key;
- query `correlation(subject, key, nativeReferenceCodec)` for a typed result.

`ProofSubjectRef` has no public constructor and exposes neither its environment ownership token nor
its local numeric value. The runtime allocates it before proof traffic. Equality includes hidden
environment ownership, so using a reference with another environment execution is rejected even
when both executions allocated a similarly rendered local reference. Rendering is limited to a
safe scenario-local label.

Creation and arming are allowed while the environment execution accepts traffic facts. Teardown
rejects new creation, arming, and candidate publication. Existing results and the journal remain
queryable after teardown. Protocol completion, rollback, or application failure does not delete or
rewrite a proof subject.

### Secret-safe keys

`CorrelationKey` contains:

- a namespaced and versioned `CorrelationKeySchema`;
- 16-64 bytes of domain-produced digest material.

The adapter or domain owns semantic normalization and digest calculation. Core accepts no raw
normalized string, phone number, message content, token, SQL parameter, credential, frame, or
protocol object. It copies the digest on construction, exposes no byte accessor, and uses the digest
only for equality and hashing. `toString()`, journal rendering, diagnostics, and exceptions contain
only schema identity and digest size.

Java field names, implementation class names, `Object`, `Map<String, Object>`, unchecked casts, and
generic raw strings are not key identity.

### Typed protocol-native references

Protocol-native identity remains in the adapter module. A future HTTP adapter may define an exchange
reference, an SMPP adapter a session-safe request/response reference, and a PostgreSQL adapter a
transaction-cycle reference. Core does not flatten them into a global protocol-independent ID.

An adapter creates `CorrelationContribution<T>` from:

- one safe `CorrelationKey`;
- its module-owned `EvidenceCodec<T>`;
- its immutable native reference value.

Capture is synchronous and retains only a detached `EvidenceSnapshot`. The source value, codec, and
codec-produced array are not retained. Typed lookup requires a codec with the exact stored
`EvidenceSchemaId`; mismatch fails explicitly before decoding. Every decode receives a fresh byte
array, so caller mutation cannot alter stored state.

### Explicit cardinality

`CorrelationResult<T>` is sealed:

- `Missing<T>` means no trustworthy distinct candidate exists;
- `Unique<T>` contains the one recorded `InteractionRef`, the native-reference schema, and the
  decoded typed native reference;
- `Ambiguous<T>` means exact selection is impossible.

Only `Unique<T>` exposes a candidate. The state rules are:

1. an armed subject/key starts `MISSING`;
2. the first distinct trustworthy candidate becomes `UNIQUE`;
3. any second distinct candidate makes it `AMBIGUOUS`;
4. `AMBIGUOUS` is terminal;
5. a key armed by more than one subject makes every association for that key terminal
   `AMBIGUOUS`;
6. a candidate with no armed subject is journaled as unassigned and does not retroactively bind if
   the key is armed later.

An exact duplicate may be idempotent only while every identity component is equal: environment
subject, key schema and digest, complete `InteractionRef`, native-reference schema, and encoded
native reference. A different interaction ordinal is distinct. A retry is distinct. A reconnect has
a different `SessionId` and is distinct. A different native reference or schema is distinct.
Protocol rollback or completion does not make a prior candidate disappear and cannot return
ambiguous state to unique.

There is no fallback to first, next, latest, earliest, most recently journaled, or most recently
completed candidates.

### Journal authority and runtime index

The closed `ScenarioEvent` hierarchy adds three core-owned immutable facts:

- `ProofSubjectCreatedEvent`;
- `ProofSubjectArmedEvent`, including whether the key is shared;
- `CorrelationCandidateEvent`, including the optional subject, safe key, complete
  `InteractionRef`, detached native-reference snapshot, and resulting cardinality.

The registry is synchronized and retains only the current per-subject/key resolution plus the one
unique candidate needed for typed lookup. It is an index, not a second event history. Every
non-idempotent creation, arming, unmatched publication, unique resolution, and ambiguity detection
is recorded in the single environment-owned journal.

`JournalSequence`, diagnostic elapsed time, wall-clock time, append order, sleeps, rendered order,
and ordinals from other sessions are never correlation inputs. Correlation depends only on explicit
environment ownership, key equality, complete interaction identity, and typed native snapshot
identity. These facts establish cardinality, not cross-protocol causality.

### Gateway ordering and failure behavior

`ProtocolUnit<E>` may carry an immutable list of `CorrelationContribution<?>` values alongside its
typed evidence and exact original bytes. The protocol adapter receives no mutable journal storage,
registry, `EnvironmentRuntime`, coordinator, socket, gateway lifecycle object, connection identity,
session identity, or ordinal.

For each correlated complete unit the gateway executes:

```text
frame complete unit
-> record typed evidence and obtain InteractionRef
-> publish all typed correlation contributions through the same InteractionSession
-> invoke InteractionDecisionCoordinator.decide(InteractionRef)
-> forward exact original bytes
```

`InteractionSession` validates that correlation refers to a previously recorded interaction from
the same physical session. A unit with no contributions remains compatible. The coordinator
therefore sees the interaction's correlation state before deciding, which allows later issue #12
work to select by typed semantics, exact connection and direction, and optional `ProofSubjectRef`
without changing these identity or cardinality contracts.

Correlation extraction/capture, journal publication, resolution, and coordinator callbacks are
inside the gateway's existing fail-closed boundary. A `RuntimeException` or `Error` closes both
sockets before any undecided byte is written. Required observation becomes `FAILED`; active
optional observation becomes `DEGRADED`; the opposite direction cannot continue. Diagnostics render
only safe connection identity and failure stage/classification. Exception messages, key material,
native references, payloads, addresses, and ports are not logged. An original `Error` is rethrown
after cleanup where the execution boundary can preserve it.

## Rejected alternatives

- A caller-constructible numeric or textual subject ID: forgeable and not execution-isolated.
- Raw normalized correlation values in core: unnecessary secret retention.
- One global native interaction ID: erases protocol and session semantics.
- Journal scanning as the public lookup API: exposes storage details and invites arrival-order
  fallback.
- A gateway-owned correlation database: competes with the environment journal and runtime.
- Rebinding on retry, reconnect, rollback, or completion: turns ambiguity into an implicit winner.

## Consequences

- Core gains a small protocol-neutral identity/cardinality API and no protocol/domain fields.
- Adapters keep their native reference types and normalization policy.
- The scenario journal remains the single auditable event history.
- Mutable journal ownership, publication, redaction, logging, and rendering boundaries are defined
  by [ADR 0004](0004-journal-ownership-and-rendering.md).
- The gateway now exposes correlation state at the existing decision boundary without changing
  immediate `FORWARD`.
- Issue #12 still owns semantic `HOLD`/`RELEASE`, barrier state, and causal guards.
- Real HTTP, SMPP, and PostgreSQL adapters still own decoding, native reference schemas, retry
  semantics, and any AML-specific safe-key policy or token transport.
- Proof verdicts, PostgreSQL transaction attribution, TLS termination, transport fault injection,
  and a generic query language remain out of scope.
