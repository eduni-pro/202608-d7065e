package se.ltu.d7065e.evaluation.control;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.diagnosis.control.OvernightFloor;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.evaluation.boundary.ResultFiles;
import se.ltu.d7065e.evaluation.entity.Percentiles;
import se.ltu.d7065e.evaluation.entity.RunTrace;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.physics.entity.PhysicsSnapshot;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-29 — a learned one-class contender against the CUSUM and the decision tree, on T-25's seeds
 * (R-1, R-3). Kept only if it wins.
 *
 * <p>The question is narrow and worth asking: the incumbent detector is a hand-built sequential
 * test with twenty-four tuned parameters and a hand-written signature table. Would a detector that
 * learns what normal looks like, from a fault-free commissioning window and nothing else, do
 * better? {@link OneClassNoveltyDetector} is that detector, and this experiment is the contest.
 * A measured loss is the result T-29 asks for as readily as a win: the row in §11.6 exists to be
 * filled, and "the contender loses by this much, for this reason" is the finding.
 *
 * <h2>The protocol is T-25's, and that is not negotiable</h2>
 *
 * The fault arm is {@code BaselineComparisonExperiment}'s, minute for minute: seeds 1..20, an 8 h
 * warm-up, a stuck damper on A124 frozen at {@code MINIMUM_POSITION}, 12 h observed, and the delay
 * measured from the first command the frozen damper ignored rather than from the injection — which
 * is the only reference an operator could have seen. The monitor's rows here therefore reproduce
 * T-25's, and the report can put the three detectors in one table.
 *
 * <h2>Where the contender's training window comes from</h2>
 *
 * A one-class detector has to be fitted before it can be asked anything, and the fit may not touch
 * a fault. T-25's fault arm has only an 8 h fault-free warm-up, which is not a commissioning
 * window, so the fit is taken from a separate fault-free run of <em>the same seed</em>: the first
 * {@link #COMMISSIONING} of it. That run shares the seed, the start instant and therefore the
 * occupancy draw with the fault arm, so the two are the same building; nothing labelled reaches
 * the fit, and the fault arm is scored by a detector that has never seen a fault.
 *
 * <p>The fault-free run is then {@code COMMISSIONING + 7 days} long, and R-3 is judged on the
 * seven days <em>after</em> the training window. That is a held-out week, not a rescaled fragment:
 * scoring a trained detector on its own training data would understate its false-alarm rate, and
 * scoring it on four days and multiplying by 7/4 would put a scaling factor in R-3's headline
 * number. The incumbent and the threshold baseline are scored on the same held-out week, so all
 * three false-alarm figures are over identical minutes. The monitor's count over T-25's own window
 * — days 0..7 of the same run — is printed beside it so the tie-back to T-25 is visible rather
 * than asserted.
 *
 * <h2>Both sides get the same flattering tuning</h2>
 *
 * T-25 tunes its threshold baseline on the very runs it reports it on, and says so. The contender
 * is given exactly that privilege and no more: thirty operating points on the learned scale
 * ({@link #QUANTILES} by {@link #DURATIONS_MINUTES}), scored on these runs and picked by Youden's
 * J. The incumbent is left at {@code MonitorParameters.defaults()} and is not touched. Tuning the
 * contender on its own evaluation set can only help it, which is the point: if it still loses, it
 * loses to a detector that was not tuned at all.
 *
 * <h2>What the contender cannot see on the fault arm</h2>
 *
 * Its overnight-floor feature needs a full day of history, and T-25's fault arm is 8 h of warm-up
 * plus 12 h of fault — twenty hours end to end. The floor is therefore {@code NaN} for every
 * minute of every fault run, and the contender judges the fault arm on two features while it
 * judges the held-out week on three. That is a property of T-25's protocol rather than a choice
 * made here, and {@link OneClassNoveltyDetector} is built to survive it: the score is a quantile
 * of the comparable training samples, so one operating point means the same tail fraction in both
 * arms. It is also the honest reason not to read a weak fault-arm result as the feature's fault.
 */
public final class LearnedContenderExperiment {

    private static final ZoneId FAULTED = Topology.A124;
    private static final int SEEDS = 20;
    private static final Duration WARMUP = Duration.ofHours(8);
    private static final Duration FAULT_DAY = Duration.ofHours(12);

    /**
     * Two days, which is the shortest window that leaves the contender anything to learn about its
     * overnight floor. The floor is a trailing-day minimum, so a one-day window would contain no
     * minute at which the floor was known and the three-feature combination would never be fitted
     * at all; two days leave about 1440 minutes of each combination, which is well past the
     * detector's own minimum.
     */
    private static final Duration COMMISSIONING = Duration.ofDays(2);

    /** The week R-3 is judged on, immediately after the training window and never overlapping it. */
    private static final Duration HELD_OUT_WEEK = Duration.ofDays(7);

    /**
     * The contender's operating points, as fractions of the learned training distribution. The
     * grid is six by five to match T-25's six limits by five durations exactly, so that neither
     * detector is given more chances than the other to find a good point. 1.0 is the strictest
     * point an empirical quantile can express — further out than every training sample — and the
     * grid stops there on purpose: extrapolating past the commissioning window would need a
     * parametric tail, which is a modelling assumption the data has not earned.
     */
    private static final double[] QUANTILES = {0.95, 0.99, 0.995, 0.999, 0.9995, 1.0};

    /**
     * The persistence requirements swept, in minutes. Two is the incumbent's own
     * {@code confirmPersistenceCycles} at a 60 s cycle, so the shortest point on this axis asks
     * the contender for exactly what the monitor is asked for.
     */
    private static final int[] DURATIONS_MINUTES = {2, 5, 10, 15, 30};

    /** The seed whose fit is printed, so the report quotes a learned model rather than describing one. */
    private static final long QUOTED_SEED = 1;

    /** The index of {@code overnight_floor_excess_ppm} in {@link OneClassNoveltyDetector.Sample}. */
    private static final int FLOOR_FEATURE = 2;

    private LearnedContenderExperiment() {
    }

    public static String run(ResultFiles files) {
        List<OneClassNoveltyDetector.AlarmRule> rules = rules();
        List<ThresholdDetector> thresholds = BaselineComparisonExperiment.grid();

        int[] contenderDetections = new int[rules.size()];
        List<List<Integer>> contenderDelays = delayLists(rules.size());
        int[] contenderHealthyAlarms = new int[rules.size()];
        int[] contenderWeekAlarms = new int[rules.size()];
        int[] contenderWeeksWithAlarm = new int[rules.size()];
        int[] contenderWorstWeek = new int[rules.size()];

        int[] thresholdDetections = new int[thresholds.size()];
        List<List<Integer>> thresholdDelays = delayLists(thresholds.size());
        int[] thresholdHealthyAlarms = new int[thresholds.size()];
        int[] thresholdWeekAlarms = new int[thresholds.size()];
        int[] thresholdWeeksWithAlarm = new int[thresholds.size()];
        int[] thresholdWorstWeek = new int[thresholds.size()];

        int monitorDetections = 0;
        List<Integer> monitorDelays = new ArrayList<>();
        int monitorHealthyCases = 0;
        int monitorWeekCases = 0;
        int monitorWeeksWithCase = 0;
        int monitorWorstWeek = 0;
        int monitorT25WindowCases = 0;
        int monitorTrainingCases = 0;
        boolean[] monitorSaw = new boolean[SEEDS];
        boolean[][] contenderSaw = new boolean[rules.size()][SEEDS];
        int degenerateFits = 0;
        int faultArmsWithoutFloor = 0;
        int floorFits = 0;
        double floorExcessTotal = 0;
        List<String> quotedFit = List.of();

        try (ResultFiles.Csv csv = files.csv("T-29-learned-contender.csv",
                "detector", "operating_point", "sustained_minutes", "set", "seed", "zone",
                "reference_minute", "faulted_flagged", "delay_minutes", "alarms_on_healthy_rooms",
                "alarms_over_held_out_week", "judged_features")) {

            for (long seed = 1; seed <= SEEDS; seed++) {
                FaultFreeRun week = faultFreeRun(seed);
                if (seed == QUOTED_SEED) {
                    quotedFit = week.detectors()[Topology.ZONE_IDS.indexOf(FAULTED)].describe();
                }
                degenerateFits += week.degenerateFits();
                for (OneClassNoveltyDetector detector : week.detectors()) {
                    double excess = detector.learnedMean(FLOOR_FEATURE);
                    if (!Double.isNaN(excess)) {
                        floorExcessTotal += excess;
                        floorFits++;
                    }
                }
                monitorWeekCases += week.monitorHeldOutCases();
                monitorWeeksWithCase += week.monitorHeldOutCases() > 0 ? 1 : 0;
                monitorWorstWeek = Math.max(monitorWorstWeek, week.monitorHeldOutCases());
                monitorT25WindowCases += week.monitorT25WindowCases();
                monitorTrainingCases += week.monitorTrainingCases();
                csv.row("model-based monitor", "defaults", "", "fault-free", seed, "all", 0,
                        false, -1, "", week.monitorHeldOutCases(), "");

                for (int point = 0; point < rules.size(); point++) {
                    int alarms = 0;
                    for (int zone = 0; zone < Topology.ZONE_IDS.size(); zone++) {
                        alarms += rules.get(point).alarms(week.exceedance()[zone],
                                week.heldOutFrom(), week.heldOutUntil());
                    }
                    contenderWeekAlarms[point] += alarms;
                    contenderWeeksWithAlarm[point] += alarms > 0 ? 1 : 0;
                    contenderWorstWeek[point] = Math.max(contenderWorstWeek[point], alarms);
                    csv.row("one-class (learned)", rules.get(point).limit(),
                            rules.get(point).sustainedMinutes(), "fault-free", seed, "all", 0,
                            false, -1, "", alarms, week.judgedFeaturesOnHeldOut());
                }

                for (int point = 0; point < thresholds.size(); point++) {
                    int alarms = 0;
                    for (ZoneId zone : Topology.ZONE_IDS) {
                        alarms += thresholds.get(point).alarms(week.trace().measured(zone),
                                week.heldOutFrom(), week.heldOutUntil());
                    }
                    thresholdWeekAlarms[point] += alarms;
                    thresholdWeeksWithAlarm[point] += alarms > 0 ? 1 : 0;
                    thresholdWorstWeek[point] = Math.max(thresholdWorstWeek[point], alarms);
                    csv.row("fixed threshold", "%.0f".formatted(thresholds.get(point).limitPpm()),
                            thresholds.get(point).sustainedMinutes(), "fault-free", seed, "all", 0,
                            false, -1, "", alarms, 1);
                }

                FaultArm fault = faultArm(seed, week.detectors());
                faultArmsWithoutFloor += fault.floorEverKnown() ? 0 : 1;
                monitorSaw[(int) seed - 1] = fault.monitorDelay() >= 0;
                monitorDetections += fault.monitorDelay() >= 0 ? 1 : 0;
                if (fault.monitorDelay() >= 0) {
                    monitorDelays.add(fault.monitorDelay());
                }
                monitorHealthyCases += fault.monitorHealthyCases();
                csv.row("model-based monitor", "defaults", "", "fault", seed, FAULTED.value(),
                        fault.reference(), fault.monitorDelay() >= 0, fault.monitorDelay(),
                        fault.monitorHealthyCases(), "", "");

                for (int point = 0; point < rules.size(); point++) {
                    OneClassNoveltyDetector.AlarmRule rule = rules.get(point);
                    int faultedZone = Topology.ZONE_IDS.indexOf(FAULTED);
                    int flagged = rule.firstFlag(fault.exceedance()[faultedZone],
                            fault.onset(), fault.minutes());
                    int delay = flagged < 0 ? -1 : Math.max(0, minuteOf(flagged) - fault.reference());
                    int healthy = 0;
                    for (int zone = 0; zone < Topology.ZONE_IDS.size(); zone++) {
                        if (zone != faultedZone) {
                            healthy += rule.alarms(fault.exceedance()[zone], fault.onset(),
                                    fault.minutes());
                        }
                    }
                    contenderSaw[point][(int) seed - 1] = flagged >= 0;
                    contenderDetections[point] += flagged < 0 ? 0 : 1;
                    if (flagged >= 0) {
                        contenderDelays.get(point).add(delay);
                    }
                    contenderHealthyAlarms[point] += healthy;
                    csv.row("one-class (learned)", rule.limit(),
                            rule.sustainedMinutes(), "fault", seed, FAULTED.value(),
                            fault.reference(), flagged >= 0, delay, healthy, "",
                            fault.judgedFeaturesOnFaultDay());
                }

                for (int point = 0; point < thresholds.size(); point++) {
                    ThresholdDetector detector = thresholds.get(point);
                    int flagged = detector.firstFlag(fault.trace().measured(FAULTED),
                            fault.onset(), fault.minutes());
                    int delay = flagged < 0 ? -1 : Math.max(0, minuteOf(flagged) - fault.reference());
                    int healthy = 0;
                    for (ZoneId zone : Topology.ZONE_IDS) {
                        if (!zone.equals(FAULTED)) {
                            healthy += detector.alarms(fault.trace().measured(zone), fault.onset(),
                                    fault.minutes());
                        }
                    }
                    thresholdDetections[point] += flagged < 0 ? 0 : 1;
                    if (flagged >= 0) {
                        thresholdDelays.get(point).add(delay);
                    }
                    thresholdHealthyAlarms[point] += healthy;
                    csv.row("fixed threshold", "%.0f".formatted(detector.limitPpm()),
                            detector.sustainedMinutes(), "fault", seed, FAULTED.value(),
                            fault.reference(), flagged >= 0, delay, healthy, "", 1);
                }
            }
        }

        int bestContender = bestByYoudenJ(contenderDetections, contenderWeeksWithAlarm);
        int bestThreshold = bestByYoudenJ(thresholdDetections, thresholdWeeksWithAlarm);
        double monitorDetectionRate = (double) monitorDetections / SEEDS;
        double monitorFalseWeekRate = (double) monitorWeeksWithCase / SEEDS;
        double monitorAlarmsPerWeek = (double) monitorWeekCases / SEEDS;
        int matchedContender = closestAlarmVolume(contenderWeekAlarms, monitorAlarmsPerWeek);
        int matchedThreshold = closestAlarmVolume(thresholdWeekAlarms, monitorAlarmsPerWeek);

        List<List<String>> rows = new ArrayList<>();
        for (int point = 0; point < rules.size(); point++) {
            rows.add(row("one-class (learned)", rules.get(point).limit(),
                    rules.get(point).sustainedMinutes(), contenderDetections[point],
                    contenderWeeksWithAlarm[point], contenderWeekAlarms[point],
                    contenderHealthyAlarms[point], contenderDelays.get(point),
                    point == bestContender ? " <- best" : ""));
        }
        rows.add(row("fixed threshold", "%.0f ppm".formatted(
                        thresholds.get(bestThreshold).limitPpm()),
                thresholds.get(bestThreshold).sustainedMinutes(),
                thresholdDetections[bestThreshold], thresholdWeeksWithAlarm[bestThreshold],
                thresholdWeekAlarms[bestThreshold], thresholdHealthyAlarms[bestThreshold],
                thresholdDelays.get(bestThreshold), " <- best of T-25's grid"));
        rows.add(row("model-based monitor", "defaults", 2, monitorDetections, monitorWeeksWithCase,
                monitorWeekCases, monitorHealthyCases, monitorDelays, " <- the incumbent"));

        int dominating = dominatingPoint(contenderDetections, contenderWeekAlarms,
                monitorDetections, monitorWeekCases);
        int quietest = quietestPoint(contenderWeekAlarms);
        int cheapestMatch = cheapestMatchOnDetection(contenderDetections, contenderWeekAlarms,
                monitorDetections);
        int withinBudget = bestWithinAlarmBudget(contenderDetections, contenderWeekAlarms,
                monitorWeekCases);
        boolean contenderWins = dominating >= 0;

        StringBuilder out = new StringBuilder();
        out.append("T-29 — a learned one-class contender against the CUSUM, identical seeds "
                        + "(R-1, R-3)\n")
                .append("  fault set:      seeds 1..%d, stuck damper on %s frozen at %s, "
                        .formatted(SEEDS, FAULTED, Topology.MINIMUM_POSITION)
                        + "%s warm-up then %s — T-25's arm, minute for minute%n"
                                .formatted(WARMUP, FAULT_DAY))
                .append("  fault-free set: the same seeds 1..%d, %s of commissioning the "
                        .formatted(SEEDS, COMMISSIONING)
                        + "contender is fitted on, then %s held out and judged%n"
                                .formatted(HELD_OUT_WEEK))
                .append("  contender:      a Mahalanobis distance over %s, fitted per room on "
                        .formatted(OneClassNoveltyDetector.FEATURE_NAMES)
                        + "the commissioning window of the same seed; never shown a fault\n")
                .append("  contender tuning: %d quantiles x %d durations on the learned scale, "
                        .formatted(QUANTILES.length, DURATIONS_MINUTES.length)
                        + "scored on these very runs and picked by detection - false-alarm rate; "
                        + "the %d points resolve %d distinct behaviours, because an empirical "
                                .formatted(rules.size(), distinctBehaviours(contenderDetections,
                                        contenderWeekAlarms, contenderHealthyAlarms))
                        + "quantile cannot separate two limits that both sit past the largest "
                        + "training distance\n")
                .append("  baseline tuning:  %s, T-25's own grid, scored on these windows%n"
                        .formatted(BaselineComparisonExperiment.gridDescription()))
                .append("  incumbent:        MonitorParameters.defaults(), untouched; R-3 counts "
                        + "distinct confirmed cases, not published CONFIRMED events\n")
                .append("  all three false-alarm columns are over the identical held-out week, "
                        + "so no column carries a scaling factor\n");

        out.append(ResultFiles.table(List.of("detector", "operating point", "min", "detect",
                "false wk", "alarms/wk", "healthy flags", "median delay", "J"), rows));

        out.append("  what the contender learned, seed %d, %s (one fit of %d; the others differ "
                        .formatted(QUOTED_SEED, FAULTED, SEEDS * Topology.ZONE_IDS.size())
                        + "only in their numbers):\n");
        quotedFit.forEach(line -> out.append("    ").append(line).append('\n'));

        out.append("  headline pair (detection rate, fraction of held-out weeks with at least one "
                        + "false alarm):\n")
                .append("    model-based monitor   (%.2f, %.2f) at %.2f cases per fault-free week, "
                        .formatted(monitorDetectionRate, monitorFalseWeekRate, monitorAlarmsPerWeek)
                        + "median delay %s min%n".formatted(median(monitorDelays)))
                .append("    one-class (learned)   (%.2f, %.2f) at %.2f alarms per fault-free week, "
                        .formatted((double) contenderDetections[bestContender] / SEEDS,
                                (double) contenderWeeksWithAlarm[bestContender] / SEEDS,
                                (double) contenderWeekAlarms[bestContender] / SEEDS)
                        + "median delay %s min, at %s%n".formatted(
                                median(contenderDelays.get(bestContender)),
                                rules.get(bestContender)))
                .append("    best fixed threshold  (%.2f, %.2f) at %.2f alarms per fault-free week, "
                        .formatted((double) thresholdDetections[bestThreshold] / SEEDS,
                                (double) thresholdWeeksWithAlarm[bestThreshold] / SEEDS,
                                (double) thresholdWeekAlarms[bestThreshold] / SEEDS)
                        + "median delay %s min, tuned to %s%n".formatted(
                                median(thresholdDelays.get(bestThreshold)),
                                thresholds.get(bestThreshold)))
                .append("  at equal false-alarm volume — the only comparison that discriminates, "
                        + "since either side can buy detection with alarms:\n")
                .append("    the monitor raises %.2f confirmed cases per fault-free week%n"
                        .formatted(monitorAlarmsPerWeek))
                .append("    the contender's closest point is %s at %.2f alarms per week, "
                        .formatted(rules.get(matchedContender),
                                (double) contenderWeekAlarms[matchedContender] / SEEDS)
                        + "detecting %.2f with median delay %s min%n".formatted(
                                (double) contenderDetections[matchedContender] / SEEDS,
                                median(contenderDelays.get(matchedContender))))
                .append("    the threshold's closest point is %s at %.2f alarms per week, "
                        .formatted(thresholds.get(matchedThreshold),
                                (double) thresholdWeekAlarms[matchedThreshold] / SEEDS)
                        + "detecting %.2f with median delay %s min%n".formatted(
                                (double) thresholdDetections[matchedThreshold] / SEEDS,
                                median(thresholdDelays.get(matchedThreshold))))
                .append("    against the monitor's %.2f detection at median delay %s min%n"
                        .formatted(monitorDetectionRate, median(monitorDelays)));

        out.append("  which seeds each detector was blind to, which is where the loss comes from:\n")
                .append("    the monitor missed seeds %s%n".formatted(missed(monitorSaw)))
                .append("    the contender at %s missed seeds %s — %s%n".formatted(
                        rules.get(quietest), missed(contenderSaw[quietest]),
                        java.util.Arrays.equals(monitorSaw, contenderSaw[quietest])
                                ? "the same set, exactly. The two detectors read the same residual, "
                                        + "so a run in which the frozen damper never moved that "
                                        + "residual is invisible to both: what limits detection "
                                        + "here is the evidence and not the decision rule, and a "
                                        + "learned rule cannot add evidence"
                                : "a different set, so the two rules are reading the same "
                                        + "residual differently and the difference is worth "
                                        + "chasing"))
                .append("  the two iso-performance readings, which is where the loss is priced:\n")
                .append("    cheapest point that matches the monitor's %.2f detection: %s%n"
                        .formatted(monitorDetectionRate, cheapestMatch < 0
                                ? "none in the grid reaches it at any alarm volume"
                                : "%s, at %.2f alarms per fault-free week (%.0fx the monitor's "
                                        .formatted(rules.get(cheapestMatch),
                                                (double) contenderWeekAlarms[cheapestMatch] / SEEDS,
                                                monitorWeekCases == 0 ? Double.NaN
                                                        : (double) contenderWeekAlarms[cheapestMatch]
                                                                / monitorWeekCases)
                                        + "%.2f) and median delay %s min".formatted(
                                                monitorAlarmsPerWeek,
                                                median(contenderDelays.get(cheapestMatch)))))
                .append("    most detection available inside the monitor's %.2f alarms per week: %s%n"
                        .formatted(monitorAlarmsPerWeek, withinBudget < 0
                                ? "no point in the grid is that quiet — the quietest is %s at "
                                        .formatted(rules.get(quietest))
                                        + "%.2f alarms per week, detecting %.2f".formatted(
                                                (double) contenderWeekAlarms[quietest] / SEEDS,
                                                (double) contenderDetections[quietest] / SEEDS)
                                : "%s, detecting %.2f".formatted(rules.get(withinBudget),
                                        (double) contenderDetections[withinBudget] / SEEDS)));

        out.append("  R-3 (<= 1 confirmed case per fault-free week), on the held-out week:\n")
                .append("    model-based monitor  %.2f per week over %d weeks, %d weeks clean, "
                        .formatted(monitorAlarmsPerWeek, SEEDS, SEEDS - monitorWeeksWithCase)
                        + "worst week %d -> %s%n".formatted(monitorWorstWeek,
                                monitorWorstWeek <= 1 ? "pass" : "FAIL"))
                .append("    one-class (learned)  %.2f per week at its quietest point %s — the "
                        .formatted((double) contenderWeekAlarms[quietest] / SEEDS,
                                rules.get(quietest))
                        + "best R-3 it can reach at any operating point — %d weeks clean, worst "
                                .formatted(SEEDS - contenderWeeksWithAlarm[quietest])
                        + "week %d, detecting %.2f -> %s%n".formatted(
                                contenderWorstWeek[quietest],
                                (double) contenderDetections[quietest] / SEEDS,
                                contenderWorstWeek[quietest] <= 1 ? "pass" : "FAIL"))
                .append("    best fixed threshold %.2f per week at %s, %d weeks clean, worst week "
                        .formatted((double) thresholdWeekAlarms[bestThreshold] / SEEDS,
                                thresholds.get(bestThreshold),
                                SEEDS - thresholdWeeksWithAlarm[bestThreshold])
                        + "%d -> %s%n".formatted(thresholdWorstWeek[bestThreshold],
                                thresholdWorstWeek[bestThreshold] <= 1 ? "pass" : "FAIL"))
                .append("    tie-back to T-25: over days 0..7 of the same runs — T-25's own "
                        + "window, training data included — the monitor raises %.2f cases per "
                                .formatted((double) monitorT25WindowCases / SEEDS)
                        + "week\n")
                .append("    the commissioning windows the contender was fitted on: the monitor "
                        + "raised %d confirmed cases inside them over the %d seeds, %s%n"
                                .formatted(monitorTrainingCases, SEEDS,
                                        monitorTrainingCases == 0
                                                ? "so every fit was taken from a window the "
                                                        + "incumbent also called healthy and the "
                                                        + "contender's loss cannot be blamed on a "
                                                        + "contaminated training set"
                                                : "so that many fits were taken from a window the "
                                                        + "incumbent considered faulty. Nothing "
                                                        + "is excluded for it: a detector being "
                                                        + "commissioned does not know, and "
                                                        + "removing those minutes would be the "
                                                        + "label leakage T-29 avoids"));

        out.append("  verdict: the contender is %s. %s%n".formatted(
                contenderWins ? "KEPT" : "DROPPED",
                contenderWins
                        ? "At %s it detects at least as often as the incumbent for no more false "
                                .formatted(rules.get(dominating))
                                + "alarms over the same held-out weeks, so a learned normal "
                                + "region beats the hand-built test on this plant and belongs in "
                                + "the design."
                        : "None of the %d operating points on the learned scale reaches the "
                                .formatted(rules.size())
                                + "incumbent's detection rate without raising more false alarms "
                                + "per fault-free week, so it is not kept; the whole grid was "
                                + "scanned, not just its best-J point. The reason is structural "
                                + "rather than a matter of tuning. The contender scores each "
                                + "minute on its own, while the CUSUM accumulates the same "
                                + "residual over time, so the contender's only way to buy an "
                                + "in-control run length is the persistence requirement — which "
                                + "is the one part of it that is hand-set and not learned, and "
                                + "which costs it the delay priced above. It is also measurably "
                                + "losing the signal rather than failing to see it: the residual "
                                + "it watches is re-anchored towards each reading at the "
                                + "observer's anchorGain, so a stuck damper's excursion decays "
                                + "instead of persisting, and the statistic that survives that is "
                                + "a cumulative one."))
                .append("  degenerate fits: %d of %d room-fits learned nothing and scored nothing "
                        .formatted(degenerateFits, SEEDS * Topology.ZONE_IDS.size())
                        + "(a fit with no usable feature combination is no evidence, not health)\n")
                .append("  fault arms in which the overnight floor was never known: %d of %d — "
                        .formatted(faultArmsWithoutFloor, SEEDS)
                        + "T-25's 20 h arm is shorter than the monitor's own %s window, so the "
                                .formatted(MonitorParameters.defaults().overnightWindow())
                        + "contender judges the fault arm on two features and the held-out week "
                        + "on three. That missing feature does not explain the verdict: the "
                        + "contender matches the monitor's detection rate, and the false alarms "
                        + "it is dropped for are counted on the held-out week, where all three "
                        + "features are present\n")
                .append("  a by-product worth recording, because the contender had to estimate it "
                        + "where the monitor only assumes it: over %d fault-free room-fits the "
                                .formatted(floorFits)
                        + "overnight floor of a HEALTHY room averages %+.1f ppm against outdoor "
                                .formatted(floorExcessTotal / floorFits)
                        + "%s, not 0. The floor is the minimum of %d noisy samples, so extreme "
                                .formatted(Topology.OUTDOOR,
                                        MonitorParameters.defaults().overnightWindow().toMinutes())
                        + "value statistics put it about that far below the empty-room "
                        + "concentration. MonitorParameters.overnightFloorTolerancePpm is %.0f "
                                .formatted(MonitorParameters.defaults().overnightFloorTolerancePpm())
                        + "ppm and its javadoc justifies that as 'a little over 3 sigma of sensor "
                        + "noise'; measured against where a healthy floor actually sits, the drift "
                        + "test's margin is about %.0f ppm rather than %.0f, so the guard is "
                                .formatted(MonitorParameters.defaults().overnightFloorTolerancePpm()
                                                - floorExcessTotal / floorFits,
                                        MonitorParameters.defaults().overnightFloorTolerancePpm())
                        + "roughly twice as conservative as the parameter's reasoning claims\n");
        return out.toString();
    }

    // ----- the two arms -----------------------------------------------------------------------

    /**
     * One fault-free run: the commissioning window the contender is fitted on, then the held-out
     * week all three detectors are judged on. Nothing is injected at any point.
     */
    static FaultFreeRun faultFreeRun(long seed) {
        SystemHarness harness = new SystemHarness(seed);
        int training = (int) COMMISSIONING.toMinutes();
        int minutes = training + (int) HELD_OUT_WEEK.toMinutes();
        int t25Window = (int) Duration.ofDays(7).toMinutes();
        RunTrace trace = new RunTrace(harness.now(), minutes + 1);
        List<List<OneClassNoveltyDetector.Sample>> samples = sampleLists();
        OvernightFloor[] floors = floors(harness);

        int[] casesByMinute = new int[minutes];
        for (int minute = 0; minute < minutes; minute++) {
            PhysicsSnapshot snapshot = harness.tick();
            trace.append(snapshot);
            observe(harness, snapshot, floors, samples);
            casesByMinute[minute] = (int) harness.events().confirmedCases();
        }

        OneClassNoveltyDetector[] detectors =
                new OneClassNoveltyDetector[Topology.ZONE_IDS.size()];
        double[][] exceedance = new double[Topology.ZONE_IDS.size()][minutes];
        int degenerate = 0;
        int judgedOnHeldOut = 0;
        for (int zone = 0; zone < Topology.ZONE_IDS.size(); zone++) {
            detectors[zone] = OneClassNoveltyDetector.fitTo(
                    samples.get(zone).subList(0, training));
            degenerate += detectors[zone].fitted() ? 0 : 1;
            for (int minute = 0; minute < minutes; minute++) {
                exceedance[zone][minute] =
                        detectors[zone].exceedance(samples.get(zone).get(minute));
            }
            judgedOnHeldOut = Math.max(judgedOnHeldOut,
                    detectors[zone].judgedFeatures(samples.get(zone).get(minutes - 1)));
        }

        return new FaultFreeRun(trace, detectors, exceedance, training, minutes,
                casesByMinute[minutes - 1] - casesByMinute[training - 1],
                casesByMinute[t25Window - 1], casesByMinute[training - 1], degenerate,
                judgedOnHeldOut);
    }

    /**
     * T-25's fault run, with the contender's feature stream recorded alongside. The warm-up, the
     * injection, the reference minute and the monitor's delay are computed exactly as
     * {@code BaselineComparisonExperiment} computes them, so the monitor's row here is T-25's row.
     */
    static FaultArm faultArm(long seed, OneClassNoveltyDetector[] detectors) {
        SystemHarness harness = new SystemHarness(seed);
        int onset = (int) WARMUP.toMinutes();
        int minutes = onset + (int) FAULT_DAY.toMinutes();
        RunTrace trace = new RunTrace(harness.now(), minutes + 1);
        List<List<OneClassNoveltyDetector.Sample>> samples = sampleLists();
        OvernightFloor[] floors = floors(harness);

        for (int minute = 0; minute < onset; minute++) {
            PhysicsSnapshot snapshot = harness.tick();
            trace.append(snapshot);
            observe(harness, snapshot, floors, samples);
        }
        harness.inject(InjectedFault.stuckDamper(FAULTED, Topology.MINIMUM_POSITION, harness.now()));
        int issuedBefore = harness.damperCommands().size();

        int ignored = -1;
        int confirmed = -1;
        for (int minute = 1; minute <= FAULT_DAY.toMinutes(); minute++) {
            PhysicsSnapshot snapshot = harness.tick();
            trace.append(snapshot);
            observe(harness, snapshot, floors, samples);
            if (ignored < 0 && hasIgnoredCommand(harness, issuedBefore)) {
                ignored = onset + minute;
            }
            if (confirmed < 0 && harness.monitor().stateOf(FAULTED) == MonitorState.CONFIRMED) {
                confirmed = onset + minute;
            }
        }
        int reference = ignored < 0 ? onset : ignored;
        int monitorDelay = confirmed < 0 ? -1 : Math.max(0, confirmed - reference);
        int healthyCases = 0;
        for (ZoneId zone : Topology.ZONE_IDS) {
            if (!zone.equals(FAULTED)) {
                healthyCases += (int) harness.events().confirmedCases(zone);
            }
        }

        double[][] exceedance = new double[Topology.ZONE_IDS.size()][minutes];
        boolean floorEverKnown = false;
        int judgedOnFaultDay = 0;
        for (int zone = 0; zone < Topology.ZONE_IDS.size(); zone++) {
            for (int minute = 0; minute < minutes; minute++) {
                OneClassNoveltyDetector.Sample sample = samples.get(zone).get(minute);
                exceedance[zone][minute] = detectors[zone].exceedance(sample);
                floorEverKnown |= !Double.isNaN(sample.overnightFloorExcessPpm());
            }
            judgedOnFaultDay = Math.max(judgedOnFaultDay,
                    detectors[zone].judgedFeatures(samples.get(zone).get(minutes - 1)));
        }
        return new FaultArm(trace, exceedance, onset, minutes, reference, monitorDelay,
                healthyCases, floorEverKnown, judgedOnFaultDay);
    }

    /**
     * The three features, read off the deployed monitor after its cycle has run. The residual and
     * the believed fan power are the monitor's own; the overnight floor is computed by the
     * monitor's own {@link OvernightFloor} at the monitor's own window, fed the same readings,
     * rather than re-derived here.
     */
    private static void observe(SystemHarness harness, PhysicsSnapshot snapshot,
            OvernightFloor[] floors, List<List<OneClassNoveltyDetector.Sample>> samples) {
        double believedWatts = harness.monitor().flowModel().believedFanPowerWatts();
        double ratio = believedWatts > 0
                ? snapshot.fanPower().watts() / believedWatts
                : Double.NaN;
        for (int zone = 0; zone < Topology.ZONE_IDS.size(); zone++) {
            ZoneId id = Topology.ZONE_IDS.get(zone);
            floors[zone].record(snapshot.at(), snapshot.measuredCo2(id).value());
            double floor = floors[zone].floorPpm();
            // WARMUP is the monitor's own word for "no residual yet"; the field it would read
            // holds its initial zero, and zero is the most ordinary residual there is.
            double residual = harness.monitor().stateOf(id) == MonitorState.WARMUP
                    ? Double.NaN
                    : harness.monitor().residualOf(id);
            samples.get(zone).add(new OneClassNoveltyDetector.Sample(residual, ratio,
                    Double.isNaN(floor) ? Double.NaN : floor - Topology.OUTDOOR.value()));
        }
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

    // ----- bookkeeping ------------------------------------------------------------------------

    static List<OneClassNoveltyDetector.AlarmRule> rules() {
        List<OneClassNoveltyDetector.AlarmRule> rules = new ArrayList<>();
        for (double quantile : QUANTILES) {
            for (int minutes : DURATIONS_MINUTES) {
                rules.add(new OneClassNoveltyDetector.AlarmRule(quantile, minutes));
            }
        }
        return rules;
    }

    /**
     * The minute label of a tick index. The arms count minutes from one — the injection happens
     * after the tick labelled {@code onset} — so a flag at index {@code i} is the minute
     * {@code i + 1} and a delay taken against a reference minute has to use the same scale.
     */
    private static int minuteOf(int tickIndex) {
        return tickIndex + 1;
    }

    private static OvernightFloor[] floors(SystemHarness harness) {
        OvernightFloor[] floors = new OvernightFloor[Topology.ZONE_IDS.size()];
        Duration window = harness.monitor().parameters().overnightWindow();
        for (int zone = 0; zone < floors.length; zone++) {
            floors[zone] = new OvernightFloor(window);
        }
        return floors;
    }

    private static List<List<OneClassNoveltyDetector.Sample>> sampleLists() {
        List<List<OneClassNoveltyDetector.Sample>> samples = new ArrayList<>();
        Topology.ZONE_IDS.forEach(zone -> samples.add(new ArrayList<>()));
        return samples;
    }

    private static List<List<Integer>> delayLists(int points) {
        List<List<Integer>> delays = new ArrayList<>();
        for (int point = 0; point < points; point++) {
            delays.add(new ArrayList<>());
        }
        return delays;
    }

    private static int bestByYoudenJ(int[] detections, int[] weeksWithAlarm) {
        int best = 0;
        double bestJ = -Double.MAX_VALUE;
        for (int point = 0; point < detections.length; point++) {
            double j = (double) detections[point] / SEEDS
                    - (double) weeksWithAlarm[point] / SEEDS;
            if (j > bestJ) {
                bestJ = j;
                best = point;
            }
        }
        return best;
    }

    private static int closestAlarmVolume(int[] weekAlarms, double target) {
        int matched = 0;
        double closest = Double.MAX_VALUE;
        for (int point = 0; point < weekAlarms.length; point++) {
            double gap = Math.abs((double) weekAlarms[point] / SEEDS - target);
            if (gap < closest) {
                closest = gap;
                matched = point;
            }
        }
        return matched;
    }

    /**
     * The first operating point in the grid that beats the incumbent outright — detecting at least
     * as often while raising no more false alarms over the same held-out weeks — or -1 when none
     * does. The whole grid is scanned rather than only its best-J point, so "it loses" is a claim
     * about every operating point the contender has and not about one choice of knob. A detector
     * that buys detection with alarms has not won, which is why a single scalar will not do.
     */
    private static int dominatingPoint(int[] detections, int[] weekAlarms, int monitorDetections,
            int monitorAlarms) {
        for (int point = 0; point < detections.length; point++) {
            if (detections[point] >= monitorDetections && weekAlarms[point] <= monitorAlarms) {
                return point;
            }
        }
        return -1;
    }

    /**
     * How many of the grid's points behave differently from one another. Two quantiles that both
     * sit past the largest training distance are the same detector, and a grid that silently
     * collapses would overstate how many chances the contender was given.
     */
    private static int distinctBehaviours(int[] detections, int[] weekAlarms,
            int[] healthyAlarms) {
        List<String> seen = new ArrayList<>();
        for (int point = 0; point < detections.length; point++) {
            String behaviour = "%d/%d/%d".formatted(detections[point], weekAlarms[point],
                    healthyAlarms[point]);
            if (!seen.contains(behaviour)) {
                seen.add(behaviour);
            }
        }
        return seen.size();
    }

    /** The quietest point: fewest false alarms over the held-out weeks, whatever it detects. */
    private static int quietestPoint(int[] weekAlarms) {
        int quietest = 0;
        for (int point = 0; point < weekAlarms.length; point++) {
            if (weekAlarms[point] < weekAlarms[quietest]) {
                quietest = point;
            }
        }
        return quietest;
    }

    /**
     * The cheapest way to match the incumbent's detection rate: among the points that detect at
     * least as often, the one that raises the fewest false alarms. -1 when none matches it.
     */
    private static int cheapestMatchOnDetection(int[] detections, int[] weekAlarms,
            int monitorDetections) {
        int cheapest = -1;
        for (int point = 0; point < detections.length; point++) {
            if (detections[point] >= monitorDetections
                    && (cheapest < 0 || weekAlarms[point] < weekAlarms[cheapest])) {
                cheapest = point;
            }
        }
        return cheapest;
    }

    /**
     * The most detection available without exceeding the incumbent's false-alarm volume. -1 when
     * no point in the grid is that quiet.
     */
    private static int bestWithinAlarmBudget(int[] detections, int[] weekAlarms, int budget) {
        int best = -1;
        for (int point = 0; point < detections.length; point++) {
            if (weekAlarms[point] <= budget
                    && (best < 0 || detections[point] > detections[best])) {
                best = point;
            }
        }
        return best;
    }

    private static List<String> row(String detector, String operatingPoint, int minutes,
            int detections, int weeksWithAlarm, int weekAlarms, int healthyAlarms,
            List<Integer> delays, String marker) {
        return List.of(detector, operatingPoint, String.valueOf(minutes),
                "%.2f".formatted((double) detections / SEEDS),
                "%.2f".formatted((double) weeksWithAlarm / SEEDS),
                "%.2f".formatted((double) weekAlarms / SEEDS),
                String.valueOf(healthyAlarms),
                median(delays),
                "%+.2f".formatted((double) detections / SEEDS - (double) weeksWithAlarm / SEEDS)
                        + marker);
    }

    /** The seeds a detector did not flag, as the printout lists them. */
    private static String missed(boolean[] saw) {
        List<String> seeds = new ArrayList<>();
        for (int index = 0; index < saw.length; index++) {
            if (!saw[index]) {
                seeds.add(String.valueOf(index + 1));
            }
        }
        return seeds.isEmpty() ? "none" : String.join(", ", seeds);
    }

    private static String median(List<Integer> values) {
        Percentiles percentiles = Percentiles.of(values);
        return percentiles.isEmpty() ? "-" : "%.0f".formatted(percentiles.median());
    }

    /**
     * One fault-free run: its trace, the per-room detectors fitted on its commissioning window,
     * every room's novelty score minute by minute, and the monitor's confirmed cases split by
     * window.
     */
    record FaultFreeRun(RunTrace trace, OneClassNoveltyDetector[] detectors,
            double[][] exceedance, int heldOutFrom, int heldOutUntil, int monitorHeldOutCases,
            int monitorT25WindowCases, int monitorTrainingCases, int degenerateFits,
            int judgedFeaturesOnHeldOut) {
    }

    /** One fault run, with T-25's reference minute and delay beside the contender's scores. */
    record FaultArm(RunTrace trace, double[][] exceedance, int onset, int minutes,
            int reference, int monitorDelay, int monitorHealthyCases, boolean floorEverKnown,
            int judgedFeaturesOnFaultDay) {
    }
}
