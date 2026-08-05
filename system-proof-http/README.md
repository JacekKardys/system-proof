# System Proof HTTP

`system-proof-http` observes a bounded plaintext HTTP/1.1 subset characterized for the Jasmin
0.11 SMS callback. It frames complete requests and responses, preserves their exact bytes for the
gateway, emits secret-safe evidence, and associates each response with one request on the same
physical adapter session.

## Dependency boundary

```text
system-proof-http
    -> system-proof-testcontainers
    -> system-proof-core
```

HTTP framing and mutable session state remain package-private. SMS form semantics and the
reference message fingerprint remain in the examples module. Core continues to own connection and
gateway-session provenance, journal facts, proof subjects, correlation cardinality, and semantic
control.

## Public contract

- `HttpProtocolAdapter` implements the gateway `ProtocolAdapter` SPI.
- `HttpProtocolLimits` bounds the start line, combined start-line/header section, header count, and
  body within the gateway's frame and aggregate-buffer limits.
- `HttpExchangeRef` identifies one request/response pair by adapter-session ordinal and request
  ordinal. The value is local to one adapter instance. Gateway connection and physical-session
  provenance is additionally required for native-flow composition.
- `HttpEvidence.RequestCompleted` records the exchange reference, an allowlisted method category,
  a request-target SHA-256 digest and byte count, optional content type, and body byte count. It
  never records the raw target, headers, or body bytes.
- `HttpEvidence.ResponseCompleted` records the exchange reference, status, conservative
  acknowledgement classification, and body byte count. It never records response bytes.
- `HttpRequestCorrelation` receives an ephemeral `HttpRequestInteraction` only while one complete
  request is decoded. Its scoped body accessor checks activity on every indexed read or copy and
  expires on callback return; only an optional digest-based `CorrelationKey` and detached
  `HttpExchangeRef` contribution can survive.

## Supported subset

- plaintext HTTP/1.1 request and status lines;
- query-free origin-form request targets;
- one non-blank `Host` field;
- case-insensitive field names and a singular parameter-free `Content-Type` media type;
- `Content-Length` body framing;
- sequential request/response exchanges on keep-alive sessions;
- exact request/response association on one physical adapter session;
- response statuses from 200 onward, including bodyless 204 and 304 responses when their
  `Content-Length` is absent or zero.

Acknowledgement is intentionally tri-state. Exact status `200` plus exactly the ten ASCII bytes
`ACK/Jasmin` is `POSITIVE`. A sub-400 status with a UTF-8 body accepted only after Jasmin's
`content.strip()` behavior, including `201`/`299` with the exact body or `200` with surrounding
whitespace, is `INDETERMINATE`. Statuses at least 400, an empty or wrong body, and invalid text are
`NEGATIVE`. Truncated or malformed responses emit no response evidence and fail closed.

TLS, `CONNECT`, `HEAD`, `Upgrade`, `Expect`, transfer codings, close-delimited responses,
informational responses, general pipelining, malformed or conflicting lengths, ambiguous
request/response association, and premature EOF fail closed without positive evidence. Unsupported
traffic is a gateway observation failure, not an `UNKNOWN` evidence value.

Default HTTP limits are an 8 KiB start line, 32 KiB combined start-line/header section, 100
headers, and a 1 MiB body. Hard maxima are 16 KiB, 64 KiB, 1024 fields, and 16 MiB respectively;
the evidence codec derives its bound from the same maxima. `ProtocolLimits` independently bounds
the complete frame and aggregate directional buffer. The adapter does not own sockets or retain a
second payload registry.

## Correlation and control

One recognized request may contribute:

```text
CorrelationKey -> HttpExchangeRef
```

The matching response carries the same `HttpExchangeRef`. A subject-bound selector can therefore
select a positive response with `.forSubject(subject).through(...)`. The core registry and gateway
also require the contribution and candidate to share the exact logical connection and physical
gateway session. Equal local references from another adapter, route, connection, or session do not
compose. Missing or ambiguous subject correlation does not select a response.

The complete response remains the existing gateway control unit:

```text
frame -> record -> correlate -> permit -> forward exact original bytes once
```

No HTTP-specific hold mechanism exists. This module does not implement SMPP evidence,
cross-connection predecessor guards, or the final T1 verdict.

The characterization, source pins, trust boundary, and consequences are recorded in
[`../docs/adr/0006-http-callback-evidence.md`](../docs/adr/0006-http-callback-evidence.md).
