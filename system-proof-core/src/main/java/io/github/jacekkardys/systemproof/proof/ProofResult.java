package io.github.jacekkardys.systemproof.proof;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** Detached deeply immutable closed result contract for one proof execution. */
public final class ProofResult {
    private static final int MAX_SECONDARY_DIAGNOSTICS = 32;
    private static final int MAX_RESOLUTIONS = 256;

    private final ProofPlanId planId;
    private final String title;
    private final ProofOutcome outcome;
    private final ProofSubjectRef primarySubject;
    private final List<ProofObligationResolution> resolutions;
    private final Optional<ProofDiagnostic> primaryFailure;
    private final List<ProofDiagnostic> secondaryDiagnostics;
    private final ProofReport report;

    public ProofResult(
        ProofPlanId planId,
        String title,
        ProofOutcome outcome,
        ProofSubjectRef primarySubject,
        List<ProofObligationResolution> resolutions,
        Optional<ProofDiagnostic> primaryFailure,
        List<ProofDiagnostic> secondaryDiagnostics
    ) {
        this.planId = Objects.requireNonNull(planId, "planId must not be null");
        this.title = ProofText.requireTitle(title);
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.primarySubject = Objects.requireNonNull(
            primarySubject,
            "primarySubject must not be null"
        );
        this.resolutions = List.copyOf(
            Objects.requireNonNull(resolutions, "resolutions must not be null")
        );
        if (this.resolutions.isEmpty() || this.resolutions.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                "resolutions must contain every required proof-plan item"
            );
        }
        if (this.resolutions.size() > MAX_RESOLUTIONS) {
            throw new IllegalArgumentException(
                "resolutions must contain at most " + MAX_RESOLUTIONS + " items"
            );
        }
        if (new HashSet<>(this.resolutions.stream()
            .map(ProofObligationResolution::id)
            .toList()).size() != this.resolutions.size()) {
            throw new IllegalArgumentException(
                "resolutions must contain each proof obligation exactly once"
            );
        }
        this.primaryFailure = Objects.requireNonNull(
            primaryFailure,
            "primaryFailure must not be null"
        );
        Objects.requireNonNull(
            secondaryDiagnostics,
            "secondaryDiagnostics must not be null"
        );
        this.secondaryDiagnostics = secondaryDiagnostics.stream()
            .limit(MAX_SECONDARY_DIAGNOSTICS)
            .map(value -> Objects.requireNonNull(
                value,
                "secondaryDiagnostics must not contain null"
            ))
            .toList();
        validateOutcome();
        report = new ProofReport(render());
    }

    public ProofPlanId planId() {
        return planId;
    }

    public String title() {
        return title;
    }

    public ProofOutcome outcome() {
        return outcome;
    }

    public ProofSubjectRef primarySubject() {
        return primarySubject;
    }

    public List<ProofObligationResolution> resolutions() {
        return resolutions;
    }

    public Optional<ProofDiagnostic> primaryFailure() {
        return primaryFailure;
    }

    public List<ProofDiagnostic> secondaryDiagnostics() {
        return secondaryDiagnostics;
    }

    public List<ProofObligationResolution> unresolved() {
        return resolutions.stream()
            .filter(value -> value.resolution() != ProofResolution.SATISFIED)
            .toList();
    }

    public Optional<ProofObligationResolution> decisiveResolution() {
        return switch (outcome) {
            case PROVED -> Optional.empty();
            case VIOLATED -> resolutions.stream()
                .filter(value -> value.resolution() == ProofResolution.VIOLATED)
                .findFirst();
            case ERROR -> resolutions.stream()
                .filter(value -> value.resolution() == ProofResolution.FAILED)
                .findFirst();
            case INCONCLUSIVE -> resolutions.stream()
                .filter(value -> value.resolution() != ProofResolution.SATISFIED)
                .findFirst();
        };
    }

    public ProofReport report() {
        return report;
    }

    /** Returns this result when the expected outcome matches, otherwise throws a safe assertion. */
    public ProofResult require(ProofOutcome expected) {
        expected = Objects.requireNonNull(expected, "expected must not be null");
        if (outcome != expected) {
            throw new AssertionError(
                "Expected proof outcome " + expected + " but was " + outcome
                    + System.lineSeparator() + report.content()
            );
        }
        return this;
    }

    @Override
    public String toString() {
        return "ProofResult[planId=" + planId + ", titleLength=" + title.length()
            + ", outcome=" + outcome + ", primarySubject=opaque, resolutions="
            + resolutions.size() + ", secondaryDiagnostics="
            + secondaryDiagnostics.size() + "]";
    }

    private void validateOutcome() {
        boolean allSatisfied = resolutions.stream()
            .allMatch(value -> value.resolution() == ProofResolution.SATISFIED);
        if (outcome == ProofOutcome.PROVED && !allSatisfied) {
            throw new IllegalArgumentException(
                "PROVED requires every prerequisite and obligation to be SATISFIED"
            );
        }
        if (outcome == ProofOutcome.VIOLATED
            && resolutions.stream().noneMatch(
                value -> value.resolution() == ProofResolution.VIOLATED
            )) {
            throw new IllegalArgumentException(
                "VIOLATED requires an explicit violated obligation"
            );
        }
        if (outcome == ProofOutcome.ERROR && primaryFailure.isEmpty()
            && resolutions.stream().noneMatch(
                value -> value.resolution() == ProofResolution.FAILED
            )) {
            throw new IllegalArgumentException(
                "ERROR requires a safe primary failure or failed obligation"
            );
        }
        if (outcome != ProofOutcome.ERROR && primaryFailure.isPresent()) {
            throw new IllegalArgumentException(
                "Only ERROR may contain a primary framework failure"
            );
        }
        if (outcome != ProofOutcome.VIOLATED && outcome != ProofOutcome.ERROR
            && resolutions.stream().anyMatch(
                value -> value.resolution() == ProofResolution.NOT_EVALUATED
            )) {
            throw new IllegalArgumentException(
                "NOT_EVALUATED is permitted only after terminal VIOLATED or ERROR"
            );
        }
    }

    private String render() {
        String lineSeparator = "\n";
        StringBuilder output = new StringBuilder();
        output.append("proof plan=").append(planId)
            .append(" title=").append(title)
            .append(" outcome=").append(outcome)
            .append(" subject=opaque")
            .append(lineSeparator);
        for (ProofObligationResolution resolution : resolutions) {
            output.append(resolution.kind()).append(' ')
                .append(resolution.id()).append(' ')
                .append(resolution.resolution()).append(' ')
                .append(resolution.reason());
            resolution.connectionId().ifPresent(value ->
                output.append(" connection=").append(value)
            );
            if (!resolution.interactions().isEmpty()) {
                output.append(" interactions=")
                    .append(resolution.interactions().stream()
                        .map(Object::toString)
                        .collect(Collectors.joining(",")));
            }
            output.append(lineSeparator);
        }
        decisiveResolution().ifPresentOrElse(
            value -> output.append("decisive=").append(value.kind()).append('/')
                .append(value.id()).append('/').append(value.reason())
                .append(lineSeparator),
            () -> output.append("decisive=")
                .append(outcome == ProofOutcome.PROVED
                    ? "all-required-items-satisfied"
                    : primaryFailure.map(value ->
                        value.stage() + "/" + value.failure().failureType()
                    ).orElse("none"))
                .append(lineSeparator)
        );
        primaryFailure.ifPresent(value -> output.append("failure=")
            .append(value.stage()).append('/').append(value.failure().failureType())
            .append(lineSeparator));
        for (ProofDiagnostic diagnostic : secondaryDiagnostics) {
            output.append("secondary=").append(diagnostic.stage()).append('/')
                .append(diagnostic.failure().failureType()).append(lineSeparator);
        }
        return output.toString();
    }
}
