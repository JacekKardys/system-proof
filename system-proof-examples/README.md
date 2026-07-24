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

Default images:

- `postgres:17.6-alpine`
- `rabbitmq:4.1.2-management-alpine`
- `redis:8.0.3-alpine`
- `system-proof-ingestion-service:local`
- `aml-smsc-simulator:local` (external simulator image)
- `jookies/jasmin:0.11.0`

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

Their defaults match the current external ingestion image contract (`AML_DB_URL`,
`AML_DB_USERNAME`, and `AML_DB_PASSWORD`). The overrides allow another service image to run
unchanged.

## Running

Run unit tests without Docker:

```bash
./mvnw clean test
```

Run both examples with Docker:

```bash
./mvnw clean verify
```
