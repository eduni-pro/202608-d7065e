package se.ltu.d7065e.evaluation.control;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import se.ltu.d7065e.diagnosis.control.ClassifyWhen;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.StepResponse;
import se.ltu.d7065e.evaluation.boundary.ResultFiles;
import se.ltu.d7065e.evaluation.control.ConfusionMatrixExperiment.Arm;
import se.ltu.d7065e.evaluation.control.ConfusionMatrixExperiment.Injected;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-35 — does classifying <em>after</em> a judged probe beat classifying at confirmation?
 *
 * <p>The question is an ordering one and it comes out of T-15. The signature table's third signal
 * is the step response; the monitor may not command, so a probe exists only as a request in the
 * evidence of a latched {@code FaultStateChanged}; and at the instant the CUSUM confirms, a room
 * the inner loop has not happened to step carries {@code NOT_JUDGED}. T-15 measured what demanding
 * {@code MISSING} there costs — the one correct cell of the matrix becomes {@code UNCLASSIFIED} and
 * the tree classifies nothing — so the leaf accepts absent evidence instead. This experiment asks
 * the other way out: keep the leaf strict in effect by <b>withholding the diagnosis until a step
 * has been judged</b>, and measure what that buys and what it costs.
 *
 * <h2>The two settings, on identical seeds</h2>
 *
 * <ul>
 * <li><b>{@code ON_CONFIRMATION}</b> — the deployed order. The case latches and is named in the
 *     same cycle, on whatever signals exist then.
 * <li><b>{@code AFTER_PROBE}</b> — the case latches in the same cycle, as {@code UNCLASSIFIED},
 *     asks for its probe on that cycle instead of one probe interval later, and is named when the
 *     step window closes or at the latest one probe interval on.
 * </ul>
 *
 * <p>Both arms and all four injected conditions are {@link ConfusionMatrixExperiment}'s own, run
 * from its constants and its injection, so the cells here can be read against T-15's cell by cell.
 *
 * <h2>What the three delays mean</h2>
 *
 * <ul>
 * <li><b>Detection delay</b> — onset to the first latched case anywhere. This is R-1's quantity and
 *     the design deliberately does not move it: the case opens on the same cycle in both settings,
 *     because a confirmed residual is a room that needs the supervisor's response whether or not
 *     the fault has a name yet. Identical columns here are the check that the implementation kept
 *     that promise, not a null result.
 * <li><b>Naming delay</b> — latch to the cycle the diagnosis is taken. Zero in
 *     {@code ON_CONFIRMATION} by construction; in {@code AFTER_PROBE} it is what the operator waits
 *     for a named work order, and it is bounded by the probe interval.
 * <li><b>Pin delay</b> — the same wait, seen from R-7. Pinning the believed opening of an
 *     unresponsive damper is what stops the five healthy rooms being charged for air this one is
 *     not passing, and it happens at the {@code STUCK_DAMPER} diagnosis. Deferring the diagnosis
 *     defers the pin by the naming delay, so the naming delay is also an isolation cost.
 * </ul>
 *
 * <h2>The verdict</h2>
 *
 * Not adopted. The reorder does what it was designed to do — the case latches on the same cycle in
 * all 88 pairs and the fault is named on a judged step in 31 of the 39 runs that defer, against none
 * under the deployed order — and the settled classification is the same in all 88 pairs, because the
 * judged step reads {@code MISSING} for every condition including the rooms whose dampers are
 * healthy. The signal the deferral waits for is constant, so it decides nothing, and the deferral is
 * a median 11 min of work order with no diagnosis on it for nothing. The cause lies in the amplitude
 * rather than in the ordering and {@code ProbeOrderedClassificationTest} isolates it; this
 * experiment's job is to show that the ordering is not where the fix belongs.
 */
public final class ProbeOrderExperiment {

    /**
     * The four conditions T-35 measured and published. Pinned rather than {@code Injected.values()},
     * because the ordering question was asked and answered over these four and a fifth condition
     * appearing in the enum must not quietly restate the counts the write-up quotes.
     * {@code SENSOR_OFFSET} is deliberately absent: T-15 owns it, and whether the ordering question
     * reads differently against a sensor fault the classifier can actually name is a measurement
     * nobody has made.
     */
    private static final List<Injected> CONDITIONS = List.of(Injected.NOTHING,
            Injected.STUCK_DAMPER, Injected.BLOCKED_FILTER, Injected.SENSOR_DRIFT);

    private ProbeOrderExperiment() {
    }

    public static String run(ResultFiles files) {
        MonitorParameters before = MonitorParameters.defaults()
                .withClassifyWhen(ClassifyWhen.ON_CONFIRMATION);
        MonitorParameters after = MonitorParameters.defaults()
                .withClassifyWhen(ClassifyWhen.AFTER_PROBE);

        List<Run> runs = new ArrayList<>();
        for (long seed : seeds()) {
            for (Arm arm : Arm.values()) {
                for (Injected injected : CONDITIONS) {
                    runs.add(run(ClassifyWhen.ON_CONFIRMATION, arm, injected, seed, before));
                    runs.add(run(ClassifyWhen.AFTER_PROBE, arm, injected, seed, after));
                }
            }
        }

        try (ResultFiles.Csv csv = files.csv("T-35-probe-order.csv",
                "classify_when", "arm", "seed", "injected", "quiet_at_onset",
                "first_confirmed_zone", "detection_minutes_from_onset", "latched_as",
                "step_response_at_latch", "settled_as", "naming_minutes_after_latch",
                "step_response_at_naming", "rooms_confirmed_at_end", "on_diagonal_at_latch",
                "on_diagonal_when_settled")) {
            runs.forEach(run -> csv.row(run.when(), run.arm(), run.seed(), run.injected(),
                    run.quietAtOnset(),
                    run.firstZone() == null ? "" : run.firstZone().value(), run.detectionMinutes(),
                    run.latchedAs(), run.stepAtLatch(), run.settledAs(), run.namingMinutes(),
                    run.stepAtNaming(), run.roomsConfirmed(), run.diagonalAtLatch(),
                    run.diagonalWhenSettled()));
        }

        StringBuilder out = new StringBuilder();
        out.append("T-35 — probe before classifying, or classify at confirmation? The same "
                        + "ordering question T-15's stuck-damper leaf leaves open\n")
                .append("  seeds %s, both of T-15's arms, all four of its injected conditions; "
                        .formatted(Arrays.toString(seeds()))
                        + "%d runs in all, %d per setting%n".formatted(runs.size(), runs.size() / 2))
                .append("  ON_CONFIRMATION latches and names in one cycle; AFTER_PROBE latches as "
                        + "UNCLASSIFIED, asks for the probe on that cycle, and names the fault when "
                        + "the %d min step window closes or at %d min, whichever is first%n"
                        .formatted(MonitorParameters.defaults().evaluationWindow().toMinutes(),
                                MonitorParameters.defaults().probeInterval().toMinutes()))
                .append("  the cell is the FIRST confirmed case anywhere, as in T-15; 'latched as' "
                        + "is what the operator receives, 'settled as' what the case ends up "
                        + "called\n");

        for (Arm arm : Arm.values()) {
            out.append("\n  %s, what the first work order is called when it is raised%n"
                    .formatted(arm.name()));
            out.append(ResultFiles.table(matrixHeader(), matrix(runs, arm, false)));
            out.append("  %s, what it is called once the case has settled%n".formatted(arm.name()));
            out.append(ResultFiles.table(matrixHeader(), matrix(runs, arm, true)));
        }

        out.append("\n  the trade, condition by condition: detection is the quantity R-1 bounds, "
                + "naming is what AFTER_PROBE spends, and the pin that isolates the five healthy "
                + "rooms waits exactly as long as the naming does\n");
        out.append(ResultFiles.table(List.of("arm", "injected", "setting", "runs",
                "median detection min", "median naming min", "worst naming min",
                "judged step when named", "diagonal when settled"), trade(runs)));

        out.append("\n  and what the probe said once it had been judged. This is the table the "
                + "question turns on: a third signal is only worth a wait if it comes back "
                + "differently for different faults\n");
        out.append(ResultFiles.table(List.of("arm", "injected", "deferred runs", "MATCHED",
                "MISSING", "NOT_JUDGED"), probeVerdicts(runs)));

        out.append(findings(runs));
        return out.toString();
    }

    /**
     * What the deferred cases' step response was when the diagnosis was finally taken, by injected
     * condition. A condition's row is the discriminating power the deferral bought: rows that all
     * read the same way mean the signal arrived and said nothing.
     */
    private static List<List<String>> probeVerdicts(List<Run> runs) {
        List<List<String>> rows = new ArrayList<>();
        for (Arm arm : Arm.values()) {
            for (Injected injected : CONDITIONS) {
                List<Run> deferred = scored(runs, ClassifyWhen.AFTER_PROBE, arm, injected).stream()
                        .filter(Run::deferred).toList();
                if (deferred.isEmpty()) {
                    continue;
                }
                List<String> row = new ArrayList<>(List.of(arm.name(), injected.name(),
                        String.valueOf(deferred.size())));
                for (StepResponse response : StepResponse.values()) {
                    long count = deferred.stream()
                            .filter(run -> run.stepAtNaming() == response).count();
                    row.add(count == 0 ? "." : String.valueOf(count));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static long[] seeds() {
        long[] seeds = new long[ConfusionMatrixExperiment.SPREAD_SEEDS.length + 1];
        seeds[0] = ConfusionMatrixExperiment.FIXED_SEED;
        System.arraycopy(ConfusionMatrixExperiment.SPREAD_SEEDS, 0, seeds, 1,
                ConfusionMatrixExperiment.SPREAD_SEEDS.length);
        return seeds;
    }

    // ----- the tables ------------------------------------------------------------------------

    private static List<String> matrixHeader() {
        return List.of("injected", "setting", "STUCK_DAMPER", "DAMPER_STUCK_OPEN",
                "BLOCKED_FILTER", "SENSOR_DRIFT",
                "UNCLASSIFIED", "never confirmed", "on diagonal");
    }

    private static List<List<String>> matrix(List<Run> runs, Arm arm, boolean settled) {
        List<List<String>> rows = new ArrayList<>();
        for (Injected injected : CONDITIONS) {
            for (ClassifyWhen when : ClassifyWhen.values()) {
                List<Run> group = scored(runs, when, arm, injected);
                List<String> row = new ArrayList<>();
                row.add(injected.name());
                row.add(when.name());
                for (FaultClassification emitted : List.of(FaultClassification.STUCK_DAMPER,
                        FaultClassification.DAMPER_STUCK_OPEN,
                        FaultClassification.BLOCKED_FILTER, FaultClassification.SENSOR_DRIFT,
                        FaultClassification.UNCLASSIFIED, FaultClassification.NONE)) {
                    long count = group.stream()
                            .filter(run -> emitted == (settled ? run.settledAs() : run.latchedAs()))
                            .count();
                    row.add(count == 0 ? "." : String.valueOf(count));
                }
                long diagonal = group.stream()
                        .filter(settled ? Run::diagonalWhenSettled : Run::diagonalAtLatch).count();
                row.add("%d/%d".formatted(diagonal, group.size()));
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * The runs a cell counts, by T-15's own rule: one setting, one arm, one condition, and in the
     * closed-loop arm only the runs with nothing already latched when the fault was injected.
     */
    private static List<Run> scored(List<Run> runs, ClassifyWhen when, Arm arm, Injected injected) {
        return runs.stream()
                .filter(run -> run.when() == when && run.arm() == arm && run.injected() == injected
                        && run.quietAtOnset())
                .toList();
    }

    private static List<List<String>> trade(List<Run> runs) {
        List<List<String>> rows = new ArrayList<>();
        for (Arm arm : Arm.values()) {
            for (Injected injected : CONDITIONS) {
                for (ClassifyWhen when : ClassifyWhen.values()) {
                    List<Run> group = scored(runs, when, arm, injected).stream()
                            .filter(run -> run.detectionMinutes() >= 0).toList();
                    rows.add(List.of(arm.name(), injected.name(), when.name(),
                            String.valueOf(group.size()),
                            median(group.stream().mapToInt(Run::detectionMinutes).toArray()),
                            median(group.stream().mapToInt(Run::namingMinutes).toArray()),
                            worst(group.stream().mapToInt(Run::namingMinutes).toArray()),
                            "%d/%d".formatted(group.stream()
                                    .filter(run -> run.stepAtNaming() != StepResponse.NOT_JUDGED)
                                    .count(), group.size()),
                            "%d/%d".formatted(group.stream().filter(Run::diagonalWhenSettled)
                                    .count(), group.size())));
                }
            }
        }
        return rows;
    }

    private static String median(int[] values) {
        if (values.length == 0) {
            return "-";
        }
        int[] sorted = values.clone();
        Arrays.sort(sorted);
        return String.valueOf(sorted[sorted.length / 2]);
    }

    private static String worst(int[] values) {
        return values.length == 0 ? "-" : String.valueOf(Arrays.stream(values).max().orElse(0));
    }

    /** Generated from the rows, so the verdict cannot drift from the runs behind it. */
    private static String findings(List<Run> runs) {
        StringBuilder out = new StringBuilder();
        for (Arm arm : Arm.values()) {
            for (ClassifyWhen when : ClassifyWhen.values()) {
                List<Run> all = new ArrayList<>();
                for (Injected injected : CONDITIONS) {
                    all.addAll(scored(runs, when, arm, injected));
                }
                out.append("  %-11s %-15s: %d of %d runs on the diagonal when raised, %d of %d "
                                .formatted(arm.name(), when.name(),
                                        all.stream().filter(Run::diagonalAtLatch).count(),
                                        all.size(),
                                        all.stream().filter(Run::diagonalWhenSettled).count(),
                                        all.size())
                        + "once settled; %d named on a judged step%n".formatted(all.stream()
                                .filter(run -> run.detectionMinutes() >= 0
                                        && run.stepAtNaming() != StepResponse.NOT_JUDGED)
                                .count()));
            }
        }

        List<Run> deferred = runs.stream()
                .filter(run -> run.when() == ClassifyWhen.AFTER_PROBE && run.quietAtOnset()
                        && run.deferred())
                .toList();
        out.append("  AFTER_PROBE only changes a run that reaches its confirmation with no judged "
                + "step at all: %d of the %d scored runs that confirmed did%n".formatted(
                        deferred.size(),
                        runs.stream().filter(run -> run.when() == ClassifyWhen.AFTER_PROBE
                                && run.quietAtOnset() && run.detectionMinutes() >= 0).count()));
        long rescued = deferred.stream()
                .filter(run -> run.stepAtNaming() != StepResponse.NOT_JUDGED).count();
        out.append("  of those %d, the probe produced a judged step in %d, so the signal the "
                        .formatted(deferred.size(), rescued)
                + "deferral was waiting for did arrive in %d of them%n".formatted(rescued));

        long pairs = 0;
        long detectionMoved = 0;
        long verdictMoved = 0;
        long roomsMoved = 0;
        for (Run before : runs) {
            if (before.when() != ClassifyWhen.ON_CONFIRMATION) {
                continue;
            }
            Run after = paired(runs, before);
            if (after == null) {
                continue;
            }
            pairs++;
            if (before.detectionMinutes() != after.detectionMinutes()) {
                detectionMoved++;
            }
            if (before.latchedAs() != after.settledAs()) {
                verdictMoved++;
            }
            if (before.roomsConfirmed() != after.roomsConfirmed()) {
                roomsMoved++;
            }
        }
        out.append("  over the %d pairs of runs on one seed and one condition: detection moved in "
                        .formatted(pairs)
                + "%d, the settled classification differed from ON_CONFIRMATION's in %d, and the "
                        .formatted(detectionMoved, verdictMoved)
                + "number of rooms latched by the end differed in %d%n".formatted(roomsMoved));
        return out.toString();
    }

    private static Run paired(List<Run> runs, Run before) {
        return runs.stream()
                .filter(run -> run.when() == ClassifyWhen.AFTER_PROBE && run.arm() == before.arm()
                        && run.injected() == before.injected() && run.seed() == before.seed())
                .findFirst().orElse(null);
    }

    // ----- the runs --------------------------------------------------------------------------

    static Run run(ClassifyWhen when, Arm arm, Injected injected, long seed,
            MonitorParameters parameters) {
        SystemHarness harness = new SystemHarness(seed, parameters);
        boolean quiet = true;
        Duration window;
        long ladderMinute = -1;
        if (arm == Arm.CLOSED_LOOP) {
            harness.tickFor(ConfusionMatrixExperiment.WARMUP);
            quiet = ConfusionMatrixExperiment.nothingLatched(harness);
            window = ConfusionMatrixExperiment.OBSERVATION;
        } else {
            harness.innerLoopRunning(false);
            harness.supervisorRunning(false);
            window = ConfusionMatrixExperiment.HISTORY.plus(ConfusionMatrixExperiment.JUDGE);
            ladderMinute = ConfusionMatrixExperiment.HISTORY.toMinutes();
        }
        ConfusionMatrixExperiment.inject(
                harness, injected, ConfusionMatrixExperiment.headlineMagnitude(injected));
        return observe(when, arm, injected, seed, harness, quiet, window, ladderMinute);
    }

    /**
     * Ticks the window out, recording the first latched case anywhere and then the cycle on which
     * that case stops withholding its diagnosis. The naming cycle is read off the monitor's own
     * deferral flag rather than off a change in the classification, because a deferral that
     * resolves back to {@code UNCLASSIFIED} is still a wait the operator paid for.
     */
    private static Run observe(ClassifyWhen when, Arm arm, Injected injected, long seed,
            SystemHarness harness, boolean quietAtOnset, Duration window, long ladderMinute) {
        ZoneId firstZone = null;
        int detectionMinute = -1;
        FaultClassification latchedAs = FaultClassification.NONE;
        StepResponse stepAtLatch = StepResponse.NOT_JUDGED;
        int namingMinute = -1;
        StepResponse stepAtNaming = StepResponse.NOT_JUDGED;

        for (long minute = 1; minute <= window.toMinutes(); minute++) {
            harness.tick();
            if (minute == ladderMinute) {
                for (int index = 0; index < Topology.ZONE_IDS.size(); index++) {
                    harness.command(Topology.ZONE_IDS.get(index),
                            ConfusionMatrixExperiment.LADDER[index],
                            ConfusionMatrixExperiment.LADDER_REASON);
                }
            }
            if (firstZone == null) {
                for (ZoneId zone : Topology.ZONE_IDS) {
                    if (!harness.monitor().stateOf(zone).isLatched()) {
                        continue;
                    }
                    firstZone = zone;
                    detectionMinute = (int) minute;
                    latchedAs = harness.monitor().caseOf(zone).classification();
                    stepAtLatch = harness.monitor().stepResponseOf(zone);
                    if (!harness.monitor().classificationDeferredOf(zone)) {
                        namingMinute = 0;
                        stepAtNaming = stepAtLatch;
                    }
                    break;
                }
            } else if (namingMinute < 0
                    && !harness.monitor().classificationDeferredOf(firstZone)) {
                namingMinute = (int) (minute - detectionMinute);
                stepAtNaming = harness.monitor().stepResponseOf(firstZone);
            }
        }

        FaultClassification settledAs = firstZone == null || harness.monitor().caseOf(firstZone) == null
                ? latchedAs
                : harness.monitor().caseOf(firstZone).classification();
        long roomsConfirmed = Topology.ZONE_IDS.stream()
                .filter(zone -> harness.monitor().stateOf(zone).isLatched()).count();
        return new Run(when, arm, injected, seed, quietAtOnset, firstZone, detectionMinute,
                latchedAs, stepAtLatch, settledAs, namingMinute, stepAtNaming, (int) roomsConfirmed,
                ConfusionMatrixExperiment.diagonal(injected, latchedAs),
                ConfusionMatrixExperiment.diagonal(injected, settledAs));
    }

    record Run(ClassifyWhen when, Arm arm, Injected injected, long seed, boolean quietAtOnset,
            ZoneId firstZone, int detectionMinutes, FaultClassification latchedAs,
            StepResponse stepAtLatch, FaultClassification settledAs, int namingMinutes,
            StepResponse stepAtNaming, int roomsConfirmed, boolean diagonalAtLatch,
            boolean diagonalWhenSettled) {

        /**
         * Whether this run's diagnosis was withheld for a cycle or more. Read off the wait itself
         * rather than off the classification, so a deferral that resolved back to the same name
         * still counts as one the operator paid for.
         */
        boolean deferred() {
            return namingMinutes > 0;
        }
    }
}
