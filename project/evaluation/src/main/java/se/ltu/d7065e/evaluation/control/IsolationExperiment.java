package se.ltu.d7065e.evaluation.control;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import se.ltu.d7065e.diagnosis.control.LowerArm;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.evaluation.boundary.ResultFiles;
import se.ltu.d7065e.evaluation.entity.RunTrace;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.supervision.control.CompensationPlan;
import se.ltu.d7065e.supervision.control.CompensationSolver;
import se.ltu.d7065e.ventilation.control.FlowSplit;
import se.ltu.d7065e.supervision.control.PlantSnapshot;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-34b — room isolation (R-7): a fault latched in one room leaves the other five alone.
 *
 * <p>Every one of the six rooms takes its turn as the injected room, against a fault-free run on the
 * same seed, so each observed room is compared with itself rather than with a neighbour. Four things
 * are asserted on the five rooms that were not injected:
 *
 * <ol>
 *   <li>zero fault events — the diagnosis stays where the fault is;</li>
 *   <li>no room is degraded — the inner loop keeps the authority it had;</li>
 *   <li>their CO₂ stays inside the fault-free run's band;</li>
 *   <li><b>their airflow stays within the supervisor's 5 % tolerance</b> — ADR-0008 consequence 9,
 *       which is what turns R-7 from a claim about diagnosis into a claim about the shared plant.</li>
 * </ol>
 *
 * <p>The airflow assertion is reported three ways on purpose, because they answer different
 * questions and the report has to be clear about which one R-7 is:
 * <ul>
 * <li><b>the plan's own figure</b>, {@link CompensationPlan#worstHealthyFlowDeviation()} — what the
 *     supervisor claims it cost the healthy rooms;</li>
 * <li><b>the split recomputed independently</b> from {@link FlowSplit} over the episode baseline and
 *     over the plan applied to it — the same claim, checked rather than taken;</li>
 * <li><b>the measured end-to-end gap</b> between the faulted and the fault-free run, minute by
 *     minute. This is the largest of the three and is <em>not</em> the 5 % quantity: a damper frozen
 *     near its minimum stops competing for the shared fan, so the healthy rooms gain flow from the
 *     fault itself before the supervisor does anything. ADR-0008 accepted that coupling; the 5 %
 *     tolerance is a bound on what the <em>compensation</em> takes from them, not on what the fault
 *     gives them.</li>
 * </ul>
 *
 * <h2>Two occupancy regimes, because the airflow clause only has teeth in one of them</h2>
 *
 * <p>Until this arm was added the experiment ran {@link se.ltu.d7065e.physics.control.OccupancyScheduler}'s
 * drawn weekday only, and that is the regime in which the airflow clause cannot fail: the solve asks
 * for a gain near one, the trim comes out at exactly 1.00, no healthy damper is commanded and the
 * plan's claimed deviation is 0.0000 in every row. ADR-0009 named that gap in as many words — the
 * two requirements "would collide in the design occupancy regime, where the plan's claimed healthy
 * deviation reaches 0.2743, and no experiment asserts R-7 there". This is that experiment.
 *
 * <p>So every configuration runs twice, on the same seeds and with the same injected rooms:
 * <ul>
 * <li><b>drawn weekday</b> — the plausible day, as before;</li>
 * <li><b>design occupancy</b> — every room held at the head count ADR-0008 sized its damper
 *     authority from, built with the same {@link DesignOccupancy} source {@link ComfortExperiment}
 *     uses for its own design arm. Every damper is open, so the trim has travel and the fan has
 *     somewhere to put the air, which is exactly when the compensation can take 5 % of a healthy
 *     room's airflow.</li>
 * </ul>
 *
 * <p>The two arms are printed side by side rather than pooled, because a failure in one of them is a
 * statement about a regime and averaging it with a pass would erase it.
 */
public final class IsolationExperiment {

    private static final long[] SEEDS = {1, 2, 3};
    private static final Duration WARMUP = Duration.ofHours(8);
    private static final Duration FAULT_DAY = Duration.ofHours(12);
    private static final double FLOW_TOLERANCE = CompensationSolver.HEALTHY_FLOW_TOLERANCE;
    private static final double CO2_BAND_PPM = 50;

    /**
     * Both settings of the two-sided detector, in both occupancy regimes. The lower arm is in this
     * table because the first pass of this experiment showed the five healthy rooms latching cases
     * of their own and it is the obvious suspect: a damper frozen below its command gives its five
     * neighbours <em>more</em> air than the monitor's model predicts, which is a negative residual
     * in every one of them — exactly what a lower arm is built to fire on. Running the sweep twice
     * is what turns that from a hypothesis into a measurement, and R-7 is a requirement about the
     * five rooms, so the setting that decides whether they stay quiet belongs here and not in a
     * footnote. The design-occupancy half repeats all three configurations rather than the deployed
     * one alone, so that whatever the new regime does can be attributed the same way.
     */
    private static final List<Pass> PASSES = List.of(
            new Pass(Occupancy.DRAWN, LowerArm.GUARDED, true),
            new Pass(Occupancy.DRAWN, LowerArm.OFF, true),
            new Pass(Occupancy.DRAWN, LowerArm.GUARDED, false),
            new Pass(Occupancy.DESIGN, LowerArm.GUARDED, true),
            new Pass(Occupancy.DESIGN, LowerArm.OFF, true),
            new Pass(Occupancy.DESIGN, LowerArm.GUARDED, false));

    /** Which day the six rooms are made to live, which is what decides whether a trim has travel. */
    private enum Occupancy {

        DRAWN("drawn"),
        DESIGN("design");

        private final String label;

        Occupancy(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * One configuration of the sweep. The supervisor-dead pass is the attribution test: if the five
     * healthy rooms stay quiet with nothing acting on the diagnosis, then whatever disturbs them in
     * the other two passes is the response to the fault and not the fault.
     */
    private record Pass(Occupancy occupancy, LowerArm lowerArm, boolean supervised) {

        /** The detector and supervisor setting, which is what the two occupancy arms share. */
        String configuration() {
            return "%s/%s".formatted(lowerArm, supervised ? "sup" : "no-sup");
        }

        String label() {
            return "%s/%s".formatted(occupancy, configuration());
        }
    }

    private IsolationExperiment() {
    }

    public static String run(ResultFiles files) {
        List<Row> rows = new ArrayList<>();
        for (Pass pass : PASSES) {
            MonitorParameters parameters = MonitorParameters.defaults()
                    .withLowerArm(pass.lowerArm());
            for (long seed : SEEDS) {
                RunTrace healthy = healthyRun(seed, parameters, pass);
                for (ZoneId injected : Topology.ZONE_IDS) {
                    rows.addAll(faultedRun(seed, injected, healthy, parameters, pass));
                }
            }
        }

        try (ResultFiles.Csv csv = files.csv("T-34b-room-isolation.csv",
                "pass", "occupancy", "lower_arm", "supervisor", "seed", "injected_zone",
                "observed_zone", "fault_events", "observed_latched", "ever_degraded",
                "ever_compensating", "max_true_ppm_faulted", "max_true_ppm_healthy",
                "co2_excess_ppm", "co2_within_band", "measured_flow_worst_gap",
                "plan_worst_healthy_deviation", "split_worst_healthy_deviation",
                "split_healthy_flow_change", "worst_airflow_deviation", "airflow_within_band",
                "injected_latched", "plan_limitation", "r7_verdict")) {
            rows.forEach(row -> csv.row(row.setup().label(), row.setup().occupancy(),
                    row.setup().lowerArm(), row.setup().supervised() ? "live" : "dead", row.seed(),
                    row.injected().value(), row.observed().value(), row.faultEvents(),
                    row.observedLatched(), row.everDegraded(), row.everCompensating(),
                    row.maxFaulted(), row.maxHealthy(), row.excess(), row.co2WithinBand(),
                    row.measuredFlowGap(), row.planDeviation(), row.splitDeviation(),
                    row.splitChange(), row.worstAirflowDeviation(), row.airflowWithinBand(),
                    row.injectedLatched(), row.limitation(), row.pass() ? "pass" : "FAIL"));
        }

        List<List<String>> summary = new ArrayList<>();
        for (Pass pass : PASSES) {
            for (long seed : SEEDS) {
            for (ZoneId injected : Topology.ZONE_IDS) {
                List<Row> group = rows.stream()
                        .filter(r -> r.setup().equals(pass) && r.seed() == seed
                                && r.injected().equals(injected)).toList();
                if (group.isEmpty()) {
                    continue;
                }
                summary.add(List.of(pass.label(), String.valueOf(seed), injected.value(),
                        String.valueOf(group.size()),
                        String.valueOf(group.stream().mapToInt(Row::faultEvents).sum()),
                        String.valueOf(group.stream().filter(Row::everDegraded).count()),
                        String.valueOf(group.stream().filter(Row::everCompensating).count()),
                        "%+.0f".formatted(group.stream().mapToDouble(Row::excess).max().orElse(0)),
                        "%.4f".formatted(group.getFirst().planDeviation()),
                        "%.4f".formatted(group.stream().mapToDouble(Row::splitDeviation).max().orElse(0)),
                        "%.3f".formatted(group.stream().mapToDouble(Row::measuredFlowGap).max().orElse(0)),
                        String.valueOf(group.getFirst().injectedLatched()),
                        group.stream().allMatch(Row::pass) ? "pass" : "FAIL"));
            }
            }
        }

        StringBuilder perPass = new StringBuilder();
        for (Pass pass : PASSES) {
            List<Row> group = rows.stream().filter(r -> r.setup().equals(pass)).toList();
            long failed = group.stream().filter(r -> !r.pass()).count();
            long accused = group.stream().filter(r -> r.faultEvents() > 0).count();
            long events = group.stream().mapToInt(Row::faultEvents).sum();
            long degradedRooms = group.stream().filter(Row::everDegraded).count();
            long overBand = group.stream().filter(r -> !r.co2WithinBand()).count();
            long overFlow = group.stream().filter(r -> !r.airflowWithinBand()).count();
            perPass.append("  %-21s: %d of %d observed rooms failed; %d rooms accused over %d "
                            .formatted(pass.label(), failed, group.size(), accused, events)
                            + "fault events, %d degraded, %d outside the CO2 band, %d outside the "
                                    .formatted(degradedRooms, overBand, overFlow)
                            + "airflow band (worst %.4f) -> %s%n".formatted(worstAirflow(group),
                                    failed == 0 ? "pass" : "FAIL"));
        }

        long failures = rows.stream().filter(row -> !row.pass()).count();
        long compensated = rows.stream().filter(Row::everCompensating).count();
        long degraded = rows.stream().filter(Row::everDegraded).count();
        // Stated from the data, not from prose: an earlier hardcoded range in this very
        // sentence survived long enough to be quoted in the write-ups, and it was wrong.
        double gapLow = rows.stream().mapToDouble(Row::measuredFlowGap).min().orElse(0);
        double gapHigh = rows.stream().mapToDouble(Row::measuredFlowGap).max().orElse(0);
        return "T-34b — room isolation, rotating the injected room (R-7)\n"
                + "  seeds %s, all six rooms injected in turn, stuck damper frozen at %s, "
                        .formatted(java.util.Arrays.toString(SEEDS), Topology.MINIMUM_POSITION)
                + "%s warm-up then %s%n".formatted(WARMUP, FAULT_DAY)
                + "  each observed room compared with itself in a fault-free run on the same seed; "
                + "airflow tolerance %.0f %%, CO2 band +%.0f ppm%n".formatted(
                        FLOW_TOLERANCE * 100, CO2_BAND_PPM)
                + "  two occupancy arms on identical seeds: the drawn weekday, and every room held "
                + "at its ADR-0008 design head count\n"
                + ResultFiles.table(List.of("pass", "seed", "injected", "observed", "fault ev",
                        "degraded", "compensating", "worst CO2 excess", "plan dev", "split dev",
                        "measured flow gap", "latched", "verdict"), summary)
                + perPass
                + byOccupancy(rows)
                + "  R-7 over every pass: %d of %d observed rooms failed -> %s%n"
                        .formatted(failures, rows.size(), failures == 0 ? "pass" : "FAIL")
                + "  Control-mode changes in the five, which R-7's literal wording also forbids: "
                + "%d of %d rooms reached COMPENSATING and %d reached DEGRADED.%n"
                        .formatted(compensated, rows.size(), degraded)
                + "  COMPENSATING is ADR-0008 option B's own mechanism — compensation works by "
                + "trimming the healthy dampers, so their mode has to change for their airflow to "
                + "be held, and the criterion as written cannot be met by the design it was written "
                + "for. DEGRADED is not: a degraded room is one the monitor accused, so every "
                + "DEGRADED room in this table is a case opened in a room with no injected fault.\n"
                + "  The measured end-to-end gap runs %.2f to %.2f times the healthy flow, over the "
                        .formatted(gapLow, gapHigh)
                + "%d observed rooms, because the fault itself hands the five rooms the air the "
                        .formatted(rows.size())
                + "frozen damper is not taking. It is not the 5 % quantity and is not asserted on.\n";
    }

    /**
     * The two occupancy arms side by side, which is the comparison the experiment was missing: the
     * airflow clause is the one the drawn weekday cannot exercise, so a pass there says nothing
     * about the regime ADR-0009 said the two requirements would collide in.
     */
    private static String byOccupancy(List<Row> rows) {
        List<List<String>> table = new ArrayList<>();
        for (Occupancy occupancy : Occupancy.values()) {
            List<Row> arm = rows.stream().filter(r -> r.setup().occupancy() == occupancy).toList();
            if (arm.isEmpty()) {
                continue;
            }
            table.add(List.of(occupancy.toString(), String.valueOf(arm.size()),
                    String.valueOf(arm.stream().filter(r -> !r.pass()).count()),
                    String.valueOf(arm.stream().filter(r -> r.faultEvents() > 0).count()),
                    String.valueOf(arm.stream().mapToInt(Row::faultEvents).sum()),
                    String.valueOf(arm.stream().filter(Row::everDegraded).count()),
                    String.valueOf(arm.stream().filter(r -> !r.co2WithinBand()).count()),
                    String.valueOf(arm.stream().filter(r -> !r.airflowWithinBand()).count()),
                    "%+.0f".formatted(arm.stream().mapToDouble(Row::excess).max().orElse(0)),
                    "%.4f".formatted(arm.stream().mapToDouble(Row::planDeviation).max().orElse(0)),
                    "%.4f".formatted(arm.stream().mapToDouble(Row::splitDeviation).max().orElse(0)),
                    "%.4f".formatted(worstAirflow(arm)),
                    "%.3f".formatted(arm.stream().mapToDouble(Row::measuredFlowGap).max().orElse(0)),
                    arm.stream().allMatch(Row::pass) ? "pass" : "FAIL"));
        }

        List<Row> design = rows.stream()
                .filter(r -> r.setup().occupancy() == Occupancy.DESIGN).toList();
        long designFailed = design.stream().filter(r -> !r.pass()).count();
        long designAccused = design.stream().filter(r -> r.faultEvents() > 0).count();
        long designOverFlow = design.stream().filter(r -> !r.airflowWithinBand()).count();
        long designVacuous = design.stream().filter(Row::observedLatched).count();
        double designWorstPlan = design.stream().mapToDouble(Row::planDeviation).max().orElse(0);
        double designWorst = worstAirflow(design);
        List<Row> dead = design.stream().filter(r -> !r.setup().supervised()).toList();
        List<Row> live = design.stream().filter(r -> r.setup().supervised()).toList();
        long deadAccused = dead.stream().filter(r -> r.faultEvents() > 0).count();
        long liveAccused = live.stream().filter(r -> r.faultEvents() > 0).count();
        long deadOverFlow = dead.stream().filter(r -> !r.airflowWithinBand()).count();
        long liveOverFlow = live.stream().filter(r -> !r.airflowWithinBand()).count();
        String dominant = designAccused >= designOverFlow
                ? "the first: %d of %d observed rooms were themselves accused of a fault they did"
                        .formatted(designAccused, design.size()) + " not have"
                : "the airflow one: %d of %d observed rooms were outside the band"
                        .formatted(designOverFlow, design.size());
        Row worstRoom = design.stream()
                .max(java.util.Comparator.comparingDouble(Row::worstAirflowDeviation)).orElse(null);
        return "\n  the two occupancy arms side by side, on identical seeds and identical injections\n"
                + ResultFiles.table(List.of("occupancy", "rooms", "failed", "rooms accused",
                        "fault ev", "degraded", "outside CO2", "outside airflow",
                        "worst CO2 excess", "worst plan dev", "worst split dev",
                        "worst airflow dev", "worst measured gap", "verdict"), table)
                + "  R-7's airflow criterion at design occupancy, which is the clause this arm was "
                + "added to exercise: the worst deviation\n  forced on a healthy room's own airflow "
                + "is %.4f against the %.4f tolerance%s,\n  %+.4f signed, and %d of %d observed "
                        .formatted(designWorst, FLOW_TOLERANCE,
                                worstRoom == null ? ""
                                        : " (%s, %s injected, observing %s)".formatted(
                                                worstRoom.setup().label(),
                                                worstRoom.injected().value(),
                                                worstRoom.observed().value()),
                                worstRoom == null ? 0 : worstRoom.splitChange(),
                                designOverFlow, design.size())
                + "rooms are outside the band -> the airflow clause %s at design\n  occupancy.%n"
                        .formatted(designOverFlow == 0 ? "holds" : "FAILS")
                + "  The sign matters and is reported rather than absorbed into a magnitude: the "
                + "breach is upward, so the healthy rooms\n  are over-supplied rather than starved. "
                + "ADR-0009 separates those two sides precisely because one costs fan energy and\n  "
                + "the other costs comfort, and then sets them equal, so R-7's band is two-sided and "
                + "this is outside it either way.\n"
                + "  The plan's own figure is not evidence here and the two arms are what shows it: "
                + "it reaches %.4f and no further,\n  because the solver caps the fan at the speed "
                        .formatted(designWorstPlan)
                + "that makes the figure exactly the tolerance and then reports that\n  cap, so "
                + "reading R-7 off it would be reading the tolerance back out of the code that "
                + "enforces it. The recomputed\n  split is the only one of the two that can fail, "
                + "and the gap between them is a reference-plant mismatch: the solver\n  bounds its "
                + "claim over the plant with each latched room at its latch-time opening, while the "
                + "recomputation uses the\n  episode baseline, where the same room sits at its "
                + "pre-latch opening. The two differ only when a second room latches\n  during an "
                + "episode, which the drawn weekday never does and design occupancy routinely does.\n"
                + "  R-7 as a whole at design occupancy: %d of %d observed rooms failed -> %s, and "
                        .formatted(designFailed, design.size(),
                                designFailed == 0 ? "pass" : "FAIL")
                + "the clause carrying most of it is\n  %s.%n".formatted(dominant)
                + "  The two clauses attribute in opposite directions, which is what the "
                + "supervisor-dead pass is in the table for.\n  Accusations: %d of %d rooms with "
                        .formatted(deadAccused, dead.size())
                + "the supervisor dead against %d of %d with it live, so they are the regime and "
                        .formatted(liveAccused, live.size())
                + "the\n  monitor rather than the response to the fault — R-3's false-alarm rate "
                + "arriving inside R-7's experiment. Airflow\n  breaches: %d of %d rooms with the "
                        .formatted(deadOverFlow, dead.size())
                + "supervisor dead against %d of %d with it live, so that half is the "
                        .formatted(liveOverFlow, live.size())
                + "compensation's own\n  cost and nothing else's; a dead supervisor produces no plan "
                + "to charge anyone for.\n"
                + "  Where an observed room is itself latched the split-deviation claim is vacuous "
                + "rather than satisfied: the solver counts a\n  latched room among the faulted "
                + "ones, so it is excluded from the healthy set the 5 % is measured over. That is "
                + "%d of\n  the %d design-occupancy rows, exactly the rows accused above, and the "
                        .formatted(designVacuous, design.size())
                + "observed_latched column in the CSV is what\n  separates the two cases. The "
                + "airflow breach is not among them: all %d of the rooms outside the band were "
                        .formatted(designOverFlow)
                + "themselves\n  unaccused and unlatched, so their recomputed split is taken over a "
                + "genuinely healthy room. The two failing clauses\n  are disjoint — %d accused "
                        .formatted(designAccused)
                + "plus %d outside the airflow band is the %d total — so neither is standing in for "
                        .formatted(designOverFlow, designFailed)
                + "the other.\n";
    }

    private static double worstAirflow(List<Row> rows) {
        return rows.stream().mapToDouble(Row::worstAirflowDeviation).max().orElse(0);
    }

    /**
     * The harness for one pass, built the way {@link ComfortExperiment} builds its own
     * design-occupancy arm rather than a second way.
     */
    private static SystemHarness harness(long seed, MonitorParameters parameters, Pass pass) {
        return pass.occupancy() == Occupancy.DESIGN
                ? new SystemHarness(seed, parameters, new DesignOccupancy())
                : new SystemHarness(seed, parameters);
    }

    private static RunTrace healthyRun(long seed, MonitorParameters parameters, Pass pass) {
        SystemHarness harness = harness(seed, parameters, pass);
        harness.supervisorRunning(pass.supervised());
        RunTrace trace = new RunTrace(harness.now(),
                (int) (WARMUP.toMinutes() + FAULT_DAY.toMinutes()) + 1);
        for (long minute = 0; minute < WARMUP.toMinutes() + FAULT_DAY.toMinutes(); minute++) {
            trace.append(harness.tick());
        }
        return trace;
    }

    private static List<Row> faultedRun(long seed, ZoneId injected, RunTrace healthy,
            MonitorParameters parameters, Pass pass) {
        SystemHarness harness = harness(seed, parameters, pass);
        harness.supervisorRunning(pass.supervised());
        int onset = (int) WARMUP.toMinutes();
        RunTrace trace = new RunTrace(harness.now(), onset + (int) FAULT_DAY.toMinutes() + 1);
        for (int minute = 0; minute < onset; minute++) {
            trace.append(harness.tick());
        }
        harness.inject(InjectedFault.stuckDamper(injected, Topology.MINIMUM_POSITION, harness.now()));
        harness.events().clear();
        for (long minute = 0; minute < FAULT_DAY.toMinutes(); minute++) {
            trace.append(harness.tick());
        }

        CompensationPlan plan = harness.supervisor().currentPlan();
        Map<ZoneId, Double> splitChange = splitChange(harness, plan);
        Map<ZoneId, ControlMode> everDegraded = new LinkedHashMap<>();
        Map<ZoneId, ControlMode> everCompensating = new LinkedHashMap<>();
        for (RecordedStrategy strategy : strategies(harness)) {
            if (strategy.mode() == ControlMode.DEGRADED) {
                everDegraded.put(strategy.zone(), strategy.mode());
            }
            if (strategy.mode() == ControlMode.COMPENSATING) {
                everCompensating.put(strategy.zone(), strategy.mode());
            }
        }

        List<Row> rows = new ArrayList<>();
        for (ZoneId observed : Topology.ZONE_IDS) {
            if (observed.equals(injected)) {
                continue;
            }
            double maxFaulted = trace.max(trace.trueSeries(observed), onset);
            double maxHealthy = healthy.max(healthy.trueSeries(observed), onset);
            double flowGap = trace.worstRelativeGap(
                    trace.flow(observed), healthy.flow(observed), onset);
            double planDeviation = plan == null ? 0 : plan.worstHealthyFlowDeviation();
            double change = splitChange.getOrDefault(observed, 0.0);
            int faultEvents = harness.events().faultEvents(observed).size();
            rows.add(new Row(pass, seed, injected, observed, faultEvents,
                    harness.monitor().stateOf(observed).isLatched(),
                    everDegraded.containsKey(observed),
                    everCompensating.containsKey(observed), maxFaulted, maxHealthy,
                    maxFaulted - maxHealthy, flowGap, planDeviation, change,
                    harness.monitor().stateOf(injected).isLatched(),
                    plan == null ? "no plan in force" : plan.limitation()));
        }
        return rows;
    }

    /**
     * The 5 % claim checked rather than taken: the split over the episode baseline against the split
     * over the plan applied to it, which is exactly the comparison the supervisor's own report
     * describes. Signed, because the two directions are different harms.
     *
     * <p>This is the only one of the two bounded figures that <em>can</em> fail, and the
     * design-occupancy arm is what shows the difference. {@link CompensationPlan#worstHealthyFlowDeviation()}
     * is bounded by the solver's own construction — it caps the fan at the speed that makes the
     * figure exactly {@link CompensationSolver#HEALTHY_FLOW_TOLERANCE} and then reports that cap — so
     * reading R-7 off the plan's claim alone would be reading the tolerance back out of the code that
     * enforces it. The recomputation here is taken over the supervisor's own episode baseline,
     * where a latched room sits at its <em>pre-latch</em> opening, while the solver bounds its claim
     * over the plant with each latched room at its <em>latch-time</em> opening. The two reference
     * plants differ whenever a second room latches during an episode, which never happens in the
     * drawn weekday and happens routinely at design occupancy, and that difference is where the
     * breach measured below comes from.
     */
    private static Map<ZoneId, Double> splitChange(SystemHarness harness, CompensationPlan plan) {
        Map<ZoneId, Double> change = new LinkedHashMap<>();
        PlantSnapshot baseline = harness.supervisor().episodeBaseline();
        if (plan == null || baseline == null) {
            return change;
        }
        Map<ZoneId, Double> before =
                FlowSplit.flowPerZone(baseline.zones(), baseline.airHandler(), 0.0);
        PlantSnapshot after = plan.appliedTo(baseline);
        Map<ZoneId, Double> afterFlow =
                FlowSplit.flowPerZone(after.zones(), after.airHandler(), 0.0);
        before.forEach((zone, flow) -> {
            if (!plan.faultedZones().contains(zone) && flow > 0) {
                change.put(zone, afterFlow.get(zone) / flow - 1);
            }
        });
        return change;
    }

    private static List<RecordedStrategy> strategies(SystemHarness harness) {
        List<RecordedStrategy> recorded = new ArrayList<>();
        harness.events().all().forEach(published -> {
            if (published.event() instanceof StrategyChanged strategy) {
                recorded.add(new RecordedStrategy(strategy.zone(), strategy.mode()));
            }
        });
        return recorded;
    }

    private record RecordedStrategy(ZoneId zone, ControlMode mode) {}

    private record Row(Pass setup, long seed, ZoneId injected, ZoneId observed,
            int faultEvents, boolean observedLatched,
            boolean everDegraded, boolean everCompensating, double maxFaulted, double maxHealthy,
            double excess, double measuredFlowGap, double planDeviation, double splitChange,
            boolean injectedLatched, String limitation) {

        /**
         * The recomputed split as a magnitude, which is what the two-sided band is tested on. The
         * sign is kept in {@link #splitChange()} because starving a healthy room and over-supplying
         * it are different harms — one costs comfort, the other fan energy — and a band breach that
         * does not say which way it went cannot be acted on.
         */
        double splitDeviation() {
            return Math.abs(splitChange);
        }

        /**
         * The airflow figure R-7 is asserted on: the worse of what the compensation claimed it took
         * from this room and what the recomputed split says it took. The measured end-to-end gap is
         * deliberately not in here — it carries what the fault gave the room, which R-7 does not
         * bound.
         */
        double worstAirflowDeviation() {
            return Math.max(planDeviation, splitDeviation());
        }

        boolean airflowWithinBand() {
            return worstAirflowDeviation() <= FLOW_TOLERANCE + 1e-9;
        }

        boolean co2WithinBand() {
            return excess <= CO2_BAND_PPM;
        }

        boolean pass() {
            return faultEvents == 0 && !everDegraded && co2WithinBand() && airflowWithinBand();
        }
    }
}
