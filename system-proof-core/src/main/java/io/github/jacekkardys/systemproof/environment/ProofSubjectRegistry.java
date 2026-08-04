package io.github.jacekkardys.systemproof.environment;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import io.github.jacekkardys.systemproof.proof.CorrelationCardinality;
import io.github.jacekkardys.systemproof.proof.CorrelationKey;
import io.github.jacekkardys.systemproof.proof.CorrelationResult;
import io.github.jacekkardys.systemproof.proof.ProofSubjectRef;
import io.github.jacekkardys.systemproof.proof.ProofSubjects;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSnapshot;
import io.github.jacekkardys.systemproof.observation.InteractionRef;

/** Environment-owned linearizable subject registry and current-state journal index. */
final class ProofSubjectRegistry implements ProofSubjects {
    private static final long FIRST_SUBJECT_VALUE = 1L;

    private final Object owner = new Object();
    private final EnvironmentEventPublisher events;
    private final Map<ProofSubjectRef, SubjectState> subjects = new HashMap<>();
    private final Map<CorrelationKey, Set<ProofSubjectRef>> subjectsByKey =
        new HashMap<>();
    private long nextSubjectValue = FIRST_SUBJECT_VALUE;
    private boolean acceptingPublications = true;

    ProofSubjectRegistry(EnvironmentEventPublisher events) {
        this.events = Objects.requireNonNull(events, "events must not be null");
    }

    @Override
    public synchronized ProofSubjectRef create() {
        requireAccepting("create proof subjects");
        ProofSubjectRef subject = createReference();
        events.proofSubjectCreated(subject);
        subjects.put(subject, new SubjectState());
        return subject;
    }

    @Override
    public synchronized void arm(ProofSubjectRef subject, CorrelationKey key) {
        requireAccepting("arm proof subjects");
        SubjectState subjectState = requireSubject(subject);
        key = Objects.requireNonNull(key, "key must not be null");
        if (subjectState.resolutions.containsKey(key)) {
            return;
        }

        Set<ProofSubjectRef> existingSubjects = subjectsByKey.get(key);
        boolean sharedKey = existingSubjects != null && !existingSubjects.isEmpty();
        events.proofSubjectArmed(subject, key, sharedKey);

        Resolution initial = sharedKey ? Ambiguous.INSTANCE : Missing.INSTANCE;
        subjectState.resolutions.put(key, initial);
        if (sharedKey) {
            for (ProofSubjectRef existing : existingSubjects) {
                requireSubject(existing).resolutions.put(key, Ambiguous.INSTANCE);
            }
        }
        subjectsByKey.computeIfAbsent(key, ignored -> new HashSet<>())
            .add(subject);
    }

    @Override
    public <T> CorrelationResult<T> correlation(
        ProofSubjectRef subject,
        CorrelationKey key,
        EvidenceCodec<T> nativeReferenceCodec
    ) {
        Resolution resolution;
        synchronized (this) {
            SubjectState subjectState = requireSubject(subject);
            key = Objects.requireNonNull(key, "key must not be null");
            nativeReferenceCodec = Objects.requireNonNull(
                nativeReferenceCodec,
                "nativeReferenceCodec must not be null"
            );
            resolution = subjectState.resolutions.get(key);
            if (resolution == null) {
                throw new IllegalArgumentException(
                    "Correlation key schema '" + key.schema()
                        + "' is not armed for proof subject '" + subject + "'"
                );
            }
        }

        return switch (resolution) {
            case Missing missing -> new CorrelationResult.Missing<>();
            case Ambiguous ambiguous -> new CorrelationResult.Ambiguous<>();
            case Unique unique -> new CorrelationResult.Unique<>(
                unique.interactionRef,
                unique.nativeReference.schemaId(),
                unique.nativeReference.decode(nativeReferenceCodec)
            );
        };
    }

    synchronized void publish(
        InteractionRef interactionRef,
        CorrelationContribution<?> contribution
    ) {
        requireAccepting("publish correlation candidates");
        interactionRef = Objects.requireNonNull(
            interactionRef,
            "interactionRef must not be null"
        );
        contribution = Objects.requireNonNull(
            contribution,
            "contribution must not be null"
        );
        CorrelationKey key = contribution.key();
        EvidenceSnapshot nativeReference = contribution.nativeReferenceSnapshot();
        Set<ProofSubjectRef> armedSubjects =
            subjectsByKey.getOrDefault(key, Set.of());

        if (armedSubjects.isEmpty()) {
            events.correlationCandidate(
                Optional.empty(),
                key,
                interactionRef,
                nativeReference,
                CorrelationCardinality.MISSING
            );
            return;
        }
        if (armedSubjects.size() > 1) {
            events.correlationCandidate(
                Optional.empty(),
                key,
                interactionRef,
                nativeReference,
                CorrelationCardinality.AMBIGUOUS
            );
            return;
        }

        ProofSubjectRef subject = armedSubjects.iterator().next();
        SubjectState subjectState = requireSubject(subject);
        Resolution current = Objects.requireNonNull(
            subjectState.resolutions.get(key),
            "Armed proof subject has no correlation resolution"
        );
        if (current instanceof Unique unique
            && unique.sameCandidate(interactionRef, nativeReference)) {
            return;
        }

        CorrelationCardinality cardinality = current == Missing.INSTANCE
            ? CorrelationCardinality.UNIQUE
            : CorrelationCardinality.AMBIGUOUS;
        events.correlationCandidate(
            Optional.of(subject),
            key,
            interactionRef,
            nativeReference,
            cardinality
        );
        subjectState.resolutions.put(
            key,
            cardinality == CorrelationCardinality.UNIQUE
                ? new Unique(interactionRef, nativeReference)
                : Ambiguous.INSTANCE
        );
    }

    synchronized void completeExecution() {
        acceptingPublications = false;
    }

    synchronized void validateSubject(ProofSubjectRef subject) {
        requireAccepting("use proof subjects in semantic controls");
        requireSubject(subject);
    }

    synchronized void validateSubjectFlow(
        ProofSubjectRef subject,
        CorrelationKey key
    ) {
        SubjectState state = requireSubject(subject);
        key = Objects.requireNonNull(key, "key must not be null");
        if (!state.resolutions.containsKey(key)) {
            throw new IllegalArgumentException(
                "Correlation key schema '" + key.schema()
                    + "' is not armed for proof subject '" + subject + "'"
            );
        }
    }

    synchronized Optional<EvidenceSnapshot> soleUniqueNativeReference(
        ProofSubjectRef subject,
        CorrelationKey key
    ) {
        SubjectState selected = requireSubject(subject);
        Resolution resolution = selected.resolutions.get(
            Objects.requireNonNull(key, "key must not be null")
        );
        if (!(resolution instanceof Unique selectedUnique)) {
            return Optional.empty();
        }
        for (Map.Entry<ProofSubjectRef, SubjectState> entry : subjects.entrySet()) {
            if (entry.getKey().equals(subject)) {
                continue;
            }
            boolean sameNativeReference = entry.getValue().resolutions.values().stream()
                .filter(Unique.class::isInstance)
                .map(Unique.class::cast)
                .anyMatch(unique -> unique.nativeReference.equals(
                    selectedUnique.nativeReference
                ));
            if (sameNativeReference) {
                return Optional.empty();
            }
        }
        return Optional.of(selectedUnique.nativeReference);
    }

    synchronized boolean isSoleUniqueNativeReference(
        ProofSubjectRef subject,
        CorrelationKey key,
        EvidenceSnapshot expected
    ) {
        return soleUniqueNativeReference(subject, key).filter(
            Objects.requireNonNull(expected, "expected must not be null")::equals
        ).isPresent();
    }

    synchronized boolean isSoleUniqueSubjectFor(
        ProofSubjectRef subject,
        InteractionRef interactionRef
    ) {
        requireSubject(subject);
        InteractionRef candidate = Objects.requireNonNull(
            interactionRef,
            "interactionRef must not be null"
        );
        boolean selectedSubjectFound = false;
        for (Map.Entry<ProofSubjectRef, SubjectState> entry : subjects.entrySet()) {
            boolean subjectMatches = entry.getValue().resolutions.values().stream()
                .filter(Unique.class::isInstance)
                .map(Unique.class::cast)
                .anyMatch(unique -> unique.interactionRef.equals(candidate));
            if (!subjectMatches) {
                continue;
            }
            if (!entry.getKey().equals(subject)) {
                return false;
            }
            selectedSubjectFound = true;
        }
        return selectedSubjectFound;
    }

    private ProofSubjectRef createReference() {
        if (nextSubjectValue < FIRST_SUBJECT_VALUE) {
            throw new IllegalStateException(
                "Proof-subject identity space is exhausted for this environment execution"
            );
        }
        ProofSubjectRef reference = new RuntimeProofSubjectRef(owner, nextSubjectValue);
        nextSubjectValue = nextSubjectValue == Long.MAX_VALUE
            ? Long.MIN_VALUE
            : nextSubjectValue + 1L;
        return reference;
    }

    private SubjectState requireSubject(ProofSubjectRef subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        if (!(subject instanceof RuntimeProofSubjectRef reference)
            || !reference.belongsTo(owner)) {
            throw new IllegalArgumentException(
                "Proof subject belongs to a different environment execution"
            );
        }
        SubjectState state = subjects.get(subject);
        if (state == null) {
            throw new IllegalArgumentException(
                "Proof subject is not allocated by this environment execution"
            );
        }
        return state;
    }

    private void requireAccepting(String action) {
        if (!acceptingPublications) {
            throw new IllegalStateException(
                "Environment execution is complete and cannot " + action
            );
        }
    }

    private static final class SubjectState {
        private final Map<CorrelationKey, Resolution> resolutions = new HashMap<>();
    }

    private static final class RuntimeProofSubjectRef implements ProofSubjectRef {
        private final Object owner;
        private final long value;

        private RuntimeProofSubjectRef(Object owner, long value) {
            this.owner = Objects.requireNonNull(owner, "owner must not be null");
            if (value < FIRST_SUBJECT_VALUE) {
                throw new IllegalArgumentException(
                    "proof-subject value must be at least " + FIRST_SUBJECT_VALUE
                );
            }
            this.value = value;
        }

        private boolean belongsTo(Object candidateOwner) {
            return owner == candidateOwner;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                || other instanceof RuntimeProofSubjectRef reference
                    && owner == reference.owner
                    && value == reference.value;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(owner) + Long.hashCode(value);
        }

        @Override
        public String toString() {
            return "proof-subject-" + value;
        }
    }

    private sealed interface Resolution permits Missing, Unique, Ambiguous {}

    private enum Missing implements Resolution {
        INSTANCE
    }

    private record Unique(
        InteractionRef interactionRef,
        EvidenceSnapshot nativeReference
    ) implements Resolution {
        private Unique {
            interactionRef = Objects.requireNonNull(
                interactionRef,
                "interactionRef must not be null"
            );
            nativeReference = Objects.requireNonNull(
                nativeReference,
                "nativeReference must not be null"
            );
        }

        private boolean sameCandidate(
            InteractionRef candidateInteraction,
            EvidenceSnapshot candidateReference
        ) {
            return interactionRef.equals(candidateInteraction)
                && nativeReference.equals(candidateReference);
        }
    }

    private enum Ambiguous implements Resolution {
        INSTANCE
    }
}
