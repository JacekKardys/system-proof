# System Proof JUnit 5

This module adapts a concrete `Environment` to the standard JUnit 5 per-test lifecycle.

Public API:

- `io.github.jacekkardys.systemproof.junit.annotation.SystemProof`: method-level test declaration,
  lifecycle integration, and facade selection. It replaces a separate JUnit `@Test` annotation.
- `io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition`: exactly one static
  zero-argument factory on that concrete facade.

Everything under `io.github.jacekkardys.systemproof.junit.internal` is implementation detail and is
not a supported extension API. The three Java-public classes in that package are named by
`@SystemProof` and must be reflectively constructible by JUnit. All invocation, validation,
diagnostics, metadata, shared-state, and failure collaborators are package-private.

Validation is split by contract rather than by JUnit callback:

- `EnvironmentDefinitionLocator` only discovers methods carrying `@EnvironmentDefinition`;
- `EnvironmentDefinitionValidator` validates the concrete facade and its
  discovered factory contract without selecting or invoking a method;
- `EnvironmentParameterValidator` validates that test and per-test lifecycle methods declare at
  most one `Environment` parameter and that it has the exact configured facade type.

Both validators use the same package-private named-rule model. `EnvironmentDefinitionResolver`
invokes only a
validated definition, prepares reflective access, and adapts invocation failures; JUnit SPI
callbacks remain orchestration-only.

The definition method must return the declared facade type. Missing or duplicate definitions,
instance methods, parameters, mismatched return types, abstract facades, and `null` results fail
before the first runtime is started.

For each `@SystemProof` method, the System Proof JUnit extensions:

1. locates and validates the facade definition;
2. invokes its static factory;
3. starts exactly the returned object;
4. injects that same concrete object into matching test, `@BeforeEach`, and `@AfterEach`
   parameters;
5. captures retained diagnostics before cleanup when the test fails;
6. closes it after success or failure and writes the captured failure artifact.

`@SystemProof` is a complete JUnit test-template declaration. It owns exactly one invocation and
does not need `@Test`:

```java
@SystemProof(ExampleEnvironment.class)
void verifiesBehavior(ExampleEnvironment environment) {
    // The environment is running here.
}
```

Combining `@SystemProof` with another direct or meta-annotated `@TestTemplate` declaration is not
supported. This includes `@ParameterizedTest`, `@RepeatedTest`, direct `@TestTemplate`, and custom
test-template annotations. The combination fails with an `ExtensionConfigurationException` before
JUnit creates an invocation and before System Proof creates or starts an environment:

```java
@SystemProof(ExampleEnvironment.class)
@ParameterizedTest
@ValueSource(strings = {"first", "second"})
void verifiesBehavior(String input, ExampleEnvironment environment) {
    // Unsupported: two annotations define test-template invocations.
}
```

Parameterized System Proof tests are deliberately outside the current contract. For different
input values, define separate `@SystemProof` methods. This fail-fast rule is a stabilization
boundary, not an incidental limitation of provider registration order.

Reflection is confined to this JUnit boundary. The module has no Testcontainers dependency.

`@SystemProof` also accepts optional `title` and `description` values. They are published as
`system-proof.title` and `system-proof.description` JUnit report entries. A non-blank `title` is
also used as the individual test invocation display name shown in JUnit-compatible IDE test trees;
the Java method name is used when the title is omitted.
