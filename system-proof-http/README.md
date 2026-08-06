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
  a request-target SHA-256 digest and byte count, a closed `ABSENT` / `FORM_URLENCODED` / `OTHER`
  content-type category, and body byte count. It never records the raw target, `Content-Type`,
  other headers, or body bytes.
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
- case-insensitive field names and a singular parameter-free request `Content-Type` media type;
- `Content-Length` body framing;
- sequential request/response exchanges on keep-alive sessions, with case-insensitive
  `Connection` token parsing;
- `Connection: close` on either side as the last exchange on that session;
- exact request/response association on one physical adapter session;
- response statuses `200`-`299` and `400`-`599`, including bodyless 204 responses when their
  `Content-Length` is absent or zero;
- response text with no `Content-Type` decoded as ISO-8859-1, or `text/plain` decoded as
  ISO-8859-1 unless its sole parameter is `charset=UTF-8`;
- responses without `Content-Encoding` and without a redirect hop.

Acknowledgement is intentionally tri-state and follows the supported subset of Jasmin 0.11 with
treq 23.11.0. The response body is decoded first using the supported `Content-Type` rule. Exact
status `200` plus decoded text exactly equal to `ACK/Jasmin` is `POSITIVE`. A sub-300 status
accepted only after Jasmin's Python `str.strip()` behavior, including `201`/`299` with the exact
text or `200` with surrounding whitespace, is `INDETERMINATE`. A supported status at least 400,
an empty body, or rejected decoded text is `NEGATIVE`.

Jasmin's actual client follows redirects by default and applies gzip content decoding before
`text_content(response)`. This adapter deliberately does not partially emulate either operation:
every 3xx response, every `Content-Encoding`, every response media type outside `text/plain`, and
every charset outside the two rules above fails closed without response evidence. Truncated,
malformed, or invalidly encoded responses fail the same way.

TLS, `CONNECT`, `HEAD`, `Upgrade`, `Expect`, transfer codings, close-delimited responses,
informational responses, redirects, content codings, other response charsets or media types,
general pipelining, malformed or conflicting lengths, ambiguous request/response association, and
premature EOF fail closed without positive evidence. After either side declares `Connection:
close`, a later request fails before it can become a decoded, recorded, or forwarded unit; this
does not depend on observing physical EOF. Unsupported traffic is a gateway observation failure,
not an `UNKNOWN` evidence value.

Default HTTP limits are an 8 KiB start line, 32 KiB combined start-line/header section, 100
headers, and a 1 MiB body. Hard maxima are 16 KiB, 64 KiB, 1024 fields, and 16 MiB respectively;
the secret-safe request evidence has a fixed-size encoding and validates its numeric fields against
the same legal maxima. `ProtocolLimits` independently bounds the complete frame and aggregate
directional buffer. The adapter does not own sockets or retain a second payload registry.

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
