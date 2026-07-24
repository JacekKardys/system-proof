# AML SMS system test

`SmsIngestionSmokeIT` is the Docker-backed happy-path scenario. Its `@EnvironmentTest` declaration
selects `AmlSmsEnvironment`, whose static `@EnvironmentDefinition` creates one real facade per test.
One `ComponentFactory` owns the system-configuration snapshot and binds the annotated component
and runtime configuration contracts. AML components, drivers, operations, and Jasmin bootstrap
code are deliberately local to this module's test sources.

The tested path is:

```text
SMSC simulator
  -> SMPP deliver_sm
  -> Jasmin 0.11
  -> POST /v1/ingestion/sms
  -> AML Ingestion Service
  -> PostgreSQL transaction
       raw_sms_event + outbox_event
```

The scenario sends one SMS, waits for one correlated `deliver_sm_resp`, and verifies one RAW row,
one correlated Outbox row, matching aggregate ID, and persisted message fields.

The scenario does not assert temporal ordering between SMPP acknowledgement and the database
commit. The T1 acknowledgement invariant is deferred; a future test requires an explicit supported
contract and a deterministic observation boundary.

Default images:

- `postgres:17.6-alpine`
- `rabbitmq:4.1.2-management-alpine`
- `redis:8.0.3-alpine`
- `aml-ingestion-service:local`
- `aml-smsc-simulator:local`
- `jookies/jasmin:0.11.0`

Image overrides: `AML_POSTGRES_IMAGE`, `AML_RABBITMQ_IMAGE`, `AML_REDIS_IMAGE`,
`AML_INGESTION_IMAGE`, `AML_SMSC_SIMULATOR_IMAGE`, and `AML_JASMIN_IMAGE`.

Run unit tests with `./mvnw clean test` and the Docker-backed scenario with
`./mvnw clean verify`.
