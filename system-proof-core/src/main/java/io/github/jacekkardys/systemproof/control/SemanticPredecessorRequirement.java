package io.github.jacekkardys.systemproof.control;

import java.util.Objects;

/** Immutable selector and boundary for one semantic predecessor requirement. */
public final class SemanticPredecessorRequirement<T> {
    private final SemanticInteractionSelector<T> selector;
    private final SemanticPredecessorBoundary boundary;

    private SemanticPredecessorRequirement(
        SemanticInteractionSelector<T> selector,
        SemanticPredecessorBoundary boundary
    ) {
        this.selector = Objects.requireNonNull(selector, "selector must not be null");
        this.boundary = Objects.requireNonNull(boundary, "boundary must not be null");
    }

    public static <T> SemanticPredecessorRequirement<T> confirmed(
        SemanticInteractionSelector<T> selector
    ) {
        return new SemanticPredecessorRequirement<>(
            selector,
            SemanticPredecessorBoundary.CONFIRMED
        );
    }

    public static <T> SemanticPredecessorRequirement<T> forwarded(
        SemanticInteractionSelector<T> selector
    ) {
        return new SemanticPredecessorRequirement<>(
            selector,
            SemanticPredecessorBoundary.FORWARDED
        );
    }

    public SemanticInteractionSelector<T> selector() {
        return selector;
    }

    public SemanticPredecessorBoundary boundary() {
        return boundary;
    }

    @Override
    public String toString() {
        return "SemanticPredecessorRequirement[selector=" + selector
            + ", boundary=" + boundary + "]";
    }
}
