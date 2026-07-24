# System Proof JUnit 5

This module adapts a concrete `Environment` to the standard JUnit 5 per-test lifecycle.

Public API:

- `@EnvironmentTest(environment = ...)`: class-level lifecycle integration and facade selection.
- `@EnvironmentDefinition`: exactly one static zero-argument factory on that concrete facade.

The definition method must return the declared facade type. Missing or duplicate definitions,
instance methods, parameters, mismatched return types, abstract facades, and `null` results fail
before the first runtime is started.

For each test method, `EnvironmentTestExtension`:

1. locates and validates the facade definition;
2. invokes its static factory;
3. starts exactly the returned object;
4. injects that same concrete object into a matching test parameter;
5. captures retained diagnostics before cleanup when the test fails;
6. closes it after success or failure and writes the captured failure artifact.

Reflection is confined to this JUnit boundary. The module has no Testcontainers dependency.
