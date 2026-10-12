package se.ltu.d7065e.evaluation.control;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.ltu.d7065e.diagnosis.control.LowerArm;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.evaluation.boundary.ResultFiles;
import se.ltu.d7065e.evaluation.entity.RunTrace;
import se.ltu.d7065e.physics.control.Co2MassBalance;
import se.ltu.d7065e.ventilation.control.FlowSplit;
import se.ltu.d7065e.physics.control.OccupancyScheduler;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.supervision.control.CompensationPlan;
import se.ltu.d7065e.supervision.control.CompensationSolver;
import se.ltu.d7065e.supervision.control.PlantSnapshot;
import se.ltu.d7065e.ventilation.entity.AirflowRate;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-26 — comfort in a faulted room, with and without the supervisory loop (R-2).
 *
 * <p>The same seed is run three times per occupancy regime: with no fault at all, with the fault
 * and {@code supervisor} dead, and with the fault and {@code supervisor} live. The three arms share
 * their occupancy draw, their sensor noise and their inner loop, so the gap between the last two is
 * the value of the supervisory loop and the first one says whether the plant could have held the
 * limit anyway. Reporting the supervised arm alone would hide both.
 *
 * <p>Two occupancy regimes, because they put the supervisor in genuinely different positions and
 * averaging them would conceal the mechanism:
 * <ul>
 * <li><b>Drawn weekday</b> — {@link OccupancyScheduler}'s plausible day. Early in the morning most
 *     rooms are under the controller's 800 ppm opening threshold and their dampers sit at the 0.15
 *     floor, so there is nothing to trim: ADR-0008 option B compensates by <em>closing</em> healthy
 *     dampers, and a damper already at its minimum cannot be closed further. Fan speed is then the
 *     only lever the solver has left, and with no trim it is not a selective one: it lifts the
 *     five healthy rooms by whatever it lifts the faulted room, so it may be spent only up to the
 *     healthy-flow tolerance. A few per cent is not what decides this regime — the fault costs no
 *     comfort here at all — which is why the plan column reads as a no-op by the end of the day,
 *     when the room is empty and asks for a gain of one.
 * <li><b>Design occupancy</b> — every room at the head count ADR-0008 sized its damper authority
 *     from, which is the regime the compensation mechanism was designed against: every damper is
 *     open, so the trim has travel and the fan has somewhere to put the air.
 * </ul>
 *
 * <p>Two fault cases, reported separately and never averaged, because ADR-0008 says they are
 * different problems:
 * <ul>
 * <li><b>Stuck at the demand-controlled floor, 0.15.</b> The room still has an air path, so the
 *     shared split can be pushed towards it. This is the case R-2 is a claim about.
 * <li><b>Genuinely shut, opening 0.</b> No air path exists, so no fan speed and no trim can
 *     multiply a flow of zero. R-2 does not cover it and the system's answer is a work order.
 * </ul>
 */
public final class ComfortExperiment {

    private static final ZoneId FAULTED = Topology.A124;
    private static final long[] SEEDS = {1, 2, 3, 4, 5};
    private static final Duration WARMUP = Duration.ofHours(8);
    private static final Duration FAULT_DAY = Duration.ofHours(12);
    private static final double COMFORT_LIMIT = Topology.COMFORT_LIMIT.value();
    private static final double REQUIRED_FRACTION = 0.95;

    private static final String DRAWN = "drawn weekday";
    private static final String DRAWN_NO_LOWER_ARM = "drawn weekday, lower arm OFF";
    private static final String DRAWN_NO_FEEDBACK = "drawn weekday, no damper feedback";
    private static final String DESIGN = "design occupancy";
    private static final List<String> REGIMES =
            List.of(DRAWN, DRAWN_NO_LOWER_ARM, DRAWN_NO_FEEDBACK, DESIGN);
    private static final String NO_FAULT = "no fault";
    private static final String UNSUPERVISED = "fault, no supervisor";
    private static final String SUPERVISED = "fault, supervisor";

    private ComfortExperiment() {
    }

    public static String run(ResultFiles files) {
        List<Arm> arms = new ArrayList<>();
        for (String regime : REGIMES) {
            for (long seed : SEEDS) {
                arms.add(reachable(regime, seed, NO_FAULT));
                arms.add(reachable(regime, seed, UNSUPERVISED));
                arms.add(reachable(regime, seed, SUPERVISED));
            }
        }
        List<Arm> shut = new ArrayList<>();
        for (long seed : SEEDS) {
            shut.add(shutCase(seed));
        }

        try (ResultFiles.Csv csv = files.csv("T-26-comfort.csv",
                "case", "regime", "arm", "seed", "zone", "onset_minute", "occupied_minutes",
                "comfort_fraction", "max_ppm_while_occupied", "mean_ppm_while_occupied",
                "monitor_state", "latched_minute", "plan_trim", "plan_fan_speed",
                "plan_achieved_gain", "plan_worst_healthy_flow_deviation", "plan_limitation",
                "other_rooms_latched_and_frozen_at")) {
            for (Arm arm : arms) {
                writeRow(csv, arm);
            }
            for (Arm arm : shut) {
                writeRow(csv, arm);
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("T-26 — comfort during a fault, with and without compensation (R-2)\n")
                .append("  room %s, stuck damper frozen at %s, seeds %s, %s warm-up then %s%n"
                        .formatted(FAULTED, Topology.MINIMUM_POSITION,
                                java.util.Arrays.toString(SEEDS), WARMUP, FAULT_DAY))
                .append("  the fraction is of occupied minutes after onset whose true CO2 is at or "
                        + "below %.0f ppm%n".formatted(COMFORT_LIMIT));

        for (String regime : REGIMES) {
            out.append("\n  case A, %s — frozen at the demand-controlled floor (the case R-2 "
                    .formatted(regime) + "covers)\n");
            out.append(table(arms.stream().filter(a -> a.regime().equals(regime)).toList()));
            double reference = mean(arms, regime, NO_FAULT);
            double without = mean(arms, regime, UNSUPERVISED);
            double with = mean(arms, regime, SUPERVISED);
            out.append("  mean comfort fraction: no fault %.3f, fault without supervisor %.3f, "
                            .formatted(reference, without)
                            + "fault with supervisor %.3f%n".formatted(with))
                    .append("  the supervisory loop is worth %+.3f of occupied minutes here; the "
                            .formatted(with - without)
                            + "fault itself costs %+.3f against no fault%n".formatted(
                                    without - reference))
                    .append("  R-2 (>= %.0f %% of occupied minutes at or below %.0f ppm), "
                            .formatted(REQUIRED_FRACTION * 100, COMFORT_LIMIT)
                            + "supervised arm: %s%n".formatted(verdict(arms, regime, SUPERVISED)))
                    .append("  for reference, the same criterion on the no-fault arm: %s%n"
                            .formatted(verdict(arms, regime, NO_FAULT)));
        }

        out.append(reachabilityBound());

        out.append("\n  case B — genuinely shut, opening 0 (the case R-2 does not cover)\n");
        out.append(table(shut));
        out.append("  mean comfort fraction with no air path: %.3f%n"
                        .formatted(shut.stream().mapToDouble(Arm::comfortFraction).average()
                                .orElse(Double.NaN)))
                .append("  the real solver's answer on a shut damper: %s%n"
                        .formatted(shutPlan().limitation()))
                .append("  compensates() = %s, achieved gain = %.3f, so a work order and occupancy "
                        .formatted(shutPlan().compensates(),
                                shutPlan().achievedFaultedFlowGain())
                        + "advice is all the design offers\n")
                .append("  the real flow split with %s shut, fan at %s: %s%n"
                        .formatted(FAULTED, Topology.NORMAL_FAN_SPEED, shutFlowsAsText()))
                .append("  NOTE: BuildingSimulation cannot express this case — a stuck-damper fault "
                        + "at position 0 is clamped back to the room's 0.15 minimum by "
                        + "VentilationZone's invariant, which is ADR-0008's own decision. Case B is "
                        + "therefore integrated from the same Co2MassBalance and FlowSplit the "
                        + "simulator uses, at the same 1 s step and on the same occupancy draw, "
                        + "with the airflow a shut damper actually receives: zero.\n");
        return out.toString();
    }

    private static void writeRow(ResultFiles.Csv csv, Arm arm) {
        csv.row(arm.caseName(), arm.regime(), arm.arm(), arm.seed(), arm.zone().value(),
                arm.onset(), arm.occupiedMinutes(), arm.comfortFraction(), arm.maxPpm(),
                arm.meanPpm(), arm.monitorState(), arm.latchedMinute(), arm.trim(),
                arm.fanSpeed(), arm.achievedGain(), arm.worstHealthyDeviation(),
                arm.limitation(), arm.otherRoomsLatched());
    }

    private static String table(List<Arm> arms) {
        List<List<String>> rows = new ArrayList<>();
        for (Arm arm : arms) {
            rows.add(List.of(String.valueOf(arm.seed()), arm.arm(),
                    String.valueOf(arm.occupiedMinutes()),
                    "%.3f".formatted(arm.comfortFraction()),
                    "%.0f".formatted(arm.maxPpm()), "%.0f".formatted(arm.meanPpm()),
                    arm.monitorState(), String.valueOf(arm.latchedMinute()),
                    arm.trim() == null ? "-" : "%.2f".formatted(arm.trim()),
                    arm.fanSpeed() == null ? "-" : "%.2f".formatted(arm.fanSpeed()),
                    arm.achievedGain() == null ? "-" : "%.2f".formatted(arm.achievedGain()),
                    arm.worstHealthyDeviation() == null ? "-"
                            : "%.4f".formatted(arm.worstHealthyDeviation()),
                    arm.otherRoomsLatched(),
                    arm.limitation() == null || arm.limitation().isBlank() ? "-"
                            : arm.limitation().substring(0, Math.min(36, arm.limitation().length()))));
        }
        return ResultFiles.table(List.of("seed", "arm", "occ min", "<=1000 ppm", "max", "mean",
                "state", "latched", "trim", "fan", "gain", "healthy dev",
                "other rooms latched, frozen at", "limitation"), rows);
    }

    private static double mean(List<Arm> arms, String regime, String arm) {
        return arms.stream().filter(a -> a.regime().equals(regime) && a.arm().equals(arm))
                .mapToDouble(Arm::comfortFraction).average().orElse(Double.NaN);
    }

    private static String verdict(List<Arm> arms, String regime, String arm) {
        List<Arm> group = arms.stream()
                .filter(a -> a.regime().equals(regime) && a.arm().equals(arm)).toList();
        long met = group.stream().filter(a -> a.comfortFraction() >= REQUIRED_FRACTION).count();
        return "%d of %d seeds met it -> %s"
                .formatted(met, group.size(), met == group.size() ? "pass" : "FAIL");
    }

    /** The reachable case, run end to end through the real plant, controller, monitor and supervisor. */
    private static Arm reachable(String regime, long seed, String arm) {
        MonitorParameters parameters = DRAWN_NO_LOWER_ARM.equals(regime)
                ? MonitorParameters.defaults().withLowerArm(LowerArm.OFF)
                : MonitorParameters.defaults();
        SystemHarness harness = DESIGN.equals(regime)
                ? new SystemHarness(seed, parameters, new DesignOccupancy())
                : new SystemHarness(seed, parameters);
        harness.supervisorRunning(SUPERVISED.equals(arm));
        harness.damperFeedback(!DRAWN_NO_FEEDBACK.equals(regime));
        int onset = (int) WARMUP.toMinutes();
        RunTrace trace = new RunTrace(harness.now(), onset + (int) FAULT_DAY.toMinutes() + 1);
        for (int minute = 0; minute < onset; minute++) {
            trace.append(harness.tick());
        }
        if (!NO_FAULT.equals(arm)) {
            harness.inject(InjectedFault.stuckDamper(
                    FAULTED, Topology.MINIMUM_POSITION, harness.now()));
        }

        int latched = -1;
        for (int minute = 1; minute <= FAULT_DAY.toMinutes(); minute++) {
            trace.append(harness.tick());
            if (latched < 0 && harness.monitor().stateOf(FAULTED).isLatched()) {
                latched = onset + minute;
            }
        }
        CompensationPlan plan = harness.supervisor().currentPlan();
        List<String> otherLatched = new ArrayList<>();
        for (ZoneId zone : Topology.ZONE_IDS) {
            if (!zone.equals(FAULTED) && harness.monitor().stateOf(zone).isLatched()) {
                otherLatched.add("%s@%.2f".formatted(zone.value(),
                        harness.latest().appliedPosition(zone).value()));
            }
        }
        return new Arm("frozen at " + Topology.MINIMUM_POSITION, regime, arm, seed, FAULTED, onset,
                trace.occupiedMinutes(FAULTED, onset),
                trace.comfortFraction(FAULTED, onset, COMFORT_LIMIT),
                trace.maxWhileOccupied(FAULTED, onset), meanWhileOccupied(trace, onset),
                harness.monitor().stateOf(FAULTED).name(), latched,
                plan == null ? null : plan.trim(),
                plan == null ? null : plan.fanSpeed().value(),
                plan == null ? null : plan.achievedFaultedFlowGain(),
                plan == null ? null : plan.worstHealthyFlowDeviation(),
                plan == null ? (SUPERVISED.equals(arm) ? "no plan was reached" : "no supervisor")
                        : plan.limitation(),
                otherLatched.isEmpty() ? "none" : String.join(" ", otherLatched));
    }

    /**
     * What the plant can reach at design occupancy, which is the question R-2's failure there
     * turns on and the one the comfort fraction alone cannot answer.
     *
     * <p>The bound is deliberately more generous than any plan this supervisor can produce: every
     * room the monitor is <em>not</em> holding latched is driven to its own minimum position —
     * per room, not by the one common factor {@link CompensationSolver} applies — and the fan goes
     * to {@link FanSpeed#MAXIMUM}. That is the largest share of the shared split {@link FlowSplit}
     * can route into the faulted room, so no trim shape and no setting of the healthy rooms'
     * over-supply allowance can beat it. Its steady concentration is therefore the plant's own
     * verdict on R-2 for that seed.
     *
     * <p>The reachability column asks for <em>every</em> room under the limit, not only the
     * faulted one, because reaching the faulted room by starving a healthy one is not reaching it.
     * The two flow columns are reported separately for the same reason: a per-room trim takes air
     * from the open healthy rooms as well as giving it to the nearly-closed ones, so this bound is
     * not the pure energy-for-comfort trade that one common trim plus a fan raise is.
     */
    private static String reachabilityBound() {
        List<List<String>> rows = new ArrayList<>();
        int reachable = 0;
        for (long seed : SEEDS) {
            SystemHarness harness = new SystemHarness(
                    seed, MonitorParameters.defaults(), new DesignOccupancy());
            harness.supervisorRunning(true);
            harness.tickFor(WARMUP);
            harness.inject(InjectedFault.stuckDamper(
                    FAULTED, Topology.MINIMUM_POSITION, harness.now()));
            harness.tickFor(FAULT_DAY);

            List<ZoneId> frozen = Topology.ZONE_IDS.stream()
                    .filter(zone -> zone.equals(FAULTED)
                            || harness.monitor().stateOf(zone).isLatched())
                    .toList();
            List<VentilationZone> best = Topology.zones().stream()
                    .map(zone -> zone.withSupervisoryCommand(frozen.contains(zone.id())
                            ? harness.latest().appliedPosition(zone.id())
                            : zone.minimumPosition()))
                    .toList();
            Map<ZoneId, Double> atBound = FlowSplit.flowPerZone(
                    best, Topology.airHandler().atSpeed(FanSpeed.of(FanSpeed.MAXIMUM)), 0.0);
            // Both directions, because they are different harms: surplus air costs energy and a
            // starved healthy room costs comfort, which is the half R-7 is really protecting.
            double mostGiven = 0;
            double mostTaken = 0;
            double worstHealthyPpm = 0;
            for (VentilationZone zone : Topology.zones()) {
                if (!frozen.contains(zone.id())) {
                    double change = atBound.get(zone.id())
                            / harness.latest().flow(zone.id()).cubicMetresPerSecond() - 1;
                    mostGiven = Math.max(mostGiven, change);
                    mostTaken = Math.min(mostTaken, change);
                    worstHealthyPpm = Math.max(worstHealthyPpm, Co2MassBalance.steadyStatePpm(
                            DesignOccupancy.designOccupancy(zone), atBound.get(zone.id()),
                            Topology.OUTDOOR.value()));
                }
            }
            double steady = Co2MassBalance.steadyStatePpm(
                    DesignOccupancy.designOccupancy(Topology.zone(FAULTED)),
                    atBound.get(FAULTED), Topology.OUTDOOR.value());
            CompensationPlan plan = harness.supervisor().currentPlan();
            // Reaching the faulted room by starving a healthy one is not reaching it: the bound
            // only counts where every healthy room is also still under the limit.
            boolean met = steady <= COMFORT_LIMIT && worstHealthyPpm <= COMFORT_LIMIT;
            reachable += met ? 1 : 0;
            rows.add(List.of(String.valueOf(seed), String.valueOf(frozen.size()),
                    String.valueOf(Topology.ZONE_IDS.size() - frozen.size()),
                    plan == null ? "no plan"
                            : "%.2f".formatted(plan.requestedFaultedFlowGain()),
                    plan == null ? "-" : "%.2f".formatted(plan.achievedFaultedFlowGain()),
                    "%.1f".formatted(1000 * harness.latest().flow(FAULTED).cubicMetresPerSecond()),
                    "%.1f".formatted(1000 * atBound.get(FAULTED)),
                    "%.2f".formatted(atBound.get(FAULTED)
                            / harness.latest().flow(FAULTED).cubicMetresPerSecond()),
                    "%.0f".formatted(steady),
                    "%+.0f%%".formatted(mostGiven * 100),
                    "%+.0f%%".formatted(mostTaken * 100),
                    worstHealthyPpm == 0 ? "-" : "%.0f".formatted(worstHealthyPpm),
                    met ? "yes" : "NO"));
        }
        return "\n  case A, design occupancy — the plant's own bound on R-2, and its price (R-7)\n"
                + "  every room the monitor is not holding latched driven to its own minimum "
                + "position, per room rather than by\n  one common trim, and the fan at its "
                + "ceiling %s: the most air the shared split can route into %s%n"
                        .formatted(FanSpeed.MAXIMUM, FAULTED)
                + ResultFiles.table(List.of("seed", "frozen", "healthy", "gain asked",
                        "gain got", "A124 l/s now", "at the bound", "bound gain", "steady ppm",
                        "most given a healthy room", "most taken", "worst healthy ppm",
                        "R-2 reachable"), rows)
                + "  the bound holds the faulted room AND every healthy room under %.0f ppm on "
                        .formatted(COMFORT_LIMIT)
                + "%d of %d seeds, so R-2's design-\n  occupancy case is not reachable by any trim "
                        .formatted(reachable, SEEDS.length)
                + "shape and any over-supply allowance on %d of them. Where it is\n  reachable it "
                        .formatted(SEEDS.length - reachable)
                + "moves a healthy room's own airflow by far more than the %.0f %% R-7 is asserted "
                        .formatted(CompensationSolver.HEALTHY_FLOW_TOLERANCE * 100)
                + "against, and\n  not only upwards: a per-room trim takes air from the open rooms "
                + "as well as giving it to the closed\n  ones, so the bound is not the pure "
                + "energy-for-comfort trade it looks like. A frozen room with no healthy\n  "
                + "neighbour left has only the fan, and the fan is capped at %s/%s = %.2f however "
                        .formatted(FanSpeed.MAXIMUM, Topology.NORMAL_FAN_SPEED,
                                FanSpeed.MAXIMUM / Topology.NORMAL_FAN_SPEED.value())
                + "the healthy rooms'\n  allowance is set.\n";
    }

    /**
     * The unreachable case. {@code BuildingSimulation} cannot express it: a stuck-damper fault at
     * position 0 is clamped back to the room's minimum by {@link VentilationZone}'s invariant, which
     * is exactly the design decision ADR-0008 took and is recorded as a finding rather than worked
     * around. So the room's balance is integrated here from the same {@link Co2MassBalance} the
     * simulator integrates, at the same one-second step, driven by the same {@link OccupancyScheduler}
     * draw, with the airflow {@link FlowSplit} gives a shut damper: zero.
     */
    private static Arm shutCase(long seed) {
        VentilationZone room = Topology.zone(FAULTED);
        OccupancyScheduler occupancy = new OccupancyScheduler(seed);
        Instant start = SystemHarness.START.plus(WARMUP);
        double ppm = Topology.OUTDOOR.value();
        int occupied = 0;
        int comfortable = 0;
        double peak = 0;
        double total = 0;
        double flow = shutFlows().get(FAULTED).cubicMetresPerSecond();
        for (long minute = 0; minute < FAULT_DAY.toMinutes(); minute++) {
            Instant at = start.plusSeconds(minute * 60);
            int people = occupancy.headCount(FAULTED, at);
            for (int second = 0; second < 60; second++) {
                ppm = Co2MassBalance.nextPpm(ppm, room.volumeCubicMetres(), people, flow,
                        Topology.OUTDOOR.value(), 1.0);
            }
            if (people > 0) {
                occupied++;
                total += ppm;
                peak = Math.max(peak, ppm);
                if (ppm <= COMFORT_LIMIT) {
                    comfortable++;
                }
            }
        }
        return new Arm("genuinely shut, opening 0", DRAWN, "no air path", seed, FAULTED,
                (int) WARMUP.toMinutes(), occupied,
                occupied == 0 ? Double.NaN : (double) comfortable / occupied, peak,
                occupied == 0 ? Double.NaN : total / occupied, "n/a", -1, null, null,
                shutPlan().achievedFaultedFlowGain(), shutPlan().worstHealthyFlowDeviation(),
                shutPlan().limitation(), "none");
    }

    /** The plan the real solver produces for a faulted damper with no opening at all. */
    private static CompensationPlan shutPlan() {
        return new CompensationSolver().solve(shutSnapshot(), Set.of(FAULTED),
                CompensationSolver.MAXIMUM_REQUESTED_GAIN);
    }

    /** The six rooms with {@link #FAULTED} shut; a zone may hold 0 only if its minimum is 0. */
    private static PlantSnapshot shutSnapshot() {
        List<VentilationZone> zones = new ArrayList<>();
        for (VentilationZone zone : Topology.zones()) {
            zones.add(zone.id().equals(FAULTED)
                    ? new VentilationZone(zone.id(), zone.volumeCubicMetres(),
                            zone.floorAreaSquareMetres(), zone.ahuId(), zone.damperAuthority(),
                            DamperPosition.SHUT, ControlMode.DEGRADED, DamperPosition.SHUT)
                    : zone);
        }
        return new PlantSnapshot(zones, Topology.airHandler());
    }

    /** What the real split gives each room when the faulted damper has no opening at all. */
    private static Map<ZoneId, AirflowRate> shutFlows() {
        return FlowSplit.across(shutSnapshot().zones(), Topology.airHandler().deliveredFlow(0.0));
    }

    private static String shutFlowsAsText() {
        Map<ZoneId, String> text = new LinkedHashMap<>();
        shutFlows().forEach((zone, rate) -> text.put(zone, rate.toString()));
        return text.toString();
    }

    private static double meanWhileOccupied(RunTrace trace, int from) {
        int[] people = trace.occupants(FAULTED);
        double[] truth = trace.trueSeries(FAULTED);
        double total = 0;
        int occupied = 0;
        for (int minute = from; minute < trace.minutes(); minute++) {
            if (people[minute] > 0) {
                occupied++;
                total += truth[minute];
            }
        }
        return occupied == 0 ? Double.NaN : total / occupied;
    }

    private record Arm(String caseName, String regime, String arm, long seed, ZoneId zone,
            int onset, int occupiedMinutes, double comfortFraction, double maxPpm, double meanPpm,
            String monitorState, int latchedMinute, Double trim, Double fanSpeed,
            Double achievedGain, Double worstHealthyDeviation, String limitation,
            String otherRoomsLatched) {
    }
}
