package se.ltu.d7065e.diagnosis.control;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import se.ltu.d7065e.diagnosis.entity.CusumStatistic;
import se.ltu.d7065e.diagnosis.entity.Evidence;
import se.ltu.d7065e.diagnosis.entity.StepResponse;
import se.ltu.d7065e.diagnosis.entity.FaultCase;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.diagnosis.entity.Residual;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.ResidualComputed;
import se.ltu.d7065e.shared.event.Topics;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.VentilationZone;

/**
 * T-13 and T-18 — one room's detector and its state machine.
 *
 * <p>{@code WARMUP -> HEALTHY -> SUSPECT -> CONFIRMED -> COMPENSATING -> SERVICED -> WARMUP},
 * with {@code BLIND} reachable from WARMUP, HEALTHY and SUSPECT. The three edges that carry the
 * design:
 *
 * <ul>
 * <li><b>BLIND (R-4).</b> While the newest reading is older than three sample intervals, or
 *     while its quality is anything but {@code GOOD}, the room is unobservable and the monitor
 *     says so and accuses nobody. BLIND is not a fault: no case is opened, no classification is
 *     made, and the only thing published on {@code faults/{zone}} is the state itself with
 *     classification {@code NONE} — a report of unavailability, which is what R-4 asks for, and
 *     not a fault event, which is what R-4 forbids. Coming back from BLIND goes to WARMUP, not
 *     to HEALTHY: after a gap the observer's prediction is a statement about a building it has
 *     not been watching, and it has to be re-anchored before it may accuse anything.
 * <li><b>SUSPECT is reversible, CONFIRMED is not.</b> SUSPECT costs nothing but a frozen
 *     generation estimate and decays back to HEALTHY on its own. CONFIRMED latches — see below.
 * <li><b>Latching (T-18).</b> Once CONFIRMED, this class will not clear the case because the
 *     residual shrank, and the reason is that the residual shrinking is the <em>expected</em>
 *     consequence of acting on the diagnosis. The supervisor raises the fan and trims the five
 *     healthy dampers, flow reaches the stuck room through the split, its CO2 falls, and the
 *     signal that found the fault is gone — destroyed by the response to it. A monitor that
 *     cleared on a small residual would therefore clear every fault it successfully handled,
 *     the supervisor would stop compensating, the CO2 would climb again, and the pair would
 *     oscillate with a broken damper still broken. The only exit is
 *     {@link #serviced(Instant)}: somebody repaired the equipment and said so.
 * </ul>
 *
 * <h2>Who may command (T-18's probe)</h2>
 *
 * A latched case still needs re-testing — otherwise a repaired damper nobody reported stays
 * latched forever, and the diagnosis becomes an article of faith. The re-test is an active
 * probe: a small damper step on this room alone, whose response the observer judges exactly as
 * it judges any other step. Per room, never the fan: a fan-speed probe would perturb all six
 * rooms at once (ADR-0008 consequence 8) and would trade R-7 for a diagnosis.
 *
 * <h2>What a latched stuck damper does to the shared flow belief (R-7)</h2>
 *
 * Confirming {@code STUCK_DAMPER} also pins this zone's believed opening in
 * {@link AhuFlowModel}, so the model stops following commands this damper is demonstrably
 * ignoring. That is not bookkeeping: the split is a ratio over all six zones, so air wrongly
 * credited to this room is air taken off the other five, and the monitor used to break its own
 * diagnostic isolation that way. {@link AhuFlowModel}'s class comment carries the measured
 * failure and the argument; {@link #unresponsiveBelief()} carries the choice of value.
 *
 * <p>The monitor does <em>not</em> issue the step. {@code fault-monitor} raises events and
 * never commands (plan section 3); if it published on {@code commands/{zone}/damper} it would
 * become a second commander racing the inner loop and the supervisor for the same actuator, and
 * the authority argument the whole design rests on would be gone. So the probe is published as
 * a <em>request</em>: a {@link FaultStateChanged} on {@code faults/{zone}} carrying the step it
 * wants as evidence, for the supervisor — the component that already owns compensation — to
 * execute or refuse. The monitor keeps the diagnosis; the supervisor keeps the actuators.
 */
public final class ZoneMonitor {

    static final String SOURCE = "fault-monitor";
    /**
     * The cross-component token the supervisor matches on, case-insensitively, as a substring of
     * {@code FaultStateChanged.evidence()}. {@code FaultStateChanged} has no dedicated probe
     * field and the schema is frozen, so this convention is the channel; the supervisor exposes
     * the same literal as {@code SupervisionService.PROBE_REQUEST_EVIDENCE}.
     */
    public static final String PROBE_REQUEST = "probe-requested";

    private final VentilationZone zone;
    private final AhuFlowModel flow;
    private final ZoneObserver observer;
    private final OvernightFloor overnightFloor;
    private final MonitorParameters parameters;
    private final Cusum cusum;
    private final FaultClassifier classifier;
    private final EventPublisherPort events;
    private final ResidualJournalPort journal;

    private MonitorState state = MonitorState.WARMUP;
    private FaultCase openCase;
    private CusumStatistic statistic = CusumStatistic.RESET;
    private CusumStatistic lowerStatistic = CusumStatistic.RESET;
    private boolean lowSide;
    private double lastMeasuredPpm = Double.NaN;
    private ObservedAt lastReadingAt;
    private double lastResidualPpm;
    private boolean elevated;
    private boolean usableThisCycle;
    private boolean commandSeenWhileLatched;
    private int usableCycles;
    private int aboveLimitCycles;
    private int quietCycles;
    private Instant nextProbeAt;
    private boolean classificationDeferred;
    private Instant confirmedAt;
    private Instant classifyBy;

    public ZoneMonitor(VentilationZone zone, AhuFlowModel flow, MonitorParameters parameters,
            FaultClassifier classifier, EventPublisherPort events, ResidualJournalPort journal) {
        this.zone = zone;
        this.flow = flow;
        this.parameters = parameters;
        this.observer = new ZoneObserver(zone, flow, parameters);
        this.overnightFloor = new OvernightFloor(parameters.overnightWindow());
        this.cusum = Cusum.of(parameters);
        this.classifier = classifier;
        this.events = events;
        this.journal = journal;
    }

    public VentilationZone zone() {
        return zone;
    }

    public MonitorState state() {
        return state;
    }

    /** The open case, or {@code null} when this room has none. */
    public FaultCase openCase() {
        return openCase;
    }

    /**
     * Whether this room's latched case is still waiting for a judged step before it is named; see
     * {@link ClassifyWhen#AFTER_PROBE}. Always false under {@link ClassifyWhen#ON_CONFIRMATION},
     * and false again the moment the diagnosis is taken.
     */
    public boolean classificationDeferred() {
        return classificationDeferred;
    }

    public CusumStatistic cusum() {
        return statistic;
    }

    /** The lower arm, as a non-negative magnitude (T-32 section 5). Zero when the arm is switched off. */
    public CusumStatistic lowerCusum() {
        return lowerStatistic;
    }

    /** Whether the excursion this room is currently pursuing is the lower arm's. */
    public boolean lowSide() {
        return lowSide;
    }

    /** The newest usable reading, or {@code NaN} before the first one. */
    public double lastMeasuredPpm() {
        return lastMeasuredPpm;
    }

    public double lastResidualPpm() {
        return lastResidualPpm;
    }

    /**
     * Whether this room's residual is up — its CUSUM has not decayed back to zero. A deliberately
     * low bar, because this is the input to the <em>common-mode</em> count across six rooms, where
     * the question is "is every room affected" and not "is this room faulty". Suspicion uses the
     * suspicion threshold instead. Six independent rooms all clearing a low bar at the same cycle
     * is rare enough on noise alone; six all clearing a high one takes long enough that the first
     * room would confirm a room-level diagnosis before the plant-level signature had assembled,
     * which is exactly what makes a blocking filter look like six separate stuck dampers.
     */
    public boolean elevated() {
        return elevated;
    }

    public ZoneObserver observer() {
        return observer;
    }

    public void readingObserved(ReadingObserved reading) {
        if (!reading.quality().isUsable()) {
            return;
        }
        if (lastReadingAt == null) {
            observer.reset(reading.ppm().value(), reading.at().value());
        }
        lastMeasuredPpm = reading.ppm().value();
        lastReadingAt = reading.at();
    }

    public void commandObserved(CommandIssued command, Instant now) {
        if (Double.isNaN(lastMeasuredPpm)) {
            observer.neighbourCommanded(command);
            return;
        }
        if (state.isLatched()) {
            commandSeenWhileLatched = true;
        }
        observer.stepCommanded(command, lastMeasuredPpm, now);
    }

    public void neighbourCommanded(CommandIssued command) {
        observer.neighbourCommanded(command);
    }

    /**
     * Phase one of a cycle: decide whether the room is observable, compute and publish the
     * residual, and advance the CUSUM. Every cycle publishes a {@link ResidualComputed} on
     * {@code residuals/{zone}} whatever the state, so the series the dashboard and the journal
     * carry has no holes to interpret.
     */
    public void evaluate(Instant now) {
        usableThisCycle = false;
        if (isStale(now)) {
            goBlindIfAllowed(now);
            publishResidual(0, now);
            return;
        }
        if (state == MonitorState.BLIND) {
            observer.reset(lastMeasuredPpm, now);
            resetArms();
            usableCycles = 0;
            transitionTo(MonitorState.WARMUP, now);
        }
        usableThisCycle = true;
        overnightFloor.record(now, lastMeasuredPpm);

        ZoneObserver.Prediction prediction = observer.advance(now);
        observer.recordStepAmplitude(lastMeasuredPpm);
        lastResidualPpm = lastMeasuredPpm - prediction.predictedPpm();
        // The published residual is the honest one, measured minus the believed prediction (T-12).
        // The CUSUM is driven by the occupancy-plausible residual instead — measured minus the
        // prediction for a room filled to its design occupancy — so that CO2 arriving people could
        // account for never accumulates into an accusation about the airflow. In a room at its
        // design occupancy the two are the same number, which is the case the T-04 spike measured;
        // in an emptier room the guard costs sensitivity, and it is subtracted rather than clipped
        // at zero, because clipping would rectify the sensor noise and destroy the one-sided
        // CUSUM's in-control run length, which is the whole basis of R-3.
        statistic = cusum.next(statistic, lastMeasuredPpm - prediction.ceilingPpm());
        // The lower arm (T-32 section 5) is driven by whichever residual LowerArm asks for: the
        // empty-room floor by default, the honest residual when the stuck-open signature is wanted
        // and R-3 is being spent on it. Both numbers are measured; see LowerArm.
        if (parameters.lowerArm().isOn()) {
            lowerStatistic = cusum.nextLower(lowerStatistic, lowerArmResidual());
        }
        // elevated() stays the upper arm alone: it feeds the common-mode count, and a blocked
        // filter starves every room, so a low-side excursion is evidence against the plant-level
        // signature rather than another vote for it.
        elevated = statistic.value() > 0;
        publishResidual(lastResidualPpm, now);

        boolean judging = observer.windowOpen(now) || state == MonitorState.SUSPECT || state.isLatched();
        if (!judging) {
            observer.learnGeneration(lastMeasuredPpm);
            observer.correctTowards(lastMeasuredPpm, now);
        }
    }

    /** Phase two of a cycle: the state machine, once the cross-room signals are known. */
    public void decide(FaultSignals signals, Instant now) {
        if (!usableThisCycle) {
            return;
        }
        switch (state) {
            case WARMUP -> {
                if (++usableCycles >= parameters.warmupCycles()) {
                    transitionTo(MonitorState.HEALTHY, now);
                }
            }
            case HEALTHY -> suspectIfElevated(now);
            case SUSPECT -> confirmOrClear(signals, now);
            case CONFIRMED -> {
                if (commandSeenWhileLatched) {
                    commandSeenWhileLatched = false;
                    openCase = openCase.compensating(ObservedAt.of(now));
                    transitionTo(MonitorState.COMPENSATING, now);
                }
            }
            case COMPENSATING -> commandSeenWhileLatched = false;
            case SERVICED -> restart(now);
            case BLIND -> { /* handled in evaluate; BLIND makes no decisions by design (R-4) */ }
        }
        if (state.isLatched()) {
            if (classificationDeferred) {
                classifyIfProbeJudged(signals, now);
            } else {
                recordProbeOutcome(now);
            }
            requestProbeIfDue(now);
        }
    }

    /**
     * The one explicit exit from a latched case, reached from {@code service/{zone}} through
     * {@code ServiceRequests} (D-10) and from the evaluation harness directly.
     *
     * <p>A report for a room that is not latched is a no-op here rather than a refusal, because
     * the refusal belongs at the boundary that received the request: that is where there is still
     * somebody to answer, and where an unknown zone and an unknown case id are refused in the same
     * place. Reaching this method means the request has already been checked.
     */
    public void serviced(Instant now) {
        if (!state.isLatched()) {
            return;
        }
        openCase = openCase.withEvidence(
                        Evidence.against("serviced: the equipment was repaired and reported, which is "
                                + "the only thing that clears a latched case", ObservedAt.of(now)),
                        ObservedAt.of(now))
                .serviced(ObservedAt.of(now));
        transitionTo(MonitorState.SERVICED, now);
    }

    /**
     * HEALTHY to SUSPECT, on whichever arm got there. The upper arm is tested first, so a room
     * whose two arms somehow both pass the threshold in the same cycle is pursued as the
     * high-side case the project already measured — not a situation this physics produces, since
     * the two arms cannot both accumulate on one sign of residual, but the order makes the tie
     * explicit instead of accidental.
     */
    private void suspectIfElevated(Instant now) {
        boolean high = statistic.exceeds(parameters.suspectThresholdPpm());
        boolean low = lowerStatistic.exceeds(parameters.suspectThresholdPpm());
        if (!high && !low) {
            return;
        }
        lowSide = !high;
        openCase = FaultCase.suspected(zone.id(), Evidence.supporting(
                "%s CUSUM %.1f ppm passed the suspicion threshold %.0f ppm on residual %+.1f ppm"
                        .formatted(lowSide ? "lower-arm" : "upper-arm", drivingStatistic().value(),
                                parameters.suspectThresholdPpm(), lastResidualPpm),
                ObservedAt.of(now)), ObservedAt.of(now));
        aboveLimitCycles = 0;
        quietCycles = 0;
        transitionTo(MonitorState.SUSPECT, now);
    }

    /**
     * What the lower arm accumulates on this cycle. {@code GUARDED} measures the room against
     * {@link ZoneObserver#unconditionalFloorPpm()} — the outdoor concentration less an allowance
     * for sensor noise — so an excursion means a reading no ventilation could produce, which is a
     * statement about the sensor and needs no command and no occupancy signal. {@code HONEST}
     * measures it against the believed prediction, which sees more and confuses more.
     */
    private double lowerArmResidual() {
        return parameters.lowerArm() == LowerArm.GUARDED
                ? lastMeasuredPpm - observer.unconditionalFloorPpm()
                : lastResidualPpm;
    }

    /** The arm this room's open case is being argued on. */
    private CusumStatistic drivingStatistic() {
        return lowSide ? lowerStatistic : statistic;
    }

    /**
     * Confirmation is judged on the arm the suspicion was raised on, so the lower arm inherits
     * {@link MonitorParameters#confirmPersistenceCycles()} unchanged — two consecutive cycles
     * above {@code h}. That is deliberate: persistence, not a higher limit, is this project's
     * answer to a thin R-3 margin, and the lower arm needs it more than the upper one because
     * the event it confuses a fault with — a room emptying out inside a judged window — is a
     * transient the honest residual walks back from on its own.
     */
    private void confirmOrClear(FaultSignals signals, Instant now) {
        CusumStatistic driving = drivingStatistic();
        if (cusum.alarms(driving)) {
            quietCycles = 0;
            if (++aboveLimitCycles >= parameters.confirmPersistenceCycles()) {
                confirm(signals, now);
            }
            return;
        }
        aboveLimitCycles = 0;
        if (driving.value() > 0) {
            quietCycles = 0;
            return;
        }
        if (++quietCycles >= parameters.suspectClearCycles()) {
            openCase = null;
            lowSide = false;
            transitionTo(MonitorState.HEALTHY, now);
        }
    }

    private void confirm(FaultSignals signals, Instant now) {
        if (parameters.classifyWhen() == ClassifyWhen.AFTER_PROBE
                && signals.stepResponse() == StepResponse.NOT_JUDGED) {
            confirmWithDiagnosisDeferred(now);
            return;
        }
        Diagnosis diagnosis = classifier.classify(signals);
        FaultCase withEvidence = openCase;
        for (Evidence evidence : diagnosis.evidence()) {
            withEvidence = withEvidence.withEvidence(evidence, ObservedAt.of(now));
        }
        if (diagnosis.classification().isUnresponsiveDamper()) {
            withEvidence = withEvidence.withEvidence(
                    Evidence.supporting(stopBelievingCommands(), ObservedAt.of(now)),
                    ObservedAt.of(now));
        }
        openCase = withEvidence.withEvidence(
                        Evidence.supporting(persistence(), ObservedAt.of(now)), ObservedAt.of(now))
                .confirmed(diagnosis.classification(), ObservedAt.of(now));
        confirmedAt = now;
        nextProbeAt = now.plus(parameters.probeInterval());
        transitionTo(MonitorState.CONFIRMED, now);
    }

    /**
     * {@link ClassifyWhen#AFTER_PROBE}'s confirmation: latch the case, say that the diagnosis is
     * being withheld and why, and ask for the probe <em>on this cycle</em> rather than one probe
     * interval from now, because the request is what the classification is waiting for.
     *
     * <p>The case has to latch here rather than at the refinement. The residual is already
     * confirmed, so the room already needs the supervisor's response, and the probe cannot be
     * commanded at all until the supervisor has a latched case to act on — the request travels in
     * the evidence of a {@link FaultStateChanged} and {@code SupervisionService.onFaultState}
     * returns early on anything unlatched. Holding the case at SUSPECT to wait for the step would
     * therefore wait for a step nobody would command, and the alternative — having the supervisor
     * probe an unlatched room — would put it in command of a damper the inner loop still owns.
     *
     * <p>{@link #classifyBy} bounds the wait at one probe interval, so a probe the supervisor
     * refuses or never sees cannot leave the case {@code UNCLASSIFIED} for ever.
     */
    private void confirmWithDiagnosisDeferred(Instant now) {
        openCase = openCase.withEvidence(Evidence.supporting(persistence(), ObservedAt.of(now)),
                        ObservedAt.of(now))
                .withEvidence(Evidence.supporting(
                        ("diagnosis deferred: the residual is confirmed, but no commanded step has "
                                + "been judged, so the signature table has two of its three signals "
                                + "and naming a fault now would be naming it on localisation and "
                                + "flat fan power alone. A probe is requested on this cycle and the "
                                + "case is classified when its %d min window closes, or in %d min "
                                + "if no step is judged by then.")
                                .formatted(parameters.evaluationWindow().toMinutes(),
                                        parameters.probeInterval().toMinutes()),
                        ObservedAt.of(now)), ObservedAt.of(now))
                .confirmed(FaultClassification.UNCLASSIFIED, ObservedAt.of(now));
        classificationDeferred = true;
        confirmedAt = now;
        classifyBy = now.plus(parameters.probeInterval());
        nextProbeAt = now;
        transitionTo(MonitorState.CONFIRMED, now);
    }

    /**
     * Names a deferred case, at the first cycle a step window closes or at {@link #classifyBy},
     * whichever comes first. The classification is the same tree over the same signals as
     * {@link #confirm}; the only difference is that the step response is now something the room
     * was actually asked for, which is the whole point of the ordering.
     */
    private void classifyIfProbeJudged(FaultSignals signals, Instant now) {
        if (!observer.windowJustClosed(now, parameters.cycleInterval())
                && now.isBefore(classifyBy)) {
            return;
        }
        classificationDeferred = false;
        Diagnosis diagnosis = classifier.classify(signals);
        FaultCase named = openCase;
        for (Evidence evidence : diagnosis.evidence()) {
            named = named.withEvidence(evidence, ObservedAt.of(now));
        }
        if (diagnosis.classification().isUnresponsiveDamper()) {
            named = named.withEvidence(
                    Evidence.supporting(stopBelievingCommands(), ObservedAt.of(now)),
                    ObservedAt.of(now));
        }
        openCase = named.withEvidence(Evidence.supporting(
                        ("diagnosis taken %d min after the case latched, on step response %s: %s")
                                .formatted(Duration.between(confirmedAt, now).toMinutes(),
                                        signals.stepResponse(), diagnosis.classification()),
                        ObservedAt.of(now)), ObservedAt.of(now))
                .reclassified(diagnosis.classification(), ObservedAt.of(now));
        publishState(now, evidenceLines());
    }

    private String persistence() {
        return "the %s CUSUM stayed above h = %.0f ppm for %d consecutive cycles"
                .formatted(lowSide ? "lower-arm" : "upper-arm", parameters.cusumH(),
                        parameters.confirmPersistenceCycles());
    }

    /**
     * A latched case's view of its own re-test. The probe's response is judged by the same
     * observer window as any command, so the outcome is read when that window closes: a residual
     * still above the suspicion threshold re-confirms the diagnosis, one that collapsed is
     * recorded as evidence against it — and the case still does not clear, because a damper the
     * supervisor is already compensating produces the same collapsed residual as a damper that
     * was repaired. Telling those two apart is what service does.
     */
    private void recordProbeOutcome(Instant now) {
        if (!observer.windowJustClosed(now, parameters.cycleInterval())) {
            return;
        }
        Evidence outcome = drivingStatistic().value() > 0
                ? Evidence.supporting(
                        "probe response: residual %+.1f ppm, CUSUM %.1f ppm — the step was commanded "
                                .formatted(lastResidualPpm, drivingStatistic().value())
                                + "and the room still did not answer, so the diagnosis stands",
                        ObservedAt.of(now))
                : Evidence.against(
                        "probe response: residual %+.1f ppm — the room answered the step, which is "
                                .formatted(lastResidualPpm)
                                + "also what successful compensation looks like, so the case stays "
                                + "latched until service",
                        ObservedAt.of(now));
        openCase = openCase.withEvidence(outcome, ObservedAt.of(now));
        publishState(now, List.of((outcome.supportsFault() ? "+ " : "- ") + outcome.observation()));
    }

    private void requestProbeIfDue(Instant now) {
        if (nextProbeAt == null || now.isBefore(nextProbeAt)) {
            return;
        }
        nextProbeAt = now.plus(parameters.probeInterval());
        double target = Math.min(1.0,
                zone.commandedPosition().value() + parameters.probeStepIncrease());
        String purpose = classificationDeferred
                ? "to supply the step response this case is withholding its diagnosis for"
                : "to re-test " + openCase.classification();
        String request = "%s: step the %s damper to %.2f for %d min %s. The monitor "
                .formatted(PROBE_REQUEST, zone.id().value(), target,
                        parameters.evaluationWindow().toMinutes(), purpose)
                + "raises events only — the supervisor owns the actuators and executes or refuses "
                + "this. Per room, never the fan: a fan-speed probe would perturb all six rooms.";
        openCase = openCase.withEvidence(
                Evidence.supporting(request, ObservedAt.of(now)), ObservedAt.of(now));
        publishState(now, List.of("+ " + request));
    }

    /**
     * Stops the shared flow model believing this damper's commands, and says so in the evidence,
     * because the five healthy rooms' residuals change the moment it happens — a work order that
     * did not record it would leave the reader unable to explain why their numbers moved.
     */
    private String stopBelievingCommands() {
        DamperPosition commandedNow = flow.positionOf(zone.id());
        DamperPosition effective = unresponsiveBelief();
        flow.damperUnresponsive(zone.id(), effective);
        return ("belief: this damper is no longer believed to be where it is commanded. The model "
                + "held it at %.2f on the strength of the command; the last position its response "
                + "agreed with, and its own CO2, put it at %.2f, and the shared fan splits a fixed total, so every "
                + "point of opening credited here is air subtracted from the other five rooms. The "
                + "belief is pinned at %.2f until service.")
                .formatted(commandedNow.value(), effective.value(), effective.value());
    }

    /**
     * Where to pin the belief for a damper that has stopped answering commands.
     *
     * <p>{@link ZoneObserver#agreedPosition()} is the anchor, and it is the one quantity here that
     * is a fact rather than an estimate: the last position for which the prediction and the
     * measurement agreed. Everything commanded since is a proposition this case has just rejected.
     * It is also what makes a <em>wrong</em> diagnosis cheap, which matters because R-3 allows one
     * false confirmation a week and a false confirmation must not then wreck the other five rooms:
     * a healthy damper answers its steps, so its agreed position is where it was last commanded,
     * and pinning it there changes no prediction at all.
     *
     * <p>{@link ZoneObserver#inferredPosition(double)} refines the anchor on the high side, and
     * that refinement is what the monitor's three pieces of evidence — the command, the predicted
     * response, the measured response — buy over simply freezing. It has to be here rather than as
     * a nicety, because the anchor alone is not good enough in the regime the containers actually
     * run in: {@code dcv-controller} trims continuously, so each new trim re-arms the judging
     * window before the previous one has accumulated a measurable predicted amplitude, and the
     * agreed position is then whatever the room was at before the last trim. Measured on the
     * isolation experiment, that put the pins at up to 0.96 on dampers frozen at 0.15, while the
     * quasi-steady inversion put the same dampers at 0.06 to 0.19 — the truth, to a couple of
     * points. Anchor alone took R-7 from 4 failing rooms of 270 to 8; anchor plus inference takes
     * it to 0.
     *
     * <p>The bracket, not a confidence test, is what makes the inference safe. It can only ever
     * tighten: never more open than the agreed position, since the diagnosis is precisely that the
     * damper stopped opening, and never more closed than {@link VentilationZone#minimumPosition()},
     * because ventilation never shuts entirely. Both bounds are facts about the actuator rather
     * than estimates, so the worst the inversion can do is fail to improve on the anchor.
     *
     * <p>It is taken <b>once</b>, at the confirmation, and that is a measured choice rather than a
     * simplification. Re-estimating it every cycle while the case is latched looks strictly better
     * — the damper's real share moves as the other five are trimmed, and a live estimate would
     * follow it — but the generation estimate is frozen for as long as a case is latched, so a live
     * estimate reads every arrival and departure in the room as damper travel. Measured: the
     * isolation experiment's drawn-weekday arm went from 0 failing rooms of 270 to 2 — that arm, not
     * R-7's verdict, which fails 231 of 270 at design occupancy — this class's own 12 h flow-belief
     * error went from 0.0000 to 0.0544 against a 0.01 tolerance, and R-3's twenty-week false-alarm
     * rate went from 1.25 to 1.50 confirmed cases a week. The confirmation is the one instant at
     * which the inversion is trustworthy, because a damper that has stopped responding has left its
     * room at the quasi-steady state of its actual flow, which is the assumption the inversion
     * rests on; so the estimate is taken there and then held.
     *
     * <p><b>The low side takes the anchor and nothing else</b>, and that asymmetry is numerical
     * rather than squeamish. The inversion is {@code Q = G_est / (C - C_out)}, so its conditioning
     * is set by how far the room sits above outdoor — and a damper stuck <em>wide open</em> is
     * precisely the fault that holds a room near outdoor. Measured on the fault-free week, the
     * low-side inversion returned openings of 2.1, 3.0 and 3.8 on rooms whose dampers were at 0.15
     * and perfectly healthy; clamped into a bracket those became pins at 1.00, which handed the
     * other five rooms a vast belief error and took R-3 from one false confirmation a week to two.
     * A starved room, by contrast, sits hundreds of ppm above outdoor, where the same expression
     * is well conditioned — the measurement that drove this fix inverted A115's 0.15 opening to
     * 0.150. So the refinement is used where it is sound and not where it is not.
     */
    private DamperPosition unresponsiveBelief() {
        double agreed = observer.agreedPosition().value();
        double inferred = observer.inferredPosition(lastMeasuredPpm);
        if (lowSide || Double.isNaN(inferred)) {
            return DamperPosition.of(agreed);
        }
        double floor = Math.min(zone.minimumPosition().value(), agreed);
        return DamperPosition.of(Math.min(agreed, Math.max(floor, inferred)));
    }

    private void restart(Instant now) {
        openCase = null;
        flow.damperResponsive(zone.id());
        resetArms();
        usableCycles = 0;
        aboveLimitCycles = 0;
        quietCycles = 0;
        nextProbeAt = null;
        classificationDeferred = false;
        confirmedAt = null;
        classifyBy = null;
        commandSeenWhileLatched = false;
        overnightFloor.clear();
        observer.reset(lastMeasuredPpm, now);
        transitionTo(MonitorState.WARMUP, now);
    }

    /**
     * Both arms, together. Every event that resets the statistic resets the pair: a statistic
     * that kept one arm's history across a BLIND gap or a service would be arguing from a
     * building it had not been watching, and the two arms have to agree about when that was.
     */
    /**
     * Re-anchors this room after the shared flow belief has changed discontinuously — because some
     * other room's damper was just pinned as unresponsive, which under ADR-0008's normalised split
     * changes the believed airflow of all six rooms at once.
     *
     * <p>It is the same argument {@link #resetArms()} already makes for a BLIND gap and for
     * service, applied to the third event that invalidates an accumulated statistic. The residual's
     * meaning is "measured minus what the believed airflow predicts", so a step change in the
     * believed airflow makes every ppm banked before it a statement about a different building.
     * Carrying it across was measured as the larger half of R-3's failure: over 20 fault-free
     * six-room weeks, 13 of 26 false confirmations were raised in a room while another room was
     * already pinned, 11 of them on the upper arm, and seed 20 latched four rooms in 53 minutes
     * once the first pin had moved the split. Which room went first was decided by the noise; what
     * made the rest follow was the belief. See ADR-0010.
     *
     * <p>A latched case is left alone. Its diagnosis is already made, its only exit is service, and
     * the belief change is a <em>consequence</em> of a confirmation rather than new information
     * about it.
     */
    void flowBeliefChanged(Instant now) {
        if (state.isLatched() || state == MonitorState.BLIND || Double.isNaN(lastMeasuredPpm)) {
            return;
        }
        openCase = null;
        aboveLimitCycles = 0;
        quietCycles = 0;
        observer.reset(lastMeasuredPpm, now);
        resetArms();
        if (state == MonitorState.SUSPECT) {
            transitionTo(MonitorState.HEALTHY, now);
        }
    }

    private void resetArms() {
        statistic = CusumStatistic.RESET;
        lowerStatistic = CusumStatistic.RESET;
        elevated = false;
        lowSide = false;
    }

    private boolean isStale(Instant now) {
        return lastReadingAt == null
                || lastReadingAt.isStalerThan(parameters.staleLimit(), now)
                || Double.isNaN(lastMeasuredPpm);
    }

    private void goBlindIfAllowed(Instant now) {
        if (state == MonitorState.BLIND || !state.canGoBlind()) {
            return;
        }
        openCase = null;
        elevated = false;
        lowSide = false;
        usableCycles = 0;
        aboveLimitCycles = 0;
        quietCycles = 0;
        transitionTo(MonitorState.BLIND, now);
    }

    private void transitionTo(MonitorState next, Instant now) {
        state = next;
        publishState(now, evidenceLines());
    }

    private List<String> evidenceLines() {
        if (openCase == null) {
            return List.of();
        }
        return openCase.evidence().stream()
                .map(e -> (e.supportsFault() ? "+ " : "- ") + e.observation())
                .toList();
    }

    private void publishState(Instant now, List<String> evidence) {
        FaultStateChanged event = new FaultStateChanged(zone.id(), state,
                openCase == null ? FaultClassification.NONE : openCase.classification(),
                evidence, ObservedAt.of(now), openCase == null ? null : openCase.id());
        events.publish(Topics.fault(zone.id()), event);
        journal.append(event, SOURCE);
    }

    private void publishResidual(double residualPpm, Instant now) {
        // One cusum field on a frozen wire schema (ADR-0006), two arms: publish whichever is
        // further along. See Cusum.published for what that costs a consumer.
        ResidualComputed event = new ResidualComputed(zone.id(), Residual.of(residualPpm),
                cusum.published(statistic, lowerStatistic), ObservedAt.of(now),
                observer.stepCommand());
        events.publish(Topics.residual(zone.id()), event);
        journal.append(event, SOURCE);
    }

    double overnightFloorPpm() {
        return overnightFloor.floorPpm();
    }

    StepResponse stepResponse() {
        double ratio = observer.stepAmplitudeRatio();
        if (Double.isNaN(ratio)) {
            // No step has been judged, which is not the same as a step that went unanswered.
            return StepResponse.NOT_JUDGED;
        }
        return ratio >= parameters.stepAmplitudeMatchFraction()
                ? StepResponse.MATCHED
                : StepResponse.MISSING;
    }

    // ----- ADR-0012: what survives a restart -------------------------------------------------

    /** This room's latched diagnosis, or {@code null} when nothing is latched. */
    LatchedCase latched() {
        return state.isLatched() ? new LatchedCase(openCase, confirmedAt) : null;
    }

    /**
     * Resumes a latched diagnosis an earlier run of this process left behind, so that a restart
     * stops being a second exit from a latch the design gives exactly one — service.
     *
     * <p>The state is taken from the case rather than stored beside it. The two cannot disagree:
     * every edge that latches or moves a latched case rewrites both in the same statement, so the
     * case's own CONFIRMED or COMPENSATING <em>is</em> this room's state, and storing it twice
     * would create the possibility of their disagreeing.
     *
     * <p>Nothing transient is restored and nothing is published. This room is left exactly as a
     * room coming back from a BLIND gap is left — no trajectory, no CUSUM, no counters — and the
     * first reading re-anchors the observer through the path {@link #readingObserved} already takes
     * for a room it has never seen. A republished state event would announce a change that did not
     * happen; the state is instead re-announced by the next probe request, within one probe
     * interval, which is also how a supervisor that lost its own document learns the latch again.
     *
     * <p>The two timers are re-seeded rather than stored, because a stored deadline is a statement
     * about a timeline the restarted process may not share. A named case gets a full probe interval
     * before its next re-test, so a restart cannot provoke a probe of a plant this monitor cannot
     * yet observe. A case still withholding its diagnosis is re-seeded exactly as
     * {@link #confirmWithDiagnosisDeferred} seeds it — the probe is due now, the deadline one
     * interval out — because the probe is what the classification is waiting for, and giving both
     * the same deadline would name the case on the two-of-three signature the deferral exists to
     * avoid. "Due now" is the first cycle with a reading in it, since {@link #decide} does nothing
     * for an unobservable room.
     */
    void recover(LatchedCase resumed, Instant now) {
        openCase = resumed.openCase();
        state = openCase.state();
        confirmedAt = resumed.confirmedAt();
        // Derived rather than stored: a latched case is withholding its diagnosis exactly while it
        // has not got one, and UNCLASSIFIED is how it says so.
        classificationDeferred = openCase.classification() == FaultClassification.UNCLASSIFIED;
        nextProbeAt = classificationDeferred ? now : now.plus(parameters.probeInterval());
        classifyBy = classificationDeferred ? now.plus(parameters.probeInterval()) : null;
    }
}
