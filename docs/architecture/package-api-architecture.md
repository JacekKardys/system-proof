# Package and API architecture

- Status: Canonical
- Scope: framework modules before 1.0
- Issue: [#41](https://github.com/JacekKardys/system-proof/issues/41)

## Decision

System Proof has no generic `model` namespace. A type's package identifies its domain owner, not
its Java shape, lifecycle phase, or visibility. A type is classified independently as supported
API, supported extension SPI, inspectable read-only model, or internal implementation.

The classification rule is:

> Put a type with the domain whose invariant gives it meaning and whose change would require that
> type to change. Keep lifecycle, resources, and mutable orchestration with that domain's internal
> owner. Java `public` does not by itself create compatibility support.

The removed umbrella could not satisfy one enforceable rule: it mixed lifecycle facades, mutable
declarations, execution exceptions, logging configuration, endpoint values, and detached runtime
snapshots. Domain-first packages make those differences explicit.

## Compatibility categories

| Category | Contract |
| --- | --- |
| Supported public API | Scenario authors may declare or invoke it. Its documented behavior is reviewed for compatibility. |
| Supported extension SPI | Driver, gateway, protocol, or framework adapters may implement or call it. Generic bounds and callback behavior are part of the contract. |
| Inspectable read-only model | Callers may retain and inspect detached immutable values. It owns no lifecycle, resources, provider endpoint lookup, or mutation capability. |
| Internal implementation | No compatibility support. It may be Java-public only where Java or JUnit requires cross-package or reflective access. |

Before 1.0, supported API and SPI may still change when the replacement is materially clearer.
Such a change is intentional, documented, and made without deprecated wrappers, aliases, or
duplicate models. Java-public internal types may change at any time.

## Current package map

| Package | Owner and responsibility |
| --- | --- |
| `communication` | Declarative communication annotations and built-in protocol semantics. |
| `component` | Component declarations, identities, and lifecycle values. |
| `configuration` | Component and driver configuration contracts, providers, validation, and redacted secrets. |
| `diagnostics` | Logging configuration, immutable diagnostics, and stateless journal rendering. |
| `driver` | Supported component-driver extension SPI. |
| `endpoint` | Immutable endpoint addresses, bindings, and protocol-specific endpoint values. |
| `environment` | Environment facade, validated assembly, routing SPI, and package-private execution. |
| `environment.state` | Detached immutable environment and runtime-connection state. |
| `journal` | Closed event vocabulary plus immutable entries and snapshots; never storage. |
| `observation` | Observation policy/status, interaction identity, evidence, and forwarding decisions. |
| `proof` | Proof-subject and correlation contracts over observation values. |
| `topology` | Contracts, ports, protocol/interaction declarations, and logical connections. |
| `junit.annotation` | Supported JUnit declaration annotations. |
| `junit.internal` | Unsupported JUnit lifecycle implementation. |
| `testcontainers.component` | Container-backed driver plans and runtime materialization. |
| `testcontainers.diagnostics` | Container log adapters. |
| `testcontainers.gateway` | Protocol adapters and the observe-before-forward gateway. |

Package-private types in `environment` own every mutable construction and execution concern:
component state and handles, connection bindings and routes, proof-subject allocation, journal
storage, redaction state, SLF4J emission, and cleanup accumulation. Public read models own none of
those concerns.

## Dependency direction

```text
examples -> junit.annotation ---------------------> core domains
        `-> testcontainers.component/gateway ----> core domains

environment -> diagnostics -> journal
environment -> driver / component / configuration / endpoint / topology
environment -> proof -> observation -> topology
journal ----> component / environment.state / topology / observation / proof
```

The following directions are forbidden and executable tests enforce them:

- `observation -> proof`;
- `configuration -> component`, `driver`, or environment execution;
- `endpoint -> driver` or environment execution;
- `journal` or `diagnostics` -> mutable environment execution types;
- journal storage -> diagnostics, logging configuration, or SLF4J;
- core -> JUnit or Testcontainers;
- driver SPI -> Testcontainers;
- public API -> provider endpoint lookup or proof-subject allocation;
- JUnit public compatibility support -> `junit.internal`.

The deliberate component/driver generic relationship is part of one typed extension contract.
Neither side depends on Testcontainers. `RuntimeEndpointBindings` is the single technical transfer
bridge used to keep provider endpoint lookup inside environment execution.

## Supported core public API whitelist

The whitelist is type-specific. Nested types shown here are included; no package is supported as a
whole.

- Communication declarations: `Communication` and nested `Amqp`, `Http`, `JdbcPostgresql`,
  `Redis`, `Smpp`, and `Tcp` annotations.
- Component declarations: `Component`, `SystemComponent`.
- Configuration: `ConfigurationSource`, `EnvironmentConfiguration`, `EnvironmentVariable`,
  `Literal`, `Secret`.
- Diagnostics configuration: `EnvironmentLogging`, `EnvironmentLoggingBuilder`, `LogLevel`.
- Environment API: `Environment`, `EnvironmentBuilder`, `EnvironmentCreator`,
  `EnvironmentTopology`, `ComponentPortFactory`, `ConnectionRouting`,
  `EnvironmentStartException`, `ComponentLifecycleException`.
- Proof access: `ProofSubjects`.
- Topology declarations: `Connection`, `Contract`, `DeclaredInteraction`, `DeclaredProtocol`,
  `InteractionSpec`, `ProtocolSpec`, `Port`, `PortContract`, `ProvidedPort`, `RequiredPort`, and
  `StartupPrerequisite`.

## Supported core extension SPI whitelist

- Component/configuration declaration SPI: `AbstractComponent`, `RuntimeConfig`, `DriverConfig`,
  `ComponentConfig`, `ConfigurationProvider`.
- Driver SPI: `ComponentDriver`, `ComponentBoundDriver`, `ComponentRuntime` and its `Builder`,
  `DriverContext`, `DriverResourceKey`, `DiagnosticSource`, `JournalContributions`.
- Routing/session SPI: `ConnectionObservations`, `ConnectionRoute`, `ConnectionRouteContext`,
  `ConnectionRouteProvider`, `CorrelationContribution`, `InteractionSession`,
  `ObservationStatusProvider`.
- Observation SPI: `EvidenceCodec`, `InteractionDecisionCoordinator`.

Route selection, preparation, consumer-target access, observation-status extraction, and route
cleanup are not SPI. They remain package-private execution mechanics.

## Inspectable core read-only model whitelist

- Component values: `ComponentId`, `ComponentType`, `ComponentState`.
- Endpoint values: `EndpointAddress`, `EndpointBinding`, `AmqpEndpoint`, `JdbcEndpoint`,
  `RedisEndpoint`, `SmppEndpoint`.
- Environment state: `EnvironmentState`, `ConnectionState`, `RoutingMode`,
  `RuntimeConnectionSnapshot`.
- Diagnostics: `EnvironmentDiagnostics`, `JournalRenderer`.
- Journal: `ScenarioEvent`, `FailureEvent`, every permitted event record and nested event enum,
  `FailureDetails`, `JournalEntry`, `JournalSequence`, `ScenarioJournalSnapshot`, `CheckpointId`,
  and `DisruptionId`.
- Observation: `ObservationRequirement`, `EffectiveObservationStatus`, `EvidenceSchemaId`,
  `EvidenceSnapshot`, `FlowDirection`, `ForwardingDecision`, `SessionId`, `InteractionRef`.
- Proof: `ProofSubjectRef`, `CorrelationKeySchema`, `CorrelationKey`, `CorrelationCardinality`,
  `CorrelationResult` and nested `Missing`, `Unique`, and `Ambiguous` results.
- Topology inspection: `CompatibilityResult`, `ConnectionDescriptor`, `ConnectionId`,
  `ConnectionRef`, `PortDirection`, `PortRef`.

Record canonical constructors are supported only where callers legitimately create declarations or
detached values. Records and value classes use value equality. `AbstractComponent`, `Component`,
environment facades, executions, runtimes, routes, and resources retain instance identity.

## Java-public internal exceptions

Only these core types remain Java-public without compatibility support:

| Type/member | Why Java-public | Guarded restriction |
| --- | --- | --- |
| `ConfigurationBinder` | Environment assembly crosses into the configuration owner. | Static `bind` only; no public constructor. |
| `ConfigurationValidator` | Environment assembly validates bound configuration. | Static `validate` only; no public constructor. |
| `ConfigurationValues` | Configuration records share fail-fast value checks. | `requireNonNull` and `requireText` only; no public constructor. |
| `RuntimeEndpointBindings` | `ComponentRuntime` transfers driver-published bindings across the driver/environment boundary. | No public constructor or lookup; public `publish` only. |
| `AbstractComponent.driver()` and `castOperations(...)` | Typed execution needs the declaration's driver and operations class without raw casts. | Exact method surface is pinned; neither exposes mutable execution state. |
| `ComponentRuntime.publishBindingsTo(...)` | Transfers already driver-owned bindings into the non-constructible environment boundary. | No environment/runtime lookup path is exposed. |

`EnvironmentRuntime`, its factory, assembly, lifecycle, inspector, component supervisor, connection
registry, proof registry, journal store, redactor, emitter, and failure accumulator are
package-private. `EnvironmentTopology.runtimeComponents()` is package-private; public topology
inspection returns only `List<Component>` and logical connections.

## JUnit whitelist

Supported API consists only of `SystemProof` and `EnvironmentDefinition`.

`EnvironmentLifecycleExtension`, `EnvironmentParameterResolver`, and
`SystemProofInvocationProvider` are Java-public only because `SystemProof` names them in
`@ExtendWith` and JUnit constructs them reflectively. Their exact constructors and callback methods
are guarded, but they are not supported SPI. All resolver, validator, reporter, shared-context,
running-environment, metadata, and failure-adapter collaborators are package-private in
`junit.internal`.

## Testcontainers whitelist

- Component API/SPI: `ContainerDriver` and nested factories, `ContainerPlan` and `Builder`,
  `PortBinding`, `RuntimeEndpointFactory`, `StartedContainer`, `TestcontainersDriver`.
- Diagnostics: `ContainerLogConsumer`.
- Gateway API/SPI/read model: `InteractionGateway`, `ProtocolAdapter`,
  `ProtocolAdapterException`, `ProtocolDecodeResult` and nested results, `ProtocolFailureKind`,
  `ProtocolLimits`, `ProtocolSession`, `ProtocolStream`, `ProtocolUnit`, `TcpEndpointAdapter` and
  `AddressReplacement`.

The Testcontainers surface depends on core contracts. Core and driver SPI never depend back on it.

## Inventory ownership matrix

Types grouped in one row share the listed properties. Every externally visible framework type is
named by the three whitelists above or by the Java-public internal table.

| Types | Created by / consumed by | Mutation, lifecycle, resources | Equality and construction | Secret and `toString` policy | Reason to change |
| --- | --- | --- | --- | --- | --- |
| Communication annotations | Scenario component declarations / environment port discovery | None | Annotation values | Protocol IDs only | Communication declaration semantics |
| Component declarations and markers | Scenario authors and environment assembly / drivers and topology | Declaration initialization ends before execution; no runtime handles | Components use instance identity; IDs/types use value equality | No endpoint values or secrets | Component declaration contract |
| Configuration API and SPI | Scenario/environment sources / component and driver binders | Immutable snapshots; no resources | Provider/value semantics as documented | `Secret.toString()` is always redacted; no generated secret equality/toString | Configuration contract |
| Configuration Java-public internals | Environment assembly / configuration implementation | Stateless | No public constructors | Error text names fields, not secret values | Binding or validation implementation |
| Diagnostics and logging | Scenario authors and inspector / users, JUnit, SLF4J emitter | Immutable configuration/read results; renderer is stateless | Value/configuration semantics | Rendering uses frozen redacted journal details | Diagnostic or logging policy |
| Driver SPI | Adapter authors / environment component supervisor | `ComponentRuntime` may own one closeable resource; environment closes it | Runtime and resource keys use identity where ownership requires it | Diagnostics are explicit suppliers; no endpoint rendering | Component runtime extension contract |
| Endpoint values | Drivers / environment connection materialization | Immutable; no owned resources | Value equality | Passwords use `Secret`; endpoint values never appear in public runtime snapshots | Endpoint contract |
| Environment API | Scenario authors / JUnit and examples | `Environment` owns exactly one execution; lifecycle methods are final | Facades use identity; topology snapshots use structural/value views | No provider endpoint lookup; exceptions use sanitized facts | Environment lifecycle or assembly contract |
| Routing/session SPI | Gateway/Testcontainers / environment connection execution | Route resources are connection-owned and closed internally | Route/session objects use execution identity; contribution metadata is detached | No public consumer-target getter or raw evidence rendering | Routing or observation extension contract |
| Environment state read models | Inspector / users, journal, diagnostics | Detached immutable; no resources or mutation | Value equality and defensive lists | Endpoint availability booleans only | Inspectable lifecycle state |
| Journal vocabulary and read models | Environment publisher/store / inspector, renderer, users | Immutable; storage is separate and package-private | Value equality; snapshots defensively copy | Failures are redacted before append; evidence bytes are defensive copies | Auditable fact vocabulary |
| Observation contracts | Gateway/codecs / journal, proof, environment | Immutable except execution-owned session implementation | Structural identities and defensive evidence values | Evidence `toString` never emits bytes | Observation policy, evidence, or forwarding semantics |
| Proof contracts | Environment registry / scenario users and journal | Public facade exposes correlation, not allocation; registry is internal | Opaque subject identity; keys/results use value semantics | Digests/native references are not rendered as secrets | Proof-subject or correlation semantics |
| Topology contracts | Scenario/environment assembly / drivers and execution | Immutable after validated assembly | Connections/components preserve declared identity; descriptors/IDs use value equality | No runtime endpoint values | Logical topology semantics |
| JUnit annotations | Test authors / JUnit extensions | None | Annotation values | Metadata only | JUnit declaration contract |
| JUnit internal extensions | `@ExtendWith`/JUnit reflection / JUnit callbacks | Per-test shared context and lifecycle only | Internal identity | Diagnostics use environment read models | JUnit lifecycle implementation |
| Testcontainers API/SPI | Adapter authors and examples / environment routing and drivers | Container/route resources have explicit owners | Plans and protocol results use documented value/identity semantics | Gateway diagnostics exclude hosts, mapped ports, credentials, and raw frames | Container or protocol adapter contract |

## Sealed hierarchy policy

`ScenarioEvent`, `FailureEvent`, `CorrelationResult`, `ProtocolDecodeResult`, and `ConnectionRef` are
core-controlled sealed hierarchies. Their permitted implementations are inspectable read models,
not user extension points. Before 1.0, adding a new permitted core fact/result is allowed but is an
explicit source-compatibility change for exhaustive pattern switches. Storage, mutation, and
publication do not become public when the vocabulary grows.

## Placement examples

- A new logical port identity belongs in `topology`.
- A detached connection status belongs in `environment.state`.
- A driver callback or runtime result belongs in `driver`.
- A gateway observation decision belongs in `observation`; a mutable coordinator implementation
  remains internal to `environment` or Testcontainers.
- A new stored fact belongs in `journal`; its publisher and mutable storage remain in `environment`.
- A text representation over an immutable snapshot belongs in `diagnostics`.
- A lifecycle exception created by environment execution belongs in `environment`, not in a value
  package.

## Enforcement

`CoreArchitectureTest` walks every class file recursively, including nested `$` classes. It compares
all public/protected types with the four exact whitelists, pins technical method/constructor surface,
checks private runtime construction, forbids public route mechanics/provider lookup/proof allocation,
verifies one journal storage owner, and checks dependency direction. `Junit5ModuleBoundaryTest` and
`TestcontainersPublicSurfaceTest` guard their module surfaces and dependency boundaries. The examples
module compiles against the supported imports and rejects internal/removed package usage.
