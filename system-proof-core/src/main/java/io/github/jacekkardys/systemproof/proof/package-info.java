/**
 * Defines environment-scoped proof subjects, protocol-neutral correlation, frozen proof plans,
 * and detached fail-closed results.
 *
 * <p>The package contains public immutable contracts that depend on observation values. Adapter
 * publication capabilities and all linearizable mutable registries remain owned by one
 * environment execution. A plan contains only bounded typed declarations; it owns no adapter,
 * predicate, payload, throwable, or event history.
 */
package io.github.jacekkardys.systemproof.proof;
