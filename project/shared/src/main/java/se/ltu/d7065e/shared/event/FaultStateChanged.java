package se.ltu.d7065e.shared.event;

import java.util.List;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Published on {@code faults/{zone}} by {@code fault-monitor}, which raises events and
 * never commands. The supervisor acts only on a state the monitor reports as latched.
 */
public record FaultStateChanged(
        ZoneId zone,
        MonitorState state,
        FaultClassification classification,
        List<String> evidence,
        ObservedAt at,
        CaseId caseId) implements DomainEvent {

    public FaultStateChanged {
        if (zone == null || state == null || classification == null || at == null) {
            throw new IllegalArgumentException("fault event fields must not be null");
        }
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        // The same invariant FaultCase enforces. Without it the monitor can publish a latched
        // state carrying no classification: the event encodes and decodes cleanly, and then
        // throws when a subscriber rebuilds the aggregate from it. R-5 requires every diagnosis
        // to be reconstructable, so the invariant holds on both sides of the wire or neither.
        if (state.isLatched() && classification == FaultClassification.NONE) {
            throw new IllegalArgumentException(
                    "a latched fault event must carry a classification, even UNCLASSIFIED");
        }
    }
}
