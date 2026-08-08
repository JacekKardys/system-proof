# ADR 0010: Secret-safe diagnostics contract

- Status: Accepted
- Date: 2026-08-07
- Issue: [#43](https://github.com/JacekKardys/system-proof/issues/43)
- Parent: [#44](https://github.com/JacekKardys/system-proof/issues/44)
- Enables: [#26](https://github.com/JacekKardys/system-proof/issues/26)

## Context

The environment journal, SLF4J output, runtime diagnostics, container output, and JUnit artifacts
previously accepted several arbitrary `String` and exception-message paths. Names such as
"diagnostic", "redacted", or "safe" did not enforce a trust boundary. In particular, exception
messages, configuration `toString()`, unclassified diagnostic suppliers, identity-sanitized
container output, and JUnit reporting failures could reach default output.

The later proof-artifact work needs a dependable default artifact boundary. This decision defines
that boundary; it does not evaluate evidence or create proof outcomes.

## Decision

Every diagnostics source has one of four trust classifications:

1. `SAFE_BY_CONSTRUCTION` is a framework-owned typed fact. Allowed values are enums, lifecycle
   states, boolean capabilities, normalized stable component/connection/interaction identities,
   explicitly allowed schema identifiers, encoded-size metadata, and digest metadata. Open opaque
   proof-subject, hold, and predecessor-guard references are retained for ownership and equality
   semantics but rendered only as fixed presence/type labels. Arbitrary payload or reference
   `toString()` is never safe by construction.
2. `REDACTED_TEXT` is extension text processed by an explicit bounded sanitizer before it enters a
   journal event or default diagnostic source. Safety depends on the supplied redaction policy.
   There is no implicit identity sanitizer and no public `safe(String)` equivalent.
3. `OPT_IN_SENSITIVE` identifies raw troubleshooting data that a driver may expose to its own
   external tooling. System Proof does not invoke or export its supplier and provides no raw
   attachment API.
4. `UNSUPPORTED_FOR_EXPORT` has no accepted safety policy. No framework export path invokes it.

`Environment.diagnostics()` is the only framework diagnostics export path. Its read model has no
public constructor or arbitrary-text factory. Under the `EnvironmentRuntime` monitor, capture
copies one immutable base snapshot containing the lifecycle state, component states, connection
snapshots, journal snapshot, and eligible diagnostic-source list. It then releases that monitor,
invokes eligible suppliers at most once, and renders the detached snapshot. Sensitive and
unsupported suppliers are never invoked by framework capture.

For a running environment, dynamic `ObservationStatusProvider` refresh is a preceding two-phase
operation. The runtime captures stable route probes under its monitors, releases the environment,
registry, and connection monitors, evaluates providers, and atomically commits the detached
results only if the lifecycle is still `RUNNING`. The diagnostics base snapshot then reads the
framework-owned cached statuses. Startup uses the same outside-lock evaluation before dependent
consumers start. Cleanup never evaluates an observation provider.

## Allowed and prohibited data

Default output may contain only the safe-by-construction fields above, fixed framework text,
type-only failure classification, and explicitly sanitized bounded text.

Default output must not contain credentials, passwords, tokens, cookies, authorization values,
SMS bodies, phone numbers or addresses, SQL or bind values, database credentials, HTTP bodies,
SMPP PDUs, PostgreSQL frames, arbitrary protocol bytes, endpoint hosts, mapped ports, URLs, query
strings, user-info, exception messages, stack traces, cause or suppressed messages, arbitrary
configuration/evidence/extension `toString()`, or unfiltered container stdout/stderr.

JUnit title and description entries remain explicit user-authored test metadata. They are not
derived from diagnostics, exceptions, configuration, evidence, or container output and are outside
the diagnostics artifact contract.

## Failure metadata

`FailureDetails.from(Throwable)` reads the runtime class once, immediately normalizes its simple
name to a 128-character classification, and retains only that `String`. It retains neither the
throwable nor its `Class` object. Anonymous, blank, or fully removed type names become `Throwable`.
Creation and rendering do not call or retain `getMessage()`, localized messages,
`Throwable.toString()`, stack traces, causes, or suppressed failures. Equality and hashing use only
the retained classification. Lifecycle stage and component, connection, or driver-resource
identity remain typed event fields.

The former `FailureRedactor` is removed because replacing one arbitrary message with another did
not create a trustworthy boundary. Runtime diagnostic capture failures, Testcontainers startup
failures, and JUnit capture/write/publication failures likewise expose only fixed operation/stage
text plus the normalized failure type. The original throwable remains available through the
programmatic cause or suppressed chain. This is outside the rendered diagnostics boundary: if that
chain escapes the test method, JUnit or Surefire may independently include its messages and stack
traces in console or XML failure reports. System Proof does not intercept or sanitize those
third-party reports.

## Text ingress and rendering

`DriverContext.log(...)` accepts `RedactedDiagnosticText`, not `String`. Creation requires an
explicit sanitizer. `DiagnosticEvent` stores that opaque bounded value. The environment publisher
appends it first and `JournalSlf4jEmitter` uses the same `JournalRenderer.renderLines(...)`
representation as journal rendering. Detached client events cannot enter the environment-owned
journal. Unknown `ScenarioEvent` implementations use a type-only fallback and their `toString()` is
never called. Known framework events containing open `ProofSubjectRef`, `SemanticHoldRef`, or
`SemanticPredecessorGuardRef` values use the same safe-by-construction rule: renderers never invoke
`toString()`, `String.valueOf()`, `hashCode()`, or any other client-controlled reference method.
Their labels expose only fixed `opaque` or `assigned` metadata. Framework event `toString()`
implementations follow the same metadata-only rule, `JournalEntry.toString()` renders only the
normalized event type, and `RedactedDiagnosticText.toString()` exposes length and truncation state
without content.

Component configuration is not rendered. Evidence rendering uses only typed schema and size
metadata. No configuration, evidence value, decoded value, endpoint, or extension object is made
safe by calling `toString()`.

`DiagnosticSource` requires an explicit `REDACTED_TEXT`, `OPT_IN_SENSITIVE`, or
`UNSUPPORTED_FOR_EXPORT` factory. A caller name is limited to 128 characters, converted to a
16-hex-character SHA-256 prefix identity, and not retained. Capture copies the source list before
callbacks, invokes an eligible supplier at most once per capture, and retains only detached output.
Sensitive and unsupported suppliers are not invoked by framework capture. Supplier or sanitizer
failure yields fixed type-only text without raw fallback. User callbacks run after the immutable
base snapshot has been captured and outside the `EnvironmentRuntime` and diagnostics monitors.

`DiagnosticSource.redacted(...)` bounds and sanitizes the returned value only after
`Supplier.get()` completes. It cannot bound memory, I/O, or work performed while the supplier
acquires that value. A trusted driver that registers a redacted source is responsible for bounding
its own acquisition before returning a `String` to the framework.

## Bounds

The limits favor enough failure context for a test artifact while bounding adversarial extension
work and output:

| Boundary | Limit |
| --- | --- |
| Diagnostic source name | 128 characters before digest identity |
| Sanitizer input | 16 KiB characters |
| One redacted result | 4 KiB characters, at most 64 lines including the marker |
| Redacted sources per default capture | 32 |
| Component state entries per default capture | 128 |
| Connection state entries per default capture | 256 |
| One rendered line | 8 KiB characters |
| Journal rendering | 256 KiB characters |
| Complete default environment diagnostics | 256 KiB characters |

Truncation and section omission use only `[TRUNCATED]`, `[DIAGNOSTICS TRUNCATED]`,
`[COMPONENT STATE OMITTED]`, or `[CONNECTION STATE OMITTED]`; no marker includes omitted text.
Sanitizer exceptions, `null`, and blank results use fixed `DIAGNOSTIC OMITTED` classifications. No
failure path falls back to raw input.

Public metadata is bounded before storage. Component type and qualifier values are at most 64
ASCII identifier characters; port names are at most 64 non-control characters; contract,
interaction, protocol, evidence/correlation schema, checkpoint, disruption, diagnostic-source,
and driver-resource identifiers are at most 128 characters under their type-specific character
sets. Canonical connection IDs are at most 2,048 characters and JVM type names at most 512.

## Container output and JUnit artifacts

`ContainerPlan` does not accept an arbitrary `GenericContainer`. It creates a package-private final
lifecycle that disables Testcontainers logging for that container, overrides both `getLogs()`
forms with an empty counted denial, installs no-op internal waiting, and exposes neither
`withLogConsumer(...)` nor `waitingFor(...)`. Framework-owned TCP/HTTP readiness runs only after the
real inherited `GenericContainer.start()` returns. This excludes `LogMessageWaitStrategy`, whose
direct Docker-log subscription is invisible to `getLogConsumers()`.

These controls cover upstream `GenericContainer.tryStart()`: its exception logger is disabled and
its fallback `getLogs()` call cannot reach `LogUtils.getOutput()` or the unbounded
`ToStringConsumer`. No `FrameConsumerResultCallback.LineConsumer` is installed, so multi-megabyte
unterminated output is not assembled in the System Proof process. The guarantee follows from the
restricted lifecycle, not from documentation or an empty consumer list. Container troubleshooting
remains the responsibility of external tooling outside the System Proof diagnostics boundary;
there is no internal, opt-in, or sanitized container-text path.

On a failed JUnit scenario the extension writes only
`target/system-proof-artifacts/<scenario>/environment.log`. Report entries publish the stable name
`environment.log`. Capture/write failures publish only a fixed operation and failure type and
remain suppressed against the primary failure.

## Source inventory

| Source | Classification and default behavior |
| --- | --- |
| Lifecycle, topology identity, state, capability, schema, digest, and size facts | `SAFE_BY_CONSTRUCTION`; rendered from typed fields only |
| Throwable graph | One normalized bounded `String` in `FailureDetails`; throwable, `Class`, messages, and traces excluded |
| Open proof-subject / semantic-control references | Retained as typed opaque values for semantics; rendered as fixed `opaque` / presence labels without invoking reference methods |
| Driver log text and `DiagnosticEvent` | `REDACTED_TEXT`; opaque bounded value required before append |
| `DiagnosticSource.redacted` | Supplier invoked once; bounded sanitizer required |
| `DiagnosticSource.sensitive` | `OPT_IN_SENSITIVE`; supplier never invoked or exported by System Proof |
| `DiagnosticSource.unsupported` | `UNSUPPORTED_FOR_EXPORT`; supplier never invoked by framework export |
| Component configuration and extension/evidence values | Excluded; no `toString()` rendering |
| Unknown client event | Type-only fallback; payload and `toString()` ignored |
| Container stdout/stderr | Excluded by the restricted lifecycle: disabled upstream logger, denied full-log fallback, no consumer/log-wait API, and non-log readiness |
| JUnit safe artifact/report errors | Safe environment content and fixed operation plus type-only failure |

## Consequences and residual limits

The default contract is enforceable at framework ingress and covered by adversarial canaries. It
does not claim universal data-loss prevention. A user-supplied sanitizer can be incomplete, and a
redacted diagnostic supplier can perform unbounded work before returning unless its trusted driver
bounds acquisition. The container guarantee applies only to `TestcontainersDriver` and its
restricted `ContainerPlan`; unmanaged Testcontainers instances and external Docker tooling are
outside this boundary.
External troubleshooting tools, artifact access control, encryption, retention, and production
logging governance remain deployment responsibilities.

No second journal, event history, global logging framework, generic DLP engine, proof plan,
coverage validator, evidence evaluator, outcome taxonomy, or AML proof claim is introduced.
