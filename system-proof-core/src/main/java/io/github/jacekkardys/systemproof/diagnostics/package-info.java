/**
 * Defines logging configuration, immutable rendered diagnostics, and stateless journal rendering.
 *
 * <p>Renderers consume detached journal entries and snapshots, never mutable storage. They own no
 * event history and have no append path. Full-history rendering is linear in total generated output
 * size and component filtering uses structured identities rather than rendered labels.
 */
package io.github.jacekkardys.systemproof.diagnostics;
