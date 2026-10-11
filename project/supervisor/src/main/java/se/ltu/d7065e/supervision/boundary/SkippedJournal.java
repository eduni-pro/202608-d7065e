package se.ltu.d7065e.supervision.boundary;

import java.time.Instant;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * What the supervisor gets when {@code JDBC_URL} is unset. R-5's reconstruction needs the
 * journal, so a run without one produces no evidence for R-5 — but it still produces the
 * compensation R-2 and R-7 are measured on, which is why a missing store degrades the run
 * rather than preventing it.
 */
final class SkippedJournal implements ResidualJournalPort {

    @Override
    public void append(DomainEvent event, String source) {
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
