package se.ltu.d7065e.evaluation.control;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.diagnosis.entity.StepResponse;
import se.ltu.d7065e.evaluation.boundary.ResultFiles;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * T-15 — the confusion matrix over the three injected faults and the no-fault arm (R-1, R-3).
 *
 * <p>Four injected conditions, not three. {@code FaultKind}'s three faults give the matrix its
 * recall rows; the arm with nothing injected gives it the false-positive row, which is R-3's
 * question and the reason a row of perfect recall on its own would prove nothing. "Never
 * confirmed" is a column of its own for the same reason: a detector that stays quiet is a
 * different outcome from one that confirms and then cannot name what it found, and collapsing
 * the two into a blank cell would hide exactly the result this experiment exists to report.
 *
 * <p>The cell a run lands in is <b>the first confirmed case anywhere in the building</b> — the
 * work order the operator actually receives — rather than the state the injected room eventually
 * reaches. That definition is the same for all four conditions, which is what makes the rows
 * comparable: a plant-wide blockage has no injected room to look in, and the no-fault arm has no
 * room at all. The CSV carries the injected room's own end state beside it, so the two readings
 * can be compared rather than taken on trust.
 *
 * <h2>Two arms</h2>
 *
 * <ul>
 * <li><b>Closed loop.</b> The deployed stack on the drawn weekday {@link SystemHarness} runs by
 *     default: the inner loop commanding, the supervisor live, nobody handing the monitor a step.
 *     A 32 h warm-up puts the onset at 08:00 on the second simulated day, with the 24 h overnight
 *     window already full, and the fault is then observed for 48 h. This is the matrix the report
 *     quotes, because it is the one an operator would see.
 * <li><b>Stepped.</b> The protocol {@code fault-monitor}'s own T-15 tests use, lifted onto the
 *     deployed simulator: the inner loop and the supervisor off, the fault active from minute
 *     zero, then 26 h of drawn occupancy and empty nights so that the entire 24 h overnight
 *     window lies after the onset, and only then one command per room on the ladder
 *     {@code CrossRoomSignatureTest} uses. The ladder is six <em>different</em> positions on
 *     purpose: under ADR-0008's proportional split, opening all six dampers to the same position
 *     redistributes nothing and produces no step response at all, so a uniform command would
 *     leave the step-shape signal undefined. This arm is the one in which the decision tree has
 *     every signal it asks for, and the gap between the two arms is therefore a measurement of
 *     observability rather than of the classifier.
 * </ul>
 *
 * <h2>Magnitudes</h2>
 *
 * The stuck damper is frozen at ADR-0008's {@code MINIMUM_POSITION}, as in every other experiment
 * here. The filter blockage is 40 %, the value {@code CrossRoomSignatureTest} injects and the
 * smallest step above the 35 % that test measures as this plant's detection floor. The sensor
 * drift is a <em>rate</em>, because that is the only form {@code InjectedFault.sensorDrift}
 * takes, and the rate is bounded from both sides by the classifier's own tree: too slow and the
 * overnight floor never clears {@code outdoor + overnightFloorTolerancePpm}, too fast and the
 * inflated generation estimate shrinks the predicted step amplitude until the room looks like a
 * damper that stopped answering. {@link #DRIFT_RATE_SWEEP} measures that window instead of
 * asserting it, and the headline rate is read off the sweep.
 */
public final class ConfusionMatrixExperiment {

    /**
     * The fixed seed the requirement asks for. 42 rather than 1 so that this matrix and
     * {@code fault-monitor}'s own T-15 tests — {@code CrossRoomSignatureTest} and
     * {@code FaultClassifierTest} — are drawn from the same occupancy, which is the only way the
     * two can be compared cell by cell.
     */
    static final long FIXED_SEED = 42;

    /** The spread beside the headline, so one draw of one week cannot be the whole result. */
    static final long[] SPREAD_SEEDS = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

    /** The seeds the drift-rate sweep walks; three is enough to show a boundary is not noise. */
    private static final long[] SWEEP_SEEDS = {42, 1, 2};

    /**
     * A124, the open-plan office, for the two per-room faults. T-24's closed-loop arm uses it for
     * the reason that applies here too: it is the only one of the six whose drawn schedule
     * guarantees a continuous occupied stretch, so the inner loop is certain to command its
     * damper and the step-shape signal is certain to be defined.
     */
    static final ZoneId FAULTED = Topology.A124;

    static final double BLOCKAGE = 0.40;

    /**
     * 60 ppm/h, and the reason is read off {@link #DRIFT_RATE_SWEEP} rather than chosen: it is the
     * smallest swept rate at which a drifting sensor raises a confirmable residual in <em>both</em>
     * arms on all three sweep seeds. Anything slower leaves the stepped arm's row empty, and an
     * empty row says nothing about the classifier — the matrix would be reporting that the
     * detector never looked. Note which way this choice cuts: it fills the SENSOR_DRIFT row with
     * a classification instead of with silence, and the classification it fills it with is the
     * wrong one.
     */
    static final double DRIFT_PPM_PER_HOUR = 60;
    /**
     * Fine between 20 and 80 ppm/h, because that is where the first pass of this experiment put
     * the boundary: below it the drift never raises a confirmable residual inside the judging
     * window, above it the accumulated offset has already walked past the step amplitude the
     * classifier needs. The grid is there to find out whether the two bounds leave a gap at all.
     */
    private static final double[] DRIFT_RATE_SWEEP = {5, 10, 20, 30, 40, 50, 60, 80, 120, 160};

    /**
     * 150 ppm, read off {@link #OFFSET_PPM_SWEEP}: the smallest swept offset at which all three
     * sweep seeds raise a confirmable residual in the <b>closed-loop</b> arm. Below it the offset
     * cannot clear the occupancy ceiling — the ceiling is the trajectory of a room at 1.25 times its
     * design occupancy, so roughly a quarter of the room's steady-state excess is spent before any
     * of the offset reaches the CUSUM — and an empty row says nothing about the classifier.
     *
     * <p>The drift rate's rule is "both arms on all three seeds" and <b>no</b> swept offset meets it,
     * which is a result rather than a reason to widen the grid: in the stepped arm the inner loop is
     * off, so nothing re-anchors the observer, the quasi-steady generation estimate absorbs a
     * constant offset within a few time constants, and the ceiling it then inflates hides the rest.
     * 500 ppm is the smallest offset any stepped seed confirms at, and no offset confirms on all
     * three. The closed-loop rule is therefore the only one this fault can be held to.
     *
     * <p>Note which way this choice cuts, as the drift rate's does: 150 ppm is the one swept value
     * at which the fixed seed's closed-loop run comes out {@code STUCK_DAMPER} rather than
     * {@code UNCLASSIFIED}, so the rule fills the headline cell with the <em>least</em> flattering
     * of the two wrong answers available.
     */
    static final double OFFSET_PPM = 150;

    /**
     * 50 to 700 ppm. The lower end is below the occupancy ceiling's headroom and the upper end is
     * most of the span between outdoor and the comfort limit, so the grid brackets a miscalibration
     * nobody would notice and one nobody could miss. It is swept rather than argued because the
     * question this experiment exists to answer — whether a sensor fault can be <em>named</em> — had
     * better not turn on one chosen magnitude.
     */
    private static final double[] OFFSET_PPM_SWEEP = {50, 100, 150, 200, 250, 300, 350, 400, 500, 700};

    static final Duration WARMUP = Duration.ofHours(32);
    static final Duration OBSERVATION = Duration.ofHours(48);

    /**
     * 34 h, which satisfies two constraints at once and was wrong at 26 h for the second of them.
     * The monitor's overnight window is 24 h, so the step has to be at least that far after the
     * onset for the floor it reads to be a floor the fault produced. And the step has to land in
     * an <em>occupied</em> stretch: 26 h after the Monday-midnight start is 02:00 on the Tuesday,
     * when every room is empty and already at the outdoor concentration, so a damper that refuses
     * to open changes nothing a sensor can see and the first version of this arm measured nothing
     * at all in every one of its 44 runs. 34 h is 10:00 on the Tuesday, mid-morning, with the
     * whole of the Monday night inside the overnight window.
     */
    static final Duration HISTORY = Duration.ofHours(34);
    static final Duration JUDGE = Duration.ofHours(1);

    static final double[] LADDER = {0.55, 0.65, 0.75, 0.85, 0.95, 1.00};
    static final String LADDER_REASON = "T-15: the step the classifier judges";

    /**
     * The matrix's columns; {@code NONE} is the monitor's own word for "no case at all".
     *
     * <p>{@code DAMPER_STUCK_OPEN} is a column with no row of its own, and the asymmetry is
     * deliberate. This experiment injects nothing stuck open — that signature is T-32's and needs
     * {@code LowerArm.HONEST}, which the fault-free week measures at six false confirmations a week
     * against GUARDED's zero, so it is not the configuration the containers run. The column is here
     * anyway because a classification the classifier can emit and the matrix cannot show would
     * silently go missing from the totals, and a cell that does not exist is not the same as a cell
     * that is empty.
     */
    private static final List<FaultClassification> EMITTED = List.of(
            FaultClassification.STUCK_DAMPER, FaultClassification.DAMPER_STUCK_OPEN,
            FaultClassification.BLOCKED_FILTER,
            FaultClassification.SENSOR_DRIFT, FaultClassification.UNCLASSIFIED,
            FaultClassification.NONE);

    private static final List<String> MATRIX_HEADER = List.of("injected", "STUCK_DAMPER",
            "DAMPER_STUCK_OPEN", "BLOCKED_FILTER", "SENSOR_DRIFT", "UNCLASSIFIED",
            "never confirmed", "on diagonal");

    /**
     * The matrix's rows. {@code NOTHING} is a constant rather than a nullable {@code FaultKind},
     * because "nothing injected" is a condition this experiment measures and not the absence of one.
     *
     * <p>Five rows against four columns of fault, because {@code SENSOR_DRIFT} and
     * {@code SENSOR_OFFSET} are two mechanisms the monitor has one word for. Both score on the
     * {@code SENSOR_DRIFT} column, and keeping them apart as rows is what lets this matrix report
     * that the word is reachable from one of them and not from the other.
     */
    enum Injected {
        NOTHING, STUCK_DAMPER, BLOCKED_FILTER, SENSOR_DRIFT, SENSOR_OFFSET
    }

    enum Arm {
        CLOSED_LOOP, STEPPED
    }

    private ConfusionMatrixExperiment() {
    }

    /**
     * The magnitude a condition's headline runs use. Only the two sensor faults are swept, so for
     * the other three this is the value their own {@code inject} branch ignores; it is returned
     * rather than special-cased so that {@link #scored} has one rule for picking headline runs.
     */
    static double headlineMagnitude(Injected injected) {
        return switch (injected) {
            case SENSOR_OFFSET -> OFFSET_PPM;
            case NOTHING, STUCK_DAMPER, BLOCKED_FILTER, SENSOR_DRIFT -> DRIFT_PPM_PER_HOUR;
        };
    }

    public static String run(ResultFiles files) {
        List<Run> runs = new ArrayList<>();
        for (Arm arm : Arm.values()) {
            for (Injected injected : Injected.values()) {
                runs.add(run(arm, injected, FIXED_SEED, headlineMagnitude(injected)));
                for (long seed : SPREAD_SEEDS) {
                    runs.add(run(arm, injected, seed, headlineMagnitude(injected)));
                }
            }
        }
        sweepMagnitudes(runs, Injected.SENSOR_DRIFT, DRIFT_RATE_SWEEP);
        sweepMagnitudes(runs, Injected.SENSOR_OFFSET, OFFSET_PPM_SWEEP);

        try (ResultFiles.Csv csv = files.csv("T-15-confusion-matrix.csv",
                "arm", "seed", "injected", "magnitude", "injected_zone", "quiet_at_onset",
                "emitted", "first_confirmed_zone", "minutes_from_onset", "confirmed_before_step",
                "localised", "rooms_confirmed_at_end", "injected_zone_end_state",
                "injected_zone_end_classification", "drift_offset_at_confirmation_ppm",
                "judged_steps_before_confirmation", "overnight_floor_at_confirmation_ppm",
                "floor_informative_at_confirmation", "step_response_at_confirmation",
                "on_diagonal")) {
            runs.forEach(run -> csv.row(run.arm(), run.seed(), run.injected(), run.magnitude(),
                    run.injected() == Injected.BLOCKED_FILTER || run.injected() == Injected.NOTHING
                            ? "PLANT" : FAULTED.value(),
                    run.quietAtOnset(), run.emitted(),
                    run.firstZone() == null ? "" : run.firstZone().value(), run.firstMinute(),
                    run.confirmedBeforeStep(), run.localised(), run.roomsConfirmed(),
                    run.injectedEndState(), run.injectedEndClassification(), run.driftOffset(),
                    run.judgedSteps(), run.floorAtConfirmationPpm(), run.floorInformative(),
                    run.stepAtConfirmation(), run.diagonal()));
        }

        StringBuilder out = new StringBuilder();
        out.append("T-15 — confusion matrix over the three injected faults and the no-fault arm "
                        + "(R-1, R-3)\n")
                .append("  fixed seed %d, with seeds %s beside it as the spread; %d runs in all%n"
                        .formatted(FIXED_SEED, java.util.Arrays.toString(SPREAD_SEEDS), runs.size()))
                .append("  injected room %s; stuck damper frozen at %s, filter blockage %.0f %% on "
                        .formatted(FAULTED, Topology.MINIMUM_POSITION, BLOCKAGE * 100)
                        + "the plant, sensor drift %.0f ppm/h%n".formatted(DRIFT_PPM_PER_HOUR))
                .append("  closed-loop arm: the deployed stack, inner loop and supervisor live, "
                        + "%s warm-up then %s observed%n".formatted(WARMUP, OBSERVATION))
                .append("  stepped arm: inner loop and supervisor off, fault from minute 0, %s of "
                        .formatted(HISTORY)
                        + "drawn days and empty nights so the 24 h overnight window is entirely "
                        + "post-onset, then one command per room on the ladder "
                        + java.util.Arrays.toString(LADDER) + ", judged for %s%n".formatted(JUDGE))
                .append("  the cell is the FIRST confirmed case anywhere — the work order the "
                        + "operator receives — and 'never confirmed' is an outcome, not a blank\n");

        out.append("\n  the headline: fixed seed %d, closed-loop arm%n".formatted(FIXED_SEED));
        out.append(ResultFiles.table(MATRIX_HEADER, matrix(runs, Arm.CLOSED_LOOP, true)));
        out.append("  fixed seed %d, stepped arm%n".formatted(FIXED_SEED));
        out.append(ResultFiles.table(MATRIX_HEADER, matrix(runs, Arm.STEPPED, true)));

        out.append("  what each fixed-seed run did, run by run:\n");
        out.append(ResultFiles.table(List.of("arm", "injected", "emitted", "first room",
                        "min from onset", "before the step", "localised", "rooms confirmed",
                        "%s steps judged".formatted(FAULTED.value()),
                        "%s end state".formatted(FAULTED.value()),
                        "%s end class".formatted(FAULTED.value())),
                detail(runs)));

        out.append("  the spread: the same matrix over seeds %d and %s, closed-loop arm%n"
                .formatted(FIXED_SEED, java.util.Arrays.toString(SPREAD_SEEDS)));
        out.append(ResultFiles.table(MATRIX_HEADER, matrix(runs, Arm.CLOSED_LOOP, false)));
        out.append("  the same, stepped arm\n");
        out.append(ResultFiles.table(MATRIX_HEADER, matrix(runs, Arm.STEPPED, false)));

        out.append("  sensor-drift rate sweep over seeds %s, which measures the two bounds the "
                        .formatted(java.util.Arrays.toString(SWEEP_SEEDS))
                + "classifier's tree puts on a drift it can name: the overnight floor has to "
                + "clear outdoor + 100 ppm, and the step amplitude has to survive the inflated "
                + "generation estimate. The headline rate above is the smallest one here that "
                + "confirms in both arms on all three seeds.\n");
        out.append(ResultFiles.table(List.of("ppm/h", "arm", "seed", "emitted",
                "min from onset", "drift at confirmation ppm"),
                sweep(runs, Injected.SENSOR_DRIFT, DRIFT_RATE_SWEEP)));

        out.append("  sensor-offset sweep over the same seeds. The offset holds still, so the two "
                + "bounds that close against each other for a ramp do not have to: the overnight "
                + "floor fills at whatever the offset is while the step amplitude stays whatever it "
                + "was. If the drift leaf is reachable from this generator at all, it is reachable "
                + "here.\n");
        out.append(ResultFiles.table(List.of("ppm", "arm", "seed", "emitted",
                "min from onset", "offset at confirmation ppm"),
                sweep(runs, Injected.SENSOR_OFFSET, OFFSET_PPM_SWEEP)));

        out.append(findings(runs));
        return out.toString();
    }

    /** Both arms and all three sweep seeds at every swept magnitude but the headline one. */
    private static void sweepMagnitudes(List<Run> runs, Injected injected, double[] magnitudes) {
        for (double magnitude : magnitudes) {
            if (magnitude == headlineMagnitude(injected)) {
                continue;
            }
            for (long seed : SWEEP_SEEDS) {
                runs.add(run(Arm.STEPPED, injected, seed, magnitude));
                runs.add(run(Arm.CLOSED_LOOP, injected, seed, magnitude));
            }
        }
    }

    // ----- the matrix ------------------------------------------------------------------------

    private static List<List<String>> matrix(List<Run> runs, Arm arm, boolean fixedSeedOnly) {
        List<List<String>> rows = new ArrayList<>();
        for (Injected injected : Injected.values()) {
            List<Run> group = scored(runs, arm, injected, fixedSeedOnly);
            List<String> row = new ArrayList<>();
            row.add(injected.name());
            for (FaultClassification emitted : EMITTED) {
                long count = group.stream().filter(run -> run.emitted() == emitted).count();
                row.add(count == 0 ? "." : String.valueOf(count));
            }
            row.add("%d/%d".formatted(group.stream().filter(Run::diagonal).count(), group.size()));
            rows.add(row);
        }
        return rows;
    }

    /**
     * The runs a matrix cell counts: one arm, one condition, the headline drift rate, and — in the
     * closed-loop arm — only the runs in which nothing was already latched when the fault was
     * injected. A room latched during the warm-up is a false alarm, which is a property of the
     * detector's threshold and belongs to T-25, not a classification of the fault that had not
     * yet been injected. The stepped arm has nothing to discard: its fault is active from minute
     * zero, so there is no fault-free warm-up to go wrong.
     */
    private static List<Run> scored(List<Run> runs, Arm arm, Injected injected,
            boolean fixedSeedOnly) {
        return runs.stream()
                .filter(run -> run.arm() == arm && run.injected() == injected
                        && run.rate() == headlineMagnitude(injected)
                        && (!fixedSeedOnly || run.seed() == FIXED_SEED)
                        && run.quietAtOnset())
                .toList();
    }

    private static List<List<String>> detail(List<Run> runs) {
        List<List<String>> rows = new ArrayList<>();
        runs.stream()
                .filter(run -> run.seed() == FIXED_SEED
                        && run.rate() == headlineMagnitude(run.injected()))
                .forEach(run -> rows.add(List.of(run.arm().name(), run.injected().name(),
                        label(run.emitted()),
                        run.firstZone() == null ? "-" : run.firstZone().value(),
                        run.firstMinute() < 0 ? "-" : String.valueOf(run.firstMinute()),
                        run.firstMinute() < 0 ? "-" : String.valueOf(run.confirmedBeforeStep()),
                        String.valueOf(run.localised()), String.valueOf(run.roomsConfirmed()),
                        String.valueOf(run.judgedSteps()), run.injectedEndState().name(),
                        label(run.injectedEndClassification()))));
        return rows;
    }

    private static List<List<String>> sweep(List<Run> runs, Injected injected, double[] magnitudes) {
        List<List<String>> rows = new ArrayList<>();
        for (double magnitude : magnitudes) {
            for (Arm arm : Arm.values()) {
                for (long seed : SWEEP_SEEDS) {
                    runs.stream()
                            .filter(run -> run.injected() == injected && run.arm() == arm
                                    && run.rate() == magnitude && run.seed() == seed)
                            .forEach(run -> rows.add(List.of("%.0f".formatted(magnitude), arm.name(),
                                    String.valueOf(seed), label(run.emitted()),
                                    run.firstMinute() < 0 ? "-" : String.valueOf(run.firstMinute()),
                                    run.firstMinute() < 0
                                            ? "-" : "%.0f".formatted(run.driftOffset()))));
                }
            }
        }
        return rows;
    }

    private static String label(FaultClassification emitted) {
        return emitted == FaultClassification.NONE ? "never confirmed" : emitted.name();
    }

    /**
     * The prose is generated from the rows rather than written beside them, because an earlier
     * experiment in this module had a hardcoded range in its own summary sentence survive long
     * enough to be quoted in the write-ups, and it was wrong.
     */
    private static String findings(List<Run> runs) {
        StringBuilder out = new StringBuilder();
        for (Arm arm : Arm.values()) {
            List<Run> all = new ArrayList<>();
            for (Injected injected : Injected.values()) {
                all.addAll(scored(runs, arm, injected, false));
            }
            long diagonal = all.stream().filter(Run::diagonal).count();
            List<Run> faulted = all.stream()
                    .filter(run -> run.injected() != Injected.NOTHING).toList();
            long missed = faulted.stream()
                    .filter(run -> run.emitted() == FaultClassification.NONE).count();
            List<Run> control = all.stream()
                    .filter(run -> run.injected() == Injected.NOTHING).toList();
            long falsePositives = control.stream()
                    .filter(run -> run.emitted() != FaultClassification.NONE).count();
            long misrouted = faulted.stream()
                    .filter(run -> run.injected() != Injected.BLOCKED_FILTER && !run.localised())
                    .count();
            out.append("  %-11s: %d of %d runs on the diagonal; %d of %d faulted runs never "
                            .formatted(arm.name(), diagonal, all.size(), missed, faulted.size())
                    + "confirmed, %d of %d faulted runs confirmed the wrong room first, and %d of "
                            .formatted(misrouted, faulted.size(), falsePositives)
                    + "%d runs with nothing injected confirmed a case anyway%n"
                            .formatted(control.size()));
        }
        long discarded = runs.stream()
                .filter(run -> run.rate() == headlineMagnitude(run.injected()) && !run.quietAtOnset())
                .count();
        out.append("  %d runs were discarded for a room already latched at the onset.%n"
                .formatted(discarded));

        sensorFindings(runs, out, Injected.SENSOR_DRIFT, DRIFT_RATE_SWEEP, "ppm/h");
        sensorFindings(runs, out, Injected.SENSOR_OFFSET, OFFSET_PPM_SWEEP, "ppm");
        out.append(whyTheDriftLeafIsUnreached(runs));

        List<Run> drifts = runs.stream()
                .filter(run -> run.injected() == Injected.SENSOR_DRIFT).toList();

        List<Run> misread = drifts.stream()
                .filter(run -> run.emitted() == FaultClassification.STUCK_DAMPER).toList();
        List<Run> vacuous = misread.stream().filter(run -> run.judgedSteps() == 0).toList();
        List<Run> judged = misread.stream().filter(run -> run.judgedSteps() > 0).toList();
        out.append("  %d drifts were read as a stuck damper, and they reach that leaf by two "
                        .formatted(misread.size())
                + "different routes. %d of them had %d to %d judged steps behind them, so the "
                        .formatted(judged.size(),
                                judged.stream().mapToInt(Run::judgedSteps).min().orElse(0),
                                judged.stream().mapToInt(Run::judgedSteps).max().orElse(0))
                + "amplitude test genuinely failed on a step the room was given. The other %d had "
                        .formatted(vacuous.size())
                + "NO judged step at all when the case confirmed, which is the vacuous route: the "
                + "leaf tests that the step response is missing, and a step that was never "
                + "commanded is missing in exactly the same way as one that was ignored.\n");
        return out.toString();
    }

    /**
     * Why neither sensor row reaches the drift leaf, counted rather than argued. The leaf wants two
     * things in the same instant — an overnight floor above {@code outdoor + tolerance} and a step
     * response that still matched — and this reports, per mechanism, how many confirmations had the
     * first, how many had the second, and how many had both. Two mechanisms fail it in two different
     * places, which is the whole reason for adding the second one.
     */
    private static String whyTheDriftLeafIsUnreached(List<Run> runs) {
        StringBuilder out = new StringBuilder("  why neither sensor row reaches the drift leaf. It "
                + "wants an informative overnight floor AND a matched step response, in the same "
                + "instant, and the classification is computed at the confirmation:\n");
        out.append(ResultFiles.table(
                List.of("injected", "confirmed", "floor informative", "step MATCHED", "both",
                        "earliest confirmation min", "latest confirmation min"),
                leafConditions(runs)));
        out.append("  the floor is a minimum over %d min, so a confirmation sooner than that after "
                        .formatted(MonitorParameters.defaults().overnightWindow().toMinutes())
                + "the onset is classified against a floor from before the fault. A ramp can outlive "
                + "the window — it keeps growing, so it stays confirmable after a day — and by then "
                + "the offset it has accumulated has inflated the generation estimate past the step "
                + "amplitude, which is the pair of bounds T-15 already reported. A step offset keeps "
                + "its amplitude and never reaches the window instead: with the inner loop running it "
                + "confirms within the hour, and with the inner loop off nothing re-anchors the "
                + "observer, the quasi-steady estimate absorbs a constant, and it never confirms at "
                + "all. The second mechanism swaps one impossibility for the other rather than "
                + "removing either.\n");
        out.append(highestOffsetFloor(runs));
        return out.toString();
    }

    /**
     * How close the best-placed step offset got to an informative floor, and why that is as close as
     * the arithmetic allows. It is worth a line of its own because it prices the conjunction: the
     * floor's tolerance is sized for the noise of a 1440-sample minimum, so the offset has to be
     * about twice the tolerance before the floor can report it — and an offset that large confirms
     * in minutes, which is the other half of the bind.
     */
    private static String highestOffsetFloor(List<Run> runs) {
        return runs.stream()
                .filter(run -> run.injected() == Injected.SENSOR_OFFSET && run.firstMinute() >= 0
                        && Double.isFinite(run.floorAtConfirmationPpm()))
                .max(java.util.Comparator.comparingDouble(Run::floorAtConfirmationPpm))
                .map(run -> ("  the closest a step offset came: %.0f ppm injected, confirmed %d min "
                        + "in, overnight floor %.0f ppm against the %.0f ppm the leaf needs. The "
                        + "floor is a minimum over %d noisy samples, so it sits about 3.3 sigma — "
                        + "very nearly %.0f ppm — below the level the room is actually holding; an "
                        + "offset therefore has to be roughly twice the %.0f ppm tolerance before the "
                        + "floor can carry it, and every swept offset that large confirms within "
                        + "minutes.%n")
                        .formatted(run.magnitude(), run.firstMinute(), run.floorAtConfirmationPpm(),
                                Topology.OUTDOOR.value()
                                        + MonitorParameters.defaults().overnightFloorTolerancePpm(),
                                MonitorParameters.defaults().overnightWindow().toMinutes(),
                                MonitorParameters.defaults().overnightFloorTolerancePpm(),
                                MonitorParameters.defaults().overnightFloorTolerancePpm()))
                .orElse("");
    }

    private static List<List<String>> leafConditions(List<Run> runs) {
        List<List<String>> rows = new ArrayList<>();
        for (Injected injected : List.of(Injected.SENSOR_DRIFT, Injected.SENSOR_OFFSET)) {
            List<Run> confirmed = runs.stream()
                    .filter(run -> run.injected() == injected && run.firstMinute() >= 0).toList();
            if (confirmed.isEmpty()) {
                continue;
            }
            rows.add(List.of(injected.name(), String.valueOf(confirmed.size()),
                    String.valueOf(confirmed.stream().filter(Run::floorInformative).count()),
                    String.valueOf(confirmed.stream()
                            .filter(run -> run.stepAtConfirmation().matched()).count()),
                    String.valueOf(confirmed.stream().filter(run -> run.floorInformative()
                            && run.stepAtConfirmation().matched()).count()),
                    String.valueOf(confirmed.stream().mapToInt(Run::firstMinute).min().orElse(-1)),
                    String.valueOf(confirmed.stream().mapToInt(Run::firstMinute).max().orElse(-1))));
        }
        return rows;
    }

    /** How often one sensor mechanism was named, and where its detection boundary sits. */
    private static void sensorFindings(List<Run> runs, StringBuilder out, Injected injected,
            double[] magnitudes, String unit) {
        List<Run> group = runs.stream().filter(run -> run.injected() == injected).toList();
        long named = group.stream()
                .filter(run -> run.emitted() == FaultClassification.SENSOR_DRIFT).count();
        out.append("  SENSOR_DRIFT was emitted by %d of the %d runs with %s injected, over "
                        .formatted(named, group.size(), injected.name())
                + "magnitudes %s %s and both arms.%n"
                        .formatted(java.util.Arrays.toString(magnitudes), unit));
        for (Arm arm : Arm.values()) {
            out.append("  %-11s %-13s: lowest swept magnitude at which any sweep seed confirms, %s "
                            .formatted(arm.name(), injected.name(),
                                    boundary(runs, arm, injected, magnitudes, false))
                    + "%s; at which all %d do, %s %s.%n".formatted(unit, SWEEP_SEEDS.length,
                            boundary(runs, arm, injected, magnitudes, true), unit));
        }
    }

    /**
     * The drift rate at which an arm's sweep seeds start confirming — the lowest swept rate where
     * every one of them does, or where any of them does. Read off the rows rather than written
     * into the prose, because the first draft of this experiment's write-up rounded the two
     * together and claimed a boundary one grid step below the one the runs show.
     */
    private static String boundary(List<Run> runs, Arm arm, Injected injected, double[] magnitudes,
            boolean every) {
        for (double magnitude : magnitudes) {
            List<Run> group = runs.stream()
                    .filter(run -> run.injected() == injected && run.arm() == arm
                            && run.rate() == magnitude
                            && java.util.Arrays.stream(SWEEP_SEEDS).anyMatch(s -> s == run.seed()))
                    .toList();
            long confirmed = group.stream()
                    .filter(run -> run.emitted() != FaultClassification.NONE).count();
            if (!group.isEmpty() && (every ? confirmed == group.size() : confirmed > 0)) {
                return "%.0f".formatted(magnitude);
            }
        }
        return "any swept";
    }

    // ----- the runs --------------------------------------------------------------------------

    /** One run, so that the tests can pin a cell of the matrix without replaying all 142. */
    static Run run(Arm arm, Injected injected, long seed, double rate) {
        return arm == Arm.CLOSED_LOOP ? closedLoop(injected, seed, rate) : stepped(injected, seed, rate);
    }

    private static Run closedLoop(Injected injected, long seed, double rate) {
        SystemHarness harness = new SystemHarness(seed);
        harness.tickFor(WARMUP);
        boolean quiet = nothingLatched(harness);
        inject(harness, injected, rate);
        return observe(Arm.CLOSED_LOOP, injected, seed, rate, harness, quiet, OBSERVATION, -1);
    }

    private static Run stepped(Injected injected, long seed, double rate) {
        SystemHarness harness = new SystemHarness(seed);
        harness.innerLoopRunning(false);
        harness.supervisorRunning(false);
        inject(harness, injected, rate);
        return observe(Arm.STEPPED, injected, seed, rate, harness, true,
                HISTORY.plus(JUDGE), HISTORY.toMinutes());
    }

    static void inject(SystemHarness harness, Injected injected, double rate) {
        switch (injected) {
            case NOTHING -> {
                // The control arm. Nothing is injected and the row it produces is R-3's.
            }
            case STUCK_DAMPER -> harness.inject(InjectedFault.stuckDamper(
                    FAULTED, Topology.MINIMUM_POSITION, harness.now()));
            case BLOCKED_FILTER -> harness.inject(
                    InjectedFault.blockedFilter(BLOCKAGE, harness.now()));
            case SENSOR_DRIFT -> harness.inject(
                    InjectedFault.sensorDrift(FAULTED, rate, harness.now()));
            case SENSOR_OFFSET -> harness.inject(
                    InjectedFault.sensorOffset(FAULTED, rate, harness.now()));
        }
    }

    /**
     * Ticks the window out, recording the first confirmed case anywhere and leaving the run to
     * finish so that the end-of-window columns describe a whole window rather than the moment the
     * first room happened to latch.
     *
     * @param ladderMinute the minute the command ladder is issued, or -1 in the closed-loop arm,
     *                     where the inner loop issues the commands the deployed system issues
     */
    private static Run observe(Arm arm, Injected injected, long seed, double rate,
            SystemHarness harness, boolean quietAtOnset, Duration window, long ladderMinute) {
        ZoneId firstZone = null;
        FaultClassification emitted = FaultClassification.NONE;
        int firstMinute = -1;
        int judgedSteps = 0;
        double floorAtConfirmation = Double.NaN;
        StepResponse stepAtConfirmation = StepResponse.NOT_JUDGED;
        for (long minute = 1; minute <= window.toMinutes(); minute++) {
            harness.tick();
            if (minute == ladderMinute) {
                for (int index = 0; index < Topology.ZONE_IDS.size(); index++) {
                    harness.command(Topology.ZONE_IDS.get(index), LADDER[index], LADDER_REASON);
                }
            }
            if (firstZone == null) {
                for (ZoneId zone : Topology.ZONE_IDS) {
                    if (harness.monitor().stateOf(zone).isLatched()) {
                        firstZone = zone;
                        firstMinute = (int) minute;
                        emitted = harness.monitor().caseOf(zone).classification();
                        judgedSteps = judgedSteps(harness);
                        floorAtConfirmation = harness.monitor().overnightFloorOf(zone);
                        stepAtConfirmation = harness.monitor().stepResponseOf(zone);
                        break;
                    }
                }
            }
        }

        long roomsConfirmed = Topology.ZONE_IDS.stream()
                .filter(zone -> harness.monitor().stateOf(zone).isLatched()).count();
        FaultClassification injectedEnd = harness.monitor().caseOf(FAULTED) == null
                ? FaultClassification.NONE
                : harness.monitor().caseOf(FAULTED).classification();
        double magnitude = switch (injected) {
            case NOTHING -> 0;
            case STUCK_DAMPER -> Topology.MINIMUM_POSITION.value();
            case BLOCKED_FILTER -> BLOCKAGE;
            case SENSOR_DRIFT, SENSOR_OFFSET -> rate;
        };
        // What the sensor was wrong by when the case confirmed: a ramp's accumulation, and a step
        // offset's own magnitude, which is the same number from its first instant.
        double offset = firstMinute < 0 ? 0 : switch (injected) {
            case SENSOR_DRIFT -> rate * firstMinute / 60.0;
            case SENSOR_OFFSET -> rate;
            case NOTHING, STUCK_DAMPER, BLOCKED_FILTER -> 0;
        };
        return new Run(arm, injected, seed, rate, magnitude, quietAtOnset, emitted, firstZone,
                firstMinute, firstMinute >= 0 && ladderMinute >= 0 && firstMinute < ladderMinute,
                FAULTED.equals(firstZone), (int) roomsConfirmed, judgedSteps,
                harness.monitor().stateOf(FAULTED), injectedEnd, offset, floorAtConfirmation,
                stepAtConfirmation, diagonal(injected, emitted));
    }

    /**
     * How many commands on the injected room the observer had armed a step window for by this
     * point. It is the column that separates the two ways the tree can reach its stuck-damper
     * leaf: a step that was commanded and did not arrive, and <em>no step at all</em>. The signal
     * is {@code StepResponse}, and the leaf accepts anything that is not {@code MATCHED} — so a
     * room with nothing to compare against still satisfies it. That is deliberate rather than
     * accidental: requiring {@code MISSING} was measured and emptied the leaf, because a probe is
     * requested through the evidence on a confirmed case and so no step has been judged at
     * classification time. A diagnosis reached that way now says so in its own evidence. The count applies the observer's own rule — a command that moves the judged
     * position by more than {@code minimumStep} arms a window, anything smaller is ignored — to
     * the commands the harness carried, because {@code ZoneObserver} is not reachable from here.
     */
    private static int judgedSteps(SystemHarness harness) {
        double minimumStep = MonitorParameters.defaults().minimumStep();
        DamperPosition judged = Topology.MINIMUM_POSITION;
        int steps = 0;
        for (CommandIssued command : harness.damperCommands()) {
            if (!command.zone().equals(FAULTED)) {
                continue;
            }
            if (command.position().differsFrom(judged, minimumStep)) {
                steps++;
            }
            judged = command.position();
        }
        return steps;
    }

    static boolean nothingLatched(SystemHarness harness) {
        return Topology.ZONE_IDS.stream()
                .noneMatch(zone -> harness.monitor().stateOf(zone).isLatched());
    }

    /**
     * The diagonal, spelled out rather than compared by name, so that the one row whose correct
     * answer is <em>silence</em> cannot be mistaken for a missing case.
     */
    static boolean diagonal(Injected injected, FaultClassification emitted) {
        return switch (injected) {
            case NOTHING -> emitted == FaultClassification.NONE;
            case STUCK_DAMPER -> emitted == FaultClassification.STUCK_DAMPER;
            case BLOCKED_FILTER -> emitted == FaultClassification.BLOCKED_FILTER;
            // Both sensor mechanisms score on the one word the monitor has for them.
            case SENSOR_DRIFT, SENSOR_OFFSET -> emitted == FaultClassification.SENSOR_DRIFT;
        };
    }

    record Run(Arm arm, Injected injected, long seed, double rate, double magnitude,
            boolean quietAtOnset, FaultClassification emitted, ZoneId firstZone, int firstMinute,
            boolean confirmedBeforeStep, boolean localised, int roomsConfirmed, int judgedSteps,
            MonitorState injectedEndState, FaultClassification injectedEndClassification,
            double driftOffset, double floorAtConfirmationPpm, StepResponse stepAtConfirmation,
            boolean diagonal) {

        /**
         * Whether the overnight floor had anything to say when the case was classified — the first of
         * the two conditions the sensor-drift leaf tests, applied with the classifier's own limit.
         * A floor still sitting at outdoor is the signature of a case confirmed before a day of the
         * fault had passed, which is a statement about <em>when</em> the tree runs rather than about
         * what it would conclude.
         */
        boolean floorInformative() {
            return Double.isFinite(floorAtConfirmationPpm) && floorAtConfirmationPpm
                    > Topology.OUTDOOR.value()
                            + MonitorParameters.defaults().overnightFloorTolerancePpm();
        }
    }
}
