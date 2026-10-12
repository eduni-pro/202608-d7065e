package se.ltu.d7065e.dashboard.control;

import java.time.Instant;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * One diagnosis as this view has watched it, open or closed.
 *
 * <p>Distinct from {@link RoomSnapshot}, which carries the case a room holds <em>now</em>. A room
 * has one current case at most, and the moment it is serviced the room is free to open another —
 * so a view built only from rooms can show a repair and then lose it. The demo this page exists
 * for is precisely a case being opened, compensated and then cleared, and an operator watching a
 * recovery needs the case to still be on the screen after it has recovered.
 *
 * <p>{@code openedAt} is the first time <em>this view</em> saw the case rather than the instant
 * the monitor opened it. The two differ for any case that was already open when the page's
 * process started, and the journal is where the authoritative timeline lives — one case replays
 * in full from {@code /api/cases/{caseId}}.
 */
public record CaseSnapshot(
        CaseId caseId,
        ZoneId zone,
        MonitorState state,
        FaultClassification classification,
        Instant openedAt,
        Instant updatedAt,
        List<String> evidence) {

    public CaseSnapshot {
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
    }

    /** Open means the monitor is still holding it: a latched case nobody has reported a fix for. */
    public boolean isOpen() {
        return state.isLatched();
    }

    /** The most recent line of the monitor's reasoning, which is what a table cell has room for. */
    public String latestEvidence() {
        return evidence.isEmpty() ? "" : evidence.getLast();
    }
}
