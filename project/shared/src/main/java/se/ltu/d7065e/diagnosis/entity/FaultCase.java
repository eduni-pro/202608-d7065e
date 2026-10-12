package se.ltu.d7065e.diagnosis.entity;

import java.util.ArrayList;
import java.util.List;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * One fault from first suspicion to service.
 *
 * <p>Invariant: a latched case leaves CONFIRMED or COMPENSATING only through
 * {@link #serviced}. This is the single hardest property in the design — once the
 * supervisor compensates, the residual shrinks and a naive monitor would clear a fault that
 * is still there, so the state machine refuses to be talked out of a confirmed diagnosis by
 * the evidence its own response destroyed.
 */
public record FaultCase(
        CaseId id,
        ZoneId zone,
        MonitorState state,
        FaultClassification classification,
        List<Evidence> evidence,
        ObservedAt openedAt,
        ObservedAt updatedAt) {

    public FaultCase {
        if (id == null || zone == null || state == null || classification == null
                || openedAt == null || updatedAt == null) {
            throw new IllegalArgumentException("fault case fields must not be null");
        }
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        if (state.isLatched() && classification == FaultClassification.NONE) {
            throw new IllegalArgumentException(
                    "a latched case must carry a classification, even UNCLASSIFIED");
        }
    }

    public static FaultCase suspected(ZoneId zone, Evidence first, ObservedAt at) {
        return new FaultCase(CaseId.newId(), zone, MonitorState.SUSPECT,
                FaultClassification.NONE, List.of(first), at, at);
    }

    public boolean isLatched() {
        return state.isLatched();
    }

    public FaultCase withEvidence(Evidence more, ObservedAt at) {
        List<Evidence> combined = new ArrayList<>(evidence);
        combined.add(more);
        return new FaultCase(id, zone, state, classification, combined, openedAt, at);
    }

    public FaultCase confirmed(FaultClassification decided, ObservedAt at) {
        if (decided == FaultClassification.NONE) {
            throw new IllegalArgumentException("cannot confirm a case as NONE");
        }
        return new FaultCase(id, zone, MonitorState.CONFIRMED, decided, evidence, openedAt, at);
    }

    /**
     * Replaces a latched case's classification without touching its state, for the ordering
     * {@code ClassifyWhen.AFTER_PROBE} needs: the case latches with the diagnosis deferred and is
     * named once the probe it asked for has been judged, which by then is often a case already
     * COMPENSATING. It is not an escape from the latching invariant — the state is carried through
     * unchanged and the only exit is still {@link #serviced} — and it cannot un-name a case, since
     * {@code NONE} is refused here as it is in {@link #confirmed}.
     */
    public FaultCase reclassified(FaultClassification decided, ObservedAt at) {
        if (!isLatched()) {
            throw new IllegalStateException(
                    "only a latched case carries a classification to replace, state was " + state);
        }
        if (decided == FaultClassification.NONE) {
            throw new IllegalArgumentException("cannot reclassify a latched case as NONE");
        }
        return new FaultCase(id, zone, state, decided, evidence, openedAt, at);
    }

    public FaultCase compensating(ObservedAt at) {
        if (!isLatched()) {
            throw new IllegalStateException(
                    "only a confirmed case may start compensating, state was " + state);
        }
        return new FaultCase(id, zone, MonitorState.COMPENSATING, classification, evidence, openedAt, at);
    }

    /** The one explicit exit from a latched case: the equipment was repaired. */
    public FaultCase serviced(ObservedAt at) {
        if (!isLatched()) {
            throw new IllegalStateException("only a latched case can be serviced, state was " + state);
        }
        return new FaultCase(id, zone, MonitorState.SERVICED, classification, evidence, openedAt, at);
    }
}
