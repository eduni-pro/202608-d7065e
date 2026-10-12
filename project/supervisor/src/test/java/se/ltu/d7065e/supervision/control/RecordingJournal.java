package se.ltu.d7065e.supervision.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** The hot store, in memory, so R-5's provenance field can be asserted without TimescaleDB. */
final class RecordingJournal implements ResidualJournalPort {

    private final List<Map.Entry<DomainEvent, String>> appended = new ArrayList<>();

    @Override
    public void append(DomainEvent event, String source) {
        appended.add(Map.entry(event, source));
    }

    @Override
    public List<DomainEvent> replayCase(CaseId caseId) {
        return appended.stream().map(Map.Entry::getKey).toList();
    }

    @Override
    public List<DomainEvent> replayZone(ZoneId zone, Instant from, Instant to) {
        return replayCase(null);
    }

    List<String> sources() {
        return appended.stream().map(Map.Entry::getValue).distinct().toList();
    }

    int size() {
        return appended.size();
    }
}
