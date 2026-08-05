# ADR 0006: HTTP callback acknowledgement evidence

- Status: Accepted
- Date: 2026-08-05
- Issue: [#9](https://github.com/JacekKardys/system-proof/issues/9)

## Context and sources

System Proof needs typed evidence for the real Jasmin-to-ingestion callback before it can compose
the callback acknowledgement with other protocol facts. The adapter must not infer success from
an arbitrary HTTP 2xx response, retain callback contents in evidence, or weaken required
observation when framing is uncertain.

The characterized client is pinned as
`jookies/jasmin:0.11.0@sha256:3f049692d22fd66ab08a55073f79db96fe442473ede9615e8ac085ac505a1064`.
Its OCI labels identify version `0.11.0`, source repository `jookies/jasmin`, and source revision
[`8455c1b875d5f22069759e8fbefcb7437c47db4b`](https://github.com/jookies/jasmin/tree/8455c1b875d5f22069759e8fbefcb7437c47db4b),
the `0.11.0` release merge. This is the reproducible image-to-source mapping used by the example.
The pinned `deliverSm` thrower source:

- constructs mandatory `id`, `from`, `to`, `origin-connector`, `content`, and hex `binary` form
  fields, with optional `priority`, `coding`, and `validity` fields;
- sends POST data as `application/x-www-form-urlencoded` through the HTTP client;
- treats status codes at least 400 as errors and otherwise compares the stripped response text with
  `ACK/Jasmin`.

See the pinned
[`throwers.py`](https://github.com/jookies/jasmin/blob/8455c1b875d5f22069759e8fbefcb7437c47db4b/jasmin/routing/throwers.py#L253-L339).
The versioned Jasmin HTTP API documentation states the narrower contract: the endpoint must return
HTTP `200 OK` and `ACK/Jasmin`, otherwise Jasmin retries. See the pinned
[`HTTP API documentation source`](https://github.com/jookies/jasmin/blob/8455c1b875d5f22069759e8fbefcb7437c47db4b/misc/doc/sources/apis/http/index.rst).

The implementation source and documentation disagree about whether other sub-400 statuses and
surrounding response whitespace are accepted. The adapter preserves that distinction: their exact
intersection is positive, source-only acceptance is indeterminate, and rejection is negative.

## Sanitized observed flow

The containerized reference topology was executed through a required-observation gateway route:

```text
SMSC -> Jasmin 0.11 -> InteractionGateway -> ingestion -> PostgreSQL

request:  POST /v1/ingestion/sms HTTP/1.1
          Host
          Content-Type: application/x-www-form-urlencoded
          Content-Length
          bounded form body

response: HTTP/1.1 200
          Content-Length: 10
          ACK/Jasmin
```

Thirteen callbacks were observed in one focused run: three for ambiguous-correlation rejection,
five unrelated callbacks, and five subject-correlated callbacks. Jasmin opened a separate physical
HTTP session for each observed callback. Every request used the path and media type above; every
reference response was status 200 with a ten-byte body and was accepted by Jasmin. The adapter also
supports sequential keep-alive exchanges because request/response association remains unambiguous,
but it does not claim that the observed Jasmin run reused a connection.

The characterization records only protocol shape and lengths. Callback values, message contents,
generated IDs, addresses, raw headers, endpoints, and frames are omitted.

## Decision

Add `system-proof-http` with dependency direction:

```text
system-proof-http -> system-proof-testcontainers -> system-proof-core
```

The module owns HTTP/1.1 framing, one synchronized request/response session model,
`HttpExchangeRef`, typed HTTP evidence, acknowledgement classification, and an ephemeral request
correlation SPI. It does not add HTTP, Jasmin, or SMS concepts to core.

One `HttpExchangeRef` contains an adapter-session ordinal and request ordinal. A complete request
allocates the reference; the corresponding complete response consumes the pending request and
carries the same reference. Reconnect allocates a new adapter-session ordinal. Equal local values
from separate adapters are possible and are not global identity. The authoritative observation
identity remains the journal-owned `InteractionRef`, whose `SessionId` contains the logical
`ConnectionId`. Native-flow composition additionally requires the request contribution and
response candidate to share that logical connection and exact physical gateway session.

The evidence hierarchy is closed and immutable:

- `RequestCompleted`: exchange reference, allowlisted method category, irreversible target
  SHA-256 plus byte count, content-type presence/value, and body byte count;
- `ResponseCompleted`: exchange reference, status code, tri-state acknowledgement, and body byte
  count.

Evidence contains no body, form values, arbitrary headers, raw bytes, socket data, endpoint, or
duplicated connection/session identity. Unsupported or undecidable traffic emits no evidence and
fails required observation closed; it is not represented as an inconclusive positive candidate.

`POSITIVE` requires both exact status `200` and exact body bytes `ACK/Jasmin`. `INDETERMINATE`
captures a complete sub-400 response that Jasmin accepts after strict UTF-8 decoding and Python
`str.strip()` whitespace handling but which is outside that exact intersection: for example,
`201`/`299` plus the exact body or `200` plus surrounding whitespace. Statuses at least 400 and a
complete empty, wrong, case-changed, prefixed, or otherwise rejected body are `NEGATIVE`.
Malformed or truncated framing emits no response evidence and fails required observation closed.

## Framing and limits

The supported plaintext HTTP/1.1 subset uses CRLF start/header lines, visible ASCII metadata,
case-insensitive header names, query-free origin-form targets, one `Host`, an optional singular parameter-free
`Content-Type` media type, and `Content-Length` framing. A request without `Content-Length` has no body. A normal response
requires `Content-Length`; body-forbidden 204/304 responses permit an absent or zero length.

Sequential keep-alive is supported. Request-input and response-input EOF are tracked independently.
After response EOF no request may begin. Request EOF may leave one already-pending exchange, but
only its single response may complete; response EOF with a pending request is desynchronization.
General pipelining is rejected because more than one pending
request makes response association ambiguous. A response without a pending request also fails
closed. A request and response share identity only in the same adapter session. EOF is clean only
on a unit boundary and when no response is pending.

TLS, `CONNECT`, `HEAD`, `Upgrade`, `Expect`, transfer codings including chunked, close-delimited
responses, informational responses, obsolete header folding, malformed start/header lines,
conflicting or invalid lengths, unsupported pipelining, premature EOF, and missing association fail
closed. These restrictions are intentional; the adapter does not continue transparently through
unknown syntax.

`HttpProtocolLimits` defaults to an 8 KiB start line, 32 KiB combined start-line/header section,
100 fields, and a 1 MiB body. Its hard maxima are 16 KiB, 64 KiB, 1024 fields, and 16 MiB. Limit
validation uses overflow-safe arithmetic, and the evidence decoder derives its maximum encoding
from the same header maximum so every evidence value emitted under a legal configuration can
round-trip. Gateway `ProtocolLimits` separately bounds complete frames and the
aggregate buffered bytes per direction. The gateway retains exact original bytes only until the
forwarding decision and write. The adapter emits the exact current frame prefix and never
re-encodes it from evidence.

## Reference correlation policy

The examples module remains the owner of Jasmin form and SMS semantics. Its policy accepts only:

- `POST /v1/ingestion/sms`;
- exactly `application/x-www-form-urlencoded`;
- the pinned known field set with every mandatory field present once;
- strict percent/plus decoding without duplicate or unknown fields;
- content whose UTF-8 bytes equal the supplied hex `binary` field for default coding;
- UCS2 coding 8 decoded from the supplied hexadecimal binary as UTF-16BE.

Unsupported coding, malformed percent or hexadecimal encoding, inconsistent text/binary values,
missing/duplicate/unknown fields, or invalid character encoding contributes no key. Source and
destination addresses use the existing strip-and-lowercase normalization. Content is preserved as
decoded text. The policy invokes the existing `SmsMessageFingerprint` key function and schema; no
parallel HTTP fingerprint exists.

The callback view is read-only and valid only during the synchronous correlation call. Body access
is scoped and indexed; every size, byte, or copy operation checks that the callback is still active.
Neither the interaction nor a retained body accessor can expose a surviving alias to the
framework-owned request body after return. A trusted policy can deliberately copy values while it
is active: this is an API ownership guarantee, not a JVM sandbox. The policy must be pure, bounded,
fast, non-blocking, and side-effect free. The view is invalidated in a `finally` block. Only the safe
digest key and detached exchange reference survive. Full form data, addresses, content, IDs, raw
bytes, and response bodies never enter correlation diagnostics, evidence, or the journal.

## Consequences and trust boundary

The adapter always declares its evidence schema and `SEMANTIC_CONTROL` because every supported
response is a complete forwarding unit. It declares the `HttpExchangeRef` native-flow schema and
`CORRELATION_CONTRIBUTIONS` only when a correlation policy is configured. It declares neither
encrypted transport nor general pipelining.

Typed evidence, `EvidenceSnapshot`, default `toString` values, and `JournalRenderer` output omit
raw targets, bodies, form values, endpoints, and policy exception messages. The Jasmin bootstrap
also avoids `httpccm -l` and emits a bounded safe configuration summary rather than the callback
URL. This boundary covers framework-owned evidence and default environment diagnostics; arbitrary
SUT/container log lines are external input and are not claimed to be secret-sanitized.

The focused real integration uses REQUIRED observation, verifies the route is routed and active,
checks one matching request and response per target exchange, and repeats subject-bound positive
response hold/release five times without sleeps. Each response is recorded before the first
forwarded byte and released through the existing one-shot gateway permit. Unrelated missing-key
traffic and deliberately ambiguous same-fingerprint traffic continue without satisfying the
subject-bound hold.

This result proves a typed positive HTTP callback acknowledgement for the characterized exchange.
It does not prove the cross-connection order between PostgreSQL commit, HTTP acknowledgement, and
SMPP acknowledgement. It adds no SMPP decoding, predecessor guard, application verdict, retry
proof, TLS termination, fault mutation, or final T1 claim.
