/**
 * Defines the supported component-driver extension SPI.
 *
 * <p>Drivers materialize one component runtime, publish typed provider bindings, contribute
 * component-scoped facts, and return optional operations and classified diagnostics. Driver log
 * text requires bounded redaction. Diagnostic suppliers are explicitly redacted, sensitive, or
 * unsupported for export; sensitive and unsupported suppliers are excluded from default capture.
 * The SPI depends only on core contracts and never on JUnit or Testcontainers.
 */
package io.github.jacekkardys.systemproof.driver;
