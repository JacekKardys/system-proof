# Adapted ukarim/smscsim fixture

This directory contains only the reproducible build inputs needed to adapt
[`ukarim/smscsim`](https://github.com/ukarim/smscsim) for the System Proof example.

- Upstream commit: `4975a569f7be11a89f9c381494f42ccf55fd49d3`
- License: MIT
- Patch: `patches/0001-empty-deliver-sm-service-type.patch`
- Contract test: `service_type_test.go`

The Docker build fetches the exact commit, applies the patch, verifies that generated `deliver_sm`
PDUs contain an empty and correctly terminated `service_type`, and builds the upstream program.
The patch replaces the invalid `smscsim` value with the SMPP default: an empty C-Octet String.

Full attribution and runtime limitations are documented in
[`docs/third-party.md`](../../../docs/third-party.md).
