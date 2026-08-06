# ADR 0007: SMPP delivery acknowledgement evidence

- Status: Accepted
- Date: 2026-08-06
- Issue: [#10](https://github.com/JacekKardys/system-proof/issues/10)

## Context and sources

System Proof needs typed evidence for the real SMSC-to-Jasmin `deliver_sm` exchange before a later
proof layer can compose the SMPP acknowledgement with PostgreSQL and HTTP facts. The adapter must
not treat a TCP write or simulator log as acknowledgement, retain message material in evidence, or
continue transparently when PDU framing or request/response association is uncertain.

The provider fixture is built from [`ukarim/smscsim` commit
`4975a569f7be11a89f9c381494f42ccf55fd49d3`](https://github.com/ukarim/smscsim/tree/4975a569f7be11a89f9c381494f42ccf55fd49d3).
The repository-owned patch changes only the invalid seven-character `deliver_sm.service_type` to
the SMPP default empty C-Octet String. The pinned source sends MO content as UCS2 in
`short_message` and uses `esm_class=0` for a one-part message. For `deliver_sm`, it passes
[`rand.Int()`](https://github.com/ukarim/smscsim/blob/4975a569f7be11a89f9c381494f42ccf55fd49d3/smsc.go#L135)
to `deliverSmPDU` and writes the result as
[`uint32(seqNum)`](https://github.com/ukarim/smscsim/blob/4975a569f7be11a89f9c381494f42ccf55fd49d3/smsc.go#L380-L385).
It does not constrain that result to the SMPP sequence range, reject zero, or generate sequences
monotonically. The characterized runs observed non-zero values, but the source does not guarantee
them. It does not validate response semantics deeply, so its log is not proof evidence.

The consumer image is
`jookies/jasmin:0.11.0@sha256:3f049692d22fd66ab08a55073f79db96fe442473ede9615e8ac085ac505a1064`.
Its OCI labels map it to [`jookies/jasmin` revision
`8455c1b875d5f22069759e8fbefcb7437c47db4b`](https://github.com/jookies/jasmin/tree/8455c1b875d5f22069759e8fbefcb7437c47db4b).
The image contains Jasmin 0.11.0, `smpp.pdu3` 0.6, and `smpp.twisted3` 0.8. That stack emits a
`deliver_sm_resp` whose sequence echoes the request and whose mandatory `message_id` is the empty
one-byte null C-Octet String.

The protocol baseline is the [SMPP 3.4 Issue 1.2
specification](https://smpp.org/SMPP_v3_4_Issue1_2.pdf): the common header is 16 bytes;
`command_length`, `command_id`, `command_status`, and `sequence_number` are unsigned 32-bit values;
the legal `sequence_number` range is `0x00000001..0x7FFFFFFF`; a response repeats the request's
number; and monotonically increasing allocation is recommended rather than an unconditional rule
for every operation. `short_message` and `message_payload` cannot both carry the user data.

## Sanitized observed flow

The exact pinned containers were executed through a required-observation gateway route. One
sanitized characterization observed:

```text
Jasmin -> SMSCsim  bind_transceiver       38 bytes, status 0, sequence S
SMSCsim -> Jasmin  bind_transceiver_resp  24 bytes, status 0, sequence S
SMSCsim -> Jasmin  deliver_sm             complete PDU, status 0, sequence D
Jasmin -> SMSCsim  deliver_sm_resp         17 bytes, status 0, sequence D
Jasmin -> SMSCsim  enquire_link            16 bytes, status 0, sequence E
SMSCsim -> Jasmin  enquire_link_resp       16 bytes, status 0, sequence E
```

The sampled `deliver_sm` was a one-part PDU with empty service type, TON/NPI zero, `esm_class=0`,
`data_coding=8`, 122 `short_message` bytes, and no optional parameter. Its complete byte count was
172 for that message; this is content-dependent and not a fixed protocol value. Shutdown closed
the TCP directions on clean unit boundaries without an observed unbind exchange.

The observed bind, delivery, response, and enquire-link sequences were non-zero. This is an
observation of those runs, not a claim that the pinned SMSCsim generator enforces non-zero or
normative SMPP values.

The characterization deliberately omits credentials, addresses, content, raw PDUs, endpoints,
ports, generated identifiers, and concrete random sequence values.

## Decision

Add `system-proof-smpp` with dependency direction:

```text
system-proof-smpp -> system-proof-testcontainers -> system-proof-core
```

The module owns common-header framing, the characterized body decoder, one synchronized session
model, `SmppExchangeRef`, typed evidence, limits, and an ephemeral delivery-correlation SPI. It
does not add SMPP, Jasmin, or SMS concepts to core and does not change the gateway SPI.

One exchange reference contains adapter-session ordinal, exchange ordinal, and unsigned wire
sequence number. `deliver_sm` allocates it; the matching `deliver_sm_resp` consumes the outstanding
mapping and carries the same value. The exchange ordinal distinguishes legal wire-sequence reuse.
Reconnect allocates a new adapter-session ordinal. The authoritative observation identity remains
the journal-owned `InteractionRef`, including logical connection and physical gateway session.

### Sequence-number compatibility policy

The adapter deliberately accepts the full non-zero uint32 range `1..0xFFFFFFFF`. Values above the
normative SMPP 3.4 maximum `0x7FFFFFFF` are a compatibility deviation for the pinned SMSCsim, not a
claim that the wider range is generally SMPP-compliant. Zero remains invalid and fails required
observation closed.

Correlation remains safe under this compatibility policy because a response must repeat the
request's exact 32 wire bits, matching occurs within the same physical adapter session, a duplicate
outstanding sequence terminates that session fail-closed, and reuse after completion allocates a
new `SmppExchangeRef` with a new exchange ordinal.

The evidence hierarchy is closed and immutable:

- `BindRequested`: sequence and complete PDU byte count;
- `BindResponded`: sequence, unsigned status, and accepted/rejected outcome;
- `SessionControl`: allowlisted enquire-link or unbind command, sequence, status, and byte count;
- `DeliverSmCompleted`: exchange reference, PDU/body/message lengths, closed UCS2 coding, and
  `esm_class`;
- `DeliverSmResponseCompleted`: exchange reference, unsigned status, positive/negative
  acknowledgement, and byte count.

Status zero is positive for `deliver_sm_resp`; every non-zero unsigned 32-bit value is negative.
The adapter first validates exact response framing and session association. Malformed or unmatched
responses emit no evidence rather than becoming negative evidence.

## Command and state matrix

| State | Consumer to provider | Provider to consumer | Result |
| --- | --- | --- | --- |
| `OPEN` | `bind_transceiver` | none | one matching bind becomes pending |
| `BIND_PENDING` | none | `bind_transceiver_resp` | accepted -> `BOUND`; rejected -> `UNBOUND` |
| `BOUND` | `deliver_sm_resp`, one pending `enquire_link`, `unbind` | `deliver_sm`, matching `enquire_link_resp` | exact association required |
| `UNBIND_PENDING` | none | matching `unbind_resp` | `UNBOUND` |
| `UNBOUND` | none | none | only clean shutdown is accepted |
| any terminal/failing state | none | none | subsequent input fails closed |

Multiple deliveries may be outstanding and responses may arrive in a different order. An
outstanding sequence cannot be reused. Enquire-link and unbind each allow only the exact modeled
request/response pair; unbind cannot start while another exchange is pending. Every request has
status zero and a non-zero sequence. A clean EOF requires no pending bind, delivery, enquire-link,
or unbind and no partial PDU; input after either direction ended is rejected.

## Framing, supported body, and limits

The framer reads the unsigned `command_length`, requires at least the 16-byte common header,
checks both SMPP and gateway frame limits before converting to an indexed Java size, waits for the
complete PDU, and returns exactly that byte prefix. Coalesced PDUs become separate gateway units;
fragmented input becomes one unit only after completion. Truncated EOF, lengths above limits,
unsigned lengths above Java addressability, and trailing body bytes fail closed.

The supported `deliver_sm` is deliberately exact: empty service type; TON/NPI zero; non-empty
printable ASCII source and destination; `esm_class`, protocol, priority, registered-delivery,
replace, and default-message fields zero; empty schedule and validity; coding 8; one non-empty
strict UTF-16BE `short_message`; and no TLV. UDH/segmentation, any optional parameter,
`message_payload`, other coding, empty content, malformed UCS2, and the ambiguous combination of
`short_message` plus `message_payload` fail closed.

The optional-parameter suffix is validated with a single constant-memory scan before rejection.
Each iteration requires a complete four-byte header, reads the unsigned value length, checks that
length against the remaining body before advancing, and skips the value without copying it. The
scanner retains only bounded `sawAnyTlv` and `sawMessagePayload` flags: it creates no collection or
per-TLV object and never publishes a tag or value. Full structural validation precedes the
unsupported/ambiguous classification.

The response body is exactly the mandatory empty `message_id` C-Octet String: one null byte. Bind
fields are bounded C-Octet strings with interface version 3.4 and the characterized TON/NPI values.
Enquire-link and unbind bodies are empty. Every other command, direction, generic nack, state
transition, or association is unsupported.

The largest supported PDU is `deliver_sm`. Its maximum metadata is 57 bytes: one empty
`service_type` terminator, four TON/NPI bytes, two 21-byte address C-Octet fields, three bytes for
ESM/protocol/priority, two empty schedule/validity terminators, and five delivery/coding/message
length bytes. Therefore the hard PDU maximum is `16 + 57 + 254 = 327` bytes. The 140-byte default
message limit gives the default PDU maximum `16 + 57 + 140 = 213` bytes. Other supported maxima
are smaller: 46 bytes for `bind_transceiver`, 32 for an accepted `bind_transceiver_resp`, 17 for
`deliver_sm_resp`, and 16 for session control.

The outstanding map uses a conservative 128-byte accounting unit covering the boxed sequence
key, hash-map node/table share, and three-long exchange reference. An 8 KiB default per-session
budget permits 64 entries; the 64 KiB hard budget permits 512. This is a stable policy accounting
unit, not a claim about one JVM's measured object layout. Gateway `ProtocolLimits` separately
bounds the frame and aggregate directional buffer.

Public evidence constructors and codec decode validate command-specific PDU sizes and all
cross-field invariants. In particular, delivery evidence requires `esm_class=0`, UCS2, a positive
even message byte count, and mutually consistent PDU, body, supported-field, and message lengths;
session controls require status zero and 16 bytes; delivery responses require exactly 17 bytes.
Fixed-width codec mutation tests supplement corrupted, truncated, and trailing-byte cases.

## Reference correlation policy

The examples module remains the owner of SMS semantics. Its policy accepts only `esm_class=0` and
`data_coding=8`, explicitly copies source, destination, and message characters while the callback
is active, rejects blank addresses or empty content, applies the existing address normalization,
and invokes the existing `SmsMessageFingerprint` key function and schema. No SMPP-specific
fingerprint exists.

The view and all retained character accessors expire in a `finally` block. Backing character
arrays are cleared before release. A trusted policy can deliberately copy values during the call;
this is an ownership boundary, not a JVM sandbox. The policy must be pure, bounded, fast,
non-blocking, and side-effect free. A null result or exception fails required observation closed.

Focused tests construct the exact semantic SMPP, HTTP callback, and PostgreSQL RAW-write
representations and prove that all three policies yield the same target fingerprint. The live SMPP
test publishes only the SMPP reference for an armed subject: publishing multiple different native
references for one key would correctly change subject cardinality to `AMBIGUOUS`, not prove order.

## Consequences and trust boundary

The adapter declares correlation contributions only when a policy is configured. Semantic control
is always available for complete supported PDUs. Evidence, codecs, snapshots, default `toString`
values, journal rendering, and framework diagnostics omit credentials, raw PDUs, endpoint data,
addresses, content, and TLV values. Policy exception messages are suppressed from those artifacts.
External SUT/container logs remain untrusted input and are outside that sanitization claim.

The real integration uses REQUIRED observation and verifies the SMPP route is routed and active.
It exercises the exact SMSCsim -> gateway -> Jasmin -> ingestion flow, proves one request/response
exchange with the same reference and wire sequence, holds the recorded positive response before
forwarding, releases it once, checks unrelated traffic does not satisfy the hold, and repeats the
target proof five times. Separate traffic proves same-fingerprint ambiguity does not select a
response. A throwing policy proves required observation fails closed without publishing delivery
evidence, forwarding the rejected SMS into RAW/Outbox persistence, or exposing its secret message.

The current `clean verify` suite contains 481 tests. Deterministic boundary coverage includes the
normative maximum, both uint32 high-bit boundaries, zero request/response failures on a REQUIRED
route, exact exchange/evidence codec round trips, and mismatched high-bit responses. Remaining
fail-closed exclusions include TLVs,
`message_payload`, multipart/UDH traffic, delivery receipts, other coding, unsupported commands and
directions, TLS termination, unmatched state transitions, and inputs beyond configured limits.

This result proves a typed positive SMPP acknowledgement for the characterized exchange. It does
not prove cross-connection predecessor order, retry semantics, multipart SMS, delivery receipts,
general SMPP interoperability, TLS termination, fault mutation, or the final T1 verdict.
