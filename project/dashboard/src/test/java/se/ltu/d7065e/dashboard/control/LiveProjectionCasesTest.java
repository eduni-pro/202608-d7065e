package se.ltu.d7065e.dashboard.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The case register behind the dashboard's cases table.
 *
 * <p>Separate from {@code LiveProjectionTest} because it asserts a different thing about the same
 * object: that test covers what a <em>room</em> currently shows, and a room forgets its case the
 * moment the next one opens. These cover what the view remembers about the <em>cases</em>
 * themselves, which is what makes a recovery watchable after it has happened.
 */
@DisplayName("The cases a live view remembers")
class LiveProjectionCasesTest {

    private static final Instant START = Instant.parse("2026-10-07T08:00:00Z");
    private static final CaseId FIRST = CaseId.of("11111111-1111-1111-1111-111111111111");
    private static final CaseId SECOND = CaseId.of("22222222-2222-2222-2222-222222222222");

    private LiveProjection projection;

    @BeforeEach
    void setUp() {
        projection = new LiveProjection(new SimulatedClock(START), Duration.ofSeconds(180));
    }

    @Test
    void shouldHaveNoCasesBeforeAnythingIsConfirmed() {
        assertEquals(List.of(), projection.cases());
    }

    @Test
    void shouldKeepTheCaseAfterTheRoomHasBeenServiced() {
        projection.observe(fault(Topology.A115, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER, FIRST, START));

        projection.observe(fault(Topology.A115, MonitorState.SERVICED,
                FaultClassification.STUCK_DAMPER, FIRST, START.plusSeconds(600)));

        List<CaseSnapshot> cases = projection.cases();
        assertEquals(1, cases.size(), "a repair must not delete the case that was repaired");
        assertFalse(cases.getFirst().isOpen());
        assertEquals(MonitorState.SERVICED, cases.getFirst().state());
    }

    @Test
    void shouldPutAnOpenCaseAboveAClosedOneHoweverRecentTheRepair() {
        projection.observe(fault(Topology.A115, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER, FIRST, START));
        projection.observe(fault(Topology.A115, MonitorState.SERVICED,
                FaultClassification.STUCK_DAMPER, FIRST, START.plusSeconds(600)));
        projection.observe(fault(Topology.A124, MonitorState.COMPENSATING,
                FaultClassification.BLOCKED_FILTER, SECOND, START.plusSeconds(60)));

        List<CaseSnapshot> cases = projection.cases();

        assertTrue(cases.getFirst().isOpen(),
                "work outstanding comes before history, even when the history is newer");
        assertEquals(Topology.A124, cases.getFirst().zone());
        assertEquals(Topology.A115, cases.get(1).zone());
    }

    @Test
    void shouldKeepTwoCasesInOneRoomApart() {
        projection.observe(fault(Topology.A115, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER, FIRST, START));
        projection.observe(fault(Topology.A115, MonitorState.SERVICED,
                FaultClassification.STUCK_DAMPER, FIRST, START.plusSeconds(600)));

        projection.observe(fault(Topology.A115, MonitorState.CONFIRMED,
                FaultClassification.SENSOR_DRIFT, SECOND, START.plusSeconds(900)));

        assertEquals(2, projection.cases().size(),
                "a room that fails twice has failed twice; the second case must not overwrite "
                        + "the record of the first");
    }

    @Test
    void shouldNotLetARedeliveredEarlierEventReopenARepairedCase() {
        projection.observe(fault(Topology.A115, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER, FIRST, START));
        projection.observe(fault(Topology.A115, MonitorState.SERVICED,
                FaultClassification.STUCK_DAMPER, FIRST, START.plusSeconds(600)));

        projection.observe(fault(Topology.A115, MonitorState.COMPENSATING,
                FaultClassification.STUCK_DAMPER, FIRST, START.plusSeconds(300)));

        assertEquals(MonitorState.SERVICED, projection.cases().getFirst().state(),
                "a repair undone by a stale redelivery would be a false alarm about a room "
                        + "somebody has already fixed");
    }

    @Test
    void shouldCarryTheMonitorsLatestReasoningForTheRow() {
        projection.observe(new FaultStateChanged(Topology.A115, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER,
                List.of("residual above h", "damper pinned at 0.15"),
                ObservedAt.of(START), FIRST));

        assertEquals("damper pinned at 0.15", projection.cases().getFirst().latestEvidence());
    }

    @Test
    void shouldRememberWhenTheCaseWasFirstSeenRatherThanWhenItLastChanged() {
        projection.observe(fault(Topology.A115, MonitorState.CONFIRMED,
                FaultClassification.STUCK_DAMPER, FIRST, START));

        projection.observe(fault(Topology.A115, MonitorState.COMPENSATING,
                FaultClassification.STUCK_DAMPER, FIRST, START.plusSeconds(120)));

        CaseSnapshot one = projection.cases().getFirst();
        assertEquals(START, one.openedAt());
        assertEquals(START.plusSeconds(120), one.updatedAt());
    }

    private static FaultStateChanged fault(ZoneId zone, MonitorState state,
            FaultClassification classification, CaseId caseId, Instant at) {
        return new FaultStateChanged(zone, state, classification,
                List.of("residual above h"), ObservedAt.of(at), caseId);
    }
}
