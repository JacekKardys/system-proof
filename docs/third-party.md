# Third-party software

## ukarim/smscsim

- Upstream: <https://github.com/ukarim/smscsim>
- License: MIT
- Pinned commit: [`4975a569f7be11a89f9c381494f42ccf55fd49d3`](https://github.com/ukarim/smscsim/commit/4975a569f7be11a89f9c381494f42ccf55fd49d3)
- Build definition: [`system-proof-examples/fixtures/ukarim-smscsim/Dockerfile`](../system-proof-examples/fixtures/ukarim-smscsim/Dockerfile)
- Compatibility patch: [`0001-empty-deliver-sm-service-type.patch`](../system-proof-examples/fixtures/ukarim-smscsim/patches/0001-empty-deliver-sm-service-type.patch)

System Proof does not maintain an independent SMSC implementation. The test fixture image is built
from the pinned upstream commit during Maven verification. The build checks and applies the
separate compatibility patch, runs a PDU-level contract test, and compiles the upstream Go
application. The upstream license is copied into `/licenses/ukarim-smscsim/LICENSE` in the
resulting local image, `system-proof-ukarim-smscsim:local`.

The patch changes only `deliver_sm.service_type`. Upstream encodes the seven-character value
`smscsim`, but SMPP 3.4 defines this field as a C-Octet String with a maximum size of six bytes
including its null terminator. Jasmin therefore rejects the upstream PDU with
`ESME_RINVSERTYP`. The adaptation emits a single null byte, selecting the SMSC default service.

The fixture inherits upstream's intentionally small SMPP 3.4 subset. It does not validate incoming
PDUs. Its web control request completes after sending `deliver_sm`; it does not wait for or expose
a correlated `deliver_sm_resp`. The simulator log that a response arrived is diagnostic only.
Future protocol evidence, including session, sequence number, and command status, belongs to later
protocol-adapter roadmap work. The current `InteractionGateway` proves transparent TCP transport
and lifecycle only.
