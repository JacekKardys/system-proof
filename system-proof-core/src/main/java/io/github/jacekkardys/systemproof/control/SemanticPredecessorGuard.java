package io.github.jacekkardys.systemproof.control;

import java.util.concurrent.CompletionStage;

/** One environment-owned, one-shot semantic predecessor guard handle. */
public interface SemanticPredecessorGuard {
    SemanticPredecessorGuardRef ref();

    SemanticPredecessorGuardState state();

    /** Completes with the first terminal lifecycle state after its journal fact is appended. */
    CompletionStage<SemanticPredecessorGuardState> completion();

    /** Cancels the guard if cancellation wins before successor authorization. */
    boolean cancel();
}
