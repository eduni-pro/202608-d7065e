package se.ltu.d7065e.evaluation.control;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import se.ltu.d7065e.diagnosis.boundary.ServiceRequests;
import se.ltu.d7065e.diagnosis.control.FaultMonitor;
import se.ltu.d7065e.diagnosis.control.MonitorParameters;
import se.ltu.d7065e.diagnosis.entity.CaseId;
import se.ltu.d7065e.evaluation.boundary.CountingJournal;
import se.ltu.d7065e.evaluation.boundary.RecordingOverlay;
import se.ltu.d7065e.evaluation.boundary.RecordingPublisher;
import se.ltu.d7065e.physics.control.BuildingSimulation;
import se.ltu.d7065e.physics.control.OccupancyScheduler;
import se.ltu.d7065e.physics.control.OccupancySource;
import se.ltu.d7065e.physics.entity.InjectedFault;
import se.ltu.d7065e.physics.entity.PhysicsSnapshot;
import se.ltu.d7065e.shared.boundary.SimulatedClock;
import se.ltu.d7065e.shared.event.CommandIssued;
import se.ltu.d7065e.shared.event.DomainEvent;
import se.ltu.d7065e.shared.event.FanPowerObserved;
import se.ltu.d7065e.shared.event.FanSpeedCommanded;
import se.ltu.d7065e.shared.event.FaultStateChanged;
import se.ltu.d7065e.shared.event.ReadingObserved;
import se.ltu.d7065e.shared.event.ServiceReported;
import se.ltu.d7065e.shared.event.StrategyChanged;
import se.ltu.d7065e.supervision.control.PlantState;
import se.ltu.d7065e.supervision.control.SupervisionService;
import se.ltu.d7065e.ventilation.control.ControlSettings;
import se.ltu.d7065e.ventilation.control.DcvControlLoop;
import se.ltu.d7065e.ventilation.entity.DamperPosition;
import se.ltu.d7065e.ventilation.entity.ObservedAt;
import se.ltu.d7065e.ventilation.entity.Quality;
import se.ltu.d7065e.ventilation.entity.Topology;
import se.ltu.d7065e.ventilation.entity.ZoneId;

/**
 * The four containers in one JVM on a {@link SimulatedClock}: {@code physics-simulator},
 * {@code dcv-controller}, {@code fault-monitor} and {@code supervisor}, wired the way
 * {@code docker-compose.yml} wires them but with events handed between them directly instead of
 * through MQTT, and with the hot store replaced by a counter.
 *
 * <p>Nothing here re-implements any of them. Every class that decides anything is the class the
 * container runs, so a number this harness produces is a number about the deployed code — which
 * is the only reason the R-1…R-7 evidence is worth anything. What the harness owns is the
 * plumbing the broker and the scheduler own in production: the order of a tick, which events
 * reach which subscriber, the ability to stop feeding one component (T-27), and — through
 * {@link #reportServiced} — the one inbound topic that carries an operator's act rather than a
 * measurement.
 *
 * <h2>The tick</h2>
 *
 * One tick is one minute of simulated time and one monitor cycle, in this order:
 * <ol>
 *   <li>the plant advances a minute and reports true and measured state;</li>
 *   <li>damper feedback reaches the supervisor's plant picture — the BuildSim-wired case
 *       {@link PlantState} describes, without which the supervisor would believe a frozen damper
 *       sits where the inner loop last commanded it rather than where it actually stuck;</li>
 *   <li>six CO₂ readings and one fan-power reading go to the controller, the monitor and the
 *       supervisor;</li>
 *   <li>the inner loop ticks and its damper commands reach the plant, the monitor and the
 *       supervisor;</li>
 *   <li>the monitor runs one cycle;</li>
 *   <li>everything the monitor and the supervisor published is delivered.</li>
 * </ol>
 *
 * <p>The tick is 60 s rather than the container's 1 s, so a simulated week is 10 080 steps
 * instead of 604 800. The cost is the forward-Euler step: at 60 s against the shortest room time
 * constant {@code V/Q} of about 65 min the explicit scheme runs an effective time constant
 * {@code dt/2τ} ≈ 0.8 % long, which shifts no steady state at all and at most a few ppm of any
 * transient — well under the 30 ppm sensor noise. Occupancy is sampled per minute, which is the
 * resolution {@link OccupancyScheduler} has.
 */
public final class SystemHarness {

    /** A Monday, so the occupancy schedules' working week starts at tick zero. */
    public static final Instant START = Instant.parse("2026-10-05T00:00:00Z");

    public static final Duration TICK = Duration.ofSeconds(60);

    private final SimulatedClock clock = new SimulatedClock(START);
    private final BuildingSimulation plant;
    private final DcvControlLoop innerLoop;
    private final FaultMonitor monitor;
    private final ServiceRequests serviceRequests;
    private final SupervisionService supervisor;
    private final PlantState supervisorPlant = PlantState.ofTopology();
    private final RecordingPublisher events;
    private final RecordingOverlay overlay = new RecordingOverlay();
    private final CountingJournal journal = new CountingJournal();
    private final List<CommandIssued> damperCommands = new ArrayList<>();

    private boolean innerLoopRunning = true;
    private boolean monitorRunning = true;
    private boolean supervisorRunning = true;
    private boolean readingsFlowing = true;
    private boolean actuatorApplying = true;
    private boolean damperFeedback = true;
    private PhysicsSnapshot latest;

    public SystemHarness(long seed) {
        this(seed, MonitorParameters.defaults(), new OccupancyScheduler(seed));
    }

    public SystemHarness(long seed, MonitorParameters parameters) {
        this(seed, parameters, new OccupancyScheduler(seed));
    }

    public SystemHarness(long seed, MonitorParameters parameters, OccupancySource occupancy) {
        this.plant = new BuildingSimulation(clock, TICK, seed, occupancy);
        this.events = new RecordingPublisher(this::route);
        // The sample interval the controller measures freshness against is the harness tick, so
        // the inner loop judges a reading from this tick fresh. The thresholds are the plan's.
        this.innerLoop = new DcvControlLoop(ControlSettings.band(
                ControlSettings.DEFAULT_OPEN_ABOVE.value(),
                ControlSettings.DEFAULT_CLOSE_BELOW.value(), TICK), clock);
        this.monitor = new FaultMonitor(Topology.zones(), Topology.airHandler(), parameters,
                clock, events, journal);
        this.serviceRequests = new ServiceRequests(monitor);
        this.supervisor = new SupervisionService(
                supervisorPlant, events, overlay, journal, clock);
    }

    // ----- what the experiments read ---------------------------------------------------------

    public FaultMonitor monitor() {
        return monitor;
    }

    public SupervisionService supervisor() {
        return supervisor;
    }

    public DcvControlLoop innerLoop() {
        return innerLoop;
    }

    public RecordingPublisher events() {
        return events;
    }

    public RecordingOverlay overlay() {
        return overlay;
    }

    public Instant now() {
        return clock.now();
    }

    public PhysicsSnapshot latest() {
        return latest;
    }

    /** Every damper command the harness carried, inner loop and supervisory probe alike. */
    public List<CommandIssued> damperCommands() {
        return List.copyOf(damperCommands);
    }

    // ----- killing a component, which is what T-27 is -----------------------------------------

    /** Stops the reading stream: the in-JVM equivalent of killing {@code co2-sensor} (R-4). */
    public void readingsFlowing(boolean flowing) {
        this.readingsFlowing = flowing;
    }

    /** Stops the monitor's cycle and its inputs: killing {@code fault-monitor}. */
    public void monitorRunning(boolean running) {
        this.monitorRunning = running;
    }

    /** Stops the supervisor seeing or doing anything: killing {@code supervisor}. */
    public void supervisorRunning(boolean running) {
        this.supervisorRunning = running;
    }

    /** Stops the inner loop commanding: killing {@code dcv-controller}. */
    public void innerLoopRunning(boolean running) {
        this.innerLoopRunning = running;
    }

    /**
     * Turns the damper feedback of step 2 off, leaving the supervisor to infer a frozen damper's
     * position from the last command the inner loop issued — which is what the deployed system
     * does, since no damper position is published on the broker. The harness defaults to feedback
     * on, the BuildSim-wired case {@link PlantState} describes, and this switch exists so that any
     * result can be shown not to depend on the choice.
     */
    public void damperFeedback(boolean wired) {
        this.damperFeedback = wired;
    }

    /**
     * Stops commands reaching the plant while they are still published: killing
     * {@code damper-actuator}. The commands are issued and observed, and the air does not move.
     */
    public void actuatorApplying(boolean applying) {
        this.actuatorApplying = applying;
    }

    // ----- driving ---------------------------------------------------------------------------

    public void inject(InjectedFault fault) {
        plant.inject(fault);
    }

    /** The repair itself: the equipment works again. Reporting it is {@link #reportServiced}. */
    public void repair() {
        plant.clearFaults();
    }

    /**
     * An operator's repair report, delivered to the monitor's own {@code service/{zone}} adapter so
     * that it is validated and refused exactly as the deployed monitor would — the harness standing
     * in for the broker again, as it does for {@link #command}.
     */
    public void reportServiced(ZoneId zone, CaseId caseId, String reason) {
        serviceRequests.accept(
                new ServiceReported(zone, caseId, ObservedAt.of(clock.now()), reason));
        events.drain();
    }

    /** One minute of simulated time and one monitor cycle. */
    public PhysicsSnapshot tick() {
        PhysicsSnapshot snapshot = advance();
        latest = snapshot;
        Instant now = snapshot.at();
        if (supervisorRunning && damperFeedback) {
            for (ZoneId zone : Topology.ZONE_IDS) {
                supervisorPlant.observeAppliedPosition(zone, snapshot.appliedPosition(zone));
            }
        }
        if (readingsFlowing) {
            for (ZoneId zone : Topology.ZONE_IDS) {
                ReadingObserved reading = new ReadingObserved(
                        zone, snapshot.measuredCo2(zone), ObservedAt.of(now), Quality.GOOD);
                innerLoop.observe(reading);
                if (monitorRunning) {
                    monitor.accept(reading);
                }
                if (supervisorRunning) {
                    supervisor.accept(reading);
                }
            }
            if (monitorRunning) {
                monitor.accept(new FanPowerObserved(Topology.AIR_HANDLER, snapshot.fanPower(),
                        ObservedAt.of(now), Quality.GOOD));
            }
        }
        if (innerLoopRunning) {
            innerLoop.tick().forEach(this::applyDamperCommand);
        }
        if (monitorRunning) {
            monitor.runCycle();
        }
        events.drain();
        return snapshot;
    }

    public void tickFor(Duration duration) {
        for (long tick = 0, ticks = duration.toMinutes(); tick < ticks; tick++) {
            tick();
        }
    }

    /** Ticks until the predicate holds or the budget runs out; -1 when it never held. */
    public int tickUntil(Duration budget, java.util.function.BooleanSupplier until) {
        for (int tick = 1, ticks = (int) budget.toMinutes(); tick <= ticks; tick++) {
            tick();
            if (until.getAsBoolean()) {
                return tick;
            }
        }
        return -1;
    }

    /**
     * A damper command from outside the inner loop: the harness standing in for the supervisory
     * step T-24's protocol needs, so the monitor sees exactly what it would see on
     * {@code commands/{zone}/damper}.
     */
    public CommandIssued command(ZoneId zone, double position, String reason) {
        CommandIssued command = new CommandIssued(zone, DamperPosition.of(position),
                ObservedAt.of(clock.now()), reason,
                se.ltu.d7065e.ventilation.entity.CommandId.newId());
        applyDamperCommand(command);
        events.drain();
        return command;
    }

    // ----- the broker's job ------------------------------------------------------------------

    private void applyDamperCommand(CommandIssued command) {
        damperCommands.add(command);
        if (actuatorApplying) {
            plant.commandDamper(command.zone(), command.position());
        }
        if (monitorRunning) {
            monitor.accept(command);
        }
        if (supervisorRunning) {
            supervisor.accept(command);
        }
    }

    private void route(String topic, DomainEvent event) {
        switch (event) {
            case FaultStateChanged fault -> {
                if (supervisorRunning) {
                    supervisor.accept(fault);
                }
            }
            case StrategyChanged strategy -> innerLoop.applyStrategy(strategy);
            case CommandIssued probe -> {
                // Only the supervisor's probe arrives here; the inner loop's commands are applied
                // where they are produced, because the monitor has to see them in the same cycle.
                if (actuatorApplying) {
                    plant.commandDamper(probe.zone(), probe.position());
                }
                if (monitorRunning) {
                    monitor.accept(probe);
                }
                damperCommands.add(probe);
            }
            case FanSpeedCommanded speed -> {
                if (actuatorApplying) {
                    plant.commandFanSpeed(speed.speed());
                }
                if (monitorRunning) {
                    monitor.accept(speed);
                }
            }
            default -> {
                // Residuals are the monitor's output and nothing in the harness subscribes.
            }
        }
    }

    private PhysicsSnapshot advance() {
        try {
            return plant.advance();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("the simulated clock does not block, so this cannot "
                    + "happen; something replaced the clock", e);
        }
    }
}
