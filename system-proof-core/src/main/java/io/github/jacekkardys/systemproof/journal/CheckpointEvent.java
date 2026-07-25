package io.github.jacekkardys.systemproof.journal;

/**
 * Category for immutable semantic checkpoint or barrier events.
 *
 * <p>Checkpoint semantics and happens-before relationships are intentionally not defined here.
 */
public non-sealed interface CheckpointEvent extends ScenarioEvent {
}
