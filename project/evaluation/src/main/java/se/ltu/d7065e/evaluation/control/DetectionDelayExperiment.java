package se.ltu.d7065e.evaluation.control;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.evaluation.boundary.ResultFiles;
import se.ltu.d7065e.evaluation.entity.Percentiles;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-24 — detection delay against the CUSUM limit {@code h} (R-1).
 *
 * <p>Two arms, because a sweep is only interpretable against a known point and the realistic
 * number is the one the report has to defend.
 *
 * <ul>
 * <li><b>The stepped arm.</b> The protocol {@code fault-monitor}'s own R-1 test uses and the T-04
 *     spike before it: every room at its design occupancy, the inner loop off, the building settled,
 *     then one damper frozen at its minimum and one command it ignores. This is the arm whose
 *     {@code h} = 150 ppm column is comparable with the monitor test's p95, and the difference
 *     between the two numbers is a statement about the two physics implementations, not about the
 *     detector.
 * <li><b>The closed-loop arm.</b> The whole system on a drawn weekday: the occupancy schedules the
 *     observer cannot see, the inner loop commanding, the supervisor live. Here nobody hands the
 *     monitor a step — the fault becomes observable only when the room fills enough for the
 *     controller to ask for air the damper will not give, so the measured delay starts at the first
 *     command the frozen damper ignored, whenever the building happened to produce it.
 * </ul>
 */
public final class DetectionDelayExperiment {

    /** A115, the room {@code fault-monitor}'s own R-1 test freezes, so the arms are comparable. */
    private static final ZoneId STEPPED_ZONE = Topology.A115;

    /**
     * A124, the open-plan office, for the closed-loop arm. It is the only one of the six whose
     * schedule guarantees a continuous occupied stretch, and a damper frozen at its minimum is
     * unobservable until somebody in the room makes the controller ask for air.
     */
    private static final ZoneId CLOSED_LOOP_ZONE = Topology.A124;

    private static final double[] LIMITS = {50, 100, 150, 200, 300};

    /**
     * The plausibility ceiling's headroom, swept on the closed-loop arm because that arm is where
     * the headroom decides everything. {@code 1.00} is the literal design-occupancy trajectory and
     * {@code 1.25} is the shipped value; below 1.00 the ceiling stops bounding occupancy at all and
     * is no longer the quantity ADR-0008 describes, so the sweep does not go there.
     */
    private static final double[] CEILINGS = {1.00, 1.25};
    private static final double SLACK = Topology.CUSUM_SLACK_PPM;
    private static final int SEEDS = 20;
    private static final Duration SETTLE = Duration.ofHours(8);
    private static final Duration BUDGET = Duration.ofMinutes(120);
    private static final Duration CLOSED_LOOP_DAY = Duration.ofHours(12);

    /** The number {@code fault-monitor}'s own R-1 test prints for seeds 1..20 at h = 150 ppm. */
    private static final int MONITOR_TEST_P95_MINUTES = 6;

    private DetectionDelayExperiment() {
    }

    public static String run(ResultFiles files) {
        List<Run> runs = new ArrayList<>();
        for (double limit : LIMITS) {
            for (long seed = 1; seed <= SEEDS; seed++) {
                runs.add(stepped(seed, limit));
            }
        }
        for (long seed = 1; seed <= SEEDS; seed++) {
            runs.add(closedLoop(seed, Topology.CUSUM_LIMIT_PPM));
        }
        for (double ceiling : CEILINGS) {
            for (long seed = 1; seed <= SEEDS; seed++) {
                runs.add(closedLoopAtCeiling(seed, ceiling, true));
                runs.add(closedLoopAtCeiling(seed, ceiling, false));
            }
        }

        try (ResultFiles.Csv csv = files.csv("T-24-detection-delay.csv",
                "arm", "cusum_k_ppm", "cusum_h_ppm", "seed", "zone", "settled",
                "ignored_command_minute", "confirmed_minute", "delay_minutes", "detected",
                "classification", "end_state", "end_cusum_ppm", "occupants_at_ignored_command",
                "true_ppm_at_ignored_command")) {
            runs.forEach(run -> csv.row(run.arm(), run.slack(), run.limit(), run.seed(),
                    run.zone().value(), run.settled(), run.ignoredCommandMinute(),
                    run.confirmedMinute(), run.delayMinutes(), run.detected(),
                    run.classification(), run.endState(), run.endCusum(),
                    run.occupantsAtCommand(), run.ppmAtCommand()));
        }

        StringBuilder out = new StringBuilder();
        out.append("T-24 — detection delay vs the CUSUM limit h (R-1)\n")
                .append("  seeds 1..%d, k = %.0f ppm fixed, stuck damper frozen at %s%n"
                        .formatted(SEEDS, SLACK, Topology.MINIMUM_POSITION))
                .append("  stepped arm: %s, design occupancy, inner loop off, settled %s, "
                        .formatted(STEPPED_ZONE, SETTLE) + "one ignored command\n")
                .append("  closed-loop arm: %s, drawn weekday, inner loop and supervisor live%n"
                        .formatted(CLOSED_LOOP_ZONE))
                .append("  lower CUSUM arm: %s (the MonitorParameters default)%n"
                        .formatted(MonitorParameters.defaults().lowerArm()));

        List<List<String>> rows = new ArrayList<>();
        for (double limit : LIMITS) {
            rows.add(summaryRow("stepped", limit, runs));
        }
        rows.add(summaryRow("closed-loop", Topology.CUSUM_LIMIT_PPM, runs));
        out.append(ResultFiles.table(List.of("arm", "h ppm", "runs", "discarded", "detected",
                "min", "median", "p95", "max", "R-1 p95<=15"), rows));
        out.append("  a run is discarded when the room was already latched before the injection — "
                + "a false alarm during the settle, which is a property of h, not of the fault.\n");

        out.append("  the closed-loop arm against its own control, and swept over the plausibility "
                + "ceiling's headroom:\n");
        List<List<String>> ceilingRows = new ArrayList<>();
        for (double ceiling : CEILINGS) {
            List<String> faulted = summaryRow("closed-loop ceiling %.2f".formatted(ceiling),
                    Topology.CUSUM_LIMIT_PPM, runs);
            List<String> control = summaryRow(
                    "closed-loop ceiling %.2f no fault".formatted(ceiling),
                    Topology.CUSUM_LIMIT_PPM, runs);
            ceilingRows.add(List.of("%.2f".formatted(ceiling), faulted.get(4), control.get(4),
                    faulted.get(6), faulted.get(7)));
        }
        out.append(ResultFiles.table(List.of("ceiling", "latched, fault injected",
                "latched, nothing injected", "median min from onset", "p95 min from onset"),
                ceilingRows));
        out.append("  the control column is what makes the first one a measurement: a room that "
                + "latches as often with nothing wrong has not been diagnosed. The ceiling column "
                + "is flat because the quantity that decides this arm is the gap between the "
                + "fault's effect on the room's steady state and the ceiling's occupancy headroom, "
                + "and at this room's drawn occupancy the first is tens of ppm and the second is "
                + "hundreds — see ADR-0010.\n");

        Percentiles reference = delays(runs, "stepped", Topology.CUSUM_LIMIT_PPM);
        out.append("  cross-check: fault-monitor's own R-1 test reports p95 = %d min at h = %.0f "
                .formatted(MONITOR_TEST_P95_MINUTES, Topology.CUSUM_LIMIT_PPM)
                + "ppm over the same seeds; this harness measures p95 = %.0f min on the same "
                        .formatted(reference.p95())
                + "protocol%s%n".formatted(
                        reference.p95() == MONITOR_TEST_P95_MINUTES ? " — they agree" : " — they differ"));
        return out.toString();
    }

    private static List<String> summaryRow(String arm, double limit, List<Run> runs) {
        List<Run> all = runs.stream()
                .filter(run -> run.arm().equals(arm) && run.limit() == limit).toList();
        List<Run> matching = all.stream().filter(Run::settled).toList();
        Percentiles delays = Percentiles.of(matching.stream()
                .filter(Run::detected).map(Run::delayMinutes).toList());
        long detected = matching.stream().filter(Run::detected).count();
        return List.of(arm, "%.0f".formatted(limit), String.valueOf(matching.size()),
                String.valueOf(all.size() - matching.size()),
                "%d/%d".formatted(detected, matching.size()),
                minutes(delays.min()), minutes(delays.median()), minutes(delays.p95()),
                minutes(delays.max()),
                delays.isEmpty() ? "no detection" : (delays.p95() <= 15 ? "pass" : "FAIL"));
    }

    private static Percentiles delays(List<Run> runs, String arm, double limit) {
        return Percentiles.of(runs.stream()
                .filter(run -> run.arm().equals(arm) && run.limit() == limit && run.settled()
                        && run.detected())
                .map(Run::delayMinutes).toList());
    }

    private static String minutes(double value) {
        return Double.isNaN(value) ? "-" : "%.0f".formatted(value);
    }

    /** The comparable protocol: a settled building, a frozen damper, one ignored command. */
    private static Run stepped(long seed, double limit) {
        SystemHarness harness = new SystemHarness(seed,
                MonitorParameters.defaults().withCusum(SLACK, limit), new DesignOccupancy());
        harness.innerLoopRunning(false);
        harness.supervisorRunning(false);
        harness.tickFor(SETTLE);
        boolean settled = !harness.monitor().stateOf(STEPPED_ZONE).isLatched();

        harness.inject(InjectedFault.stuckDamper(
                STEPPED_ZONE, Topology.MINIMUM_POSITION, harness.now()));
        harness.command(STEPPED_ZONE, 0.80, "T-24: the first ignored command");
        int delay = harness.tickUntil(BUDGET,
                () -> harness.monitor().stateOf(STEPPED_ZONE).isLatched());
        return finish("stepped", seed, limit, STEPPED_ZONE, settled, 0, delay, harness, 0, 0);
    }

    /**
     * The closed-loop arm again, at a chosen plausibility-ceiling headroom and with the option of
     * injecting nothing at all.
     *
     * <p>The nothing-injected runs are the control this arm never had, and they are what makes its
     * detection rate a measurement rather than a count: a detector that latches A124 within 12 h of
     * a drawn weekday whether or not its damper is frozen has detected nothing. The ceiling sweep is
     * beside it because the ceiling's headroom is the one parameter that decides whether this arm
     * can see the fault at all — see ADR-0010 for why that headroom, and not {@code h}, is the
     * binding constraint here.
     */
    private static Run closedLoopAtCeiling(long seed, double ceiling, boolean inject) {
        String arm = "closed-loop ceiling %.2f%s".formatted(ceiling, inject ? "" : " no fault");
        SystemHarness harness = new SystemHarness(seed, MonitorParameters.defaults()
                .withCusum(SLACK, Topology.CUSUM_LIMIT_PPM).withOccupancyCeiling(ceiling));
        harness.tickFor(SETTLE);
        boolean settled = !harness.monitor().stateOf(CLOSED_LOOP_ZONE).isLatched();
        if (inject) {
            harness.inject(InjectedFault.stuckDamper(
                    CLOSED_LOOP_ZONE, Topology.MINIMUM_POSITION, harness.now()));
        }
        int delay = harness.tickUntil(CLOSED_LOOP_DAY,
                () -> harness.monitor().stateOf(CLOSED_LOOP_ZONE).isLatched());
        // The reference point is 0, not the first ignored command, so this arm's delay is measured
        // from fault ONSET while closedLoop's is measured from the moment the fault became
        // observable. Both are meaningful and they are not interchangeable: at the shipped ceiling
        // the two arms confirm on exactly the same minute, but report 180 and 74 as the median
        // because they are counting from different zeros. R-1's delay is closedLoop's.
        return finish(arm, seed, Topology.CUSUM_LIMIT_PPM, CLOSED_LOOP_ZONE, settled, 0, delay,
                harness, harness.latest().occupancy(CLOSED_LOOP_ZONE),
                harness.latest().trueCo2(CLOSED_LOOP_ZONE).value());
    }

    /** The realistic protocol: the fault waits for the building to ask the damper for air. */
    private static Run closedLoop(long seed, double limit) {
        SystemHarness harness = new SystemHarness(seed,
                MonitorParameters.defaults().withCusum(SLACK, limit));
        harness.tickFor(SETTLE);
        boolean settled = !harness.monitor().stateOf(CLOSED_LOOP_ZONE).isLatched();
        harness.inject(InjectedFault.stuckDamper(
                CLOSED_LOOP_ZONE, Topology.MINIMUM_POSITION, harness.now()));

        int alreadyIssued = harness.damperCommands().size();
        int ignoredAt = -1;
        int confirmedAt = -1;
        int occupantsAtCommand = 0;
        double ppmAtCommand = 0;
        for (int minute = 1; minute <= CLOSED_LOOP_DAY.toMinutes(); minute++) {
            harness.tick();
            if (ignoredAt < 0 && firstIgnored(harness, alreadyIssued)) {
                ignoredAt = minute;
                occupantsAtCommand = harness.latest().occupancy(CLOSED_LOOP_ZONE);
                ppmAtCommand = harness.latest().trueCo2(CLOSED_LOOP_ZONE).value();
            }
            if (confirmedAt < 0 && harness.monitor().stateOf(CLOSED_LOOP_ZONE).isLatched()) {
                confirmedAt = minute;
                break;
            }
        }
        int delay = ignoredAt < 0 || confirmedAt < 0 ? -1 : confirmedAt - ignoredAt;
        return finish("closed-loop", seed, limit, CLOSED_LOOP_ZONE, settled, ignoredAt, delay,
                harness, occupantsAtCommand, ppmAtCommand);
    }

    /**
     * Whether the frozen damper has been told to move since the fault was injected. A command for
     * less than the monitor's own minimum step is not a step response worth judging and so is not
     * the command R-1 measures from.
     */
    private static boolean firstIgnored(SystemHarness harness, int alreadyIssued) {
        List<CommandIssued> commands = harness.damperCommands();
        double frozen = Topology.MINIMUM_POSITION.value();
        double minimumStep = MonitorParameters.defaults().minimumStep();
        for (int index = alreadyIssued; index < commands.size(); index++) {
            CommandIssued command = commands.get(index);
            if (command.zone().equals(CLOSED_LOOP_ZONE)
                    && command.position().value() > frozen + minimumStep) {
                return true;
            }
        }
        return false;
    }

    private static Run finish(String arm, long seed, double limit, ZoneId zone, boolean settled,
            int ignoredAt, int delay, SystemHarness harness, int occupants, double ppm) {
        MonitorState state = harness.monitor().stateOf(zone);
        FaultClassification classification = harness.monitor().caseOf(zone) == null
                ? FaultClassification.NONE
                : harness.monitor().caseOf(zone).classification();
        return new Run(arm, SLACK, limit, seed, zone, settled, ignoredAt,
                delay < 0 ? -1 : ignoredAt + delay, delay, settled && delay >= 0, classification,
                state, harness.monitor().cusumOf(zone).value(), occupants, ppm);
    }

    private record Run(String arm, double slack, double limit, long seed, ZoneId zone,
            boolean settled, int ignoredCommandMinute, int confirmedMinute, int delayMinutes,
            boolean detected, FaultClassification classification, MonitorState endState,
            double endCusum, int occupantsAtCommand, double ppmAtCommand) {
    }
}
