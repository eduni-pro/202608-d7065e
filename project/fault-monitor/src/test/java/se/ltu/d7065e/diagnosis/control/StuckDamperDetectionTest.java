package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.FaultCase;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * R-1 — when a damper stops responding to commands, the fault is confirmed, p95 within 15 min of
 * the first ignored command.
 */
class StuckDamperDetectionTest {

    private static final ZoneId FAULTED = Topology.A115;
    private static final Duration BUDGET = Duration.ofMinutes(15);

    @Test
    void shouldConfirmAStuckDamperWithinFifteenMinutesOfTheFirstIgnoredCommand() {
        MonitorHarness harness = settledHarness(42);

        harness.physics().stick(FAULTED, Topology.MINIMUM_POSITION.value());
        harness.command(FAULTED, 0.80, "the first ignored command");

        int cycles = harness.tickUntil(BUDGET,
                () -> harness.monitor().stateOf(FAULTED) == MonitorState.CONFIRMED);

        assertNotEquals(-1, cycles,
                "the stuck damper was not confirmed within 15 min");
        System.out.println("R-1 stuck damper on A115, seed 42: confirmed " + cycles
                + " min after the first ignored command");
        assertEquals(FaultClassification.STUCK_DAMPER,
                harness.monitor().caseOf(FAULTED).classification());
    }

    @Test
    void shouldConfirmWithinFifteenMinutesOnEveryOneOfTwentySeeds() {
        List<Integer> delays = new ArrayList<>();
        for (long seed = 1; seed <= 20; seed++) {
            MonitorHarness harness = settledHarness(seed);
            harness.physics().stick(FAULTED, Topology.MINIMUM_POSITION.value());
            harness.command(FAULTED, 0.80, "the first ignored command");
            int cycles = harness.tickUntil(BUDGET,
                    () -> harness.monitor().stateOf(FAULTED) == MonitorState.CONFIRMED);
            assertNotEquals(-1, cycles, "seed " + seed + " missed the 15 min budget");
            delays.add(cycles);
        }
        delays.sort(null);
        int p95 = delays.get((int) Math.ceil(0.95 * delays.size()) - 1);
        System.out.println("R-1 stuck damper on A115, seeds 1..20: confirmation delay in minutes "
                + "min/median/p95/max = %d/%d/%d/%d".formatted(delays.get(0),
                delays.get(delays.size() / 2), p95, delays.get(delays.size() - 1)));
        assertTrue(p95 <= 15, "p95 confirmation delay was " + p95 + " min");
    }

    @Test
    void shouldCarryEnoughEvidenceToExplainTheDiagnosis() {
        MonitorHarness harness = settledHarness(42);
        harness.physics().stick(FAULTED, Topology.MINIMUM_POSITION.value());
        harness.command(FAULTED, 0.80, "the first ignored command");
        harness.tickUntil(BUDGET, () -> harness.monitor().stateOf(FAULTED) == MonitorState.CONFIRMED);

        FaultCase confirmed = harness.monitor().caseOf(FAULTED);
        assertTrue(confirmed.evidence().size() >= 5,
                "a confirmed case carried only " + confirmed.evidence().size() + " observations");
        assertTrue(confirmed.evidence().stream().anyMatch(e -> !e.supportsFault()),
                "no evidence against any hypothesis survived, so R-5 cannot argue with the result");
        assertEquals(FAULTED, confirmed.zone());
    }

    /**
     * A building at the quasi-steady state the observer learns its generation term from: every room
     * at its design occupancy, every damper at the minimum position, settled for an hour so that
     * the estimate has converged before anything is injected.
     */
    private MonitorHarness settledHarness(long seed) {
        MonitorHarness harness = MonitorHarness.standard(seed);
        harness.innerLoopRunning(false);
        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
            harness.physics().settle(zone);
        }
        harness.tickFor(Duration.ofHours(1));
        for (ZoneId zone : Topology.ZONE_IDS) {
            assertEquals(MonitorState.HEALTHY, harness.monitor().stateOf(zone),
                    "zone " + zone + " was not healthy after settling");
        }
        harness.events().clear();
        return harness;
    }
}
