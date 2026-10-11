package se.ltu.d7065e.supervision.control;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.diagnosis.entity.DiagnosticProbe;
import se.ltu.d7065e.diagnosis.entity.FaultClassification;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.shared.control.BuildingOverlayPort;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.shared.event.Topics;
import se.ltu.d7065e.ventilation.control.FlowSplit;
import se.ltu.d7065e.ventilation.entity.CommandId;
import se.ltu.d7065e.ventilation.entity.ControlMode;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Ppm;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The supervisor's decision logic (T-16, T-17, and the commanding half of T-18). Plain Java over
 * ports, so the evaluation harness drives it for R-2 and R-7 with no broker, no database, no
 * HTTP and no wall clock (ADR-0005).
 *
 * <h2>Authority</h2>
 *
 * This class is the <em>only</em> thing in the system that acts. {@code fault-monitor} raises
 * {@link FaultStateChanged} and never commands, so a wrong diagnosis cannot move a damper by
 * itself and a monitor that crashes cannot stop ventilation — the dampers and the fan simply
 * stay where the supervisor last put them while the inner loop keeps running the healthy rooms.
 * The supervisor acts only on a state the monitor reports as
 * {@linkplain MonitorState#isLatched() latched}: SUSPECT produces nothing at all, because a
 * suspicion that has not survived the CUSUM limit is not yet a reason to take a damper away from
 * the inner loop.
 *
 * <p>The same split governs the active probe (T-18): the <b>monitor decides</b> that a probe is
 * warranted and says so in its evidence; the <b>supervisor owns the command</b>. The monitor
 * cannot issue one, which is the whole point — a component with no command authority cannot
 * disturb a room on a mistaken diagnosis. The probe is a damper step on the faulted room only,
 * never a plant-wide fan-speed probe, which would perturb all six rooms (ADR-0008
 * consequence 8), and it is rate limited per zone by {@link ProbeLimiter}.
 */
public final class SupervisionService {

    public static final String JOURNAL_SOURCE = "supervisor";

    /**
     * How the monitor asks for a probe. {@link FaultStateChanged} carries no probe field, so the
     * request travels in its {@code evidence} list; the monitor appends this token when its
     * masking defence wants a step. <b>Invented here</b> — it is a convention between two
     * components over an existing field, not a schema change, and it is matched loosely so the
     * monitor may add detail after the token.
     */
    public static final String PROBE_REQUEST_EVIDENCE = "probe-requested";

    /** The supervisor aims under the comfort limit, not at it, so the trim has somewhere to sit. */
    public static final double COMFORT_TARGET_FRACTION = 0.9;

    private final PlantState plant;
    private final EventPublisherPort publisher;
    private final BuildingOverlayPort overlay;
    private final ResidualJournalPort journal;
    private final ClockPort clock;
    private final CompensationSolver solver;
    private final ProbeLimiter probes;

    private final Map<ZoneId, SupervisedCase> cases = new LinkedHashMap<>();
    private final Map<ZoneId, Ppm> lastReading = new HashMap<>();

    /**
     * The plant as it stood before any trim of this episode. Every solve starts from here, so
     * repeated latched events — the monitor republishes COMPENSATING on every cycle — recompute
     * the same plan instead of compounding their own trim.
     */
    private PlantSnapshot baseline;
    private CompensationPlan plan;

    public SupervisionService(PlantState plant, EventPublisherPort publisher,
            BuildingOverlayPort overlay, ResidualJournalPort journal, ClockPort clock) {
        this(plant, publisher, overlay, journal, clock,
                new CompensationSolver(), new ProbeLimiter(clock));
    }

    public SupervisionService(PlantState plant, EventPublisherPort publisher,
            BuildingOverlayPort overlay, ResidualJournalPort journal, ClockPort clock,
            CompensationSolver solver, ProbeLimiter probes) {
        this.plant = plant;
        this.publisher = publisher;
        this.overlay = overlay;
        this.journal = journal;
        this.clock = clock;
        this.solver = solver;
        this.probes = probes;
    }

    /** The single entry point the boundary feeds every subscribed event into. */
    public void accept(DomainEvent event) {
        switch (event) {
            case FaultStateChanged e -> onFaultState(e);
            case ReadingObserved e -> observeReading(e);
            case CommandIssued e -> observeInnerLoopCommand(e);
            default -> {
                // The supervisor subscribes to faults, readings and damper commands only.
            }
        }
    }

    public void observeReading(ReadingObserved reading) {
        if (reading.quality().isUsable()) {
            lastReading.put(reading.zone(), reading.ppm());
        }
    }

    public void observeInnerLoopCommand(CommandIssued command) {
        plant.observeInnerLoopCommand(command.zone(), command.position());
    }

    public void onFaultState(FaultStateChanged event) {
        if (event.state() == MonitorState.SERVICED) {
            release(event);
            return;
        }
        if (!event.state().isLatched()) {
            return;
        }
        ZoneId zone = event.zone();
        if (!cases.containsKey(zone)) {
            if (baseline == null) {
                baseline = plant.snapshot();
            }
            cases.put(zone, new SupervisedCase(
                    caseIdOf(event), event.classification(), plant.zone(zone)));
            degrade(zone);
        } else {
            nameIfRefined(zone, event);
        }
        compensate();
        if (requestsProbe(event)) {
            probe(zone, cases.get(zone));
        }
    }

    // ----- T-16: degrade, then compensate by ADR-0008 option B's mechanism -------------------

    /**
     * Takes the damper away from the inner loop. {@code dcv-controller} honours
     * {@link ControlMode#DEGRADED} on {@code strategy/{zone}}, and
     * {@link VentilationZone#withControlCommand} refuses a normal command for a degraded zone,
     * so a stale controller command cannot undo this — but only for as long as the controller
     * remembers being told. The mode itself is published by {@link #compensate()}, on every
     * cycle rather than once per case, for the reason recorded there.
     */
    private void degrade(ZoneId zone) {
        plant.changeMode(zone, ControlMode.DEGRADED);
    }

    private void compensate() {
        CaseId leadCase = cases.values().iterator().next().caseId();
        plan = solver.solve(solverInput(), cases.keySet(), requestedGain());
        // Every latched zone's mode goes out again here, not once when the case opened. A
        // dcv-controller that restarts comes back with every zone NORMAL — the mode lives in its
        // heap — and a DEGRADED published only at latch time would never reach it again, so it
        // would resume commanding a damper this loop has taken away and R-7's isolation would
        // hold only until the inner loop happened to restart. The guard has to be re-asserted,
        // not announced once.
        cases.forEach((zone, open) ->
                publishStrategy(zone, ControlMode.DEGRADED, null, open.caseId()));
        plan.trimmedPositions().forEach((zone, position) -> {
            plant.changeMode(zone, ControlMode.COMPENSATING);
            plant.observeAppliedPosition(zone, position);
            // The trim travels as a bare Double on the wire because null means "no trim", so the
            // solver carries it as a DamperPosition up to this line and the [0,1] range the
            // event re-checks is already guaranteed rather than hoped for.
            publishStrategy(zone, ControlMode.COMPENSATING, position.value(), leadCase);
        });
        commandFanSpeed(plan.fanSpeed(), fanReason(leadCase));
        cases.forEach((zone, open) -> overlay.raiseWorkOrder(zone, open.caseId(),
                WorkOrderNarrative.summary(open.frozenZone(), open.classification(), plan),
                WorkOrderNarrative.severity(open.classification(), plan)));
    }

    /**
     * Baseline positions for the healthy zones, latch-time positions for the faulted ones: a
     * damper froze where it was when it froze, which is not necessarily where this episode
     * started.
     */
    private PlantSnapshot solverInput() {
        List<VentilationZone> zones = baseline.zones().stream()
                .map(zone -> cases.containsKey(zone.id())
                        ? cases.get(zone.id()).frozenZone() : zone)
                .toList();
        return new PlantSnapshot(zones, baseline.airHandler());
    }

    /** The gain the worst-off faulted room needs; the solver clamps it to what is feasible. */
    private double requestedGain() {
        return cases.keySet().stream().mapToDouble(this::requestedGain).max().orElse(1.0);
    }

    /**
     * Measurement first: R-2 is a requirement about CO2, so if the room is reading comfortably
     * there is nothing to buy by disturbing the other five, however little air the frozen damper
     * is passing. The gain then comes from the room's own concentration (R-2), aimed under the
     * limit rather than at it.
     *
     * <p>With no usable reading the supervisor falls back to the plant: restore the room towards
     * the design flow ADR-0008 sized its damper authority from. That needs no occupancy guess and
     * no sensor — only the split — which is also why it is the right fallback when the fault
     * turns out to be the CO2 sensor itself.
     */
    private double requestedGain(ZoneId zone) {
        Ppm measured = lastReading.get(zone);
        if (measured != null) {
            return CompensationSolver.gainForComfort(measured.value(),
                    Topology.COMFORT_LIMIT.value() * COMFORT_TARGET_FRACTION,
                    Topology.OUTDOOR.value());
        }
        double designFlowLitresPerSecond = baseline.zone(zone).damperAuthority();
        double currentLitresPerSecond = 1000 * FlowSplit
                .flowPerZone(solverInput().zones(), baseline.airHandler(), 0.0).get(zone);
        return currentLitresPerSecond <= 0 ? 1.0
                : designFlowLitresPerSecond / currentLitresPerSecond;
    }

    /**
     * Takes the monitor's diagnosis when it arrives after the case did. Under
     * {@code ClassifyWhen.AFTER_PROBE} the monitor latches a case as {@code UNCLASSIFIED} and
     * names it a few minutes later, once the probe it asked for here has been judged — and the
     * supervisor has to follow, or the work order would keep quoting a diagnosis the monitor has
     * since replaced.
     *
     * <p>Only an {@code UNCLASSIFIED} case is renamed. A named diagnosis is the monitor's latched
     * conclusion and the supervisor is not the place to revise it; accepting any later name would
     * let a work order change its mind on every republished cycle.
     */
    private void nameIfRefined(ZoneId zone, FaultStateChanged event) {
        SupervisedCase open = cases.get(zone);
        if (open.classification() != FaultClassification.UNCLASSIFIED
                || event.classification() == FaultClassification.UNCLASSIFIED
                || event.classification() == FaultClassification.NONE) {
            return;
        }
        cases.put(zone, open.named(event.classification()));
    }

    // ----- T-18: the probe the monitor asked for and only the supervisor may command ---------

    private void probe(ZoneId zone, SupervisedCase open) {
        if (!probes.claim(zone)) {
            return;
        }
        VentilationZone frozen = open.frozenZone();
        CommandIssued command = new CommandIssued(zone, probeTarget(frozen), now(),
                ("active probe of the %s damper while case %s is latched: step the command away"
                        + " from %s and see whether the damper follows (T-18). The monitor asked"
                        + " for the probe; the supervisor owns the command.")
                        .formatted(zone.value(), open.caseId().value(), frozen.commandedPosition()),
                CommandId.newId());
        publisher.publish(Topics.damperCommand(zone), command);
        journal.append(command, JOURNAL_SOURCE);
    }

    /**
     * A step away from where the damper is sitting, in whichever direction has room. The
     * supervisor does not record the probe as an applied position: a stuck damper will not move,
     * and assuming it did would corrupt the opening the compensation is solved from.
     *
     * <p>The amplitude is {@link DiagnosticProbe#STEP_INCREASE}, which the monitor asks for; the
     * clamp to the actuator's range and the reversal when there is no room up stay here, because
     * they are limits of the thing being commanded rather than properties of the test.
     */
    private static DamperPosition probeTarget(VentilationZone frozen) {
        double position = frozen.commandedPosition().value();
        double up = Math.min(DamperPosition.OPEN.value(),
                position + DiagnosticProbe.STEP_INCREASE);
        return up > position ? DamperPosition.of(up)
                : DamperPosition.of(Math.max(frozen.minimumPosition().value(),
                        position - DiagnosticProbe.STEP_INCREASE));
    }

    private static boolean requestsProbe(FaultStateChanged event) {
        return event.evidence().stream().anyMatch(entry -> entry != null
                && entry.toLowerCase(Locale.ROOT).contains(PROBE_REQUEST_EVIDENCE));
    }

    // ----- Release --------------------------------------------------------------------------

    private void release(FaultStateChanged event) {
        SupervisedCase closed = cases.remove(event.zone());
        if (closed == null) {
            return;
        }
        overlay.clearWorkOrder(closed.caseId());
        probes.forget(event.zone());
        if (cases.isEmpty()) {
            plant.zones().forEach(zone -> {
                plant.changeMode(zone.id(), ControlMode.NORMAL);
                publishStrategy(zone.id(), ControlMode.NORMAL, null, closed.caseId());
            });
            baseline.zones().forEach(
                    zone -> plant.observeAppliedPosition(zone.id(), zone.commandedPosition()));
            commandFanSpeed(Topology.NORMAL_FAN_SPEED,
                    ("case %s serviced: every zone back to NORMAL and the air handler back to its"
                            + " normal speed %s").formatted(
                            closed.caseId().value(), Topology.NORMAL_FAN_SPEED));
            baseline = null;
            plan = null;
            return;
        }
        plant.changeMode(event.zone(), ControlMode.NORMAL);
        publishStrategy(event.zone(), ControlMode.NORMAL, null, closed.caseId());
        compensate();
    }

    // ----- Publishing -----------------------------------------------------------------------

    private void publishStrategy(ZoneId zone, ControlMode mode, Double trim, CaseId caseId) {
        StrategyChanged event = new StrategyChanged(zone, mode, trim, now(), caseId);
        publisher.publish(Topics.strategy(zone), event);
        journal.append(event, JOURNAL_SOURCE);
    }

    private void commandFanSpeed(FanSpeed speed, String reason) {
        if (Math.abs(speed.value() - plant.airHandler().commandedSpeed().value()) < 1e-9) {
            return;
        }
        FanSpeedCommanded event = new FanSpeedCommanded(
                Topology.AIR_HANDLER, speed, now(), reason, CommandId.newId());
        plant.observeFanSpeed(speed);
        publisher.publish(Topics.fanSpeedCommand(Topology.AIR_HANDLER), event);
        journal.append(event, JOURNAL_SOURCE);
    }

    /**
     * What the fan command is for, and what it costs. The power multiple is stated because the
     * affinity law charges {@code (s/s0)^3} for a raise — 0.7 to 1.0 is nearly three times the
     * fan power — and a trade an operator cannot see in the journal is a trade nobody made.
     */
    private String fanReason(CaseId caseId) {
        double baselineSpeed = baseline.airHandler().commandedSpeed().value();
        double speedRatio = plan.fanSpeed().value() / baselineSpeed;
        String lever = plan.trimmedPositions().isEmpty()
                ? "no healthy damper could be trimmed, so fan speed carries the compensation alone"
                : "healthy dampers trimmed to %.0f%%".formatted(plan.trim() * 100);
        return ("compensating case %s: %s and the fan set to %s, which moves the healthy rooms'"
                + " own airflow by %.1f%% and costs %.2fx the fan power, while the faulted room"
                + " gains %.0f%%")
                .formatted(caseId.value(), lever, plan.fanSpeed(),
                        plan.worstHealthyFlowDeviation() * 100,
                        speedRatio * speedRatio * speedRatio,
                        (plan.achievedFaultedFlowGain() - 1) * 100);
    }

    private ObservedAt now() {
        return ObservedAt.of(clock.now());
    }

    /** The monitor may leave the case id unset; the supervisor still needs one to key a work order. */
    private static CaseId caseIdOf(FaultStateChanged event) {
        return event.caseId() == null ? CaseId.of("zone-" + event.zone().value())
                : event.caseId();
    }

    // ----- ADR-0012: what survives a restart -------------------------------------------------

    /**
     * What this supervisor believes, as one immutable value the calling thread publishes.
     *
     * <p>A value and not a live view, deliberately. The boundary that stores it encodes on the
     * thread it was handed the snapshot on, and nothing inside the snapshot can move underneath
     * it afterwards — the rule ADR-0011 sets for the telemetry gauges, here for the same reason:
     * a store that reads a live reference records a belief that was never held.
     */
    public SupervisionSnapshot snapshot() {
        return new SupervisionSnapshot(List.copyOf(cases.values()), baseline, probes.lastProbes());
    }

    /**
     * Resumes the belief an earlier run of this process left behind, so a restarted supervisor
     * comes back holding its open cases and its plant baseline instead of starting empty.
     *
     * <p>Called once, from the {@code boundary}, before anything is subscribed — never from a
     * constructor and never from {@code control}. That is not style: the evaluation harness
     * constructs this class directly against a simulated clock and must never recover, because
     * every published R-1, R-3 and R-6 figure comes off these classes.
     *
     * <p>The plan is not restored and does not need to be. The next republished latched event
     * recomputes it from the baseline and the cases and re-raises every work order from it, and a
     * {@code SERVICED} arriving first is released without a plan at all.
     */
    public void recover(SupervisionSnapshot resumed) {
        // Every zone is resolved against this plant before anything is mutated. A document
        // written by a process configured for a different zone set names rooms this one does not
        // have, and throwing before the first mutation is what lets the boundary turn that into a
        // cold start rather than a half-recovered supervisor.
        resumed.cases().forEach(open -> plant.zone(open.frozenZone().id()));
        resumed.cases().forEach(open -> {
            ZoneId zone = open.frozenZone().id();
            cases.put(zone, open);
            // The mode is re-asserted here rather than left to the next compensation cycle, which
            // moves only the healthy zones: until it is back, this plant would accept an
            // inner-loop command for a damper the supervisor has already taken away.
            degrade(zone);
        });
        baseline = resumed.baseline();
        probes.resume(resumed.lastProbes());
    }

    // ----- What the evaluation harness reads -------------------------------------------------

    public PlantState plant() {
        return plant;
    }

    /** The plan currently in force, or null while nothing is latched. */
    public CompensationPlan currentPlan() {
        return plan;
    }

    public Set<ZoneId> faultedZones() {
        return Set.copyOf(cases.keySet());
    }

    /** The plant as it stood before this episode's first trim, or null while nothing is latched. */
    public PlantSnapshot episodeBaseline() {
        return baseline;
    }
}
