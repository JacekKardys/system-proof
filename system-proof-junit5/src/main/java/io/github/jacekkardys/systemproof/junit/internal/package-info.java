/**
 * Contains unsupported JUnit lifecycle implementation.
 *
 * <p>The three extension classes referenced by {@code @SystemProof} are Java-public only because
 * JUnit constructs them reflectively. All collaborators and state holders are package-private.
 * None of these types belongs to the supported compatibility surface.
 * Default failure artifacts contain only environment safe diagnostics. The extension has no raw
 * or sensitive attachment path.
 */
package io.github.jacekkardys.systemproof.junit.internal;
