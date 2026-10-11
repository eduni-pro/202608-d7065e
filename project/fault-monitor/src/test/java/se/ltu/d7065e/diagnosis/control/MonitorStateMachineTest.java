package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/** T-13 and T-18 — the state machine's three load-bearing edges: BLIND, latching, and service. */
class MonitorStateMachineTest {

    private static final ZoneId FAULTED = Topology.A115;
    private static final Duration BUDGET = Duration.ofMinutes(15);

    @Test
    void shouldReportBlindAndPublishNoFaultEventsWhenReadingsGoStale() {
        MonitorHarness harness = settledHarness(42);

        harness.readingsFlowing(false);
        harness.tickFor(Duration.ofMinutes(10));

        for (ZoneId zone : Topology.ZONE_IDS) {
            assertEquals(MonitorState.BLIND, harness.monitor().stateOf(zone));
            assertNull(harness.monitor().caseOf(zone), "BLIND opened a case, and BLIND is not a fault");
            assertEquals(List.of(), harness.events().faultEvents(zone),
                    "R-4: a blind monitor published a fault event for " + zone);
        }
        assertTrue(harness.events().states(FAULTED).stream()
                        .anyMatch(e -> e.state() == MonitorState.BLIND),
                "R-4: the monitor went blind without reporting it unavailable");
    }

    @Test
    void shouldTreatReadingsThatAreNotGoodAsUnusable() {
        MonitorHarness harness = settledHarness(42);

        harness.quality(Quality.DUPLICATE);
        harness.tickFor(Duration.ofMinutes(10));

        assertEquals(MonitorState.BLIND, harness.monitor().stateOf(FAULTED),
                "a stream of non-GOOD readings must drive the monitor blind, not feed the observer");
    }

    @Test
    void shouldReturnThroughWarmupRatherThanStraightToHealthyAfterGoingBlind() {
        MonitorHarness harness = settledHarness(42);
        harness.readingsFlowing(false);
        harness.tickFor(Duration.ofMinutes(10));

        harness.readingsFlowing(true);
        harness.tick();

        assertEquals(MonitorState.WARMUP, harness.monitor().stateOf(FAULTED),
                "after a gap the prediction is about a building nobody watched; it must re-anchor");
        harness.tickFor(Duration.ofMinutes(5));
        assertEquals(MonitorState.HEALTHY, harness.monitor().stateOf(FAULTED));
    }

    @Test
    void shouldKeepALatchedCaseWhenTheResidualCollapsesToZero() {
        MonitorHarness harness = confirmedHarness(42);
        CaseId latched = harness.monitor().caseOf(FAULTED).id();

        // The residual that found the fault disappears on its own, and it is worth being precise
        // about why, because the reason changed with R-7's fix. The confirmation pins the believed
        // opening at where the damper actually is, so from that cycle on the prediction integrates
        // at the room's real airflow and converges on the measurement over a few time constants.
        // The evidence for the fault evaporates without anything being repaired — which is exactly
        // the masking T-18's latch exists to survive, reached by a shorter road than compensation.
        // Freeing the damper is no longer the way to produce it: with the belief pinned, a damper
        // that silently starts working again drives the residual strongly *negative* instead, and
        // shouldLeaveALatchedCaseOnlyThroughService covers that case.
        harness.tickFor(Duration.ofHours(3));

        assertTrue(Math.abs(harness.monitor().residualOf(FAULTED)) < 150,
                "the residual did not collapse, so this test is not exercising masking");
        assertTrue(harness.monitor().stateOf(FAULTED).isLatched(),
                "T-18: the case cleared because the residual shrank, which is what compensation does");
        assertEquals(latched, harness.monitor().caseOf(FAULTED).id(),
                "T-18: a new case was opened instead of the original being kept");
    }

    @Test
    void shouldLeaveALatchedCaseOnlyThroughService() {
        MonitorHarness harness = confirmedHarness(42);
        harness.physics().unstick(FAULTED);
        harness.tickFor(Duration.ofHours(1));
        assertTrue(harness.monitor().stateOf(FAULTED).isLatched());

        harness.monitor().serviced(FAULTED);
        assertEquals(MonitorState.SERVICED, harness.monitor().stateOf(FAULTED));

        harness.tick();
        assertEquals(MonitorState.WARMUP, harness.monitor().stateOf(FAULTED));
        assertNull(harness.monitor().caseOf(FAULTED), "service must close the case, not keep it");

        harness.tickFor(Duration.ofMinutes(10));
        assertEquals(MonitorState.HEALTHY, harness.monitor().stateOf(FAULTED),
                "a serviced room must be able to become healthy again");
    }

    @Test
    void shouldStartCompensatingWhenASupervisorCommandReachesALatchedZone() {
        MonitorHarness harness = confirmedHarness(42);

        harness.command(FAULTED, 1.0, "supervisor: compensate a latched zone");
        harness.tick();

        assertEquals(MonitorState.COMPENSATING, harness.monitor().stateOf(FAULTED));
        assertTrue(harness.monitor().stateOf(FAULTED).isLatched(),
                "COMPENSATING must still be latched, or compensation would clear the fault");
    }

    @Test
    void shouldRequestAProbeAsAnEventAndNeverPublishToACommandTopic() {
        MonitorHarness harness = confirmedHarness(42);
        harness.events().clear();

        harness.tickFor(Duration.ofMinutes(35));

        List<FaultStateChanged> probes = harness.events().states(FAULTED).stream()
                .filter(e -> e.evidence().stream().anyMatch(line -> line.contains(ZoneMonitor.PROBE_REQUEST)))
                .toList();
        assertNotEquals(0, probes.size(), "T-18: a latched case never asked to be re-tested");
        assertTrue(probes.get(0).evidence().stream().anyMatch(line -> line.contains(FAULTED.value())),
                "the probe request must name the room it wants stepped");

        for (String topic : harness.events().topics()) {
            assertTrue(topic.startsWith("residuals/") || topic.startsWith("faults/"),
                    "fault-monitor published on " + topic + ", and it raises events only");
        }
    }

    @Test
    void shouldNeverGoBlindWhileACaseIsLatched() {
        MonitorHarness harness = confirmedHarness(42);

        harness.readingsFlowing(false);
        harness.tickFor(Duration.ofHours(1));

        assertTrue(harness.monitor().stateOf(FAULTED).isLatched(),
                "a latched case must survive the sensor dying; losing it would be a way to clear a fault");
        assertEquals(MonitorState.BLIND, harness.monitor().stateOf(Topology.A124),
                "the healthy rooms must still go blind on the same dead stream");
    }

    @Test
    void shouldLeaveTheOtherFiveZonesUntouchedWhenOneZoneFaults() {
        for (ZoneId injected : List.of(Topology.A115, Topology.A145, Topology.A124)) {
            MonitorHarness harness = settledHarness(42);
            harness.physics().stick(injected, Topology.MINIMUM_POSITION.value());
            harness.command(injected, 0.80, "the first ignored command");
            harness.tickUntil(BUDGET, () -> harness.monitor().stateOf(injected) == MonitorState.CONFIRMED);
            harness.tickFor(Duration.ofHours(2));

            assertTrue(harness.monitor().stateOf(injected).isLatched(),
                    "the injected room " + injected + " was not confirmed");
            for (ZoneId other : Topology.ZONE_IDS) {
                if (other.equals(injected)) {
                    continue;
                }
                assertEquals(MonitorState.HEALTHY, harness.monitor().stateOf(other),
                        "R-7: injecting into " + injected + " moved " + other);
                assertNull(harness.monitor().caseOf(other),
                        "R-7: injecting into " + injected + " opened a case on " + other);
                assertEquals(List.of(), harness.events().faultEvents(other),
                        "R-7: injecting into " + injected + " produced a fault event on " + other);
            }
        }
    }

    private MonitorHarness settledHarness(long seed) {
        MonitorHarness harness = MonitorHarness.standard(seed);
        harness.innerLoopRunning(false);
        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
            harness.physics().settle(zone);
        }
        harness.tickFor(Duration.ofHours(1));
        harness.events().clear();
        return harness;
    }

    private MonitorHarness confirmedHarness(long seed) {
        MonitorHarness harness = settledHarness(seed);
        harness.physics().stick(FAULTED, Topology.MINIMUM_POSITION.value());
        harness.command(FAULTED, 0.80, "the first ignored command");
        int cycles = harness.tickUntil(BUDGET,
                () -> harness.monitor().stateOf(FAULTED) == MonitorState.CONFIRMED);
        assertNotEquals(-1, cycles, "the fixture needs a confirmed case to latch");
        return harness;
    }
}
