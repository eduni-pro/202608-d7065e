package se.ltu.d7065e.diagnosis.boundary;

import java.time.Instant;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The journal when there is no database: {@code JDBC_URL} unset means the monitor runs and
 * detects, it just does not keep history.
 *
 * <p>This exists so that a missing hot store degrades the one capability that needs it (R-5's
 * reconstruction) instead of stopping the component from starting at all. The evaluation harness
 * drives thousands of simulated hours through the same control classes and has no reason to own a
 * Timescale instance; neither does anyone reading the code on a laptop. The choice is a boundary
 * choice — which adapter gets wired — so {@code control} never learns that the journal might be
 * absent, and a null journal never reaches it.
 */
public final class DiscardingJournal implements ResidualJournalPort {

    @Override
    public void append(DomainEvent event, String source) {
        // No store configured. Deliberately silent: logging a line per event per cycle would
        // bury the log, and the absence of a journal is a deployment choice, not an incident.
    }

    @Override
    public List<DomainEvent> replayCase(CaseId caseId) {
        return List.of();
    }

    @Override
    public List<DomainEvent> replayZone(ZoneId zone, Instant from, Instant to) {
        return List.of();
    }
}
