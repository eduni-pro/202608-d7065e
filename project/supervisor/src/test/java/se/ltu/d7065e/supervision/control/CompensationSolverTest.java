package se.ltu.d7065e.supervision.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import se.ltu.d7065e.ventilation.control.FlowSplit;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * ADR-0008 option B's compensation algebra on its own, where the numbers can be pinned. These are
 * the figures the report quotes for R-7's airflow assertion.
 */
class CompensationSolverTest {

    private static final ZoneId FAULTED = Topology.A145;
    private static final double OCCUPIED_POSITION = 0.6;

    private final CompensationSolver solver = new CompensationSolver();

    @Test
    void shouldHoldEveryHealthyFlowExactlyWhileTheFanHasHeadroom_R7() {
        PlantSnapshot baseline = plant(OCCUPIED_POSITION, 0.15, Topology.NORMAL_FAN_SPEED);

        CompensationPlan plan = solver.solve(baseline, Set.of(FAULTED), 2.0);

        assertEquals(0.5, plan.trim(), 1e-12, "a twofold gain is a trim to one half");
        assertEquals(2.0, plan.achievedFaultedFlowGain(), 1e-12, "the gain is exactly 1/trim");
        assertEquals(0.0, plan.worstHealthyFlowDeviation(), 1e-12,
                "with headroom one fan speed holds all five flows at once");
        assertTrue(plan.meetsRequest());
        assertEquals("", plan.limitation());
        assertEquals(0.8012, plan.fanSpeed().value(), 5e-5);

        Map<ZoneId, Double> before = flows(baseline);
        Map<ZoneId, Double> after = flows(plan.appliedTo(baseline));
        healthy().forEach(zone ->
                assertEquals(before.get(zone), after.get(zone), 1e-15, zone + " must not move"));
        assertEquals(2 * before.get(FAULTED), after.get(FAULTED), 1e-15);
    }

    /**
     * The headline R-2 figure: at the plant's normal speed the fan has enough headroom to take a
     * room frozen at the damper floor all the way back to the design flow ADR-0008 sized it for,
     * with the other five rooms' airflow unchanged. This is the number the report quotes.
     */
    @Test
    void shouldRestoreTheFrozenRoomToItsDesignFlowAtTheNormalFanSpeed_R2() {
        PlantSnapshot baseline = plant(OCCUPIED_POSITION, 0.15, Topology.NORMAL_FAN_SPEED);
        double designLitresPerSecond = baseline.zone(FAULTED).damperAuthority();
        double frozenLitresPerSecond = 1000 * flows(baseline).get(FAULTED);

        CompensationPlan plan = solver.solve(baseline, Set.of(FAULTED),
                designLitresPerSecond / frozenLitresPerSecond);

        assertTrue(plan.meetsRequest(), plan.limitation());
        assertEquals(0.0, plan.worstHealthyFlowDeviation(), 1e-12);
        assertEquals(designLitresPerSecond, 1000 * flows(plan.appliedTo(baseline)).get(FAULTED),
                1e-9, "the frozen room comes back to its design flow");
        assertTrue(plan.fanSpeed().value() <= FanSpeed.MAXIMUM);
    }

    @Test
    void shouldSpendExactlyTheStatedToleranceWhenTheFanIsAlreadyAtNominal_R7() {
        PlantSnapshot baseline = plant(OCCUPIED_POSITION, 0.15, FanSpeed.NOMINAL);

        CompensationPlan plan = solver.solve(baseline, Set.of(FAULTED), 4.0);

        assertEquals(FanSpeed.NOMINAL, plan.fanSpeed(), "there is nothing left to raise");
        assertEquals(CompensationSolver.HEALTHY_FLOW_TOLERANCE,
                plan.worstHealthyFlowDeviation(), 1e-12,
                "a saturated fan turns compensation into redistribution, bounded by the tolerance");
        assertEquals(1.2960, plan.achievedFaultedFlowGain(), 5e-4);
        assertFalse(plan.meetsRequest());
        assertTrue(plan.limitation().contains("ceiling"), plan.limitation());

        Map<ZoneId, Double> before = flows(baseline);
        Map<ZoneId, Double> after = flows(plan.appliedTo(baseline));
        healthy().forEach(zone -> assertEquals(1 - CompensationSolver.HEALTHY_FLOW_TOLERANCE,
                after.get(zone) / before.get(zone), 1e-12,
                zone + " gives up exactly the tolerance and no more"));
    }

    @Test
    void shouldRefuseToCompensateADamperWithNoAirPath_R2Bound() {
        VentilationZone template = Topology.zone(FAULTED);
        VentilationZone shut = new VentilationZone(template.id(), template.volumeCubicMetres(),
                template.floorAreaSquareMetres(), template.ahuId(), template.damperAuthority(),
                DamperPosition.SHUT, ControlMode.NORMAL, DamperPosition.SHUT);
        PlantSnapshot baseline = new PlantSnapshot(
                Topology.zones().stream()
                        .map(zone -> zone.id().equals(FAULTED) ? shut
                                : zone.withSupervisoryCommand(DamperPosition.of(OCCUPIED_POSITION)))
                        .toList(),
                Topology.airHandler());

        CompensationPlan plan = solver.solve(baseline, Set.of(FAULTED), 4.0);

        assertFalse(plan.compensates());
        assertEquals(1.0, plan.trim(), 1e-12);
        assertEquals(Topology.NORMAL_FAN_SPEED, plan.fanSpeed(),
                "nothing is commanded for an unreachable room");
        assertTrue(plan.trimmedPositions().isEmpty());
        assertTrue(plan.limitation().contains("no air path"), plan.limitation());
    }

    /**
     * The trim is gone and the fan is the only lever left. It is not a selective one — with
     * nothing trimmed the split's denominator has not moved, so the frozen room's gain is exactly
     * the healthy rooms' over-supply — so what it may buy is one tolerance and no more, whatever
     * headroom the plant still has. The assertion this case replaces said a plan here buys
     * nothing at all, which was a property of the trim lever and not of the plant.
     */
    @Test
    void shouldSpendFanHeadroomWhenEveryHealthyDamperIsAlreadyAtItsFloor_R2() {
        PlantSnapshot baseline = new PlantSnapshot(Topology.zones(), Topology.airHandler());
        double tolerance = CompensationSolver.HEALTHY_FLOW_TOLERANCE;

        CompensationPlan plan = solver.solve(baseline, Set.of(FAULTED), 4.0);

        assertTrue(plan.compensates(), "fan speed reaches a room no trim can reach");
        assertEquals(1.0, plan.trim(), 1e-12, "there is no trim to make");
        assertTrue(plan.trimmedPositions().isEmpty(),
                "and no healthy damper is taken off the inner loop to hold it where it already is");
        assertEquals(Topology.NORMAL_FAN_SPEED.value() * (1 + tolerance),
                plan.fanSpeed().value(), 1e-12);
        assertEquals(1 + tolerance, plan.achievedFaultedFlowGain(), 1e-12,
                "with no trim the gain is the speed ratio");
        assertEquals(tolerance, plan.worstHealthyFlowDeviation(), 1e-12,
                "which the healthy rooms pay for in over-supply, not in loss");
        assertFalse(plan.meetsRequest());
        assertTrue(plan.limitation().contains("minimum position"), plan.limitation());
        assertTrue(plan.limitation().contains("%.0f%%".formatted(tolerance * 100)),
                "the plan must say what stopped it: " + plan.limitation());

        Map<ZoneId, Double> before = flows(baseline);
        Map<ZoneId, Double> after = flows(plan.appliedTo(baseline));
        assertEquals(1 + tolerance, after.get(FAULTED) / before.get(FAULTED), 1e-12,
                "the frozen room really does get the air");
        healthy().forEach(zone -> assertEquals(1 + tolerance, after.get(zone) / before.get(zone),
                1e-12, zone + " is over-ventilated by exactly the same factor and no more"));
    }

    /**
     * Every zone faulted. There is no healthy room to disturb, so the tolerance has nothing to
     * protect and the fan runs to the plant's ceiling — which is also the one case where the
     * whole 43 % of ADR-0008's reserved headroom is spendable.
     */
    @Test
    void shouldRunTheFanToItsCeilingWhenEveryZoneIsFaulted_R2() {
        PlantSnapshot baseline = new PlantSnapshot(Topology.zones(), Topology.airHandler());

        CompensationPlan plan = solver.solve(baseline, Set.copyOf(Topology.ZONE_IDS), 4.0);

        assertEquals(FanSpeed.MAXIMUM, plan.fanSpeed().value(), 1e-12);
        assertEquals(FanSpeed.MAXIMUM / Topology.NORMAL_FAN_SPEED.value(),
                plan.achievedFaultedFlowGain(), 1e-12);
        assertEquals(0.0, plan.worstHealthyFlowDeviation(), 1e-12,
                "there is no healthy room whose airflow could have moved");
        assertTrue(plan.trimmedPositions().isEmpty());
        assertFalse(plan.meetsRequest(), "1.43 is not the 4.0 that was asked for");
        assertTrue(plan.limitation().contains("every zone is faulted"), plan.limitation());
        assertTrue(plan.limitation().contains("ceiling"), plan.limitation());

        Map<ZoneId, Double> before = flows(baseline);
        Map<ZoneId, Double> after = flows(plan.appliedTo(baseline));
        Topology.ZONE_IDS.forEach(zone -> assertEquals(plan.achievedFaultedFlowGain(),
                after.get(zone) / before.get(zone), 1e-12, zone + " gains the speed ratio"));
    }

    /**
     * The trim is short of the request rather than absent: the solver takes the gain the trim can
     * hold for free and then spends fan headroom on top of it, instead of stopping at the
     * preserving speed with headroom unspent.
     */
    @Test
    void shouldExtendATrimThatReachedItsFloorWithFanHeadroom_R2() {
        PlantSnapshot baseline = plant(0.30, 0.15, Topology.NORMAL_FAN_SPEED);
        double tolerance = CompensationSolver.HEALTHY_FLOW_TOLERANCE;
        CompensationPlan free = solver.solve(baseline, Set.of(FAULTED), 2.0);

        CompensationPlan plan = solver.solve(baseline, Set.of(FAULTED), 4.0);

        assertEquals(0.5, plan.trim(), 1e-12, "the trim floor is 0.15/0.30");
        assertEquals(2.0, free.achievedFaultedFlowGain(), 1e-12,
                "the trim alone is worth a twofold gain, at no cost to the healthy rooms");
        assertEquals(0.0, free.worstHealthyFlowDeviation(), 1e-12);
        assertEquals(2.0 * (1 + tolerance), plan.achievedFaultedFlowGain(), 1e-12,
                "and the fan adds one tolerance on top of it");
        assertEquals(tolerance, plan.worstHealthyFlowDeviation(), 1e-12);
        assertTrue(plan.fanSpeed().value() > free.fanSpeed().value(),
                "the headroom the preserving speed left unspent is what paid for it");
        assertTrue(plan.fanSpeed().value() <= FanSpeed.MAXIMUM);

        Map<ZoneId, Double> before = flows(baseline);
        assertTrue(flows(plan.appliedTo(baseline)).get(FAULTED)
                        > flows(free.appliedTo(baseline)).get(FAULTED),
                "the faulted room's flow must actually be higher for the extra speed");
        assertEquals(2.0 * (1 + tolerance),
                flows(plan.appliedTo(baseline)).get(FAULTED) / before.get(FAULTED), 1e-12);
    }

    /**
     * The bound R-7 rests on, swept rather than argued: whichever lever the solver reaches for,
     * no healthy room's own airflow moves by more than the stated tolerance — in either
     * direction, since the trim takes air from them and the fan gives it.
     */
    @Test
    void shouldNeverMoveAHealthyRoomsAirflowBeyondTheTolerance_R7() {
        double tolerance = CompensationSolver.HEALTHY_FLOW_TOLERANCE;
        for (double baselineSpeed : new double[] {0.2, 0.5, 0.7, 0.9, 1.0}) {
            for (double healthyPosition : new double[] {0.15, 0.3, 0.6, 1.0}) {
                for (double requested : new double[] {1.0, 1.05, 2.0, 4.0, 1e9}) {
                    for (ZoneId faulted : Topology.ZONE_IDS) {
                        PlantSnapshot baseline = new PlantSnapshot(
                                Topology.zones().stream()
                                        .map(zone -> zone.withSupervisoryCommand(
                                                DamperPosition.of(zone.id().equals(faulted)
                                                        ? 0.15 : healthyPosition)))
                                        .toList(),
                                Topology.airHandler().atSpeed(FanSpeed.of(baselineSpeed)));

                        CompensationPlan plan = solver.solve(baseline, Set.of(faulted), requested);

                        String where = "%s at baseline %s, healthy %s, requesting %s".formatted(
                                faulted, baselineSpeed, healthyPosition, requested);
                        assertTrue(plan.worstHealthyFlowDeviation() <= tolerance + 1e-9,
                                "plan claims %.4f for %s".formatted(
                                        plan.worstHealthyFlowDeviation(), where));
                        Map<ZoneId, Double> before = flows(baseline);
                        Map<ZoneId, Double> after = flows(plan.appliedTo(baseline));
                        for (ZoneId zone : Topology.ZONE_IDS) {
                            if (zone.equals(faulted)) {
                                continue;
                            }
                            assertEquals(1.0, after.get(zone) / before.get(zone), tolerance + 1e-9,
                                    "%s moved outside the tolerance for %s".formatted(zone, where));
                        }
                        assertTrue(plan.achievedFaultedFlowGain() >= 1 - 1e-12,
                                "compensation may not take air out of the faulted room: " + where);
                    }
                }
            }
        }
    }

    @Test
    void shouldNeverReturnAFanSpeedOutsideThePlantRange() {
        for (double baselineSpeed : new double[] {0.2, 0.5, 0.7, 0.8, 1.0}) {
            for (double requested : new double[] {0.0, 1.0, 1.5, 4.0, 1e9}) {
                for (ZoneId faulted : Topology.ZONE_IDS) {
                    PlantSnapshot baseline = new PlantSnapshot(
                            Topology.zones().stream()
                                    .map(zone -> zone.withSupervisoryCommand(
                                            DamperPosition.of(zone.id().equals(faulted)
                                                    ? 0.15 : OCCUPIED_POSITION)))
                                    .toList(),
                            Topology.airHandler().atSpeed(FanSpeed.of(baselineSpeed)));

                    double speed = solver.solve(baseline, Set.of(faulted), requested)
                            .fanSpeed().value();

                    assertTrue(speed >= FanSpeed.MINIMUM && speed <= FanSpeed.MAXIMUM,
                            "speed %s for %s at baseline %s requesting %s"
                                    .formatted(speed, faulted, baselineSpeed, requested));
                }
            }
        }
    }

    /**
     * The two sides of the healthy-room constraint are different quantities and the solver keeps
     * them apart: a trim takes air from a healthy room and can cost it comfort, a fan raise gives
     * it air and costs only energy. Raising only the over-supply side must buy the frozen room
     * more air and must still never starve a healthy room past the comfort-bearing side.
     */
    @Test
    void shouldBoundStarvingAndOverSupplySeparately_R7() {
        PlantSnapshot baseline = new PlantSnapshot(Topology.zones(), Topology.airHandler());
        double starve = CompensationSolver.HEALTHY_FLOW_TOLERANCE;
        CompensationSolver generous = new CompensationSolver(starve, 0.30);

        CompensationPlan plan = generous.solve(baseline, Set.of(FAULTED), 4.0);

        assertEquals(1.0, plan.trim(), 1e-12, "every healthy damper is already at its floor");
        assertEquals(1.30, plan.achievedFaultedFlowGain(), 1e-12,
                "with no trim the frozen room's gain is the speed ratio the over-supply allows");
        assertEquals(0.30, plan.worstHealthyFlowDeviation(), 1e-12);
        Map<ZoneId, Double> before = flows(baseline);
        Map<ZoneId, Double> after = flows(plan.appliedTo(baseline));
        healthy().forEach(zone -> assertTrue(after.get(zone) >= before.get(zone) * (1 - starve),
                zone + " must never be starved beyond the comfort-bearing side"));
    }

    /**
     * The measurement that decides R-2's design-occupancy case, asserted rather than recounted.
     * With no healthy damper left to trim the fan lever is not selective, so the frozen room's
     * gain <em>is</em> the speed ratio, and the speed ratio is capped by the plant at
     * {@code MAXIMUM / NORMAL_FAN_SPEED} = 1.43. No setting of the over-supply allowance can
     * exceed it, which is why loosening that allowance cannot buy a regime that needs 2.0 to 3.3.
     */
    @Test
    void shouldNotExceedThePlantsFanCeilingHoweverLargeTheOverSupplyAllowance_R2() {
        PlantSnapshot baseline = new PlantSnapshot(Topology.zones(), Topology.airHandler());
        double ceiling = FanSpeed.MAXIMUM / Topology.NORMAL_FAN_SPEED.value();

        for (double oversupply : new double[] {0.05, 0.10, 0.20, 0.43, 0.75, 0.99}) {
            CompensationPlan plan = new CompensationSolver(
                    CompensationSolver.HEALTHY_FLOW_TOLERANCE, oversupply)
                    .solve(baseline, Set.of(FAULTED), 4.0);

            assertTrue(plan.achievedFaultedFlowGain() <= ceiling + 1e-12,
                    "gain %.4f at an over-supply allowance of %s exceeds the plant's own ceiling"
                            .formatted(plan.achievedFaultedFlowGain(), oversupply));
            assertEquals(Math.min(1 + oversupply, ceiling), plan.achievedFaultedFlowGain(), 1e-12,
                    "the gain is the allowance until the fan runs out, and the fan then decides");
        }
    }

    @Test
    void shouldSizeTheGainFromTheMeasuredConcentration_R2() {
        double outdoor = Topology.OUTDOOR.value();
        assertEquals(1.0, CompensationSolver.gainForComfort(800, 900, outdoor), 1e-12,
                "a comfortable room buys nothing by disturbing the other five");
        assertEquals(2.0, CompensationSolver.gainForComfort(1380, 900, outdoor), 1e-12,
                "twice the excess over outdoor needs twice the airflow");
    }

    @Test
    void shouldSplitTheWholeFanFlowAcrossTheSixRooms() {
        PlantSnapshot baseline = plant(OCCUPIED_POSITION, 0.15, FanSpeed.of(0.8));

        double delivered = flows(baseline).values().stream().mapToDouble(Double::doubleValue).sum();

        assertEquals(Topology.NOMINAL_FLOW.cubicMetresPerSecond() * 0.8, delivered, 1e-12,
                "the split conserves the fan's flow, which is why one damper closing moves the rest");
    }

    private static PlantSnapshot plant(
            double healthyPosition, double faultedPosition, FanSpeed baselineSpeed) {
        List<VentilationZone> zones = Topology.zones().stream()
                .map(zone -> zone.withSupervisoryCommand(DamperPosition.of(
                        zone.id().equals(FAULTED) ? faultedPosition : healthyPosition)))
                .toList();
        return new PlantSnapshot(zones, Topology.airHandler().atSpeed(baselineSpeed));
    }

    private static List<ZoneId> healthy() {
        return Topology.ZONE_IDS.stream().filter(zone -> !zone.equals(FAULTED)).toList();
    }

    private static Map<ZoneId, Double> flows(PlantSnapshot snapshot) {
        return FlowSplit.flowPerZone(snapshot.zones(), snapshot.airHandler(), 0.0);
    }
}
