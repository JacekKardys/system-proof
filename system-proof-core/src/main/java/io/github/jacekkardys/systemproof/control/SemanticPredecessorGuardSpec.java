package io.github.jacekkardys.systemproof.control;

import java.time.Duration;
import java.util.Objects;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;

/** Immutable declaration of one subject-scoped semantic predecessor obligation. */
public final class SemanticPredecessorGuardSpec {
    private final ProofSubjectRef subject;
    private final SemanticPredecessorRequirement<?> predecessor;
    private final SemanticInteractionSelector<?> successor;
    private final Duration maximumDuration;

    private SemanticPredecessorGuardSpec(
        ProofSubjectRef subject,
        SemanticPredecessorRequirement<?> predecessor,
        SemanticInteractionSelector<?> successor,
        Duration maximumDuration
    ) {
        this.subject = Objects.requireNonNull(subject, "subject must not be null");
        this.predecessor = Objects.requireNonNull(
            predecessor,
            "predecessor must not be null"
        );
        this.successor = Objects.requireNonNull(successor, "successor must not be null");
        this.maximumDuration = requirePositive(maximumDuration);
        requireSubject("predecessor", predecessor.selector(), subject);
        requireSubject("successor", successor, subject);
    }

    public static SemanticPredecessorGuardSpec requiring(
        ProofSubjectRef subject,
        SemanticPredecessorRequirement<?> predecessor,
        SemanticInteractionSelector<?> successor,
        Duration maximumDuration
    ) {
        return new SemanticPredecessorGuardSpec(
            subject,
            predecessor,
            successor,
            maximumDuration
        );
    }

    public ProofSubjectRef subject() {
        return subject;
    }

    public SemanticPredecessorRequirement<?> predecessor() {
        return predecessor;
    }

    public SemanticInteractionSelector<?> successor() {
        return successor;
    }

    public Duration maximumDuration() {
        return maximumDuration;
    }

    @Override
    public String toString() {
        return "SemanticPredecessorGuardSpec[subject=opaque"
            + ", predecessor=" + predecessor
            + ", successor=" + successor
            + ", maximumDuration=" + maximumDuration + "]";
    }

    private static void requireSubject(
        String role,
        SemanticInteractionSelector<?> selector,
        ProofSubjectRef expected
    ) {
        if (!selector.proofSubject().filter(expected::equals).isPresent()) {
            throw new IllegalArgumentException(
                "The " + role + " selector must target the guard proof subject"
            );
        }
    }

    private static Duration requirePositive(Duration duration) {
        duration = Objects.requireNonNull(duration, "maximumDuration must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("maximumDuration must be positive");
        }
        return duration;
    }
}
