# System Proof Examples

This module contains two executable examples of System Proof's public API.

## PostgreSQL example

`PostgresExampleIT` defines an environment with one PostgreSQL component, starts it through the
Testcontainers adapter, injects the environment through JUnit 5, and verifies behavior through
typed database operations.

The example uses `postgres:17.6-alpine` by default. Override it with
`SYSTEM_PROOF_EXAMPLE_POSTGRES_IMAGE`.

## Complete SMS ingestion example

`SmsIngestionSmokeIT` retains the complete multi-component system scenario as a baseline smoke
test:

```text
SMSC simulator
  -> SMPP deliver_sm
  -> Jasmin 0.11
  -> POST /v1/ingestion/sms
  -> SMS ingestion service
  -> PostgreSQL transaction
       raw_sms_event + outbox_event
```

Its environment contains the SMSC simulator, Jasmin, ingestion service, PostgreSQL, RabbitMQ, and
Redis components together with their drivers, configuration, bootstrap, operations, and typed
connections.

The scenario sends one SMS and verifies one correlated `deliver_sm_resp`, one RAW row, one
correlated Outbox row, matching aggregate ID, persisted message fields, session identity, sequence
numbers, command status, and SMSC-local delivery-before-response ordering.

This is not proof of T1. It does not establish ordering between the PostgreSQL commit and the SMPP
acknowledgement. Its waits, timestamps, and SMSC-local event indexes cannot prove cross-component
causality. The accepted T1 evidence and success boundary are defined in
[`docs/adr/0001-t1-proof-contract.md`](../docs/adr/0001-t1-proof-contract.md).

Default dependency images:

- `postgres:17.6-alpine`
- `rabbitmq:4.1.2-management-alpine`
- `redis:8.0.3-alpine`
- `jookies/jasmin:0.11.0`

The reference applications live under `apps/`:

- `system-proof-ingestion-service`: Spring Boot HTTP ingress with Flyway-managed
  `raw_sms_event` and `outbox_event` tables written in one transaction.
- `system-proof-smsc-simulator`: jSMPP server plus the minimal HTTP control API used by the smoke.

During the root reactor's `verify` phase, their JARs are packaged first and the drivers build
`system-proof-ingestion-service:local` and `system-proof-smsc-simulator:local` directly from those
artifacts. A clean checkout therefore does not need either image in a registry or local Docker
cache. Explicit image overrides still use the supplied image without rebuilding it.

Image overrides:

- `SYSTEM_PROOF_EXAMPLE_POSTGRES_IMAGE`
- `SYSTEM_PROOF_EXAMPLE_RABBITMQ_IMAGE`
- `SYSTEM_PROOF_EXAMPLE_REDIS_IMAGE`
- `SYSTEM_PROOF_EXAMPLE_INGESTION_IMAGE`
- `SYSTEM_PROOF_EXAMPLE_SMSC_SIMULATOR_IMAGE`
- `SYSTEM_PROOF_EXAMPLE_JASMIN_IMAGE`

The ingestion container's database environment-variable names are configurable through:

- `SYSTEM_PROOF_EXAMPLE_INGESTION_DATABASE_URL_VARIABLE`
- `SYSTEM_PROOF_EXAMPLE_INGESTION_DATABASE_USERNAME_VARIABLE`
- `SYSTEM_PROOF_EXAMPLE_INGESTION_DATABASE_PASSWORD_VARIABLE`

Their defaults are `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD`. The overrides
allow another service image with a different environment contract to run unchanged.

## Running

Run unit tests without Docker:

```bash
./mvnw clean test
```

Run both examples with Docker:

```bash
./mvnw clean verify
```

The reference SMSC intentionally implements only the proof fixture's needs: one active receiver or
transceiver session, bind authentication, `deliver_sm`, correlated `deliver_sm_resp` journaling,
and the `/health`, `/test/messages`, and `/test/events` endpoints. It is not a general-purpose SMSC.
