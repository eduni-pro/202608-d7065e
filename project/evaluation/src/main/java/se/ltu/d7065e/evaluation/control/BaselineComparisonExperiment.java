package se.ltu.d7065e.evaluation.control;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.evaluation.boundary.ResultFiles;
import se.ltu.d7065e.evaluation.entity.Percentiles;
import se.ltu.d7065e.evaluation.entity.RunTrace;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-25 — the model-based monitor against a plain CO₂ threshold, on identical seeds (R-1, R-3).
 *
 * <p>The project's claim is that modelling what the command should have achieved beats a threshold
 * on the concentration alone. A threshold can always win on detection by flagging everything, so
 * the claim is only testable as a pair: a detection rate on a fault set and a false-alarm rate on a
 * fault-free set, measured on the <em>same</em> twenty seeds and the same recorded sensor series.
 *
 * <p>The baseline is tuned as favourably as this harness can manage, and the tuning is stated
 * rather than buried: a grid of six limits by five durations is scored on exactly the runs it is
 * then reported on, and the operating point quoted is the grid's best by Youden's J
 * ({@code detection − false alarm}). Tuning a detector on its own evaluation set flatters it — that
 * is deliberate, because the question is whether the model-based monitor beats the best a threshold
 * can be made to look, not whether it beats a badly tuned one.
 */
public final class BaselineComparisonExperiment {

    private static final ZoneId FAULTED = Topology.A124;
    private static final int SEEDS = 20;
    private static final Duration WARMUP = Duration.ofHours(8);
    private static final Duration FAULT_DAY = Duration.ofHours(12);
    private static final Duration FAULT_FREE_WEEK = Duration.ofDays(7);

    private static final double[] LIMITS_PPM = {900, 1000, 1100, 1200, 1300, 1500};
    private static final int[] DURATIONS_MINUTES = {5, 10, 15, 30, 60};

    private BaselineComparisonExperiment() {
    }

    /**
     * The tuning grid, as one definition rather than two: T-29 scores the same thirty operating
     * points on its own windows, and a baseline that differed between the two experiments would
     * make the three detectors incomparable for a reason that had nothing to do with any of them.
     */
    static List<ThresholdDetector> grid() {
        List<ThresholdDetector> grid = new ArrayList<>();
        for (double limit : LIMITS_PPM) {
            for (int minutes : DURATIONS_MINUTES) {
                grid.add(new ThresholdDetector(limit, minutes));
            }
        }
        return grid;
    }

    /** How the grid is described wherever it is quoted. */
    static String gridDescription() {
        return "%d limits x %d durations".formatted(LIMITS_PPM.length, DURATIONS_MINUTES.length);
    }

    public static String run(ResultFiles files) {
        List<ThresholdDetector> grid = grid();

        int[] thresholdDetections = new int[grid.size()];
        List<List<Integer>> thresholdDelays = new ArrayList<>();
        int[] thresholdHealthyAlarmsOnFaultRuns = new int[grid.size()];
        int[] thresholdWeekAlarms = new int[grid.size()];
        int[] thresholdWeeksWithAlarm = new int[grid.size()];
        grid.forEach(detector -> thresholdDelays.add(new ArrayList<>()));

        int monitorDetections = 0;
        List<Integer> monitorDelays = new ArrayList<>();
        int monitorHealthyFaultEventsOnFaultRuns = 0;
        int monitorWeekConfirmations = 0;
        int monitorWeeksWithConfirmation = 0;
        int monitorWorstWeek = 0;
        int thresholdWorstWeek = 0;

        try (ResultFiles.Csv csv = files.csv("T-25-baseline-comparison.csv",
                "detector", "limit_ppm", "sustained_minutes", "set", "seed", "reference_minute",
                "faulted_flagged", "delay_minutes", "alarms_on_healthy_rooms",
                "alarms_over_week")) {

            for (long seed = 1; seed <= SEEDS; seed++) {
                FaultRun fault = faultRun(seed);
                monitorDetections += fault.monitorDelay() >= 0 ? 1 : 0;
                if (fault.monitorDelay() >= 0) {
                    monitorDelays.add(fault.monitorDelay());
                }
                monitorHealthyFaultEventsOnFaultRuns += fault.monitorHealthyFaultEvents();
                csv.row("model-based monitor", "", "", "fault", seed, fault.reference(),
                        fault.monitorDelay() >= 0, fault.monitorDelay(),
                        fault.monitorHealthyFaultEvents(), "");

                for (int point = 0; point < grid.size(); point++) {
                    ThresholdDetector detector = grid.get(point);
                    int flagged = detector.firstFlag(
                            fault.trace().measured(FAULTED), fault.onset(), fault.trace().minutes());
                    int healthy = 0;
                    for (ZoneId zone : Topology.ZONE_IDS) {
                        if (!zone.equals(FAULTED)) {
                            healthy += detector.alarms(fault.trace().measured(zone),
                                    fault.onset(), fault.trace().minutes());
                        }
                    }
                    int delay = flagged < 0 ? -1 : Math.max(0, flagged - fault.reference());
                    thresholdDetections[point] += flagged < 0 ? 0 : 1;
                    if (flagged >= 0) {
                        thresholdDelays.get(point).add(delay);
                    }
                    thresholdHealthyAlarmsOnFaultRuns[point] += healthy;
                    csv.row("fixed threshold", detector.limitPpm(), detector.sustainedMinutes(),
                            "fault", seed, fault.reference(), flagged >= 0, delay, healthy, "");
                }
            }

            for (long seed = 1; seed <= SEEDS; seed++) {
                FaultFreeWeek week = faultFreeWeek(seed);
                monitorWeekConfirmations += week.monitorConfirmations();
                monitorWeeksWithConfirmation += week.monitorConfirmations() > 0 ? 1 : 0;
                monitorWorstWeek = Math.max(monitorWorstWeek, week.monitorConfirmations());
                csv.row("model-based monitor", "", "", "fault-free", seed, 0, false, -1, "",
                        week.monitorConfirmations());

                for (int point = 0; point < grid.size(); point++) {
                    ThresholdDetector detector = grid.get(point);
                    int alarms = 0;
                    for (ZoneId zone : Topology.ZONE_IDS) {
                        alarms += detector.alarms(week.trace().measured(zone), 0,
                                week.trace().minutes());
                    }
                    thresholdWeekAlarms[point] += alarms;
                    thresholdWeeksWithAlarm[point] += alarms > 0 ? 1 : 0;
                    csv.row("fixed threshold", detector.limitPpm(), detector.sustainedMinutes(),
                            "fault-free", seed, 0, false, -1, "", alarms);
                }
            }
        }

        double monitorDetectionRate = (double) monitorDetections / SEEDS;
        double monitorFalseWeekRate = (double) monitorWeeksWithConfirmation / SEEDS;
        int best = 0;
        double bestJ = -Double.MAX_VALUE;
        for (int point = 0; point < grid.size(); point++) {
            double j = (double) thresholdDetections[point] / SEEDS
                    - (double) thresholdWeeksWithAlarm[point] / SEEDS;
            if (j > bestJ) {
                bestJ = j;
                best = point;
            }
        }

        List<List<String>> rows = new ArrayList<>();
        for (int point = 0; point < grid.size(); point++) {
            rows.add(List.of("fixed threshold",
                    "%.0f".formatted(grid.get(point).limitPpm()),
                    String.valueOf(grid.get(point).sustainedMinutes()),
                    "%.2f".formatted((double) thresholdDetections[point] / SEEDS),
                    "%.2f".formatted((double) thresholdWeeksWithAlarm[point] / SEEDS),
                    "%.2f".formatted((double) thresholdWeekAlarms[point] / SEEDS),
                    String.valueOf(thresholdHealthyAlarmsOnFaultRuns[point]),
                    median(thresholdDelays.get(point)),
                    "%+.2f".formatted((double) thresholdDetections[point] / SEEDS
                            - (double) thresholdWeeksWithAlarm[point] / SEEDS)
                            + (point == best ? " <- best" : "")));
        }
        rows.add(List.of("model-based monitor", "-", "-",
                "%.2f".formatted(monitorDetectionRate),
                "%.2f".formatted(monitorFalseWeekRate),
                "%.2f".formatted((double) monitorWeekConfirmations / SEEDS),
                String.valueOf(monitorHealthyFaultEventsOnFaultRuns),
                median(monitorDelays),
                "%+.2f".formatted(monitorDetectionRate - monitorFalseWeekRate)));

        ThresholdDetector chosen = grid.get(best);
        double monitorAlarmsPerWeek = (double) monitorWeekConfirmations / SEEDS;
        int matched = 0;
        double closest = Double.MAX_VALUE;
        for (int point = 0; point < grid.size(); point++) {
            double gap = Math.abs((double) thresholdWeekAlarms[point] / SEEDS
                    - monitorAlarmsPerWeek);
            if (gap < closest) {
                closest = gap;
                matched = point;
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("T-25 — model-based monitor vs a tuned fixed threshold, identical seeds "
                        + "(R-1, R-3)\n")
                .append("  fault set:      seeds 1..%d, stuck damper on %s frozen at %s, "
                        .formatted(SEEDS, FAULTED, Topology.MINIMUM_POSITION)
                        + "%s warm-up then %s%n".formatted(WARMUP, FAULT_DAY))
                .append("  fault-free set: the same seeds 1..%d, %s, nothing injected%n"
                        .formatted(SEEDS, FAULT_FREE_WEEK))
                .append("  baseline tuning: %s, scored on these very runs ".formatted(
                                gridDescription())
                        + "and picked by detection - false-alarm rate\n")
                .append("  lower CUSUM arm: %s (the MonitorParameters default); R-3 counts "
                        .formatted(se.ltu.d7065e.diagnosis.control.MonitorParameters.defaults()
                                .lowerArm())
                        + "distinct confirmed cases, not published CONFIRMED events\n");
        out.append(ResultFiles.table(List.of("detector", "limit", "min", "detect", "false wk",
                "alarms/wk", "healthy flags", "median delay", "J"), rows));
        out.append("  headline pair (detection rate, fraction of fault-free weeks with at least "
                        + "one false alarm):\n")
                .append("    model-based monitor   (%.2f, %.2f) at %.2f alarms per fault-free week, "
                        .formatted(monitorDetectionRate, monitorFalseWeekRate, monitorAlarmsPerWeek)
                        + "median delay %s min%n".formatted(median(monitorDelays)))
                .append("    best fixed threshold  (%.2f, %.2f) at %.2f alarms per fault-free week, "
                        .formatted((double) thresholdDetections[best] / SEEDS,
                                (double) thresholdWeeksWithAlarm[best] / SEEDS,
                                (double) thresholdWeekAlarms[best] / SEEDS)
                        + "median delay %s min, tuned to %s%n"
                                .formatted(median(thresholdDelays.get(best)), chosen))
                .append("  on the binary pair the two %s. The discriminating comparison is at equal "
                        .formatted(Math.abs(monitorDetectionRate
                                - (double) thresholdDetections[best] / SEEDS) < 1e-9
                                && Math.abs(monitorFalseWeekRate
                                        - (double) thresholdWeeksWithAlarm[best] / SEEDS) < 1e-9
                                ? "tie" : "differ")
                        + "alarm volume:\n")
                .append("    at %s the threshold raises %.2f alarms per fault-free week — the "
                        .formatted(grid.get(matched),
                                (double) thresholdWeekAlarms[matched] / SEEDS)
                        + "closest match to the monitor's %.2f — and detects %.2f of the faults "
                                .formatted(monitorAlarmsPerWeek,
                                        (double) thresholdDetections[matched] / SEEDS)
                        + "against the monitor's %.2f%n".formatted(monitorDetectionRate))
                .append("  R-3 (<= 1 confirmed case per fault-free week): %.2f per week over %d "
                        .formatted(monitorAlarmsPerWeek, SEEDS)
                        + "weeks, %d weeks clean, worst week %d -> %s%n".formatted(
                                SEEDS - monitorWeeksWithConfirmation, monitorWorstWeek,
                                monitorWorstWeek <= 1 ? "pass" : "FAIL"));
        return out.toString();
    }

    private static String median(List<Integer> values) {
        Percentiles percentiles = Percentiles.of(values);
        return percentiles.isEmpty() ? "-" : "%.0f".formatted(percentiles.median());
    }

    /**
     * Package-private so that T-29 can assert its own fault arm reproduces this one rather than
     * claiming it does. The two experiments' numbers are only comparable if the arm is identical,
     * and that is a property a test should hold rather than a comment.
     */
    static FaultRun faultRun(long seed) {
        SystemHarness harness = new SystemHarness(seed);
        int onset = (int) WARMUP.toMinutes();
        RunTrace trace = new RunTrace(
                harness.now(), onset + (int) FAULT_DAY.toMinutes() + 1);
        for (int minute = 0; minute < onset; minute++) {
            trace.append(harness.tick());
        }
        harness.inject(InjectedFault.stuckDamper(FAULTED, Topology.MINIMUM_POSITION, harness.now()));
        int issuedBefore = harness.damperCommands().size();

        int ignored = -1;
        int confirmed = -1;
        for (int minute = 1; minute <= FAULT_DAY.toMinutes(); minute++) {
            trace.append(harness.tick());
            if (ignored < 0 && hasIgnoredCommand(harness, issuedBefore)) {
                ignored = onset + minute;
            }
            if (confirmed < 0 && harness.monitor().stateOf(FAULTED) == MonitorState.CONFIRMED) {
                confirmed = onset + minute;
            }
        }
        int reference = ignored < 0 ? onset : ignored;
        int monitorDelay = confirmed < 0 ? -1 : Math.max(0, confirmed - reference);
        int healthyFaultEvents = 0;
        for (ZoneId zone : Topology.ZONE_IDS) {
            if (!zone.equals(FAULTED)) {
                // Distinct cases, not published events, so the column is comparable with the
                // threshold detector's alarm episodes in the same five rooms.
                healthyFaultEvents += (int) harness.events().confirmedCases(zone);
            }
        }
        return new FaultRun(seed, trace, onset, reference, monitorDelay, healthyFaultEvents);
    }

    private static boolean hasIgnoredCommand(SystemHarness harness, int issuedBefore) {
        List<CommandIssued> commands = harness.damperCommands();
        double frozen = Topology.MINIMUM_POSITION.value();
        for (int index = issuedBefore; index < commands.size(); index++) {
            CommandIssued command = commands.get(index);
            if (command.zone().equals(FAULTED) && command.position().value() > frozen + 0.05) {
                return true;
            }
        }
        return false;
    }

    private static FaultFreeWeek faultFreeWeek(long seed) {
        SystemHarness harness = new SystemHarness(seed);
        RunTrace trace = new RunTrace(harness.now(), (int) FAULT_FREE_WEEK.toMinutes() + 1);
        for (long minute = 0; minute < FAULT_FREE_WEEK.toMinutes(); minute++) {
            trace.append(harness.tick());
        }
        return new FaultFreeWeek(seed, trace, (int) harness.events().confirmedCases());
    }

    record FaultRun(long seed, RunTrace trace, int onset, int reference, int monitorDelay,
            int monitorHealthyFaultEvents) {
    }

    private record FaultFreeWeek(long seed, RunTrace trace, int monitorConfirmations) {
    }
}
