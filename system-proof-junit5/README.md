# System Proof JUnit 5

This module adapts a concrete `Environment` to the standard JUnit 5 per-test lifecycle.

Public API:

- `io.github.jacekkardys.systemproof.junit.annotation.SystemProof`: method-level test declaration,
  lifecycle integration, and facade selection. It replaces a separate JUnit `@Test` annotation.
- `io.github.jacekkardys.systemproof.junit.annotation.EnvironmentDefinition`: exactly one static
  zero-argument factory on that concrete facade.

Everything under `io.github.jacekkardys.systemproof.junit.internal` is implementation detail and is
not a supported extension API.

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

Reflection is confined to this JUnit boundary. The module has no Testcontainers dependency.

`@SystemProof` also accepts optional `title` and `description` values. They are published as
`system-proof.title` and `system-proof.description` JUnit report entries. A non-blank `title` is
also used as the individual test invocation display name shown in JUnit-compatible IDE test trees;
the Java method name is used when the title is omitted.
