package se.ltu.d7065e.diagnosis.control;

import java.time.Duration;
import java.time.Instant;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;

/**
 * T-11 — predicts the CO2 response a damper command should have produced, <em>without
 * occupancy</em>.
 *
 * <h2>Why there is no head count in this class</h2>
 *
 * The T-04 spike's observer used the true occupancy {@code n}, and said so: that was a
 * deliberate simplification, and the plan's risk register names the consequence — "observer
 * needs occupancy it cannot have". A real building has no head count. There is no people
 * counter in the BuildSim model, an occupancy schedule is a guess that would be wrong on every
 * late meeting, and an observer that needs {@code n} is an observer that cannot be deployed.
 * The plan's mitigation is "restrict detection to commanded step responses only", and this
 * class is that answer.
 *
 * <h2>The method</h2>
 *
 * <ol>
 * <li><b>Learn the generation term from quasi-steady state, not from people.</b> At steady
 *     state the mass balance {@code V*dC/dt = G - Q*(C - C_out)} has {@code dC/dt = 0}, so
 *     {@code G = Q*(C_measured - C_out)}. Multiplied into ppm, {@code G_est} is in ppm*m3/s and
 *     lumps together everything that makes CO2 in the room — occupants, their number, where
 *     they sit — without naming any of it. It is updated only while the zone is HEALTHY (or
 *     warming up) and no command step is being judged, by exponential smoothing at
 *     {@link MonitorParameters#generationGain()}; a slow estimate is the point, because the
 *     quasi-steady assumption is false during a transient.
 * <li><b>Freeze it at the step and integrate the believed model forward.</b> When a command
 *     arrives, {@code G_est} is frozen and the prediction is re-anchored to the CO2 that was
 *     actually measured at the step, then integrated as
 *     {@code V*dC_hat/dt = G_est - Q_new*(C_hat - C_out)}, where {@code Q_new} comes from the
 *     commanded position through ADR-0008's shared-AHU split in {@link AhuFlowModel} — fan
 *     flow {@code Q_nom*s*(1-beta)} split over {@code a_i = position * damperAuthority}. The
 *     monitor believes {@code beta = 0} until it has diagnosed a blocked filter, and
 *     <em>that belief gap is the blocked-filter signal</em>.
 * <li><b>Judge only inside a window.</b> The prediction runs free for
 *     {@link MonitorParameters#evaluationWindow()} (10 min) after each step. Outside a window,
 *     while the zone is healthy, the observer re-anchors to the measurement every cycle and
 *     carries no opinion at all: between commands it has no occupancy information, so the
 *     residual it reports there is a one-cycle-ahead prediction error, which is noise plus one
 *     cycle of model error. This is what keeps a room filling up in the morning from looking
 *     like a fault — the observer is not predicting the morning, it is predicting the response
 *     to a command.
 * <li><b>Guard the judgement from both sides.</b> A command step and an
 *     occupancy step can arrive in the same minute, and then a frozen {@code G_est} would
 *     blame the damper for the people. The observer therefore integrates a second trajectory,
 *     the <em>ceiling</em>, with the generation of a room filled to its design occupancy — a
 *     number ADR-0008's design-flow rule makes recoverable from the topology, since
 *     {@code designFlow = 7 l/s per person + 0.35 l/s per m2}, so
 *     {@code persons = (damperAuthority - 0.35*area)/7}. CO2 below the ceiling is CO2 that
 *     arriving people could explain; CO2 above it is CO2 that a full room at the commanded
 *     airflow could not have produced, and that is an accusation about the airflow rather than
 *     about the people. {@link ZoneMonitor} drives the CUSUM from the ceiling residual for that
 *     reason. In a room at its design occupancy the two trajectories coincide and the detector
 *     is exactly the one the spike measured; in an emptier room the guard costs detection
 *     sensitivity, and the report owns that.
 * </ol>
 *
 * <p>The residual the component publishes is the honest one, {@code r = C_measured - C_hat}
 * against the frozen-{@code G_est} prediction (T-12). The ceiling is what suspicion is measured
 * against, not what gets reported.
 */
public final class ZoneObserver {

    /** ppm is a volume fraction times 1e6, so m3/s of CO2 becomes ppm*m3/s after times 1e6. */
    private static final double CUBIC_METRES_TO_PPM = 1e6;

    private static final double LITRES_PER_SECOND_PER_PERSON = 7.0;
    private static final double LITRES_PER_SECOND_PER_SQUARE_METRE = 0.35;

    private final VentilationZone zone;
    private final AhuFlowModel flow;
    private final MonitorParameters parameters;
    private final double outdoorPpm = Topology.OUTDOOR.value();
    private final double ceilingGeneration;

    private double generationEstimate;
    private double predictedPpm;
    private double ceilingPpm;
    private double floorPpm;
    private Instant anchoredAt;
    private DamperPosition judgedPosition;
    private DamperPosition agreedPosition;
    private CommandId stepCommand;
    private double measuredAtStep;
    private double predictedAtStep;
    private double amplitudeMeasuredSum;
    private double amplitudePredictedSum;
    private Instant windowEndsAt;

    public ZoneObserver(VentilationZone zone, AhuFlowModel flow, MonitorParameters parameters) {
        this.zone = zone;
        this.flow = flow;
        this.parameters = parameters;
        this.ceilingGeneration = designOccupancy(zone) * parameters.occupancyCeilingFactor()
                * Topology.GENERATION_PER_PERSON * CUBIC_METRES_TO_PPM;
        this.predictedPpm = outdoorPpm;
        this.ceilingPpm = outdoorPpm;
        this.floorPpm = outdoorPpm;
        this.judgedPosition = zone.commandedPosition();
        this.agreedPosition = zone.commandedPosition();
    }

    /**
     * The room's design occupancy, recovered from ADR-0008's design-flow rule rather than
     * configured a second time. A room whose authority does not cover its floor area would
     * give a negative count, which is read as an unoccupied room rather than thrown on: the
     * ceiling is a guard, and a guard that refuses to start is worse than a tight one.
     */
    static double designOccupancy(VentilationZone zone) {
        double perArea = LITRES_PER_SECOND_PER_SQUARE_METRE * zone.floorAreaSquareMetres();
        return Math.max(0, (zone.damperAuthority() - perArea) / LITRES_PER_SECOND_PER_PERSON);
    }

    /**
     * Starts the observer, or restarts it after a BLIND gap or a service. The generation estimate
     * is seeded from the quasi-steady reading rather than from zero, because a zero estimate
     * predicts a room emptying out and three warmup cycles of smoothing would not have recovered
     * from it by the time the first command arrives.
     */
    public void reset(double measuredPpm, Instant at) {
        predictedPpm = measuredPpm;
        ceilingPpm = measuredPpm;
        floorPpm = Math.max(outdoorPpm, measuredPpm);
        anchoredAt = at;
        stepCommand = null;
        windowEndsAt = null;
        amplitudeMeasuredSum = 0;
        amplitudePredictedSum = 0;
        judgedPosition = flow.positionOf(zone.id());
        agreedPosition = judgedPosition;
        generationEstimate = Math.max(0,
                flow.believedFlowTo(zone.id()) * (measuredPpm - outdoorPpm));
    }

    /**
     * A command on this zone. Freezes the generation estimate and re-anchors both trajectories
     * to the CO2 measured at the step, which is what makes the residual a statement about this
     * command rather than about the whole history. A nudge below
     * {@link MonitorParameters#minimumStep()} is not judged: it would cost a frozen estimate
     * and tell nothing.
     */
    public void stepCommanded(CommandIssued command, double measuredPpm, Instant now) {
        // Against the position this observer last judged, not against the shared flow model's: the
        // model holds all six rooms and a command on any of them writes to it, so one command
        // reaching six observers would have the commanded room's own step already applied by the
        // time its own observer looked, and the window would silently never arm.
        DamperPosition previous = judgedPosition;
        judgedPosition = command.position();
        flow.positionCommanded(zone.id(), command.position());
        if (!command.position().differsFrom(previous, parameters.minimumStep())) {
            return;
        }
        // Everything up to this instant was predicted at the previous position and the residual
        // agreed with it, so that is the last position the model and the measurement were of one
        // mind about. If the room answers this step, recordStepAmplitude moves it forward.
        agreedPosition = previous;
        Instant at = command.at().value().isAfter(now) ? now : command.at().value();
        // Integrate forward from the CO2 measured at the step — but from the *corrected* estimate
        // of it, which is what predictedPpm already is: between commands the prediction is pulled
        // towards every reading at the anchor gain, so it is the filtered measurement. Re-anchoring
        // to the single raw sample at the step instead would inject one full sigma of sensor noise
        // into the anchor and hold it for the whole window, which raises the residual's standard
        // deviation by root two and costs the detector an order of magnitude of in-control run
        // length — the very quantity R-3 is a statement about.
        anchoredAt = at;
        measuredAtStep = measuredPpm;
        predictedAtStep = predictedPpm;
        amplitudeMeasuredSum = 0;
        amplitudePredictedSum = 0;
        stepCommand = command.commandId();
        windowEndsAt = at.plus(parameters.evaluationWindow());
    }

    /**
     * A command on some other room still changes this room's believed airflow, because the six
     * dampers share one fan (ADR-0008). It does not arm a window here: this room was not
     * commanded, so there is no step response of its own to judge.
     */
    public void neighbourCommanded(CommandIssued command) {
        flow.positionCommanded(command.zone(), command.position());
    }

    public boolean windowOpen(Instant now) {
        return windowEndsAt != null && !now.isAfter(windowEndsAt);
    }

    /**
     * The three trajectories as they stand, without integrating them. The guards are what the
     * CUSUM arms are measured against, so a test that asserts on the detector's selectivity has to
     * be able to read them rather than infer them from the statistic.
     */
    Prediction prediction() {
        return new Prediction(predictedPpm, ceilingPpm, floorPpm);
    }

    /**
     * The lower bound the guarded arm is measured against: the outdoor concentration, less an
     * allowance for sensor noise.
     *
     * <p>It is a bound that needs no anchor, no command and no occupancy signal, because it is not
     * a statement about a command at all — ventilating a room with 420 ppm air cannot take it below
     * 420 ppm, whatever any damper did.
     *
     * <p>The allowance is {@link MonitorParameters#overnightFloorTolerancePpm()}, reused rather
     * than invented, and for the reason {@link FaultClassifier} already gives for reusing it on its
     * own low-side test: a healthy room resting at the outdoor concentration reads <em>below</em>
     * 420 ppm on about half of its samples, so a bound placed at 420 ppm exactly has no in-control
     * margin at all and a one-sided CUSUM against it is a zero-mean statistic.
     *
     * <h2>Why this replaced an empty-room trajectory (ADR-0010)</h2>
     *
     * The guarded arm used to be driven against {@link #floorPpm}, a trajectory integrated at zero
     * generation from the last anchor and clamped here. That is tighter and therefore looks better,
     * and it is unsound in a direction nothing controls: the trajectory falls at
     * {@code Q_believed} while the room falls at {@code Q_actual}, so whenever the shared split has
     * moved and the belief has not caught up, a perfectly healthy room falls through its own floor.
     * Worse, {@link #correctTowards} pulls that floor <em>up</em> towards every reading, so between
     * commands it is a lagging filter of the measurement rather than a floor at all, and every
     * departure crosses it from above. Measured over 20 fault-free six-room weeks of the deployed
     * stack, 7 of the 13 primary false confirmations were the lower arm's and their hours cluster
     * at 16:00 to 19:00, when offices empty.
     *
     * <p>The clamp has no such failure mode because it is not a trajectory and depends on no belief
     * at all. What it gives up is the damper stuck wide open, which {@link LowerArm#GUARDED} never
     * claimed to see and {@code TwoSidedDetectionTest} already measures it missing.
     */
    double unconditionalFloorPpm() {
        return outdoorPpm - parameters.overnightFloorTolerancePpm();
    }

    public boolean windowJustClosed(Instant now, Duration cycle) {
        return windowEndsAt != null && now.isAfter(windowEndsAt)
                && !now.minus(cycle).isAfter(windowEndsAt);
    }

    public CommandId stepCommand() {
        return stepCommand;
    }

    public double generationEstimate() {
        return generationEstimate;
    }

    /** Integrates both trajectories forward to {@code now} and returns where they landed. */
    public Prediction advance(Instant now) {
        if (anchoredAt == null) {
            return new Prediction(predictedPpm, ceilingPpm, floorPpm);
        }
        double airflow = flow.believedFlowTo(zone.id());
        double ceilingGenerationNow = Math.max(generationEstimate, ceilingGeneration);
        long seconds = Duration.between(anchoredAt, now).getSeconds();
        long step = Math.max(1, parameters.integrationStep().getSeconds());
        for (long elapsed = 0; elapsed < seconds; elapsed += step) {
            long dt = Math.min(step, seconds - elapsed);
            predictedPpm += dt * rate(predictedPpm, generationEstimate, airflow);
            ceilingPpm += dt * rate(ceilingPpm, ceilingGenerationNow, airflow);
            // The floor integrates at zero generation — the emptiest the room could be — and is
            // clamped at outdoor, because ventilating a room with 420 ppm air cannot take it below
            // 420 ppm however long the damper stays open. The clamp is what makes a reading under
            // outdoor impossible rather than merely low, and it is the lower arm's sharpest signal.
            floorPpm = Math.max(outdoorPpm, floorPpm + dt * rate(floorPpm, 0, airflow));
        }
        if (seconds > 0) {
            anchoredAt = now;
        }
        return new Prediction(predictedPpm, ceilingPpm, floorPpm);
    }

    /**
     * Pulls both trajectories towards the measurement at {@link MonitorParameters#anchorGain()}.
     * Called every cycle while the zone is healthy and no step is being judged — see point 3 of
     * the class comment.
     *
     * <p>A correction rather than a hard re-anchor, because snapping the prediction onto the
     * latest reading would make the next residual the difference of two noisy samples: standard
     * deviation root two sigma instead of sigma, which is enough to turn the spike's measured
     * in-control run length of 79 days into about half a day and would hand R-3 a false alarm
     * every few hours across six rooms. The gain is the price of that: a first-order filter on the
     * measurement, which lags a transient by roughly one over the gain cycles. The lag shows up in
     * the published residual during a morning fill and does not reach the CUSUM, because the
     * CUSUM is driven by the plausibility ceiling, which is integrated with a generation term
     * large enough to follow the fill.
     */
    public void correctTowards(double measuredPpm, Instant at) {
        double gain = parameters.anchorGain();
        predictedPpm += gain * (measuredPpm - predictedPpm);
        ceilingPpm += gain * (measuredPpm - ceilingPpm);
        floorPpm = Math.max(outdoorPpm, floorPpm + gain * (measuredPpm - floorPpm));
        anchoredAt = at;
    }

    /** The quasi-steady update of the generation term, smoothed. */
    public void learnGeneration(double measuredPpm) {
        double airflow = flow.believedFlowTo(zone.id());
        double observed = Math.max(0, airflow * (measuredPpm - outdoorPpm));
        double gain = parameters.generationGain();
        generationEstimate = (1 - gain) * generationEstimate + gain * observed;
    }

    /**
     * How much of the predicted step response actually arrived, as a fraction: the measured change
     * since the step over the predicted change since the step. One for a healthy room, around zero
     * for a damper that did not move, and near one for a sensor reading the right change at the
     * wrong level — which is the discriminator T-15 needs between drift and a jammed damper, and
     * the reason this is a ratio and not a difference in ppm. A sensor offset cancels out of the
     * measured change but inflates the observer's generation estimate and so the predicted change,
     * so the ppm difference between the two amplitudes is large for a drifting sensor and would
     * have it diagnosed as a stuck damper.
     *
     * <h2>Why it is accumulated over the window rather than read off one sample</h2>
     *
     * This used to be the instantaneous ratio at whatever cycle asked for it, and the cycle that
     * asks is the cycle the CUSUM confirmed on — which is a biased sample, not an arbitrary one. A
     * confirmation is triggered by the statistic clearing {@code h}, so it lands preferentially on
     * a cycle whose reading drew <em>high</em>; and a high reading is exactly the reading that
     * makes the measured fall look smaller than it was, so it depresses this ratio at the one
     * moment the classifier reads it. On seed 42's drifting sensor the instantaneous ratio at the
     * confirming cycle was 0.34 against an expected 0.6, which is how a drifting sensor came to be
     * diagnosed as a stuck damper.
     *
     * <p>So the comparison is a ratio of sums over the cycles of the window instead,
     * {@code sum(C_measured - C_anchor) / sum(C_hat - C_anchor)}, which is the same quantity with
     * the triggering sample demoted to one term of several. Two details earn their place:
     * <ul>
     * <li>both sums are anchored at {@code predictedAtStep}, the <em>filtered</em> measurement at
     *     the step, rather than at the single raw sample taken there. That is the same argument
     *     {@link #stepCommanded} already makes for the prediction's anchor: a raw sample puts a
     *     full sigma of sensor noise into the anchor and holds it for the whole window, and here it
     *     would do so in every term of the numerator at once.
     * <li>a cycle contributes only once the predicted amplitude has grown past one sigma of sensor
     *     noise. Early in a window the predicted change is a few ppm, so the ratio there is almost
     *     pure noise and averaging it in would add variance rather than remove it.
     * </ul>
     *
     * <p>{@code NaN} when there is no step to compare against, or when the prediction never said
     * the step would do anything measurable, in which case there is nothing to be a fraction of.
     */
    public double stepAmplitudeRatio() {
        if (stepCommand == null
                || Math.abs(amplitudePredictedSum) < Topology.SENSOR_NOISE_SIGMA_PPM) {
            return Double.NaN;
        }
        return amplitudeMeasuredSum / amplitudePredictedSum;
    }

    /**
     * This cycle's contribution to {@link #stepAmplitudeRatio()}, called once per cycle after
     * {@link #advance(Instant)}. It also moves {@link #agreedPosition()} forward once the response
     * has arrived: a step whose amplitude the measurement matched is proof the actuator moved.
     */
    public void recordStepAmplitude(double measuredPpm) {
        if (stepCommand == null) {
            return;
        }
        double predictedChange = predictedPpm - predictedAtStep;
        if (Math.abs(predictedChange) < Topology.SENSOR_NOISE_SIGMA_PPM) {
            return;
        }
        amplitudeMeasuredSum += measuredPpm - predictedAtStep;
        amplitudePredictedSum += predictedChange;
        double ratio = stepAmplitudeRatio();
        if (!Double.isNaN(ratio) && ratio >= parameters.stepAmplitudeMatchFraction()) {
            agreedPosition = judgedPosition;
        }
    }

    /**
     * The last position for which the prediction and the measurement agreed — where this observer
     * last had grounds to believe the damper actually was, as opposed to where it was told to go.
     * It moves forward to a commanded position when the room delivers that command's step
     * response, and it stays behind at the position a step was commanded <em>from</em> while that
     * step's response has not arrived. Before any step has been judged it is the position the zone
     * was configured at, which is the same assumption the flow model starts from.
     */
    public DamperPosition agreedPosition() {
        return agreedPosition;
    }

    /**
     * The opening the room's own CO2 implies, rather than the one it was commanded to take.
     *
     * <p>Two steps, both of them algebra the class already does in the other direction. The
     * quasi-steady mass balance gives the flow: with {@code dC/dt = 0},
     * {@code G_est = Q * (C_measured - C_out)}, so {@code Q = G_est / (C_measured - C_out)}. Then
     * {@link AhuFlowModel#openingImpliedByFlow} inverts the shared-AHU split to turn that flow back
     * into an opening. {@code G_est} is the estimate frozen at the step, learned while the room was
     * healthy, so it is a statement about the room's occupants and not about its damper — which is
     * what makes the inversion a measurement of the damper.
     *
     * <p>{@code NaN} when the quasi-steady assumption has nothing to stand on: no generation
     * estimate yet, or a room sitting at or below the outdoor concentration, where
     * {@code C - C_out} is zero or negative and the flow it implies is infinite or absurd.
     */
    public double inferredPosition(double measuredPpm) {
        double excess = measuredPpm - outdoorPpm;
        if (!(generationEstimate > 0) || !(excess > 0)) {
            return Double.NaN;
        }
        return flow.openingImpliedByFlow(zone.id(), generationEstimate / excess);
    }

    private double rate(double ppm, double generation, double airflow) {
        return (generation - airflow * (ppm - outdoorPpm)) / zone.volumeCubicMetres();
    }

    /**
     * The believed CO2, the most CO2 a room filled to its design occupancy could hold at the
     * commanded airflow, and the least an empty one could — the last clamped at outdoor, since no
     * ventilation takes a room below the concentration of the air it is ventilated with.
     */
    public record Prediction(double predictedPpm, double ceilingPpm, double floorPpm) {}
}
