package se.ltu.d7065e.dashboard.control;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.DomainEvent;

/**
 * R-5 reconstruction as the view needs it: everything the journal holds for one case, oldest
 * first.
 *
 * <p>Reading the rows is the journal adapter's job. What lives here is the part that is not
 * infrastructure and can therefore be tested without a database — whether a replay is possible
 * at all, and the time order the timeline has to be in before anything renders it. The order is
 * re-established here rather than trusted from the journal: a replay is assembled from several
 * tables, so each table arrives sorted while the union of them is not, and a timeline whose
 * events are out of order does not reconstruct a diagnosis, it just lists its parts.
 */
public final class CaseReplay {

    private final ResidualJournalPort journal;

    /** {@code null} is the supported deployment in which {@code JDBC_URL} was never set. */
    public CaseReplay(ResidualJournalPort journal) {
        this.journal = journal;
    }

    public boolean isAvailable() {
        return journal != null;
    }

    /**
     * The case's events in the order they happened. Empty when the journal holds no such case,
     * and empty when there is no journal at all: a caller that must tell those apart asks
     * {@link #isAvailable()} first, which is what the HTTP boundary does to answer 503 rather
     * than 404.
     */
    public List<DomainEvent> timelineOf(CaseId caseId) {
        if (journal == null) {
            return List.of();
        }
        List<DomainEvent> events = new ArrayList<>(journal.replayCase(caseId));
        events.sort(Comparator.comparing(event -> event.at().value()));
        return List.copyOf(events);
    }
}
