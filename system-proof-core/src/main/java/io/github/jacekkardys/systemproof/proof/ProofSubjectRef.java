package io.github.jacekkardys.systemproof.proof;

/**
 * Opaque identity of one scenario-selected operation in one environment execution.
 *
 * <p>Only the environment runtime can allocate references. The ownership token and numeric
 * identity are intentionally not exposed.
 */
public interface ProofSubjectRef {}
