package se.ltu.d7065e.diagnosis.control;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import se.ltu.d7065e.diagnosis.entity.CusumStatistic;
import se.ltu.d7065e.diagnosis.entity.FaultCase;
import se.ltu.d7065e.diagnosis.entity.MonitorState;
import se.ltu.d7065e.diagnosis.entity.StepResponse;
import se.ltu.d7065e.shared.control.ClockPort;
import se.ltu.d7065e.shared.control.EventPublisherPort;
import se.ltu.d7065e.shared.control.ResidualJournalPort;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanPowerObserved;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.ventilation.entity.AirHandlingUnit;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.FanSpeed;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.VentilationZone;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The diagnosis core: six {@link ZoneMonitor}s over one shared {@link AhuFlowModel}, driven one
 * cycle at a time.
 *
 * <p>Nothing here knows about Quarkus, MQTT or JDBC (ADR-0005). Time arrives through
 * {@link ClockPort}, events leave through {@link EventPublisherPort}, history leaves through
 * {@link ResidualJournalPort}, and inputs arrive as plain domain events through
 * {@link #accept(DomainEvent)}. That is what lets the evaluation harness replay a simulated week
 * against a {@code SimulatedClock} in seconds, and it is why R-1, R-3, R-6 and R-7 are measured
 * against the same classes the container runs rather than against a test-only copy of them.
 *
 * <p>A cycle runs in two phases, and the order is load-bearing: every room's residual is computed
 * first, and only then does any room decide anything. The common-mode residual is a count over
 * all six rooms, so a room that classified before its neighbours had been evaluated would be
 * classifying against last cycle's cross-room picture — which is exactly the signal that
 * separates a blocked filter from a stuck damper.
 */
public final class FaultMonitor {

    private final Map<ZoneId, ZoneMonitor> monitors = new LinkedHashMap<>();
    private final AhuFlowModel flow;
    private final MonitorParameters parameters;
    private final ClockPort clock;
    private final ResidualJournalPort journal;

    private double observedFanPowerWatts = Double.NaN;

    public FaultMonitor(List<VentilationZone> zones, AirHandlingUnit ahu,
            MonitorParameters parameters, ClockPort clock, EventPublisherPort events,
            ResidualJournalPort journal) {
        this.parameters = parameters;
        this.clock = clock;
        this.journal = journal;
        this.flow = new AhuFlowModel(ahu, zones);
        FaultClassifier classifier = new FaultClassifier(parameters);
        for (VentilationZone zone : zones) {
            monitors.put(zone.id(), new ZoneMonitor(zone, flow, parameters, classifier, events, journal));
        }
    }

    /**
     * The single inbound hook, so the boundary has one thing to wire its four subscriptions to —
     * the three topics the brief named plus the fan-speed command, for the reason
     * {@link AhuFlowModel#fanSpeedBelieved} gives.
     * Events the monitor has no business reading are ignored rather than rejected: another
     * component's command on another topic is not an error here.
     */
    public void accept(DomainEvent event) {
        switch (event) {
            case ReadingObserved reading -> {
                ZoneMonitor monitor = monitors.get(reading.zone());
                if (monitor != null) {
                    monitor.readingObserved(reading);
                    journal.append(reading, ZoneMonitor.SOURCE);
                }
            }
            case FanPowerObserved power -> {
                observedFanPowerWatts = power.watts().watts();
                journal.append(power, ZoneMonitor.SOURCE);
            }
            case CommandIssued command -> commandIssued(command);
            case FanSpeedCommanded speed -> {
                // Not one of the three topics the brief named, and read-only like the rest: the
                // supervisor owns the fan, and the monitor has to know what it set or every
                // compensation would look like a blocked filter to the affinity law.
                flow.fanSpeedBelieved(speed.speed());
                journal.append(speed, ZoneMonitor.SOURCE);
            }
            default -> { /* residuals, fault states and strategies are this component's output */ }
        }
    }

    private void commandIssued(CommandIssued command) {
        Instant now = clock.now();
        ZoneMonitor commanded = monitors.get(command.zone());
        if (commanded == null) {
            return;
        }
        for (ZoneMonitor monitor : monitors.values()) {
            if (monitor == commanded) {
                monitor.commandObserved(command, now);
            } else {
                monitor.neighbourCommanded(command);
            }
        }
        journal.append(command, ZoneMonitor.SOURCE);
    }

    /** One monitor cycle over every room. */
    public void runCycle() {
        Instant now = clock.now();
        for (ZoneMonitor monitor : monitors.values()) {
            monitor.evaluate(now);
        }
        int elevated = (int) monitors.values().stream().filter(ZoneMonitor::elevated).count();
        long pinnedBefore = pinnedDampers();
        for (ZoneMonitor monitor : monitors.values()) {
            monitor.decide(signalsFor(monitor, elevated, now), now);
        }
        // A confirmation that pins a damper changes what every room's airflow is believed to be,
        // because ADR-0008's split is a ratio over all six openings. The rooms that did not cause
        // it have to be re-anchored, or their statistics go on accumulating against a building the
        // model no longer describes — which is where half of R-3's false confirmations came from.
        // See ZoneMonitor.flowBeliefChanged and ADR-0010.
        if (pinnedDampers() > pinnedBefore) {
            for (ZoneMonitor monitor : monitors.values()) {
                monitor.flowBeliefChanged(now);
            }
        }
    }

    private long pinnedDampers() {
        return monitors.keySet().stream().filter(flow::believesUnresponsive).count();
    }

    private FaultSignals signalsFor(ZoneMonitor monitor, int elevated, Instant now) {
        double believed = flow.believedFanPowerWatts();
        double ratio = Double.isNaN(observedFanPowerWatts) || believed <= 0
                ? 1.0
                : observedFanPowerWatts / believed;
        return new FaultSignals(monitor.zone().id(), monitor.lastMeasuredPpm(),
                monitor.lastResidualPpm(),
                monitor.cusum().value(), monitor.lowerCusum().value(), monitor.lowSide(),
                elevated, monitors.size(), ratio, monitor.stepResponse(),
                monitor.overnightFloorPpm(), ObservedAt.of(now));
    }

    /**
     * The explicit service exit from a latched case (T-18), as reached from
     * {@code service/{zone}}. A zone this monitor does not watch is ignored for the reason
     * {@link ZoneMonitor#serviced} gives: the request was checked where it arrived.
     */
    public void serviced(ZoneId zone) {
        ZoneMonitor monitor = monitors.get(zone);
        if (monitor != null) {
            monitor.serviced(clock.now());
        }
    }

    /** See {@link AhuFlowModel#fanSpeedBelieved(FanSpeed)} for why this is not a subscription. */
    public void fanSpeedBelieved(FanSpeed speed) {
        flow.fanSpeedBelieved(speed);
    }

    /**
     * Closes the belief gap after a blocked filter has been diagnosed, so that the monitor stops
     * predicting air the plant is not delivering and the five healthy rooms stop looking faulty
     * for the plant's reason.
     */
    public void blockageDiagnosed(double blockage) {
        flow.blockageDiagnosed(blockage);
    }

    public MonitorState stateOf(ZoneId zone) {
        return monitors.get(zone).state();
    }

    /** The open case for a room, or {@code null} when it has none. */
    public FaultCase caseOf(ZoneId zone) {
        return monitors.get(zone).openCase();
    }

    public CusumStatistic cusumOf(ZoneId zone) {
        return monitors.get(zone).cusum();
    }

    /** The lower CUSUM arm of a room, as a magnitude (T-32 section 5). */
    public CusumStatistic lowerCusumOf(ZoneId zone) {
        return monitors.get(zone).lowerCusum();
    }

    public double residualOf(ZoneId zone) {
        return monitors.get(zone).lastResidualPpm();
    }

    /**
     * What the last commanded step told the monitor about a room's damper — the third signal of
     * the signature table, and the one whose availability at classification time
     * {@link ClassifyWhen} is about. Public because {@code ProbeOrderExperiment} measures the
     * ordering and cannot report whether a diagnosis had the signal by inferring it from the
     * classification it produced.
     */
    public StepResponse stepResponseOf(ZoneId zone) {
        return monitors.get(zone).stepResponse();
    }

    /** Whether a room's latched case is still withholding its diagnosis (T-35). */
    public boolean classificationDeferredOf(ZoneId zone) {
        return monitors.get(zone).classificationDeferred();
    }

    /**
     * A room's trailing-day minimum reading — the overnight floor the classifier's sensor-drift leaf
     * tests. Public because T-15 reports it at the instant a case confirms: the leaf needs the floor
     * to have seen a day of the fault, and whether it has is the difference between a signal the tree
     * can use and one that is merely defined.
     */
    public double overnightFloorOf(ZoneId zone) {
        return monitors.get(zone).overnightFloorPpm();
    }

    /** A room's observer, for the tests that measure the belief rather than the state machine. */
    ZoneObserver observerOf(ZoneId zone) {
        return monitors.get(zone).observer();
    }

    /** The newest usable reading a room has, for the tests. */
    double lastMeasuredOf(ZoneId zone) {
        return monitors.get(zone).lastMeasuredPpm();
    }

    /**
     * The shared AHU belief, so that R-7's isolation tests can assert on what the monitor believes
     * about the plant and not only on what it concluded about the rooms. A belief that drifts from
     * the plant is the defect {@link AhuFlowModel} documents, so it has to be observable.
     */
    public AhuFlowModel flowModel() {
        return flow;
    }

    public List<ZoneId> zones() {
        return List.copyOf(monitors.keySet());
    }

    public MonitorParameters parameters() {
        return parameters;
    }

    // ----- ADR-0012: what survives a restart -------------------------------------------------

    /**
     * What this monitor believes, as one immutable value the calling thread publishes.
     *
     * <p>A value and not a live view, deliberately. The boundary that stores it encodes on the
     * thread it was handed the snapshot on, and nothing inside the snapshot can move underneath it
     * afterwards — the rule ADR-0011 sets for the telemetry gauges, here for the same reason: a
     * store that reads a live reference records a belief that was never held.
     */
    public DiagnosisSnapshot snapshot() {
        List<LatchedCase> latched = new ArrayList<>();
        Map<ZoneId, DamperPosition> positions = new LinkedHashMap<>();
        Set<ZoneId> pinned = new LinkedHashSet<>();
        for (Map.Entry<ZoneId, ZoneMonitor> watched : monitors.entrySet()) {
            LatchedCase open = watched.getValue().latched();
            if (open != null) {
                latched.add(open);
            }
            positions.put(watched.getKey(), flow.positionOf(watched.getKey()));
            if (flow.believesUnresponsive(watched.getKey())) {
                pinned.add(watched.getKey());
            }
        }
        return new DiagnosisSnapshot(latched, flow.believedFanSpeed(), positions, pinned);
    }

    /**
     * Resumes the belief an earlier run of this process left behind, so a restarted monitor comes
     * back holding its latched diagnoses and its pinned dampers instead of starting cold.
     *
     * <p>Called once, from the {@code boundary}, before anything is subscribed — never from a
     * constructor and never from {@code control}. That is not style: the evaluation harness
     * constructs this class directly against a simulated clock and must never recover, because
     * every published R-1, R-3, R-6 and R-7 figure comes off these classes.
     *
     * <h2>Why the overnight floor is not restored, which was a judgement call</h2>
     *
     * <p>{@link OvernightFloor} is the slowest state here to re-earn — a 24 h trailing window, one
     * sample a cycle — and it is the one item in this snapshot that was genuinely arguable. It is
     * not stored, for three reasons that are all facts about this code rather than preferences.
     *
     * <p>First, its absence already has a designed meaning and a designed reader. The floor answers
     * {@code NaN} until the window fills, {@code FaultClassifier} reads {@code NaN} as "no evidence
     * either way" rather than as a healthy floor, and so a restarted monitor is in exactly the state
     * every cold start is in for its first day — a state the shipped detector already runs in and
     * the measured results already cover. Nothing is latched or cleared on it; it sharpens one leaf
     * of the tree, the sensor-drift leaf, and only for a case confirmed inside the first day back.
     * The latched cases this record is about come back already named, so the floor's loss cannot
     * unmake a diagnosis — at most it costs the naming of a <em>new</em> one its third signal, which
     * is the same price the first day of any run pays.
     *
     * <p>Second, the state machine already treats a fresh start of a case as a reason to re-earn
     * the window: {@code ZoneMonitor.restart} calls {@code OvernightFloor.clear()} on service. A
     * restart is at least as large a discontinuity as a repair, so clearing it is the behaviour
     * already chosen for the analogous event rather than a new one invented here.
     *
     * <p>Third, the cost is the one thing ADR-0012's choice of a file over a store cannot afford.
     * The window is 24 h at one sample per cycle — 1,440 samples a room, 8,640 over six — and this
     * document is rewritten whole on every change, so storing them turns a two-kilobyte
     * whole-object write into something near half a megabyte. The compacted alternative is worse
     * than that bargain looks: a sliding-window minimum cannot be reduced without keeping the
     * samples that may yet become the minimum, so "compacted" means a monotonic candidate deque —
     * a new structure in {@code control}, existing only to make a {@code boundary} concern cheaper,
     * which is precisely the abstraction this project's rules say not to add.
     *
     * <p>So the accepted consequence, stated rather than hidden: for 24 h after a restart the
     * sensor-drift leaf has no floor to test, and a case confirmed in that window is named on two
     * of its three signals.
     */
    public void recover(DiagnosisSnapshot resumed) {
        // Every zone is resolved against this monitor before anything is mutated. A document
        // written by a process configured for a different zone set names rooms this one does not
        // watch, and throwing before the first mutation is what lets the boundary turn that into a
        // cold start rather than a half-recovered monitor.
        resumed.believedPositions().keySet().forEach(this::requireWatched);
        resumed.unresponsiveDampers().forEach(this::requireWatched);
        resumed.cases().forEach(open -> requireWatched(open.zone()));
        Instant now = clock.now();
        // The flow belief first, because a zone monitor recovering a STUCK_DAMPER case is relying
        // on its damper already being pinned: the pin is what its confirmation set, and the five
        // healthy rooms' residuals are computed from the same shared model.
        flow.recover(resumed.believedFanSpeed(), resumed.believedPositions(),
                resumed.unresponsiveDampers());
        resumed.cases().forEach(open -> monitors.get(open.zone()).recover(open, now));
    }

    private void requireWatched(ZoneId zone) {
        if (!monitors.containsKey(zone)) {
            throw new IllegalArgumentException("not a monitored zone: " + zone.value());
        }
    }
}
