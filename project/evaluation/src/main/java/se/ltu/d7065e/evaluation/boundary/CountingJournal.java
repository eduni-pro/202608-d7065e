package se.ltu.d7065e.evaluation.boundary;

import java.time.Instant;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * Timescale, replaced by a counter. The harness measures detection, comfort, isolation and
 * throughput; none of those is a statement about the hot store, and keeping a simulated week of
 * six rooms' residuals in a list would make the memory the experiment's limit rather than the
 * code's. R-5's reconstruction is T-20's evidence and is measured against the real adapter.
 */
public final class CountingJournal implements ResidualJournalPort {

    private long appended;

    @Override
    public void append(DomainEvent event, String source) {
        appended++;
    }

    @Override
    public List<DomainEvent> replayCase(CaseId caseId) {
        return List.of();
    }

    @Override
    public List<DomainEvent> replayZone(ZoneId zone, Instant from, Instant to) {
        return List.of();
    }

    public long appended() {
        return appended;
    }
}
