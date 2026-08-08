# ADR 0011: Frozen proof-plan evaluation

- Status: Accepted
- Date: 2026-08-08
- Issue: [#26](https://github.com/JacekKardys/system-proof/issues/26)
- Builds on: [ADR 0009](0009-semantic-predecessor-guards.md),
  [ADR 0010](0010-secret-safe-diagnostics.md)
- Enables: [#13](https://github.com/JacekKardys/system-proof/issues/13),
  [#62](https://github.com/JacekKardys/system-proof/issues/62)

## Context

Proof subjects, typed correlation, required observation, semantic holds, and predecessor guards
already provide authoritative protocol-neutral facts and enforcement. They deliberately do not
decide whether a whole controlled execution proved its claim. Scenario authors therefore need one
explicit boundary that freezes all required coverage before stimulus, interprets only typed
framework facts, and fails closed when evidence or trust is incomplete.

The final AML T1 scenario and versioned proof-artifact serialization are separate work. This
decision provides the public execution seam they can use without importing PostgreSQL, HTTP, SMPP,
JUnit, or Testcontainers into core.

## Decision

`ProofPlan` is the complete immutable declaration for one primary `ProofSubjectRef`. Its builder
retains declaration order and accepts only bounded typed metadata: prerequisite tokens,
`ConnectionId` plus exact `RequiredObservationProfile`, correlation key plus native-reference
schema, previously declared hold/guard references with their successful terminal states, typed
evidence obligations, explicit guard-owned causal relations, and a positive deadline. It accepts
at most 256 obligations. It retains no predicate, lambda, adapter, payload, throwable, decoded
native reference, or arbitrary rendering source.

One environment execution owns at most one valid proof execution. Its lifecycle is:

```text
DRAFT -> ACTIVATING -> ACTIVE -> EVALUATING -> COMPLETED
```

Malformed, contradictory, foreign, direct/bypassed, or statically incompatible declarations fail
with `ProofConfigurationException` and create no proof outcome. A structurally valid unsupported
runtime prerequisite or unavailable supported path completes `INCONCLUSIVE` before stimulus. An
internal activation failure completes `ERROR` before stimulus.

Activation validates the subject and every reference against the exact environment, validates
profiles, schemas, capabilities, and obligation coverage, samples fresh observation state, and
then arms all prepared controls as one transaction. A later arming failure cancels every control
that was already armed; cancellation never authorizes held traffic. Only after all controls remain
armed does the coordinator establish the evidence window, schedule the deadline, enter `ACTIVE`,
and permit the caller's one stimulus callback. Facts published before that boundary cannot satisfy
the execution.

## Read model and evaluation

The authoritative `ScenarioJournal` remains the only history. The proof coordinator receives the
same framework-owned typed facts after journal append and maintains only a bounded current-state
index for the frozen plan. It does not expose or scan the journal, duplicate entries, interpret
payloads, or use journal sequence, timestamps, callback-await order, map iteration, or stream
ordinals as causality.

Correlation resolution comes only from the existing proof-subject registry contract. Required
correlation is satisfied only by one subject-owned candidate in the exact connection and native
schema namespace. Missing and ambiguous state remain explicit gaps. Controls resolve only from the
exact environment-owned hold or guard reference. A causal relation resolves only from the typed
predecessor-guard relation or violation fact.

Required observation covers the entire evidence window. The exact routed connection and profile
must be `ACTIVE` at activation. Every fresh observation commit is forwarded to the current-state
index. Terminal `FAILED`/`DEGRADED` cache semantics and semantic-control observation-failure
markers remain authoritative; later `ACTIVE` cannot restore lost coverage.

The protocol-neutral outcome evaluator consumes one detached resolution snapshot. Every plan item
has exactly one of:

`SATISFIED`, `VIOLATED`, `MISSING`, `AMBIGUOUS`, `UNSUPPORTED`, `UNREACHED`, `TIMED_OUT`, `FAILED`,
or `NOT_EVALUATED`.

The closed outcomes are exactly:

- `PROVED`: every required item is explicitly `SATISFIED`;
- `VIOLATED`: an authoritative explicit counterexample won the terminal transition;
- `INCONCLUSIVE`: evidence or runtime support is missing, ambiguous, unreached, or timed out;
- `ERROR`: framework, gateway, adapter, journal, control, stimulus, evaluator, or teardown trust
  failed before a violation became terminal.

The first terminal transition is the sole linearization point. Later facts cannot replace its
primary outcome. In particular, later success cannot repair `VIOLATED`, and facts after `ERROR`
cannot become trustworthy evidence. A later cleanup/framework failure may be retained only as one
of at most 32 secondary type-only diagnostics. Repeated evaluation and result access return the
same immutable `ProofResult` instance.

## Result and report

`ProofResult` is detached and deeply immutable. It preserves plan identity and bounded title, an
opaque primary subject, every ordered obligation resolution, decisive evidence identities or the
decisive gap, unresolved/not-evaluated items, a type-only primary failure, and bounded secondary
diagnostics. Its deterministic compact `ProofReport` is limited to 64 KiB characters.

Reports render only framework-owned identifiers, enums, connection/session/interaction identity,
stages, and normalized throwable type. They never render evidence bytes, payloads, native-reference
values, adapter/runtime objects, exception messages or graphs, predicates, or arbitrary opaque
reference `toString()` output. Full journal and troubleshooting diagnostics remain separate
artifacts governed by ADR 0010.

An activated execution left unfinished at environment teardown completes `ERROR` at `TEARDOWN`
and makes `Environment.close()` fail. It cannot disappear as an apparently successful test.

## Public sequence

The first public seam is deliberately explicit and is not a final DSL:

```java
ProofPlan plan = ProofPlan.builder("claim", "Claim", subject, Duration.ofSeconds(30))
    .prerequisite("environment-ready", environment.proofs().satisfiedPrerequisite())
    .observation("http-observation", httpConnection, httpProfile)
    .correlation("http-correlation", httpConnection, key, httpNativeSchema)
    .control("commit-before-http", commitBeforeHttp, SemanticPredecessorGuardState.SATISFIED)
    .causalRelation("commit-before-http-relation", commitBeforeHttp)
    .build();

ProofExecution execution = environment.proofs().activate(plan);
execution.runStimulus(() -> invokeSystemUnderTest());
ProofResult result = execution.evaluate().require(ProofOutcome.PROVED);
```

## Consequences and boundaries

The evaluator is not part of `SemanticControlCoordinator`, a gateway, protocol session, adapter,
correlation registry, or connection lifecycle. Those owners keep their own state and
linearization. Core imports no protocol, JUnit, or Testcontainers type.

This ADR does not claim the final AML T1 proof, define plan templates or nested/parallel proofs, or
serialize/version proof artifacts. Issue #13 may compose the public guard relations for PostgreSQL
commit before positive HTTP forwarding and positive HTTP forwarding before positive SMPP
acknowledgement. Issue #62 may serialize the already detached result without changing evaluation
semantics.
