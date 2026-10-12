package se.ltu.d7065e.shared.control;

import java.time.Instant;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The hot store. R-5 requires every diagnosis to be reconstructable, so the journal keeps the
 * event stream rather than a bespoke persistence shape, and reconstruction is reading back
 * the events that produced the decision.
 */
public interface ResidualJournalPort {

    void append(DomainEvent event, String source);

    /** Every event belonging to one case, oldest first, for R-5 reconstruction. */
    List<DomainEvent> replayCase(CaseId caseId);

    /** The residual window a classifier needs, which is why this is native SQL (ADR-0002). */
    List<DomainEvent> replayZone(ZoneId zone, Instant from, Instant to);
}
