package io.github.jacekkardys.systemproof.junit.internal.execution;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** A named predicate describing one fail-fast validation rule. */
record ValidationRule<T>(String description, Predicate<T> condition) {

    ValidationRule {
        Objects.requireNonNull(description, "description must not be null");
        Objects.requireNonNull(condition, "condition must not be null");
    }

    boolean isViolatedBy(T subject) {
        return !condition.test(subject);
    }

    static <T> Optional<ValidationRule<T>> firstViolation(
        T subject,
        List<ValidationRule<T>> rules
    ) {
        return rules.stream()
            .filter(rule -> rule.isViolatedBy(subject))
            .findFirst();
    }
}
