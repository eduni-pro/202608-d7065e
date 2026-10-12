package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * R-7 — the five rooms that were not injected, against the defect that broke it.
 *
 * <p>The evaluation harness measured R-7 failing in 80 of 270 observed rooms while the
 * compensation airflow deviation was exactly 0.0000 everywhere, which said the algebra was sound
 * and the belief feeding it was not. Attribution with the supervisor switched off entirely put
 * 411 fault events in the five healthy rooms with nothing acting on any diagnosis, so the monitor
 * was breaking its own diagnostic isolation. The mechanism is in {@link AhuFlowModel}'s class
 * comment: the model believed a stuck damper was wherever it had last been commanded, and because
 * {@code Q_i = Q_fan * a_i / sum(a_j)} is a ratio, air wrongly credited to the stuck room is air
 * subtracted from the other five.
 *
 * <p>These tests are written to fail against that monitor. The shape they need is the shape the
 * live failure had: a damper latched in one room, and the room's <em>command</em> then driven on
 * upwards the way compensation drives it — A115 was commanded up to 0.49 while frozen at 0.15 —
 * over a horizon long enough for a slow accumulation to arrive, since the live false confirmation
 * appeared about four hours in.
 *
 * <p>Occupancy is deliberately held constant across the horizon. The quantity under test is the
 * flow belief, so everything else is held still; what occupancy edges cost the two-sided detector
 * is R-3's question and is measured over a full week in {@link FalseAlarmWeekTest}.
 */
class StuckDamperIsolationTest {

    private static final ZoneId FAULTED = Topology.A115;
    private static final Duration HORIZON = Duration.ofHours(12);

    /**
     * The flow belief is only ever as good as the fan speed belief and the five healthy positions
     * that go into the same split, so 1 % is a statement about the damper belief specifically: at
     * this tolerance nothing but a wrong believed opening can fail it.
     */
    private static final double FLOW_TOLERANCE = 0.01;

    @Test
    void shouldLeaveTheOtherFiveZonesBelowSuspicionForTwelveHoursAfterALatch() {
        MonitorHarness harness = latchedStuckDamper(42);
        double suspicion = harness.monitor().parameters().suspectThresholdPpm();

        double worst = 0;
        ZoneId worstZone = null;
        long minutes = HORIZON.toMinutes();
        for (long minute = 0; minute < minutes; minute++) {
            // What compensation does to the faulted room's command, and what the old belief
            // followed: the supervisor keeps asking the dead damper for more air.
            if (minute % 30 == 0) {
                double target = Math.min(1.0, 0.20 + 0.05 * (minute / 30));
                harness.command(FAULTED, target, "supervisor: compensate on a stuck damper");
            }
            harness.tick();
            for (ZoneId zone : Topology.ZONE_IDS) {
                if (zone.equals(FAULTED)) {
                    continue;
                }
                double arm = Math.max(harness.monitor().cusumOf(zone).value(),
                        harness.monitor().lowerCusumOf(zone).value());
                if (arm > worst) {
                    worst = arm;
                    worstZone = zone;
                }
            }
        }

        System.out.println(("R-7 isolation, stuck damper latched on %s, seed 42, %s horizon with "
                + "the faulted room commanded up to 1.00: worst CUSUM in the five healthy rooms "
                + "%.1f ppm (%s), against the %.0f ppm suspicion threshold and h = %.0f ppm")
                .formatted(FAULTED.value(), HORIZON, worst, worstZone,
                        suspicion, harness.monitor().parameters().cusumH()));

        for (ZoneId zone : Topology.ZONE_IDS) {
            if (zone.equals(FAULTED)) {
                continue;
            }
            assertEquals(MonitorState.HEALTHY, harness.monitor().stateOf(zone),
                    "zone %s reached %s with no fault injected in it".formatted(zone,
                            harness.monitor().stateOf(zone)));
        }
        assertTrue(worst < suspicion,
                "a healthy room's CUSUM reached %.1f ppm against the %.0f ppm suspicion threshold, "
                        .formatted(worst, suspicion)
                        + "which is the monitor breaking its own diagnostic isolation");
    }

    /**
     * The same horizon, read as a statement about the plant rather than about the state machine:
     * what the monitor believes each healthy room is getting against what it is actually getting.
     * This is the assertion that fails loudest on the old monitor, because it names the quantity
     * that was wrong instead of the symptom.
     */
    @Test
    void shouldPredictWhatTheFiveHealthyZonesAreActuallyGettingAfterTheLatch() {
        MonitorHarness harness = latchedStuckDamper(42);

        double worst = 0;
        ZoneId worstZone = null;
        for (long minute = 0; minute < HORIZON.toMinutes(); minute++) {
            if (minute % 30 == 0) {
                harness.command(FAULTED, Math.min(1.0, 0.20 + 0.05 * (minute / 30)),
                        "supervisor: compensate on a stuck damper");
            }
            harness.tick();
            for (ZoneId zone : Topology.ZONE_IDS) {
                if (zone.equals(FAULTED)) {
                    continue;
                }
                double believed = harness.monitor().flowModel().believedFlowTo(zone);
                double actual = harness.physics().trueFlow(zone);
                double error = Math.abs(believed / actual - 1);
                if (error > worst) {
                    worst = error;
                    worstZone = zone;
                }
            }
        }

        System.out.println(("R-7 flow belief, stuck damper latched on %s: worst relative error "
                + "between believed and actual airflow across the five healthy rooms over %s is "
                + "%.4f (%s), tolerance %.2f")
                .formatted(FAULTED.value(), HORIZON, worst, worstZone, FLOW_TOLERANCE));
        assertTrue(worst <= FLOW_TOLERANCE,
                ("the monitor believed a healthy room was getting air it was not: worst relative "
                        + "error %.4f in %s, over the %.2f tolerance. Every point of that error is "
                        + "air credited to the stuck damper, which is what pushed the healthy "
                        + "rooms' residuals negative.").formatted(worst, worstZone, FLOW_TOLERANCE));
    }

    /** The belief stops following the faulted zone's commands, and only service restores that. */
    @Test
    void shouldStopBelievingTheFaultedZonesCommandsOnceLatched() {
        MonitorHarness harness = latchedStuckDamper(42);
        double pinned = harness.monitor().flowModel().positionOf(FAULTED).value();

        assertTrue(harness.monitor().flowModel().believesUnresponsive(FAULTED),
                "a latched stuck damper is still believed to answer its commands");
        assertEquals(Topology.MINIMUM_POSITION.value(), pinned, 0.02,
                "the belief was not pinned where the room's own CO2 and its last agreed position "
                        + "put the damper, which is " + Topology.MINIMUM_POSITION);

        harness.command(FAULTED, 0.95, "supervisor: compensate harder");
        harness.tickFor(Duration.ofMinutes(10));
        assertEquals(pinned, harness.monitor().flowModel().positionOf(FAULTED).value(), 1e-9,
                "the belief followed a command the damper is demonstrably ignoring");

        // Service is the one event that makes the commands meaningful again: somebody repaired the
        // hardware and said so, which is the only thing that can.
        harness.physics().unstick(FAULTED);
        harness.monitor().serviced(FAULTED);
        harness.tick();
        harness.command(FAULTED, 0.60, "dcv: back under control");
        assertTrue(!harness.monitor().flowModel().believesUnresponsive(FAULTED),
                "service did not restore the model's belief in this damper's commands");
        assertEquals(0.60, harness.monitor().flowModel().positionOf(FAULTED).value(), 1e-9,
                "a serviced damper's belief still does not follow its commands");
    }

    /**
     * A building at design occupancy with every damper at its minimum, A115's damper frozen there,
     * and the first command it ignores confirmed as a stuck damper — the R-1 scenario, carried on
     * past the confirmation into the horizon R-7 is about.
     */
    private MonitorHarness latchedStuckDamper(long seed) {
        MonitorHarness harness = MonitorHarness.standard(seed);
        harness.innerLoopRunning(false);
        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
            harness.physics().settle(zone);
        }
        harness.tickFor(Duration.ofHours(1));
        harness.physics().stick(FAULTED, Topology.MINIMUM_POSITION.value());
        harness.command(FAULTED, 0.80, "the first ignored command");
        int cycles = harness.tickUntil(Duration.ofMinutes(15),
                () -> harness.monitor().stateOf(FAULTED) == MonitorState.CONFIRMED);
        assertNotEquals(-1, cycles, "the stuck damper this test is about was never confirmed");
        return harness;
    }
}
