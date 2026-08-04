package io.github.jacekkardys.systemproof.control;

import java.time.Duration;

/** Environment-scoped facade for one-shot semantic traffic controls. */
public interface SemanticControls {
    /**
     * Arms one selector before its stimulus.
     *
     * <p>The maximum duration starts when the selected interaction reaches the held state, not
     * while the selector is merely armed.
     */
    <T> SemanticHold arm(
        SemanticHoldSelector<T> selector,
        Duration maximumHoldDuration
    );
}
