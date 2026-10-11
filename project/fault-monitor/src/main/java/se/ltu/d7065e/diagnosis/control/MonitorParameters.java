package se.ltu.d7065e.diagnosis.control;

import java.time.Duration;
import se.ltu.d7065e.diagnosis.entity.DiagnosticProbe;

/**
 * Every tunable the diagnosis core has, in one place, so that the report can list what was
 * invented and the evaluation harness can sweep it.
 *
 * <p>Four values are carried over from the T-04 spike and ADR-0008's parameter table and are
 * not invented here: {@code cusumK} = 30 ppm (1 sigma), {@code cusumH} = 150 ppm (5 sigma),
 * the 60 s cycle, and the 420 ppm outdoor concentration the observer reads from
 * {@link se.ltu.d7065e.ventilation.entity.Topology}. Everything else on this record is a
 * number this component had to pick; each one carries the reason in its accessor comment and
 * every one of them belongs in T-32's critique.
 */
public record MonitorParameters(
        double cusumK,
        double cusumH,
        Duration cycleInterval,
        Duration sampleInterval,
        int staleIntervals,
        Duration evaluationWindow,
        int warmupCycles,
        double suspectThresholdFraction,
        int confirmPersistenceCycles,
        int suspectClearCycles,
        double generationGain,
        double anchorGain,
        double occupancyCeilingFactor,
        double minimumStep,
        Duration probeInterval,
        double probeStepIncrease,
        double fanPowerFlatTolerance,
        double fanPowerRiseThreshold,
        int commonModeZones,
        double stepAmplitudeMatchFraction,
        double overnightFloorTolerancePpm,
        Duration overnightWindow,
        Duration integrationStep,
        LowerArm lowerArm,
        ClassifyWhen classifyWhen) {

    public MonitorParameters {
        if (cusumK < 0 || cusumH <= 0) {
            throw new IllegalArgumentException("cusum k must be non-negative and h positive");
        }
        if (cycleInterval == null || cycleInterval.isZero() || cycleInterval.isNegative()) {
            throw new IllegalArgumentException("cycle interval must be positive");
        }
        if (sampleInterval == null || sampleInterval.isZero() || sampleInterval.isNegative()) {
            throw new IllegalArgumentException("sample interval must be positive");
        }
        if (integrationStep == null || integrationStep.isZero() || integrationStep.isNegative()) {
            throw new IllegalArgumentException("integration step must be positive");
        }
        if (staleIntervals < 1) {
            throw new IllegalArgumentException("staleness needs at least one interval");
        }
        if (confirmPersistenceCycles < 1 || suspectClearCycles < 1 || warmupCycles < 1) {
            throw new IllegalArgumentException("cycle counts must be at least one");
        }
        if (!(generationGain > 0 && generationGain <= 1)) {
            throw new IllegalArgumentException("generation gain must be within (0,1]");
        }
        if (!(anchorGain > 0 && anchorGain <= 1)) {
            throw new IllegalArgumentException("anchor gain must be within (0,1]");
        }
        if (occupancyCeilingFactor < 1) {
            throw new IllegalArgumentException("the occupancy ceiling may not sit below design");
        }
        if (lowerArm == null) {
            throw new IllegalArgumentException("the lower arm needs a setting; see LowerArm");
        }
        if (commonModeZones < 2) {
            throw new IllegalArgumentException("common mode needs at least two zones");
        }
    }

    /**
     * The defaults the containers and the evaluation harness both start from.
     *
     * <p>Invented here, with the reason each was picked:
     * <ul>
     * <li><b>sampleInterval = 60 s, staleIntervals = 3</b> — R-4's "three sample intervals" is
     *     measured against the monitor's own judging cadence, not the sensor's publish rate.
     *     {@code co2-sensor} publishes every second; the monitor judges one reading per cycle,
     *     so three missed cycles is the stale limit.
     * <li><b>evaluationWindow = 10 min</b> — the spike detected a stuck damper 5–11 min after
     *     the ignored command, so a window shorter than 11 min would throw away the slowest
     *     detection it measured, and a much longer one lets occupancy drift into the judgement.
     * <li><b>warmupCycles = 3</b> — the observer needs a generation estimate before it can
     *     predict anything, and the estimate is a three-cycle smoother at this gain.
     * <li><b>suspectThresholdFraction = 0.6 of h</b> — 90 ppm at the default limit. A fraction
     *     rather than a fixed ppm figure so that T-24's sweep of {@code h} carries the suspicion
     *     threshold with it. SUSPECT costs only a frozen generation estimate and clears on its
     *     own, but it is not free: while a zone is suspect the observer stops correcting towards
     *     the measurement, so a threshold low enough for noise to reach regularly would leave the
     *     prediction running open-loop for no reason. At 0.6 of h the statistic has to make an
     *     excursion that noise alone almost never manages, while a genuine fault passes it one
     *     or two cycles before it passes h.
     * <li><b>confirmPersistenceCycles = 2</b> — R-3. See {@link #confirmPersistenceCycles()}.
     * <li><b>suspectClearCycles = 3</b> — SUSPECT is not latched (only CONFIRMED is), so a
     *     statistic that has decayed to zero for three cycles returns the zone to HEALTHY.
     * <li><b>generationGain = 0.3</b> — a roughly three-cycle smoother on the quasi-steady
     *     generation estimate: fast enough to follow a room filling up, slow enough that one
     *     noisy sample does not move the believed generation far.
     * <li><b>anchorGain = 0.3</b> — the gain at which the <em>believed prediction</em> is pulled
     *     towards each reading between commands, and the only one here whose counterfactual was
     *     measured. Re-anchoring it hard to each new reading makes the published residual the
     *     difference of two noisy samples rather than of a sample and a model, which widens it by
     *     the square root of two and collapses the in-control run length from about seventy-nine
     *     days to about half a day. A partial anchor keeps the prediction tied to reality without
     *     buying that.
     *     <p>It used to be applied to the two plausibility guards as well, and that is what ADR-0010
     *     removed: a bound pulled 30 % of the way onto every reading is a filter of the reading, so
     *     both CUSUM arms were driven by a filter error rather than by an occupancy argument, and
     *     R-3 failed at 1.30 confirmed cases per fault-free week. The gain is therefore no longer
     *     "the value that decides R-3" — what decides R-3 is that the guards are bounds.
     * <li><b>occupancyCeilingFactor = 1.25</b> — the plausibility guard runs at a quarter above
     *     the room's design occupancy, because a room genuinely runs over its design head-count
     *     often enough that the monitor must not blame the damper for it. The cost is that a room
     *     more than a quarter over design can be misdiagnosed, and that detection is less
     *     sensitive in a half-empty room. See {@link ZoneObserver} for what the guard is for.
     * <li><b>minimumStep = 0.05</b> — a damper nudge smaller than five points of travel is not
     *     a step response worth judging; it only costs a frozen generation estimate.
     * <li><b>probeInterval = 30 min, probeStepIncrease = {@link DiagnosticProbe#STEP_INCREASE}</b>
     *     — T-18's active probe. Long enough that the probe is not itself a control action, large
     *     enough that the response clears the 2 sigma shape tolerance below. The amplitude is
     *     owned by {@code DiagnosticProbe} rather than written here, because the supervisor
     *     commands the step this parameter asks for and the two must agree.
     * <li><b>fanPowerFlatTolerance = 0.05, fanPowerRiseThreshold = 0.10</b> — "flat" and "up"
     *     for the one cross-room signal, as fractions of the power the affinity law predicts.
     * <li><b>commonModeZones = 5</b> — five of six residuals up is common mode. Six of six
     *     would make one noisy sensor able to veto a blocked-filter diagnosis.
     * <li><b>stepAmplitudeMatchFraction = 0.3</b> — a step response that delivered at least this
     *     fraction of the predicted amplitude, in the same direction, counts as a response that
     *     happened. A fraction rather than a ppm tolerance because the discriminator has to work
     *     on a room whose level is wrong: a drifting sensor inflates the observer's generation
     *     estimate, which inflates the predicted amplitude too, so comparing amplitudes in ppm
     *     would call a drifting sensor a jammed damper. The ratio survives that, because the
     *     sensor offset cancels out of the measured change. No upper bound: a response larger
     *     than predicted is not evidence of a damper that did not move.
     *     <p>The fraction is the midpoint of the two populations it has to separate, and both
     *     ends of that interval are computable. A jammed damper delivers none of the response, so
     *     its ratio is centred on zero. A drifting sensor delivers all of the <em>true</em>
     *     response against an amplitude the inflated generation estimate predicts, and at
     *     quasi-steady state the arithmetic comes out exactly
     *     {@code G_true / (G_true + offset * Q_0)}: the observer learns
     *     {@code G_est = Q_0 * (C_measured - C_out)} from a room whose reading carries the offset,
     *     so its estimate is too high by {@code offset * Q_0} and the amplitude it predicts is too
     *     large in the same proportion. That expression is a function of the <em>plant</em>, which
     *     is why this parameter is not plant-independent and why resizing the air handler moved it.
     *     At A115's 10-person design occupancy ({@code G_true} = 50 ppm·m³/s) and the project's
     *     350 ppm reference drift, the old 0.42 m³/s plant put the drift population at 0.70 and
     *     the resized 0.59 m³/s plant puts it at 0.63 — more air means a given sensor offset
     *     inflates the generation estimate by more. Against a measured 0.10 standard deviation on
     *     the estimate, 0.5 was a 1.3 sigma margin on the resized plant where it had been 2 sigma,
     *     and seed 42's drifting sensor duly came out as a stuck damper. The midpoint of 0.63 and
     *     0 is 0.31; 0.3 is that, and it restores about 3 sigma on both sides.
     *     <p>Lowering it also raises the largest drift the discriminator can still call drift,
     *     since the drift ratio falls as the offset grows. Solving the same expression for the
     *     offset at which the ratio reaches the threshold gives about 580 ppm at 0.5 and about
     *     1360 ppm at 0.3 on this plant — an algebraic bound, so the usable one is lower; the 350
     *     ppm reference drift sits comfortably inside both and a 500 ppm one already confirms a
     *     case before any command is judged. A drift past the bound is read as a stuck damper,
     *     which is a real limit of a three-signal taxonomy and belongs in the critique.
     * <li><b>overnightFloorTolerancePpm = 100 ppm, overnightWindow = 24 h</b> — a healthy room
     *     returns to 420 ppm with nobody in it; a floor above 520 ppm over a full day is the
     *     drift signature. 100 ppm is a little over 3 sigma of sensor noise. The low-side drift
     *     test in {@link FaultClassifier} reuses the same number at twice the magnitude, for the
     *     arithmetic reason given there.
     * <li><b>integrationStep = 1 s</b> — the spike's forward-Euler step, accurate to well under
     *     1 ppm against time constants of tens of minutes.
     * <li><b>lowerArm = GUARDED</b> — T-32 section 5's two-sided statistic, on by default and
     *     behind its plausibility guard. {@code GUARDED} and {@code OFF} both keep R-3 and
     *     {@code HONEST} does not; the claim this bullet used to make — that {@code GUARDED} was
     *     the only setting that kept R-3 — was true of a measurement in which {@code OFF} also
     *     kept it, so it never distinguished the two. What the arm cost R-3 was measured
     *     separately and was not nothing: before ADR-0010, 7 of the 13 primary false confirmations
     *     over 20 fault-free weeks of the deployed stack were the lower arm's, raised against an
     *     empty-room trajectory that a legitimate departure could cross. That trajectory is gone
     *     and the arm now watches the outdoor clamp alone. A setting rather than a constant because
     *     a second arm is the one change in this component that can only make R-3 worse, so the
     *     measurement that justifies it has to be reproducible with the arm off. See
     *     {@link LowerArm}.
     * </ul>
     */
    public static MonitorParameters defaults() {
        return new MonitorParameters(
                30.0,
                150.0,
                Duration.ofSeconds(60),
                Duration.ofSeconds(60),
                3,
                Duration.ofMinutes(10),
                3,
                0.6,
                2,
                3,
                0.3,
                0.3,
                1.25,
                0.05,
                Duration.ofMinutes(30),
                DiagnosticProbe.STEP_INCREASE,
                0.05,
                0.10,
                5,
                0.3,
                100.0,
                Duration.ofHours(24),
                Duration.ofSeconds(1),
                LowerArm.GUARDED,
                ClassifyWhen.ON_CONFIRMATION);
    }

    public MonitorParameters withCusum(double k, double h) {
        return new MonitorParameters(k, h, cycleInterval, sampleInterval, staleIntervals,
                evaluationWindow, warmupCycles, suspectThresholdFraction, confirmPersistenceCycles,
                suspectClearCycles, generationGain, anchorGain, occupancyCeilingFactor, minimumStep,
                probeInterval, probeStepIncrease, fanPowerFlatTolerance, fanPowerRiseThreshold,
                commonModeZones, stepAmplitudeMatchFraction, overnightFloorTolerancePpm,
                overnightWindow, integrationStep, lowerArm, classifyWhen);
    }

    public MonitorParameters withCycleInterval(Duration cycle) {
        return new MonitorParameters(cusumK, cusumH, cycle, cycle, staleIntervals,
                evaluationWindow, warmupCycles, suspectThresholdFraction, confirmPersistenceCycles,
                suspectClearCycles, generationGain, anchorGain, occupancyCeilingFactor, minimumStep,
                probeInterval, probeStepIncrease, fanPowerFlatTolerance, fanPowerRiseThreshold,
                commonModeZones, stepAmplitudeMatchFraction, overnightFloorTolerancePpm,
                overnightWindow, integrationStep, lowerArm, classifyWhen);
    }

    /**
     * Varies the plausibility ceiling's headroom, which is the one parameter that sets where the
     * detector sits on the R-1/R-3 trade: the ceiling is the trajectory of a room at this multiple
     * of its design occupancy, so a larger factor accuses less and detects less. ADR-0010 reports
     * the curve this sweeps.
     */
    public MonitorParameters withOccupancyCeiling(double factor) {
        return new MonitorParameters(cusumK, cusumH, cycleInterval, sampleInterval, staleIntervals,
                evaluationWindow, warmupCycles, suspectThresholdFraction, confirmPersistenceCycles,
                suspectClearCycles, generationGain, anchorGain, factor, minimumStep,
                probeInterval, probeStepIncrease, fanPowerFlatTolerance, fanPowerRiseThreshold,
                commonModeZones, stepAmplitudeMatchFraction, overnightFloorTolerancePpm,
                overnightWindow, integrationStep, lowerArm, classifyWhen);
    }

    /** Chooses what the lower CUSUM arm is driven by, or turns it off; see {@link LowerArm}. */
    public MonitorParameters withLowerArm(LowerArm arm) {
        return new MonitorParameters(cusumK, cusumH, cycleInterval, sampleInterval, staleIntervals,
                evaluationWindow, warmupCycles, suspectThresholdFraction, confirmPersistenceCycles,
                suspectClearCycles, generationGain, anchorGain, occupancyCeilingFactor, minimumStep,
                probeInterval, probeStepIncrease, fanPowerFlatTolerance, fanPowerRiseThreshold,
                commonModeZones, stepAmplitudeMatchFraction, overnightFloorTolerancePpm,
                overnightWindow, integrationStep, arm, classifyWhen);
    }

    /** Chooses when a confirmed case is classified; see {@link ClassifyWhen}. */
    public MonitorParameters withClassifyWhen(ClassifyWhen when) {
        return new MonitorParameters(cusumK, cusumH, cycleInterval, sampleInterval, staleIntervals,
                evaluationWindow, warmupCycles, suspectThresholdFraction, confirmPersistenceCycles,
                suspectClearCycles, generationGain, anchorGain, occupancyCeilingFactor, minimumStep,
                probeInterval, probeStepIncrease, fanPowerFlatTolerance, fanPowerRiseThreshold,
                commonModeZones, stepAmplitudeMatchFraction, overnightFloorTolerancePpm,
                overnightWindow, integrationStep, lowerArm, when);
    }

    /**
     * How long a reading may be the newest one before the zone goes BLIND (R-4): three sample
     * intervals.
     */
    public Duration staleLimit() {
        return sampleInterval.multipliedBy(staleIntervals);
    }

    /** Where HEALTHY becomes SUSPECT, as a fraction of the confirmation limit. */
    public double suspectThresholdPpm() {
        return suspectThresholdFraction * cusumH;
    }

    /**
     * R-3's margin. The T-04 spike's worst healthy seed peaked at 149.1 ppm against
     * {@code h} = 150 ppm — a one-ppm margin, which is luck, not design. Raising {@code h}
     * blindly would buy the margin with detection delay against R-1's 15 min, and the spike's
     * own sweep shows 8 sigma already costs a minute. Requiring the statistic to stay above
     * {@code h} for two consecutive cycles buys the margin from the noise's time structure
     * instead: a single tail draw that pushes the statistic over the limit is gone on the next
     * sample because the CUSUM subtracts {@code k} from every sample, whereas a stuck damper's
     * residual grows monotonically and clears the limit on every cycle from the first. The
     * spike's own numbers price it: the stuck-damper statistic passes {@code h} and keeps
     * rising, so one extra cycle costs one minute of the fifteen R-1 allows.
     */
    public int confirmPersistenceCycles() {
        return confirmPersistenceCycles;
    }

    /**
     * What the lower CUSUM arm accumulates on, or {@link LowerArm#OFF} (T-32 section 5). Default
     * {@link LowerArm#GUARDED}, because the faults the second arm sees include a sensor drifting
     * low, and a sensor that under-reads makes the inner loop under-ventilate an occupied room —
     * the one fault in this taxonomy with a safety consequence, and the one an upper arm reports
     * as perfect health.
     *
     * <p>{@code OFF} is not a feature flag for a future change, it is the control arm of the R-3
     * experiment: with it the detector is bit for bit the one the T-04 spike measured, so
     * {@code FalseAlarmWeekTest} can put the two-sided false-alarm counts beside the one-sided
     * one instead of asserting an unmeasured claim about them.
     */
    public LowerArm lowerArm() {
        return lowerArm;
    }

    /**
     * When a confirmed case is classified (see {@link ClassifyWhen}).
     *
     * <p>Default {@link ClassifyWhen#ON_CONFIRMATION}, and the reason is T-35's numbers rather than
     * a preference. {@code AFTER_PROBE} works: over 88 paired runs the case latches on the same
     * cycle every time and the probe really is judged before the fault is named in 31 of the 39
     * runs that defer, against none under the deployed order. It also changes nothing — the settled
     * classification is identical in all 88 pairs — because the judged step comes back
     * {@code MISSING} whatever is wrong, including on eleven rooms whose dampers are in perfect
     * health. {@code AFTER_PROBE} stays reachable because that is the result, in the same sense as
     * {@link LowerArm#HONEST}: without it the comparison cannot be re-run.
     */
    public ClassifyWhen classifyWhen() {
        return classifyWhen;
    }
}
