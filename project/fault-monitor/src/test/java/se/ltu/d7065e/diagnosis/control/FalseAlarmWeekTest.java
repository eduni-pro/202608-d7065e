package se.ltu.d7065e.diagnosis.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * R-3 — at most one false alarm per simulated week, re-measured for the two-sided detector
 * (T-32 section 5).
 *
 * <p>A fault-free week of all six rooms at sensor noise sigma = 30 ppm, with the inner loop running
 * and an occupancy schedule the observer cannot see: arrivals, a lunch dip, departures, and the
 * seminar room A145 filling to twenty people for booked slots and emptying between them. The
 * schedule is what makes this a real test of the design rather than of the CUSUM — the observer has
 * no head count, so every one of those transitions is model error it has to absorb.
 */
class FalseAlarmWeekTest {

    private static final double SIGMA = 30;
    private static final long[] SEEDS = {42, 1, 2, 3, 7};

    @Test
    void shouldRaiseAtMostOneConfirmationOverAFaultFreeWeek() {
        long confirmations = faultFreeWeek(42, LowerArm.GUARDED);
        assertTrue(confirmations <= 1,
                "R-3: a fault-free week at sigma = %.0f ppm raised %d confirmations"
                        .formatted(SIGMA, confirmations));
    }

    /**
     * The measurement T-32 section 3 asked for and section 5 made urgent: what a second CUSUM arm
     * costs R-3, over one fault-free component week across five seeds rather than on the one seed
     * the threshold was tuned on.
     *
     * <p>The bound asserted here is the one <em>measured</em>, not the one wanted. With the guard
     * the worst seed of five raises <b>0</b> false confirmations per six-room week, against the one
     * R-3 allows — so on this horizon the second arm costs the requirement nothing, and the
     * assertion's {@code <= 1} is a bound with a whole case of room in it rather than a measurement
     * sitting on its limit. Without the guard the detector raises six, which fails R-3 outright and
     * latches every room in the building inside a week. If a future change moves either number, this
     * test fails and the report's claim has to change with it; that is the point of asserting the
     * measurement.
     *
     * <p><b>This is not R-3's verdict and must not be quoted as one.</b> R-3's criterion is scored
     * over twenty fault-free weeks, where the rate is 0.55 confirmed cases per week — 45 % below the
     * limit — with 16 of 20 weeks clean and a worst week of 6. The criterion is the worst week, so
     * <b>R-3 FAILS</b>. What this test measures is the second arm's marginal cost on a seven-day
     * component week; a clean result here is consistent with that failure, because the week that
     * fails R-3 is a tail draw this five-seed horizon does not contain.
     */
    @Test
    void shouldMeasureWhatTheSecondArmCostsR3() {
        long worstOff = 0;
        long worstGuarded = 0;
        long worstHonest = 0;
        for (long seed : SEEDS) {
            worstOff = Math.max(worstOff, faultFreeWeek(seed, LowerArm.OFF));
            worstGuarded = Math.max(worstGuarded, faultFreeWeek(seed, LowerArm.GUARDED));
            worstHonest = Math.max(worstHonest, faultFreeWeek(seed, LowerArm.HONEST));
        }
        System.out.println(("R-3 fault-free week over %d seeds, worst-case false confirmations per "
                + "six-room week: lower arm OFF = %d, GUARDED = %d, HONEST = %d (R-3 allows 1)")
                .formatted(SEEDS.length, worstOff, worstGuarded, worstHonest));

        assertEquals(0, worstOff, "the one-sided detector's measured false-alarm count moved");
        assertTrue(worstGuarded <= 1,
                "R-3 FAILS with the guarded lower arm: worst seed raised " + worstGuarded);
        assertTrue(worstHonest >= 2,
                "the unguarded lower arm no longer fails R-3; re-measure before believing it");
    }

    private long faultFreeWeek(long seed, LowerArm arm) {
        MonitorHarness harness = MonitorHarness.withLowerArm(seed, arm);
        Occupancy occupancy = new Occupancy(seed);
        for (long minute = 0; minute < Duration.ofDays(7).toMinutes(); minute++) {
            occupancy.apply(harness, harness.now());
            harness.tick();
        }
        long confirmations = harness.events().confirmedCases();
        System.out.println("R-3 fault-free week, seed %d, lower arm %s: confirmations = %d, "
                .formatted(seed, arm, confirmations)
                + "suspicions = " + harness.events().suspicions()
                + ", latched = " + harness.events().latchedZones());
        return confirmations;
    }

    @Test
    void shouldStayHealthyWhileAHealthyNeighboursDamperMoves() {
        MonitorHarness harness = MonitorHarness.standard(7);
        harness.innerLoopRunning(false);
        for (ZoneId zone : Topology.ZONE_IDS) {
            harness.physics().occupyToDesign(zone);
            harness.physics().settle(zone);
        }
        harness.tickFor(Duration.ofHours(1));

        // One frozen damper shifts the split for the other five (ADR-0008's accepted trade-off).
        // Here nothing is frozen: a healthy neighbour simply opens, and the other five must absorb
        // the flow they lose without suspecting anything.
        harness.command(Topology.A145, 1.0, "a healthy neighbour opens");
        harness.tickFor(Duration.ofHours(3));

        for (ZoneId zone : Topology.ZONE_IDS) {
            assertTrue(harness.monitor().stateOf(zone) == MonitorState.HEALTHY,
                    "zone %s reached %s because a healthy neighbour moved"
                            .formatted(zone, harness.monitor().stateOf(zone)));
        }
    }

    /**
     * The occupancy the rooms were designed for in ADR-0008 part 1, as a weekday schedule. Nothing
     * in the monitor sees this — it exists only to make the truth move the way a building does.
     */
    private static final class Occupancy {

        private final Random jitter;
        private final Map<ZoneId, Double> current = new LinkedHashMap<>();

        private Occupancy(long seed) {
            this.jitter = new Random(seed);
        }

        private void apply(MonitorHarness harness, Instant now) {
            var time = now.atZone(ZoneOffset.UTC);
            int hour = time.getHour();
            int day = time.getDayOfWeek().getValue();
            boolean weekday = day <= 5;
            for (ZoneId zone : Topology.ZONE_IDS) {
                double people = weekday ? scheduled(zone, hour, time.getMinute()) : 0;
                Double previous = current.get(zone);
                if (previous == null || Math.abs(previous - people) > 1e-9) {
                    current.put(zone, people);
                    harness.physics().occupy(zone, people);
                }
            }
        }

        private double scheduled(ZoneId zone, int hour, int minute) {
            double design = design(zone);
            if (hour < 8 || hour >= 17) {
                return 0;
            }
            if (hour == 12) {
                return Math.round(design * 0.3);
            }
            if (zone.equals(Topology.A145)) {
                // Booked two-hour slots, empty between: the sharpest occupancy edge in the building.
                return hour % 4 < 2 ? design : 0;
            }
            if (zone.equals(Topology.A115)) {
                // Booked one-hour slots.
                return hour % 2 == 0 ? design : 0;
            }
            if (hour == 8 || hour == 16) {
                return Math.round(design * (minute < 30 ? 0.5 : 1.0));
            }
            return Math.max(0, design - jitter.nextInt(2));
        }

        private double design(ZoneId zone) {
            return ZoneObserver.designOccupancy(Topology.zone(zone));
        }
    }
}
