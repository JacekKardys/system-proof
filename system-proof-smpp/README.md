# System Proof SMPP

`system-proof-smpp` observes a bounded plaintext SMPP 3.4 subset characterized for the pinned
`ukarim/smscsim` fixture and Jasmin 0.11. It frames complete PDUs, preserves their exact bytes for
the gateway, emits secret-safe evidence, and associates each `deliver_sm_resp` with one
`deliver_sm` on the same physical adapter session.

## Dependency boundary

```text
system-proof-smpp
    -> system-proof-testcontainers
    -> system-proof-core
```

PDU decoding, constant-memory TLV validation, mutable session state, outstanding request maps,
and buffers remain package-private. SMS message semantics and the reference fingerprint remain in
the examples module. Core continues to own connection and gateway-session provenance, journal
facts, proof subjects, correlation cardinality, and semantic control.

## Public contract

- `SmppProtocolAdapter` implements the gateway `ProtocolAdapter` SPI.
- `SmppProtocolLimits` bounds complete PDU size, outstanding deliveries, and `short_message` size
  within the gateway's frame and aggregate-buffer limits.
- `SmppExchangeRef` identifies one delivery exchange by adapter-session ordinal, exchange ordinal,
  and the exact non-zero uint32 wire sequence number accepted by the fixture-compatibility policy.
  It is local to one adapter instance; connection and physical gateway-session provenance are
  additionally required for native-flow composition.
- `SmppEvidence` is a closed hierarchy for bind, session-control, `deliver_sm`, and
  `deliver_sm_resp` facts. It records only commands, unsigned status/sequence values, bounded
  lengths, closed outcomes, and the exchange reference.
- `SmppDeliverCorrelation` receives an ephemeral `SmppDeliverInteraction` during one complete
  `deliver_sm` decode. Address and message access checks callback activity on every operation and
  expires on return. Only an optional digest-based `CorrelationKey` and detached
  `SmppExchangeRef` contribution can survive.

## Supported subset

- the 16-byte SMPP 3.4 common header with one complete PDU per gateway unit;
- `bind_transceiver` and `bind_transceiver_resp`, including a rejected bind;
- one-part `deliver_sm` with empty `service_type`, TON/NPI zero, printable ASCII source and
  destination addresses, `esm_class=0`, `protocol_id=0`, `priority_flag=0`, empty schedule and
  validity, default delivery/replace flags, `data_coding=8`, default message ID, a non-empty
  strictly decoded UTF-16BE `short_message`, and no optional parameters;
- `deliver_sm_resp` with the same sequence number, any unsigned 32-bit status, and the mandatory
  one-byte null `message_id` body;
- `enquire_link`/`enquire_link_resp` and `unbind`/`unbind_resp` with exact request/response
  association;
- multiple outstanding deliveries with distinct sequence numbers and responses in any order;
- wire sequence reuse only after its prior delivery exchange completes;
- clean EOF only on PDU boundaries with no outstanding exchange.

Bind is permitted once per adapter session. Deliveries and enquire-link traffic require an
accepted bind. Unbind requires no outstanding delivery or enquire-link and ends application
traffic for that session. A reconnect receives a new adapter-session ordinal.

Every other command or direction, `generic_nack`, zero sequence number, non-zero request status,
unknown response, sequence mismatch, duplicate outstanding sequence, malformed C-Octet field,
UDH/segmentation, any TLV, `message_payload`, another data coding, empty/malformed UCS2 content,
truncation, trailing bytes, exceeded limit, traffic outside the state machine, and EOF with an
outstanding exchange fail closed. Unsupported traffic emits no evidence for that PDU and is not
forwarded as unobserved bytes.

## Sequence-number compatibility

SMPP 3.4 Issue 1.2 defines `0x00000001..0x7FFFFFFF` as the legal `sequence_number`
range. Responses repeat the request value. Monotonic allocation is recommended, but it is not an
unconditional requirement for every operation.

The pinned SMSCsim instead passes `rand.Int()` to `deliverSmPDU` and writes
`uint32(seqNum)`. It does not enforce the normative range, exclude zero, or allocate monotonically;
only non-zero values were observed in the characterized runs.

The adapter therefore deliberately accepts `1..0xFFFFFFFF`, including high-bit values, as a
fixture-compatibility deviation. This is not a general SMPP compliance claim. Zero fails required
observation closed. Safety does not depend on monotonic allocation: the response must repeat the
request's exact 32 bits, matching is scoped to the same physical adapter session, duplicate
outstanding values fail closed, and completed reuse receives a new `SmppExchangeRef`.

The default limits are a 213-byte PDU, 64 outstanding deliveries, and a 140-byte
`short_message`. The hard maxima are 327 PDU bytes, 512 outstanding deliveries, and 254 message
bytes. The PDU maximum is the 16-byte header plus the largest supported 57-byte `deliver_sm`
metadata layout plus 254 message bytes; bind (at most 46 bytes), control (16 bytes), and
`deliver_sm_resp` (17 bytes) are smaller. The default PDU limit uses the same formula with the
140-byte message default.

Outstanding delivery limits use an explicit conservative accounting unit of 128 bytes for the
boxed sequence key, hash-map entry/table share, and three-long exchange reference. The default
8 KiB per-session budget yields 64 entries; the hard 64 KiB budget yields 512. This is a stable
limit-accounting rule, not a claim about one JVM's measured object layout. `ProtocolLimits`
independently bounds the complete frame and aggregate buffered bytes per direction.

Optional parameters remain unsupported. Before rejection, the decoder scans the complete TLV
suffix once: it validates every four-byte header and unsigned value length, advances over values
without copying, and retains only `sawAnyTlv` and `sawMessagePayload` bits. It creates no TLV
collection or per-TLV object. This keeps validation memory constant even for a large internal
decoder input and preserves the diagnostic distinction for `short_message` plus
`message_payload`; tags and values never enter evidence or diagnostics.

## Correlation and control

One recognized delivery may contribute:

```text
CorrelationKey -> SmppExchangeRef
```

The response carries the same reference. A subject-bound selector can therefore hold a positive
`deliver_sm_resp` with `.forSubject(subject).through(...)`. The gateway records the response and
evaluates correlation before forwarding the exact original 17-byte reference response. Missing or
ambiguous subject correlation does not select it. Release is the existing one-shot gateway permit;
there is no SMPP-specific hold mechanism or payload registry.

The reference SMS policy accepts only the characterized one-part UCS2 interaction, explicitly
copies the ephemeral semantic characters, normalizes addresses with the existing strip-and-lower
rule, and invokes the existing `SmsMessageFingerprint` key function. Focused tests prove the key
is identical to the existing HTTP callback and PostgreSQL RAW-write policies. Publishing different
native references for the same armed key remains intentionally ambiguous in the proof-subject
model; cross-connection order is not inferred from equal fingerprints.

Evidence, reference codecs, default `toString` output, journal snapshots, and framework diagnostics
never contain credentials, raw PDUs, endpoint data, addresses, message content, or optional
parameter values. Policy exceptions fail required observation closed and their messages are not
published.

The current `clean verify` suite contains 481 tests. Deterministic sequence regressions cover
`0x7FFFFFFF`, `0x80000000`, `0xFFFFFFFF`, and zero, including REQUIRED-route no-forwarding,
high-bit response matching, mismatch rejection, and exchange/evidence codec round trips.
Remaining fail-closed exclusions include all
TLVs, `message_payload`, multipart/UDH traffic, delivery receipts, other data codings, unsupported
commands or directions, TLS termination, unmatched state transitions, and traffic beyond the
configured hard limits. The live five-repetition proof demonstrates the characterized SMPP
acknowledgement only; it is not the final T1 proof.

The characterization, exact source/image pins, command/state matrix, trust boundary, and
consequences are recorded in
[`../docs/adr/0007-smpp-evidence.md`](../docs/adr/0007-smpp-evidence.md).
