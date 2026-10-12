package se.ltu.d7065e.dashboard.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.CusumStatistic;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.diagnosis.entity.Residual;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.ResidualComputed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * R-5's reconstruction, as far as it can be verified without a database: the part of the replay
 * that is not SQL.
 *
 * <p>What is covered here is the time ordering a reconstruction depends on, the empty case, the
 * unconfigured-journal case, and that a journal read failure is surfaced rather than swallowed. The
 * SQL itself — the statements in {@code TimescaleJournalAdapter} that select a case's decisions,
 * strategies, readings, commands and residuals, and the one-hour evidence lookback that decides
 * which residuals belong to a case — needs a PostgreSQL instance, and is covered by
 * {@code TimescaleJournalIntegrationTest} in {@code shared}, which runs against the compose stack's
 * Timescale container and skips when there is none. This file does not stand in for it.
 */
class CaseReplayTest {

    private static final ZoneId ROOM = Topology.A115;
    private static final Instant START = Instant.parse("2026-10-07T08:00:00Z");
    private static final CaseId CASE = CaseId.of("case-42");

    @Test
    void shouldReturnEveryEventOfTheCaseOldestFirst() {
        // As a replay arrives from the journal: each table sorted, their union not.
        FakeJournal journal = new FakeJournal(List.of(
                fault(START.plusSeconds(120)),
                reading(START),
                residual(START.plusSeconds(60)),
                reading(START.plusSeconds(30))));

        List<DomainEvent> timeline = new CaseReplay(journal).timelineOf(CASE);

        assertEquals(
                List.of(START, START.plusSeconds(30), START.plusSeconds(60),
                        START.plusSeconds(120)),
                timeline.stream().map(event -> event.at().value()).toList(),
                "a reconstruction that is not in time order does not explain anything");
    }

    @Test
    void shouldKeepEveryEventIncludingTwoRecordedAtTheSameInstant() {
        ReadingObserved first = reading(START);
        ResidualComputed second = residual(START);
        FakeJournal journal = new FakeJournal(List.of(first, second));

        List<DomainEvent> timeline = new CaseReplay(journal).timelineOf(CASE);

        assertEquals(List.of(first, second), timeline,
                "the ordering is stable, so the seq numbers the endpoint assigns are reproducible");
    }

    @Test
    void shouldReturnAnEmptyTimelineForACaseTheJournalDoesNotHold() {
        List<DomainEvent> timeline = new CaseReplay(new FakeJournal(List.of())).timelineOf(CASE);

        assertTrue(timeline.isEmpty(), "an unknown case is empty, not an error");
    }

    @Test
    void shouldReportUnavailableAndReplayNothingWhenNoJournalIsConfigured() {
        CaseReplay replay = new CaseReplay(null);

        assertFalse(replay.isAvailable(), "an unset JDBC_URL is a supported deployment");
        assertTrue(replay.timelineOf(CASE).isEmpty());
    }

    @Test
    void shouldReportAvailableWhenAJournalIsPresent() {
        assertTrue(new CaseReplay(new FakeJournal(List.of())).isAvailable());
    }

    @Test
    void shouldAskTheJournalForExactlyTheCaseRequested() {
        FakeJournal journal = new FakeJournal(List.of(reading(START)));

        new CaseReplay(journal).timelineOf(CASE);

        assertEquals(List.of(CASE), journal.requested);
    }

    /** The endpoint turns this into 503 rather than an empty timeline, so it must not be eaten. */
    @Test
    void shouldLetAJournalReadFailureOut() {
        CaseReplay replay = new CaseReplay(new FailingJournal());

        assertThrows(IllegalStateException.class, () -> replay.timelineOf(CASE));
    }

    private static ReadingObserved reading(Instant at) {
        return new ReadingObserved(ROOM, Ppm.of(880), ObservedAt.of(at), Quality.GOOD);
    }

    private static ResidualComputed residual(Instant at) {
        return new ResidualComputed(ROOM, Residual.of(480), CusumStatistic.of(900),
                ObservedAt.of(at), null);
    }

    private static FaultStateChanged fault(Instant at) {
        return new FaultStateChanged(ROOM, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER, List.of("residual above h"),
                ObservedAt.of(at), CASE);
    }

    private static final class FakeJournal implements ResidualJournalPort {

        private final List<DomainEvent> events;
        private final List<CaseId> requested = new ArrayList<>();

        FakeJournal(List<DomainEvent> events) {
            this.events = events;
        }

        @Override
        public void append(DomainEvent event, String source) {
            throw new UnsupportedOperationException("the dashboard only reads the journal");
        }

        @Override
        public List<DomainEvent> replayCase(CaseId caseId) {
            requested.add(caseId);
            return events;
        }

        @Override
        public List<DomainEvent> replayZone(ZoneId zone, Instant from, Instant to) {
            throw new UnsupportedOperationException("the dashboard replays whole cases");
        }
    }

    /** What an unreachable or broken PostgreSQL looks like through the journal adapter. */
    private static final class FailingJournal implements ResidualJournalPort {

        @Override
        public void append(DomainEvent event, String source) {
            throw new UnsupportedOperationException("the dashboard only reads the journal");
        }

        @Override
        public List<DomainEvent> replayCase(CaseId caseId) {
            throw new IllegalStateException("journal read failed: SELECT ... FROM decisions");
        }

        @Override
        public List<DomainEvent> replayZone(ZoneId zone, Instant from, Instant to) {
            throw new UnsupportedOperationException("the dashboard replays whole cases");
        }
    }
}
