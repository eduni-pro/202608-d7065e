package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-32 section 5 — the two faults an upper-arm-only CUSUM cannot see, because they push CO2 the
 * other way.
 *
 * <ul>
 * <li><b>A sensor drifting low</b> is the dangerous one. The inner loop believes the room is
 *     cleaner than it is and under-ventilates an occupied room, so a safety-relevant fault reads
 *     as perfect health on a one-sided detector. It is also the one with the cleanest signature in
 *     the whole system: with nobody in the room the reading settles <em>below</em> the 420 ppm
 *     outdoor concentration, which no amount of ventilation can achieve.
 * <li><b>A damper stuck wide open</b> over-ventilates: the room stays comfortable, the fan energy
 *     is wasted, the other five rooms are quietly robbed of air by the split, and the controller
 *     never learns the damper is dead because its own measurement looks good.
 * </ul>
 *
 * <p>The two need different settings of {@link LowerArm}, and that is the finding rather than a
 * convenience. The guarded arm — the honest mirror of the upper arm's occupancy ceiling — keeps
 * R-3 and sees the drifting sensor. It cannot see the stuck-open damper, because "everybody left
 * the room" explains a room that stopped getting stuffier at least as well as "the damper ignored
 * the close command" does, and nothing in a 10 min window separates them without an occupancy
 * signal this building does not have. Dropping the guard sees the stuck-open damper and costs six
 * false alarms a week ({@link FalseAlarmWeekTest}). Both results are asserted here so neither can
 * drift unnoticed.
 */
class TwoSidedDetectionTest {

    private static final ZoneId FAULTED = Topology.A115;
    private static final double DRIFT_PPM_PER_HOUR = -300;

    /**
     * A sensor whose zero walks down at 300 ppm/h, which is what
     * {@code InjectedFault.sensorDrift} expresses with a negative rate. Detected by the guarded
     * lower arm and classified from the impossible floor: the drift takes the empty-room reading
     * below outdoor, and no ventilation takes a room below the air it is ventilated with.
     */
    @Test
    void shouldDetectASensorDriftingLowAndClassifyItFromTheImpossibleOvernightFloor() {
        MonitorHarness harness = MonitorHarness.withLowerArm(42, LowerArm.GUARDED);
        harness.innerLoopRunning(false);

        // A day and a half of healthy history first, so the overnight floor exists and says the
        // sensor's zero was where it should be before the drift started. Without it the monitor
        // would be confirming a fault it has no empty-room evidence about, which is the honest
        // UNCLASSIFIED case and not this test.
        assertEquals(-1, occupyByDay(harness, 30, 0),
                "a day and a half of healthy history confirmed a fault on its own");
        for (ZoneId zone : Topology.ZONE_IDS) {
            assertTrue(!harness.monitor().stateOf(zone).isLatched(),
                    "a healthy day and a half latched " + zone);
        }

        int minutes = occupyByDay(harness, 24, DRIFT_PPM_PER_HOUR);
        assertNotEquals(-1, minutes, "a sensor drifting low at 300 ppm/h was never confirmed, "
                + "which is the fault the controller answers by under-ventilating the room");
        System.out.println(("T-32 sensor drifting low on A115, seed 42: confirmed %d min (%.1f h) "
                + "after the drift started, by then %.0f ppm of offset; overnight floor %.0f ppm "
                + "against outdoor %.0f ppm")
                .formatted(minutes, minutes / 60.0, DRIFT_PPM_PER_HOUR * minutes / 60.0,
                        harness.monitor().overnightFloorOf(FAULTED), Topology.OUTDOOR.value()));

        assertEquals(FaultClassification.SENSOR_DRIFT,
                harness.monitor().caseOf(FAULTED).classification(),
                "a sub-outdoor overnight floor was not read as drift: " + evidence(harness));
        assertTrue(evidence(harness).stream().anyMatch(line -> line.contains("BELOW")),
                "the impossibility was not named in the evidence: " + evidence(harness));
        assertTrue(harness.monitor().lowerCusumOf(FAULTED).value()
                        > harness.monitor().cusumOf(FAULTED).value(),
                "the diagnosis did not come from the lower arm");
    }

    /**
     * A damper that ignores a command to close, and keeps the room over-ventilated. Needs
     * {@link LowerArm#HONEST}; the next test measures that the guarded arm misses it.
     */
    @Test
    void shouldDetectADamperStuckWideOpenAfterACommandToClose() {
        MonitorHarness harness = openedThenSettled(42, LowerArm.HONEST);

        harness.physics().stick(FAULTED, 0.80);
        harness.command(FAULTED, Topology.MINIMUM_POSITION.value(), "dcv: close down, CO2 is low");

        int cycles = harness.tickUntil(Duration.ofMinutes(15),
                () -> harness.monitor().stateOf(FAULTED) == MonitorState.CONFIRMED);
        assertNotEquals(-1, cycles, "a damper stuck wide open was never confirmed in 15 min");
        System.out.println(("T-32 damper stuck wide open on A115, seed 42: confirmed %d min after "
                + "the ignored close command, residual %+.1f ppm, lower arm %.1f ppm")
                .formatted(cycles, harness.monitor().residualOf(FAULTED),
                        harness.monitor().lowerCusumOf(FAULTED).value()));

        assertEquals(FaultClassification.DAMPER_STUCK_OPEN,
                harness.monitor().caseOf(FAULTED).classification(),
                "the mirror of a stuck-shut damper has its own name (D-11), because the two "
                        + "extremes need opposite responses: " + evidence(harness));
        assertTrue(evidence(harness).stream().anyMatch(line -> line.contains("WIDE OPEN")),
                "the evidence still names the extreme the damper is stuck at, which is what a "
                        + "technician reads: " + evidence(harness));
        assertTrue(harness.monitor().flowModel().believesUnresponsive(FAULTED),
                "the shared flow belief has to be pinned for a damper stuck OPEN as well as one "
                        + "stuck shut, or the model goes on crediting this room with the closed "
                        + "position it was commanded to and the other five rooms' residuals are "
                        + "computed against a building that does not exist (ADR-0010, R-7)");
        assertEquals(0.0, harness.monitor().cusumOf(FAULTED).value(),
                "the upper arm accumulated on a room that is losing CO2");
    }

    /**
     * The measured cost of the guard, asserted so it cannot be quietly lost: the same stuck-open
     * damper, the same seed, with the lower arm behind its plausibility floor, is <em>not</em>
     * detected. A room that stops getting stuffier after a close command is exactly what a room
     * emptying out looks like, and the floor trajectory — zero generation, the emptiest the room
     * could be — sits below the measurement the whole time, so nothing accumulates.
     *
     * <p>This is the trade in one assertion. R-3 and the stuck-open signature cannot both be had
     * from a residual observer with no occupancy signal, and the project reports that rather than
     * inventing a signal to escape it.
     */
    @Test
    void shouldNotDetectADamperStuckWideOpenWhileTheLowerArmIsGuarded() {
        MonitorHarness harness = openedThenSettled(42, LowerArm.GUARDED);

        harness.physics().stick(FAULTED, 0.80);
        harness.command(FAULTED, Topology.MINIMUM_POSITION.value(), "dcv: close down, CO2 is low");
        harness.tickFor(Duration.ofHours(2));

        System.out.println(("T-32 damper stuck wide open, guarded lower arm: A115 ended %s with "
                + "residual %+.1f ppm and lower arm %.1f ppm after two hours — the measured cost "
                + "of the plausibility guard")
                .formatted(harness.monitor().stateOf(FAULTED),
                        harness.monitor().residualOf(FAULTED),
                        harness.monitor().lowerCusumOf(FAULTED).value()));
        assertTrue(!harness.monitor().stateOf(FAULTED).isLatched(),
                "the guarded arm now sees a stuck-open damper; if that is real, re-measure R-3 "
                        + "before believing it, because this test was the reason the guard exists");
    }

    /**
     * The upper arm has to be the arm the T-04 spike measured, cycle for cycle, whatever the lower
     * arm is doing — otherwise R-1's measured delay and the spike's separation are statements
     * about a detector that no longer exists. Asserted as an identity over twenty seeds rather
     * than as a p95, because a p95 could hide a changed series behind an unchanged percentile.
     */
    @Test
    void shouldLeaveTheUpperArmsBehaviourBitForBitUnchanged() {
        for (long seed = 1; seed <= 20; seed++) {
            MonitorHarness oneSided = stuckShut(seed, LowerArm.OFF);
            MonitorHarness twoSided = stuckShut(seed, LowerArm.GUARDED);
            for (int cycle = 1; cycle <= 15; cycle++) {
                oneSided.tick();
                twoSided.tick();
                assertEquals(oneSided.monitor().cusumOf(FAULTED).value(),
                        twoSided.monitor().cusumOf(FAULTED).value(), 0.0,
                        "seed %d cycle %d: the upper arm moved when the lower one was added"
                                .formatted(seed, cycle));
                assertEquals(oneSided.monitor().stateOf(FAULTED),
                        twoSided.monitor().stateOf(FAULTED),
                        "seed %d cycle %d: the state machine took a different edge"
                                .formatted(seed, cycle));
            }
            assertEquals(MonitorState.CONFIRMED, twoSided.monitor().stateOf(FAULTED));
            assertEquals(FaultClassification.STUCK_DAMPER,
                    twoSided.monitor().caseOf(FAULTED).classification());
        }
    }

    /** Both arms are magnitudes, and a healthy building leaves both of them on the floor. */
    @Test
    void shouldKeepBothArmsNonNegativeAndNearZeroWhileNothingIsWrong() {
        MonitorHarness harness = MonitorHarness.withLowerArm(42, LowerArm.GUARDED);
        harness.innerLoopRunning(false);
        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
            harness.physics().settle(zone);
        }
        double worstUpper = 0;
        double worstLower = 0;
        for (int cycle = 0; cycle < Duration.ofHours(6).toMinutes(); cycle++) {
            harness.tick();
            for (ZoneId zone : Topology.ZONE_IDS) {
                double upper = harness.monitor().cusumOf(zone).value();
                double lower = harness.monitor().lowerCusumOf(zone).value();
                assertTrue(upper >= 0 && lower >= 0,
                        "an arm went negative in " + zone + ": " + upper + " / " + lower);
                worstUpper = Math.max(worstUpper, upper);
                worstLower = Math.max(worstLower, lower);
            }
        }
        System.out.println(("T-32 healthy six hours, six rooms: worst upper arm %.1f ppm, worst "
                + "lower arm %.1f ppm, against h = 150 ppm")
                .formatted(worstUpper, worstLower));
        assertTrue(worstUpper < Topology.CUSUM_LIMIT_PPM && worstLower < Topology.CUSUM_LIMIT_PPM,
                "a healthy building reached h: upper %.1f ppm, lower %.1f ppm"
                        .formatted(worstUpper, worstLower));
    }

    /**
     * A day of occupancy with the room's sensor drifting at {@code ppmPerHour}, ticked minute by
     * minute. Returns the minute the room confirmed, or -1.
     */
    private int occupyByDay(MonitorHarness harness, int hours, double ppmPerHour) {
        for (int minute = 0; minute < hours * 60; minute++) {
            int hourOfDay = (int) ((harness.now().getEpochSecond() / 3600) % 24);
            boolean occupied = hourOfDay >= 8 && hourOfDay < 17;
            for (ZoneId zone : Topology.ZONE_IDS) {
                if (occupied) {
                    harness.physics().occupyToDesign(zone);
                } else {
                    harness.physics().occupy(zone, 0);
                }
            }
            if (ppmPerHour != 0) {
                harness.physics().drift(FAULTED, ppmPerHour * minute / 60.0);
            }
            harness.tick();
            if (harness.monitor().stateOf(FAULTED) == MonitorState.CONFIRMED) {
                return minute;
            }
        }
        return -1;
    }

    /**
     * A115 open at 0.80 and the whole building at the steady state of that, which is the only state
     * a damper can be stuck <em>wide open</em> in: a damper has to be open before it can fail to
     * close. Two hours is about nine time constants at the open position's airflow.
     */
    private MonitorHarness openedThenSettled(long seed, LowerArm arm) {
        MonitorHarness harness = MonitorHarness.withLowerArm(seed, arm);
        harness.innerLoopRunning(false);
        // The open command goes in before the first reading, so the monitor starts from a building
        // that is already at the steady state of an open damper rather than having to watch it get
        // there. That is not a convenience: with LowerArm.HONEST a legitimate opening confirms a
        // fault on its own, because the quasi-steady generation estimate overshoots during the
        // transit — Q_believed is the new large flow while the room is still at the old high level,
        // so G_est comes out high, the prediction rises above the measurement, and the honest
        // residual goes negative for twenty minutes with nothing wrong. It is the same weakness
        // FalseAlarmWeekTest prices at six false alarms a week, reached from the other direction,
        // and it is worth stating plainly: the unguarded arm cannot tell a damper that ignored a
        // command from a damper that obeyed one.
        harness.command(FAULTED, 0.80, "dcv: open up");
        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
            harness.physics().settle(zone);
        }
        harness.tickFor(Duration.ofHours(1));
        assertEquals(MonitorState.HEALTHY, harness.monitor().stateOf(FAULTED),
                "the room was not healthy with its damper legitimately open");
        harness.events().clear();
        return harness;
    }

    private MonitorHarness stuckShut(long seed, LowerArm arm) {
        MonitorHarness harness = MonitorHarness.withLowerArm(seed, arm);
        harness.innerLoopRunning(false);
        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
            harness.physics().settle(zone);
        }
        harness.tickFor(Duration.ofHours(1));
        harness.physics().stick(FAULTED, Topology.MINIMUM_POSITION.value());
        harness.command(FAULTED, 0.80, "the first ignored command");
        return harness;
    }

    private List<String> evidence(MonitorHarness harness) {
        return harness.monitor().caseOf(FAULTED).evidence().stream()
                .map(e -> (e.supportsFault() ? "+ " : "- ") + e.observation())
                .toList();
    }
}
